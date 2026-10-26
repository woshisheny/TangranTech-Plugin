package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.AddonPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 双子账本（原脚本 7728-8360 行）：<b>经营货币不进背包，直接记在天实兑换菜单的账本里</b>。
 *
 * <p>货币 13 种：虚币（打死虚仆）+ 矿物精华/木之精华（挖矿/伐木）+ 泥土点/沙子点/玻璃点/
 * 陶瓦点/灵魂点（铲土）+ 各作物点（萝卜/西瓜/甘蔗/下界疣/可可豆）。
 * 另有 4 个<b>增益购买次数</b>（攻击力/护甲值/吸血/四重祝福）存在同一份文件里，
 * 但不作为货币显示 —— 它们的属性应用在批次 6c（天实商店）。</p>
 *
 * <h2>持久化口径（原脚本三条实测教训）</h2>
 * <ul>
 *   <li><b>写盘用「临时文件 + 原子替换」</b>：直接覆写在崩溃时会留下半个 JSON，
 *       下一次读出来就是空的，玩家攒的点全没。</li>
 *   <li><b>读失败只告警一次</b>：反复读同一个坏文件每秒刷一条日志。</li>
 *   <li><b>攒批写</b>：每次 add 都写盘会把主线程拖成 IO 沙尘暴 —— 内存先记账，
 *       标脏，统一由 5 秒一次的冲刷任务落盘。</li>
 * </ul>
 */
public final class TwinLedger {

    private TwinLedger() {}

    // ==================================================================
    // 货币与显示名（原脚本 XU_CUR_NAME / XU_UPGRADE_KEYS）
    // ==================================================================

    /** 全部账本键 → 显示名。 */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("coin", "虚币"),
            Map.entry("mineral", "矿物精华"),
            Map.entry("wood", "木之精华"),
            Map.entry("dirt", "泥土点"),
            Map.entry("sand", "沙子点"),
            Map.entry("glass", "玻璃点"),
            Map.entry("terracotta", "陶瓦点"),
            Map.entry("soul", "灵魂点"),
            Map.entry("carrot", "萝卜点"),
            Map.entry("melon", "西瓜点"),
            Map.entry("cane", "甘蔗点"),
            Map.entry("wart", "下界疣点"),
            Map.entry("cocoa", "可可豆点"),
            // 增益购买次数（不作为货币显示，同文件存储）
            Map.entry("upAtk", "攻击力提升"),
            Map.entry("upArmor", "护甲值提升"),
            Map.entry("upLs", "吸血效果"),
            Map.entry("upBuff4", "四重祝福"),
            // 双子挑战周目（第几次）：与账本同文件存储，不作为货币显示、不入账播报
            Map.entry("week", "周目"));

    /** 显示名（未知键原样返回）。 */
    public static String name(String key) {
        return NAMES.getOrDefault(key, String.valueOf(key));
    }

    // ==================================================================
    // 存储
    // ==================================================================

    /** 玩家名 → 账本（内存权威数据）。 */
    private static final Map<String, Map<String, Integer>> CACHE = new ConcurrentHashMap<>();

    /** 读过坏文件的玩家（只告警一次，避免刷屏）。 */
    private static final Map<String, Long> BAD_LOGGED = new ConcurrentHashMap<>();

    /** 攒批写的脏标记。 */
    private static final Map<String, Boolean> DIRTY = new ConcurrentHashMap<>();

    /** 入账播报队列：玩家 UUID → (键 → 待播数量)。 */
    private static final Map<UUID, Map<String, Integer>> GAIN_QUEUE = new ConcurrentHashMap<>();

    private static volatile boolean flushTaskStarted;
    private static volatile boolean gainTaskStarted;

    private static File fileOf(String playerName) {
        return new File(AddonPaths.configDir("双子账本"), playerName + ".json");
    }

    private static Map<String, Integer> blank() {
        Map<String, Integer> led = new LinkedHashMap<>();
        for (String key : NAMES.keySet()) led.put(key, 0);
        return led;
    }

    /**
     * 读账本：文件缺失（新玩家）时返回并缓存全 0 空账本；文件存在但读不出（损坏 /
     * 半截 JSON）时只返回<b>不缓存</b>的临时空账本。
     *
     * <p>两者的区别是数据安全的分界线：缺失文件的全 0 就是权威数据，必须缓存——否则
     * add/pay 会落在临时对象上被丢弃；而损坏文件的全 0 绝不能缓存成权威——否则任何
     * 一次入账置脏后，5 秒内 flush 会用全 0 覆盖玩家的完好存档（点数与增益次数永久
     * 清零）。损坏窗口内读操作按 0 看、写操作不落盘，等文件被修复或删除后自动恢复。</p>
     */
    public static Map<String, Integer> of(Player player) {
        String name = player.getName();
        Map<String, Integer> cached = CACHE.get(name);
        if (cached != null) return cached;
        Map<String, Integer> led = blank();
        try {
            File f = fileOf(name);
            if (!f.exists()) {
                // 首次玩家：还没有存档，全 0 即权威，必须缓存
                CACHE.put(name, led);
                return led;
            }
            Map<String, Object> data = AddonPaths.readJson(f);
            if (data == null) {
                // 注意 readJson 对损坏文件是吞异常返回 null，不会抛——必须在这里拦
                warnBad(name, "双子账本文件存在但无法解析，暂不载入也不覆写"
                        + "（期间点数按 0 显示、变动不落盘）");
                return led;
            }
            for (String key : NAMES.keySet()) {
                Object v = data.get(key);
                if (v instanceof Number n) led.put(key, Math.max(0, n.intValue()));
            }
            CACHE.put(name, led);
        } catch (Throwable t) {
            warnBad(name, "双子账本读取失败：" + t);
        }
        return led;
    }

    /** 同一玩家的异常告警 60 秒最多一条（坏文件 / 写盘失败共用，避免刷屏）。 */
    private static void warnBad(String name, String msg) {
        Long logged = BAD_LOGGED.get(name);
        if (logged == null || System.currentTimeMillis() - logged > 60_000L) {
            BAD_LOGGED.put(name, System.currentTimeMillis());
            BossLog.warn(msg + "（" + name + "）");
        }
    }

    /**
     * 落盘（.tmp + 原子替换由 {@link AddonPaths#writeJson} 负责；只写有变化的玩家）。
     *
     * <p>写失败时<b>保留脏标记</b>，由 5 秒冲刷任务下轮重试——writeJson 失败不抛异常
     * 只返回 false，不看返回值就会把整批变更静默丢掉。缓存里没有该玩家（含「文件损坏
     * 不缓存」的窗口）时无可落盘内容，摘掉脏标记防止每 5 秒空转。</p>
     */
    private static void flush(String playerName) {
        try {
            Map<String, Integer> led = CACHE.get(playerName);
            if (led == null) {
                DIRTY.remove(playerName);
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>(led);
            if (AddonPaths.writeJson(fileOf(playerName), data)) {
                DIRTY.remove(playerName);
            } else {
                warnBad(playerName, "双子账本写盘失败，变更保留待重试");
            }
        } catch (Throwable t) {
            warnBad(playerName, "双子账本写盘异常，变更保留待重试：" + t);
        }
    }

    /** 启动冲刷任务（5 秒一次，只写脏的）。 */
    private static void ensureFlushTask() {
        if (flushTaskStarted) return;
        flushTaskStarted = true;
        Plugin plugin = TT.plugin();
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                for (String name : new ArrayList<>(DIRTY.keySet())) {
                    flush(name);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }, 100L, 100L);
    }

    // ==================================================================
    // 记账
    // ==================================================================

    /** 记一笔账（负数按 0 夹住）+ 排一条入账播报。 */
    public static void add(Player player, String key, int n) {
        try {
            if (player == null || key == null || n == 0) return;
            ensureFlushTask();
            ensureGainTask();
            Map<String, Integer> led = of(player);
            int next = Math.max(0, led.getOrDefault(key, 0) + n);
            led.put(key, next);
            DIRTY.put(player.getName(), Boolean.TRUE);
            queueGain(player, key, n);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 读一个键的当前数量。 */
    public static int count(Player player, String key) {
        try {
            return of(player).getOrDefault(key, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 双子挑战周目（第几次挑战双子）。 */
    public static int week(Player player) {
        return count(player, "week");
    }

    /**
     * 周目 +1 并返回新值（进双子阶段时调，对齐原脚本 {@code xuTwinRecSave(player, {week})}）。
     *
     * <p>不走 {@link #add}：周目不是货币，不该触发入账播报。</p>
     */
    public static int incrementWeek(Player player) {
        try {
            if (player == null) return 0;
            ensureFlushTask();
            Map<String, Integer> led = of(player);
            int next = led.getOrDefault("week", 0) + 1;
            led.put("week", next);
            DIRTY.put(player.getName(), Boolean.TRUE);
            return next;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 买得起吗（价格表逐项核对）。 */
    public static boolean has(Player player, Map<String, Integer> price) {
        try {
            if (price == null || price.isEmpty()) return true;
            Map<String, Integer> led = of(player);
            for (Map.Entry<String, Integer> e : price.entrySet()) {
                if (led.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 扣款（买得起才扣；返回是否成功）。 */
    public static boolean pay(Player player, Map<String, Integer> price) {
        try {
            if (!has(player, price)) return false;
            ensureFlushTask();
            Map<String, Integer> led = of(player);
            for (Map.Entry<String, Integer> e : price.entrySet()) {
                led.put(e.getKey(), led.getOrDefault(e.getKey(), 0) - e.getValue());
            }
            DIRTY.put(player.getName(), Boolean.TRUE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 价格表的可读形式（{@code 虚币×10 矿物精华×5}）。 */
    public static String priceText(Map<String, Integer> price) {
        try {
            if (price == null || price.isEmpty()) return "免费";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : price.entrySet()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(name(e.getKey())).append("×").append(e.getValue());
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // ==================================================================
    // 入账播报（同类合并，每 20 拍汇总一次；虚币由击杀提示单独报，这里不重复）
    // ==================================================================

    private static void ensureGainTask() {
        if (gainTaskStarted) return;
        gainTaskStarted = true;
        Plugin plugin = TT.plugin();
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (GAIN_QUEUE.isEmpty()) return;
                for (Map.Entry<UUID, Map<String, Integer>> e : GAIN_QUEUE.entrySet()) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p == null || !p.isOnline()) continue;
                    StringBuilder sb = new StringBuilder("§e[账本] §f");
                    boolean any = false;
                    for (Map.Entry<String, Integer> g : e.getValue().entrySet()) {
                        if (g.getValue() == 0) continue;
                        if (any) sb.append("，");
                        sb.append(name(g.getKey())).append(" §a+").append(g.getValue()).append("§f");
                        any = true;
                    }
                    if (any) {
                        try {
                            p.sendMessage(sb.toString());
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
                GAIN_QUEUE.clear();
            } catch (Throwable ignored) {
                // 忽略
            }
        }, 20L, 20L);
    }

    private static void queueGain(Player player, String key, int n) {
        try {
            GAIN_QUEUE.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>())
                    .merge(key, n, Integer::sum);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 清空一位玩家的账本（正常流程不自动调用：物资点数跨局保留）。 */
    public static void clear(String playerName) {
        try {
            CACHE.remove(playerName);
            File f = fileOf(playerName);
            if (f.exists()) {
                AddonPaths.writeJson(f, new LinkedHashMap<>(blank()));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 强制冲刷 + 清缓存（插件卸载时）。 */
    public static void shutdown() {
        for (String name : new ArrayList<>(DIRTY.keySet())) {
            flush(name);
        }
        CACHE.clear();
        DIRTY.clear();
        GAIN_QUEUE.clear();
    }

    /** 供 TwinMinigame 构造价格表用（可变 Map）。 */
    public static Map<String, Integer> price(String key1, int n1) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(key1, n1);
        return m;
    }

    /** 供 TwinMinigame 构造价格表用（两键）。 */
    public static Map<String, Integer> price(String k1, int n1, String k2, int n2) {
        Map<String, Integer> m = price(k1, n1);
        m.put(k2, n2);
        return m;
    }

    /** 供 TwinMinigame 构造价格表用（三键）。 */
    public static Map<String, Integer> price(String k1, int n1, String k2, int n2, String k3, int n3) {
        Map<String, Integer> m = price(k1, n1, k2, n2);
        m.put(k3, n3);
        return m;
    }

    /** 供 TwinMinigame 构造价格表用（四键）。 */
    public static Map<String, Integer> price(String k1, int n1, String k2, int n2, String k3, int n3, String k4, int n4) {
        Map<String, Integer> m = price(k1, n1, k2, n2, k3, n3);
        m.put(k4, n4);
        return m;
    }

    /** 供 TwinMinigame 构造价格表用（五键）。 */
    public static Map<String, Integer> price5(String k1, int n1, String k2, int n2, String k3, int n3, String k4, int n4, String k5, int n5) {
        Map<String, Integer> m = price(k1, n1, k2, n2, k3, n3, k4, n4);
        m.put(k5, n5);
        return m;
    }

    /** 供批次 6c 的商店读玩家名（冲刷用）。 */
    static void flushNow(String playerName) {
        flush(playerName);
    }

    // ==================================================================
    // 批次 6c：账本文本 / 缺额 / 增益购买次数
    // ==================================================================

    /** 增益购买 +1（不走入账播报；原脚本 xuUpgradeAdd）。 */
    public static void upgradeAdd(Player player, String key, int n) {
        try {
            if (player == null || key == null || n == 0) return;
            ensureFlushTask();
            Map<String, Integer> led = of(player);
            led.put(key, Math.max(0, led.getOrDefault(key, 0) + n));
            DIRTY.put(player.getName(), Boolean.TRUE);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 账本全文（主页账本书的 lore 用；0 的键不显示）。 */
    public static String text(Player player) {
        try {
            Map<String, Integer> led = of(player);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : led.entrySet()) {
                if (e.getValue() <= 0 || e.getKey().startsWith("up") || "week".equals(e.getKey())) continue;
                if (sb.length() > 0) sb.append("，");
                sb.append(name(e.getKey())).append("×").append(e.getValue());
            }
            return sb.length() > 0 ? sb.toString() : "（空）";
        } catch (Throwable ignored) {
            return "（空）";
        }
    }

    /** 还缺什么（只列不够的项；原脚本 xuShortText）。 */
    public static String shortText(Player player, Map<String, Integer> price) {
        try {
            Map<String, Integer> led = of(player);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : price.entrySet()) {
                int have = led.getOrDefault(e.getKey(), 0);
                if (have >= e.getValue()) continue;
                if (sb.length() > 0) sb.append("，");
                sb.append(name(e.getKey())).append("×").append(e.getValue() - have);
            }
            return sb.length() > 0 ? sb.toString() : "（已足够）";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 播报队列是否为空（测试用）。 */
    static boolean gainQueueEmpty() {
        return GAIN_QUEUE.isEmpty();
    }
}
