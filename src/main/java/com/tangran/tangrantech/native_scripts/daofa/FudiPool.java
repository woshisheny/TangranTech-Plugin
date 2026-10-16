package com.tangran.tangrantech.native_scripts.daofa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;

/**
 * 「福地生珍」的产出池（对应 {@code scripts/道法/储物空间.js} 抽奖部分的前半段）。
 *
 * <p>产出一件物品要做两件事：先按珍稀度决定「从哪个池子抽」，再从池子里随机取一项。
 * 池子分两类：</p>
 * <ul>
 *   <li><b>动态池</b> —— 原版物品直接扫 {@link Material#values()}（因此会抽到命令方块、屏障、
 *       结构方块这类生存拿不到的东西，这是设计意图：稀有档的「不可获取物」就靠它）；
 *       粘液物品从写死的 id 表里抽。</li>
 *   <li><b>固定池</b> —— 按珍稀度写死的具体物品（九大元素、原初刻印、二十种新材料…）。</li>
 * </ul>
 *
 * <p>各档的抽取比例逐字保留自原脚本：</p>
 * <table border="1">
 *   <caption>抽取比例</caption>
 *   <tr><th>珍稀度</th><th>分支</th></tr>
 *   <tr><td>常见</td><td>35% 原版动态 / 30% 粘液动态 / 35% 固定池</td></tr>
 *   <tr><td>稀有</td><td>30% 原版动态 / 25% 粘液动态 / 45% 固定池</td></tr>
 *   <tr><td>史诗</td><td>100% 固定池</td></tr>
 *   <tr><td>传说</td><td>100% 固定池</td></tr>
 * </table>
 *
 * <h2>原脚本里两处「名不副实」——均已照抄保留</h2>
 * <ol>
 *   <li>{@code randomVanillaUnobtainableItem}（稀有档用的「生存不可获取原版物」）的实现与
 *       {@code randomVanillaSurvivalItem}（常见档）<b>一字不差</b>：都是从全部 Material 均匀抽。
 *       这里不「修正」——真改成只抽不可获取物，会让稀有档的出货分布与线上老档不一致。</li>
 *   <li>{@code VANILLA_COMMON_IDS}（43 项）与 {@code VANILLA_RARE_IDS}（12 项）两张表
 *       <b>定义了但从未被读取</b>（原脚本随机原版物时走的是全量 Material 扫描）。
 *       属纯死代码，这里不再搬。<b>但 {@code VANILLA_LOOT_NAMES} 是活的</b>，它给随机到的
 *       原版物提供中文显示名，必须保留。</li>
 * </ol>
 */
public final class FudiPool {

    private FudiPool() {}

    /** 珍稀度：常见。 */
    public static final String COMMON = "常见";
    /** 珍稀度：稀有。 */
    public static final String RARE = "稀有";
    /** 珍稀度：史诗。 */
    public static final String EPIC = "史诗";
    /** 珍稀度：传说。 */
    public static final String LEGENDARY = "传说";

    /** 一件产出候选。{@code cat} 只会是 {@code vanilla} 或 {@code slimefun}。 */
    public record Product(String cat, String id, String name) {}

    // ------------------------------------------------------------------
    // 动态池
    // ------------------------------------------------------------------

    /** 粘液科技「常见」档的抽奖池（原脚本 {@code SLIMEFUN_COMMON_IDS}）。 */
    private static final List<String> SLIMEFUN_COMMON_IDS = List.of(
            "MAGIC_LUMP_1", "MAGIC_LUMP_2", "MAGIC_LUMP_3", "MAGIC_SUGAR", "ENDER_LUMP_1",
            "ENDER_LUMP_2", "ENDER_LUMP_3", "BASIC_CIRCUIT_BOARD", "COPPER_WIRE", "MAGNET",
            "HEATING_COIL", "ELECTRIC_MOTOR", "ELECTRO_MAGNET", "POWER_CRYSTAL");

    /** 粘液科技「稀有」档的抽奖池（原脚本 {@code SLIMEFUN_RARE_IDS}）。 */
    private static final List<String> SLIMEFUN_RARE_IDS = List.of(
            "ADVANCED_CIRCUIT_BOARD", "BATTERY", "STEEL_THRUSTER", "COOLING_UNIT",
            "WITHER_PROOF_OBSIDIAN", "WITHER_PROOF_GLASS", "REACTOR_COLLANT_CELL",
            "NETHER_ICE_COOLANT_CELL", "ANDROID_MEMORY_CORE", "ENERGY_REGULATOR", "CARGO_MOTOR");

    /**
     * 原版物品 id → 中文名。
     *
     * <p>随机到原版物时用它取显示名；表里没有的（比如 1.21 新增的几千种方块）回落成 id 本身
     * —— 这与原脚本的 {@code VANILLA_LOOT_NAMES[id] || id} 一致。</p>
     */
    private static final Map<String, String> VANILLA_NAMES = buildVanillaNames();

    /** 全部可作为物品的原版材质；首次访问时扫描一次并缓存。 */
    private static volatile List<Material> allVanilla;

    // ------------------------------------------------------------------
    // 固定池
    // ------------------------------------------------------------------

    /** 固定池条目。 */
    private record Fixed(String cat, String id, String name) {}

    /**
     * 常见档固定池：下界之星 + 九大元素 + 原初刻印。
     *
     * <p>这 11 项之所以「常见」而不按元素分级，是原脚本的设计——元素类物品是炼丹与升维的
     * 基础料，需要稳定的产出量。</p>
     */
    private static final List<Fixed> COMMON_FIXED = List.of(
            new Fixed("vanilla", "NETHER_STAR", "下界之星"),
            new Fixed("slimefun", "TR_FJ", "金·锋寂"),
            new Fixed("slimefun", "TR_SL", "木·森罗"),
            new Fixed("slimefun", "TR_YL", "水·渊澜"),
            new Fixed("slimefun", "TR_JM", "火·烬灭"),
            new Fixed("slimefun", "TR_CH", "土·尘寰"),
            new Fixed("slimefun", "TR_YC", "光·曜辰"),
            new Fixed("slimefun", "TR_YY", "暗·幽湮"),
            new Fixed("slimefun", "TR_TE", "雷·霆殛"),
            new Fixed("slimefun", "TR_XS", "冰·雪殇"),
            new Fixed("slimefun", "TR_YCKY", "原初刻印"));

    /** 稀有档固定池：太虚枢机、猫王、九色琉璃珠 + 八种「福地神珍」初级材料。 */
    private static final List<Fixed> RARE_FIXED = List.of(
            new Fixed("slimefun", "TR_TXSJ", "太虚枢机"),
            new Fixed("slimefun", "TR_MW", "猫王"),
            new Fixed("slimefun", "TR_JSLLZ", "九色琉璃珠"),
            new Fixed("slimefun", "TR_FDSZ01", "星纹铁"),
            new Fixed("slimefun", "TR_FDSZ02", "月华砂"),
            new Fixed("slimefun", "TR_FDSZ03", "灵犀玉"),
            new Fixed("slimefun", "TR_FDSZ04", "青冥石"),
            new Fixed("slimefun", "TR_FDSZ05", "紫电晶"),
            new Fixed("slimefun", "TR_FDSZ06", "玄冰魄"),
            new Fixed("slimefun", "TR_FDSZ07", "赤炎髓"),
            new Fixed("slimefun", "TR_FDSZ08", "风灵羽"));

    /** 史诗档固定池：7 种中级福地材料。 */
    private static final List<Fixed> EPIC_FIXED = List.of(
            new Fixed("slimefun", "TR_FDSZ09", "天机丝"),
            new Fixed("slimefun", "TR_FDSZ10", "太一真水"),
            new Fixed("slimefun", "TR_FDSZ11", "鸿蒙尘"),
            new Fixed("slimefun", "TR_FDSZ12", "万象铜"),
            new Fixed("slimefun", "TR_FDSZ13", "涅槃火种"),
            new Fixed("slimefun", "TR_FDSZ14", "九幽铁"),
            new Fixed("slimefun", "TR_FDSZ15", "星辰钢"));

    /** 传说档固定池：5 种顶级福地材料。 */
    private static final List<Fixed> LEGENDARY_FIXED = List.of(
            new Fixed("slimefun", "TR_FDSZ16", "混沌源核"),
            new Fixed("slimefun", "TR_FDSZ17", "造化玉碟碎片"),
            new Fixed("slimefun", "TR_FDSZ18", "大道之种"),
            new Fixed("slimefun", "TR_FDSZ19", "永恒神木"),
            new Fixed("slimefun", "TR_FDSZ20", "鸿蒙紫晶"));

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 按珍稀度抽一件产出。
     *
     * @param rarity {@link #COMMON}／{@link #RARE}／{@link #EPIC}／{@link #LEGENDARY}
     * @return 产出候选；未知珍稀度按「传说」处理（与原脚本的兜底分支一致）
     */
    public static Product product(String rarity) {
        double r = ThreadLocalRandom.current().nextDouble();
        if (COMMON.equals(rarity)) {
            if (r < 0.35) return randomVanilla();
            if (r < 0.65) return randomSlimefun(SLIMEFUN_COMMON_IDS);
            return fixed(COMMON_FIXED);
        }
        if (RARE.equals(rarity)) {
            if (r < 0.30) return randomVanilla();
            if (r < 0.55) return randomSlimefun(SLIMEFUN_RARE_IDS);
            return fixed(RARE_FIXED);
        }
        if (EPIC.equals(rarity)) return fixed(EPIC_FIXED);
        return fixed(LEGENDARY_FIXED);
    }

    /** 按珍稀度决定一束产出的数量（原脚本 {@code getAmountByRarity}）。 */
    public static int amountFor(String rarity) {
        if (COMMON.equals(rarity)) return 1 + ThreadLocalRandom.current().nextInt(8);
        if (RARE.equals(rarity)) return 1 + ThreadLocalRandom.current().nextInt(5);
        if (EPIC.equals(rarity)) return 1 + ThreadLocalRandom.current().nextInt(3);
        return 1 + ThreadLocalRandom.current().nextInt(2);
    }

    /**
     * 从全部原版材质里随机取一项。
     *
     * <p>「生存物料」与「不可获取物」两个档位在原脚本里共用这一个实现（见类注释第 1 条），
     * 因此这里也只暴露一个方法，由调用方自行决定标记成哪个档。</p>
     */
    public static Product randomVanilla() {
        List<Material> all = allVanillaMaterials();
        Material mat = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        String id = mat.name();
        return new Product("vanilla", id, VANILLA_NAMES.getOrDefault(id, id));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Product randomSlimefun(List<String> ids) {
        String id = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        // 粘液物品的显示名交给运行时按 id 反查（原脚本此处也是直接用 id，未带名字）
        return new Product("slimefun", id, id);
    }

    private static Product fixed(List<Fixed> pool) {
        Fixed f = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return new Product(f.cat(), f.id(), f.name());
    }

    /**
     * 全部可作为物品的原版材质。
     *
     * <p>用 {@code isItem()} 过滤掉纯方块态与空气。单元素读取可能抛异常（个别材质在
     * 客户端数据缺失时 {@code isItem()} 会炸），与原脚本一致地逐项吞掉。</p>
     */
    private static List<Material> allVanillaMaterials() {
        List<Material> cached = allVanilla;
        if (cached != null) return cached;
        synchronized (FudiPool.class) {
            if (allVanilla != null) return allVanilla;
            List<Material> out = new ArrayList<>();
            try {
                for (Material mat : Material.values()) {
                    try {
                        if (mat != Material.AIR && mat.isItem()) out.add(mat);
                    } catch (Throwable ignored) {
                        // 单项探测失败不影响整表
                    }
                }
            } catch (Throwable t) {
                // 极端情况下至少保证非空，否则下面的随机取下标会越界
            }
            if (out.isEmpty()) out.add(Material.STONE);
            allVanilla = out;
            return out;
        }
    }

    private static Map<String, String> buildVanillaNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DIAMOND", "钻石");
        m.put("EMERALD", "绿宝石");
        m.put("IRON_INGOT", "铁锭");
        m.put("GOLD_INGOT", "金锭");
        m.put("COAL", "煤炭");
        m.put("REDSTONE", "红石粉");
        m.put("LAPIS_LAZULI", "青金石");
        m.put("QUARTZ", "下界石英");
        m.put("AMETHYST_SHARD", "紫水晶碎片");
        m.put("COPPER_INGOT", "铜锭");
        m.put("NETHERITE_SCRAP", "下界合金碎片");
        m.put("BONE", "骨头");
        m.put("STRING", "线");
        m.put("LEATHER", "皮革");
        m.put("FEATHER", "羽毛");
        m.put("PORKCHOP", "生猪排");
        m.put("BEEF", "生牛肉");
        m.put("CHICKEN", "生鸡肉");
        m.put("MUTTON", "生羊肉");
        m.put("WHEAT", "小麦");
        m.put("CARROT", "胡萝卜");
        m.put("POTATO", "马铃薯");
        m.put("BEETROOT", "甜菜根");
        m.put("APPLE", "苹果");
        m.put("SUGAR_CANE", "甘蔗");
        m.put("BAMBOO", "竹子");
        m.put("SWEET_BERRIES", "甜浆果");
        m.put("GLOW_BERRIES", "发光浆果");
        m.put("KELP", "海带");
        m.put("MELON_SLICE", "西瓜片");
        m.put("PUMPKIN", "南瓜");
        m.put("COCOA_BEANS", "可可豆");
        m.put("NETHER_WART", "下界疣");
        m.put("CHORUS_FRUIT", "紫颂果");
        m.put("ENDER_PEARL", "末影珍珠");
        m.put("BLAZE_ROD", "烈焰棒");
        m.put("GHAST_TEAR", "恶魂之泪");
        m.put("PHANTOM_MEMBRANE", "幻翼膜");
        m.put("SLIME_BALL", "粘液球");
        m.put("GUNPOWDER", "火药");
        m.put("SPIDER_EYE", "蜘蛛眼");
        m.put("ROTTEN_FLESH", "腐肉");
        m.put("ARROW", "箭");
        m.put("COMMAND_BLOCK", "命令方块");
        m.put("CHAIN_COMMAND_BLOCK", "连锁命令方块");
        m.put("REPEATING_COMMAND_BLOCK", "循环命令方块");
        m.put("BARRIER", "屏障");
        m.put("BEDROCK", "基岩");
        m.put("END_PORTAL_FRAME", "末地传送门框架");
        m.put("DRAGON_EGG", "龙蛋");
        m.put("LIGHT", "光源方块");
        m.put("STRUCTURE_BLOCK", "结构方块");
        m.put("STRUCTURE_VOID", "结构空位");
        m.put("JIGSAW", "拼图方块");
        m.put("DEBUG_STICK", "调试棒");
        return Map.copyOf(m);
    }
}
