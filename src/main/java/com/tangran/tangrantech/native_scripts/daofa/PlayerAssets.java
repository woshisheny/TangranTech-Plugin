package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Perms;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.tangran.tangrantech.native_scripts.daofa.HeavenCurrencyScript;
import com.tangran.tangrantech.native_scripts.daofa.PillsScript;
import com.tangran.tangrantech.native_scripts.daofa.YanhuoSelectionScript;

/**
 * 玩家资产的统一读写：道源 / 功德 / 问天玉碟 / 异火权限 / 丹方权限。
 *
 * <p>这五样东西在游戏里由多个界面共同读写：天道货币兑换台、浮生当铺、异火选择、丹药、
 * BOSS战…。在 RSC 版里每个脚本都自己拼一遍文件路径与字段名，只要有一处写错就会让
 * 玩家的资产「在 A 界面看得到、在 B 界面看不到」。原生化时把这部分收敛到一处。</p>
 *
 * <h2>四份存档</h2>
 * <table border="1">
 *   <caption>存档位置与字段</caption>
 *   <tr><th>内容</th><th>相对路径</th><th>字段</th></tr>
 *   <tr><td>道源 / 功德</td><td>{@code addon_configs/糖然科技/天道货币/<玩家名>.json}</td>
 *       <td>{@code daoyuan}（数值）、{@code daoyuanExact}（字符串）、{@code gongde}</td></tr>
 *   <tr><td>异火权限</td><td>{@code addon_configs/糖然科技/异火权限/<玩家名>.json}</td>
 *       <td>{@code owned}（id 数组）、{@code selected}</td></tr>
 *   <tr><td>丹方权限</td><td>{@code addon_configs/糖然科技/丹方权限/<玩家名>.json}</td>
 *       <td>{@code owned}（id 数组）、{@code exp}</td></tr>
 *   <tr><td>玉碟归属</td><td>物品 PDC {@code tangran:daoyuan_owner}</td><td>玩家 UUID</td></tr>
 * </table>
 *
 * <h2>为什么要 {@code daoyuanExact} 与 {@code daoyuan} 两个字段</h2>
 * <p>{@code daoyuanExact} 是字符串形式的权威余额，{@code daoyuan} 是数值兼容字段。
 * 原因是 JSON 数字走 double，大额余额累加会掉精度；而老档、以及其它脚本可能只写数值字段。
 * {@link #daoyuanExact} 完整复刻了原脚本的调和规则：</p>
 * <ol>
 *   <li>{@code daoyuanExact} 缺失 → 用 {@code daoyuan}；</li>
 *   <li>精确值为 0 而数值有余额 → 视为「其它脚本更新过」，用数值；</li>
 *   <li>数值明显大于精确值（相对差 &gt; 1e-9）→ 精确值已过期，用数值；</li>
 *   <li>否则以精确值为准。</li>
 * </ol>
 *
 * <p><b>与其它类的重复说明</b>：{@code HeavenCurrencyScript}（天道货币兑换）、
 * {@code YanhuoSelectionScript}（异火选择）、{@code PillsScript}（丹药）目前各自也有一份
 * 读写同一份存档的私有实现，字段名与格式与本类完全一致，因此互通。它们在原生化时早于本类
 * 成型，本轮未强行改动以规避回归；后续如需收敛，把它们的方法体替换成对 {@code PlayerAssets}
 * 的调用即可（签名几乎一一对应）。</p>
 */
public final class PlayerAssets {

    private PlayerAssets() {}

    /** 天道货币存档子目录。 */
    public static final String DIR_CURRENCY = "天道货币";

    /** 问天玉碟的物品 id。 */
    public static final String CARD_ID = "TR_WTYD";

    /** 玉碟归属玩家的 PDC 键（沿用原脚本的命名空间与键名）。 */
    private static final NamespacedKey CARD_OWNER_KEY =
            NamespacedKey.fromString("tangran:daoyuan_owner");

    /** lore 中「器主 / 道源 / 功德」三行的下标。 */
    private static final int LORE_OWNER_LINE = 2;
    private static final int LORE_DAOYUAN_LINE = 3;
    private static final int LORE_GONGDE_LINE = 4;

    /** legacy {@code §} 码 → 组件。 */
    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    // ==================================================================
    // 道源 / 功德
    // ==================================================================

    public static File currencyFile(String playerName) {
        return new File(AddonPaths.configDir(DIR_CURRENCY), PlayerKeys.key(playerName) + ".json");
    }

    private static Map<String, Object> load(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(currencyFile(playerName));
        return data == null ? new LinkedHashMap<>() : data;
    }

    private static void save(String playerName, Map<String, Object> data) {
        AddonPaths.writeJson(currencyFile(playerName), data);
    }

    /** 道源余额（double 视图）。 */
    public static double daoyuan(String playerName) {
        return toDouble(daoyuanExact(playerName), 0);
    }

    /**
     * 道源余额的<b>精确字符串</b>（调和方法见类注释）。
     *
     * <p>做扣款时请用这个字符串配 {@link java.math.BigDecimal}，不要先转 double ——
     * 大额余额下 double 的 15~17 位有效数字会吃掉尾数。</p>
     */
    public static String daoyuanExact(String playerName) {
        Map<String, Object> data = load(playerName);
        Object rawExact = data.get("daoyuanExact");
        Object rawNum = data.get("daoyuan");

        if (rawExact == null) return rawNum == null ? "0" : jsNum(rawNum);
        String exactStr = jsNum(rawExact);
        if (rawNum == null) return exactStr;

        double exactVal = toDouble(rawExact, Double.NaN);
        double numVal = toDouble(rawNum, Double.NaN);
        if (Double.isNaN(exactVal)) return jsNum(rawNum);
        if (Double.isNaN(numVal)) return exactStr;
        // 精确值为 0 但兼容字段有钱 → 旧数据或其它脚本更新过，优先兼容字段
        if (exactVal == 0 && numVal > 0) return jsNum(rawNum);
        // 兼容字段明显大于精确值 → 其它脚本加过钱，精确值已过期
        if (numVal > exactVal) {
            double relative = (numVal - exactVal) / Math.max(1, Math.abs(exactVal));
            if (relative > 1e-9) return jsNum(rawNum);
        }
        return exactStr;
    }

    /** 写入道源余额（同时刷新精确字段与数值字段）。 */
    public static void setDaoyuanExact(String playerName, String exact) {
        Map<String, Object> data = load(playerName);
        data.put("daoyuanExact", String.valueOf(exact));
        data.put("daoyuan", toDouble(exact, 0));
        data.putIfAbsent("gongde", 0);
        save(playerName, data);
    }

    /** 道源余额（数值，会同时写精确字段），供其它脚本按数值写入时使用。 */
    public static void setDaoyuan(String playerName, double value) {
        setDaoyuanExact(playerName, jsNum(value));
    }

    /** 功德余额。 */
    public static double gongde(String playerName) {
        return toDouble(load(playerName).get("gongde"), 0);
    }

    public static void setGongde(String playerName, double value) {
        Map<String, Object> data = load(playerName);
        data.put("gongde", value);
        data.putIfAbsent("daoyuan", 0);
        save(playerName, data);
    }

    /**
     * 按 JS 的 {@code String(x)} 语义格式化：数字整数不带小数点，字符串保持原文。
     *
     * <p>原脚本大量用 {@code String(number)} 拼接余额，Java 的 {@code String.valueOf(2.0)}
     * 会得到 {@code "2.0"} 而 JS 得到 {@code "2"} —— 直接拼会写进存档影响后续比对。</p>
     */
    public static String jsNum(Object o) {
        if (o == null) return "0";
        if (o instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? "0" : t;
        }
        if (o instanceof Boolean b) return b ? "true" : "false";
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(o);
    }

    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    // ==================================================================
    // 异火权限
    // ==================================================================

    private static File yanhuoFile(String playerName) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_YANHUO), PlayerKeys.key(playerName) + ".json");
    }

    /** 玩家已掌握的异火 id（可能为空）。 */
    public static List<String> ownedYanhuo(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(yanhuoFile(playerName));
        List<String> out = new ArrayList<>();
        if (data == null) return out;
        for (Object o : Json.arr(data, "owned")) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 是否已掌握某种异火。
     *
     * <p><b>存档为主、权限为辅</b>：存档里记了就算有（旧档不受影响）；
     * 没记但手里有 {@code tangrantech.yanhuo.<id>} 节点也算 —— 管理员因此可以
     * 直接用权限组发放异火资格，不必改存档文件。</p>
     */
    public static boolean hasYanhuo(String playerName, String id) {
        if (id == null) return false;
        for (String owned : ownedYanhuo(playerName)) {
            if (id.equalsIgnoreCase(owned)) return true;
        }
        Player online = onlineOf(playerName);
        return online != null && Perms.has(online, Perms.yanhuoNode(id));
    }

    /** 记录一个已掌握的异火（保留 {@code selected} 字段不动）。 */
    public static void addYanhuo(String playerName, String id) {
        if (id == null) return;
        Map<String, Object> data = AddonPaths.readJson(yanhuoFile(playerName));
        if (data == null) data = new LinkedHashMap<>();
        List<String> owned = new ArrayList<>();
        for (Object o : Json.arr(data, "owned")) {
            if (o != null) owned.add(String.valueOf(o));
        }
        boolean found = false;
        for (String s : owned) {
            if (id.equalsIgnoreCase(s)) {
                found = true;
                break;
            }
        }
        if (!found) owned.add(id);
        data.put("owned", owned);
        AddonPaths.writeJson(yanhuoFile(playerName), data);
        // 资格同步到权限系统（在线才写；没装 LuckPerms 时是空操作，资格靠存档）
        Player online = onlineOf(playerName);
        if (online != null) Perms.grant(online, Perms.yanhuoNode(id), true);
    }

    /** 在线玩家（按游戏名精确匹配）；不在线返回 null。 */
    private static Player onlineOf(String playerName) {
        try {
            return playerName == null ? null : Bukkit.getPlayerExact(playerName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 丹方权限
    // ==================================================================

    private static File danfangFile(String playerName) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_DANFANG), PlayerKeys.key(playerName) + ".json");
    }

    /** 玩家已掌握的丹方 id（可能为空）。 */
    public static List<String> ownedDanfang(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(danfangFile(playerName));
        List<String> out = new ArrayList<>();
        if (data == null) return out;
        for (Object o : Json.arr(data, "owned")) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** 是否已学会某种丹方（存档为主、权限为辅，口径同 {@link #hasYanhuo}）。 */
    public static boolean hasDanfang(String playerName, String id) {
        if (id == null) return false;
        for (String owned : ownedDanfang(playerName)) {
            if (id.equalsIgnoreCase(owned)) return true;
        }
        Player online = onlineOf(playerName);
        return online != null && Perms.has(online, Perms.danfangNode(id));
    }

    /** 记录一个已掌握的丹方（保留 {@code exp} 字段不动）。 */
    public static void addDanfang(String playerName, String id) {
        if (id == null) return;
        Map<String, Object> old = AddonPaths.readJson(danfangFile(playerName));
        List<String> owned = new ArrayList<>();
        if (old != null) {
            for (Object o : Json.arr(old, "owned")) {
                if (o != null) owned.add(String.valueOf(o));
            }
        }
        boolean found = false;
        for (String s : owned) {
            if (id.equalsIgnoreCase(s)) {
                found = true;
                break;
            }
        }
        if (!found) owned.add(id);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owned", owned);
        if (old != null && old.get("exp") != null) data.put("exp", old.get("exp"));
        AddonPaths.writeJson(danfangFile(playerName), data);
        Player online = onlineOf(playerName);
        if (online != null) Perms.grant(online, Perms.danfangNode(id), true);
    }

    // ==================================================================
    // 问天玉碟
    // ==================================================================

    /** 读玉碟归属的玩家 UUID；未绑定返回 null。 */
    public static String cardOwner(ItemStack item) {
        try {
            if (item == null || CARD_OWNER_KEY == null) return null;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            return meta.getPersistentDataContainer().get(CARD_OWNER_KEY, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在背包里找属于该玩家的已绑定玉碟；没有返回 null。 */
    public static ItemStack findBoundCard(Inventory inv, UUID uuid) {
        if (inv == null || uuid == null) return null;
        String want = uuid.toString();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = com.tangran.tangrantech.TT.sfIdOf(stack);
            if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
            String owner = cardOwner(stack);
            if (owner != null && owner.equals(want)) return stack;
        }
        return null;
    }

    /** 刷新一块玉碟的器主 / 道源余额 / 功德余额三行 lore。 */
    public static void updateCardLore(ItemStack item, String playerName, String daoyuanExact) {
        try {
            if (item == null) return;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            while (lore.size() < 5) lore.add(legacy("§7"));

            lore.set(LORE_OWNER_LINE, legacy("§f[§e器主§f]§b " + playerName));
            lore.set(LORE_DAOYUAN_LINE, legacy("§f[§e道源余额§f]§b " + daoyuanExact + "道源"));
            lore.set(LORE_GONGDE_LINE,
                    legacy("§f[§e功德余额§f]§6 " + jsNum(gongde(playerName)) + "功德"));
            meta.lore(lore);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // lore 刷新失败不影响余额
        }
    }

    /** 把背包里所有属于该玩家的玉碟 lore 刷成最新余额。 */
    public static void syncAllCards(Inventory inv, UUID uuid, String playerName, String daoyuanExact) {
        if (inv == null || uuid == null) return;
        String want = uuid.toString();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = com.tangran.tangrantech.TT.sfIdOf(stack);
            if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
            String owner = cardOwner(stack);
            if (owner != null && owner.equals(want)) updateCardLore(stack, playerName, daoyuanExact);
        }
    }

    /** 玉碟的显示名（广播用）；取不到时回落成 id。 */
    public static String displayNameOf(ItemStack stack, String fallback) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String s = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
            // 落到 fallback
        }
        return fallback;
    }

    /** 小写化的 id 比较（异火/丹方 id 在配置里大小写混用）。 */
    public static boolean sameId(String a, String b) {
        if (a == null || b == null) return false;
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
