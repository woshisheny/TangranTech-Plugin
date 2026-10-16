package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * 「糖然牌堆叠机」的存档层：机器方块上的数据读写 + 界面内容的 Base64 持久化。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的存档相关部分
 * （{@code pdSaveMenu} / {@code pdLoadMenu} / {@code pdVoidSavedMenu} 一族 +
 * {@code pdLocalGet/Set} + {@code pdForgetMachine}）。</p>
 *
 * <h2>为什么堆叠机不能像别的机器一样用 menus.yml</h2>
 * <p>原脚本明确写了「本机刻意<b>不</b>写 menus.yml：界面完全由脚本创建」。
 * 所以 Slimefun 那边<b>没有</b> BlockMenu，界面内容无处可放 —— 全部得自己存：
 * 一部分在脚本内存里（开关界面时复用），一部分序列化进<b>机器方块自身的 BlockData</b>
 * （跨 {@code /rsc reload}、跨重启都还在）。</p>
 *
 * <h2>三套防复制机制（都是踩过坑才有的）</h2>
 * <ol>
 *   <li><b>作废标记</b>（{@code MENU_VOID}）：切换机器类型 / 换布局时，老界面里的东西已经
 *       还给玩家、槽位也清空了，但盘上还是老内容 —— 玩家再打开父界面就会把老内容<b>读回来</b>
 *       ⇒ 背包一份 + 界面一份 = 无限刷物。而 {@code removeData} 同样是<b>排队更新</b>
 *       （写完立刻 {@code getData} 可能还是旧值），所以除了 removeData 还要一个作废标记
 *       把「读回」这条路堵死；直到有人真的把当前界面存一次盘才解除作废。</li>
 *   <li><b>只有存进真东西才解除作废</b>：写空盘不该解除（解除后又可能读到旧的复制内容）。</li>
 *   <li><b>死亡标记</b>（{@code DEAD}）：机器被拆掉后禁止再落盘，否则同一坐标放新机器会把
 *       旧内容「复活」= 复制。</li>
 * </ol>
 *
 * <h2>写通缓存 {@code LOCAL}</h2>
 * <p>{@code SlimefunBlockData.setData} 是排队更新，<b>写完立刻 getData 很可能还是旧值</b>
 * （原脚本注释也点明了）。所以凡是「刚写完马上就要读」的键（玩家固定的配方索引）都过一层
 * 内存写通缓存：写的时候同时更新内存，读的时候内存优先。</p>
 */
public final class StackMachineStore {

    private StackMachineStore() {}

    // ==================================================================
    // 机器方块上的数据键（沿用原脚本的键名，老档可直接读）
    // ==================================================================

    /** 识别出的类型（{@code recipe} / {@code matgen} / {@code generator}）。 */
    public static final String DATA_TYPE = "trpddj_type";
    /** 识别出的机器台数（存储物品里可能上万）。 */
    public static final String DATA_COUNT = "trpddj_count";
    /** 被识别的机器 id。 */
    public static final String DATA_MACHINE = "trpddj_machine";
    /** 父界面内容（Base64 序列化）。 */
    public static final String DATA_MENU = "trpddj_menu";
    /** 配置界面内容（含机器槽里那台机器）。 */
    public static final String DATA_CFG = "trpddj_cfg";
    /** 玩家固定选择的配方索引（空 = 自动检测）。 */
    public static final String DATA_RECIPE = "trpddj_recipe";
    /** 能找到网络的坐标锚点（链式进网用）。 */
    public static final String DATA_NET = "trpddj_net";

    // ==================================================================
    // 坐标键
    // ==================================================================

    /** 机器坐标 → 唯一键（{@code 世界|X|Y|Z}）。取不到坐标返回 null。 */
    public static String locKey(Location loc) {
        try {
            if (loc == null || loc.getWorld() == null) return null;
            return loc.getWorld().getName() + "|" + loc.getBlockX()
                    + "|" + loc.getBlockY() + "|" + loc.getBlockZ();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 写通缓存
    // ==================================================================

    /** {@code locKey|数据键 → 值}：刚写完马上要读的键走这里，绕开 BlockData 的排队更新。 */
    private static final Map<String, String> LOCAL = new ConcurrentHashMap<>();

    /** 优先读内存，其次读盘，最后返回默认值。 */
    public static String localGet(Location loc, String key, String def) {
        try {
            String k = locKey(loc) + "|" + key;
            String cached = LOCAL.get(k);
            if (cached != null) return cached;
            String v = getData(loc, key);
            if (v != null && !v.isEmpty()) {
                LOCAL.put(k, v);
                return v;
            }
        } catch (Throwable ignored) {
            // 落到默认值
        }
        return def;
    }

    /** 同时写内存与盘（顺序：先盘后内存，保证内存里的一定是最新意图）。 */
    public static void localSet(Location loc, String key, String value) {
        try {
            String sv = String.valueOf(value);
            setData(loc, key, sv);
            LOCAL.put(locKey(loc) + "|" + key, sv);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // BlockData 读写（薄封装，只为把异常收敛在一处）
    // ==================================================================

    public static String getData(Location loc, String key) {
        try {
            return StorageCacheUtils.getData(loc, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setData(Location loc, String key, String value) {
        try {
            StorageCacheUtils.setData(loc, key, value);
        } catch (Throwable t) {
            TT.log("[堆叠机] 写方块数据失败 key=" + key + ": " + t);
        }
    }

    public static void removeData(Location loc, String key) {
        try {
            StorageCacheUtils.removeData(loc, key);
        } catch (Throwable t) {
            TT.log("[堆叠机] 清方块数据失败 key=" + key + ": " + t);
        }
    }

    // ==================================================================
    // 识别结果：类型 / 台数 / 机器 id
    // ==================================================================

    /** 识别结果落盘（父界面、重启后仍能读到）。 */
    public static void storeType(Location loc, String type, String machineId, long count) {
        if (loc == null) return;
        setData(loc, DATA_TYPE, type == null ? "" : type);
        setData(loc, DATA_MACHINE, machineId == null ? "" : machineId);
        // 注意：Java 没有 String(long) 构造器，必须走 String.valueOf
        setData(loc, DATA_COUNT, String.valueOf(Math.max(1L, Math.round((double) count))));
    }

    /** 识别出的机器台数（直接放机器 = 1；从存储物品里读出来的可能上万）。 */
    public static long getCount(Location loc) {
        try {
            if (loc == null) return 1L;
            String v = getData(loc, DATA_COUNT);
            if (v == null) return 1L;
            double n = Double.parseDouble(v.trim());
            return (Double.isFinite(n) && n >= 1) ? (long) n : 1L;
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    /** 识别出的类型；未识别返回 null。 */
    public static String getType(Location loc) {
        try {
            if (loc == null) return null;
            String v = getData(loc, DATA_TYPE);
            if (v == null) return null;
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 被识别的机器 id；未识别返回 null。 */
    public static String getMachineId(Location loc) {
        try {
            if (loc == null) return null;
            String v = getData(loc, DATA_MACHINE);
            if (v == null) return null;
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 作废标记
    // ==================================================================

    /** {@code locKey|数据键 → true}：盘上这条内容已作废，禁止读回。 */
    private static final Map<String, Boolean> MENU_VOID = new ConcurrentHashMap<>();

    /** 标记某条盘上内容作废，并顺手把盘上的删掉。 */
    public static void voidSavedMenu(Location loc, String dataKey) {
        try {
            String k = locKey(loc);
            if (k == null || dataKey == null) return;
            MENU_VOID.put(k + "|" + dataKey, Boolean.TRUE);
            removeData(loc, dataKey);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 盘上这条内容是否已作废。 */
    public static boolean isSavedMenuVoid(Location loc, String dataKey) {
        try {
            String k = locKey(loc);
            if (k == null) return false;
            return Boolean.TRUE.equals(MENU_VOID.get(k + "|" + dataKey));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解除某条内容的作废标记（只在「真的存进了东西」之后调）。 */
    public static void clearSavedMenuVoid(Location loc, String dataKey) {
        try {
            String k = locKey(loc);
            if (k == null) return;
            MENU_VOID.remove(k + "|" + dataKey);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 解除这台机器两条内容（父界面 + 配置界面）的作废标记。 */
    public static void clearAllSavedMenuVoid(Location loc) {
        clearSavedMenuVoid(loc, DATA_MENU);
        clearSavedMenuVoid(loc, DATA_CFG);
    }

    // ==================================================================
    // 死亡标记（拆机后禁止落盘）
    // ==================================================================

    /** 已拆坐标 → true。 */
    private static final Map<String, Boolean> DEAD = new ConcurrentHashMap<>();

    public static boolean isDeadLoc(Location loc) {
        try {
            String k = locKey(loc);
            return k != null && Boolean.TRUE.equals(DEAD.get(k));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void markDead(Location loc) {
        String k = locKey(loc);
        if (k != null) DEAD.put(k, Boolean.TRUE);
    }

    /** 同坐标又放了新机器（能重新右键打开）→ 解除死亡标记，恢复落盘。 */
    public static void clearDead(Location loc) {
        String k = locKey(loc);
        if (k != null) DEAD.remove(k);
    }

    // ==================================================================
    // 界面内容的序列化
    // ==================================================================

    /**
     * 把界面内容写进机器的 BlockData。
     *
     * <p>图标格（脚本自己画的那几格）<b>不存</b> —— 重开时会重画，存了反而会和玩家塞进去的
     * 东西混淆。空槽写 null，反序列化时跳过。</p>
     *
     * @param special 图标槽位（下标即槽号）；null 表示只按 {@link #isGuiIcon} 过滤
     * @return 是否写成功
     */
    public static boolean saveMenu(Inventory inv, Location loc, String key, int[] special) {
        try {
            if (inv == null || loc == null || key == null) return false;
            if (isDeadLoc(loc)) return false;   // 机器已拆 → 不再落盘（免得同坐标新机器把旧内容复活）

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int size = inv.getSize();
            boolean any = false;
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos)) {
                out.writeInt(size);
                for (int i = 0; i < size; i++) {
                    ItemStack it = inv.getItem(i);
                    if (it != null && it.getType() == Material.AIR) it = null;
                    if (it != null && contains(special, i)) it = null;   // 图标格不存（重画）
                    if (it != null && isGuiIcon(it)) it = null;
                    if (it != null) any = true;
                    out.writeObject(it);
                }
            }
            setData(loc, key, Base64.getEncoder().encodeToString(baos.toByteArray()));
            if (any) clearSavedMenuVoid(loc, key);
            return true;
        } catch (Throwable t) {
            TT.log("[堆叠机] 保存界面内容失败 key=" + key + ": " + t);
            return false;
        }
    }

    // ==================================================================
    // 脏检查落盘（静态审查：发电机每 tick 全界面序列化是全插件最大单点开销）
    // ==================================================================

    /** locKey|key → 上次落盘时的内容指纹。 */
    private static final Map<String, String> MENU_FINGERPRINT = new ConcurrentHashMap<>();

    /**
     * 内容没变就不落盘（性能优化）。
     *
     * <p>发电机每粘液刻都会走到 {@code saveMenu}——54 槽逐个 Bukkit 序列化 + Base64
     * + 写 BlockData，台数一多 TPS 直接受影响。这里先算一个<b>廉价的指纹</b>
     * （各非图标槽的 类型:数量:耐久），内容没变直接跳过整份序列化。</p>
     *
     * <p>兜底口径：BlockData 里<b>没有已存数据</b>时强制落盘（新机器 / 数据被作废后
     * 指纹碰巧相同也不能跳，否则开局内容会被丢）。</p>
     */
    public static boolean saveMenuIfChanged(Inventory inv, Location loc, String key, int[] special) {
        try {
            if (inv == null || loc == null || key == null) return false;
            String k = locKey(loc) + "|" + key;
            String stored = getData(loc, key);
            String fp = fingerprintOf(inv, special);
            if (stored != null && !stored.isEmpty() && fp.equals(MENU_FINGERPRINT.get(k))) {
                return true;    // 内容没变：跳过整份序列化
            }
            boolean ok = saveMenu(inv, loc, key, special);
            if (ok) MENU_FINGERPRINT.put(k, fp);
            else MENU_FINGERPRINT.remove(k);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 廉价内容指纹：非图标槽（含 special 名单）的「槽号:类型:数量(:耐久)」串——刻意不读 meta。 */
    private static String fingerprintOf(Inventory inv, int[] special) {
        StringBuilder sb = new StringBuilder(128);
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            if (contains(special, i)) continue;
            sb.append(i).append(':').append(it.getType().name()).append(':').append(it.getAmount());
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) {
                sb.append(':').append(d.getDamage());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /**
     * 从机器的 BlockData 恢复界面内容。
     *
     * <p>被作废（{@link #isSavedMenuVoid}）时<b>绝不读回</b> —— 这是防复制的那道闸。</p>
     */
    public static boolean loadMenu(Inventory inv, Location loc, String key, int[] special) {
        try {
            if (inv == null || loc == null || key == null) return false;
            if (isSavedMenuVoid(loc, key)) return false;

            String enc = getData(loc, key);
            if (enc == null || enc.isEmpty()) return false;

            byte[] raw = Base64.getDecoder().decode(enc);
            int invSize = inv.getSize();
            int n = 0;
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
                int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    Object o = null;
                    try {
                        o = in.readObject();
                    } catch (Throwable ignored) {
                        o = null;
                    }
                    if (!(o instanceof ItemStack it)) continue;
                    if (i >= invSize) continue;
                    if (contains(special, i)) continue;
                    inv.setItem(i, it);
                    n++;
                }
            }
            return n > 0;
        } catch (Throwable t) {
            TT.log("[堆叠机] 读取界面内容失败 key=" + key + ": " + t);
            return false;
        }
    }

    /**
     * 从盘上把界面内容读进一个临时界面再「取出来」。
     *
     * <p>拆机器时用：{@code reload} 之后脚本内存是空的，内容只在盘上，
     * 所以先读进临时界面，再按「玩家物品」的口径取出来。</p>
     */
    public static java.util.List<ItemStack> harvestMenuData(Location loc, String dataKey,
                                                            java.util.function.Supplier<Inventory> tempFactory,
                                                            int[] special) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        try {
            if (loc == null || tempFactory == null) return out;
            Inventory tmp = tempFactory.get();
            if (tmp == null) return out;
            if (loadMenu(tmp, loc, dataKey, special)) {
                out.addAll(takePlayerItems(tmp, null));
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 拆机取物失败: " + t);
        }
        return out;
    }

    // ==================================================================
    // 界面物品与「玩家物品」的区分
    // ==================================================================

    /**
     * 是不是脚本自己画的图标。
     *
     * <p>材质 + 名字一起判：只看材质会把玩家自己的同名物品（一块普通玻璃板）一起吞掉。
     * 配方选择按钮与模板标签还要连 lore 一起对，防玩家用铁砧改出同名物品骗过判定。</p>
     */
    public static boolean isGuiIcon(ItemStack it) {
        try {
            if (it == null) return false;
            Material m = it.getType();
            String name = "";
            try {
                var meta = it.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    name = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().serialize(meta.displayName());
                }
            } catch (Throwable ignored) {
                name = "";
            }

            if (m == Material.OAK_HANGING_SIGN) return true;      // 配置牌
            if (m == Material.SOUL_LANTERN) return true;          // 未识别时的机器信息
            if (m == Material.ORANGE_STAINED_GLASS_PANE
                    || m == Material.GREEN_STAINED_GLASS_PANE) return true;   // 机器信息
            if (m == Material.LIGHT_BLUE_STAINED_GLASS_PANE) return name.isEmpty() || name.equals(" ");
            if (m == Material.BLUE_STAINED_GLASS_PANE) return name.isEmpty();
            if (m == Material.NETHER_STAR) {
                return name.contains("放在机器") || name.contains("类型识别");
            }
            if (m == Material.BOOK) return name.contains("已识别");

            String loreAll = "";
            try {
                var meta = it.getItemMeta();
                if (meta != null && meta.hasLore()) {
                    var ll = meta.lore();
                    if (ll != null) {
                        StringBuilder sb = new StringBuilder();
                        for (var c : ll) {
                            sb.append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .legacySection().serialize(c)).append('|');
                        }
                        loreAll = sb.toString();
                    }
                }
            } catch (Throwable ignored) {
                loreAll = "";
            }
            if (m == Material.INK_SAC) {
                return name.contains("选择具体允许配方") && loreAll.contains("可以跳过不选");
            }
            if (m == Material.PAPER) {
                return name.contains("放入所需的模板") && loreAll.contains("模板");
            }
            // 进度条（无名糖）
            if (m == Material.SUGAR) return name.isEmpty() || name.equals(" ");
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把界面里「玩家放的」东西全部取出来（顺手清空槽位）。
     *
     * <p>判定按<b>布局</b>而不是按槽位范围：图标格里的图标跳过（会被重画），
     * 但「图标格被玩家换了东西」以及非图标格里的东西一律取出 —— 免得把界面自己的玻璃板
     * 当成玩家物品送出去。</p>
     */
    public static java.util.List<ItemStack> takePlayerItems(Inventory inv, int[] special) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        try {
            if (inv == null) return out;
            int size = inv.getSize();
            for (int i = 0; i < size; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType() == Material.AIR) continue;
                if (contains(special, i) && isGuiIcon(it)) continue;   // 图标格里的图标：留着
                inv.setItem(i, null);
                out.add(it);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 取出界面物品失败: " + t);
        }
        return out;
    }

    /** 给玩家：背包放不下就掉在脚下（不提示）。 */
    public static void giveOrDrop(Player player, ItemStack stack) {
        try {
            if (player == null || stack == null || stack.getType() == Material.AIR) return;
            var left = player.getInventory().addItem(stack);
            for (ItemStack rest : left.values()) {
                if (rest != null) player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        } catch (Throwable t) {
            try {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            } catch (Throwable ignored) {
                TT.log("[堆叠机] 物品既进不了背包也掉不出来: " + t);
            }
        }
    }

    // ==================================================================
    // 拆机收尾
    // ==================================================================

    /**
     * 拆机器收尾：标记已拆 / 清脚本缓存 / 清盘上的菜单键 / 关掉还开着这台机器界面的玩家。
     *
     * <p>「关掉别人还开着的界面」这步不能省：他随手一点就会把旧内容<b>重新写回</b> BlockData，
     * 之后同坐标放新机器就成了复制。<b>必须先把 viewers 拷进数组再关</b> —— 边遍历边关
     * 会 ConcurrentModificationException。</p>
     *
     * @param closeViewers 关闭「还看着这台机器界面」的玩家的回调（由 GUI 侧提供，
     *                     因为「这个界面是不是我们的」只有 GUI 侧知道）
     * @return 被关掉界面的玩家数
     */
    public static int forgetMachine(Location loc, java.util.function.IntSupplier closeViewers) {
        try {
            String key = locKey(loc);
            if (key == null) return 0;
            markDead(loc);
            removeData(loc, DATA_MENU);
            removeData(loc, DATA_CFG);
            // 清理这一坐标的写通缓存（否则同坐标新机器会读到旧配方索引）
            LOCAL.keySet().removeIf(k -> k.startsWith(key + "|"));
            clearAllSavedMenuVoid(loc);   // 作废标记也一起复位：同坐标再放新机器时能正常落盘/读回
            return closeViewers == null ? 0 : closeViewers.getAsInt();
        } catch (Throwable t) {
            TT.log("[堆叠机] 拆机收尾失败: " + t);
            return 0;
        }
    }

    /** 关掉还开着这张界面的其他玩家（不含 except）。 */
    public static int closeViewersOf(Inventory inv, Player except) {
        int n = 0;
        try {
            if (inv == null) return 0;
            // 先拷进数组再关：边遍历边关会 ConcurrentModificationException
            for (org.bukkit.entity.HumanEntity viewer
                    : new java.util.ArrayList<>(inv.getViewers())) {
                if (viewer == null) continue;
                if (except != null && viewer.equals(except)) continue;
                try {
                    viewer.closeInventory();
                    n++;
                } catch (Throwable ignored) {
                    // 单个失败不影响其它
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 关闭界面观众失败: " + t);
        }
        return n;
    }

    /** 在线玩家快照（供 GUI 侧的「关掉还开着这台机器界面的玩家」用）。 */
    public static java.util.List<Player> onlinePlayers() {
        try {
            return new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    /** 数字格式化：照抄 LogiTech 的 {@code DecimalFormat("###,###,###,###,###,###.#")}。 */
    public static String fmtNum(double v) {
        try {
            double n = v;
            if (!Double.isFinite(n)) n = 0;
            boolean neg = n < 0;
            if (neg) n = -n;
            // 先按 LogiTech 的口径保留 1 位小数
            String s = String.valueOf(Math.round(n * 10) / 10.0);
            int dot = s.indexOf('.');
            String head = dot < 0 ? s : s.substring(0, dot);
            String tail = dot < 0 ? "" : s.substring(dot);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int i = head.length() - 1; i >= 0; i--) {
                sb.append(head.charAt(i));
                if (++count % 3 == 0 && i > 0) sb.append(',');
            }
            return (neg ? "-" : "") + sb.reverse() + tail;
        } catch (Throwable ignored) {
            return String.valueOf(v);
        }
    }

    /** 百分比：先按两位小数取整，再乘 100 取整（与原脚本一致）。 */
    public static String percent(double v) {
        try {
            double n = Math.round(v * 100) / 100.0;
            return Math.round(n * 100) + "%";
        } catch (Throwable ignored) {
            return String.valueOf(v);
        }
    }

    /** {@code "% -3d"}：左对齐补空格到 3 位（与 LogiTech 的格式串一致）。 */
    public static String pad3(long v) {
        String s = String.valueOf(v);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 3) sb.append(' ');
        return sb.toString();
    }

    /** {@code "%.3f"}：三位小数。 */
    public static String fmt3(double v) {
        try {
            return String.format(java.util.Locale.ROOT, "%.3f", v);
        } catch (Throwable ignored) {
            return String.valueOf(v);
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        LOCAL.clear();
        MENU_VOID.clear();
        DEAD.clear();
    }

    /** 空的只读映射，避免调用方到处判 null。 */
    public static Map<String, String> emptyLocal() {
        return new LinkedHashMap<>();
    }
}
