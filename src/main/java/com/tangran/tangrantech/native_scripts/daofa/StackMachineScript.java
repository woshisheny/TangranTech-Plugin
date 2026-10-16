package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineGenerator;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineNet;
import com.tangran.tangrantech.native_scripts.daofa.StackMachinePower;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRecipe;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRegistry;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineStore;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineWork;

/**
 * 「糖然牌堆叠机」（{@code TR_TRPDDJ}）—— 界面 + 识别 + 交互层。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的阶段 1~3：注册 + 右键打开父页面 + 配置界面
 * + 机器类型识别 + 识别结果决定布局与方块材质 + 拆除时归还界面物品。</p>
 *
 * <h2>父页面两种布局</h2>
 * <ul>
 *   <li><b>默认布局</b>（未识别 / 生成器）—— 6 行，最后一行是功能栏
 *       （48 = 配置告示牌、50 = 机器信息、其余淡蓝玻璃板）；前 45 格全是输出槽。</li>
 *   <li><b>输入输出布局</b>（配方机器 / 发电机）—— 前三列 18 格输入槽 + 第四列
 *       （3 = 配置牌 / 12,21,30,39 = 蓝玻璃 / 48 = 机器信息）+ 后五列 30 格输出槽。
 *       <b>发电机也用这套布局</b>：有些发电机要输入燃料、还可能产出物品。</li>
 * </ul>
 * <p>材质跟着识别结果走：配方机器 = 蜜脾块、发电机 = 蜂巢、生成器/未识别 = 蜂箱。
 * <b>每次打开都同步一次</b>（不能只在「类型变化」时同步 —— 老机器早把类型写进 BlockData 了，
 * 重放同款机器不会触发「变化」）。</p>
 *
 * <h2>未识别前的父界面禁止放东西</h2>
 * <p>识别之前不知道输入/输出槽在哪，所以空白格一律不许交互：往里放会提示
 * 「请先进入配置界面进行类型识别！」；从里面拿则静默拦下。</p>
 *
 * <h2>界面内容的三重缓存</h2>
 * <ol>
 *   <li>脚本内存（{@link #PARENT_CACHE} / {@link #CONFIG_CACHE}）—— 按机器坐标缓存，
 *       同一台机器反复开关不会被清空；</li>
 *   <li>机器 BlockData（{@link StackMachineStore}）—— 跨 reload / 重启；</li>
 *   <li>换布局时把老界面的东西还给玩家，并把盘上那份<b>作废</b>（防复制）。</li>
 * </ol>
 *
 * <h2>本阶段未包含（下轮）</h2>
 * <ul>
 *   <li><b>配方归一化</b>（{@code pdNormalizeRecipe} / {@code pdNormalizeGeneric}）——
 *       「选择具体允许配方」子菜单与模板标签需要它，本轮按钮画出来但点开会提示「暂不可用」；</li>
 *   <li><b>工作循环与能量总线</b>（{@code pdRunWorkCycle} / {@code pdRunGeneratorCycle} /
 *       能量缓冲代理）—— 所以机器信息格暂时只有「名称 / 并行数 / 工作效率」三行，
 *       耗电与电量恒为 0。</li>
 * </ul>
 */
public final class StackMachineScript implements NativeLifecycleScript {

    // ==================================================================
    // 基本常量
    // ==================================================================

    /** 本机物品 id。 */
    private static final String ITEM_ID = "TR_TRPDDJ";
    /** 父页面标题。 */
    private static final String TITLE = "§b§l糖然牌堆叠机";
    /** 配置界面标题。 */
    private static final String CFG_TITLE = "§b§l配置界面";

    /** 父页面尺寸（两种布局都是 6 行）。 */
    private static final int SIZE = 54;

    /** 默认布局：配置牌 / 机器信息 / 功能栏其余格。 */
    private static final int SLOT_CONFIG = 48;
    private static final int SLOT_INFO = 50;
    private static final int[] FILL_SLOTS = {45, 46, 47, 49, 51, 52, 53};

    /** 初始堆叠效率（lore 第 1 行写的就是它）。 */
    private static final double DEFAULT_EFFICIENCY = 2.0;

    // ---- 配置界面 ----

    private static final int CFG_SLOT_MACHINE = 4;
    private static final int CFG_SLOT_TYPE = 9;
    private static final int CFG_SLOT_RECIPE = 10;
    private static final int CFG_SLOT_TEMPLATE = 18;
    private static final int[] CFG_DECO = {0, 1, 2, 6, 7, 8};
    private static final int[] CFG_STARS = {3, 5};
    private static final String CFG_STAR_NAME = "§b在第五格放入机器进行类型识别";
    private static final int CFG_SIZE_1 = 9;
    private static final int CFG_SIZE_2 = 27;

    /** 默认布局（未识别 / 生成器）的进度条槽：功能栏第 49 格。 */
    private static final int SLOT_PROGRESS_DEFAULT = 49;
    /** 配方布局（配方机器 / 发电机）的进度条槽：第四列第二格。 */
    private static final int SLOT_PROGRESS_RECIPE = 12;
    /** 配方子菜单标题。 */
    private static final String RECIPE_MENU_TITLE = "§b§l选择具体允许配方";

    /** 各布局的「特殊槽」（脚本放的图标格）：一律锁死，既不能拿走也不能替换。 */
    private static final int[] DEFAULT_SPECIAL = {45, 46, 47, 48, 49, 50, 51, 52, 53};
    private static final int[] RECIPE_SPECIAL = {3, 12, 21, 30, 39, 48};
    private static final int[] CFG_SPECIAL = {0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 18};

    /** 输入输出布局：前三列 18 格输入 / 第四列 4 格蓝玻璃 / 后五列 30 格输出。 */
    private static final int[] RECIPE_INPUT_SLOTS = {
            0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38, 45, 46, 47};
    private static final int[] RECIPE_PANE_SLOTS = {12, 21, 30, 39};
    private static final int[] RECIPE_OUTPUT_SLOTS = {
            4, 5, 6, 7, 8, 13, 14, 15, 16, 17, 22, 23, 24, 25, 26,
            31, 32, 33, 34, 35, 40, 41, 42, 43, 44, 49, 50, 51, 52, 53};
    private static final int RECIPE_CFG_SLOT = 3;
    private static final int RECIPE_INFO_SLOT = 48;

    /** 默认布局的「前 5 行 45 格全是输出槽」（生成器的 45 个空白槽就是输出槽）。 */
    private static final int[] DEFAULT_OUTPUT_SLOTS = buildDefaultOutputSlots();

    private static int[] buildDefaultOutputSlots() {
        int[] out = new int[45];
        for (int i = 0; i < 45; i++) out[i] = i;
        return out;
    }

    // ==================================================================
    // 会话
    // ==================================================================

    /** 一份配置会话。 */
    private static final class Session {
        Location loc;
        String type;
        String machineId;
        long count = 1L;
        boolean fromStorage;
        /** 机器槽内容的「指纹」，只在真的变了才重新识别。 */
        String seenSig;
    }

    /** 玩家名 → 配置会话。 */
    private static final Map<String, Session> CFG_SESS = new ConcurrentHashMap<>();
    /** 玩家名 → 最近打开的堆叠机坐标。 */
    private static final Map<String, Location> LAST_MACHINE = new ConcurrentHashMap<>();
    /** 机器坐标键 → 父界面缓存（含布局种类）。 */
    private static final Map<String, ParentCache> PARENT_CACHE = new ConcurrentHashMap<>();
    /** 机器坐标键 → 配置界面缓存（含是否两行）。 */
    private static final Map<String, ConfigCache> CONFIG_CACHE = new ConcurrentHashMap<>();

    private static final class ParentCache {
        Inventory inv;
        String kind;
    }

    private static final class ConfigCache {
        Inventory inv;
        boolean two;
    }

    /** 取指纹失败时的自增计数（失败必须给唯一值，否则会被当成「没变」）。 */
    private static final java.util.concurrent.atomic.AtomicLong SIG_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    // ==================================================================
    // Holder：用 holder 认自己的界面，不靠标题比对
    // ==================================================================

    static final class ParentHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    static final class ConfigHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static boolean isParent(Inventory top) {
        return top != null && top.getHolder() instanceof ParentHolder;
    }

    private static boolean isConfig(Inventory top) {
        return top != null && top.getHolder() instanceof ConfigHolder;
    }

    private static Inventory topOf(InventoryClickEvent event) {
        try {
            return event.getView().getTopInventory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Inventory topOf(InventoryDragEvent event) {
        try {
            return event.getView().getTopInventory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 类型 / 布局
    // ==================================================================

    /** 布局种类：{@code recipe}（配方机器 + 发电机共用）或 {@code default}（生成器 / 未识别）。 */
    private static String parentKind(String type) {
        if (StackMachineRegistry.TYPE_RECIPE.equals(type)
                || StackMachineRegistry.TYPE_GEN.equals(type)) return "recipe";
        return "default";
    }

    private static int parentConfigSlot(String type) {
        return "recipe".equals(parentKind(type)) ? RECIPE_CFG_SLOT : SLOT_CONFIG;
    }

    private static int parentInfoSlot(String type) {
        return "recipe".equals(parentKind(type)) ? RECIPE_INFO_SLOT : SLOT_INFO;
    }

    private static int[] specialSlotsOfKind(String kind) {
        return "recipe".equals(kind) ? RECIPE_SPECIAL : DEFAULT_SPECIAL;
    }

    private static int[] specialSlotsOf(String type) {
        return specialSlotsOfKind(parentKind(type));
    }

    /** 输出槽：任何「扫输出槽」的地方都必须用它，不要硬编码（原脚本踩过：生成器前四列的残留物抓不进网络）。 */
    static int[] outputSlotsOf(String type) {
        return "recipe".equals(parentKind(type)) ? RECIPE_OUTPUT_SLOTS : DEFAULT_OUTPUT_SLOTS;
    }

    private static boolean isParentSpecial(String type, int slot) {
        for (int s : specialSlotsOf(type)) {
            if (s == slot) return true;
        }
        return false;
    }

    private static boolean isCfgSpecial(int slot) {
        for (int s : CFG_SPECIAL) {
            if (s == slot) return true;
        }
        return false;
    }

    // ==================================================================
    // 图标工厂
    // ==================================================================

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    private static ItemStack named(ItemStack item, String name, List<String> loreLines) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreLines.size());
                for (String l : loreLines) lore.add(legacy(l));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack plain(Material mat) {
        return mat == null ? null : new ItemStack(mat);
    }

    /** 配置告示牌。 */
    private static ItemStack iconSign() {
        return named(new ItemStack(Material.OAK_HANGING_SIGN), "§e点我开始配置堆叠机", null);
    }

    /** 功能栏占位：淡蓝玻璃板（名字留空格，免得悬浮显示物品名）。 */
    private static ItemStack iconFiller() {
        return named(new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE), " ", null);
    }

    /** 配方机器第四列的蓝玻璃（不设名字）。 */
    private static ItemStack iconBluePane() {
        return plain(Material.BLUE_STAINED_GLASS_PANE);
    }

    /** 配置界面第一行左右的淡蓝玻璃板（不设名字）。 */
    private static ItemStack iconLightBluePane() {
        return plain(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
    }

    private static ItemStack iconNetherStar() {
        return named(new ItemStack(Material.NETHER_STAR), CFG_STAR_NAME, null);
    }

    /** 未工作时的机器信息：灵魂灯笼 + 「待机中」。 */
    private static ItemStack iconInfoIdle() {
        return named(new ItemStack(Material.SOUL_LANTERN), "§c机器信息", List.of("§7待机中"));
    }

    /**
     * 工作中的机器信息：绿色玻璃板 + 五行。
     *
     * <p>原脚本五行是「机器名称 / 并行处理数 / 每刻耗电量 / 当前电量 / 工作效率」。
     * 耗电与电量要等工作循环 + 能量总线接进来（本阶段未做），所以这里恒为 0 ——
     * 界面上如实显示 0，不伪造数据。</p>
     */
    private static ItemStack iconInfoWorking(String machineName, long parallel, double energyPerTick,
                                             double charge, double efficiency, boolean noPower) {
        return named(new ItemStack(Material.GREEN_STAINED_GLASS_PANE), "§a机器信息", List.of(
                "§7当前模拟的机器名称: " + (machineName == null ? "" : machineName),
                "§7当前并行处理数: " + StackMachineStore.pad3(parallel),
                "§7当前每刻耗电量: " + StackMachineStore.fmtNum(energyPerTick) + "J/t",
                "§7当前电量: " + StackMachineStore.fmtNum(charge) + "J"
                        + (noPower ? " §c（电力不足）" : ""),
                "§7当前工作效率: " + StackMachineStore.percent(efficiency)));
    }

    /** 发电机未发电。 */
    private static ItemStack iconInfoGenIdle() {
        return named(new ItemStack(Material.ORANGE_STAINED_GLASS_PANE), "§c当前状态: 未发电", null);
    }

    /**
     * 发电机发电中：橙色玻璃板 + 五行。
     *
     * <p>五行是「当前单体发电 / 当前电机数目 / 当前工作效率 / 当前发电速率 / 当前电力存储」。
     * <b>电力缓存满时，单体发电与发电速率两行换成「电力缓存已满!」</b> ——
     * 这是原脚本刻意做的提示：缓存满了再显示数字会让玩家误以为「还在往里存」。</p>
     */
    private static ItemStack iconInfoGenActive(long unitGen, long motors, double efficiency,
                                               long rate, long storage, boolean full) {
        String unitLine = full ? "电力缓存已满!" : StackMachineStore.fmtNum(unitGen) + "J/t";
        String rateLine = full ? "电力缓存已满!" : StackMachineStore.fmtNum(rate) + "J/t";
        return named(new ItemStack(Material.ORANGE_STAINED_GLASS_PANE), "§a当前状态: 发电中", List.of(
                "§7当前单体发电: " + unitLine,
                "§7当前电机数目: " + Math.round(motors) + "x",
                "§7当前工作效率: " + StackMachineStore.fmt3(efficiency),
                "§7当前发电速率: " + rateLine,
                "§7当前电力存储: " + StackMachineStore.fmtNum(storage) + "J"));
    }

    /** 识别成功后的书：「已识别:类型」（多台时补数量与来源）。 */
    private static ItemStack iconTypeBook(String type, long count, boolean fromStorage) {
        List<String> lore = null;
        if (fromStorage || count > 1) {
            lore = List.of(
                    "§7数量: §f" + StackMachineStore.fmtNum(count) + " §7台",
                    "§7来源: §f" + (fromStorage ? "存储物品" : "机器槽直放"));
        }
        return named(new ItemStack(Material.BOOK),
                "§b已识别:" + StackMachineRegistry.typeLabel(type), lore);
    }

    // ==================================================================
    // 机器信息（父界面的 48 / 50 格）
    // ==================================================================

    /** 按识别结果出对应的那一件机器信息。 */
    private static ItemStack infoItem(Player player) {
        try {
            return iconInfoItem(infoStateOf(player));
        } catch (Throwable t) {
            TT.log("[堆叠机] 生成机器信息失败: " + t);
            return iconInfoIdle();
        }
    }

    /** 玩家当前这台机器识别出的类型（会话优先，其次本机 BlockData）。 */
    private static String infoTypeOf(Player player) {
        try {
            if (player == null) return null;
            Session sess = CFG_SESS.get(player.getName());
            if (sess != null && sess.type != null) return sess.type;
            Location loc = (sess != null && sess.loc != null) ? sess.loc : recallMachine(player);
            return StackMachineStore.getType(loc);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 玩家 ↔ 当前机器
    // ==================================================================

    private static void rememberMachine(Player player, Location loc) {
        try {
            if (player == null || loc == null) return;
            LAST_MACHINE.put(player.getName(), loc.clone());
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static Location recallMachine(Player player) {
        return player == null ? null : LAST_MACHINE.get(player.getName());
    }

    /**
     * 把会话绑到「当前这台机器」。
     *
     * <p>右键别的机器时把会话切过去 —— 否则会把 A 的类型套到 B 上、甚至改错方块的材质。</p>
     */
    private static Session bindMachine(Player player, Location loc) {
        try {
            if (player == null) return null;
            String name = player.getName();
            String key = StackMachineStore.locKey(loc);
            Session sess = CFG_SESS.get(name);
            String curKey = (sess != null) ? StackMachineStore.locKey(sess.loc) : null;

            if (sess == null || (key != null && !key.equals(curKey))) {
                Session fresh = new Session();
                fresh.loc = (loc == null) ? null : loc.clone();
                fresh.type = StackMachineStore.getType(loc);
                fresh.machineId = StackMachineStore.getMachineId(loc);
                fresh.count = StackMachineStore.getCount(loc);
                CFG_SESS.put(name, fresh);
                return fresh;
            }
            if (sess.loc == null && loc != null) sess.loc = loc.clone();
            return sess;
        } catch (Throwable t) {
            TT.log("[堆叠机] 绑定机器会话失败: " + t);
            return null;
        }
    }

    // ==================================================================
    // 绘制：父界面
    // ==================================================================

    /** 画图标前，先把「占了图标格的非图标物品」还给玩家（否则重画会把它清掉）。 */
    private static void clearIconSlot(Inventory inv, int slot, Player player) {
        try {
            if (inv == null) return;
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() == Material.AIR) return;
            if (StackMachineStore.isGuiIcon(it)) return;   // 已经是我们自己的图标 → 直接覆盖
            inv.setItem(slot, null);
            StackMachineStore.giveOrDrop(player, it);
        } catch (Throwable t) {
            TT.log("[堆叠机] 清理图标格失败 slot=" + slot + ": " + t);
        }
    }

    private static void drawParent(Inventory inv, Player player, String type) {
        try {
            if (inv == null) return;

            // 机器信息先画（单独 try）：万一后面画图标出问题，也不会让信息格停在旧物品上
            try {
                int slotInfo = parentInfoSlot(type);
                clearIconSlot(inv, slotInfo, player);
                inv.setItem(slotInfo, infoItem(player));
            } catch (Throwable t) {
                TT.log("[堆叠机] 画机器信息失败: " + t);
            }

            if ("recipe".equals(parentKind(type))) {
                // 输入/输出槽留空给玩家，只画第四列
                clearIconSlot(inv, RECIPE_CFG_SLOT, player);
                inv.setItem(RECIPE_CFG_SLOT, iconSign());
                for (int slot : RECIPE_PANE_SLOTS) {
                    if (slot == parentInfoSlot(type)) continue;
                    clearIconSlot(inv, slot, player);
                    // 第四列第二格（12）留给进度条（配方机器 / 发电机）
                    if (slot == SLOT_PROGRESS_RECIPE && type != null) {
                        inv.setItem(slot, parentBar(player, type));
                    } else {
                        inv.setItem(slot, iconBluePane());
                    }
                }
                return;
            }

            // 默认布局：最后一行功能栏（生成器在 49 格显示进度条；未识别时仍是淡蓝填充板）
            clearIconSlot(inv, SLOT_CONFIG, player);
            inv.setItem(SLOT_CONFIG, iconSign());
            for (int slot : FILL_SLOTS) {
                if (slot == parentInfoSlot(type)) continue;
                clearIconSlot(inv, slot, player);
                if (slot == SLOT_PROGRESS_DEFAULT && type != null) {
                    inv.setItem(slot, parentBar(player, type));
                    continue;
                }
                inv.setItem(slot, iconFiller());
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 画父界面失败: " + t);
        }
    }

    /** 材质跟着类型走（幂等，每次打开都调）。 */
    private static void syncMachineMaterial(Player player) {
        try {
            Session sess = CFG_SESS.get(player.getName());
            Location loc = (sess != null && sess.loc != null) ? sess.loc : recallMachine(player);
            setMachineMaterial(loc, infoTypeOf(player));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static Material typeMaterial(String type) {
        if (StackMachineRegistry.TYPE_RECIPE.equals(type)) return Material.HONEYCOMB_BLOCK;
        if (StackMachineRegistry.TYPE_GEN.equals(type)) return Material.BEE_NEST;
        return Material.BEEHIVE;
    }

    private static boolean isOurMaterial(Material m) {
        return m == Material.BEEHIVE || m == Material.BEE_NEST || m == Material.HONEYCOMB_BLOCK;
    }

    private static void setMachineMaterial(Location loc, String type) {
        try {
            if (loc == null) return;
            Block block = loc.getBlock();
            Material cur = block.getType();
            Material want = typeMaterial(type);
            // 只在本机还是「我们这三种材质」之一时才改（机器被挖了/换成别的东西 → 不动它）
            if (!isOurMaterial(cur)) return;
            if (want == null || cur == want) return;
            block.setType(want, false);
        } catch (Throwable t) {
            TT.log("[堆叠机] 同步机器材质失败: " + t);
        }
    }

    // ==================================================================
    // 打开：父界面
    // ==================================================================

    private static boolean openParent(Player player, Location machineLoc) {
        try {
            if (player == null || !player.isOnline()) return false;
            if (machineLoc != null) {
                rememberMachine(player, machineLoc);
                bindMachine(player, machineLoc);
            }
            String type = infoTypeOf(player);
            syncMachineMaterial(player);

            String kind = parentKind(type);
            String key = StackMachineStore.locKey(recallMachine(player));
            if (key != null) StackMachineStore.clearDead(recallMachine(player));   // 同坐标新机器 → 恢复落盘

            ParentCache cached = (key != null) ? PARENT_CACHE.get(key) : null;
            Inventory inv;
            if (cached != null && kind.equals(cached.kind) && cached.inv != null) {
                inv = cached.inv;   // 布局没变 → 复用同一个界面（里面的东西不会被清掉）
            } else {
                inv = Bukkit.createInventory(new ParentHolder(), SIZE, legacy(TITLE));
                if (cached != null && cached.inv != null) {
                    Location loc0 = recallMachine(player);
                    // 换布局：老界面里的东西还给玩家 + 关掉还看着老界面的别人 + 盘上那份作废
                    for (ItemStack it : StackMachineStore.takePlayerItems(cached.inv,
                            specialSlotsOfKind(cached.kind))) {
                        StackMachineStore.giveOrDrop(player, it);
                    }
                    StackMachineStore.closeViewersOf(cached.inv, player);
                    StackMachineStore.voidSavedMenu(loc0, StackMachineStore.DATA_MENU);
                }
                if (key != null) {
                    ParentCache fresh = new ParentCache();
                    fresh.inv = inv;
                    fresh.kind = kind;
                    PARENT_CACHE.put(key, fresh);
                    // 从 BlockData 恢复（跨 reload）
                    StackMachineStore.loadMenu(inv, recallMachine(player),
                            StackMachineStore.DATA_MENU, specialSlotsOfKind(kind));
                }
            }
            drawParent(inv, player, type);
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            TT.log("[堆叠机] 打开父界面失败: " + t);
            return false;
        }
    }

    // ==================================================================
    // 打开：配置界面
    // ==================================================================

    private static void drawConfigRow1(Inventory inv, Player player) {
        try {
            if (inv == null) return;
            for (int slot : CFG_DECO) {
                clearIconSlot(inv, slot, player);
                inv.setItem(slot, iconLightBluePane());
            }
            for (int slot : CFG_STARS) {
                clearIconSlot(inv, slot, player);
                inv.setItem(slot, iconNetherStar());
            }
            // slot 4 留给玩家放机器，不覆盖
        } catch (Throwable t) {
            TT.log("[堆叠机] 画配置界面第一行失败: " + t);
        }
    }

    private static void drawConfigRows(Inventory inv, Player player, boolean two) {
        try {
            if (inv == null || !two) return;
            Session sess = CFG_SESS.get(player.getName());
            String type = sess == null ? null : sess.type;
            long count = (sess != null && sess.count > 0) ? sess.count : 1L;
            boolean fromStorage = sess != null && sess.fromStorage;

            clearIconSlot(inv, CFG_SLOT_TYPE, player);
            inv.setItem(CFG_SLOT_TYPE, iconTypeBook(type, count, fromStorage));

            clearIconSlot(inv, CFG_SLOT_RECIPE, player);
            inv.setItem(CFG_SLOT_RECIPE, iconRecipeSelectIcon(player));

            clearIconSlot(inv, CFG_SLOT_TEMPLATE, player);
            inv.setItem(CFG_SLOT_TEMPLATE, iconTemplateLabel(player));
        } catch (Throwable t) {
            TT.log("[堆叠机] 画配置界面第二三行失败: " + t);
        }
    }

    private static Session cfgSession(Player player) {
        return CFG_SESS.computeIfAbsent(player.getName(), k -> new Session());
    }

    /**
     * 打开 / 重建配置界面。
     *
     * @param oldInv 非空时把老界面的东西搬到新界面（换尺寸时用）
     */
    private static boolean openConfig(Player player, Inventory oldInv) {
        try {
            if (player == null || !player.isOnline()) return false;
            Session sess = cfgSession(player);
            if (sess.loc == null) sess.loc = recallMachine(player);
            if (sess.type == null) {   // 首次打开：从本机 BlockData 回填
                sess.type = StackMachineStore.getType(sess.loc);
                sess.machineId = StackMachineStore.getMachineId(sess.loc);
                sess.count = StackMachineStore.getCount(sess.loc);
            }
            boolean two = sess.type != null;
            int size = two ? CFG_SIZE_2 : CFG_SIZE_1;
            syncMachineMaterial(player);

            String key = StackMachineStore.locKey(sess.loc);
            ConfigCache cached = (key != null) ? CONFIG_CACHE.get(key) : null;
            Inventory inv;
            if (cached != null && cached.inv != null && cached.two == two
                    && cached.inv.getSize() == size) {
                inv = cached.inv;   // 布局没变 → 复用（再进子菜单东西不会消失）
            } else {
                inv = Bukkit.createInventory(new ConfigHolder(), size, legacy(CFG_TITLE));
                boolean moved = false;
                if (cached != null && cached.inv != null) {
                    StackMachineStore.closeViewersOf(cached.inv, player);
                    migrateConfig(player, cached.inv, inv);
                    moved = true;
                } else if (oldInv != null) {
                    StackMachineStore.closeViewersOf(oldInv, player);
                    migrateConfig(player, oldInv, inv);
                    moved = true;
                } else if (key != null) {
                    // 从 BlockData 恢复（跨 reload：机器槽里的机器还在）
                    StackMachineStore.loadMenu(inv, sess.loc, StackMachineStore.DATA_CFG, CFG_SPECIAL);
                }
                if (key != null) {
                    ConfigCache cc = new ConfigCache();
                    cc.inv = inv;
                    cc.two = two;
                    CONFIG_CACHE.put(key, cc);
                    // 换尺寸搬家后「立刻」把新界面写盘：盘上那份还是搬家前的旧内容，
                    // 万一这会儿崩服/重载，搬进来的东西会丢、而「装不下还给玩家」的那几件会从盘上复活 ⇒ 复制
                    if (moved) {
                        StackMachineStore.saveMenu(inv, sess.loc, StackMachineStore.DATA_CFG, CFG_SPECIAL);
                    }
                }
            }
            drawConfigRow1(inv, player);
            drawConfigRows(inv, player, two);
            sess.seenSig = sigOf(inv.getItem(CFG_SLOT_MACHINE));
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            TT.log("[堆叠机] 打开配置界面失败: " + t);
            return false;
        }
    }

    /**
     * 配置界面换尺寸时搬家：非图标格原样搬；新界面装不下的还给玩家（静默）。
     *
     * <p>图标格：自己的图标丢掉重画，玩家塞进去的东西还给他。</p>
     */
    private static void migrateConfig(Player player, Inventory oldInv, Inventory newInv) {
        try {
            if (oldInv == null || newInv == null) return;
            int osize = oldInv.getSize();
            int nsize = newInv.getSize();
            int max = Math.min(osize, nsize);

            for (int i = 0; i < max; i++) {
                ItemStack it = oldInv.getItem(i);
                if (it == null || it.getType() == Material.AIR) continue;
                if (isCfgSpecial(i)) {
                    if (!StackMachineStore.isGuiIcon(it)) {
                        oldInv.setItem(i, null);
                        StackMachineStore.giveOrDrop(player, it);
                    }
                    continue;
                }
                newInv.setItem(i, it);
                oldInv.setItem(i, null);
            }
            for (int j = max; j < osize; j++) {   // 新界面装不下的 → 还给玩家
                ItemStack extra = oldInv.getItem(j);
                if (extra == null || extra.getType() == Material.AIR) continue;
                if (isCfgSpecial(j)) continue;
                oldInv.setItem(j, null);
                StackMachineStore.giveOrDrop(player, extra);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 配置界面搬家失败: " + t);
        }
    }

    // ==================================================================
    // 识别
    // ==================================================================

    /**
     * 机器槽内容的「指纹」。
     *
     * <p>{@code CraftInventory#getItem} 每次返回新对象，所以不能比对象身份，只能比内容。</p>
     */
    private static String sigOf(ItemStack stack) {
        try {
            if (stack == null || stack.getType() == Material.AIR) return "";
            StackMachineRegistry.StorageInfo st = StackMachineRegistry.storageOf(stack);
            if (st != null) {
                return "q|" + stack.getType() + "|" + st.content().getType() + "|"
                        + StackMachineRegistry.machineIdOf(st.content()) + "|" + st.amount();
            }
            return stack.getType() + "|" + stack.getAmount() + "|"
                    + StackMachineRegistry.machineIdOf(stack);
        } catch (Throwable t) {
            // 取指纹失败就当成「变了」，逼它重新识别（两次失败不能给同一个值）
            return "err" + SIG_SEQ.incrementAndGet();
        }
    }

    /**
     * 延迟一拍后做识别：读玩家当前打开的配置界面的机器槽。
     *
     * <p>延迟是必须的 —— 点击事件里读到的槽位内容还是<b>操作前</b>的。</p>
     */
    private static void checkRecognize(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            Inventory top;
            try {
                top = player.getOpenInventory().getTopInventory();
            } catch (Throwable ignored) {
                return;
            }
            if (!isConfig(top)) return;

            ItemStack stack = top.getItem(CFG_SLOT_MACHINE);
            Session sess = cfgSession(player);
            String sig = sigOf(stack);
            if (sess.seenSig == null) sess.seenSig = sig;   // 首次：以当前内容为基准
            if (sig.equals(sess.seenSig)) return;           // 内容没变 → 什么都不做
            sess.seenSig = sig;

            StackMachineRegistry.MachineSource src = StackMachineRegistry.machineSourceOf(stack);
            String type = (src == null) ? null : StackMachineRegistry.identify(src.item());
            String id = (src == null) ? null : StackMachineRegistry.machineIdOf(src.item());
            long count = (src == null) ? 1L : src.amount();
            boolean fromStorage = src != null && src.storage();

            boolean changed = !equalsSafe(sess.type, type)
                    || !equalsSafe(sess.machineId, id)
                    || sess.count != count
                    || sess.fromStorage != fromStorage;
            if (!changed) {
                syncMachineMaterial(player);   // 识别结果没变：不重开界面，但材质照样同步一次
                return;
            }

            sess.type = type;
            sess.machineId = id;
            sess.count = count;
            sess.fromStorage = fromStorage;
            if (sess.loc == null) sess.loc = recallMachine(player);
            StackMachineStore.storeType(sess.loc, type, id, count);
            onTypeChanged(player, sess.loc, type);   // 老父界面的东西还回去 + 本机材质跟着换
            openConfig(player, top);                // 重开：1 行 ↔ 3 行
        } catch (Throwable t) {
            TT.log("[堆叠机] 识别检查失败: " + t);
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 类型变了：老父界面里的东西还给玩家 + 关掉还看着它的别人 + 盘上那份作废 + 同步材质。 */
    private static void onTypeChanged(Player player, Location loc, String type) {
        try {
            String key = StackMachineStore.locKey(loc);
            ParentCache cached = (key != null) ? PARENT_CACHE.get(key) : null;
            if (cached != null && cached.inv != null) {
                for (ItemStack it : StackMachineStore.takePlayerItems(cached.inv,
                        specialSlotsOfKind(cached.kind))) {
                    StackMachineStore.giveOrDrop(player, it);
                }
                StackMachineStore.closeViewersOf(cached.inv, player);
                // 盘上的老内容作废：否则再开父界面又读回来 = 复制 / 无限刷物
                StackMachineStore.voidSavedMenu(loc, StackMachineStore.DATA_MENU);
                if (key != null) PARENT_CACHE.remove(key);
            }
            setMachineMaterial(loc, type);
        } catch (Throwable t) {
            TT.log("[堆叠机] 处理类型变更失败: " + t);
        }
    }

    // ==================================================================
    // 点击 / 拖拽
    // ==================================================================

    /** 这一次点击是不是「往界面里放东西」。 */
    private static boolean isPlaceAction(InventoryClickEvent event, Inventory top) {
        try {
            ItemStack cursor = event.getCursor();
            Inventory clicked = event.getClickedInventory();
            if (clicked != null && clicked.equals(top)) {
                if (cursor != null && cursor.getType() != Material.AIR) return true;   // 光标上有东西 → 放/换
                ClickType ct = event.getClick();
                return ct == ClickType.NUMBER_KEY || ct == ClickType.DOUBLE_CLICK
                        || ct == ClickType.SWAP_OFFHAND || ct == ClickType.CONTROL_DROP
                        || ct == ClickType.CREATIVE;
            }
            return event.isShiftClick();   // 背包里 shift → 往界面里塞
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void warnNeedConfig(Player player) {
        try {
            player.sendMessage("§c请先进入配置界面进行类型识别！");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 点完 / 拖完 1 拍后落盘（跨 reload 不丢）。 */
    private static void saveMenuLater(Player player) {
        try {
            NativePluginHelper.runLater(() -> saveOpenMenu(player), 1L);
        } catch (Throwable t) {
            TT.log("[堆叠机] 延迟落盘失败: " + t);
        }
    }

    private static void saveOpenMenu(Player player) {
        try {
            if (player == null) return;
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null) return;
            Location loc = recallMachine(player);
            if (loc == null) return;
            if (isParent(top)) {
                StackMachineStore.saveMenu(top, loc, StackMachineStore.DATA_MENU,
                        specialSlotsOf(infoTypeOf(player)));
            } else if (isConfig(top)) {
                StackMachineStore.saveMenu(top, loc, StackMachineStore.DATA_CFG, CFG_SPECIAL);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 落盘失败: " + t);
        }
    }

    private static void onClick(InventoryClickEvent event) {
        try {
            Inventory top = topOf(event);
            if (top == null) return;
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getRawSlot();

            if (isParent(top) || isConfig(top)) saveMenuLater(player);

            // 双击（收集同名物品到光标）会把整个界面里的同名物品合并 —— 图标格里的玻璃板/书会被一起吸走，
            // 所以本脚本的界面里一律禁掉双击
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
                return;
            }

            // 配方子菜单：整张界面只读，左键右键都当「选中」
            if (isRecipeMenu(top)) {
                event.setCancelled(true);
                onRecipeMenuClick(player, slot);
                return;
            }

            if (isParent(top)) {
                String type = infoTypeOf(player);
                if (isParentSpecial(type, slot)) {          // 图标格：锁死
                    event.setCancelled(true);
                    if (slot == parentConfigSlot(type)) {
                        NativePluginHelper.runLater(() -> openConfig(player, null), 1L);
                    }
                    return;
                }
                if (type == null) {                          // 未配置：空白格也不许交互
                    if (slot >= 0 && slot < top.getSize()) {
                        event.setCancelled(true);
                        if (isPlaceAction(event, top)) warnNeedConfig(player);
                        return;
                    }
                    if (isPlaceAction(event, top)) {          // 背包里 shift 想塞进来 → 拦 + 提示
                        event.setCancelled(true);
                        warnNeedConfig(player);
                    }
                }
                return;                                      // 已识别：输入/输出槽随意交互
            }

            if (isConfig(top)) {
                if (isCfgSpecial(slot)) {
                    event.setCancelled(true);
                    if (slot == CFG_SLOT_RECIPE) {         // 墨囊 → 配方子菜单
                        NativePluginHelper.runLater(() -> openRecipeMenu(player), 1L);
                    }
                    return;
                }
                NativePluginHelper.runLater(() -> checkRecognize(player), 1L);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 点击处理失败: " + t);
        }
    }

    private static boolean dragHitsSlots(InventoryDragEvent event, java.util.function.IntPredicate test) {
        try {
            var slots = event.getRawSlots();
            for (int s : slots) {
                if (test.test(s)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;   // 拿不到槽位就保守拦下
        }
    }

    private static boolean dragTouchesTop(InventoryDragEvent event, Inventory top) {
        try {
            int size = top.getSize();
            for (int s : event.getRawSlots()) {
                if (s < size) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void onDrag(InventoryDragEvent event) {
        try {
            Inventory top = topOf(event);
            if (top == null) return;
            if (!(event.getWhoClicked() instanceof Player player)) return;

            if (isParent(top) || isConfig(top)) saveMenuLater(player);

            if (isRecipeMenu(top)) {                        // 配方子菜单：只读
                event.setCancelled(true);
                return;
            }
            if (isParent(top)) {
                String type = infoTypeOf(player);
                if (type == null) {
                    if (dragTouchesTop(event, top)) {
                        event.setCancelled(true);
                        warnNeedConfig(player);
                    }
                    return;
                }
                if (dragHitsSlots(event, s -> isParentSpecial(type, s))) event.setCancelled(true);
                return;
            }
            if (!isConfig(top)) return;
            if (dragHitsSlots(event, StackMachineScript::isCfgSpecial)) {
                event.setCancelled(true);
                return;
            }
            NativePluginHelper.runLater(() -> checkRecognize(player), 1L);
        } catch (Throwable t) {
            TT.log("[堆叠机] 拖拽处理失败: " + t);
        }
    }

    private static void onClose(InventoryCloseEvent event) {
        try {
            if (!(event.getPlayer() instanceof Player player)) return;
            Inventory inv = event.getInventory();
            if (!isParent(inv) && !isConfig(inv)) return;
            // 关闭时落盘（内容已经在每次点击后落过，这里兜一次关界面时的手动改动）
            Location loc = recallMachine(player);
            if (loc == null) return;
            if (isParent(inv)) {
                StackMachineStore.saveMenu(inv, loc, StackMachineStore.DATA_MENU,
                        specialSlotsOf(infoTypeOf(player)));
            } else {
                StackMachineStore.saveMenu(inv, loc, StackMachineStore.DATA_CFG, CFG_SPECIAL);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 关闭处理失败: " + t);
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        return onUse(event);
                    }
                    return null;
                }
                case "onBreak" -> {
                    if (args.length > 0 && args[0] instanceof BlockBreakEvent event) {
                        onBreak(event);
                    }
                    return null;
                }
                case "tick" -> {
                    if (args.length > 0 && args[0] instanceof com.tangran.tangrantech.items
                            .ScriptMachineItem.TickInfo info) {
                        tickBlock(info);
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    private static Object onUse(PlayerInteractEvent event) {
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_BLOCK) return null;
            Block block = event.getClickedBlock();
            if (block == null) return null;
            // 只认本机材质（识别后会在三种材质之间切换）
            if (!isOurMaterial(block.getType())) return null;
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) return null;
            event.setCancelled(true);
            openParent(player, block.getLocation());
            return Boolean.TRUE;
        } catch (Throwable t) {
            TT.log("[堆叠机] 右键处理失败: " + t);
            return null;
        }
    }

    /**
     * 拆机器。
     *
     * <p>本机<b>没有 menus.yml</b> ⇒ 没有粘液 BlockMenu，界面内容全在「脚本缓存 + 机器 BlockData」里，
     * 所以必须自己收、自己掉 —— 否则拆机器时父界面/配置界面里的东西直接凭空消失。</p>
     */
    private static void onBreak(BlockBreakEvent event) {
        try {
            Block block = event.getBlock();
            if (block == null) return;
            if (!isOurMaterial(block.getType())) return;
            Location loc = block.getLocation();
            Player player = event.getPlayer();

            // 1) 内存里那份缓存界面里的玩家物品
            String key = StackMachineStore.locKey(loc);
            ParentCache pc = (key != null) ? PARENT_CACHE.get(key) : null;
            List<ItemStack> fromCache = new ArrayList<>();
            if (pc != null && pc.inv != null) {
                fromCache.addAll(StackMachineStore.takePlayerItems(pc.inv, specialSlotsOfKind(pc.kind)));
            }
            ConfigCache cc = (key != null) ? CONFIG_CACHE.get(key) : null;
            if (cc != null && cc.inv != null) {
                fromCache.addAll(StackMachineStore.takePlayerItems(cc.inv, CFG_SPECIAL));
            }

            // 2) 盘上那份（reload 之后内存是空的，内容只在盘上）
            fromCache.addAll(StackMachineStore.harvestMenuData(loc, StackMachineStore.DATA_MENU,
                    () -> Bukkit.createInventory(new ParentHolder(), SIZE, legacy(TITLE)),
                    DEFAULT_SPECIAL));
            fromCache.addAll(StackMachineStore.harvestMenuData(loc, StackMachineStore.DATA_CFG,
                    () -> Bukkit.createInventory(new ConfigHolder(), CFG_SIZE_2, legacy(CFG_TITLE)),
                    CFG_SPECIAL));

            // 3) 全部掉在机器位置
            int dropped = 0;
            for (ItemStack it : fromCache) {
                if (it == null || it.getType() == Material.AIR) continue;
                try {
                    block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), it);
                    dropped++;
                } catch (Throwable t) {
                    TT.log("[堆叠机] 拆机掉落失败: " + t);
                }
            }

            // 4) 收尾：标记已拆 + 清盘上的菜单键 + 关掉还开着这台机器界面的玩家
            StackMachineStore.forgetMachine(loc, () -> closeStaleMenus(key));
            PARENT_CACHE.remove(key);
            CONFIG_CACHE.remove(key);

            if (dropped > 0) {
                TT.log("[堆叠机] 拆机归还 " + dropped + " 组物品（机器位置）");
            }
            if (player != null) {
                // 提示放在最后：先保证东西落地，再告诉玩家
                if (dropped > 0) player.sendMessage("§e[堆叠机] 已归还界面里的 §f" + dropped + " §e组物品。");
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 拆机处理失败: " + t);
        }
    }

    /** 关掉「会话指向这台机器」且正开着它的玩家界面。 */
    private static int closeStaleMenus(String key) {
        int n = 0;
        try {
            if (key == null) return 0;
            for (Player p : StackMachineStore.onlinePlayers()) {
                if (p == null) continue;
                Session sess = CFG_SESS.get(p.getName());
                Location ploc = (sess != null) ? sess.loc : null;
                if (ploc == null || !key.equals(StackMachineStore.locKey(ploc))) continue;
                CFG_SESS.remove(p.getName());
                LAST_MACHINE.remove(p.getName());
                try {
                    Inventory top = p.getOpenInventory().getTopInventory();
                    if (isParent(top) || isConfig(top)) {
                        p.closeInventory();
                        n++;
                    }
                } catch (Throwable ignored) {
                    // 单个失败不影响其它
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 关闭旧界面失败: " + t);
        }
        return n;
    }

    /**
     * 本机<b>没有 menus.yml 条目</b>：界面是脚本自建的（存在方块 BlockData 里），
     * tick 逻辑也完全不碰菜单。所以必须声明「不需要菜单」——
     * 否则一旦该方块没被 Slimefun 建出 BlockMenu，这台机器就会彻底不工作。
     */
    @Override
    public boolean needsMenu() {
        return false;
    }

    @Override
    public Iterable<Listener> listeners() {
        return List.of(new MachineListener());
    }

    @Override
    public void registerListeners(org.bukkit.plugin.Plugin plugin) {
        if (plugin == null) return;
        // 家族表体积小（几个 yml 的顶层键），这里先扫一遍；服务器表留到第一次识别时再建
        try {
            StackMachineRegistry.knownCount();
        } catch (Throwable t) {
            TT.log("[堆叠机] 家族表预热失败: " + t);
        }
    }

    /** 界面事件。 */
    private static final class MachineListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            StackMachineScript.onClick(event);
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            StackMachineScript.onDrag(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            StackMachineScript.onClose(event);
        }
    }

    // ==================================================================
    // tick 主循环（原脚本 tick(info)）
    // ==================================================================

    /** 每台机器的 tick 状态（原脚本对应 PD_TICK[key] 那个动态对象）。 */
    public static final class TickState {
        /** 当前进度（粘液刻）。 */
        public long prog;
        /** 本拍是否因电力不足停机。 */
        public boolean noPower;
        /** 进度条上次写过的内容（内容没变就不写，避免界面无谓抖动）。 */
        public long barP = -1L;
        public long barT = -1L;
        /** 机器信息卡上次的签名（同理，内容没变就不写）。 */
        public String selfCardSig;
        public String genCardSig;
        /** 发电机：当前这份燃料还剩几刻。 */
        public long genBurn;
        /** 发电机：当前这份燃料总共几刻。 */
        public long genBurnTotal;
        public boolean genWorking;
        public long genUnit;
        public long genParallel;
        public long genRate;
        public long genRateOut;
        public long genStorage;
        public long genCap;
        public boolean genFull;
    }

    private static final Map<String, TickState> TICK = new ConcurrentHashMap<>();

    /** 取（必要时创建）本机 tick 状态。发电机层也要用，所以不是 private。 */
    static TickState tickStateOf(Location loc) {
        try {
            String key = StackMachineStore.locKey(loc);
            if (key == null) return null;
            return TICK.computeIfAbsent(key, k -> new TickState());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 本机当前打开着的父界面（没有返回 null）。 */
    private static Inventory parentInvOf(Location loc) {
        try {
            String key = StackMachineStore.locKey(loc);
            ParentCache c = (key != null) ? PARENT_CACHE.get(key) : null;
            return (c != null) ? c.inv : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 每粘液刻的机器主循环。
     *
     * <h2>三条与原脚本一致的「不做无用功」规则</h2>
     * <ol>
     *   <li><b>未识别就什么都不做</b>：只把进度条归零，不碰材质、不吸电。</li>
     *   <li><b>没有能跑的配方时进度归零停住</b>。原脚本曾在这里照样 {@code prog + 1} 并刷条
     *       ⇒ 无输入时进度条也会一直来回刷（用户实测过）。</li>
     *   <li><b>电力不足时保留当前进度停机</b>（不清零）。用户口径：攒够电后要接着从这里推进，
     *       而不是从头再来。</li>
     * </ol>
     *
     * <h2>一个必须守住的顺序</h2>
     * <p><b>「吸电」必须在「没配方能跑」的判断之前</b>。原脚本把它放在有配方之后，
     * 结果没配方能跑（等材料 / 等模板）时永远不吸电，本机电量看着永远是 0
     * （用户口径：「堆叠机器现在存不进去电」）。</p>
     */
    private static void tickBlock(com.tangran.tangrantech.items.ScriptMachineItem.TickInfo info) {
        try {
            if (info == null) return;
            Block block = info.block();
            if (block == null) return;
            Location loc = block.getLocation();
            if (loc == null) return;

            String type = StackMachineStore.getType(loc);
            if (type == null) {
                // 未识别：不工作
                refreshBar(loc, null, 0L, 0L);
                return;
            }
            long count = StackMachineStore.getCount(loc);
            if (count <= 0L) count = 1L;
            String machineId = StackMachineStore.getMachineId(loc);
            List<StackMachineRecipe.Recipe> recipes = StackMachineRecipe.listOf(machineId);

            TickState st = tickStateOf(loc);
            if (st == null) return;
            Integer selected = StackMachineRecipe.selectedIndexOf(loc);
            Inventory inv = parentInvOf(loc);

            // ★ 发电机：每粘液刻都要跑（烧燃料 / 按鱼发电 / 问 provider），
            //   不是「攒够 ticks 才出一份」
            if (StackMachineRegistry.TYPE_GEN.equals(type)) {
                StackMachineGenerator.runCycle(loc, type, machineId, count, inv, selected);
                // 进度条：烧燃料时显示「这一份燃料烧到哪了」；没在烧就停在 0%
                long totalBurn = st.genBurnTotal;
                long leftBurn = st.genBurn;
                long burnTotal = (totalBurn > 0L) ? totalBurn : 1L;
                long burnNow = (totalBurn > 0L && leftBurn > 0L)
                        ? Math.max(0L, totalBurn - leftBurn) : 0L;
                refreshBar(loc, type, burnNow, burnTotal);
                refreshGenInfoCard(loc, type, machineId, count, Math.max(1L, count));
                if (inv != null) {
                    // 性能优化：内容没变就不落盘（原先每 tick 全量 54 槽序列化 + Base64）
                    StackMachineStore.saveMenuIfChanged(inv, loc, StackMachineStore.DATA_MENU,
                            specialSlotsOfKind(parentKind(type)));
                }
                return;
            }

            StackMachineRecipe.Recipe rec = StackMachineRecipe.pick(recipes, inv, selected, type, loc);
            long total = (rec != null && rec.ticks > 0) ? rec.ticks : 10L;

            // ★ 先「蓄电」：**不管这拍有没有活干**，每粘液刻都尽量把电攒进本机缓存
            try {
                StackMachinePower.selfChargeUp(loc);
            } catch (Throwable ignored) {
                // 吸电失败不影响本拍工作
            }

            // ★ 现在没有能跑的配方（无输入 / 输入不足 / 固定配方材料不够 / 生成器没有可用配方）：
            //   进度归零停住
            if (rec == null) {
                st.prog = 0L;
                st.noPower = false;
                refreshBar(loc, type, 0L, total);
                refreshInfoCard(loc, type, machineId, count);
                return;
            }

            // ★ 本机耗电：电量不够 → 保留当前进度停机（不清零），攒够电后接着推进；
            //   够电 → 扣掉本拍耗电再推进进度
            long parallelN = Math.max(1L, (long) Math.floor(count * StackMachineWork.DEFAULT_EFFICIENCY));
            long costTick = (long) Math.floor(
                    StackMachinePower.machinePowerCost(machineId) * (double) parallelN);
            if (costTick > 0L && StackMachinePower.SELF_REQUIRE_POWER) {
                long haveE = StackMachinePower.selfEnergyOf(loc);
                if (haveE < costTick) {
                    st.noPower = true;
                    refreshBar(loc, type, st.prog, total);      // 保留下进度（不清零）
                    refreshInfoCard(loc, type, machineId, count);
                    return;
                }
                StackMachinePower.setSelfEnergy(loc, haveE - costTick);
                st.noPower = false;
            } else {
                st.noPower = false;
            }

            // 界面没开时也要照常工作：Progress 只影响进度条
            st.prog = st.prog + 1L;
            if (st.prog < total) {
                // 奇数拍刷一次（内容没变时 refreshBar 自己会跳过）
                if (st.prog % 2L == 1L) refreshBar(loc, type, st.prog, total);
                refreshInfoCard(loc, type, machineId, count);
                return;
            }
            st.prog = 0L;
            if (inv == null) {
                refreshBar(loc, type, 0L, total);
                refreshInfoCard(loc, type, machineId, count);
                return;
            }
            // 性能优化：把上面 pick 好的配方传下去，Work 循环不再重挑一遍
            StackMachineWork.run(loc, type, machineId, count, inv, recipes, selected, rec);
            refreshBar(loc, type, 0L, total);
            refreshInfoCard(loc, type, machineId, count);
            StackMachineStore.saveMenu(inv, loc, StackMachineStore.DATA_MENU,
                    specialSlotsOfKind(parentKind(type)));
        } catch (Throwable t) {
            TT.log("[堆叠机] tick 处理失败: " + t);
        }
    }

    // ==================================================================
    // 机器信息卡的状态
    // ==================================================================

    /** 机器信息卡的一份状态（配方机器 / 生成器 / 发电机共用）。 */
    public static final class InfoState {
        public String type;
        public boolean working;
        public String name;
        public long machines;
        public long parallel;
        public double energyPerTick;
        public double charge;
        public double efficiency;
        public long rate;
        public long rateOut;
        public long storage;
        public boolean full;
        public boolean noPower;
    }

    /** 按状态出对应的那一件机器信息。 */
    private static ItemStack iconInfoItem(InfoState state) {
        try {
            if (state == null) return iconInfoIdle();
            if (StackMachineRegistry.TYPE_GEN.equals(state.type)) {
                return state.working
                        ? iconInfoGenActive(state.rate, state.machines, state.efficiency,
                                state.rateOut, state.storage, state.full)
                        : iconInfoGenIdle();
            }
            return iconInfoWorking(state.name, state.parallel, state.energyPerTick,
                    state.charge, state.efficiency, state.noPower);
        } catch (Throwable t) {
            TT.log("[堆叠机] 生成机器信息失败: " + t);
            return iconInfoIdle();
        }
    }

    /**
     * 父菜单机器信息的状态。
     *
     * <p>没识别过机器 → null（显示「待机中」）；发电机 → 走实测的发电状态；
     * 配方机器 / 生成器 → 真实耗电 + 真实电量。</p>
     */
    private static InfoState infoStateOf(Player player) {
        try {
            if (player == null) return null;
            Session sess = CFG_SESS.get(player.getName());
            Location loc = (sess != null && sess.loc != null) ? sess.loc : recallMachine(player);
            String type = infoTypeOf(player);
            if (type == null) return null;
            String id = (sess != null && sess.machineId != null) ? sess.machineId
                    : StackMachineStore.getMachineId(loc);
            // 台数：直接放机器 = 1；存储物品里可能上万
            long machines = (sess != null && sess.count > 0L) ? sess.count
                    : StackMachineStore.getCount(loc);
            if (StackMachineRegistry.TYPE_GEN.equals(type)) {
                return genState(loc, id, machines, machines);
            }
            return selfState(loc, type, id, machines);
        } catch (Throwable t) {
            TT.log("[堆叠机] 读取机器信息状态失败: " + t);
            return null;
        }
    }

    /** 发电机机器信息状态（按本机实测，不看会话）。 */
    static InfoState genState(Location loc, String machineId, long count, long machines) {
        try {
            TickState st = (loc != null) ? TICK.get(StackMachineStore.locKey(loc)) : null;
            long n = (machines > 0L) ? machines : Math.max(1L, count);
            InfoState s = new InfoState();
            s.type = StackMachineRegistry.TYPE_GEN;
            s.working = (st != null && st.genWorking);
            s.name = StackMachineRegistry.machineName(machineId);
            s.machines = n;
            s.parallel = (long) Math.floor(n * DEFAULT_EFFICIENCY);
            s.energyPerTick = 0.0;
            s.charge = StackMachinePower.localChargeOf(loc);
            s.efficiency = DEFAULT_EFFICIENCY;
            s.rate = (st != null) ? Math.max(0L, st.genUnit) : 0L;
            s.rateOut = (st != null) ? Math.max(0L, st.genRate) : 0L;
            s.storage = StackMachinePower.localChargeOf(loc);
            s.full = (st != null && st.genFull);
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 配方机器 / 生成器的机器信息状态（真实耗电 + 真实电量）。 */
    static InfoState selfState(Location loc, String type, String machineId, long count) {
        try {
            TickState st = (loc != null) ? TICK.get(StackMachineStore.locKey(loc)) : null;
            long machines = Math.max(1L, count);
            long parallel = (long) Math.floor(machines * DEFAULT_EFFICIENCY);
            long cost = (long) Math.floor(
                    StackMachinePower.machinePowerCost(machineId) * (double) parallel);
            InfoState s = new InfoState();
            s.type = type;
            s.working = !(st != null && st.noPower);
            s.name = StackMachineRegistry.machineName(machineId);
            s.machines = machines;
            s.parallel = parallel;
            s.energyPerTick = cost;
            s.charge = StackMachinePower.selfEnergyOf(loc);
            s.efficiency = DEFAULT_EFFICIENCY;
            s.noPower = (st != null && st.noPower);
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 进度条（原脚本 pdProgressSlot / pdSugarBar 一族）
    // ==================================================================

    /** 进度条槽：配方布局在第四列第二格，其它布局在功能栏第 49 格。 */
    private static int progressSlot(String type) {
        return "recipe".equals(parentKind(type)) ? SLOT_PROGRESS_RECIPE : SLOT_PROGRESS_DEFAULT;
    }

    /** 百分比 → 颜色（照抄粘液本体 NumberUtils 的阈值）。 */
    private static String pctColor(long pct) {
        if (pct < 16) return "§4";
        if (pct < 32) return "§c";
        if (pct < 48) return "§6";
        if (pct < 64) return "§e";
        if (pct < 80) return "§2";
        return "§a";
    }

    /** 剩余秒数 → "45s" / "1m30s"（照抄粘液本体 NumberUtils 的格式）。 */
    private static String timeLeftText(long seconds) {
        long s = Math.max(0L, seconds);
        long m = s / 60L;
        return (m > 0L ? m + "m" : "") + (s - m * 60L) + "s";
    }

    /** 第一行 lore：20 格进度条 + 百分比（已完成格用分级颜色，未完成一律灰色）。 */
    private static String progressLoreLine(long prog, long total) {
        try {
            long t = (total > 0L) ? total : 10L;
            long p = Math.max(0L, Math.min(prog, t));
            long pct = Math.round(p * 100.0d / (double) t);
            long filled = pct / 5L;                 // 每 5% 一格，最多 20 格
            if (filled < 0L) filled = 0L;
            if (filled > 20L) filled = 20L;
            StringBuilder sb = new StringBuilder(pctColor(pct));
            for (long i = 0L; i < filled; i++) sb.append(':');
            sb.append("§7");
            for (long j = filled; j < 20L; j++) sb.append(':');
            return sb + " - " + pct + "%";
        } catch (Throwable ignored) {
            return "§7:::::::::::::::::::: - 0%";
        }
    }

    /**
     * 进度条物品：单个糖 + 两行 lore。
     *
     * <p>物品本身永远不变，只有 lore 在变 ⇒ 谁都拿不走、也不会被识别成「数量变化」。</p>
     * <p>名字必须给<b>一个空格</b>，不能给空字符串：{@code fromStringOrNull("")} 会返回 null
     * ⇒ {@code hasDisplayName()} 变 false ⇒ 客户端显示物品本名「糖」。
     * 给空格才是「有名字但看不见」（和本脚本的无名玻璃板同一个写法）。</p>
     */
    private static ItemStack iconSugarBar(long prog, long total) {
        try {
            long t = (total > 0L) ? total : 10L;
            long p = Math.max(0L, Math.min(prog, t));
            return named(new ItemStack(Material.SUGAR, 1), " ", List.of(
                    progressLoreLine(p, t),
                    // 剩余粘液刻 ÷ 2 = 秒
                    "§7" + timeLeftText((t - p) / 2L)));
        } catch (Throwable ignored) {
            return new ItemStack(Material.SUGAR, 1);
        }
    }

    /**
     * 父界面里那根进度条。
     *
     * <p>口径与 tick <b>完全一致</b>：用同一份「本机配方表 + 固定配方 + 父界面输入」挑配方
     * ⇒ 总时长 / 百分比不会对不上。</p>
     */
    private static ItemStack parentBar(Player player, String type) {
        try {
            Session sess = CFG_SESS.get(player.getName());
            Location loc = (sess != null && sess.loc != null) ? sess.loc : recallMachine(player);
            TickState st = (loc != null) ? TICK.get(StackMachineStore.locKey(loc)) : null;
            long prog = (st != null) ? st.prog : 0L;
            String machineId = (sess != null && sess.machineId != null) ? sess.machineId
                    : ((loc != null) ? StackMachineStore.getMachineId(loc) : null);
            List<StackMachineRecipe.Recipe> recipes = StackMachineRecipe.listOf(machineId);
            Integer selected = StackMachineRecipe.selectedIndexOf(loc);
            Inventory inv = parentInvOf(loc);
            StackMachineRecipe.Recipe rec =
                    StackMachineRecipe.pick(recipes, inv, selected, type, loc);
            long total = (rec != null && rec.ticks > 0) ? rec.ticks : 10L;
            return iconSugarBar(prog, total);
        } catch (Throwable ignored) {
            return iconSugarBar(0L, 10L);
        }
    }

    /**
     * 刷进度条。
     *
     * <p><b>内容没变就不写</b>：否则「没有输入 / 不在发电」时进度条也会每刻刷新
     * （用户实测过：无输入也在一直刷）。</p>
     */
    static void refreshBar(Location loc, String type, long prog, long total) {
        try {
            String key = StackMachineStore.locKey(loc);
            if (key == null) return;
            long p = Math.max(0L, prog);
            long t = Math.max(0L, total);
            TickState st = TICK.get(key);
            if (st != null) {
                if (st.barP == p && st.barT == t) return;
                st.barP = p;
                st.barT = t;
            }
            ParentCache cached = PARENT_CACHE.get(key);
            if (cached != null && cached.inv != null) {
                cached.inv.setItem(progressSlot(type), iconSugarBar(p, t));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按「签名」刷机器信息卡（耗电 / 电量 / 电力不足 有变化才写）。 */
    static void refreshInfoCard(Location loc, String type, String machineId, long count) {
        try {
            String key = StackMachineStore.locKey(loc);
            if (key == null) return;
            ParentCache cached = PARENT_CACHE.get(key);
            if (cached == null || cached.inv == null) return;
            TickState st = TICK.get(key);
            InfoState state = selfState(loc, type, machineId, count);
            if (state == null) return;
            String sig = state.name + "|" + state.parallel + "|"
                    + (long) state.energyPerTick + "|" + (long) state.charge
                    + "|" + (state.noPower ? "1" : "0");
            if (st != null) {
                if (sig.equals(st.selfCardSig)) return;
                st.selfCardSig = sig;
            }
            cached.inv.setItem(parentInfoSlot(type), iconInfoItem(state));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按「签名」刷发电机的机器信息卡（否则用户看到的永远是旧的「未发电」）。 */
    static void refreshGenInfoCard(Location loc, String type, String machineId,
                                   long count, long machines) {
        try {
            String key = StackMachineStore.locKey(loc);
            if (key == null) return;
            ParentCache cached = PARENT_CACHE.get(key);
            if (cached == null || cached.inv == null) return;
            TickState st = TICK.get(key);
            InfoState state = genState(loc, machineId, count, machines);
            if (state == null) return;
            if (st != null) {
                String sig = (state.working ? "1" : "0") + "|" + state.rate + "|"
                        + state.parallel + "|" + state.rateOut + "|" + state.storage + "|"
                        + (state.full ? "1" : "0") + "|" + machines + "|"
                        + st.genBurnTotal + "|" + st.genBurn;
                if (sig.equals(st.genCardSig)) return;
                st.genCardSig = sig;
            }
            cached.inv.setItem(parentInfoSlot(type), iconInfoItem(state));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 配置界面内容的只读快照（tick 每刻查模板 / 鱼都要用）
    // ==================================================================

    /**
     * 取本机的配置界面，必要时从盘上复原一份。
     *
     * <p>两个来源：</p>
     * <ol>
     *   <li>本插件里已有<b>活界面</b> → 直接用（玩家正开着配置界面时就是它）；</li>
     *   <li>没有 → 从盘上的 {@code trpddj_cfg} 复原一份（<b>不打开给玩家</b>，
     *       只给 tick 查模板 / 鱼用）并缓存。</li>
     * </ol>
     * <p>★ 这一步是必须的：tick 每粘液刻都要查模板，<b>不能只在「玩家开着配置界面」时才算数</b> ——
     * 装好模板关掉界面，机器照样要工作。原脚本的 {@code pdCfgInvOf} 就是这个用途。</p>
     */
    static Inventory configInvFor(Location loc, boolean create) {
        try {
            String key = StackMachineStore.locKey(loc);
            if (key == null) return null;
            ConfigCache cached = CONFIG_CACHE.get(key);
            if (cached != null && cached.inv != null) return cached.inv;
            if (!create) return null;
            boolean two = (StackMachineStore.getType(loc) != null);
            int size = two ? CFG_SIZE_2 : CFG_SIZE_1;
            Inventory inv = Bukkit.createInventory(new ConfigHolder(), size, legacy(CFG_TITLE));
            try {
                StackMachineStore.loadMenu(inv, loc, StackMachineStore.DATA_CFG, CFG_SPECIAL);
            } catch (Throwable ignored) {
                // 读不出来就当作「模板槽是空的」
            }
            ConfigCache fresh = new ConfigCache();
            fresh.inv = inv;
            fresh.two = two;
            CONFIG_CACHE.put(key, fresh);
            return inv;
        } catch (Throwable t) {
            TT.log("[堆叠机] 复原配置界面失败: " + t);
            return null;
        }
    }

    // ==================================================================
    // 配方子菜单（原脚本 pdOpenRecipeMenu 一族）
    // ==================================================================

    /** 配方子菜单的 holder（用 holder 认自己的界面，不靠标题比对）。 */
    static final class RecipeHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** 玩家名 → 配方子菜单（复用同一个界面，点选时只清空重画）。 */
    private static final Map<String, RecipeCache> RECIPE_CACHE = new ConcurrentHashMap<>();

    private static final class RecipeCache {
        Inventory inv;
        Location loc;
    }

    private static boolean isRecipeMenu(Inventory top) {
        try {
            return top != null && top.getHolder() instanceof RecipeHolder;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 玩家当前配置会话指向的机器坐标。 */
    private static Location cfgLocOf(Player player) {
        try {
            Session sess = CFG_SESS.get(player.getName());
            if (sess != null && sess.loc != null) return sess.loc;
            return recallMachine(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 本机当前「生效」的配方：选了固定配方就是它，否则按顺序挑第一条（用于模板提示）。 */
    private static StackMachineRecipe.Recipe activeRecipeOf(Player player) {
        try {
            Location loc = cfgLocOf(player);
            Session sess = CFG_SESS.get(player.getName());
            String mid = (sess != null && sess.machineId != null) ? sess.machineId
                    : ((loc != null) ? StackMachineStore.getMachineId(loc) : null);
            return StackMachineRecipe.activeOf(loc, mid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 墨囊：选择具体允许配方。
     *
     * <p>lore 按「本机共几条 / 当前是固定第几条还是自动检测」实时算 ——
     * 每次重画配置界面都会调它，所以识别结果一变提示就跟着变。</p>
     */
    private static ItemStack iconRecipeSelectIcon(Player player) {
        try {
            Location loc = cfgLocOf(player);
            List<StackMachineRecipe.Recipe> recipes = StackMachineRecipe.listOf(
                    (loc != null) ? StackMachineStore.getMachineId(loc) : null);
            List<String> lore = new ArrayList<>();
            lore.add("§7可以跳过不选，堆叠机自动检测");
            lore.add("§7本机共 §f" + recipes.size() + " §7条配方");
            Integer sel = (loc != null) ? StackMachineRecipe.selectedIndexOf(loc) : null;
            if (sel != null && sel >= 0 && sel < recipes.size()) {
                lore.add("§a当前固定第 " + (sel + 1) + " 条");
            } else {
                lore.add("§7当前：§f自动检测");
            }
            lore.add("§e左键/右键点击打开配方列表");
            return named(new ItemStack(Material.INK_SAC, 1), "§b选择具体允许配方", lore);
        } catch (Throwable t) {
            TT.log("[堆叠机] 生成配方选择按钮失败: " + t);
            return named(new ItemStack(Material.INK_SAC, 1), "§b选择具体允许配方", null);
        }
    }

    /** 纸：模板标签（lore 按「自动检测 / 已选配方」需要哪种模板来填）。 */
    private static ItemStack iconTemplateLabel(Player player) {
        try {
            StackMachineRecipe.Recipe rec = activeRecipeOf(player);
            List<String> tplNames = StackMachineRecipe.templateNamesOf(rec);
            List<String> lore = new ArrayList<>();
            if (rec == null) {
                lore.add("§7识别出机器后这里会提示需要的模板");
            } else if (tplNames.isEmpty()) {
                lore.add("§7您当前需要放入的模板：§f不需要（可留空）");
            } else {
                lore.add("§7您当前需要放入 §f" + String.join("§7、§f", tplNames) + " §7模板");
            }
            lore.add("§7把模板放在右边这几格即可（§f只当模具，不会被消耗§7）");
            return named(new ItemStack(Material.PAPER, 1), "§b在本行右侧放入所需的模板", lore);
        } catch (Throwable t) {
            TT.log("[堆叠机] 生成模板标签失败: " + t);
            return named(new ItemStack(Material.PAPER, 1), "§b在本行右侧放入所需的模板", null);
        }
    }

    /**
     * 打开配方子菜单（54 格：配方图标从 0 开始铺，45 = 返回上一级、53 = 改回自动检测）。
     *
     * <p>复用同一个界面（每次点选都新建 54 格太浪费，也让「同一个界面」语义更清楚）。</p>
     */
    private static boolean openRecipeMenu(Player player) {
        try {
            if (player == null || !player.isOnline()) return false;
            Location loc = cfgLocOf(player);
            String mid = (loc != null) ? StackMachineStore.getMachineId(loc) : null;
            List<StackMachineRecipe.Recipe> recipes = StackMachineRecipe.listOf(mid);
            if (recipes.isEmpty()) {
                player.sendMessage("§c[堆叠机] 认不出这台机器的配方，无法选择");
                return false;
            }
            String pname = player.getName();
            RecipeCache cachedM = RECIPE_CACHE.get(pname);
            Inventory inv;
            if (cachedM != null && cachedM.inv != null && cachedM.inv.getSize() == 54) {
                inv = cachedM.inv;
                for (int c = 0; c < 54; c++) inv.setItem(c, null);
            } else {
                inv = Bukkit.createInventory(new RecipeHolder(), 54, legacy(RECIPE_MENU_TITLE));
            }
            Integer sel = (loc != null) ? StackMachineRecipe.selectedIndexOf(loc) : null;
            int maxIcons = 45;                     // 0~44 放配方图标
            for (int i = 0; i < recipes.size() && i < maxIcons; i++) {
                ItemStack icon = iconRecipeEntry(recipes.get(i), i, sel != null && sel == i);
                if (icon != null) inv.setItem(i, icon);
            }
            inv.setItem(45, iconRecipeNav(Material.ARROW, "§e返回上一级菜单", "§7回到堆叠机配置界面"));
            inv.setItem(53, iconRecipeNav(Material.BARRIER, "§c改回自动检测",
                    "§7不固定配方，堆叠机自己挑"));
            RecipeCache rc = new RecipeCache();
            rc.inv = inv;
            rc.loc = (loc == null) ? null : loc.clone();
            RECIPE_CACHE.put(pname, rc);
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            TT.log("[堆叠机] 打开配方子菜单失败: " + t);
            return false;
        }
    }

    /**
     * 配方子菜单点击：左键右键都当「选中」；45 = 返回；53 = 改回自动。
     *
     * <p>「改回自动」写的是<b>空字符串</b>而不是 {@code removeData}：原脚本就是写空串，
     * 而 {@code localGet} 对空串会回落到默认值 ⇒ 语义等价于「未固定」。
     * 走 {@code removeData} 的话会碰到 Slimefun 的<b>排队更新</b>（写完立刻读可能还是旧值），
     * 反而可能出现「点了改回自动、界面还显示固定第 N 条」。</p>
     */
    private static boolean onRecipeMenuClick(Player player, int slot) {
        try {
            Location loc = cfgLocOf(player);
            if (slot == 45) {
                openConfig(player, null);
                return true;
            }
            if (slot == 53) {
                if (loc != null) {
                    StackMachineStore.localSet(loc, StackMachineStore.DATA_RECIPE, "");
                }
                try {
                    player.sendMessage("§a[堆叠机] 已改回自动检测配方");
                } catch (Throwable ignored) {
                    // 忽略
                }
                openRecipeMenu(player);
                return true;
            }
            if (slot >= 0 && slot < 45) {
                String mid = (loc != null) ? StackMachineStore.getMachineId(loc) : null;
                List<StackMachineRecipe.Recipe> recipes = StackMachineRecipe.listOf(mid);
                if (slot >= recipes.size()) return true;
                if (loc != null) {
                    StackMachineStore.localSet(loc, StackMachineStore.DATA_RECIPE, String.valueOf(slot));
                }
                try {
                    StackMachineRecipe.Recipe rec = recipes.get(slot);
                    String tpl = (rec != null && rec.template != null)
                            ? "（需要模板，放配置界面第三行）" : "";
                    player.sendMessage("§a[堆叠机] 已固定第 " + (slot + 1) + " 条配方" + tpl);
                } catch (Throwable ignored) {
                    // 忽略
                }
                openRecipeMenu(player);            // 刷新选中标记
                return true;
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 配方子菜单点击失败: " + t);
        }
        return false;
    }

    /** 配方图标：用配方自身的展示物品（发电机伪配方里是燃料 / 鱼），没有就退回第一个产物。 */
    private static ItemStack iconRecipeEntry(StackMachineRecipe.Recipe rec, int idx, boolean selected) {
        try {
            if (rec == null) return null;
            ItemStack base = rec.display;
            if (base == null && !rec.outputs.isEmpty()) base = rec.outputs.get(0).stack;
            ItemStack stack = (base != null) ? base.clone() : new ItemStack(Material.PAPER, 1);
            stack.setAmount(1);

            List<String> lore = new ArrayList<>();
            lore.add("§7耗时: §f" + rec.ticks + " §7粘液刻");
            boolean asTpl = rec.inputsAreTemplates;
            if (asTpl) {
                List<String> tns = StackMachineRecipe.templateNamesOf(rec);
                lore.add("§7需要模板: "
                        + (tns.isEmpty() ? "§f无" : "§f" + String.join("§7、§f", tns))
                        + "§7（放配置界面第三行，永不消耗）");
            } else {
                StringBuilder ins = new StringBuilder();
                for (int i = 0; i < rec.inputs.size(); i++) {
                    StackMachineRecipe.Input in = rec.inputs.get(i);
                    String nm = StackMachineRecipe.plainName(in.stack);
                    if (nm.isEmpty()) nm = String.valueOf(in.stack.getType());
                    ins.append(i > 0 ? "§7、§f" : "§f").append(nm).append("§7×")
                            .append(in.consume > 0 ? String.valueOf(in.consume)
                                    : ("(不消耗) " + in.need));
                }
                lore.add("§7输入: " + (ins.length() > 0 ? ins.toString() : "§f无"));
            }
            StringBuilder outs = new StringBuilder();
            for (int j = 0; j < rec.outputs.size(); j++) {
                StackMachineRecipe.Output o = rec.outputs.get(j);
                String nm = StackMachineRecipe.plainName(o.stack);
                if (nm.isEmpty()) nm = String.valueOf(o.stack.getType());
                outs.append(j > 0 ? "§7、§f" : "§f").append(nm).append("§7×").append(o.amount);
                if (o.chance < 100.0d) outs.append("§7(").append(trimNum(o.chance)).append("%)");
            }
            lore.add("§7产出: " + (outs.length() > 0 ? outs.toString() : "§f无"));
            if (asTpl) {
                // 已在上面按「需要模板」显示过
            } else if (rec.template != null) {
                String tn = StackMachineRecipe.plainName(rec.template);
                if (tn.isEmpty()) tn = String.valueOf(rec.template.getType());
                lore.add("§7需要模板: §f" + tn + "§7（放配置界面第三行）");
            } else if (rec.templateName != null && !rec.templateName.isEmpty()) {
                lore.add("§7需要模板: §f" + rec.templateName + "§7（放配置界面第三行，永不消耗）");
            }
            lore.add(selected ? "§a当前已选中（点击右下角屏障改回自动检测）" : "§e左键或右键点击选中");
            return named(stack, (selected ? "§a§l" : "§f") + "配方 #" + (idx + 1), lore);
        } catch (Throwable t) {
            TT.log("[叠堆机] 生成配方图标失败: " + t);
            return null;
        }
    }

    /** 配方子菜单的导航按钮。 */
    private static ItemStack iconRecipeNav(Material mat, String name, String loreLine) {
        try {
            List<String> lore = (loreLine == null) ? null : List.of(loreLine);
            return named(new ItemStack(mat, 1), name, lore);
        } catch (Throwable ignored) {
            return new ItemStack(Material.PAPER, 1);
        }
    }

    /** 数字去掉无意义的小数尾巴（33.0 → "33"，33.5 → "33.5"）。 */
    private static String trimNum(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        CFG_SESS.clear();
        LAST_MACHINE.clear();
        PARENT_CACHE.clear();
        CONFIG_CACHE.clear();
        TICK.clear();
        RECIPE_CACHE.clear();
        StackMachineStore.shutdown();
        StackMachineRegistry.shutdown();
        StackMachineRecipe.shutdown();
        StackMachineNet.shutdown();
        StackMachinePower.shutdown();
        StackMachineGenerator.shutdown();
    }
}
