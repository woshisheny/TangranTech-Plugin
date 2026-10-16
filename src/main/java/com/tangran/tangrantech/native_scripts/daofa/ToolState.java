package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.AddonPaths;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「糖b四工具」与「破法弓」的玩家开关存档。
 *
 * <p>两份装备共用同一份文件：{@code addon_configs/糖然科技/糖b工具/<玩家名>.json}，
 * 顶层按「工具 id」分键：</p>
 * <pre>
 * {
 *   "Tr_ktg": { "on": true, "silk": false, "smelt": true },
 *   "Tr_zjf": { "on": true, "silk": false, "smelt": true },
 *   "Tr_pfg": { "on": true, "special": false, "flame": false, "infinity": true,
 *               "potion": false, "potions": [], "heal": false,
 *               "tp": false, "freeze": false, "boom": false }
 * }
 * </pre>
 *
 * <p><b>为什么不用物品 NBT</b>：原脚本注释记录过 —— 这版服务端上
 * {@code setLocalizedName} 会被 {@code displayName} 覆盖、PDC 又可能解析到只读 View，
 * 所以开关只能按「玩家 × 工具」记在文件里。这也意味着一把工具在被交易给别的玩家后，
 * 开关状态跟着<b>新主人</b>走（沿用原行为）。</p>
 *
 * <p><b>缓存</b>：读一次进内存，之后都读内存；写的时候内存与磁盘一起更新。
 * 这与原脚本的 {@code TWIN_STATE_CACHE} 一致 —— 好处是界面上狂点开关不会反复读盘。</p>
 *
 * <p><b>一处刻意的差异</b>：原脚本落盘用紧凑 JSON（{@code JSON.stringify(data)}），
 * 这里用 2 空格缩进（管理员会直接打开看）。两种写法都能被读取方解析。</p>
 */
public final class ToolState {

    private ToolState() {}

    /** 存档子目录（相对插件数据目录）。 */
    public static final String DIR = "糖b工具";

    /** 玩家名 → 该玩家的整份开关数据（内存缓存）。 */
    private static final Map<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    private static File file(String playerName) {
        return new File(AddonPaths.configDir(DIR), String.valueOf(playerName) + ".json");
    }

    /** 取整份开关数据（必要时从磁盘读并缓存）。永不返回 null。 */
    public static Map<String, Object> data(String playerName) {
        String key = String.valueOf(playerName);
        Map<String, Object> cached = CACHE.get(key);
        if (cached != null) return cached;

        Map<String, Object> loaded = AddonPaths.readJson(file(key));
        if (loaded == null) loaded = new LinkedHashMap<>();
        CACHE.put(key, loaded);
        return loaded;
    }

    /** 取某个键下的子对象（可能是空 map）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sub(String playerName, String key) {
        Object v = data(playerName).get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<>();
    }

    /** 写入某个键的对象并落盘。 */
    public static void put(String playerName, String key, Map<String, Object> value) {
        if (playerName == null || key == null || value == null) return;
        data(playerName).put(key, value);
        save(playerName);
    }

    /** 落盘。 */
    public static void save(String playerName) {
        try {
            AddonPaths.writeJson(file(playerName), data(playerName));
        } catch (Throwable t) {
            TT.log("[装备] 保存工具开关失败 " + playerName + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // 取值辅助（与 JS 的 `x !== false` / `x === true` 语义一一对应）
    // ------------------------------------------------------------------

    /** {@code e.x === true}。 */
    public static boolean isTrue(Map<String, Object> m, String key) {
        return m != null && Boolean.TRUE.equals(m.get(key));
    }

    /** {@code e.x === false}。 */
    public static boolean isFalse(Map<String, Object> m, String key) {
        return m != null && Boolean.FALSE.equals(m.get(key));
    }

    /** 取字符串列表（缺失返回空列表）。 */
    public static List<String> strList(Map<String, Object> m, String key) {
        if (m == null) return List.of();
        Object v = m.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        java.util.List<String> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** 供调试/卸载使用：清空内存缓存。 */
    public static void shutdown() {
        CACHE.clear();
    }
}
