package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.tangran.tangrantech.native_scripts.daofa.QuestsScript;

/**
 * 「道法/BOSS战」的玩家进度存档。
 *
 * <h2>路径与字段不能改</h2>
 * <p>文件：{@code addon_configs/糖然科技/BOSS战/<玩家名>.json}</p>
 * <p>字段：</p>
 * <ul>
 *   <li>{@code cleared} —— 已通关的阶段号数组（1/2/3/4）；</li>
 *   <li>{@code guardians} —— 已击败的护法 id 数组（{@code zqx125} / {@code Katarina} / {@code D_RKIN}）。</li>
 * </ul>
 * <p><b>这两样都被「道法/任务」直接读取</b>（见 {@code BossBattleBridge#readProgress} 与
 * {@code QuestsScript} 的「通关三阶段」判定）。改路径或字段名会让任务八的显示静默失效 ——
 * 这不是「改个常量」那么轻的事。</p>
 *
 * <h2>为什么写 JSON 而不是用 BlockData / PDC</h2>
 * <p>原脚本就是这么存的，而且「任务」「浮生当铺」「天道证明」等脚本都在读同一批 JSON 目录，
 * 保持一致比「技术更优」重要。另外这些文件是<b>玩家级</b>（一人一份），
 * 多玩家并行挑战时不会互相覆盖。</p>
 */
public final class BossProgress {

    private BossProgress() {}

    /** 存档子目录名（在 {@code addon_configs/糖然科技/} 下）。 */
    public static final String DIR = "BOSS战";

    /** 字段名：已通关的阶段号。 */
    public static final String KEY_CLEARED = "cleared";
    /** 字段名：已击败的护法 id。 */
    public static final String KEY_GUARDIANS = "guardians";

    /** 玩家进度文件。 */
    public static File fileOf(String playerName) {
        String name = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        return new File(AddonPaths.configDir(DIR), PlayerKeys.key(name) + ".json");
    }

    /**
     * 进度内存缓存（玩家名 → 已规范化的进度表）。
     *
     * <p>进度是「只增不减」的小表，内存即权威、磁盘是落盘副本：load 首次读盘后进缓存，
     * save 同步刷新缓存并写盘（写穿）。没有这道缓存时，任务菜单渲染反复调
     * {@code hasBeatenGuardian}/{@code hasPhase}，每次都是一次同步磁盘读。</p>
     */
    private static final Map<String, Map<String, Object>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String nameKey(String playerName) {
        return (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
    }

    /**
     * 读进度。
     *
     * <p>文件缺失或损坏时返回一份「空进度」而不是 null —— 调用方永远拿到可用的 Map，
     * 这是原脚本 {@code loadBossProgress} 的行为（{@code return { cleared: [] }}）。</p>
     */
    public static Map<String, Object> load(String playerName) {
        String key = nameKey(playerName);
        Map<String, Object> cached = CACHE.get(key);
        if (cached != null) return cached;
        Map<String, Object> data = null;
        try {
            data = AddonPaths.readJson(fileOf(playerName));
        } catch (Throwable t) {
            BossLog.warn("读 BOSS战 进度失败 " + playerName + ": " + t);
            data = null;
        }
        if (data == null) data = new LinkedHashMap<>();
        // 保证两个数组字段一定存在（原脚本同样做这一步）
        if (!(data.get(KEY_CLEARED) instanceof List)) data.put(KEY_CLEARED, new ArrayList<>());
        if (!(data.get(KEY_GUARDIANS) instanceof List)) data.put(KEY_GUARDIANS, new ArrayList<>());
        CACHE.put(key, data);
        return data;
    }

    /** 写进度（内存缓存同步刷新；2 空格缩进，与原脚本的 {@code JSON.stringify(data, null, 2)} 一致）。 */
    public static void save(String playerName, Map<String, Object> data) {
        try {
            CACHE.put(nameKey(playerName), data);
            AddonPaths.writeJson(fileOf(playerName), data);
        } catch (Throwable t) {
            BossLog.warn("写 BOSS战 进度失败 " + playerName + ": " + t);
        }
    }

    /** 已通关的阶段号。 */
    public static List<Integer> cleared(String playerName) {
        List<Integer> out = new ArrayList<>();
        for (Object o : Json.arr(load(playerName), KEY_CLEARED)) {
            if (o instanceof Number n) out.add(n.intValue());
        }
        return out;
    }

    /** 某个阶段是否已通关。 */
    public static boolean hasPhase(String playerName, int phase) {
        return cleared(playerName).contains(phase);
    }

    /** 标记某阶段通关（幂等：已记录就不重复加）。 */
    public static void markPhaseCleared(String playerName, int phase) {
        try {
            Map<String, Object> data = load(playerName);
            List<Object> list = Json.arr(data, KEY_CLEARED);
            boolean has = false;
            for (Object o : list) {
                if (o instanceof Number n && n.intValue() == phase) {
                    has = true;
                    break;
                }
            }
            if (has) return;
            // Json.arr 返回的可能是不可变列表 → 统一替换成新的可变副本再改
            List<Object> next = new ArrayList<>(list);
            next.add(phase);
            data.put(KEY_CLEARED, next);
            save(playerName, data);
        } catch (Throwable t) {
            BossLog.warn("标记阶段通关失败 " + playerName + " phase=" + phase + ": " + t);
        }
    }

    /** 已击败的护法 id。 */
    public static List<String> guardians(String playerName) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(load(playerName), KEY_GUARDIANS)) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** 是否已击败过这位护法。 */
    public static boolean hasBeatenGuardian(String playerName, String guardianId) {
        if (guardianId == null) return false;
        return guardians(playerName).contains(guardianId);
    }

    /** 标记护法已被击败（幂等）。 */
    public static void markGuardianDefeated(String playerName, String guardianId) {
        try {
            if (guardianId == null) return;
            Map<String, Object> data = load(playerName);
            List<Object> list = Json.arr(data, KEY_GUARDIANS);
            if (list.contains(guardianId)) return;
            List<Object> next = new ArrayList<>(list);
            next.add(guardianId);
            data.put(KEY_GUARDIANS, next);
            save(playerName, data);
        } catch (Throwable t) {
            BossLog.warn("标记护法击败失败 " + playerName + " " + guardianId + ": " + t);
        }
    }
}
