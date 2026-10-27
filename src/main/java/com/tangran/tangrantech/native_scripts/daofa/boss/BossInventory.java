package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.util.AddonPaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * 二阶段的「背包备份 / 返还」。
 *
 * <h2>为什么它必须和战局层同批落地</h2>
 * <p>二阶段开始时会<b>清空玩家背包</b>并发一套挑战装备，结束时再还回去。
 * 这一路上有<b>三条会永久吞掉玩家背包</b>的路径，原脚本都做了防护，
 * 本类必须把它们一并实现 —— 否则「战局状态是纯内存的」这件事一旦碰上服务器重启，
 * 玩家就是真丢东西：</p>
 * <ol>
 *   <li><b>重启 / 重载后备份文件还在，但内存里的战局没了</b> →
 *       启动时扫目录把没归还的备份还给在线的本人（{@link #restoreOrphans()}）。</li>
 *   <li><b>上一轮备份没归还，这一轮又要备份</b> → 直接写就会把「已被清空的背包」
 *       覆盖掉旧备份，旧物品永久消失 ⇒ {@link #beginBackup} 发现旧文件时
 *       <b>先返还，返还失败就拒绝备份</b>（宁可这次挑战开不起来，也不能吞背包）。</li>
 *   <li><b>玩家在挑战中退出 / 死亡</b> → {@code isOnline()} / {@code isDead()} 时不返还，
 *       留到下次上线由启动扫描补还（而不能在这里删文件）。</li>
 * </ol>
 *
 * <h2>与原脚本逐字一致的两点</h2>
 * <ul>
 *   <li>备份的是 {@code player.getInventory().getContents()}，即<b>主背包 36 格</b>；
 *       盔甲与副手不在其中（挑战期间保留给玩家）—— 这是原脚本的既定行为，不改。</li>
 *   <li>序列化格式：{@code BukkitObjectOutputStream} 写 {@code int 长度} 再逐个 {@code writeObject}，
 *       整体 Base64 后存成 {@code {"base64": "..."}} 一行 JSON。这样<b>老备份文件仍然读得回来</b>。</li>
 * </ul>
 */
public final class BossInventory {

    private BossInventory() {}

    /** 备份文件名前缀（原脚本 {@code phase2_backup_}）。 */
    private static final String PREFIX = "phase2_backup_";

    /** 备份文件（与进度存档同目录）。 */
    public static File backupFile(String playerName) {
        String who = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        return new File(AddonPaths.configDir(BossProgress.DIR), PREFIX + PlayerKeys.key(who) + ".json");
    }

    /**
     * 备份背包并清空（原脚本 {@code beginPhaseTwoInventoryBackup}）。
     *
     * @return 备份成功返回 true；检测到未归还的旧备份且无法返还时返回 false（<b>不覆盖</b>）
     */
    public static boolean beginBackup(Player player, BossState state) {
        try {
            if (player == null || state == null) return false;
            if (state.phaseTwo.inventorySaved) return true;

            // ★ 关键保护：存在上一轮未归还的备份时，先还给玩家；还不了就绝不覆盖。
            File old = backupFile(player.getName());
            if (old.isFile()) {
                if (!restore(player)) {
                    BossLog.warn("检测到未归还的二阶段背包备份，且当前无法返还，已阻止本次备份覆盖"
                            + "（玩家 " + player.getName() + "）");
                    return false;
                }
            }

            ItemStack[] contents = player.getInventory().getContents();
            byte[] raw;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos)) {
                out.writeInt(contents.length);
                for (ItemStack item : contents) {
                    out.writeObject(item);
                }
                out.flush();
                raw = baos.toByteArray();
            }
            String encoded = Base64.getEncoder().encodeToString(raw);

            File file = backupFile(player.getName());
            // 用 Json.write 而不是手拼字符串：内容里可能含换行/引号（Base64 不会，
            // 但统一走工具类是硬约定——报告第二节「脚本原生化的五条硬约定」）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("base64", encoded);
            if (!AddonPaths.writeJson(file, data)) {
                BossLog.warn("二阶段背包备份写入失败：" + file.getAbsolutePath());
                return false;
            }

            player.getInventory().clear();
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
            state.phaseTwo.inventorySaved = true;
            state.phaseTwo.inventoryFile = file;
            return true;
        } catch (Throwable t) {
            BossLog.warn("二阶段背包备份失败: " + t);
            return false;
        }
    }

    /**
     * 返还背包并删除备份文件（原脚本 {@code restorePhaseTwoInventory}）。
     *
     * @return 真的返还了返回 true
     */
    public static boolean restore(Player player) {
        try {
            if (player == null || !player.isOnline() || player.isDead()) return false;
            return restore(player, backupFile(player.getName()));
        } catch (Throwable t) {
            BossLog.warn("二阶段背包返还失败: " + t);
            return false;
        }
    }

    /** 从指定文件返还。 */
    private static boolean restore(Player player, File file) {
        try {
            if (file == null || !file.isFile()) return false;
            Map<String, Object> data = AddonPaths.readJson(file);
            Object b64 = (data == null) ? null : data.get("base64");
            if (b64 == null) return false;

            byte[] raw = Base64.getDecoder().decode(String.valueOf(b64));
            ItemStack[] contents;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(raw);
                 BukkitObjectInputStream in = new BukkitObjectInputStream(bais)) {
                int size = in.readInt();
                contents = new ItemStack[size];
                for (int i = 0; i < size; i++) {
                    Object o = in.readObject();
                    contents[i] = (o instanceof ItemStack stack) ? stack : null;
                }
            }

            player.getInventory().clear();
            player.getInventory().setContents(contents);
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
            // 只有在「返还成功」之后才删档 —— 顺序反了就是丢背包
            if (!AddonPaths.deleteDoc(file)) {
                BossLog.warn("备份存档删除失败（下次启动会再还一次）: " + file.getAbsolutePath());
            }
            try {
                player.sendMessage("§a备份背包已返还。");
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable t) {
            BossLog.warn("二阶段背包返还失败: " + t);
            return false;
        }
    }

    /**
     * 启动扫描：把「没归还的备份」还给在线的本人（原脚本 {@code xuRestoreOrphanBackups}）。
     *
     * <p>正在挑战的人要跳过 —— 那份备份他还在用，收场时才会返还。</p>
     *
     * <p><b>还有一个更隐蔽的情形必须挡住</b>：脚本重载 / 服务器重启后，
     * 内存里的战局表是空的，{@link BossFight#isActive} 对谁都说 false ——
     * 所以只靠战局表判断，就会把「正在挑战的那位玩家」的备份当成孤儿还给他，
     * 于是这场战斗结束时无背包可还。原脚本为此加了「场上还有活着的挑战本体就整体跳过」
     * 这道闸门（{@link BossArena#hasLiveBossAnywhere()}），这里照做。</p>
     *
     * @return 返还成功的人数
     */
    public static int restoreOrphans() {
        int done = 0;
        try {
            // 先看看有没有备份文件 —— 没有就立刻返回。
            // 下面的安全闸门要遍历全服实体，绝大多数服务器根本没有备份文件，
            // 不该为了「什么都没发生」白扫一遍世界。
            List<File> candidates = new ArrayList<>();
            File dir = AddonPaths.configDir(BossProgress.DIR);
            // 后端是 SQLite，枚举走文档 API（listDocs 会把库里与还没入库的旧文件一起列出）
            for (File f : AddonPaths.listDocs(dir)) {
                if (f == null) continue;
                String name = f.getName();
                if (!name.startsWith(PREFIX) || !name.endsWith(".json")) continue;
                String who = name.substring(PREFIX.length(), name.length() - ".json".length());
                if (!who.isEmpty()) candidates.add(f);
            }
            if (candidates.isEmpty()) return 0;

            // ★ 安全闸门：场上有活着的本体 → 这些备份都还在服役，一律不动
            if (BossArena.hasLiveBossAnywhere()) {
                BossLog.warn("检测到场上仍有活着的挑战本体，跳过备份返还（避免吞掉正在挑战的玩家的背包）");
                return 0;
            }

            for (File f : candidates) {
                try {
                    String name = f.getName();
                    String who = name.substring(PREFIX.length(), name.length() - ".json".length());
                    Player p = resolveOnline(who);
                    if (p == null) continue;
                    // 正在挑战的人：这份备份还要用，收场时才返还
                    if (BossFight.isActive(p)) continue;
                    if (restore(p)) done++;
                } catch (Throwable ignored) {
                    // 单个文件失败不影响其它玩家
                }
            }
            if (done > 0) {
                BossLog.warn("启动自清理：把未归还的备份背包还给玩家 " + done + " 份");
            }
        } catch (Throwable t) {
            BossLog.warn("启动扫描备份失败: " + t);
        }
        return done;
    }

    /**
     * 备份键 → 在线玩家：新键是 uuid（{@link java.util.UUID#fromString} 能解析就按 uuid 找），
     * 旧名键兜底按名字找（迁移期两种文件可能并存）。
     */
    private static Player resolveOnline(String key) {
        try {
            return Bukkit.getPlayer(java.util.UUID.fromString(key));
        } catch (IllegalArgumentException notUuid) {
            return Bukkit.getPlayerExact(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
