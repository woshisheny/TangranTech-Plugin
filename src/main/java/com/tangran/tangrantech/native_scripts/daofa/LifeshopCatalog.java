package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import com.tangran.tangrantech.native_scripts.daofa.PlayerAssets;

/**
 * 「浮生当铺」的商品目录：分类定义、手工商品表、原版全物品自动生成与定价、价格缓存。
 *
 * <p>对应 {@code scripts/道法/浮生当铺.js} 的前 913 行（商品与定价部分）。</p>
 *
 * <h2>商品分三层</h2>
 * <ol>
 *   <li><b>手工商品</b>（{@code material} / {@code item} / {@code machine} / {@code lingpu} /
 *       {@code lingzhi} / {@code yanhuo} / {@code danfang}）—— 粘液科技物品，价格与额外材料
 *       写死在 {@link #buildManual()} 里。</li>
 *   <li><b>原版全物品</b>（11 个 {@code vanilla_*} 分类）—— 运行时扫全部 {@link Material}，
 *       按关键词规则分类、按合成配方递归定价。</li>
 *   <li><b>药水与光源</b> —— 药水按 {@link PotionType} × 三种容器 + 药箭展开；
 *       光源方块按亮度 0~15 拆成 16 件。</li>
 * </ol>
 *
 * <h2>定价的三层优先级（{@link #finalVanillaPrice}）</h2>
 * <ol>
 *   <li><b>矿石</b> = 对应掉落物价格 × 1.5（挖矿必须比直接买矿物贵，否则没人挖矿）；</li>
 *   <li><b>矿物块</b> = 对应基础材料价格 × 9；</li>
 *   <li>其余：属于「基础物资」分类（原材料 / 自然 / 刷怪蛋 / 管理员用品）直接用基础价，
 *       否则按<b>合成配方递归</b>算出「所需材料总价 ÷ 产出数量」，多配方取最便宜的。</li>
 * </ol>
 * <p>配方递归带环检测（{@code COMPUTING}），因为原版配方里存在互相引用的环
 * （例如 9 个锭 ↔ 1 个块）。撞到环时退回基础价，避免栈溢出。</p>
 *
 * <h2>缓存</h2>
 * <p>生成的商品表会写到 {@code addons/糖然科技 TangranTech/浮生当铺缓存.json}
 * （字段 {@code version} / {@code categories}），下次启动直接读，省掉整轮生成。
 * 版本号不符会重新生成。</p>
 *
 * <p><b>与原脚本的差异</b>：原脚本只读磁盘上的缓存。本插件的 jar 里自带一份
 * {@code data/浮生当铺缓存.json}（首次发布的初始表），因此 {@link #loadCache} 在磁盘
 * 取不到时会回落到 jar 内资源 —— 否则每个新服的第一次启动都要现场跑完整轮生成。</p>
 */
public final class LifeshopCatalog {

    /** 每页商品数（第 2~5 行、每行 7 格）。 */
    public static final int PAGE_SIZE = 28;

    /** 缓存版本；不符则重新生成。 */
    private static final int CACHE_VERSION = 2;

    /** 道源价格倍率（原脚本注释：在 50 倍基础上再翻 4 倍）。 */
    private static final double PRICE_MULTIPLIER = 200.0;

    // ==================================================================
    // 分类定义
    // ==================================================================

    /** 一个可点击分类（主菜单 / 子菜单共用）。 */
    public record Category(String id, String name, String lore, String icon, int slot) {}

    /** 主菜单：原版 / 粘液科技。 */
    public static final List<Category> CATEGORIES = List.of(
            new Category("vanilla", "§a原版", "§7原版方块、物品、材料", "GRASS_BLOCK", 11),
            new Category("slimefun", "§b粘液科技", "§7粘液科技物品", "SLIME_BALL", 15));

    /** 粘液科技子分类。 */
    public static final List<Category> SUBCATEGORIES = List.of(
            new Category("material", "§6材料类", "§7材料类粘液科技物品", "IRON_INGOT", 0),
            new Category("item", "§d道具类", "§7道具类粘液科技物品", "HEAVY_CORE", 1),
            new Category("lingpu", "§e灵圃", "§7灵圃土壤", "FARMLAND", 2),
            new Category("lingzhi", "§a灵植种子", "§7灵植种子", "WHEAT_SEEDS", 3),
            new Category("machine", "§5机器类", "§7各种功能机器", "PURPLE_GLAZED_TERRACOTTA", 4),
            new Category("juepin", "§6浮生绝品", "§7限量的镇店之宝", "GOLD_BLOCK", 44));

    /** 浮生绝品子分类。 */
    public static final List<Category> JUE_SUBCATEGORIES = List.of(
            new Category("yanhuo", "§c异火购买类", "§7限量的九种异火", "BLAZE_POWDER", 22),
            new Category("danfang", "§d丹方购买类", "§7九种丹药的炼制丹方", "BOOK", 31));

    /** 原版子分类。 */
    public static final List<Category> VANILLA_SUBCATEGORIES = List.of(
            new Category("vanilla_building", "§7建筑方块类", "§7各类建筑与结构方块", "BRICKS", 0),
            new Category("vanilla_colored", "§d染色方块类", "§7羊毛、陶瓦、混凝土、染色玻璃等", "MAGENTA_CONCRETE", 1),
            new Category("vanilla_natural", "§a自然方块类", "§7泥土、石头、树木、矿物、花草等", "OAK_LOG", 2),
            new Category("vanilla_functional", "§6功能方块类", "§7箱子、熔炉、工作台、附魔台等", "CRAFTING_TABLE", 3),
            new Category("vanilla_redstone", "§c红石方块类", "§7红石元件与机械", "REDSTONE", 4),
            new Category("vanilla_tools", "§e工具与实用物品类", "§7镐、斧、桶、地图、指南针等", "DIAMOND_PICKAXE", 5),
            new Category("vanilla_combat", "§4战斗用品类", "§7剑、盔甲、弓、箭、盾等", "DIAMOND_SWORD", 6),
            new Category("vanilla_food", "§6食物与饮品类", "§7各种食物、饮品与药水", "GOLDEN_APPLE", 7),
            new Category("vanilla_raw", "§b原材料类", "§7锭、宝石、粉、皮革、线等", "DIAMOND", 8),
            new Category("vanilla_spawn_egg", "§5刷怪蛋类", "§7所有生物的刷怪蛋", "ZOMBIE_SPAWN_EGG", 9),
            new Category("vanilla_admin", "§c管理员用具类", "§7基岩、命令方块、屏障等创造专属物品", "COMMAND_BLOCK", 10));

    /** 原版分类 id 顺序（缓存按这个顺序写）。 */
    public static final List<String> VANILLA_CATEGORY_IDS = List.of(
            "vanilla_building", "vanilla_colored", "vanilla_natural", "vanilla_functional",
            "vanilla_redstone", "vanilla_tools", "vanilla_combat", "vanilla_food",
            "vanilla_raw", "vanilla_spawn_egg", "vanilla_admin");

    // ==================================================================
    // 商品定义
    // ==================================================================

    /**
     * 一件商品。
     *
     * <p>{@code type} 取 {@code slimefun} / {@code vanilla} / {@code danfang}；<br>
     * {@code priceDao} 是道源单价。原脚本里有两个字段名（{@code priceDaoyuan} 与
     * {@code price}），靠 {@code priceDaoyuan || price || 0} 合并。经核对，
     * <b>没有任何一件商品同时写这两个字段</b>，因此这里合并成一个字段，行为等价。</p>
     */
    public static final class Def {
        public String type;
        public String id;
        /** GUI 显示名；null 表示按 id 反查。 */
        public String name;
        /** 中文名（搜索用；原版商品才有）。 */
        public String zhName = "";
        public double priceDao;
        public double priceGongde;
        public Map<String, Integer> needItems;
        public boolean limited;
        public boolean danfang;
        public int giveAmount = 1;
        /** 原版商品的材质。 */
        public Material material;
        /** 预制物品（药水、光源用它，避免每次重建）。 */
        public ItemStack preset;
        /** 药水类型名（缓存往返用）。 */
        public String potionType;
        /** 光源亮度（缓存往返用）。 */
        public Integer lightLevel;

        Def(String type, String id, String name) {
            this.type = type;
            this.id = id;
            this.name = name;
        }

        Def dao(double v) { this.priceDao = v; return this; }
        Def gongde(double v) { this.priceGongde = v; return this; }
        Def needs(Map<String, Integer> v) { this.needItems = v; return this; }
        Def limited() { this.limited = true; return this; }
        Def danfang() { this.danfang = true; return this; }
        Def give(int v) { this.giveAmount = v; return this; }
    }

    // ==================================================================
    // 目录状态
    // ==================================================================

    /** 分类 id → 商品列表。 */
    private static final Map<String, List<Def>> CATALOG = new LinkedHashMap<>();

    /** 是否已完成初始化（含缓存加载或现场生成）。 */
    private static volatile boolean ready = false;

    /** 初始化锁。 */
    private static final Object INIT_LOCK = new Object();

    // ---- 原版中文名（lang 文件） ----

    /** 材质名（大写）→ 中文名。 */
    private static final Map<String, String> VANILLA_ZH = new HashMap<>();

    /** 原始 lang 表（药水名要用带 {@code .effect.} 的完整键）。 */
    private static Map<String, Object> langData;

    // ---- 配方与价格缓存 ----

    private record RecipeEntry(List<Material> ingredients, int outputCount) {}

    private static final Map<String, List<RecipeEntry>> RECIPE_MAP = new HashMap<>();
    private static final Map<String, Double> RECIPE_PRICE_CACHE = new HashMap<>();
    private static final Set<String> RECIPE_COMPUTING = new HashSet<>();
    private static boolean recipeLoaded = false;

    /** 直接用基础价、不走配方递归的分类。 */
    private static final Set<String> BASE_PRICE_CATEGORIES = Set.of(
            "vanilla_raw", "vanilla_natural", "vanilla_spawn_egg", "vanilla_admin");

    private LifeshopCatalog() {}

    // ==================================================================
    // 对外接口
    // ==================================================================

    /** 某个分类的商品列表（永不返回 null）。 */
    public static List<Def> items(String catId) {
        init();
        return CATALOG.getOrDefault(catId, List.of());
    }

    /** 初始化（幂等）。首次调用可能较慢：需要扫全量材质并递归算配方价。 */
    public static void init() {
        if (ready) return;
        synchronized (INIT_LOCK) {
            if (ready) return;
            long start = System.currentTimeMillis();
            buildManual();
            boolean fromCache = loadCache();
            if (!fromCache) {
                loadChineseNames();
                generateVanillaItems();
                saveCache();
            }
            ready = true;
            TT.log("[浮生当铺] 商品表就绪（" + (fromCache ? "读缓存" : "现场生成") + "，耗时 "
                    + (System.currentTimeMillis() - start) + "ms）");
        }
    }

    /** 是否已就绪。未就绪时界面应提示「数据加载中」。 */
    public static boolean isReady() {
        return ready;
    }

    /** 某分类的页数（至少 1 页）。 */
    public static int pageCount(String catId) {
        List<Def> list = items(catId);
        return Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 分类名（四组表里挨个查，查不到回落成 id）。 */
    public static String categoryName(String catId) {
        for (Category c : CATEGORIES) if (c.id().equals(catId)) return c.name();
        for (Category c : SUBCATEGORIES) if (c.id().equals(catId)) return c.name();
        for (Category c : JUE_SUBCATEGORIES) if (c.id().equals(catId)) return c.name();
        for (Category c : VANILLA_SUBCATEGORIES) if (c.id().equals(catId)) return c.name();
        return catId;
    }

    /** 某个分类的商品数（原版主菜单入口要对 11 个子分类求和）。 */
    public static int itemCount(String catId) {
        return items(catId).size();
    }

    /** 原版商品总数（主菜单显示用）。 */
    public static int vanillaTotal() {
        int n = 0;
        for (String id : VANILLA_CATEGORY_IDS) n += itemCount(id);
        return n;
    }

    /** 商品的显示名。 */
    public static String itemName(Def def) {
        if (def == null) return "?";
        if (def.name != null && !def.name.isBlank()) return def.name;
        if ("slimefun".equals(def.type) || "danfang".equals(def.type)) {
            ItemStack stack = cleanStack(def);
            if (stack != null) {
                String n = PlayerAssets.displayNameOf(stack, null);
                if (n != null && !n.isBlank()) return n;
            }
            return def.id;
        }
        return VANILLA_ZH.getOrDefault(def.id, def.id);
    }

    /** 额外需求物品的显示名。 */
    public static String needItemName(String id) {
        String fromTable = NEED_ITEM_NAMES.get(id);
        if (fromTable != null) return fromTable;
        try {
            var sf = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(id);
            if (sf != null && sf.getItem() != null) {
                String n = PlayerAssets.displayNameOf(sf.getItem(), null);
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Throwable ignored) {
            // 落到 id
        }
        return id;
    }

    /** 额外需求物品的中文名表（原脚本 {@code NEED_ITEM_NAMES}）。 */
    private static final Map<String, String> NEED_ITEM_NAMES = needItemNames();

    private static Map<String, String> needItemNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Tr_lz01_fruit", "灵蕴草穗");
        m.put("Tr_lz02_fruit", "凝露花");
        m.put("Tr_lz03_fruit", "赤炎果");
        m.put("Tr_lz04_fruit", "寒霜参");
        m.put("Tr_lz05_fruit", "紫电藤果");
        m.put("Tr_lz06_fruit", "金髓稻穗");
        m.put("Tr_lz07_fruit", "青木芝");
        m.put("Tr_lz08_fruit", "玄水莲子");
        m.put("Tr_lz09_fruit", "厚土根");
        m.put("Tr_lz10_fruit", "曜光葵");
        m.put("Tr_lz11_fruit", "幽影菇");
        m.put("Tr_lz12_fruit", "雷音竹");
        m.put("Tr_lz13_fruit", "冰心梅");
        m.put("Tr_lz14_fruit", "火灵椒");
        m.put("Tr_lz15_fruit", "风语藤");
        m.put("Tr_lz16_fruit", "星辰麦");
        for (int i = 1; i <= 20; i++) {
            String key = String.format(Locale.ROOT, "Tr_fdsz%02d", i);
            m.put(key, FDSZ_NAMES[i - 1]);
        }
        return Map.copyOf(m);
    }

    private static final String[] FDSZ_NAMES = {
            "星纹铁", "月华砂", "灵犀玉", "青冥石", "紫电晶", "玄冰魄", "赤炎髓", "风灵羽",
            "天机丝", "太一真水", "鸿蒙尘", "万象铜", "涅槃火种", "九幽铁", "星辰钢",
            "混沌源核", "造化玉碟碎片", "大道之种", "永恒神木", "鸿蒙紫晶"};

    /**
     * 造一件「干净」商品底物（无自定义名与 lore）。
     *
     * <p>粘液物品必须 clone —— {@code sf.getItem()} 返回注册模板，直接改会污染同类物品。</p>
     */
    public static ItemStack cleanStack(Def def) {
        if (def == null) return null;
        if (def.preset != null) return def.preset.clone();
        if ("slimefun".equals(def.type)) {
            return slimefunItem(def.id);
        }
        if ("vanilla".equals(def.type)) {
            Material mat = def.material != null ? def.material : Material.matchMaterial(def.id);
            return mat == null ? null : new ItemStack(mat);
        }
        if ("danfang".equals(def.type)) return new ItemStack(Material.PAPER);
        return null;
    }

    /** 按 id 取粘液物品拷贝（大小写轮试）。 */
    public static ItemStack slimefunItem(String id) {
        if (id == null || id.isBlank()) return null;
        for (String candidate : new String[]{id, id.toUpperCase(Locale.ROOT), id.toLowerCase(Locale.ROOT)}) {
            try {
                var sf = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(candidate);
                if (sf != null && sf.getItem() != null) return sf.getItem().clone();
            } catch (Throwable ignored) {
                // 换下一种写法
            }
        }
        return null;
    }

    // ==================================================================
    // 手工商品表
    // ==================================================================

    private static Map<String, Integer> needs(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), (Integer) kv[i + 1]);
        }
        return m;
    }

    private static Def sfGoods(String id, String name) {
        return new Def("slimefun", id, name);
    }

    private static Def danfangGoods(String id, String name) {
        Def d = new Def("danfang", id, name);
        d.danfang = true;
        return d;
    }

    /** 建立全部手工商品（含品类与顺序，逐字对照原脚本的 {@code ITEMS}）。 */
    private static void buildManual() {
        if (!CATALOG.isEmpty()) return;

        // —— 材料类 ——
        CATALOG.put("material", List.of(
                sfGoods("TR_TXSJ", "太虚枢机").dao(10).give(1),
                sfGoods("TR_MW", "猫王").dao(10).give(1),
                sfGoods("TR_HMZQ", "鸿蒙紫气").dao(200).gongde(50).give(1)));

        // —— 道具类 ——
        CATALOG.put("item", List.of(
                sfGoods("TR_FSY", "浮生仪").gongde(1000).give(1),
                sfGoods("TR_NJD", "纳界袋").dao(1000).give(1),
                sfGoods("TR_CWKJKPS", "储物空间开辟石").dao(1000).give(1),
                sfGoods("TR_FDSZSXQ", "福地生珍刷新券").dao(600).give(1),
                sfGoods("TR_ZLZ", "镇炉珠").dao(1000).give(1),
                sfGoods("TR_ZDLDQ", "自动炼丹券").gongde(5000).give(1),
                sfGoods("TR_XSD", "洗髓丹").dao(500).give(1)));

        // —— 机器类 ——
        CATALOG.put("machine", List.of(
                sfGoods("TR_MGZJ", "命轨织机").gongde(5000)
                        .needs(needs("TR_MGXX", 64, "TR_TXSJ", 64, "TR_SPSJ", 64)).give(1),
                sfGoods("TR_TRPDDJ", "§b§l糖然牌堆叠机").dao(1000).gongde(1000)
                        .needs(needs("TR_XPSJ", 2)).give(1)));

        // —— 灵圃 ——
        CATALOG.put("lingpu", List.of(
                sfGoods("TR_QMLP", "青木灵圃").dao(50).give(1),
                sfGoods("TR_XHLP", "星辉灵圃").dao(500).gongde(10).give(1),
                sfGoods("TR_HMSG", "鸿蒙神圃").dao(5000).needs(needs("TR_HMZQ", 4)).give(1),
                sfGoods("TR_JLS", "聚灵散").dao(10).give(1)));

        // —— 灵植种子（16 种，均 1 道源） ——
        String[] seedIds = {
                "TR_LZ01_SEED", "TR_LZ02_SEED", "TR_LZ03_SEED", "TR_LZ04_SEED",
                "TR_LZ05_SEED", "TR_LZ06_SEED", "TR_LZ07_SEED", "TR_LZ08_SEED",
                "TR_LZ09_SEED", "TR_LZ10_SEED", "TR_LZ11_SEED", "TR_LZ12_SEED",
                "TR_LZ13_SEED", "TR_LZ14_SEED", "TR_LZ15_SEED", "TR_LZ16_SEED"};
        String[] seedNames = {
                "灵蕴草种子", "凝露花种子", "赤炎果种子", "寒霜参种子", "紫电藤种子", "金髓稻种子",
                "青木芝孢子", "玄水莲种子", "厚土根种子", "曜光葵种子", "幽影菇孢子", "雷音竹笋",
                "冰心梅种子", "火灵椒种子", "风语藤种子", "星辰麦种子"};
        List<Def> seeds = new ArrayList<>();
        for (int i = 0; i < seedIds.length; i++) {
            seeds.add(sfGoods(seedIds[i], seedNames[i]).dao(1).give(1));
        }
        CATALOG.put("lingzhi", List.copyOf(seeds));

        // —— 浮生绝品（空壳，进入后选择子分类） ——
        CATALOG.put("juepin", List.of());

        // —— 浮生绝品 · 异火购买类 ——
        // 九种异火：100000 道源 + 30000 功德 + 对应元素 640 个，每种限购 1
        String[][] yanhuo = {
                {"TR_YH01", "玄金焚天炎", "TR_FJ"},
                {"TR_YH02", "青木长生焰", "TR_SL"},
                {"TR_YH03", "沧溟玄水焱", "TR_YL"},
                {"TR_YH04", "赤霄焚灵炎", "TR_JM"},
                {"TR_YH05", "厚土地心火", "TR_CH"},
                {"TR_YH06", "曜日净世炎", "TR_YC"},
                {"TR_YH07", "冥渊噬魂火", "TR_YY"},
                {"TR_YH08", "九霄紫雷炎", "TR_TE"},
                {"TR_YH09", "霜雪凝神焰", "TR_XS"}};
        List<Def> yanhuoDefs = new ArrayList<>();
        for (String[] row : yanhuo) {
            yanhuoDefs.add(sfGoods(row[0], row[1]).dao(100_000).gongde(30_000)
                    .needs(needs(row[2], 640)).limited().give(1));
        }
        CATALOG.put("yanhuo", List.copyOf(yanhuoDefs));

        // —— 浮生绝品 · 丹方购买类 ——
        // 每张丹方：100000 道源 + 5000 功德 + 两种材料。注意 needItems 里的 id 是小写配置键
        Object[][] danfangRows = {
                {"Tr_dy01", "灵蕴丹方", needs("Tr_lz01_fruit", 16, "Tr_fdsz01", 8, "Tr_fdsz02", 4)},
                {"Tr_dy02", "凝露丹方", needs("Tr_lz02_fruit", 16, "Tr_fdsz03", 8, "Tr_fdsz04", 4)},
                {"Tr_dy03", "赤炎丹方", needs("Tr_lz03_fruit", 16, "Tr_fdsz05", 8, "Tr_fdsz07", 4)},
                {"Tr_dy04", "寒霜丹方", needs("Tr_lz04_fruit", 16, "Tr_fdsz06", 8, "Tr_fdsz04", 4)},
                {"Tr_dy05", "紫电丹方", needs("Tr_lz05_fruit", 16, "Tr_fdsz05", 8, "Tr_fdsz09", 4)},
                {"Tr_dy06", "金髓丹方", needs("Tr_lz06_fruit", 16, "Tr_fdsz12", 8, "Tr_fdsz15", 4)},
                {"Tr_dy07", "青木丹方", needs("Tr_lz07_fruit", 16, "Tr_fdsz19", 8, "Tr_fdsz11", 4)},
                {"Tr_dy08", "玄水丹方", needs("Tr_lz08_fruit", 16, "Tr_fdsz10", 8, "Tr_fdsz04", 4)},
                {"Tr_dy09", "厚土丹方", needs("Tr_lz09_fruit", 16, "Tr_fdsz14", 8, "Tr_fdsz11", 4)}};
        List<Def> danfangDefs = new ArrayList<>();
        for (Object[] row : danfangRows) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> nd = (Map<String, Integer>) row[2];
            danfangDefs.add(danfangGoods((String) row[0], (String) row[1])
                    .dao(100_000).gongde(5_000).needs(nd).give(1));
        }
        CATALOG.put("danfang", List.copyOf(danfangDefs));

        // 原版分类先占位（稍后由缓存或现场生成填充）
        for (String id : VANILLA_CATEGORY_IDS) CATALOG.putIfAbsent(id, new ArrayList<>());
    }

    // ==================================================================
    // 原版物品：分类规则
    // ==================================================================

    private static final String[] COLOR_NAMES = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"};

    private static final String[] COLORED_SUFFIXES = {
            "_WOOL", "_TERRACOTTA", "_CONCRETE", "_CONCRETE_POWDER", "_STAINED_GLASS",
            "_STAINED_GLASS_PANE", "_GLAZED_TERRACOTTA", "_CANDLE", "_BED", "_CARPET",
            "_BANNER", "_SHULKER_BOX", "_DYE"};

    private static final String[] REDSTONE_KEYWORDS = {
            "REDSTONE", "PISTON", "OBSERVER", "HOPPER", "DROPPER", "DISPENSER", "REPEATER",
            "COMPARATOR", "NOTE_BLOCK", "TARGET", "LEVER", "BUTTON", "PRESSURE_PLATE", "RAIL",
            "DAYLIGHT_DETECTOR", "TRIPWIRE_HOOK", "REDSTONE_LAMP", "REDSTONE_TORCH"};

    private static final String[] NATURAL_KEYWORDS = {
            "DIRT", "GRASS", "SAND", "GRAVEL", "CLAY", "MUD", "STONE", "COBBLESTONE", "DEEPSLATE",
            "TUFF", "CALCITE", "DRIPSTONE", "AMETHYST", "OBSIDIAN", "END_STONE", "PURPUR",
            "PRISMARINE", "SPONGE", "MAGMA", "NETHERRACK", "BASALT", "BLACKSTONE", "SCULK",
            "MUSHROOM", "FLOWER", "TULIP", "ORCHID", "ALLIUM", "BLUET", "CORNFLOWER", "LILY",
            "FERN", "VINE", "MOSS", "CACTUS", "SUGAR_CANE", "BAMBOO", "PUMPKIN", "MELON", "COCOA",
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "CRIMSON", "WARPED",
            "NYLIUM", "SOUL_SAND", "SOUL_SOIL", "CORAL", "KELP", "SEAGRASS", "LILY_PAD",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "DANDELION", "POPPY", "TORCHFLOWER",
            "PITCHER_PLANT", "_LOG", "_LEAVES", "_SAPLING", "_ORE"};

    private static final String[] FUNCTIONAL_KEYWORDS = {
            "FURNACE", "CRAFTING_TABLE", "CHEST", "BARREL", "SHULKER", "ANVIL", "GRINDSTONE",
            "SMITHING_TABLE", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "LOOM", "STONECUTTER",
            "BREWING_STAND", "CAULDRON", "COMPOSTER", "LECTERN", "BELL", "CAMPFIRE", "LANTERN",
            "TORCH", "END_ROD", "CHAIN", "LADDER", "SCAFFOLDING", "BOOKSHELF", "ENCHANTING_TABLE",
            "JUKEBOX", "NOTE_BLOCK", "TNT", "FLOWER_POT", "DECORATED_POT", "BEE_NEST", "BEEHIVE",
            "SPAWNER", "CONDUIT", "BEACON", "RESPAWN_ANCHOR", "DOOR", "TRAPDOOR", "FENCE_GATE",
            "SIGN", "TRIAL_SPAWNER", "VAULT"};

    private static final String[] TOOL_KEYWORDS = {
            "PICKAXE", "AXE", "SHOVEL", "HOE", "BUCKET", "SHEARS", "FLINT_AND_STEEL", "FISHING_ROD",
            "LEAD", "NAME_TAG", "MAP", "COMPASS", "CLOCK", "BRUSH", "SPYGLASS", "BOOK", "PAPER",
            "BOWL", "ENDER_PEARL", "ENDER_EYE", "BLAZE_ROD", "BREEZE_ROD", "BONE", "BONE_MEAL",
            "STRING", "FEATHER", "LEATHER", "RABBIT_HIDE", "SLIME_BALL", "HONEYCOMB",
            "GLASS_BOTTLE", "EXPERIENCE_BOTTLE", "ELYTRA", "FIREWORK", "GOAT_HORN", "MUSIC_DISC",
            "PAINTING", "ITEM_FRAME", "ARMOR_STAND", "END_CRYSTAL", "MINECART",
            "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "RECOVERY_COMPASS", "WRITABLE_BOOK",
            "WRITTEN_BOOK", "TRIAL_KEY", "OMINOUS_TRIAL_KEY", "OMINOUS_BOTTLE"};

    private static final String[] COMBAT_KEYWORDS = {
            "SWORD", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SHIELD", "ARROW", "BOW",
            "CROSSBOW", "TRIDENT", "MACE", "TOTEM_OF_UNDYING", "WIND_CHARGE"};

    private static final String[] FOOD_KEYWORDS = {
            "APPLE", "BREAD", "PORKCHOP", "COD", "SALMON", "BEEF", "CHICKEN", "MUTTON", "RABBIT",
            "STEW", "SOUP", "CAKE", "COOKIE", "MELON_SLICE", "DRIED_KELP", "PIE", "CARROT",
            "POTATO", "BEETROOT", "BERRIES", "HONEY_BOTTLE", "GOLDEN_APPLE", "GOLDEN_CARROT",
            "CHORUS_FRUIT", "MILK_BUCKET", "POTION"};

    /** 管理员专属材质（完全同名的精确匹配表）。 */
    private static final Set<String> ADMIN_EXACT = Set.of(
            "BARRIER", "BEDROCK", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK",
            "SPAWNER", "KNOWLEDGE_BOOK", "LIGHT", "END_PORTAL_FRAME", "END_PORTAL", "END_GATEWAY",
            "NETHER_PORTAL", "MOVING_PISTON", "PISTON_HEAD", "FIRE", "SOUL_FIRE", "WATER", "LAVA",
            "BUBBLE_COLUMN", "CAVE_AIR", "VOID_AIR", "TRIAL_SPAWNER", "VAULT", "OMINOUS_VAULT",
            "REINFORCED_DEEPSLATE", "TEST_BLOCK", "TEST_INSTANCE_BLOCK");

    private static final Map<String, String> SEED_CROP_MAP = Map.of(
            "WHEAT_SEEDS", "WHEAT",
            "BEETROOT_SEEDS", "BEETROOT",
            "PUMPKIN_SEEDS", "PUMPKIN",
            "MELON_SEEDS", "MELON",
            "TORCHFLOWER_SEEDS", "TORCHFLOWER",
            "PITCHER_POD", "PITCHER_PLANT");

    /** 矿石 → 掉落物（矿石价 = 掉落物价 × 1.5）。 */
    private static final Map<String, String> ORE_DROP_MAP = oreDropMap();

    private static Map<String, String> oreDropMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("COAL_ORE", "COAL");
        m.put("DEEPSLATE_COAL_ORE", "COAL");
        m.put("IRON_ORE", "RAW_IRON");
        m.put("DEEPSLATE_IRON_ORE", "RAW_IRON");
        m.put("COPPER_ORE", "RAW_COPPER");
        m.put("DEEPSLATE_COPPER_ORE", "RAW_COPPER");
        m.put("GOLD_ORE", "RAW_GOLD");
        m.put("DEEPSLATE_GOLD_ORE", "RAW_GOLD");
        m.put("REDSTONE_ORE", "REDSTONE");
        m.put("DEEPSLATE_REDSTONE_ORE", "REDSTONE");
        m.put("LAPIS_ORE", "LAPIS_LAZULI");
        m.put("DEEPSLATE_LAPIS_ORE", "LAPIS_LAZULI");
        m.put("DIAMOND_ORE", "DIAMOND");
        m.put("DEEPSLATE_DIAMOND_ORE", "DIAMOND");
        m.put("EMERALD_ORE", "EMERALD");
        m.put("DEEPSLATE_EMERALD_ORE", "EMERALD");
        m.put("NETHER_QUARTZ_ORE", "QUARTZ");
        m.put("NETHER_GOLD_ORE", "GOLD_NUGGET");
        m.put("ANCIENT_DEBRIS", "NETHERITE_SCRAP");
        return m;
    }

    /** 矿物块 → 基础材料（块价 = 基础价 × 9）。 */
    private static final Map<String, String> STORAGE_BLOCK_ITEMS = storageBlocks();

    private static Map<String, String> storageBlocks() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("NETHERITE_BLOCK", "NETHERITE_INGOT");
        m.put("DIAMOND_BLOCK", "DIAMOND");
        m.put("EMERALD_BLOCK", "EMERALD");
        m.put("IRON_BLOCK", "IRON_INGOT");
        m.put("GOLD_BLOCK", "GOLD_INGOT");
        m.put("COPPER_BLOCK", "COPPER_INGOT");
        m.put("LAPIS_BLOCK", "LAPIS_LAZULI");
        m.put("REDSTONE_BLOCK", "REDSTONE");
        m.put("QUARTZ_BLOCK", "QUARTZ");
        m.put("COAL_BLOCK", "COAL");
        m.put("AMETHYST_BLOCK", "AMETHYST_SHARD");
        m.put("RAW_IRON_BLOCK", "RAW_IRON");
        m.put("RAW_GOLD_BLOCK", "RAW_GOLD");
        m.put("RAW_COPPER_BLOCK", "RAW_COPPER");
        return m;
    }

    private static String[][] rareGroups() {
        return new String[][]{
                // {关键词..., 价格} —— 末位是价格，前面全是关键词
                {"NETHER_STAR", "DRAGON_EGG", "HEART_OF_THE_SEA", "CONDUIT", "BEACON", "ELYTRA",
                        "TRIDENT", "SHULKER_SHELL", "TOTEM_OF_UNDYING", "HEAVY_CORE", "MACE",
                        "ENCHANTED_GOLDEN_APPLE", "5"},
                {"NETHERITE", "0.05"},
                {"WITHER_SKELETON_SKULL", "ECHO_SHARD", "NAUTILUS_SHELL", "BREEZE_ROD", "TRIAL_KEY",
                        "OMINOUS_TRIAL_KEY", "1"},
                {"DIAMOND", "EMERALD", "GOLD", "0.01"},
                {"IRON", "COPPER", "LAPIS", "QUARTZ", "AMETHYST", "0.005"}};
    }

    private static boolean containsAny(String name, String[] keywords) {
        for (String k : keywords) if (name.contains(k)) return true;
        return false;
    }

    private static boolean isAdminMaterial(String name) {
        if (ADMIN_EXACT.contains(name)) return true;
        return name.contains("COMMAND_BLOCK");
    }

    private static boolean isColoredBlock(String name) {
        for (String suffix : COLORED_SUFFIXES) {
            if (!name.endsWith(suffix)) continue;
            String prefix = name.substring(0, name.length() - suffix.length());
            for (String color : COLOR_NAMES) if (prefix.equals(color)) return true;
        }
        return false;
    }

    /** 判定一个原版材质属于哪个原版分类。 */
    private static String classifyVanilla(Material mat) {
        String name = mat.name();
        boolean isBlock;
        try {
            isBlock = mat.isBlock();
        } catch (Throwable ignored) {
            isBlock = false;
        }

        if (isAdminMaterial(name)) return "vanilla_admin";
        if (name.endsWith("_SPAWN_EGG")) return "vanilla_spawn_egg";
        if (isEdible(mat) || containsAny(name, FOOD_KEYWORDS)) return "vanilla_food";
        if (containsAny(name, COMBAT_KEYWORDS)) return "vanilla_combat";
        if (containsAny(name, REDSTONE_KEYWORDS)) return "vanilla_redstone";
        if (isBlock && isColoredBlock(name)) return "vanilla_colored";
        if (isBlock && containsAny(name, NATURAL_KEYWORDS)) return "vanilla_natural";
        if (isBlock && containsAny(name, FUNCTIONAL_KEYWORDS)) return "vanilla_functional";
        if (containsAny(name, TOOL_KEYWORDS)) return "vanilla_tools";
        if (!isBlock) return "vanilla_raw";
        return "vanilla_building";
    }

    private static boolean isEdible(Material mat) {
        try {
            return mat.isEdible();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 原版物品：定价
    // ==================================================================

    private static double rarityPrice(String name) {
        for (String[] group : rareGroups()) {
            double price = Double.parseDouble(group[group.length - 1]);
            for (int i = 0; i < group.length - 1; i++) {
                if (name.contains(group[i])) return price;
            }
        }
        return 0.001;
    }

    private static double spawnEggPrice(String name) {
        if (name.contains("WITHER") || name.contains("ENDER_DRAGON") || name.contains("ELDER_GUARDIAN")) return 50;
        if (name.contains("WARDEN")) return 30;
        if (name.contains("RAVAGER")) return 10;
        if (name.contains("SHULKER")) return 5;
        if (name.contains("ALLAY") || name.contains("AXOLOTL") || name.contains("GOAT")
                || name.contains("FROG") || name.contains("TADPOLE") || name.contains("CAMEL")
                || name.contains("SNIFFER") || name.contains("BREEZE")) return 2;
        if (name.contains("VILLAGER") || name.contains("PIG") || name.contains("COW")
                || name.contains("SHEEP") || name.contains("CHICKEN") || name.contains("RABBIT")) return 0.1;
        return 0.5;
    }

    private static double toolMaterialMultiplier(String name) {
        if (name.contains("NETHERITE")) return 10;
        if (name.contains("DIAMOND")) return 5;
        if (name.contains("IRON")) return 2;
        if (name.contains("GOLD")) return 1.5;
        if (name.contains("STONE")) return 0.5;
        if (name.contains("WOOD")) return 0.2;
        return 1;
    }

    private static double growthMultiplier(String name) {
        if (name.contains("SMALL_")) return 0.5;
        if (name.contains("MEDIUM_")) return 1;
        if (name.contains("LARGE_")) return 2;
        if (name.contains("CLUSTER")) return 4;
        if (name.contains("_BUD")) return 0.8;
        return 1;
    }

    /** 基础价（不含矿石/矿物块/配方三条特例）。 */
    private static double baseVanillaPrice(Material mat) {
        String name = mat.name();
        if (isAdminMaterial(name)) return 5000;

        // 两个指定刷怪蛋一口价 100
        if ("WITHER_SKELETON_SPAWN_EGG".equals(name) || "VILLAGER_SPAWN_EGG".equals(name)) return 100;

        // 种子价 = 对应成熟作物价 ÷ 3（按一株平均产出估算）
        String seedCrop = SEED_CROP_MAP.get(name);
        if (seedCrop != null) {
            Material crop = Material.matchMaterial(seedCrop);
            if (crop != null) return baseVanillaPrice(crop) / 3;
        }

        double rarity = rarityPrice(name);
        String cat = classifyVanilla(mat);
        double price;
        if (name.endsWith("_SPAWN_EGG")) {
            price = spawnEggPrice(name);
        } else if ("vanilla_food".equals(cat)) {
            price = Math.max(0.01, rarity);
        } else if ("vanilla_combat".equals(cat) || "vanilla_tools".equals(cat)) {
            price = Math.max(0.01, rarity * toolMaterialMultiplier(name));
        } else if ("vanilla_raw".equals(cat)) {
            price = Math.max(0.001, rarity);
        } else if ("vanilla_redstone".equals(cat)) {
            price = Math.max(0.01, rarity);
        } else if ("vanilla_functional".equals(cat)) {
            price = Math.max(0.01, rarity);
        } else {
            price = Math.max(0.001, rarity * 0.01);
        }
        // 普通物资统一翻 200 倍；管理员用品在上面已单独定价
        return price * PRICE_MULTIPLIER * growthMultiplier(name);
    }

    private static double orePrice(Material mat) {
        String dropName = ORE_DROP_MAP.get(mat.name());
        if (dropName == null) return Double.NaN;
        Material drop = Material.matchMaterial(dropName);
        if (drop == null) return Double.NaN;
        return finalVanillaPrice(drop, classifyVanilla(drop)) * 1.5;
    }

    private static double storageBlockPrice(Material mat) {
        String baseName = STORAGE_BLOCK_ITEMS.get(mat.name());
        if (baseName == null) return Double.NaN;
        Material base = Material.matchMaterial(baseName);
        if (base == null) return Double.NaN;
        return finalVanillaPrice(base, classifyVanilla(base)) * 9;
    }

    /** 最终价：矿石 → 矿物块 → 基础物资直接定价 → 配方递归，四选一。 */
    private static double finalVanillaPrice(Material mat, String cat) {
        double ore = orePrice(mat);
        if (!Double.isNaN(ore)) return ore;
        double block = storageBlockPrice(mat);
        if (!Double.isNaN(block)) return block;
        if (BASE_PRICE_CATEGORIES.contains(cat)) return baseVanillaPrice(mat);
        return recipePrice(mat);
    }

    /**
     * 按合成配方递归定价：所需材料总价 ÷ 产出数量，多配方取最便宜。
     *
     * <p>带环检测：原版配方存在互相引用的环（9 锭 ↔ 1 块），撞到环时退回基础价，
     * 否则会无限递归。</p>
     */
    private static double recipePrice(Material mat) {
        String key = mat.name();
        Double cached = RECIPE_PRICE_CACHE.get(key);
        if (cached != null) return cached;
        if (RECIPE_COMPUTING.contains(key)) return baseVanillaPrice(mat);
        RECIPE_COMPUTING.add(key);

        double best = Double.NaN;
        for (RecipeEntry r : RECIPE_MAP.getOrDefault(key, List.of())) {
            double total = 0;
            for (Material ingredient : r.ingredients()) total += recipePrice(ingredient);
            double price = total / r.outputCount();
            if (Double.isNaN(best) || price < best) best = price;
        }

        RECIPE_COMPUTING.remove(key);
        double result = Double.isNaN(best) ? baseVanillaPrice(mat) : best;
        RECIPE_PRICE_CACHE.put(key, result);
        return result;
    }

    // ==================================================================
    // 原版物品：配方收集
    // ==================================================================

    private static void loadRecipes() {
        if (recipeLoaded) return;
        recipeLoaded = true;
        try {
            Iterator<Recipe> it = Bukkit.getServer().recipeIterator();
            while (it.hasNext()) addRecipe(it.next());
        } catch (Throwable t) {
            TT.log("[浮生当铺] 遍历配方失败: " + t);
        }
        // 只有迭代器一条都没读到才用 getRecipesFor 逐个补（避免重复解析拖慢加载）
        if (RECIPE_MAP.isEmpty()) {
            try {
                for (Material mat : Material.values()) {
                    try {
                        if (!mat.isItem()) continue;
                        for (Recipe r : Bukkit.getServer().getRecipesFor(new ItemStack(mat))) {
                            addRecipe(r);
                        }
                    } catch (Throwable ignored) {
                        // 单个材质失败不影响其它
                    }
                }
            } catch (Throwable ignored) {
                // 兜底也失败就只能用基础价
            }
        }
    }

    /**
     * 收录一条合成配方。
     *
     * <p>用 {@code getIngredientMap()} / {@code getIngredientList()} —— 在 1.21 的 Paper 上
     * 它们已经改名为 {@code getChoiceMap()} / {@code getChoiceList()}（返回 {@code RecipeChoice}），
     * 但旧的 ItemStack 重载仍保留，本插件的目标版本上可用，且与原脚本的解析路径一致。</p>
     */
    @SuppressWarnings("deprecation")
    private static void addRecipe(Recipe r) {
        try {
            if (r == null) return;
            ItemStack result = r.getResult();
            if (result == null || result.getType().isAir()) return;

            List<Material> ingredients = new ArrayList<>();
            if (r instanceof ShapedRecipe shaped) {
                for (ItemStack choice : shaped.getIngredientMap().values()) {
                    if (choice != null && !choice.getType().isAir()) ingredients.add(choice.getType());
                }
            } else if (r instanceof ShapelessRecipe shapeless) {
                for (ItemStack choice : shapeless.getIngredientList()) {
                    if (choice != null && !choice.getType().isAir()) ingredients.add(choice.getType());
                }
            } else {
                return;
            }
            if (ingredients.isEmpty()) return;

            RECIPE_MAP.computeIfAbsent(result.getType().name(), k -> new ArrayList<>())
                    .add(new RecipeEntry(List.copyOf(ingredients), Math.max(1, result.getAmount())));
        } catch (Throwable ignored) {
            // 单条配方解析失败忽略
        }
    }

    // ==================================================================
    // 原版物品：中文名
    // ==================================================================

    private static File langFile() {
        return new File(AddonPaths.addonDir("lang"), "zh_cn.json");
    }

    /**
     * 载入原版中文名表。
     *
     * <p>磁盘上没有（{@code addons/糖然科技 TangranTech/lang/zh_cn.json}）时回落到 jar 内
     * 的 {@code data/lang/zh_cn.json} —— 本插件的语言文件是随包发布的。</p>
     */
    private static void loadChineseNames() {
        try {
            String text = null;
            File file = langFile();
            if (file.exists()) {
                text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            if (text == null) text = AddonPaths.readBundledText("lang/zh_cn.json");
            if (text == null) return;

            Map<String, Object> data = Json.parseObject(text);
            if (data.isEmpty()) return;
            langData = data;

            for (Map.Entry<String, Object> e : data.entrySet()) {
                String key = e.getKey();
                String shortName = null;
                if (key.startsWith("block.minecraft.")) {
                    shortName = key.substring("block.minecraft.".length());
                } else if (key.startsWith("item.minecraft.")) {
                    shortName = key.substring("item.minecraft.".length());
                }
                if (shortName == null) continue;
                String matName = shortName.toUpperCase(Locale.ROOT);
                VANILLA_ZH.putIfAbsent(matName, stripColors(String.valueOf(e.getValue())));
            }
        } catch (Throwable t) {
            TT.log("[浮生当铺] 载入中文名表失败: " + t);
        }
    }

    private static String stripColors(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    /** 从 lang 表取名字（去色）；没有返回 null。 */
    private static String langName(String key) {
        if (langData == null) return null;
        Object v = langData.get(key);
        return v == null ? null : stripColors(String.valueOf(v));
    }

    /** 英文材质名转「首字母大写」的显示名（中文名缺失时的回落）。 */
    static String titleCase(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (upper && Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
            if (c == ' ') upper = true;
        }
        return sb.toString();
    }

    // ==================================================================
    // 原版物品：生成
    // ==================================================================

    private static void generateVanillaItems() {
        loadRecipes();

        for (String catId : VANILLA_CATEGORY_IDS) CATALOG.put(catId, new ArrayList<>());

        for (Material mat : Material.values()) {
            try {
                if (!mat.isItem()) continue;
                String name = mat.name();
                if ("AIR".equals(name) || "CAVE_AIR".equals(name) || "VOID_AIR".equals(name)) continue;
                try {
                    new ItemStack(mat);
                } catch (Throwable invalid) {
                    continue;
                }
                String cat = classifyVanilla(mat);
                List<Def> bucket = CATALOG.get(cat);
                if (bucket == null) continue;
                // 光源方块单独拆成 0~15 亮度出售，这里跳过
                if ("LIGHT".equals(name)) continue;

                Def def = new Def("vanilla", name, VANILLA_ZH.getOrDefault(name, titleCase(name)));
                def.zhName = VANILLA_ZH.getOrDefault(name, "");
                def.material = mat;
                def.priceDao = finalVanillaPrice(mat, cat);
                def.giveAmount = 1;
                bucket.add(def);
            } catch (Throwable ignored) {
                // 单个材质失败不影响整表
            }
        }

        // 管理员用具：补全 0~15 级光源方块
        Material lightMat = Material.LIGHT;
        List<Def> admin = CATALOG.get("vanilla_admin");
        if (admin != null && lightMat != null) {
            for (int level = 0; level <= 15; level++) {
                ItemStack stack = buildLightStack(level);
                Def def = new Def("vanilla", "LIGHT", "光源方块·亮度" + level);
                def.zhName = "光源方块·亮度" + level;
                def.material = lightMat;
                def.preset = stack;
                def.lightLevel = level;
                def.priceDao = 5000;
                def.giveAmount = 1;
                admin.add(def);
            }
        }

        generatePotionItems();

        // 每个分类按「价格升序、同价按名字」排序
        Comparator<Def> byPriceThenName = Comparator
                .comparingDouble((Def d) -> d.priceDao)
                .thenComparing(d -> d.name == null ? "" : d.name);
        for (String catId : VANILLA_CATEGORY_IDS) {
            List<Def> list = CATALOG.get(catId);
            if (list != null) list.sort(byPriceThenName);
        }
    }

    /**
     * 光源方块：{@code LIGHT} 的亮度靠 BlockData 的 level 表达。
     *
     * <p>{@code setBlockData} 不在 {@code ItemMeta} 上，而在子接口 {@code BlockDataMeta}
     * 上（只有方块类物品的 meta 才实现它），所以要先 instanceof 收窄。</p>
     */
    private static ItemStack buildLightStack(int level) {
        try {
            Material mat = Material.LIGHT;
            if (mat == null) return null;
            ItemStack stack = new ItemStack(mat);
            ItemMeta meta = stack.getItemMeta();
            if (!(meta instanceof org.bukkit.inventory.meta.BlockDataMeta blockMeta)) return null;
            BlockData data = mat.createBlockData();
            if (!(data instanceof Levelled levelled)) return null;
            levelled.setLevel(level);
            blockMeta.setBlockData(data);
            stack.setItemMeta(blockMeta);
            return stack;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ItemStack buildPotionStack(Material material, PotionType type) {
        ItemStack stack = new ItemStack(material);
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof PotionMeta pm) {
                pm.setBasePotionType(type);
                stack.setItemMeta(pm);
            }
        } catch (Throwable ignored) {
            // 药水类型设置失败就用基础物品
        }
        return stack;
    }

    private static String potionTypeKey(PotionType type) {
        try {
            return type.getKey().getKey();
        } catch (Throwable ignored) {
            return type.name().toLowerCase(Locale.ROOT);
        }
    }

    private static double potionPrice(PotionType type, Material material) {
        double price = 2;
        String name = type.name();
        if (name.contains("LONG_")) price *= 1.5;
        if (name.contains("STRONG_")) price *= 1.5;
        if (material == Material.SPLASH_POTION) price *= 1.2;
        if (material == Material.LINGERING_POTION) price *= 1.5;
        if (material == Material.TIPPED_ARROW) price *= 1.2;
        return price;
    }

    /** 药水：每种 {@link PotionType} × {普通/喷溅/滞留}，外加药箭。 */
    private static void generatePotionItems() {
        try {
            Material[] potionMats = {Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION};
            List<Def> food = CATALOG.get("vanilla_food");
            List<Def> combat = CATALOG.get("vanilla_combat");

            for (PotionType type : PotionType.values()) {
                String key = potionTypeKey(type);

                for (Material mat : potionMats) {
                    if (mat == null) continue;
                    String langKey = "item.minecraft." + mat.name().toLowerCase(Locale.ROOT)
                            + ".effect." + key;
                    String zh = langName(langKey);
                    if (zh == null) zh = "Potion " + type.name();

                    Def def = new Def("vanilla", "POTION_" + mat.name() + "_" + type.name(), zh);
                    def.zhName = zh;
                    def.material = mat;
                    def.preset = buildPotionStack(mat, type);
                    def.potionType = type.name();
                    def.priceDao = potionPrice(type, mat);
                    def.giveAmount = 1;
                    if (food != null) food.add(def);
                }

                Material arrowMat = Material.TIPPED_ARROW;
                if (arrowMat != null) {
                    String arrowLang = "item.minecraft.tipped_arrow.effect." + key;
                    String arrowZh = langName(arrowLang);
                    if (arrowZh == null) arrowZh = "Tipped Arrow " + type.name();

                    Def def = new Def("vanilla", "TIPPED_ARROW_" + type.name(), arrowZh);
                    def.zhName = arrowZh;
                    def.material = arrowMat;
                    def.preset = buildPotionStack(arrowMat, type);
                    def.potionType = type.name();
                    def.priceDao = potionPrice(type, arrowMat);
                    def.giveAmount = 1;
                    if (combat != null) combat.add(def);
                }
            }
        } catch (Throwable t) {
            TT.log("[浮生当铺] 生成药水商品失败: " + t);
        }
    }

    // ==================================================================
    // 缓存
    // ==================================================================

    private static File cacheFile() {
        return new File(AddonPaths.addonRoot(), "浮生当铺缓存.json");
    }

    /** 保存原版商品表（只存原版分类；手工商品不缓存）。 */
    private static void saveCache() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", CACHE_VERSION);
            Map<String, Object> categories = new LinkedHashMap<>();

            for (String catId : VANILLA_CATEGORY_IDS) {
                List<Object> arr = new ArrayList<>();
                for (Def def : CATALOG.getOrDefault(catId, List.of())) {
                    Map<String, Object> plain = new LinkedHashMap<>();
                    plain.put("type", "vanilla");
                    plain.put("id", def.id);
                    plain.put("material", def.material != null ? def.material.name() : def.id);
                    plain.put("name", def.name);
                    plain.put("zhName", def.zhName == null ? "" : def.zhName);
                    plain.put("price", def.priceDao);
                    plain.put("giveAmount", def.giveAmount);
                    if (def.potionType != null) plain.put("potionType", def.potionType);
                    if (def.lightLevel != null) plain.put("lightLevel", def.lightLevel);
                    arr.add(plain);
                }
                categories.put(catId, arr);
            }
            out.put("categories", categories);

            AddonPaths.writeJson(cacheFile(), out);
        } catch (Throwable t) {
            TT.log("[浮生当铺] 保存缓存失败: " + t);
        }
    }

    /**
     * 读缓存。磁盘没有时回落到 jar 内随包发布的 {@code data/浮生当铺缓存.json}。
     *
     * @return 是否成功载入（false 时调用方需要现场生成）
     */
    private static boolean loadCache() {
        try {
            String text = null;
            File file = cacheFile();
            if (file.exists()) {
                text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            boolean fromBundle = false;
            if (text == null) {
                text = AddonPaths.readBundledText("浮生当铺缓存.json");
                fromBundle = text != null;
            }
            if (text == null) return false;

            Map<String, Object> data = Json.parseObject(text);
            if (data.get("version") == null) return false;
            if (Json.i(data, "version", -1) != CACHE_VERSION) return false;

            Map<String, Object> categories = Json.obj(data, "categories");
            if (categories.isEmpty()) return false;

            for (String catId : VANILLA_CATEGORY_IDS) {
                List<Object> arr = Json.arr(categories, catId);
                List<Def> loaded = new ArrayList<>();
                for (Object raw : arr) {
                    if (!(raw instanceof Map<?, ?> plain)) continue;
                    String matName = plain.get("material") == null ? null
                            : String.valueOf(plain.get("material"));
                    Material mat = matName == null ? null : Material.matchMaterial(matName);
                    if (mat == null) continue;

                    Def def = new Def("vanilla", String.valueOf(plain.get("id")),
                            plain.get("name") == null ? null : String.valueOf(plain.get("name")));
                    def.zhName = plain.get("zhName") == null ? "" : String.valueOf(plain.get("zhName"));
                    def.material = mat;
                    def.priceDao = plain.get("price") instanceof Number n ? n.doubleValue() : 0;
                    def.giveAmount = plain.get("giveAmount") instanceof Number n ? n.intValue() : 1;

                    Object potion = plain.get("potionType");
                    Object light = plain.get("lightLevel");
                    if (potion != null) {
                        def.potionType = String.valueOf(potion);
                        try {
                            def.preset = buildPotionStack(mat, PotionType.valueOf(def.potionType));
                        } catch (Throwable ignored) {
                            // 药水类型在当前版本不存在：保留底物，不预置类型
                        }
                    }
                    if (light != null && light instanceof Number n) {
                        def.lightLevel = n.intValue();
                        def.preset = buildLightStack(def.lightLevel);
                    }
                    loaded.add(def);
                }
                CATALOG.put(catId, loaded);
            }

            // 从 jar 内资源载入时，顺手把中文名表也载进来（搜索要用）
            if (fromBundle) loadChineseNames();
            return true;
        } catch (Throwable t) {
            TT.log("[浮生当铺] 读缓存失败: " + t);
            return false;
        }
    }

    /** 搜索原版商品：先精确匹配 id，否则按名字/中文名包含匹配。 */
    public static List<Def> searchVanilla(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();

        List<Def> results = new ArrayList<>();
        for (String catId : VANILLA_CATEGORY_IDS) {
            for (Def def : items(catId)) {
                String id = String.valueOf(def.id).toLowerCase(Locale.ROOT);
                if (id.equals(q)) return List.of(def);   // 精确命中直接返回单件

                String name = def.name == null ? "" : def.name.toLowerCase(Locale.ROOT);
                String zh = def.zhName == null ? "" : def.zhName.toLowerCase(Locale.ROOT);
                if (name.contains(q) || zh.contains(q)) results.add(def);
            }
        }
        return results;
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        synchronized (INIT_LOCK) {
            ready = false;
            CATALOG.clear();
            RECIPE_MAP.clear();
            RECIPE_PRICE_CACHE.clear();
            RECIPE_COMPUTING.clear();
            recipeLoaded = false;
            VANILLA_ZH.clear();
            langData = null;
        }
    }
}
