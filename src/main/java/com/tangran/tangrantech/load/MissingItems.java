package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「物品未找到」折叠上报。
 *
 * <p>未安装对应附属（无尽贪婪 / 动力科技 等）时，内容文件里指向外部物品的引用可达数千处。
 * 逐条打日志会淹没控制台，因此这里按 key 归并计数，加载结束时只输出一行汇总；
 * 被脚本逻辑依赖的关键物品（特殊物品）则在发现时立即单独告警。</p>
 */
public final class MissingItems {

    private MissingItems() {}

    /** key -> 出现次数（LinkedHashMap 保持首次发现顺序，便于排查）。 */
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();

    /** 需要立即告警的特殊物品 id 片段（大小写不敏感）。 */
    private static final List<String> CRITICAL = List.of(
            "TR_YTC", "TR_GL", "TR_ZNQ", "TR_TJ", "TR_SUGAR", "TR_CJTZHX", "TR_DLJX"
    );

    public static void reset() {
        COUNTS.clear();
    }

    /** 记录一次缺失引用。 */
    public static void record(String key) {
        if (key == null || key.isBlank()) return;
        COUNTS.merge(key, 1, Integer::sum);
        if (isCritical(key)) {
            TT.log("关键物品未找到: " + key + "（相关玩法可能不可用）");
        }
    }

    private static boolean isCritical(String key) {
        String u = key.toUpperCase(java.util.Locale.ROOT);
        for (String c : CRITICAL) {
            if (u.contains(c)) return true;
        }
        return false;
    }

    /** 加载结束汇总输出。 */
    public static void report() {
        if (COUNTS.isEmpty()) return;
        int total = 0;
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            total += e.getValue();
            if (top.size() < 10) top.add(e.getKey() + " x" + e.getValue());
        }
        TT.log("加载期共 " + total + " 处未找到的物品引用（" + COUNTS.size()
                + " 种，多为未安装的可选附属）；前几条: " + String.join(", ", top));
    }
}
