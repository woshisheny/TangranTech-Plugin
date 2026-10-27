package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;

/**
 * 「道法/丹药」的原生实现（对应 {@code scripts/道法/丹药.js}）。
 * 太虚琉璃炉（{@code Tr_txlll}）的完整炼丹小游戏。
 *
 * <p><b>流程</b>：右键琉璃炉打开炼药菜单 → 选一个已购买的丹方 → 「开始炼丹」进入炼丹流程。
 * 炼丹是一串<b>按预定时间铺开的事件</b>，共三类：</p>
 * <ul>
 *   <li><b>加入材料</b>：按提示把指定材料投入（右键投 1 个，Shift 右键按需求一次投完），
 *       投入越快评级越高（≤3s 完美 / ≤5s 优秀 / ≤7s 精良 / ≤9s 合格，超时失败）；</li>
 *   <li><b>加水</b>：投致死量全糖水（超时阈值更紧：3/4/5/6s）；</li>
 *   <li><b>控火</b>：手持异火核心右键抬升火候，<b>必须使用当前选中的异火</b>，选错直接失败；
 *       火候与目标值的偏差决定评级（0→完美，1→优秀，2→精良，3→合格，≥4→失败）。</li>
 * </ul>
 *
 * <p>最终品质由全部事件评级折算：失败 ≥5 次直接炸炉（未入阶时）；完美 ≥5 次为无暇；
 * 否则取出现次数最多的评级，<b>出现并列时判为「？？」</b>（原脚本的这个设计让高品阶不那么好拿）。
 * 丹尊改为按概率直接掷品质（无暇 20% / 绝品 30% / 珍品 30% / ？？ 10% / 凡品 10%）；
 * 丹帝则是<b>点击即出丹</b>（一次 64 颗无暇，不再走流程）。</p>
 *
 * <p><b>炸炉</b>：以炉子为中心，半径 {@code 1 + 已完成事件数} 的立方体里随机摧毁
 * {@code 5 + 完成数×5} 个方块，炉子本身变黑曜石并清掉粘液数据，对周围生物造成
 * {@code 10 + 完成数×10} 点伤害。镇炉珠（{@code TR_ZLZ}）可以抵挡一次炸炉。</p>
 *
 * <p><b>熟练度</b>：每次成功按品质加 10~50 熟练度（上限 2000），品阶为
 * 未入阶 → 丹士(100) → 丹师(300) → 丹宗(700) → 丹尊(1200) → 丹帝(2000)。
 * 开始炼丹每次消耗糖之力 {@code max(0, 50 - 品阶序号×10)}，走
 * {@link RealmScript#consumeSugarPower}（统一到境界那份实现，不再各写一份读文件逻辑）。</p>
 *
 * <p><b>下线中止</b>：炼丹小游戏全程需要本人在场，因此推进任务里检查玩家在线状态，
 * 下线即干净中止（撤投影、停任务、不发丹不炸炉，已扣糖之力作废）——
 * 杜绝「开始炼丹后下线挂机、回来白拿一炉凡品丹」（以及控火事件超时炸掉基地里的炉子）。</p>
 *
 * <p>与 1.21 的适配：投影用 {@link TextDisplay}（原脚本也是），文本走 Adventure。</p>
 */
public final class PillsScript implements NativeLifecycleScript {

    /** 太虚琉璃炉的机器 id。 */
    private static final String MACHINE_ID = "Tr_txlll";

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20};
    private static final int CLOSE_SLOT = 49;
    private static final int START_SLOT = 4;
    private static final int PROTECT_SLOT = 8;
    private static final int HEAD_SLOT = 7;
    private static final int[] OUTER_SLOTS = {9, 17, 18, 26, 27, 35, 36, 44};
    private static final int[] BOTTOM_BLAZE_SLOTS = {45, 46, 47, 48, 50, 51, 52, 53};
    private static final int MENU_SIZE = 54;

    private static final String[] GLASS_COLORS = {
            "RED_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE",
            "LIGHT_BLUE_STAINED_GLASS_PANE", "BLUE_STAINED_GLASS_PANE", "PURPLE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE", "PINK_STAINED_GLASS_PANE",
    };

    /** 控火事件的目标火候上限。 */
    private static final int FIRE_MAX = 20;
    /** 事件推进的 tick 周期。 */
    private static final long TICK_PERIOD = 20L;
    /** 炼丹中零散交互的节流（ms）。 */
    private static final long DEFAULT_INPUT_COOLDOWN_MS = 300L;
    /** 控火抬火候的节流（ms），比默认更短以便快速微调。 */
    private static final long FIRE_INPUT_COOLDOWN_MS = 100L;
    /** 菜单重复打开的节流（ms）。 */
    private static final long MENU_OPEN_THROTTLE_MS = 300L;

    // ------------------------------------------------------------------
    // 丹方
    // ------------------------------------------------------------------

    /** 一个丹方：成品 id + 名字 + 四类材料需求（材料名或粘液 id → 数量）。 */
    private record Formula(String id, String name, String pill,
                           Map<String, Integer> needVanilla,
                           Map<String, Integer> needMinerals,
                           Map<String, Integer> needSpirit,
                           Map<String, Integer> needFdsz) {}

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), (Integer) kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    private static final List<Formula> FORMULAS = List.of(
            new Formula("Tr_dy01", "灵蕴丹", "Tr_dy01",
                    m("WHEAT", 8, "SUGAR_CANE", 4), m("IRON_INGOT", 4),
                    m("Tr_lz01_fruit", 8), m("Tr_fdsz01", 2, "Tr_fdsz02", 1)),
            new Formula("Tr_dy02", "凝露丹", "Tr_dy02",
                    m("ALLIUM", 8, "SUGAR_CANE", 4), m("GOLD_INGOT", 4),
                    m("Tr_lz02_fruit", 8), m("Tr_fdsz03", 2, "Tr_fdsz04", 1)),
            new Formula("Tr_dy03", "赤炎丹", "Tr_dy03",
                    m("NETHER_WART", 8, "SWEET_BERRIES", 4), m("REDSTONE", 4),
                    m("Tr_lz03_fruit", 8), m("Tr_fdsz05", 2, "Tr_fdsz07", 1)),
            new Formula("Tr_dy04", "寒霜丹", "Tr_dy04",
                    m("BEETROOT", 8, "SNOWBALL", 4), m("LAPIS_LAZULI", 4),
                    m("Tr_lz04_fruit", 8), m("Tr_fdsz06", 2, "Tr_fdsz04", 1)),
            new Formula("Tr_dy05", "紫电丹", "Tr_dy05",
                    m("COCOA_BEANS", 8, "AMETHYST_SHARD", 4), m("QUARTZ", 4),
                    m("Tr_lz05_fruit", 8), m("Tr_fdsz05", 2, "Tr_fdsz09", 1)),
            new Formula("Tr_dy06", "金髓丹", "Tr_dy06",
                    m("WHEAT", 8, "SUGAR_CANE", 8), m("GOLD_INGOT", 4),
                    m("Tr_lz06_fruit", 8), m("Tr_fdsz12", 2, "Tr_fdsz15", 1)),
            new Formula("Tr_dy07", "青木丹", "Tr_dy07",
                    m("BROWN_MUSHROOM", 8, "OAK_LOG", 4), m("EMERALD", 4),
                    m("Tr_lz07_fruit", 8), m("Tr_fdsz19", 2, "Tr_fdsz11", 1)),
            new Formula("Tr_dy08", "玄水丹", "Tr_dy08",
                    m("KELP", 8, "LILY_PAD", 4), m("QUARTZ", 4),
                    m("Tr_lz08_fruit", 8), m("Tr_fdsz10", 2, "Tr_fdsz04", 1)),
            new Formula("Tr_dy09", "厚土丹", "Tr_dy09",
                    m("POTATO", 8, "WHEAT_SEEDS", 8), m("IRON_INGOT", 4),
                    m("Tr_lz09_fruit", 8), m("Tr_fdsz14", 2, "Tr_fdsz11", 1)));

    /** 原版材料 → 可替代的粘液物品（灵植 / 福地生珍）。 */
    private static final Map<String, List<String>> VANILLA_SUBSTITUTES = substitutes();

    private static Map<String, List<String>> substitutes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("WHEAT", List.of("Tr_lz01_fruit", "Tr_lz06_fruit"));
        out.put("WHEAT_SEEDS", List.of("Tr_lz01_fruit", "Tr_lz06_fruit"));
        out.put("SUGAR_CANE", List.of("Tr_lz02_fruit"));
        out.put("ALLIUM", List.of("Tr_lz02_fruit"));
        out.put("SWEET_BERRIES", List.of("Tr_lz03_fruit"));
        out.put("NETHER_WART", List.of("Tr_lz07_fruit"));
        out.put("BEETROOT", List.of("Tr_lz04_fruit"));
        out.put("SNOWBALL", List.of("Tr_lz04_fruit"));
        out.put("COCOA_BEANS", List.of("Tr_lz05_fruit"));
        out.put("AMETHYST_SHARD", List.of("Tr_fdsz05"));
        out.put("GOLD_NUGGET", List.of("Tr_fdsz12"));
        out.put("BROWN_MUSHROOM", List.of("Tr_lz07_fruit"));
        out.put("OAK_LOG", List.of("Tr_fdsz19"));
        out.put("KELP", List.of("Tr_lz08_fruit"));
        out.put("LILY_PAD", List.of("Tr_lz08_fruit"));
        out.put("POTATO", List.of("Tr_lz09_fruit"));
        out.put("IRON_INGOT", List.of("Tr_fdsz01", "Tr_fdsz14", "Tr_fdsz15"));
        out.put("GOLD_INGOT", List.of("Tr_fdsz12"));
        out.put("REDSTONE", List.of("Tr_fdsz07"));
        out.put("LAPIS_LAZULI", List.of("Tr_fdsz06"));
        out.put("QUARTZ", List.of("Tr_fdsz03", "Tr_fdsz05"));
        out.put("EMERALD", List.of("Tr_fdsz16"));
        return Collections.unmodifiableMap(out);
    }

    /** 原版材料的中文名。 */
    private static final Map<String, String> MATERIAL_NAMES = materialNames();

    private static Map<String, String> materialNames() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("WHEAT", "小麦");
        out.put("SUGAR_CANE", "甘蔗");
        out.put("ALLIUM", "绒球葱");
        out.put("SWEET_BERRIES", "甜浆果");
        out.put("NETHER_WART", "下界疣");
        out.put("BEETROOT", "甜菜根");
        out.put("SNOWBALL", "雪球");
        out.put("COCOA_BEANS", "可可豆");
        out.put("AMETHYST_SHARD", "紫水晶碎片");
        out.put("GOLD_NUGGET", "金粒");
        out.put("BROWN_MUSHROOM", "棕色蘑菇");
        out.put("OAK_LOG", "橡木原木");
        out.put("KELP", "海带");
        out.put("LILY_PAD", "睡莲");
        out.put("POTATO", "马铃薯");
        out.put("WHEAT_SEEDS", "小麦种子");
        out.put("IRON_INGOT", "铁锭");
        out.put("GOLD_INGOT", "金锭");
        out.put("REDSTONE", "红石粉");
        out.put("LAPIS_LAZULI", "青金石");
        out.put("QUARTZ", "下界石英");
        out.put("EMERALD", "绿宝石");
        return Collections.unmodifiableMap(out);
    }

    /** 丹药材料（灵植 / 福地生珍）的中文名。 */
    private static final Map<String, String> NEED_ITEM_NAMES = needNames();

    private static Map<String, String> needNames() {
        Map<String, String> out = new LinkedHashMap<>();
        String[] fruits = {"灵蕴草穗", "凝露花", "赤炎果", "寒霜参", "紫电藤果", "金髓稻穗",
                "青木芝", "玄水莲子", "厚土根", "曜光葵", "幽影菇", "雷音竹",
                "冰心梅", "火灵椒", "风语藤", "星辰麦"};
        for (int i = 0; i < fruits.length; i++) {
            out.put(String.format("Tr_lz%02d_fruit", i + 1), fruits[i]);
        }
        String[] fdsz = {"星纹铁", "月华砂", "灵犀玉", "青冥石", "紫电晶", "玄冰魄", "赤炎髓",
                "风灵羽", "天机丝", "太一真水", "鸿蒙尘", "万象铜", "涅槃火种", "九幽铁",
                "星辰钢", "混沌源核", "造化玉碟碎片", "大道之种", "永恒神木", "鸿蒙紫晶"};
        for (int i = 0; i < fdsz.length; i++) {
            out.put(String.format("Tr_fdsz%02d", i + 1), fdsz[i]);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 异火 id → 名字（控火事件提示用）。
     *
     * <p><b>这里的名字必须与 {@link #YANHUO_NAME_ARRAY} 逐字一致</b>：控火事件拿随机名字当提示，
     * 玩家抬手时又按 id 反查名字比对，两处对不上就会「提示 X、按 X 却没反应」。</p>
     */
    private static final Map<String, String> YANHUO_NAMES = Map.ofEntries(
            Map.entry("TR_YH01", "玄金焚天炎"), Map.entry("TR_YH02", "青木长生焰"),
            Map.entry("TR_YH03", "沧溟玄水焱"), Map.entry("TR_YH04", "赤霄焚灵炎"),
            Map.entry("TR_YH05", "厚土地心火"), Map.entry("TR_YH06", "曜日净世炎"),
            Map.entry("TR_YH07", "冥渊噬魂火"), Map.entry("TR_YH08", "九霄紫雷炎"),
            Map.entry("TR_YH09", "霜雪凝神焰"));

    private static final String[] YANHUO_NAME_ARRAY = {
            "玄金焚天炎", "青木长生焰", "沧溟玄水焱", "赤霄焚灵炎", "厚土地心火",
            "曜日净世炎", "冥渊噬魂火", "九霄紫雷炎", "霜雪凝神焰"};

    /** 品阶顺序（下标即品阶序号，用于糖之力消耗折算）。 */
    private static final List<String> RANK_ORDER =
            List.of("未入阶", "丹士", "丹师", "丹宗", "丹尊", "丹帝");
    private static final int[] RANK_EXP_REQ = {0, 100, 300, 700, 1200, 2000};
    private static final Map<String, Integer> QUALITY_EXP = Map.of(
            "凡品", 10, "？？", 20, "珍品", 30, "绝品", 40, "无暇", 50);

    private static final Random RANDOM = new Random();

    // ------------------------------------------------------------------
    // 运行时状态
    // ------------------------------------------------------------------

    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();
    /** 菜单 Inventory → 会话（选中丹方 / 炉子位置 / 是否已受防炸赐福）。 */
    private static final Map<Inventory, MenuSession> MENUS = new ConcurrentHashMap<>();
    /** 玩家 UUID → 炼丹状态。 */
    private static final Map<java.util.UUID, State> ALCHEMY = new ConcurrentHashMap<>();
    /** 炉子位置 key → 已受防炸赐福。 */
    private static final Map<String, Boolean> FURNACE_PROTECTED = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次零散交互时间（分别对应默认与控火两档节流）。 */
    private static final Map<java.util.UUID, Long> INPUT_AT = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, Long> FIRE_INPUT_AT = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, Long> MENU_OPEN_AT = new ConcurrentHashMap<>();
    /** 自动炼丹券刚用完的节流（避免同一 tick 又把菜单打开）。 */
    private static final Map<java.util.UUID, Long> AUTO_TICKET_AT = new ConcurrentHashMap<>();

    /** 菜单会话。 */
    private static final class MenuSession {
        String selected;
        Location furnaceLoc;
        boolean protectedFlag;
    }

    /** 一次炼丹过程中「需要玩家投入的一种材料」。 */
    private static final class Mat {
        final String type;   // vanilla / slimefun
        final String id;
        final String name;
        int need;

        Mat(String type, String id, String name, int need) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.need = need;
        }
    }

    /** 一个炼丹事件。 */
    private static final class AlchemyEvent {
        final String type;          // material / water / fire
        List<Mat> materials = new ArrayList<>();
        int amount;                 // 加水事件剩余需要量
        String yanhuo;              // 控火事件要求的异火名

        AlchemyEvent(String type) {
            this.type = type;
        }
    }

    /**
     * 品阶对应的流程参数。
     *
     * <p>注意：记录组件不能叫 {@code wait}（与 {@link Object#wait()} 冲突，javac 直接报
     * 「记录组件名称 wait 非法」），因此改名为 {@code waitMs}。</p>
     */
    private record Config(long total, long eventPhase, int waitMs, int mat, int water, int fire,
                          int remove, int rewardMin, int rewardMax,
                          boolean instant, int count, String quality) {}

    /** 一次炼丹的完整状态。 */
    private static final class State {
        final String playerName;
        final java.util.UUID uuid;
        final Formula formula;
        final String rank;
        final Config config;
        final Location furnaceLoc;
        final long startTime;
        final long totalDuration;
        final long eventPhase;
        final int totalEvents;
        final Deque<AlchemyEvent> queue;
        final List<String> grades = new ArrayList<>();

        AlchemyEvent currentEvent;
        int eventIndex;
        long nextEventStart;
        long eventStartTime;
        long eventDuration;
        int fireLevel;
        int bestFire;
        boolean finalPhase;
        boolean protectedFlag;
        TextDisplay stand;
        BukkitTask task;

        State(String playerName, java.util.UUID uuid, Formula formula, String rank, Config config,
              Location furnaceLoc, Deque<AlchemyEvent> queue) {
            this.playerName = playerName;
            this.uuid = uuid;
            this.formula = formula;
            this.rank = rank;
            this.config = config;
            this.furnaceLoc = furnaceLoc;
            this.startTime = System.currentTimeMillis();
            this.totalDuration = config.total();
            this.eventPhase = config.eventPhase();
            this.totalEvents = config.mat() + config.water() + config.fire();
            this.queue = queue;
            this.nextEventStart = this.startTime;
        }
    }

    @Override
    public Object invoke(String fn, Object... args) {
        // 本脚本只靠监听器驱动，没有对外回调函数
        return null;
    }

    // ------------------------------------------------------------------
    // 基础工具
    // ------------------------------------------------------------------

    private static String sfId(ItemStack stack) {
        return TT.sfIdOf(stack);
    }

    private static boolean isSf(ItemStack stack, String id) {
        String actual = sfId(stack);
        return actual != null && actual.equalsIgnoreCase(id);
    }

    private static String materialName(String matName) {
        return MATERIAL_NAMES.getOrDefault(matName, matName);
    }

    private static String sfName(String id) {
        String known = NEED_ITEM_NAMES.get(id);
        if (known != null) return known;
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf != null) {
                ItemStack it = sf.getItem();
                if (it != null && it.hasItemMeta()) {
                    ItemMeta meta = it.getItemMeta();
                    if (meta != null && meta.hasDisplayName()) {
                        return LegacyComponentSerializer.legacySection().serialize(meta.displayName());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 退到 id
        }
        return id;
    }

    private static ItemStack glass(String name, String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack randomGlass() {
        return glass("§0", GLASS_COLORS[RANDOM.nextInt(GLASS_COLORS.length)]);
    }

    private static ItemStack blazePowder() {
        return glass("§0", "BLAZE_POWDER");
    }

    private static net.kyori.adventure.text.Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private static ItemStack named(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(line.isEmpty()
                            ? net.kyori.adventure.text.Component.empty() : legacy(line));
                }
                meta.lore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Formula formulaById(String id) {
        if (id == null) return null;
        for (Formula f : FORMULAS) {
            if (f.id().equals(id)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 丹方权限存档
    // ------------------------------------------------------------------

    private static File danfangFile(String playerName) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_DANFANG), PlayerKeys.key(playerName) + ".json");
    }

    private static List<String> loadOwned(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(danfangFile(playerName));
        if (data == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(data, "owned")) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static void saveOwned(String playerName, List<String> owned) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owned", owned);
        // 保留原有 exp（原脚本显式保 exp）
        Map<String, Object> old = AddonPaths.readJson(danfangFile(playerName));
        if (old != null && old.get("exp") != null) data.put("exp", old.get("exp"));
        AddonPaths.writeJson(danfangFile(playerName), data);
    }

    private static int danfangExp(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(danfangFile(playerName));
        return data == null ? 0 : Json.i(data, "exp", 0);
    }

    private static void saveExp(String playerName, int exp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owned", loadOwned(playerName));
        data.put("exp", exp);
        AddonPaths.writeJson(danfangFile(playerName), data);
    }

    private static int addExp(String playerName, int amount) {
        int exp = Math.min(2000, danfangExp(playerName) + amount);
        saveExp(playerName, exp);
        return exp;
    }

    private static boolean hasDanfang(String playerName, String id) {
        for (String o : loadOwned(playerName)) {
            if (o.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 品阶
    // ------------------------------------------------------------------

    private static String rankOf(int exp) {
        if (exp >= 2000) return "丹帝";
        if (exp >= 1200) return "丹尊";
        if (exp >= 700) return "丹宗";
        if (exp >= 300) return "丹师";
        if (exp >= 100) return "丹士";
        return "未入阶";
    }

    private static int nextRankExp(int exp) {
        for (int i = 0; i < RANK_EXP_REQ.length - 1; i++) {
            if (exp < RANK_EXP_REQ[i + 1]) return RANK_EXP_REQ[i + 1];
        }
        return 2000;
    }

    /** 该品阶至少需要完成几个事件才能拿熟练度；丹尊及以上不设限制。 */
    private static int minCompletedForRank(String rank) {
        return switch (rank) {
            case "丹士" -> 3;
            case "丹师" -> 2;
            case "丹宗" -> 1;
            default -> 0;
        };
    }

    private static Config configOf(String rank) {
        return switch (rank) {
            case "丹士" -> new Config(120000, 90000, 30000, 3, 1, 2, 1, 12, 20, false, 0, null);
            case "丹师" -> new Config(90000, 60000, 30000, 2, 1, 1, 2, 16, 24, false, 0, null);
            case "丹宗" -> new Config(60000, 60000, 0, 1, 0, 1, 2, 20, 24, false, 0, null);
            case "丹尊" -> new Config(30000, 30000, 0, 1, 0, 0, 999, 32, 32, false, 0, null);
            case "丹帝" -> new Config(0, 0, 0, 0, 0, 0, 0, 0, 0, true, 64, "无暇");
            default -> new Config(150000, 130000, 20000, 4, 2, 2, 0, 8, 16, false, 0, null);
        };
    }

    // ------------------------------------------------------------------
    // 材料匹配 / 统计 / 扣除
    // ------------------------------------------------------------------

    private static int countMaterial(Player player, String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) return 0;
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (stack.getType() == mat) count += stack.getAmount();
        }
        return count;
    }

    private static boolean takeMaterial(Player player, String matName, int amount) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) return false;
        if (countMaterial(player, matName) < amount) return false;
        PlayerInventory inv = player.getInventory();
        int remain = amount;
        for (int i = 0; i < 36 && remain > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir() || stack.getType() != mat) continue;
            int take = Math.min(stack.getAmount(), remain);
            int left = stack.getAmount() - take;
            if (left <= 0) {
                inv.setItem(i, null);
            } else {
                stack.setAmount(left);
            }
            remain -= take;
        }
        return true;
    }

    private static int countSlimefun(Player player, String id) {
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String actual = sfId(stack);
            if (actual != null && actual.equalsIgnoreCase(id)) count += stack.getAmount();
        }
        return count;
    }

    private static boolean takeSlimefun(Player player, String id, int amount) {
        if (countSlimefun(player, id) < amount) return false;
        PlayerInventory inv = player.getInventory();
        int remain = amount;
        for (int i = 0; i < 36 && remain > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String actual = sfId(stack);
            if (actual == null || !actual.equalsIgnoreCase(id)) continue;
            int take = Math.min(stack.getAmount(), remain);
            int left = stack.getAmount() - take;
            if (left <= 0) {
                inv.setItem(i, null);
            } else {
                stack.setAmount(left);
            }
            remain -= take;
        }
        return true;
    }

    private static boolean hasRoomFor(Player player, ItemStack stack) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(stack) && s.getAmount() < s.getMaxStackSize()) return true;
        }
        return false;
    }

    /** 一键炼制（菜单里不直接用，保留供将来扩展）。 */
    private static void craftPill(Player player, Formula formula) {
        try {
            if (!hasDanfang(player.getName(), formula.id())) {
                player.sendMessage("§c您还没有这个丹方！");
                return;
            }
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Integer> e : formula.needVanilla().entrySet()) {
                if (countMaterial(player, e.getKey()) < e.getValue()) {
                    missing.add(materialName(e.getKey()) + " x" + e.getValue());
                }
            }
            for (Map.Entry<String, Integer> e : formula.needMinerals().entrySet()) {
                if (countMaterial(player, e.getKey()) < e.getValue()) {
                    missing.add(materialName(e.getKey()) + " x" + e.getValue());
                }
            }
            for (Map.Entry<String, Integer> e : formula.needSpirit().entrySet()) {
                if (countSlimefun(player, e.getKey()) < e.getValue()) {
                    missing.add(sfName(e.getKey()) + " x" + e.getValue());
                }
            }
            for (Map.Entry<String, Integer> e : formula.needFdsz().entrySet()) {
                if (countSlimefun(player, e.getKey()) < e.getValue()) {
                    missing.add(sfName(e.getKey()) + " x" + e.getValue());
                }
            }
            if (!missing.isEmpty()) {
                player.sendMessage("§c材料不足，缺少：§f" + String.join("、", missing));
                return;
            }
            SlimefunItem sf = SlimefunItem.getById(formula.pill());
            if (sf == null || sf.getItem() == null) return;
            ItemStack stack = sf.getItem().clone();
            if (!hasRoomFor(player, stack)) {
                player.sendMessage("§c背包已满，无法炼制！");
                return;
            }
            for (Map.Entry<String, Integer> e : formula.needVanilla().entrySet()) {
                takeMaterial(player, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : formula.needMinerals().entrySet()) {
                takeMaterial(player, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : formula.needSpirit().entrySet()) {
                takeSlimefun(player, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : formula.needFdsz().entrySet()) {
                takeSlimefun(player, e.getKey(), e.getValue());
            }
            player.getInventory().addItem(stack);
            player.sendMessage("§a炼制成功！获得 §b" + formula.name() + "§a。");
            player.playSound(player.getLocation(), "block.brewing_stand.brew", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    private static ItemStack pillStack(Formula formula) {
        try {
            for (String candidate : new String[]{
                    formula.pill(), formula.pill().toUpperCase(Locale.ROOT),
                    formula.pill().toLowerCase(Locale.ROOT)}) {
                SlimefunItem sf = SlimefunItem.getById(candidate);
                if (sf != null && sf.getItem() != null) return sf.getItem().clone();
            }
        } catch (Throwable ignored) {
            // 落到兜底
        }
        return named(Material.GLOWSTONE_DUST, "§d" + formula.name(), List.of());
    }

    private static ItemStack headStack(Player player) {
        int exp = danfangExp(player.getName());
        String rank = rankOf(exp);
        int next = nextRankExp(exp);
        List<String> lore = List.of("§7熟练度:" + exp + "/" + next, "§7", "§7品阶:" + rank);
        try {
            ItemStack head = named(Material.PLAYER_HEAD, "§e§l熟练度", lore);
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof SkullMeta skull) skull.setOwningPlayer(player);
            head.setItemMeta(meta);
            return head;
        } catch (Throwable ignored) {
            return named(Material.GRAY_STAINED_GLASS_PANE, "§e§l熟练度", lore);
        }
    }

    private static Inventory buildMenu(Player player, String selectedId, boolean protectedFlag) {
        String playerName = player.getName();
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy("§d太虚琉璃炉 · 炼药"));

        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        for (int slot : OUTER_SLOTS) inv.setItem(slot, randomGlass());

        inv.setItem(0, named(Material.PAPER, "§e§l注意事项", List.of(
                "§71.请先前往浮生当铺购买丹方",
                "§72.选择丹方，准备好材料与异火，点击\"开始炼丹\"进入炼丹流程",
                "§73.炼丹要把握好材料投入的量、时机,并使用异火控制火候",
                "§74.若操作不当、火候过大或炼丹超时，会炸炉并破坏周围地形哦",
                "§75.丹药分品质，品质根据炼丹流程的表现评判",
                "§76.投入材料(右键): 消耗手上1个对应材料",
                "§77.投入材料(Shift右键): 按需求数量智能消耗并完成",
                "§78.控火事件必须使用提示的异火，选错会直接判定失败")));

        inv.setItem(START_SLOT, glass("§a§l开始炼丹", "LIME_STAINED_GLASS_PANE"));

        if (protectedFlag) {
            inv.setItem(PROTECT_SLOT, named(Material.LIME_STAINED_GLASS_PANE,
                    "§b§l已受防炸赐福", List.of("§7此炉已受镇炉珠保护", "§7下次炸炉会被抵挡一次")));
        } else {
            inv.setItem(PROTECT_SLOT, named(Material.ENDER_PEARL, "§6镇炉珠", List.of(
                    "§7点击消耗背包中的1个镇炉珠", "§7为本次炼丹添加一次防炸赐福")));
        }

        inv.setItem(HEAD_SLOT, headStack(player));
        for (int slot : BOTTOM_BLAZE_SLOTS) inv.setItem(slot, blazePowder());
        inv.setItem(CLOSE_SLOT, glass("§c关闭", "BARRIER"));

        for (int i = 0; i < FORMULAS.size() && i < SLOTS.length; i++) {
            Formula formula = FORMULAS.get(i);
            if (hasDanfang(playerName, formula.id())) {
                ItemStack stack = pillStack(formula);
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    List<String> lore = new ArrayList<>();
                    lore.add(formula.id().equalsIgnoreCase(selectedId) ? "§a√ 已选择" : "§e点击选择该丹方");
                    lore.add("§7需要材料：");
                    for (String mat : formula.needVanilla().keySet()) lore.add("  §f" + materialName(mat));
                    for (String mat : formula.needMinerals().keySet()) lore.add("  §f" + materialName(mat));
                    for (String sp : formula.needSpirit().keySet()) lore.add("  §d" + sfName(sp));
                    for (String fd : formula.needFdsz().keySet()) lore.add("  §d" + sfName(fd));
                    lore.add("  §d致死量全糖水");
                    List<net.kyori.adventure.text.Component> comp = new ArrayList<>();
                    for (String line : lore) comp.add(legacy(line));
                    meta.lore(comp);
                    stack.setItemMeta(meta);
                }
                inv.setItem(SLOTS[i], stack);
            } else {
                inv.setItem(SLOTS[i], named(Material.GRAY_STAINED_GLASS_PANE, "§7未获得丹方", List.of(
                        "§7请到浮生当铺·浮生绝品·丹方购买类购买",
                        "§7丹方：§e" + formula.name())));
            }
        }
        return inv;
    }

    private static void refresh(Inventory inv, Player player) {
        MenuSession sess = MENUS.get(inv);
        String selected = sess == null ? null : sess.selected;
        boolean prot = sess != null && sess.protectedFlag;
        Inventory fresh = buildMenu(player, selected, prot);
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, fresh.getItem(i));
    }

    private static void openMenu(Player player, Location furnaceLoc) {
        try {
            java.util.UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = MENU_OPEN_AT.get(uuid);
            if (last != null && now - last < MENU_OPEN_THROTTLE_MS) return;
            MENU_OPEN_AT.put(uuid, now);

            boolean prot = furnaceLoc != null
                    && FURNACE_PROTECTED.getOrDefault(locKey(furnaceLoc), false);
            State active = ALCHEMY.get(uuid);
            if (active != null && active.protectedFlag) prot = true;

            Inventory inv = buildMenu(player, null, prot);
            MenuSession sess = new MenuSession();
            sess.furnaceLoc = furnaceLoc == null ? null : furnaceLoc.clone();
            sess.protectedFlag = prot;
            MENUS.put(inv, sess);
            ACTIVE.add(inv);

            player.playSound(player.getLocation(), "block.brewing_stand.brew", 0.8f, 1.2f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[丹药] 打开炼药菜单失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 事件生成
    // ------------------------------------------------------------------

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + ","
                + loc.getBlockZ();
    }

    /** 材料优先级：福地生珍先删、原版居中、灵植最后删。 */
    private static int matPriority(Mat mat) {
        if ("slimefun".equals(mat.type)
                && mat.id.toLowerCase(Locale.ROOT).startsWith("tr_fdsz")) return 0;
        return "vanilla".equals(mat.type) ? 1 : 2;
    }

    private static void shuffle(List<?> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }

    /** 按丹方生成材料事件（会按品阶删掉若干种材料）。 */
    private static List<AlchemyEvent> generateMaterialEvents(Formula formula, int removeCount,
                                                             int eventCount) {
        List<Mat> all = new ArrayList<>();
        for (String mat : formula.needVanilla().keySet()) {
            all.add(new Mat("vanilla", mat, materialName(mat), 0));
        }
        for (String mat : formula.needMinerals().keySet()) {
            all.add(new Mat("vanilla", mat, materialName(mat), 0));
        }
        for (String sp : formula.needSpirit().keySet()) {
            all.add(new Mat("slimefun", sp, sfName(sp), 0));
        }
        for (String fd : formula.needFdsz().keySet()) {
            all.add(new Mat("slimefun", fd, sfName(fd), 0));
        }

        all.sort((a, b) -> Integer.compare(matPriority(a), matPriority(b)));
        if (removeCount > 0) {
            if (removeCount >= all.size()) {
                // 至少留最后两种
                all = new ArrayList<>(all.subList(Math.max(0, all.size() - 2), all.size()));
            } else {
                all = new ArrayList<>(all.subList(removeCount, all.size()));
            }
        }

        shuffle(all);
        List<List<Mat>> groups = new ArrayList<>();
        int groupCount = Math.max(1, eventCount);
        for (int g = 0; g < groupCount; g++) groups.add(new ArrayList<>());
        for (int i = 0; i < all.size(); i++) {
            groups.get(i % groups.size()).add(all.get(i));
        }

        List<AlchemyEvent> events = new ArrayList<>();
        for (List<Mat> group : groups) {
            if (group.isEmpty()) continue;
            AlchemyEvent ev = new AlchemyEvent("material");
            for (Mat base : group) {
                ev.materials.add(new Mat(base.type, base.id, base.name, 1 + RANDOM.nextInt(4)));
            }
            events.add(ev);
        }
        return events;
    }

    private static Deque<AlchemyEvent> generateQueue(Formula formula, Config config) {
        List<AlchemyEvent> mats = generateMaterialEvents(formula, config.remove(), config.mat());
        AlchemyEvent first = mats.isEmpty() ? null : mats.remove(0);

        List<AlchemyEvent> rest = new ArrayList<>(mats);
        for (int i = 0; i < config.water(); i++) {
            AlchemyEvent ev = new AlchemyEvent("water");
            ev.amount = 1 + RANDOM.nextInt(3);
            rest.add(ev);
        }
        for (int i = 0; i < config.fire(); i++) {
            AlchemyEvent ev = new AlchemyEvent("fire");
            ev.yanhuo = YANHUO_NAME_ARRAY[RANDOM.nextInt(YANHUO_NAME_ARRAY.length)];
            rest.add(ev);
        }
        shuffle(rest);

        Deque<AlchemyEvent> queue = new ArrayDeque<>();
        if (first != null) queue.add(first);
        queue.addAll(rest);
        return queue;
    }

    private static String gradeByTime(String type, long elapsedMs) {
        double s = elapsedMs / 1000.0;
        if ("material".equals(type)) {
            if (s <= 3) return "完美";
            if (s <= 5) return "优秀";
            if (s <= 7) return "精良";
            if (s <= 9) return "合格";
            return "失败";
        }
        if ("water".equals(type)) {
            if (s <= 3) return "完美";
            if (s <= 4) return "优秀";
            if (s <= 5) return "精良";
            if (s <= 6) return "合格";
            return "失败";
        }
        return "失败";
    }

    private static int completedCount(State state) {
        int c = 0;
        for (String g : state.grades) {
            if (!"失败".equals(g)) c++;
        }
        return c;
    }

    // ------------------------------------------------------------------
    // 投影显示
    // ------------------------------------------------------------------

    private static String fireDisplay(State state) {
        StringBuilder bars = new StringBuilder();
        for (int i = 1; i <= FIRE_MAX; i++) {
            if (i == state.bestFire && i == state.fireLevel) {
                bars.append("§a▮");
            } else if (i == state.bestFire) {
                bars.append("§c▮");
            } else if (i <= state.fireLevel) {
                bars.append(i > state.bestFire + 4 ? "§6▮" : "§a▮");
            } else {
                bars.append("§7▮");
            }
        }
        return bars.toString();
    }

    private static void updateProjection(State state) {
        try {
            long now = System.currentTimeMillis();
            long remain = Math.max(0,
                    (long) Math.ceil((state.totalDuration - (now - state.startTime)) / 1000.0));
            List<String> lines = new ArrayList<>();
            lines.add("§e倒计时: " + remain + "s");

            if (state.finalPhase) {
                lines.add("§a即将成丹...");
            } else if (state.currentEvent != null) {
                AlchemyEvent ev = state.currentEvent;
                long evRemain = Math.max(0, (long) Math.ceil(
                        (state.eventStartTime + state.eventDuration - now) / 1000.0));
                if ("material".equals(ev.type)) {
                    lines.add("§b加入材料 §7(" + evRemain + "s)");
                    List<String> parts = new ArrayList<>();
                    for (Mat m : ev.materials) parts.add(m.name + " x" + m.need);
                    lines.add("§f需要: " + String.join("、", parts));
                } else if ("water".equals(ev.type)) {
                    lines.add("§b加水 §7(" + evRemain + "s)");
                    lines.add("§f需要: 致死量全糖水 x" + ev.amount);
                } else if ("fire".equals(ev.type)) {
                    lines.add("§c控火 §7(" + evRemain + "s)");
                    lines.add("§f使用异火: " + ev.yanhuo);
                    lines.add("§7当前火候: " + fireDisplay(state));
                }
            } else {
                lines.add(state.eventIndex < state.totalEvents
                        ? "§7等待下一事件..." : "§7等待成丹...");
            }

            if (state.stand != null && state.stand.isValid()) {
                state.stand.text(legacy(String.join("\n", lines)));
            }
        } catch (Throwable ignored) {
            // 投影失败不影响流程
        }
    }

    private static void startEvent(State state, Player player) {
        AlchemyEvent ev = state.queue.poll();
        if (ev == null) return;
        state.currentEvent = ev;
        state.eventIndex++;
        state.eventStartTime = System.currentTimeMillis();
        state.nextEventStart = state.startTime
                + (long) Math.floor(state.eventIndex * (double) state.eventPhase / state.totalEvents);

        if ("material".equals(ev.type)) {
            state.eventDuration = 10000 + RANDOM.nextInt(2001);
        } else if ("water".equals(ev.type)) {
            state.eventDuration = 7000 + RANDOM.nextInt(1001);
        } else {
            state.eventDuration = 10000;
            state.fireLevel = 0;
            state.bestFire = 5 + RANDOM.nextInt(11);
            try {
                player.sendActionBar("§7当前火候: " + fireDisplay(state));
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        updateProjection(state);
        try {
            player.playSound(player.getLocation(), "block.note_block.pling", 1.0f, 1.5f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 炼丹主循环
    // ------------------------------------------------------------------

    private static void startAlchemy(Player player, Formula formula, Location furnaceLoc,
                                     boolean protectedFlag) {
        java.util.UUID uuid = player.getUniqueId();
        if (ALCHEMY.containsKey(uuid)) {
            player.sendMessage("§c你已经在炼丹中！");
            return;
        }

        int exp = danfangExp(player.getName());
        String rank = rankOf(exp);
        Config config = configOf(rank);

        // 未开辟境界不能炼丹
        if (!RealmScript.hasRealm(player)) {
            player.sendMessage("§c请先使用洗髓丹开辟境界！");
            return;
        }

        // 每次开始炼丹消耗糖之力；品阶每提升一级少 10 点
        int rankIndex = Math.max(0, RANK_ORDER.indexOf(rank));
        double sugarCost = Math.max(0, 50 - rankIndex * 10);
        if (!RealmScript.consumeSugarPower(player, sugarCost)) {
            player.sendMessage("§c糖之力不足，需要" + (int) sugarCost + "糖之力才能开始炼丹！");
            return;
        }

        // 丹帝：点击开始直接出丹
        if (config.instant()) {
            spawnPillReward(player, formula, config.quality(), furnaceLoc, config.count());
            player.sendMessage("§6丹帝出手，丹药自成！获得 " + config.count() + " 颗无暇丹药！");
            return;
        }

        State state = new State(player.getName(), uuid, formula, rank, config,
                furnaceLoc.clone(), generateQueue(formula, config));
        state.protectedFlag = protectedFlag;
        ALCHEMY.put(uuid, state);

        try {
            TextDisplay stand = furnaceLoc.getWorld().spawn(
                    furnaceLoc.clone().add(0.5, 2.2, 0.5), TextDisplay.class);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.text(legacy("§e倒计时: " + Math.ceil(config.total() / 1000.0) + "s"));
            try {
                stand.setBillboard(Display.Billboard.CENTER);
            } catch (Throwable ignored) {
                // 旧服务端无此属性
            }
            state.stand = stand;
        } catch (Throwable ignored) {
            // 投影生成失败不影响流程
        }

        startEvent(state, player);

        final State st = state;
        st.task = Bukkit.getScheduler().runTaskTimer(
                com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(),
                () -> tickAlchemy(player, st), 0L, TICK_PERIOD);
    }

    private static void tickAlchemy(Player player, State state) {
        try {
            // 玩家下线 → 干净中止本次炼丹。
            //
            // 炼丹是全程需要本人操作的小游戏（投料/加水/控火都有秒级窗口）。
            // 早先这里不检查在线状态：下线后计时任务照跑，剩余事件全部按超时自动
            // 「失败」，到点照样 spawnPillReward 掉一炉凡品丹（挂机白拿），
            // 控火事件还会因火候不动而判失败炸掉基地里的炉子。
            // 现在人不在线就中止：撤投影、停任务、清状态，不发丹也不炸炉；
            // 开始炼丹时扣的糖之力视为本次尝试作废（与主动破坏炉鼎的炸炉惩罚区分开）。
            if (player == null || !player.isOnline()) {
                TT.log("[丹药] 玩家 " + state.playerName + " 下线，本次炼丹中止（未发丹）");
                abortAlchemy(state);
                return;
            }

            long now = System.currentTimeMillis();
            long elapsed = now - state.startTime;

            if (elapsed >= state.totalDuration) {
                cancel(state.task);
                finishAlchemy(player, state.formula, state);
                return;
            }
            if (elapsed >= state.eventPhase) state.finalPhase = true;
            updateProjection(state);

            // 按预定时间平滑开始下一个事件
            if (state.currentEvent == null && state.eventIndex < state.totalEvents
                    && now >= state.nextEventStart) {
                startEvent(state, player);
            }

            if (state.currentEvent != null
                    && now >= state.eventStartTime + state.eventDuration) {
                String grade = "失败";
                if ("fire".equals(state.currentEvent.type)) {
                    int diff = Math.abs(state.fireLevel - state.bestFire);
                    grade = diff == 0 ? "完美" : diff == 1 ? "优秀" : diff == 2 ? "精良"
                            : diff == 3 ? "合格" : "失败";
                }
                completeEvent(player, state, grade);
            }
        } catch (Throwable ignored) {
            // 炼丹循环绝不抛异常
        }
    }

    private static void completeEvent(Player player, State state, String grade) {
        state.grades.add(grade);
        if (state.currentEvent != null && "fire".equals(state.currentEvent.type)
                && "失败".equals(grade)) {
            if (state.protectedFlag) {
                state.protectedFlag = false;
                FURNACE_PROTECTED.remove(locKey(state.furnaceLoc));
                player.sendMessage("§b镇炉珠替你挡下了一次炸炉！");
                player.sendMessage("§e请再次使用一颗镇炉珠防止下次异火选择错误炸炉！");
                nextEvent(state);
                return;
            }
            boomFurnace(player, state, completedCount(state));
            return;
        }
        nextEvent(state);
    }

    private static void nextEvent(State state) {
        state.currentEvent = null;
        updateProjection(state);
    }

    // ------------------------------------------------------------------
    // 炸炉
    // ------------------------------------------------------------------

    private static void removeProjection(State state) {
        try {
            if (state.stand != null && state.stand.isValid()) state.stand.remove();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void boomFurnace(Player player, State state, int completed) {
        try {
            removeProjection(state);
            cancel(state.task);
            ALCHEMY.remove(state.uuid);
            FURNACE_PROTECTED.remove(locKey(state.furnaceLoc));

            Location loc = state.furnaceLoc;
            World world = loc.getWorld();
            int r = 1 + completed;
            int targetCount = 5 + completed * 5;

            List<Block> candidates = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Block b = world.getBlockAt(loc.getBlockX() + dx,
                                loc.getBlockY() + dy, loc.getBlockZ() + dz);
                        if (b.getType() == Material.AIR) continue;
                        if (b.getX() == loc.getBlockX() && b.getY() == loc.getBlockY()
                                && b.getZ() == loc.getBlockZ()) continue;
                        candidates.add(b);
                    }
                }
            }
            shuffle(candidates);
            int destroyed = Math.min(targetCount, candidates.size());
            for (int k = 0; k < destroyed; k++) {
                try {
                    candidates.get(k).setType(Material.AIR);
                } catch (Throwable ignored) {
                    // 单块失败跳过
                }
            }

            Block furnace = world.getBlockAt(loc);
            try {
                BlockStorage.clearBlockInfo(furnace);
            } catch (Throwable ignored) {
                // 忽略
            }
            furnace.setType(Material.OBSIDIAN);

            playSoundNearby(world, loc, "entity.generic.explode", 1.5f, 0.8f, 16);
            playSoundNearby(world, loc, "entity.generic.explode", 1.5f, 1.2f, 16);
            playSoundNearby(world, loc, "block.rail", 1.5f, 1.1f, 16);
            playSoundNearby(world, loc, "entity.lightning_bolt.thunder", 1.5f, 0.7f, 16);

            // 对周围非无敌生物造成真实伤害：基础 10 点，每完成 1 次事件 +10 点
            double damage = 10 + completed * 10.0;
            try {
                for (Entity ent : world.getNearbyEntities(loc, r, r, r)) {
                    if (!(ent instanceof LivingEntity living)) continue;
                    if (living.isInvulnerable()) continue;
                    try {
                        living.setNoDamageTicks(0);
                        living.damage(damage);
                    } catch (Throwable ignored) {
                        // 单体失败跳过
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            player.sendMessage("§c炸炉了！太虚琉璃炉已被彻底摧毁。");
        } catch (Throwable ignored) {
            // 炸炉本身失败不应让异常冒出去
        }
    }

    private static void playSoundNearby(World world, Location center, String sound,
                                       float volume, float pitch, double radius) {
        try {
            for (Player p : world.getPlayers()) {
                try {
                    if (p.getLocation().distance(center) <= radius) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                } catch (Throwable ignored) {
                    // 跨世界等异常跳过
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 结算
    // ------------------------------------------------------------------

    private static String rollDanZunQuality() {
        double r = RANDOM.nextDouble();
        if (r < 0.20) return "无暇";
        if (r < 0.50) return "绝品";
        if (r < 0.80) return "珍品";
        if (r < 0.90) return "？？";
        return "凡品";
    }

    /**
     * 由全部事件评级折算品质。
     *
     * <p>规则：失败 ≥5 次 → 炸炉；完美 ≥5 次 → 无暇；否则取出现次数最多的评级，
     * <b>出现并列时判为「？？」</b>。</p>
     */
    private static String qualityOf(List<String> grades) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String g : new String[]{"完美", "优秀", "精良", "合格", "失败"}) counts.put(g, 0);
        for (String g : grades) counts.merge(g, 1, Integer::sum);

        if (counts.getOrDefault("失败", 0) >= 5) return "炸炉";
        if (counts.getOrDefault("完美", 0) >= 5) return "无暇";

        int max = 0;
        String maxGrade = "合格";
        boolean tie = false;
        for (String g : new String[]{"完美", "优秀", "精良", "合格", "失败"}) {
            int c = counts.getOrDefault(g, 0);
            if (c > max) {
                max = c;
                maxGrade = g;
                tie = false;
            } else if (c == max && c > 0) {
                tie = true;
            }
        }
        if (tie) return "？？";
        return switch (maxGrade) {
            case "完美" -> "无暇";
            case "优秀" -> "绝品";
            case "精良" -> "珍品";
            default -> "凡品";
        };
    }

    private static void finishAlchemy(Player player, Formula formula, State state) {
        try {
            int fail = 0;
            for (String g : state.grades) {
                if ("失败".equals(g)) fail++;
            }
            // 非未入阶不再有「失败次数多就炸炉」的特性
            if (fail >= 5 && "未入阶".equals(state.rank)) {
                if (state.protectedFlag) {
                    state.protectedFlag = false;
                    FURNACE_PROTECTED.remove(locKey(state.furnaceLoc));
                    removeProjection(state);
                    cancel(state.task);
                    ALCHEMY.remove(state.uuid);
                    player.sendMessage("§b镇炉珠替你挡下了一次炸炉！炼丹失败，但炉子保住了。");
                    return;
                }
                boomFurnace(player, state, completedCount(state));
                return;
            }

            removeProjection(state);
            cancel(state.task);
            ALCHEMY.remove(state.uuid);

            String quality = "丹尊".equals(state.rank) ? rollDanZunQuality() : qualityOf(state.grades);
            Config config = state.config;
            int count = (config == null)
                    ? (8 + RANDOM.nextInt(9))
                    : (config.rewardMin()
                    + RANDOM.nextInt(config.rewardMax() - config.rewardMin() + 1));
            spawnPillReward(player, formula, quality, state.furnaceLoc, count);

            int expGain = QUALITY_EXP.getOrDefault(quality, 10);
            int completed = completedCount(state);
            int minCompleted = minCompletedForRank(state.rank);
            if (completed >= minCompleted) {
                int newExp = addExp(player.getName(), expGain);
                player.sendMessage("§e熟练度 +" + expGain + "（当前 " + newExp + "）");
            } else {
                player.sendMessage("§c完成事件不足（至少 " + minCompleted + " 次），本次无法获得熟练度！");
            }
            player.sendMessage("§a炼丹成功！丹药品质：" + quality + "，丹药正从炉中飞出...");
            player.playSound(player.getLocation(), "block.brewing_stand.brew", 1.0f, 1.5f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 自动炼丹券：立即按掷出的品质结算。 */
    private static void completeAlchemyWithQuality(Player player, State state, String quality) {
        try {
            removeProjection(state);
            cancel(state.task);
            ALCHEMY.remove(state.uuid);

            Config config = state.config;
            int count = (config == null)
                    ? (8 + RANDOM.nextInt(9))
                    : (config.rewardMin()
                    + RANDOM.nextInt(config.rewardMax() - config.rewardMin() + 1));
            spawnPillReward(player, state.formula, quality, state.furnaceLoc, count);

            int expGain = QUALITY_EXP.getOrDefault(quality, 10);
            int newExp = addExp(player.getName(), expGain);
            player.sendMessage("§a自动炼丹完成！品质：" + quality + "，熟练度 +" + expGain
                    + "（当前 " + newExp + "）");
            player.playSound(player.getLocation(), "block.brewing_stand.brew", 1.0f, 1.5f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 弹出丹药奖励：先在炉子上方堆出一批不可拾取、无重力的掉落物，
     * 全部升到 +4 格后转为「飞向玩家」，靠近 1.5 格才允许拾取，200 刻后自动清理。
     */
    private static void spawnPillReward(Player player, Formula formula, String quality,
                                       Location furnaceLoc, int count) {
        try {
            int n = count <= 0 ? (8 + RANDOM.nextInt(9)) : count;

            ItemStack stack = pillStack(formula);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                List<net.kyori.adventure.text.Component> existing = meta.lore();
                if (existing != null) lore.addAll(existing);
                lore.add(legacy("§e品质：" + quality));
                meta.lore(lore);
                stack.setItemMeta(meta);
            }

            World world = furnaceLoc.getWorld();
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Item it = world.dropItem(furnaceLoc.clone().add(0.5, 0.5, 0.5), stack.clone());
                it.setPickupDelay(32767);
                it.setGravity(false);
                items.add(it);
            }

            final double targetY = furnaceLoc.getY() + 4;
            final Item[] arr = items.toArray(new Item[0]);
            final String[] phase = {"rise"};

            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(
                    com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(), () -> {
                        try {
                            if ("rise".equals(phase[0])) {
                                boolean allRisen = true;
                                for (int i = 0; i < arr.length; i++) {
                                    Item it = arr[i];
                                    if (it == null || !it.isValid()) continue;
                                    Location loc = it.getLocation();
                                    if (loc.getY() < targetY) {
                                        loc.add(0, 0.25, 0);
                                        it.teleport(loc);
                                        allRisen = false;
                                    }
                                }
                                if (allRisen) phase[0] = "seek";
                            } else {
                                Location playerLoc = player.getLocation().clone().add(0, 1, 0);
                                for (int i = 0; i < arr.length; i++) {
                                    Item it = arr[i];
                                    if (it == null || !it.isValid()) continue;
                                    Location iloc = it.getLocation();
                                    if (iloc.getWorld() != playerLoc.getWorld()) {
                                        arr[i] = null;
                                        continue;
                                    }
                                    double dist = iloc.distance(playerLoc);
                                    if (dist <= 1.5) {
                                        it.setPickupDelay(0);
                                        it.setGravity(true);
                                        final Item captured = it;
                                        com.tangran.tangrantech.native_scripts.NativePluginHelper
                                                .runLater(() -> {
                                                    try {
                                                        if (captured.isValid()) captured.remove();
                                                    } catch (Throwable ignored) {
                                                        // 忽略
                                                    }
                                                }, 200L);
                                        arr[i] = null;
                                    } else {
                                        Vector vec = playerLoc.toVector().subtract(iloc.toVector());
                                        if (vec.lengthSquared() < 0.0001) continue;
                                        vec.normalize().multiply(0.25);
                                        it.setVelocity(vec);
                                    }
                                }
                                boolean any = false;
                                for (Item it : arr) {
                                    if (it != null) {
                                        any = true;
                                        break;
                                    }
                                }
                                if (!any) cancel(holder[0]);
                            }
                        } catch (Throwable ignored) {
                            // 单帧失败跳过
                        }
                    }, 0L, 1L);
        } catch (Throwable ignored) {
            // 奖励动画失败不影响成丹结果
        }
    }

    private static void cancel(BukkitTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {
            // 已结束
        }
    }

    /**
     * 干净中止一次炼丹：撤投影、停任务、清状态。
     *
     * <p>目前唯一调用方是 {@link #tickAlchemy} 的「玩家下线」分支：
     * 不发丹也不炸炉，开始炼丹时扣的糖之力视为本次尝试作废。</p>
     */
    private static void abortAlchemy(State state) {
        try {
            removeProjection(state);
            cancel(state.task);
            ALCHEMY.remove(state.uuid);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 材料匹配（炼丹投入时用）
    // ------------------------------------------------------------------

    /** 纯原版物品（无 lore）才能当原版材料用。 */
    private static boolean isPlainVanilla(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta == null || meta.lore() == null || meta.lore().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该物品的 lore 是否与注册物品的原始 lore 不同（不同则不能作为替代品）。 */
    private static boolean hasExtraLore(ItemStack item, String sfId) {
        try {
            ItemMeta meta = item.getItemMeta();
            List<net.kyori.adventure.text.Component> lore =
                    (meta == null) ? null : meta.lore();
            if (lore == null || lore.isEmpty()) return false;

            SlimefunItem sf = SlimefunItem.getById(sfId);
            if (sf == null || sf.getItem() == null) return true;
            ItemMeta sfMeta = sf.getItem().getItemMeta();
            List<net.kyori.adventure.text.Component> sfLore =
                    (sfMeta == null) ? null : sfMeta.lore();
            if (sfLore == null || sfLore.isEmpty()) return true;
            if (lore.size() != sfLore.size()) return true;
            for (int i = 0; i < lore.size(); i++) {
                if (!lore.get(i).equals(sfLore.get(i))) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 手里物品是否匹配事件要求里的某一种材料；匹配则返回那一项。 */
    private static Mat findMaterialMatch(ItemStack item, List<Mat> materials) {
        if (item == null || item.getType().isAir()) return null;
        for (Mat mat : materials) {
            if (mat.need <= 0) continue;
            if ("vanilla".equals(mat.type)) {
                Material want = Material.matchMaterial(mat.id);
                if (want != null && item.getType() == want && isPlainVanilla(item)) return mat;
                List<String> subs = VANILLA_SUBSTITUTES.get(mat.id);
                if (subs != null) {
                    String id = sfId(item);
                    if (id != null) {
                        for (String sub : subs) {
                            if (id.equalsIgnoreCase(sub) && !hasExtraLore(item, sub)) return mat;
                        }
                    }
                }
            } else {
                // 直接要求的粘液材料（灵植 / 福地生珍）按 id 识别即可，不限制 lore
                String id = sfId(item);
                if (id != null && id.equalsIgnoreCase(mat.id)) return mat;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 交互节流
    // ------------------------------------------------------------------

    private static boolean tryConsumeInput(Player player, long cooldownMs) {
        java.util.UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - INPUT_AT.getOrDefault(uuid, 0L) < cooldownMs) return false;
        INPUT_AT.put(uuid, now);
        return true;
    }

    private static boolean tryConsumeFireInput(Player player) {
        java.util.UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - FIRE_INPUT_AT.getOrDefault(uuid, 0L) < FIRE_INPUT_OPEN_THROTTLE) return false;
        FIRE_INPUT_AT.put(uuid, now);
        return true;
    }

    private static final long FIRE_INPUT_OPEN_THROTTLE = 100L;

    /** 当前选中的异火名；未选中返回 null。挑战期间由境界侧判定为未选中。 */
    private static String selectedYanhuoName(String playerName) {
        try {
            File f = new File(AddonPaths.configDir(AddonPaths.DIR_YANHUO), PlayerKeys.key(playerName) + ".json");
            Map<String, Object> data = AddonPaths.readJson(f);
            if (data == null) return null;
            Object sel = data.get("selected");
            if (sel == null) return null;
            return YANHUO_NAMES.get(String.valueOf(sel));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取走手持物品的 count 个（减到 0 就清空对应手的槽位）。
     *
     * <p><b>必须按 {@code event.getHand()} 分手</b>：{@code onInteract} 里 {@code item} 取自
     * {@code event.getItem()}，玩家用副手持丹/糖水/材料右键时命中的是副手槽。原写法无条件写回
     * 主手，会把副手物品复制进主手（同一引用进两个槽）或清错手——副手消耗写主手。与
     * {@code SaccharinScript} 的副手扣减保持同一口径。</p>
     */
    private static void consumeFromHand(Player player, EquipmentSlot hand, ItemStack item, int count) {
        try {
            PlayerInventory inv = player.getInventory();
            boolean off = hand == EquipmentSlot.OFF_HAND;
            int left = item.getAmount() - count;
            if (left <= 0) {
                if (off) inv.setItemInOffHand(null);
                else inv.setItemInMainHand(null);
            } else {
                item.setAmount(left);
                if (off) inv.setItemInOffHand(item);
                else inv.setItemInMainHand(item);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new PillsListener());
    }

    private static final class PillsListener implements Listener {

        // ---- 右键：控火 / 镇炉珠 / 自动炼丹券 / 投料 / 打开菜单 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Player player = event.getPlayer();
                ItemStack item = event.getItem() != null
                        ? event.getItem() : player.getInventory().getItemInMainHand();
                // 消耗按实际手持的手走（副手持物右键命中副手槽），与 SaccharinScript 同口径
                EquipmentSlot hand = event.getHand() != null ? event.getHand() : EquipmentSlot.HAND;
                java.util.UUID uuid = player.getUniqueId();
                State state = ALCHEMY.get(uuid);
                Action action = event.getAction();
                boolean rightClick = action == Action.RIGHT_CLICK_AIR
                        || action == Action.RIGHT_CLICK_BLOCK;

                // 控火事件：右键异火核心推进火候（0.1 秒冷却，方便快速调整）
                if (rightClick && isSf(item, "TR_KH")
                        && state != null && state.currentEvent != null
                        && "fire".equals(state.currentEvent.type)) {
                    if (!tryConsumeFireInput(player)) return;
                    event.setCancelled(true);
                    String selectedName = selectedYanhuoName(player.getName());
                    if (selectedName == null || !selectedName.equals(state.currentEvent.yanhuo)) {
                        player.sendMessage("§c异火选择错误!");
                        completeEvent(player, state, "失败");
                        return;
                    }
                    state.fireLevel = Math.min(FIRE_MAX, state.fireLevel + 1);
                    updateProjection(state);
                    try {
                        player.sendActionBar("§7当前火候: " + fireDisplay(state));
                        player.playSound(player.getLocation(),
                                "block.note_block.pling", 0.8f, 1.5f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return;
                }

                // 炼丹中任意位置右键镇炉珠：附加防炸赐福
                if (rightClick && state != null && isSf(item, "TR_ZLZ")) {
                    if (!tryConsumeInput(player, DEFAULT_INPUT_COOLDOWN_MS)) return;
                    event.setCancelled(true);
                    applyProtection(player, state);
                    return;
                }

                if (action != Action.RIGHT_CLICK_BLOCK) return;
                Block block = event.getClickedBlock();
                if (block == null) return;
                String id = BlockStorage.checkID(block);
                if (id == null || !id.equalsIgnoreCase(MACHINE_ID)) return;

                event.setCancelled(true);

                // 自动炼丹券刚使用完的重复事件不再打开菜单
                if (isSf(item, "TR_ZDLDQ") && state == null) {
                    long last = AUTO_TICKET_AT.getOrDefault(uuid, 0L);
                    if (System.currentTimeMillis() - last < DEFAULT_INPUT_COOLDOWN_MS) return;
                }

                // 手持控火右键太虚琉璃炉不打开炼丹菜单
                if (isSf(item, "TR_KH")) return;

                if (state == null) {
                    openMenu(player, block.getLocation().clone());
                    return;
                }

                // 炼丹中手持镇炉珠右键：重新附加防炸赐福
                if (isSf(item, "TR_ZLZ")) {
                    if (!tryConsumeInput(player, DEFAULT_INPUT_COOLDOWN_MS)) return;
                    applyProtection(player, state);
                    return;
                }

                // 自动炼丹券：右键立即完成本次炼丹
                if (isSf(item, "TR_ZDLDQ")) {
                    if (!tryConsumeInput(player, DEFAULT_INPUT_COOLDOWN_MS)) return;
                    consumeFromHand(player, hand, item, 1);
                    double r = RANDOM.nextDouble();
                    String autoQuality = r < 0.40 ? "凡品" : r < 0.70 ? "？？"
                            : r < 0.90 ? "珍品" : r < 0.99 ? "绝品" : "无暇";
                    completeAlchemyWithQuality(player, state, autoQuality);
                    AUTO_TICKET_AT.put(uuid, System.currentTimeMillis());
                    return;
                }

                // 加水事件：手持致死量全糖水投入
                if (state.currentEvent != null && "water".equals(state.currentEvent.type)
                        && isSf(item, "TR_ZSLQTS")) {
                    if (!tryConsumeInput(player, DEFAULT_INPUT_COOLDOWN_MS)) return;
                    int consume = player.isSneaking()
                            ? Math.min(item.getAmount(), state.currentEvent.amount) : 1;
                    if (consume > 0) {
                        consumeFromHand(player, hand, item, consume);
                        state.currentEvent.amount -= consume;
                        try {
                            player.playSound(player.getLocation(),
                                    "block.brewing_stand.brew", 0.6f, 1.2f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        updateProjection(state);
                        if (state.currentEvent.amount <= 0) {
                            completeEvent(player, state,
                                    gradeByTime("water",
                                            System.currentTimeMillis() - state.eventStartTime));
                        }
                    }
                    return;
                }

                // 材料事件：手持所需材料投入
                if (state.currentEvent != null && "material".equals(state.currentEvent.type)) {
                    if (!tryConsumeInput(player, DEFAULT_INPUT_COOLDOWN_MS)) return;
                    Mat mat = findMaterialMatch(item, state.currentEvent.materials);
                    if (mat != null) {
                        int consume = player.isSneaking()
                                ? Math.min(item.getAmount(), mat.need) : 1;
                        if (consume > 0) {
                            consumeFromHand(player, hand, item, consume);
                            mat.need -= consume;
                            try {
                                player.playSound(player.getLocation(),
                                        "block.brewing_stand.brew", 0.6f, 1.2f);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            updateProjection(state);
                            boolean allDone = true;
                            for (Mat m : state.currentEvent.materials) {
                                if (m.need > 0) {
                                    allDone = false;
                                    break;
                                }
                            }
                            if (allDone) {
                                completeEvent(player, state,
                                        gradeByTime("material",
                                                System.currentTimeMillis() - state.eventStartTime));
                            }
                        }
                        return;
                    }
                }

                // 其余情况：炼丹中右键炉子 = 打开菜单（可查看进度 / 切换丹方）
                openMenu(player, block.getLocation().clone());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static void applyProtection(Player player, State state) {
            if (state.protectedFlag) {
                player.sendMessage("§e此炉已受防炸赐福!");
                return;
            }
            if (countSlimefun(player, "TR_ZLZ") < 1) {
                player.sendMessage("§c背包中没有镇炉珠！");
                return;
            }
            takeSlimefun(player, "TR_ZLZ", 1);
            state.protectedFlag = true;
            FURNACE_PROTECTED.put(locKey(state.furnaceLoc), true);
            player.sendMessage("§a镇炉珠已生效，下次炸炉会被抵挡！");
        }

        // ---- 破坏炉鼎 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onBreak(BlockBreakEvent event) {
            try {
                Block block = event.getBlock();
                String id = BlockStorage.checkID(block);
                if (id == null || !id.equalsIgnoreCase(MACHINE_ID)) return;

                Player player = event.getPlayer();
                State state = ALCHEMY.get(player.getUniqueId());
                if (state == null) return;

                if (state.protectedFlag) {
                    // 防炸赐福抵挡挖掘引发的炸炉，正常掉落炉鼎
                    event.setDropItems(true);
                    state.protectedFlag = false;
                    FURNACE_PROTECTED.remove(locKey(state.furnaceLoc));
                    removeProjection(state);
                    cancel(state.task);
                    ALCHEMY.remove(state.uuid);
                    player.sendMessage("§b镇炉珠让您安全地破坏掉了太虚琉璃炉！");
                } else {
                    // 炼丹期间破坏炉鼎：不掉落炉鼎，按进度结算爆炸
                    event.setDropItems(false);
                    boomFurnace(player, state, completedCount(state));
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 菜单点击 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                event.setCancelled(true);
                if (!(event.getWhoClicked() instanceof Player player)) return;

                int slot = event.getRawSlot();
                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                    return;
                }
                MenuSession sess = MENUS.get(top);
                if (sess == null) return;

                State active = ALCHEMY.get(player.getUniqueId());

                // 开始炼丹
                if (slot == START_SLOT) {
                    if (sess.selected == null) {
                        player.sendMessage("§c请先选择一个已拥有的丹方！");
                        return;
                    }
                    Formula formula = formulaById(sess.selected);
                    if (formula == null) return;
                    if (sess.furnaceLoc == null) {
                        player.sendMessage("§c找不到太虚琉璃炉位置！");
                        return;
                    }
                    player.closeInventory();
                    startAlchemy(player, formula, sess.furnaceLoc, sess.protectedFlag);
                    return;
                }

                // 使用镇炉珠
                if (slot == PROTECT_SLOT) {
                    if (sess.protectedFlag) {
                        player.sendMessage("§e此炉已受防炸赐福!");
                        return;
                    }
                    if (countSlimefun(player, "TR_ZLZ") < 1) {
                        player.sendMessage("§c背包中没有镇炉珠！");
                        return;
                    }
                    takeSlimefun(player, "TR_ZLZ", 1);
                    sess.protectedFlag = true;
                    if (sess.furnaceLoc != null) {
                        FURNACE_PROTECTED.put(locKey(sess.furnaceLoc), true);
                    }
                    refresh(top, player);
                    player.sendMessage("§a已使用镇炉珠，此炉已受防炸赐福！");
                    return;
                }

                // 选择丹方
                int idx = -1;
                for (int i = 0; i < SLOTS.length; i++) {
                    if (SLOTS[i] == slot) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0 || idx >= FORMULAS.size()) return;

                Formula formula = FORMULAS.get(idx);
                if (!hasDanfang(player.getName(), formula.id())) {
                    player.sendMessage("§c未获得该丹方，无法选择！");
                    return;
                }
                if (active != null) {
                    // 炼丹中切换丹方 → 炸炉（有镇炉珠则抵挡一次）
                    player.closeInventory();
                    if (active.protectedFlag) {
                        active.protectedFlag = false;
                        FURNACE_PROTECTED.remove(locKey(active.furnaceLoc));
                        player.sendMessage("§b镇炉珠替你挡下了一次炸炉！");
                        return;
                    }
                    boomFurnace(player, active, completedCount(active));
                    return;
                }
                sess.selected = formula.id();
                refresh(top, player);
            } catch (Throwable t) {
                TT.log("[丹药] 菜单点击处理出错: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            // 拖拽是独立于 InventoryClickEvent 的一条通路：onClick 里 setCancelled 管不到它。
            // 不拦的话玩家把物品拖进菜单 → refresh() 重建时直接被抹掉 / 关闭时随界面丢弃，
            // 都等于凭空消失。与其余 8 个脚本菜单（异火选择 / 浮生当铺 / 堆叠机…）保持一致。
            try {
                Inventory top = Views.top(event.getView());
                if (top != null && ACTIVE.contains(top)) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory inv = event.getInventory();
                ACTIVE.remove(inv);
                MENUS.remove(inv);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (State s : ALCHEMY.values()) {
            cancel(s.task);
            removeProjection(s);
        }
        ALCHEMY.clear();
        ACTIVE.clear();
        MENUS.clear();
        FURNACE_PROTECTED.clear();
    }
}
