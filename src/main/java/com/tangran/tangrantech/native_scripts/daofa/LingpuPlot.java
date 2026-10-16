package com.tangran.tangrantech.native_scripts.daofa;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.tangran.tangrantech.native_scripts.daofa.StorageSpaceScript;

/**
 * 一块「灵圃」地块的数据模型与状态推进（对应 {@code scripts/道法/储物空间.js} 的灵圃部分）。
 *
 * <p>玩家在储物空间的「洞天福地 → 灵圃」界面里，把灵圃（{@code TR_QMLP} / {@code TR_XHLP} /
 * {@code TR_HMSG}）拖到格子上，再拖入种子（{@code TR_LZnn_SEED}）即可种植。成熟过程分两种：</p>
 * <ul>
 *   <li><b>普通灵圃</b>（青木 / 星辉）—— 种植时随机决定本次会出现 1~5 次「农事事件」
 *       （浇水 / 翻耕 / 施肥，各最多累计 4 个）。倒计时一直在走，<b>但只要还有未处理的
 *       事件就暂停</b>；事件处理完才恢复。倒计时到 0 且事件全部完成才算可收获。</li>
 *   <li><b>鸿蒙神圃</b> —— 不做事件，种下即全自动：成熟后由 {@code StorageSpaceScript} 的
 *       40 刻定时任务自动收获并立刻重新开始下一轮，永不枯竭（这是顶级灵圃的卖点）。</li>
 * </ul>
 *
 * <h2>与 JSON 存档的字段对应关系</h2>
 * <p>字段名与嵌套结构刻意沿用原脚本（含 {@code events.water/hoe/bone} 三层嵌套），
 * 这样老档里已经种下的灵圃不会因为改名而全部作废。</p>
 *
 * <h2>一处照抄的越界计算</h2>
 * <p>{@link #pageCount} 用的是原脚本的 {@code floor((maxIndex+1)/45)+1}。当地块索引
 * 恰好是 44（第一页最后一格）时它会算出 2 页，也就是会多出一个纯空的第 2 页。
 * 这是原脚本的 off-by-one，<b>照抄不修</b> —— 修了会让老玩家看到的页数与之前不一致。</p>
 */
public final class LingpuPlot {

    // ------------------------------------------------------------------
    // 静态配置
    // ------------------------------------------------------------------

    /**
     * 土壤等级。
     *
     * @param id       粘液 id
     * @param name     显示名（也是菜单里地块图标的标题）
     * @param duration 基准成熟时长（ms），普通灵圃的实际时长还会按果实功德点放大
     * @param min      单次收获果实数量下限
     * @param max      单次收获果实数量上限
     * @param ultimate 是否鸿蒙神圃（全自动、作物不消失）
     */
    public record Soil(String id, String name, long duration, int min, int max, boolean ultimate) {}

    /** 三种灵圃。 */
    private static final Map<String, Soil> SOILS = Map.of(
            "TR_QMLP", new Soil("TR_QMLP", "青木灵圃", 180_000L, 1, 3, false),
            "TR_XHLP", new Soil("TR_XHLP", "星辉灵圃", 30_000L, 5, 10, false),
            "TR_HMSG", new Soil("TR_HMSG", "鸿蒙神圃", 3_000L, 15, 20, true));

    /**
     * 果实 id → 功德点。
     *
     * <p>功德点同时是「天道货币兑换」的折算依据，两份表必须一致（原脚本注释也强调了这点）。
     * 这里只用来决定成熟时长：功德点越高的果实长得越慢。</p>
     */
    private static final Map<String, Integer> FRUIT_GONGDE = Map.ofEntries(
            Map.entry("TR_LZ01_FRUIT", 1), Map.entry("TR_LZ02_FRUIT", 1),
            Map.entry("TR_LZ03_FRUIT", 1), Map.entry("TR_LZ04_FRUIT", 1),
            Map.entry("TR_LZ05_FRUIT", 1), Map.entry("TR_LZ06_FRUIT", 1),
            Map.entry("TR_LZ07_FRUIT", 5), Map.entry("TR_LZ08_FRUIT", 5),
            Map.entry("TR_LZ09_FRUIT", 5), Map.entry("TR_LZ10_FRUIT", 5),
            Map.entry("TR_LZ11_FRUIT", 5),
            Map.entry("TR_LZ12_FRUIT", 30), Map.entry("TR_LZ13_FRUIT", 30),
            Map.entry("TR_LZ14_FRUIT", 30),
            Map.entry("TR_LZ15_FRUIT", 100), Map.entry("TR_LZ16_FRUIT", 100));

    /** 事件类型标识。 */
    public static final String EVENT_WATER = "water";
    /** 事件类型标识。 */
    public static final String EVENT_HOE = "hoe";
    /** 事件类型标识。 */
    public static final String EVENT_BONE = "bone";

    /** 单类事件可累计的上限。 */
    private static final int EVENT_CAP = 4;

    // ------------------------------------------------------------------
    // 实例字段
    // ------------------------------------------------------------------

    /** 土壤 id；null 表示这不是一块有效地块。 */
    public String soilId;
    /** 已种下的种子 id；null 表示空地。 */
    public String seedId;
    /** 待完成的浇水次数。 */
    public int water;
    /** 待完成的翻耕次数。 */
    public int hoe;
    /** 待完成的施肥次数。 */
    public int bone;
    /** 已经出现过的农事事件总数。 */
    public int eventsAdded;
    /** 本次种植总共会出现的事件数（1~5）。 */
    public int totalEvents;
    /** 下一次事件出现的绝对时间戳（ms）。 */
    public long nextEventTime;
    /** 成熟剩余毫秒。 */
    public long remainingMs;
    /** 上次推进倒计时的时间戳（ms）。 */
    public long lastUpdate;
    /** 是否因存在未处理事件而暂停倒计时。 */
    public boolean paused;
    /** 种植时刻（ms）。 */
    public long plantTime;

    // ------------------------------------------------------------------
    // 存取
    // ------------------------------------------------------------------

    /** 从存档字段还原。土壤 id 缺失或非法时返回 null（视为该格没有地块）。 */
    @SuppressWarnings("unchecked")
    public static LingpuPlot fromJson(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> map = (Map<String, Object>) m;
        String soilId = str(map.get("soilId"));
        if (soilId == null || !SOILS.containsKey(soilId)) return null;

        LingpuPlot p = new LingpuPlot();
        p.soilId = soilId;
        p.seedId = str(map.get("seedId"));
        Object evRaw = map.get("events");
        if (evRaw instanceof Map<?, ?> ev) {
            p.water = num(ev.get(EVENT_WATER));
            p.hoe = num(ev.get(EVENT_HOE));
            p.bone = num(ev.get(EVENT_BONE));
        }
        p.eventsAdded = num(map.get("eventsAdded"));
        p.totalEvents = num(map.get("totalEvents"));
        p.nextEventTime = lng(map.get("nextEventTime"));
        p.remainingMs = lng(map.get("remainingMs"));
        p.lastUpdate = lng(map.get("lastUpdate"));
        p.paused = Boolean.TRUE.equals(map.get("paused"));
        p.plantTime = lng(map.get("plantTime"));
        return p;
    }

    /** 序列化回存档（键名与顺序沿用原脚本）。 */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("soilId", soilId);
        m.put("seedId", seedId);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put(EVENT_WATER, water);
        ev.put(EVENT_HOE, hoe);
        ev.put(EVENT_BONE, bone);
        m.put("events", ev);
        m.put("eventsAdded", eventsAdded);
        m.put("totalEvents", totalEvents);
        m.put("nextEventTime", nextEventTime);
        m.put("remainingMs", remainingMs);
        m.put("lastUpdate", lastUpdate);
        m.put("paused", paused);
        m.put("plantTime", plantTime);
        return m;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 该土壤的等级信息（土壤 id 合法时不会为 null）。 */
    public Soil soil() {
        return SOILS.get(soilId);
    }

    /** 是否鸿蒙神圃。 */
    public boolean ultimate() {
        Soil s = soil();
        return s != null && s.ultimate();
    }

    /** 是否已种下种子。 */
    public boolean planted() {
        return seedId != null && !seedId.isBlank();
    }

    /** 倒计时是否走完（注意：走完不代表能收，还要看事件是否做完）。 */
    public boolean mature() {
        return remainingMs <= 0;
    }

    /** 农事事件是否全部完成。 */
    public boolean eventsDone() {
        return eventsAdded >= totalEvents && water <= 0 && hoe <= 0 && bone <= 0;
    }

    /** 可收获：倒计时走完，且（鸿蒙神圃 或 事件全部完成）。 */
    public boolean harvestable() {
        Soil s = soil();
        if (s == null || !planted() || !mature()) return false;
        return s.ultimate() || eventsDone();
    }

    /** 待完成事件的文字描述。 */
    public String pendingEventsText() {
        StringBuilder sb = new StringBuilder();
        if (water > 0) sb.append("浇水x").append(water);
        if (hoe > 0) {
            if (sb.length() > 0) sb.append('、');
            sb.append("翻耕x").append(hoe);
        }
        if (bone > 0) {
            if (sb.length() > 0) sb.append('、');
            sb.append("施肥x").append(bone);
        }
        return sb.length() > 0 ? sb.toString() : "无需事件";
    }

    /** 种子 id → 果实 id。非种子 id 原样返回（与原脚本的 {@code /_SEED$/} 替换等价）。 */
    public static String seedToFruit(String seedId) {
        if (seedId == null) return null;
        return seedId.endsWith("_SEED")
                ? seedId.substring(0, seedId.length() - "_SEED".length()) + "_FRUIT"
                : seedId;
    }

    /**
     * 成熟总时长：按土壤档次 + 果实功德点分级。
     *
     * <p>青木 3~5 分钟、星辉 30~50 秒、鸿蒙固定 3 秒。功德点越高时间越长——这是原脚本刻意的
     * 平衡：高功德果实产出多，用时间换。</p>
     */
    public long duration() {
        String fruitId = seedToFruit(seedId);
        int gongde = fruitId == null ? 1 : FRUIT_GONGDE.getOrDefault(fruitId, 1);
        if ("TR_QMLP".equals(soilId)) {
            if (gongde <= 1) return 180_000L;
            if (gongde <= 5) return 210_000L;
            if (gongde <= 30) return 240_000L;
            return 300_000L;
        }
        if ("TR_XHLP".equals(soilId)) {
            if (gongde <= 1) return 30_000L;
            if (gongde <= 5) return 35_000L;
            if (gongde <= 30) return 40_000L;
            return 50_000L;
        }
        Soil s = soil();
        return s == null ? 3_000L : s.duration();
    }

    // ------------------------------------------------------------------
    // 状态推进
    // ------------------------------------------------------------------

    /** 种下普通灵圃的种子：初始化事件表与倒计时。 */
    public void plantNormalSeed(String seed) {
        seedId = seed;
        plantTime = System.currentTimeMillis();
        totalEvents = 1 + ThreadLocalRandom.current().nextInt(5);
        eventsAdded = 0;
        water = 0;
        hoe = 0;
        bone = 0;
        nextEventTime = System.currentTimeMillis() + 3_000L
                + ThreadLocalRandom.current().nextInt(5_000);
        remainingMs = duration();
        lastUpdate = System.currentTimeMillis();
        paused = false;
    }

    /** 种下鸿蒙神圃的种子：没有事件，倒计时直接起跑。 */
    public void plantUltimateSeed(String seed) {
        seedId = seed;
        plantTime = System.currentTimeMillis();
        water = 0;
        hoe = 0;
        bone = 0;
        eventsAdded = 0;
        totalEvents = 0;
        nextEventTime = 0L;
        Soil s = soil();
        remainingMs = (s == null) ? 3_000L : s.duration();
        lastUpdate = System.currentTimeMillis();
        paused = false;
    }

    /**
     * 推进一次状态：走倒计时、按时间点刷事件、重算暂停标志。
     *
     * <p>顺序与原脚本严格一致，尤其是<b>最后</b>用「是否还有未处理事件」覆盖 {@code paused}
     * —— 前面判 {@code !paused} 用的是上一轮的暂停状态，所以一次调用里倒计时最多被扣掉
     * 「距上次推进」的时长，不会因为同一次调用里刷出新事件就少扣。</p>
     */
    public void tick() {
        if (!planted()) return;
        long now = System.currentTimeMillis();
        if (lastUpdate <= 0) lastUpdate = now;

        if (!paused && remainingMs > 0) {
            remainingMs -= (now - lastUpdate);
            if (remainingMs < 0) remainingMs = 0;
        }
        lastUpdate = now;

        // 事件到点了就补上。理论上 totalEvents ≤ 5 而单类上限 4，候选集不会空，
        // 因此这个循环最多跑一次；仍加上次数上限，避免将来改动 totalEvents 时死循环。
        for (int guard = 0; guard < 16 && eventsAdded < totalEvents && nextEventTime <= now; guard++) {
            if (!addRandomEvent()) break;
        }

        paused = (water + hoe + bone) > 0;
    }

    /**
     * 追加一个随机事件。
     *
     * @return 是否成功追加（候选集为空时返回 false，调用方据此中断循环）
     */
    private boolean addRandomEvent() {
        if (eventsAdded >= totalEvents) return false;
        String[] types = {EVENT_WATER, EVENT_HOE, EVENT_BONE};
        int[] cur = {water, hoe, bone};
        java.util.List<Integer> candidates = new java.util.ArrayList<>(3);
        for (int i = 0; i < types.length; i++) {
            if (cur[i] < EVENT_CAP) candidates.add(i);
        }
        if (candidates.isEmpty()) return false;
        int pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        switch (pick) {
            case 0 -> water++;
            case 1 -> hoe++;
            default -> bone++;
        }
        eventsAdded++;
        nextEventTime = System.currentTimeMillis() + 3_000L
                + ThreadLocalRandom.current().nextInt(5_000);
        return true;
    }

    /**
     * 完成一次农事事件。
     *
     * @param type {@link #EVENT_WATER}／{@link #EVENT_HOE}／{@link #EVENT_BONE}
     * @return 是否真的消耗掉了一个待办（没有待办时返回 false，调用方不要扣玩家手上的工具/材料）
     */
    public boolean consumeEvent(String type) {
        switch (type) {
            case EVENT_WATER -> {
                if (water <= 0) return false;
                water--;
            }
            case EVENT_HOE -> {
                if (hoe <= 0) return false;
                hoe--;
            }
            case EVENT_BONE -> {
                if (bone <= 0) return false;
                bone--;
            }
            default -> {
                return false;
            }
        }
        if (eventsDone()) {
            // 事件做完，恢复倒计时（从此刻重新计，避免把做事件这段时间也算进成熟时间）
            paused = false;
            lastUpdate = System.currentTimeMillis();
        }
        return true;
    }

    /** 聚灵散催熟：减少 10 秒。 */
    public boolean speedUp(long ms) {
        if (!planted() || remainingMs <= 0) return false;
        remainingMs = Math.max(0L, remainingMs - ms);
        return true;
    }

    /** 成熟剩余秒数（向上取整，显示用）。 */
    public long remainingSeconds() {
        return Math.max(0L, (remainingMs + 999L) / 1000L);
    }

    /** 是否会因为「果实不存在」而出不了货（防御性检查用）。 */
    public static boolean knownSoil(String soilId) {
        return soilId != null && SOILS.containsKey(soilId);
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /** 按土壤 id 取名字，未知道路返回 id 本身。 */
    public static String soilName(String soilId) {
        Soil s = SOILS.get(soilId);
        return s == null ? String.valueOf(soilId) : s.name();
    }

    /** 页数（公式见类注释，照抄原脚本的 off-by-one）。 */
    public static int pageCount(Iterable<String> plotKeys) {
        long maxIndex = -1;
        for (String k : plotKeys) {
            try {
                maxIndex = Math.max(maxIndex, Long.parseLong(k.trim()));
            } catch (NumberFormatException ignored) {
                // 非数字键（理论上不会出现）忽略
            }
        }
        return (int) Math.max(1L, Math.floorDiv(maxIndex + 1, 45L) + 1L);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
