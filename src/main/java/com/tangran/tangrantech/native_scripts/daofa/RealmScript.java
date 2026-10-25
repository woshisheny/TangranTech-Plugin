package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossChallengeState;

/**
 * 「道法/境界」的原生实现（对应 {@code scripts/道法/境界.js}）。境界 + 糖之力 + 丹药属性系统。
 *
 * <p><b>洗髓丹</b> {@code tr_xsd} 右键开辟境界（一次性）。<b>丹药</b> {@code tr_dy01~tr_dy09}
 * 右键按 lore 里的「品质：X」给对应属性加进度（凡品 2 / ？？ 4 / 珍品 8 / 绝品 16 / 无暇 32）。
 * 每次服用同时按品质回复糖之力（5% / 10% / 15% / 20% / 25%）。</p>
 *
 * <p><b>境界</b>共 6 级（糖丸 → 糖师 → 糖灵 → 糖圣 → 糖尊 → 糖帝），
 * 突破条件是九项属性全部达到当前境界的阈值（100/300/700/1200/1800/2500）。</p>
 *
 * <p><b>糖之力</b>上限 = {@code 100 + Tr_dy01 进度}；每 27 刻（约 1.33 秒）按境界百分比自动回复；
 * 死亡回满；可开关 actionBar 显示。</p>
 *
 * <p><b>属性加成</b>由 {@link #applyAttributeEffects} 通过 {@link AttributeModifier} 施加。
 * 关键取舍（原脚本踩过坑）：清旧修饰符时<b>只保留 {@code tr_xu_} 前缀的</b>（那是天实商店买的增益），
 * 其余一律清掉。原脚本注释记录过：早先写成「只清 {@code tr_attr_} 前缀」，结果因为 key/name
 * 格式对不上而一个都没清掉，导致糖帝属性再也压不下去。</p>
 *
 * <p><b>跨脚本接口</b>（原 JS 版挂在 {@code globalThis} / 插件实例上，本类改为 public static）：</p>
 * <ul>
 *   <li>{@link #consumeSugarPower} / {@link #getSugarPower} / {@link #getMaxSugarPower}
 *       —— 供丹药、异火、BOSS战消费糖之力；</li>
 *   <li>{@link #forceOff} —— 供 BOSS战 在挑战开场立刻压一次糖帝属性，
 *       不必等这里 27 刻一轮的循环；</li>
 *   <li>{@link #challengeLock} —— 挑战期间「硬关」糖帝属性：直接把存档里的开关全置 false
 *       并备份到 {@code jjChSaved}，挑战结束原样恢复。这样即使 metadata 锁掉了、
 *       或有别的路径重算属性，也加不回来。</li>
 * </ul>
 *
 * <p>与 1.21 的适配：{@code Attribute} 在 1.21 已由枚举改为注册表接口，
 * 因此属性统一用常量引用而非 {@code valueOf}；{@code PLAYER_SUBMERGED_MINING_SPEED}
 * 已更名为 {@link Attribute#SUBMERGED_MINING_SPEED}；
 * {@code AttributeModifier} 改用 {@link NamespacedKey} 构造（UUID 构造器已废弃）。</p>
 */
public final class RealmScript implements NativeLifecycleScript {

    /** 洗髓丹 id（小写比较）。 */
    private static final String REALM_PILL_ID = "tr_xsd";
    /** 丹药 id 前缀（小写比较）。 */
    private static final String PILL_PREFIX = "tr_dy";

    private static final String[] REALM_NAMES = {"糖丸", "糖师", "糖灵", "糖圣", "糖尊", "糖帝"};
    private static final int[] REALM_THRESHOLDS = {100, 300, 700, 1200, 1800, 2500};

    /** 品质 → 属性进度点。 */
    private static final Map<String, Integer> QUALITY_POINTS = Map.of(
            "凡品", 2, "？？", 4, "珍品", 8, "绝品", 16, "无暇", 32);
    /** 品质 → 回糖之力百分比。 */
    private static final Map<String, Integer> QUALITY_HEAL_PERCENT = Map.of(
            "凡品", 5, "？？", 10, "珍品", 15, "绝品", 20, "无暇", 25);

    /** 九项丹药属性。 */
    private record Attr(String pill, String name, String icon, String desc) {}

    private static final Attr[] ATTRS = {
            new Attr("Tr_dy01", "糖之力突破", "GLOWSTONE_DUST", "增加最大糖之力上限"),
            new Attr("Tr_dy02", "灵露润体", "SUGAR", "提升护甲值"),
            new Attr("Tr_dy03", "焚天烈力", "BLAZE_POWDER", "提升近战攻击力"),
            new Attr("Tr_dy04", "寒晶坚壁", "SNOWBALL", "提升护甲韧性"),
            new Attr("Tr_dy05", "雷亟疾影", "AMETHYST_SHARD", "提升移动速度"),
            new Attr("Tr_dy06", "金髓连击", "GOLD_NUGGET", "提升攻击速度"),
            new Attr("Tr_dy07", "青木生机", "BROWN_MUSHROOM", "提升最大生命值"),
            new Attr("Tr_dy08", "玄水潜息", "PRISMARINE_CRYSTALS", "提升水下挖掘速度"),
            new Attr("Tr_dy09", "厚土镇岳", "CLAY_BALL", "提升击退抗性"),
    };

    /** 属性加成在 lore 里显示的单位名。 */
    private static final Map<String, String> ATTR_BONUS_UNITS = Map.of(
            "Tr_dy01", "最大糖之力上限",
            "Tr_dy02", "护甲值",
            "Tr_dy03", "近战攻击力",
            "Tr_dy04", "护甲韧性",
            "Tr_dy05", "移动速度",
            "Tr_dy06", "攻击速度",
            "Tr_dy07", "最大生命值",
            "Tr_dy08", "水下挖掘速度",
            "Tr_dy09", "击退抗性");

    private static final int[] ATTR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20};
    private static final int ENABLED_SLOT = 8;
    private static final int[] ENABLED_ATTR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19};
    private static final int SUGAR_BAR_SLOT = 20;
    private static final int BREAK_SLOT = 4;
    private static final int HEAD_SLOT = 0;
    private static final int CLOSE_SLOT = 49;
    private static final int MENU_SIZE = 54;

    /** 糖之力自动回复周期（刻）。 */
    private static final long SUGAR_PERIOD = 27L;
    /** 移动速度属性每点进度的换算系数。 */
    private static final double SPEED_PER_POINT = 0.01;

    /** 境界菜单 / 属性开关菜单。 */
    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();
    /** 菜单 Inventory → 类型（{@code jj} 境界查看 / {@code jj_attr} 属性开关）。 */
    private static final Map<Inventory, String> MENU_TYPE = new ConcurrentHashMap<>();
    /** 已被挑战强制关闭属性的玩家（用于挑战结束后判断是否需要重算一次）。 */
    private static final Set<String> PHASE2_FORCED = ConcurrentHashMap.newKeySet();

    private static volatile BukkitTask sugarTask;

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        // 洗髓丹 / 丹药的右键由 RealmListener.onInteract 统一处理（开辟 / 服用 + 消耗），
                        // 此处必须让位：yml 给 Tr_xsd 绑了本脚本，ScriptItemListener 会派发 onUse，
                        // 若这里也开菜单就会与 RealmListener 双触发（先提示「已开辟过」又弹界面）。
                        // 丹药（Tr_dy*）没有 script 绑定，只走 RealmListener 一条路。
                        ItemStack held = event.getItem() != null
                                ? event.getItem()
                                : event.getPlayer().getInventory().getItemInMainHand();
                        String heldId = TT.sfIdOf(held);
                        if (heldId != null
                                && (REALM_PILL_ID.equalsIgnoreCase(heldId)
                                    || heldId.toLowerCase(Locale.ROOT).startsWith(PILL_PREFIX))) {
                            return Boolean.FALSE;
                        }
                        openRealmMenu(event.getPlayer());
                    }
                    return Boolean.FALSE;
                }
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        openRealmMenu(player);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("境界 invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    private static File realmFile(Player player) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_REALM), player.getName() + ".json");
    }

    /** 读境界存档；不存在返回 null（表示未开辟）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Player player) {
        Map<String, Object> data = AddonPaths.readJson(realmFile(player));
        if (data == null) return null;
        if (!(data.get("enabledAttrs") instanceof Map)) {
            Map<String, Object> enabled = new LinkedHashMap<>();
            for (Attr a : ATTRS) {
                if (!"Tr_dy01".equals(a.pill())) enabled.put(a.pill(), Boolean.TRUE);
            }
            data.put("enabledAttrs", enabled);
        }
        data.putIfAbsent("showSugarBar", Boolean.TRUE);
        return data;
    }

    private static void save(Player player, Map<String, Object> data) {
        AddonPaths.writeJson(realmFile(player), data);
    }

    /**
     * 境界存档的<b>单点读-改-写入口</b>（供其他脚本安全修改境界数据）。
     *
     * <p>背景：{@code DivineCrystalScript}（神晶获取）早年自带一份
     * {@code realmFile/loadRealm/saveRealm}，与本类拼出同一路径
     * （{@code addon_configs/糖然科技/境界/<玩家>.json}）各写各的 —— 两条独立的
     * 「读-改-写」路径对着同一个文件，任一改成异步、或在读写之间让出主线程，
     * 就会发生静默丢失更新（一方的扣除被另一方持有的旧副本盖回）。
     * 现将文件访问统一收口到本类，调用方只描述「怎么改」。</p>
     *
     * <p>未开辟境界（或读档异常）时返回 false 且不写文件；{@code edit} 回调里抛出的
     * 异常同样被捕获、不落盘 —— 保证不会写出改了一半的存档。</p>
     *
     * @param player 目标玩家
     * @param edit   对存档 Map 的修改，在 load 之后、save 之前同步执行
     * @return 是否完成了 load → edit → save 全流程
     */
    public static boolean editRealm(Player player, java.util.function.Consumer<Map<String, Object>> edit) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return false;
            edit.accept(data);
            save(player, data);
            return true;
        } catch (Throwable t) {
            TT.log("境界 editRealm 异常 " + player.getName() + ": " + t);
            return false;
        }
    }

    /** 全新存档（开辟境界）。 */
    private static Map<String, Object> newData() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Map<String, Object> enabled = new LinkedHashMap<>();
        for (Attr a : ATTRS) {
            attrs.put(a.pill(), 0);
            if (!"Tr_dy01".equals(a.pill())) enabled.put(a.pill(), Boolean.TRUE);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("opened", Boolean.TRUE);
        data.put("realm", 0);
        data.put("sugarPower", 100);
        data.put("maxSugarPower", 100);
        data.put("attrs", attrs);
        data.put("enabledAttrs", enabled);
        data.put("showSugarBar", Boolean.TRUE);
        return data;
    }

    // ------------------------------------------------------------------
    // 数值读取
    // ------------------------------------------------------------------

    private static int realmOf(Map<String, Object> data) {
        return Math.max(0, Json.i(data, "realm", 0));
    }

    private static String realmName(Map<String, Object> data) {
        int r = realmOf(data);
        return (r >= 0 && r < REALM_NAMES.length) ? REALM_NAMES[r] : REALM_NAMES[0];
    }

    private static int realmThreshold(Map<String, Object> data) {
        int r = realmOf(data);
        return (r >= 0 && r < REALM_THRESHOLDS.length) ? REALM_THRESHOLDS[r] : REALM_THRESHOLDS[0];
    }

    private static int attrProgress(Map<String, Object> data, String pill) {
        return Json.i(Json.obj(data, "attrs"), pill, 0);
    }

    private static boolean isAttrFull(Map<String, Object> data, String pill) {
        return attrProgress(data, pill) >= realmThreshold(data);
    }

    private static boolean allAttrsFull(Map<String, Object> data) {
        for (Attr a : ATTRS) {
            if (!isAttrFull(data, a.pill())) return false;
        }
        return true;
    }

    /** 糖之力上限 = 100 + Tr_dy01 进度。 */
    private static double maxSugar(Map<String, Object> data) {
        return 100.0 + attrProgress(data, "Tr_dy01");
    }

    private static double sugarPower(Map<String, Object> data) {
        return Json.dbl(data, "sugarPower", 0);
    }

    /** 按 JS 的 {@code String(number)} 语义格式化（整数不带小数点）。 */
    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------
    // 糖之力（含跨脚本接口）
    // ------------------------------------------------------------------

    /** 动作栏去重（玩家 UUID → 上次展示的文本）：糖之力每轮回 1，只有数值变化才值得发包。 */
    private static final Map<java.util.UUID, String> LAST_SUGAR_BAR = new java.util.concurrent.ConcurrentHashMap<>();

    private static void sendSugarBar(Player player, Map<String, Object> data) {
        try {
            if (Boolean.FALSE.equals(data.get("showSugarBar"))) {
                LAST_SUGAR_BAR.remove(player.getUniqueId());
                return;
            }
            double max = maxSugar(data);
            double cur = Math.min(sugarPower(data), max);
            String text = "§b§l糖之力§f§l: §e§l" + num(cur) + "§f§l/§d§l" + num(max);
            // ★ 数值有变化才展示：addSugarPower 每轮回 1，满值时数值不变——
            //   原实现每周期无条件发包，动作栏被糖之力常驻占据
            if (text.equals(LAST_SUGAR_BAR.put(player.getUniqueId(), text))) return;
            player.sendActionBar(text);
        } catch (Throwable ignored) {
            // actionBar 失败忽略
        }
    }

    /**
     * 消耗糖之力。
     *
     * <p>未开辟境界时直接返回 true（不消耗），避免影响旧功能 —— 与原脚本一致。</p>
     */
    public static boolean consumeSugarPower(Player player, double amount) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return true;
            if (sugarPower(data) < amount) return false;
            data.put("sugarPower", sugarPower(data) - amount);
            save(player, data);
            sendSugarBar(player, data);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static double getSugarPower(Player player) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return 0;
            return sugarPower(data);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static double getMaxSugarPower(Player player) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return 0;
            return maxSugar(data);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 该玩家是否已开辟境界（供异火等脚本判断能否消费糖之力）。 */
    public static boolean hasRealm(Player player) {
        try {
            Map<String, Object> data = load(player);
            return data != null && Json.bool(data, "opened", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addSugarPower(Player player, double amount) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return;
            double max = maxSugar(data);
            data.put("sugarPower", Math.min(max, sugarPower(data) + amount));
            save(player, data);
            sendSugarBar(player, data);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 属性加成
    // ------------------------------------------------------------------

    /** 雷亟疾影（Tr_dy05）速度上限随境界递增：0.06 / 0.12 / 0.18 / 0.24 / 0.30 / 0.36。 */
    private static double speedCapByRealm(int realm) {
        int r = Math.max(0, Math.min(5, realm));
        return 0.06 + 0.06 * r;
    }

    /** 与 {@link #applyAttributeEffects} 共用同一套换算，保证「显示值 = 实际加成值」。 */
    private static double attrBonusValue(String pill, int progress, int realm) {
        double amount = progress;
        if ("tr_dy05".equalsIgnoreCase(pill)) {
            amount = Math.min(amount * SPEED_PER_POINT, speedCapByRealm(realm));
        }
        return amount;
    }

    private static String formatBonus(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-7) return String.valueOf(Math.round(v));
        // 保留 3 位再抹掉末尾 0，等价 JS 的 Number(v.toFixed(3))
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 每项属性对应的 Bukkit Attribute；无法映射的返回 null。 */
    private static Attribute bukkitAttr(String pill) {
        return switch (pill) {
            case "Tr_dy02" -> Attribute.ARMOR;
            case "Tr_dy03" -> Attribute.ATTACK_DAMAGE;
            case "Tr_dy04" -> Attribute.ARMOR_TOUGHNESS;
            case "Tr_dy05" -> Attribute.MOVEMENT_SPEED;
            case "Tr_dy06" -> Attribute.ATTACK_SPEED;
            case "Tr_dy07" -> Attribute.MAX_HEALTH;
            // 1.21 起 PLAYER_SUBMERGED_MINING_SPEED 更名为 SUBMERGED_MINING_SPEED
            case "Tr_dy08" -> Attribute.SUBMERGED_MINING_SPEED;
            case "Tr_dy09" -> Attribute.KNOCKBACK_RESISTANCE;
            default -> null;
        };
    }

    /** 重算玩家全部丹药属性加成。 */
    private static void applyAttributeEffects(Player player, Map<String, Object> data) {
        try {
            boolean locked = BossChallengeState.isPhaseTwoLocked(player);
            Map<String, Object> enabledAttrs = Json.obj(data, "enabledAttrs");
            int realm = realmOf(data);

            for (Attr a : ATTRS) {
                Attribute attrEnum = bukkitAttr(a.pill());
                if (attrEnum == null) continue;

                AttributeInstance attr;
                try {
                    attr = player.getAttribute(attrEnum);
                } catch (Throwable ignored) {
                    continue;
                }
                if (attr == null) continue;

                // 清除旧修饰符：只保留天实商店买的增益（tr_xu_），其余照旧清掉。
                // 原脚本注释记录过：写成「只清 tr_attr_ 前缀」会因为 key/name 格式对不上
                // 而一个都不清 → 糖帝属性再也压不下去。
                List<AttributeModifier> toRemove = new ArrayList<>();
                try {
                    for (AttributeModifier m : new ArrayList<>(attr.getModifiers())) {
                        String mn = "";
                        try {
                            if (m.getKey() != null) mn = String.valueOf(m.getKey());
                        } catch (Throwable ignored) {
                            // 落到 getName
                        }
                        if (mn.isEmpty()) {
                            try {
                                mn = String.valueOf(m.getName());
                            } catch (Throwable ignored) {
                                // 保持空
                            }
                        }
                        if (mn.contains("tr_xu_")) continue;
                        toRemove.add(m);
                    }
                } catch (Throwable ignored) {
                    // 取不到修饰符列表就跳过清理
                }
                for (AttributeModifier m : toRemove) {
                    try {
                        attr.removeModifier(m);
                    } catch (Throwable ignored) {
                        // 已移除
                    }
                }

                int progress = attrProgress(data, a.pill());
                // 挑战期间一律按「关闭」处理；否则看玩家自己的开关（缺省为开）
                boolean enabled = !locked
                        && !Boolean.FALSE.equals(enabledAttrs.get(a.pill()));
                if (progress <= 0 || !enabled) continue;

                double amount = attrBonusValue(a.pill(), progress, realm);
                try {
                    NamespacedKey key = new NamespacedKey(TT.plugin,
                            "tr_attr_" + a.pill().toLowerCase(Locale.ROOT));
                    attr.addModifier(new AttributeModifier(key, amount,
                            AttributeModifier.Operation.ADD_NUMBER));
                } catch (Throwable t) {
                    TT.log("境界属性修饰符添加失败 " + a.pill() + ": " + t);
                }
            }
        } catch (Throwable ignored) {
            // 属性重算绝不影响主流程
        }
    }

    // ------------------------------------------------------------------
    // 丹药
    // ------------------------------------------------------------------

    /** 按品质回复糖之力。 */
    private static void healSugarByQuality(Player player, String quality) {
        Map<String, Object> data = load(player);
        if (data == null || !Json.bool(data, "opened", false)) return;
        int pct = QUALITY_HEAL_PERCENT.getOrDefault(quality, 5);
        addSugarPower(player, Math.ceil(maxSugar(data) * pct / 100.0));
    }

    /** 服用一枚丹药：加属性进度。 */
    private static boolean addPillProgress(Player player, String pill, String quality) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) {
                player.sendMessage("§c请先使用洗髓丹开辟境界！");
                return false;
            }

            Attr attr = null;
            for (Attr a : ATTRS) {
                if (a.pill().equalsIgnoreCase(pill)) {
                    attr = a;
                    break;
                }
            }
            if (attr == null) return false;

            int points = QUALITY_POINTS.getOrDefault(quality, 2);
            boolean isSugar = "tr_dy01".equalsIgnoreCase(attr.pill());

            if (isAttrFull(data, attr.pill())) {
                if (isSugar) {
                    healSugarByQuality(player, quality);
                    player.sendMessage("§a回复了糖之力！");
                    return true;
                }
                player.sendMessage("§c" + attr.name() + " 当前进度已圆满，无法继续服用该丹药！");
                return false;
            }

            Map<String, Object> attrs = Json.obj(data, "attrs");
            int now = attrProgress(data, attr.pill()) + points;
            attrs.put(attr.pill(), now);
            data.put("attrs", attrs);
            if (isSugar) data.put("maxSugarPower", maxSugar(data));
            save(player, data);
            applyAttributeEffects(player, data);
            if (isSugar) healSugarByQuality(player, quality);

            player.sendMessage("§a" + attr.name() + " 进度 +" + points
                    + "（当前 " + now + "/" + realmThreshold(data) + "）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从 lore 里读「品质：X」；读不到返回 null。 */
    private static String pillQuality(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            List<Component> lore = meta.lore();
            if (lore == null) return null;
            for (Component line : lore) {
                String text = LegacyComponentSerializer.legacySection().serialize(line);
                int idx = text.indexOf("品质：");
                if (idx >= 0) return text.substring(idx + 3).trim();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 开辟境界
    // ------------------------------------------------------------------

    /** 洗髓丹：开辟境界；已开辟返回 false。 */
    private static boolean openRealm(Player player) {
        Map<String, Object> data = load(player);
        if (data != null) {
            player.sendMessage("§c您已经开辟过境界，无法再次使用洗髓丹！");
            return false;
        }
        Map<String, Object> fresh = newData();
        save(player, fresh);
        try {
            player.playSound(player.getLocation(), "block.beacon.activate", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 忽略
        }
        String msg = "§e" + player.getName() + " §a已开辟胰岛";
        for (Player online : Bukkit.getOnlinePlayers()) {
            try {
                online.sendMessage(msg);
            } catch (Throwable ignored) {
                // 单个玩家发送失败跳过
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private static ItemStack glass(String name, String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack named(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(legacy(line));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack attrIcon(Attr attr, List<String> loreLines) {
        Material mat = Material.matchMaterial(attr.icon());
        if (mat == null) mat = Material.STONE;
        return named(mat, "§b" + attr.name(), loreLines);
    }

    /** 境界查看界面。 */
    private static Inventory buildRealmMenu(Player player) {
        Map<String, Object> data = load(player);
        String realm = (data == null) ? "未开辟" : realmName(data);

        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy("§d境界查看"));
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta hm = head.getItemMeta();
        if (hm instanceof SkullMeta skull) {
            try {
                skull.setOwningPlayer(player);
            } catch (Throwable ignored) {
                // 皮肤设置失败不影响其余
            }
        }
        if (hm != null) {
            hm.displayName(legacy("§e当前境界:" + realm));
            head.setItemMeta(hm);
        }
        inv.setItem(HEAD_SLOT, head);

        inv.setItem(BREAK_SLOT, glass("§a§l开始突破", "LIME_STAINED_GLASS_PANE"));
        inv.setItem(ENABLED_SLOT, glass("§b§l属性开关", "COMPARATOR"));
        inv.setItem(CLOSE_SLOT, glass("§c关闭", "BARRIER"));

        if (data != null) {
            int threshold = realmThreshold(data);
            for (int i = 0; i < ATTRS.length; i++) {
                Attr attr = ATTRS[i];
                int prog = attrProgress(data, attr.pill());

                List<String> lore = new ArrayList<>();
                lore.add(prog >= threshold ? "§7当前进度:§a圆满" : "§7当前进度:§f" + prog + "/" + threshold);
                lore.add("§7特殊属性:§f" + attr.desc());

                double bonus = attrBonusValue(attr.pill(), prog, realmOf(data));
                String unit = ATTR_BONUS_UNITS.getOrDefault(attr.pill(), "");
                lore.add("§7属性加成:§e+" + formatBonus(bonus)
                        + (unit.isEmpty() ? "" : " §f" + unit));

                inv.setItem(ATTR_SLOTS[i], attrIcon(attr, lore));
            }
        }
        return inv;
    }

    /** 属性开关界面。 */
    private static Inventory buildAttrToggleMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy("§b属性开关"));
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }

        Map<String, Object> data = load(player);
        boolean forcedOff = BossChallengeState.isPhaseTwoLocked(player);
        Map<String, Object> enabledAttrs = (data == null) ? null : Json.obj(data, "enabledAttrs");

        for (int i = 0; i < 8; i++) {
            // 跳过糖之力突破（ATTRS[0]）
            Attr attr = ATTRS[i + 1];
            boolean enabled = !forcedOff
                    && (enabledAttrs == null || !Boolean.FALSE.equals(enabledAttrs.get(attr.pill())));

            List<String> lore = new ArrayList<>();
            lore.add(enabled ? "§7当前:§a已启用" : "§7当前:§c已禁用");
            lore.add(forcedOff ? "§c挑战期间强制关闭，无法修改" : "§e点击切换");

            inv.setItem(ENABLED_ATTR_SLOTS[i], attrIcon(attr, lore));
        }

        boolean sugarOn = (data == null) || !Boolean.FALSE.equals(data.get("showSugarBar"));
        inv.setItem(SUGAR_BAR_SLOT, named(Material.GLOWSTONE_DUST, "§b糖之力显示", List.of(
                sugarOn ? "§7当前:§a已启用" : "§7当前:§c已禁用",
                "§e点击切换")));
        inv.setItem(CLOSE_SLOT, glass("§e返回", "BARRIER"));
        return inv;
    }

    /** 打开境界查看界面。 */
    private static void openRealmMenu(Player player) {
        try {
            if (BossChallengeState.isPhaseTwoLocked(player)) {
                player.sendMessage("§c糖然挑战期间，无法打开境界加成界面！");
                return;
            }
            Map<String, Object> data = load(player);
            if (data != null && Json.bool(data, "opened", false)) {
                applyAttributeEffects(player, data);
            }
            Inventory inv = buildRealmMenu(player);
            ACTIVE.add(inv);
            MENU_TYPE.put(inv, "jj");
            player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.5f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("境界菜单打开失败: " + t);
        }
    }

    /** 打开属性开关界面。 */
    private static void openAttrToggleMenu(Player player) {
        try {
            if (BossChallengeState.isPhaseTwoLocked(player)) {
                player.sendMessage("§c糖然挑战期间，无法打开属性开关界面！");
                return;
            }
            Inventory inv = buildAttrToggleMenu(player);
            ACTIVE.add(inv);
            MENU_TYPE.put(inv, "jj_attr");
            player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.5f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("属性开关菜单打开失败: " + t);
        }
    }

    /** 原地把新界面的内容覆盖到旧界面上。 */
    private static void refresh(Inventory target, Inventory fresh) {
        for (int i = 0; i < MENU_SIZE; i++) {
            target.setItem(i, fresh.getItem(i));
        }
    }

    /** 突破境界。 */
    private static void tryBreakthrough(Player player) {
        try {
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return;

            if (!allAttrsFull(data)) {
                player.sendMessage("§c还有属性未圆满，无法突破！");
                return;
            }
            int realm = realmOf(data);
            if (realm >= REALM_NAMES.length - 1) {
                player.sendMessage("§c已达到最高境界糖帝！");
                return;
            }
            data.put("realm", realm + 1);
            save(player, data);
            applyAttributeEffects(player, data);
            player.sendMessage("§a突破成功！当前境界:§e" + realmName(data));
            player.playSound(player.getLocation(), "block.beacon.activate", 1.0f, 1.2f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 糖之力自动回复
    // ------------------------------------------------------------------

    private static void startSugarTask() {
        if (sugarTask != null) return;
        sugarTask = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), RealmScript::sugarTick, 0L, SUGAR_PERIOD);
    }

    private static void sugarTick() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Map<String, Object> data = load(p);
                if (data == null || !Json.bool(data, "opened", false)) continue;

                // 异常残留自愈：不在挑战里却还挂着挑战锁 → 还原玩家自己的开关
                healChallengeLock(p);

                String name = p.getName();
                if (BossChallengeState.isPhaseTwoLocked(p)) {
                    // 挑战期间每轮重压一遍（幂等：先清本系统的修饰符、再加回 0 个），
                    // 避免「某个瞬间被重新加上 → 下一秒才压回去」的回归闪烁
                    PHASE2_FORCED.add(name);
                    applyAttributeEffects(p, data);
                } else if (PHASE2_FORCED.remove(name)) {
                    applyAttributeEffects(p, data);
                }

                double max = maxSugar(data);
                double regen = 1;
                int realm = realmOf(data);
                if (realm >= 1) {
                    int pct = (realm <= 5) ? new int[]{0, 1, 2, 3, 4, 5}[realm] : 1;
                    if (pct == 0) pct = 1;
                    regen = Math.max(1, Math.round(max * pct / 100.0));
                }
                addSugarPower(p, regen);
            }
        } catch (Throwable ignored) {
            // 回复循环绝不抛异常
        }
    }

    // ------------------------------------------------------------------
    // 跨脚本接口（供 BOSS战 / 丹药 / 异火调用）
    // ------------------------------------------------------------------

    /**
     * 挑战开场立刻压一次糖帝属性，不用等 27 刻一轮的循环。
     *
     * <p>已在强制关闭名单（{@code PHASE2_FORCED}）里的玩家直接返回：本方法被
     * 二阶段 lockWatcher 每 0.5 秒调一次，每次 {@code load()} 都是一次同步 JSON
     * 文件读——原写法整场挑战持续 2 次读/秒。名单内的玩家由 {@link #sugarTick}
     * 每轮重压兜底，短路不丢失任何行为（挑战开场的前一次压制由
     * {@code challengeLock(on=true)} 完成，它本就调用过 applyAttributeEffects）。</p>
     */
    public static boolean forceOff(Player player) {
        try {
            if (player == null) return false;
            if (PHASE2_FORCED.contains(player.getName())) return true;
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return false;
            PHASE2_FORCED.add(player.getName());
            applyAttributeEffects(player, data);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 挑战期间「硬关」糖帝属性，或挑战结束恢复。
     *
     * <p>开启时把 {@code enabledAttrs} 全部置 false 并备份到 {@code jjChSaved}；
     * 关闭时把备份原样写回。这样即使 metadata 锁掉了、或有别的路径重算属性，也加不回来。</p>
     */
    public static boolean challengeLock(Player player, boolean on) {
        try {
            if (player == null) return false;
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return false;
            String name = player.getName();

            if (on) {
                if (!(data.get("jjChSaved") instanceof Map)) {
                    Map<String, Object> saved = new LinkedHashMap<>();
                    saved.put("enabledAttrs", new LinkedHashMap<>(Json.obj(data, "enabledAttrs")));
                    saved.put("at", System.currentTimeMillis());
                    data.put("jjChSaved", saved);
                }
                Map<String, Object> allOff = new LinkedHashMap<>();
                for (Attr a : ATTRS) allOff.put(a.pill(), Boolean.FALSE);
                data.put("enabledAttrs", allOff);
                save(player, data);
                PHASE2_FORCED.add(name);
                applyAttributeEffects(player, data);
                return true;
            }

            if (data.get("jjChSaved") instanceof Map<?, ?> saved) {
                Map<String, Object> back = new LinkedHashMap<>();
                if (saved.get("enabledAttrs") instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        back.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                data.put("enabledAttrs", back);
                data.remove("jjChSaved");
                save(player, data);
                PHASE2_FORCED.remove(name);
                applyAttributeEffects(player, data);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 自己巡检：不在挑战里却还留着 {@code jjChSaved}（重启等异常残留）→ 还原玩家原本的开关。 */
    private static void healChallengeLock(Player player) {
        try {
            if (player == null) return;
            Map<String, Object> data = load(player);
            if (data == null || !Json.bool(data, "opened", false)) return;
            if (!(data.get("jjChSaved") instanceof Map)) return;
            if (BossChallengeState.isPhaseTwoLocked(player)) return;  // 还在挑战里就不还原
            challengeLock(player, false);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        // 模块加载即启动糖之力回复循环（与原脚本 startSugarTask() 等价）
        startSugarTask();
        return Collections.singletonList(new RealmListener());
    }

    /** 全部事件入口。 */
    private static final class RealmListener implements Listener {

        /** 右键：洗髓丹开辟境界 / 丹药服用。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

                Player player = event.getPlayer();
                ItemStack item = event.getItem() != null
                        ? event.getItem()
                        : player.getInventory().getItemInMainHand();
                if (item == null || item.getType().isAir()) return;

                SlimefunItem sf = SlimefunItem.getByItem(item);
                if (sf == null) return;
                String id = String.valueOf(sf.getId());
                if (id.isBlank()) return;

                if (REALM_PILL_ID.equalsIgnoreCase(id)) {
                    event.setCancelled(true);
                    if (openRealm(player)) {
                        consumeOne(player, item);
                    }
                    return;
                }

                if (id.toLowerCase(Locale.ROOT).startsWith(PILL_PREFIX)) {
                    String quality = pillQuality(item);
                    if (quality == null) return;
                    event.setCancelled(true);
                    if (addPillProgress(player, id, quality)) {
                        consumeOne(player, item);
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 死亡时糖之力回满。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(PlayerDeathEvent event) {
            try {
                Player p = event.getEntity();
                Map<String, Object> data = load(p);
                if (data == null || !Json.bool(data, "opened", false)) return;
                data.put("sugarPower", maxSugar(data));
                save(p, data);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 上线时重新附加属性效果。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            try {
                Player p = event.getPlayer();
                Map<String, Object> data = load(p);
                if (data != null && Json.bool(data, "opened", false)) {
                    applyAttributeEffects(p, data);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 菜单点击。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                event.setCancelled(true);
                if (!(event.getWhoClicked() instanceof Player player)) return;

                String type = MENU_TYPE.get(top);
                if (type == null) return;
                int slot = event.getRawSlot();

                if ("jj".equals(type)) {
                    if (slot == CLOSE_SLOT) {
                        player.closeInventory();
                        return;
                    }
                    if (slot == ENABLED_SLOT) {
                        if (BossChallengeState.isPhaseTwoLocked(player)) {
                            player.sendMessage("§c糖然二阶段挑战期间，无法修改丹药属性开关！");
                            return;
                        }
                        openAttrToggleMenu(player);
                        return;
                    }
                    if (slot == BREAK_SLOT) {
                        tryBreakthrough(player);
                        refresh(top, buildRealmMenu(player));
                    }
                    return;
                }

                if ("jj_attr".equals(type)) {
                    if (slot == CLOSE_SLOT) {
                        openRealmMenu(player);
                        return;
                    }
                    int idx = indexOf(ENABLED_ATTR_SLOTS, slot);
                    if (idx >= 0 && idx < 8) {
                        if (BossChallengeState.isPhaseTwoLocked(player)) {
                            player.sendMessage("§c糖然二阶段挑战期间，无法修改丹药属性开关！");
                            refresh(top, buildAttrToggleMenu(player));
                            return;
                        }
                        Attr attr = ATTRS[idx + 1];
                        Map<String, Object> data = load(player);
                        if (data == null) return;

                        Map<String, Object> enabledAttrs = Json.obj(data, "enabledAttrs");
                        // 原脚本是「取反当前值」，缺省（未记录）时视为 true → 取反后为 false。
                        // JS 语义：!undefined 与 !true 都会得到 false，!false 得到 true。
                        enabledAttrs.put(attr.pill(),
                                !Boolean.TRUE.equals(enabledAttrs.get(attr.pill())));
                        data.put("enabledAttrs", enabledAttrs);
                        save(player, data);
                        applyAttributeEffects(player, data);
                        player.sendMessage("§a" + attr.name()
                                + (Boolean.TRUE.equals(enabledAttrs.get(attr.pill()))
                                ? " §a已启用" : " §c已禁用"));
                        refresh(top, buildAttrToggleMenu(player));
                        return;
                    }
                    if (slot == SUGAR_BAR_SLOT) {
                        Map<String, Object> data = load(player);
                        if (data == null) return;
                        boolean currentlyOn = !Boolean.FALSE.equals(data.get("showSugarBar"));
                        data.put("showSugarBar", !currentlyOn);
                        save(player, data);
                        player.sendMessage("§a糖之力显示: "
                                + (currentlyOn ? "§c已关闭" : "§a已开启"));
                        refresh(top, buildAttrToggleMenu(player));
                    }
                }
            } catch (Throwable t) {
                TT.log("境界菜单点击处理出错: " + t);
            }
        }

        /** 关闭菜单时从活跃集合摘除。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory inv = event.getInventory();
                ACTIVE.remove(inv);
                MENU_TYPE.remove(inv);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static int indexOf(int[] arr, int v) {
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == v) return i;
            }
            return -1;
        }

        /** 消耗主手一个（与原脚本一致：数量减一，减到 0 就清空主手）。 */
        private static void consumeOne(Player player, ItemStack item) {
            try {
                int amount = item.getAmount() - 1;
                if (amount <= 0) {
                    player.getInventory().setItemInMainHand(null);
                } else {
                    item.setAmount(amount);
                    player.getInventory().setItemInMainHand(item);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        if (sugarTask != null) {
            try {
                sugarTask.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
            sugarTask = null;
        }
        ACTIVE.clear();
        MENU_TYPE.clear();
        PHASE2_FORCED.clear();
        LAST_SUGAR_BAR.clear();
    }
}
