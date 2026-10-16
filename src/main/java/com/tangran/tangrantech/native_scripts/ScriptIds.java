package com.tangran.tangrantech.native_scripts;

/**
 * 原 RSC 版 scripts/ 目录下的 32 个 JS 脚本名常量。
 *
 * <p>值必须与 yml 里 {@code script:} 字段的写法逐字一致（含「道法/」前缀与大小写），
 * 否则脚本查不到。原生化后每个名字对应 {@code native_scripts} 包下的一个 Java 实现类。</p>
 */
public final class ScriptIds {

    private ScriptIds() {}

    // ---- 根目录脚本 ----
    public static final String QIAOLEZI_COOLANT = "俏了滋牌冷却剂获取";
    public static final String FIRST_CHAPTER_ALTAR = "初章祭坛";
    public static final String PRIMORDIAL_MARK = "原初刻印";
    public static final String HAQI_COLLECTOR = "哈气收集";
    public static final String TAIXU_HUB = "太虚枢机获取";
    public static final String TREASURE_SIMULATOR = "宝库模拟器";
    public static final String INJECTION = "注能";
    public static final String TANGTIAN_HALBERD = "糖天画戟";
    public static final String TANGRAN_BASIC_MATTER = "糖然牌基础物质获取";
    public static final String SACCHARIN = "糖精获取";
    public static final String LAOWU_SUMMONER = "老吴召唤器";
    public static final String LETHAL_SUGAR_WATER = "致死量全糖水";
    public static final String RAW_SUGAR_WATER = "粗糖水获取";

    // ---- 道法/ 子目录脚本 ----
    public static final String BOSS_BATTLE = "道法/BOSS战";
    public static final String PILLS = "道法/丹药";
    public static final String QUESTS = "道法/任务";
    public static final String STORAGE_SPACE = "道法/储物空间";
    public static final String STORAGE_SPACE_OPEN = "道法/储物空间开辟";
    public static final String FULL_ANALYSIS = "道法/全解";
    public static final String ASCENSION_PATH = "道法/升维之路";
    public static final String STACK_MACHINE = "道法/堆叠机";
    public static final String REALM = "道法/境界";
    public static final String LARGE_STRUCTURE_FILL = "道法/大型结构填充";
    public static final String HEAVEN_CURRENCY_EXCHANGE = "道法/天道货币兑换";
    public static final String FLOATING_PAWN_SHOP = "道法/浮生当铺";
    public static final String SPIRIT_CRYSTAL = "道法/神晶获取";
    public static final String FINAL_CRAFTING_TABLE = "道法/终刻合成台";
    public static final String FINAL_CRAFTING_COMPILER = "道法/终刻合成台编译器";
    public static final String AUTO_TAIXU_HUB = "道法/自动太虚枢机";
    public static final String EQUIPMENT = "道法/装备";
    public static final String EXOTIC_FLAME_CHOICE = "道法/异火选择";
    public static final String CLEAR_CHALLENGE_AREA = "道法/清理挑战区域";

    /** 全部脚本名（用于日志与自检）。 */
    public static String[] all() {
        return new String[] {
                QIAOLEZI_COOLANT, FIRST_CHAPTER_ALTAR, PRIMORDIAL_MARK, HAQI_COLLECTOR,
                TAIXU_HUB, TREASURE_SIMULATOR, INJECTION, TANGTIAN_HALBERD,
                TANGRAN_BASIC_MATTER, SACCHARIN, LAOWU_SUMMONER, LETHAL_SUGAR_WATER,
                RAW_SUGAR_WATER,
                BOSS_BATTLE, PILLS, QUESTS, STORAGE_SPACE, STORAGE_SPACE_OPEN,
                FULL_ANALYSIS, ASCENSION_PATH, STACK_MACHINE, REALM,
                LARGE_STRUCTURE_FILL, HEAVEN_CURRENCY_EXCHANGE, FLOATING_PAWN_SHOP,
                SPIRIT_CRYSTAL, FINAL_CRAFTING_TABLE, FINAL_CRAFTING_COMPILER,
                AUTO_TAIXU_HUB, EQUIPMENT, EXOTIC_FLAME_CHOICE, CLEAR_CHALLENGE_AREA,
        };
    }
}
