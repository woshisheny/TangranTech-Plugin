package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.Json;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 「道法/全解」的原生实现（对应 {@code scripts/道法/全解.js}）。两个管理员一次性物品。
 *
 * <p><b>{@code TR_DLQJ}（丹类全解）</b>：右键后写出两份存档并消耗一个——</p>
 * <ul>
 *   <li>{@code addon_configs/糖然科技/丹方权限/<玩家名>.json} = <code>{owned:[Tr_dy01..09], exp:2000}</code>（丹帝）；</li>
 *   <li>{@code addon_configs/糖然科技/异火权限/<玩家名>.json} = <code>{owned:[TR_YH01..09], selected:""}</code>。</li>
 * </ul>
 *
 * <p><b>{@code TR_JJQJ}（境界全解）</b>：右键后写出
 * {@code addon_configs/糖然科技/境界/<玩家名>.json}，直接开辟境界并写到糖帝（realm 5）：
 * 9 项属性进度各 2528、糖之力 2628。其中 {@code Tr_dy01}（糖之力突破本身，决定上限）
 * 默认<b>不</b>开启，其余 8 项默认开启。</p>
 *
 * <p>文件已存在时直接覆盖改写，与原脚本一致。存档路径沿用原 RSC 版的相对路径，
 * 保证老档数据不失效。</p>
 */
public final class FullAnalysisScript implements NativeLifecycleScript {

    /** 丹类全解物品。 */
    private static final String DAN_ITEM_ID = "TR_DLQJ";
    /** 境界全解物品。 */
    private static final String JINGJIE_ITEM_ID = "TR_JJQJ";

    /** 存档根目录（相对插件数据目录）。 */
    private static final String BASE_PATH = "addon_configs/糖然科技";
    private static final String DANFANG_DIR = BASE_PATH + "/丹方权限";
    private static final String YANHUO_DIR = BASE_PATH + "/异火权限";
    private static final String JINGJIE_DIR = BASE_PATH + "/境界";

    /** 9 种丹（丹方权限的键）。 */
    private static final String[] DAN_IDS = {
            "Tr_dy01", "Tr_dy02", "Tr_dy03", "Tr_dy04", "Tr_dy05",
            "Tr_dy06", "Tr_dy07", "Tr_dy08", "Tr_dy09",
    };
    /** 9 种异火。 */
    private static final String[] YANHUO_IDS = {
            "TR_YH01", "TR_YH02", "TR_YH03", "TR_YH04", "TR_YH05",
            "TR_YH06", "TR_YH07", "TR_YH08", "TR_YH09",
    };

    /** 丹道熟练度（丹帝）。 */
    private static final int DANFANG_EXP = 2000;
    /** 境界全解：每项属性进度。 */
    private static final int ATTR_VALUE = 2528;
    /** 糖之力（= 100 + Tr_dy01 进度）。 */
    private static final int SUGAR_POWER = 2628;
    /** 境界等级：5 = 糖帝。 */
    private static final int REALM_SUGAR_EMPEROR = 5;

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"onUse".equals(fn)) return null;
        if (args.length == 0 || !(args[0] instanceof PlayerInteractEvent event)) {
            return Boolean.FALSE;
        }
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                return Boolean.FALSE;
            }

            Player player = event.getPlayer();
            String id = heldItemId(player);
            if (id == null) return Boolean.FALSE;

            if (DAN_ITEM_ID.equals(id)) {
                event.setCancelled(true);
                if (applyAllDan(player)) consumeOne(player);
                return Boolean.TRUE;
            }
            if (JINGJIE_ITEM_ID.equals(id)) {
                event.setCancelled(true);
                if (applyAllJingjie(player)) consumeOne(player);
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            log("onUse 异常: " + t);
            return Boolean.FALSE;
        }
    }

    /** 主手物品的粘液 id（大写）；非粘液物品返回 null。 */
    private static String heldItemId(Player player) {
        try {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType().isAir()) return null;
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null) return null;
            return String.valueOf(sf.getId()).toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 丹类全解
    // ------------------------------------------------------------------

    /** 解锁 9 种丹方（丹帝熟练度）+ 9 种异火（selected 置空）。 */
    private static boolean applyAllDan(Player player) {
        try {
            String name = player.getName();

            Map<String, Object> danfang = new LinkedHashMap<>();
            danfang.put("owned", new ArrayList<Object>(List.of(DAN_IDS)));
            danfang.put("exp", DANFANG_EXP);
            boolean ok1 = writeJson(DANFANG_DIR, name, danfang);

            Map<String, Object> yanhuo = new LinkedHashMap<>();
            yanhuo.put("owned", new ArrayList<Object>(List.of(YANHUO_IDS)));
            yanhuo.put("selected", "");
            boolean ok2 = writeJson(YANHUO_DIR, name, yanhuo);

            send(player, "§6§l[丹类全解] §f已解锁 §e9 种丹方§f，丹道熟练度 §e"
                    + DANFANG_EXP + "§f（§6丹帝§f）");
            send(player, "§6§l[丹类全解] §f已解锁 §e9 种异火§f，可打开异火选择界面查看");
            sound(player, "entity.player.levelup", 1.0f, 1.0f);
            sound(player, "block.beacon.activate", 0.7f, 1.6f);

            if (!ok1 || !ok2) {
                send(player, "§c部分存档写入失败，请看服务器后台日志。");
            }
            return ok1 && ok2;
        } catch (Throwable t) {
            log("丹类全解执行异常: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 境界全解
    // ------------------------------------------------------------------

    /** 直接开辟境界并写成糖帝数据。 */
    private static boolean applyAllJingjie(Player player) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            Map<String, Object> enabledAttrs = new LinkedHashMap<>();
            for (int i = 0; i < DAN_IDS.length; i++) {
                attrs.put(DAN_IDS[i], ATTR_VALUE);
                // Tr_dy01 是「糖之力突破」本身（决定上限），默认关闭；其余 8 项默认开启
                if (i > 0) enabledAttrs.put(DAN_IDS[i], Boolean.TRUE);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("opened", Boolean.TRUE);
            data.put("realm", REALM_SUGAR_EMPEROR);
            data.put("sugarPower", SUGAR_POWER);
            data.put("maxSugarPower", SUGAR_POWER);
            data.put("attrs", attrs);
            data.put("enabledAttrs", enabledAttrs);

            boolean ok = writeJson(JINGJIE_DIR, player.getName(), data);

            send(player, "§d§l[境界全解] §f已开辟境界，直接成就 §d§l糖帝 §f境界");
            send(player, "§d§l[境界全解] §f属性进度 §b" + ATTR_VALUE
                    + " §f/ 糖之力 §b" + SUGAR_POWER);
            sound(player, "entity.ender_dragon.growl", 0.8f, 1.4f);
            sound(player, "block.beacon.power_select", 1.0f, 1.2f);

            if (!ok) {
                send(player, "§c存档写入失败，请看服务器后台日志。");
            }
            return ok;
        } catch (Throwable t) {
            log("境界全解执行异常: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 存档 / 消耗 / 提示
    // ------------------------------------------------------------------

    /** 写出 {@code <dir>/<玩家名>.json}（玩家数据 → 统一走 AddonPaths，落到 SQLite 后端）。 */
    private static boolean writeJson(String relDir, String playerName, Map<String, Object> data) {
        try {
            File dir = new File(TT.plugin.getDataFolder(), relDir);
            if (!dir.exists() && !dir.mkdirs()) {
                log("目录创建失败: " + dir.getAbsolutePath());
                return false;
            }
            // ★ 不能直接 Files.write：玩家数据已迁到 SQLite，必须经 AddonPaths 才会写进库
            File file = new File(dir, PlayerKeys.key(playerName) + ".json");
            if (com.tangran.tangrantech.util.AddonPaths.writeJson(file, data)) return true;
            log("写入存档失败 " + playerName + " / " + relDir);
            return false;
        } catch (Throwable t) {
            log("写入存档异常 " + playerName + " / " + relDir + ": " + t);
            return false;
        }
    }

    /** 使用成功后消耗主手 1 个。 */
    private static void consumeOne(Player player) {
        try {
            PlayerInventory inv = player.getInventory();
            ItemStack item = inv.getItemInMainHand();
            if (item == null || item.getType().isAir()) return;
            int amount = item.getAmount();
            if (amount <= 1) {
                inv.setItemInMainHand(null);
            } else {
                item.setAmount(amount - 1);
                inv.setItemInMainHand(item);
            }
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 已废弃接口
            }
            player.playSound(player.getLocation(), "entity.item.break", 0.7f, 1.2f);
        } catch (Throwable t) {
            log("消耗物品失败: " + t);
        }
    }

    private static void log(String msg) {
        try {
            TT.log("[糖然科技/全解] " + msg);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 发送消息（原脚本用 {@code §} 字面量，这里原样透传）。 */
    private static void send(Player player, String legacy) {
        try {
            player.sendMessage(legacy);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void sound(Player player, String key, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), key, volume, pitch);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 右键入口走 ScriptItem → invoke("onUse")，无需自注册监听器。
        return Collections.emptyList();
    }
}
