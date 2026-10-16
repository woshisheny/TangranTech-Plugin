package com.tangran.tangrantech.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/**
 * 原版材质中文名表。
 *
 * <p>糖然科技的 JS 脚本在提示玩家时会去读 {@code addons/糖然科技 TangranTech/lang/zh_cn.json}
 * 里的原版翻译（如物品掉落提示、境界突破提示等），按键 {@code item.minecraft.xxx} /
 * {@code block.minecraft.xxx} 取中文名。原生化后保留同样的数据源：jar 内
 * {@code data/lang/zh_cn.json}，运行时按需查表。
 *
 * <p>查表顺序：{@code block.minecraft.<枚举名小写>} → {@code item.minecraft.<枚举名小写>}
 * → 内置兜底表 → 小写枚举名。任一步命中即返回，全程不抛异常。</p>
 */
public final class ZhNames {

    private ZhNames() {}

    private static volatile Map<String, String> lang;

    /** 内置兜底表：覆盖脚本提示里高频出现、且不依赖原版 lang 文件也能说清楚的材质。 */
    private static final Map<String, String> FALLBACK = new HashMap<>();

    static {
        put("FARMLAND", "耕地");
        put("SOUL_SAND", "灵魂沙");
        put("SOUL_SOIL", "灵魂土");
        put("SAND", "沙子");
        put("RED_SAND", "红沙");
        put("DIRT", "泥土");
        put("GRASS_BLOCK", "草方块");
        put("COARSE_DIRT", "砂土");
        put("PODZOL", "灰化土");
        put("ROOTED_DIRT", "缠根泥土");
        put("MUD", "泥巴");
        put("END_STONE", "末地石");
        put("MYCELIUM", "菌丝体");
        put("NETHERRACK", "下界岩");
        put("CRIMSON_NYLIUM", "绯红菌岩");
        put("WARPED_NYLIUM", "诡异菌岩");
        put("SANDSTONE", "砂岩");
        put("CLAY", "黏土块");
        put("GRAVEL", "沙砾");
        put("SNOW_BLOCK", "雪块");
        put("ICE", "冰");
        put("OBSIDIAN", "黑曜石");
        put("STONE", "石头");
        put("DEEPSLATE", "深板岩");
        put("MOSS_BLOCK", "苔藓块");
        put("WHEAT", "小麦");
        put("CARROTS", "胡萝卜");
        put("POTATOES", "马铃薯");
        put("BEETROOTS", "甜菜根");
        put("NETHER_WART", "下界疣");
        put("SUGAR_CANE", "甘蔗");
        put("CACTUS", "仙人掌");
        put("COCOA", "可可豆");
        put("SWEET_BERRY_BUSH", "甜浆果丛");
        put("BAMBOO", "竹子");
        put("FLINT_AND_STEEL", "打火石");
        put("SHEARS", "剪刀");
        put("LILY_PAD", "睡莲");
        put("SALMON", "生鲑鱼");
        put("COD", "生鳕鱼");
        put("PUFFERFISH", "河豚");
        put("TROPICAL_FISH", "热带鱼");
        put("SEAGRASS", "海草");
        put("SEA_PICKLE", "海泡菜");
        put("KELP", "海带");
        put("HEART_OF_THE_SEA", "海洋之心");
        put("NAUTILUS_SHELL", "鹦鹉螺壳");
        put("STRING", "线");
        put("BONE", "骨头");
        put("INK_SAC", "墨囊");
        put("SPIDER_EYE", "蜘蛛眼");
        put("PRISMARINE_SHARD", "海晶碎片");
        put("SADDLE", "鞍");
        put("LEATHER", "皮革");
        put("ROTTEN_FLESH", "腐肉");
        put("GUNPOWDER", "火药");
        put("SLIME_BALL", "黏液球");
        put("EGG", "鸡蛋");
        put("PAPER", "纸");
        put("BOOK", "书");
        put("EXPERIENCE_BOTTLE", "附魔之瓶");
        put("NAME_TAG", "命名牌");
        put("SUGAR", "糖");
    }

    private static void put(String key, String zh) {
        FALLBACK.put(key, zh);
    }

    /** 原版 lang 表（懒加载，来自 jar 内 {@code data/lang/zh_cn.json}）。 */
    public static Map<String, String> lang() {
        Map<String, String> m = lang;
        if (m == null) {
            synchronized (ZhNames.class) {
                m = lang;
                if (m == null) {
                    m = loadLang();
                    lang = m;
                }
            }
        }
        return m;
    }

    private static Map<String, String> loadLang() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = ZhNames.class.getClassLoader().getResourceAsStream("data/lang/zh_cn.json")) {
            if (in == null) return out;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
                Object parsed = Json.parse(sb.toString());
                if (parsed instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()).replace("\\n", "\n"));
                    }
                }
            }
        } catch (Exception e) {
            // 语言表只用于提示文本，加载失败不影响内容注册
        }
        return out;
    }

    /** 材质中文名；未收录返回 null（调用方可回退旧英文格式）。 */
    public static String of(Material m) {
        if (m == null) return null;
        String lower = m.name().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> table = lang();
        String zh = table.get("block.minecraft." + lower);
        if (zh != null) return zh;
        zh = table.get("item.minecraft." + lower);
        if (zh != null) return zh;
        return FALLBACK.get(m.name());
    }

    /** 材质中文名；未收录回退为小写枚举名（下划线转空格）。 */
    public static String orEnglish(Material m) {
        String zh = of(m);
        return zh != null ? zh : m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
