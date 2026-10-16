package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 「道法/天道货币兑换」的原生实现（对应 {@code scripts/道法/天道货币兑换.js}）。
 * 「问天玉碟」{@code TR_WTYD} 的绑定与兑换界面。
 *
 * <p><b>绑定</b>：右键未绑定的玉碟即绑定到本人（在物品 PDC 的
 * {@code tangran:daoyuan_owner} 上写玩家 UUID）；一人一碟，已持有绑定碟时拒绝；
 * 已绑定给别人的碟拒绝使用。</p>
 *
 * <p><b>兑换</b>：界面最外圈是随机彩色玻璃（纯装饰），输入区 28 格放材料，
 * 点第 44 格的「确认兑换」把材料折算成<b>道源</b>与<b>功德</b>
 * （两张兑换表见 {@link #EXCHANGE_RATES} 与 {@link #GONGDE_RATES}），
 * 同时把背包里所有已绑定的玉碟 lore 刷成最新余额。关闭界面时输入区的材料原样还给玩家。</p>
 *
 * <p>存档：{@code addon_configs/糖然科技/天道货币/<玩家名>.json}，字段
 * {@code daoyuan} / {@code daoyuanExact} / {@code gongde}。沿用原脚本的相对路径，
 * 保证老档余额不失效。</p>
 *
 * <p>与原脚本的差异：原脚本的 {@code daoyuanExact} 与 {@code daoyuan} 双字段是为了绕开
 * JSON 数字精度（浮点累加后 {@code 0.1+0.2} 之类）。这里完整复刻了它的调和规则
 * {@link #getDaoyuan}，读取时以 {@code daoyuanExact} 为准、两者明显不一致时取较大值。</p>
 */
public final class HeavenCurrencyScript implements NativeLifecycleScript {

    /** 问天玉碟的物品 id。 */
    private static final String CARD_ID = "TR_WTYD";

    /** 兑换界面标题。 */
    private static final String GUI_TITLE = "§b天道货币兑换";

    /** 玉碟绑定玩家的 PDC 键（沿用原脚本的命名空间与键名）。 */
    private static final NamespacedKey CARD_OWNER_KEY =
            NamespacedKey.fromString("tangran:daoyuan_owner");

    /** 聊天前缀。 */
    private static final String DY_PREFIX = "§f[§x§F§F§D§7§0§0道§x§F§F§A§B§8§0源§f] ";

    // ---------------- GUI 布局 ----------------

    /** 输入槽（最外圈以内，共 28 格）。 */
    private static final int[] INPUT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };

    /** 确认按钮槽位。 */
    private static final int CONFIRM_SLOT = 44;
    /** 道源清单槽位。 */
    private static final int PURPLE_SLOT = 0;
    /** 功德清单槽位。 */
    private static final int GONGDE_INFO_SLOT = 8;

    /** 最外圈装饰槽位。 */
    private static final int[] OUTER_SLOTS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 45, 46, 47, 48, 49, 50, 51, 52, 53,
    };

    /** 外圈随机颜色。 */
    private static final String[] OUTER_COLORS = {
            "RED_STAINED_GLASS_PANE",
            "ORANGE_STAINED_GLASS_PANE",
            "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE",
            "GREEN_STAINED_GLASS_PANE",
            "CYAN_STAINED_GLASS_PANE",
            "LIGHT_BLUE_STAINED_GLASS_PANE",
            "BLUE_STAINED_GLASS_PANE",
            "PURPLE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE",
            "PINK_STAINED_GLASS_PANE",
    };

    // ---------------- 兑换表 ----------------

    /** 道源兑换表（物品 id → 单价）。 */
    private static final Map<String, Double> EXCHANGE_RATES = rates();

    /** 功德兑换表（16 种果实）。 */
    private static final Map<String, Double> GONGDE_RATES = gongdeRates();

    /** 物品 id → 显示名（兑换成功提示用）。 */
    private static final Map<String, String> ITEM_NAMES = names();

    private static Map<String, Double> rates() {
        Map<String, Double> m = new LinkedHashMap<>();
        // 0.5 道源档：九种元素
        for (String id : new String[]{
                "TR_FJ", "TR_SL", "TR_YL", "TR_JM", "TR_CH",
                "TR_YC", "TR_YY", "TR_TE", "TR_XS"}) {
            m.put(id, 0.5);
        }
        m.put("TR_JSLLZ", 5.0);
        // 0.05 道源档
        for (String id : new String[]{
                "TR_SUGAR", "TR_TJ", "TR_CJTZHX", "TR_TZ", "TR_BOTTLE",
                "TR_CTS", "TR_CJNLHX_BCP", "TR_CJNLHX", "TR_QLZPLQJ"}) {
            m.put(id, 0.05);
        }
        // 中间档
        m.put("TR_CJSMHHW", 1.0);
        m.put("TR_MGXX", 1.0);
        m.put("TR_ZMHQ", 1.0);
        m.put("TR_TXSJ", 5.0);
        m.put("TR_YHS", 0.1);
        m.put("TR_HQ", 0.1);
        m.put("TR_MW", 5.0);
        m.put("TR_HJX", 4.0);
        m.put("TR_ZWXYP", 4.0);
        m.put("TR_BXS", 0.2);
        m.put("TR_HQY", 0.2);
        // 浮屠神座：稀有 50 / 史诗 400 / 传说 2000
        for (int i = 1; i <= 8; i++) m.put(String.format("TR_FDSZ%02d", i), 50.0);
        for (int i = 9; i <= 15; i++) m.put(String.format("TR_FDSZ%02d", i), 400.0);
        for (int i = 16; i <= 20; i++) m.put(String.format("TR_FDSZ%02d", i), 2000.0);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Double> gongdeRates() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) m.put(String.format("TR_LZ%02d_FRUIT", i), 1.0);
        for (int i = 7; i <= 11; i++) m.put(String.format("TR_LZ%02d_FRUIT", i), 5.0);
        for (int i = 12; i <= 14; i++) m.put(String.format("TR_LZ%02d_FRUIT", i), 30.0);
        for (int i = 15; i <= 16; i++) m.put(String.format("TR_LZ%02d_FRUIT", i), 100.0);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> names() {
        Map<String, String> m = new LinkedHashMap<>();
        String[][] pairs = {
                {"TR_FJ", "金·锋寂"}, {"TR_SL", "木·森罗"}, {"TR_YL", "水·渊澜"},
                {"TR_JM", "火·烬灭"}, {"TR_CH", "土·尘寰"}, {"TR_YC", "光·曜辰"},
                {"TR_YY", "暗·幽湮"}, {"TR_TE", "雷·霆殛"}, {"TR_XS", "冰·雪殇"},
                {"TR_JSLLZ", "九色琉璃珠"},
                {"TR_LZ01_FRUIT", "灵蕴草穗"}, {"TR_LZ02_FRUIT", "凝露花"},
                {"TR_LZ03_FRUIT", "赤炎果"}, {"TR_LZ04_FRUIT", "寒霜参"},
                {"TR_LZ05_FRUIT", "紫电藤果"}, {"TR_LZ06_FRUIT", "金髓稻穗"},
                {"TR_LZ07_FRUIT", "青木芝"}, {"TR_LZ08_FRUIT", "玄水莲子"},
                {"TR_LZ09_FRUIT", "厚土根"}, {"TR_LZ10_FRUIT", "曜光葵"},
                {"TR_LZ11_FRUIT", "幽影菇"}, {"TR_LZ12_FRUIT", "雷音竹"},
                {"TR_LZ13_FRUIT", "冰心梅"}, {"TR_LZ14_FRUIT", "火灵椒"},
                {"TR_LZ15_FRUIT", "风语藤果"}, {"TR_LZ16_FRUIT", "星辰麦穗"},
                {"TR_SUGAR", "糖然牌基础物质"}, {"TR_TJ", "糖精"}, {"TR_CJTZHX", "初级糖之核心"},
                {"TR_TZ", "糖渣"}, {"TR_BOTTLE", "万用瓶"}, {"TR_CTS", "粗糖水"},
                {"TR_CJNLHX_BCP", "初级能量核心·半成品"}, {"TR_CJNLHX", "初级能量核心"},
                {"TR_QLZPLQJ", "俏了滋牌冷却剂"},
                {"TR_CJSMHHW", "初级神明化合物"}, {"TR_MGXX", "命轨星屑"}, {"TR_ZMHQ", "致密哈气"},
                {"TR_TXSJ", "太虚枢机"}, {"TR_YHS", "余恨砂"}, {"TR_HQ", "哈气"},
                {"TR_MW", "猫王"}, {"TR_HJX", "哈基仙"}, {"TR_ZWXYP", "造物匣原片"},
                {"TR_BXS", "碧息石"}, {"TR_HQY", "黄泉引"},
                {"TR_FDSZ01", "星纹铁"}, {"TR_FDSZ02", "月华砂"}, {"TR_FDSZ03", "灵犀玉"},
                {"TR_FDSZ04", "青冥石"}, {"TR_FDSZ05", "紫电晶"}, {"TR_FDSZ06", "玄冰魄"},
                {"TR_FDSZ07", "赤炎髓"}, {"TR_FDSZ08", "风灵羽"}, {"TR_FDSZ09", "天机丝"},
                {"TR_FDSZ10", "太一真水"}, {"TR_FDSZ11", "鸿蒙尘"}, {"TR_FDSZ12", "万象铜"},
                {"TR_FDSZ13", "涅槃火种"}, {"TR_FDSZ14", "九幽铁"}, {"TR_FDSZ15", "星辰钢"},
                {"TR_FDSZ16", "混沌源核"}, {"TR_FDSZ17", "造化玉碟碎片"}, {"TR_FDSZ18", "大道之种"},
                {"TR_FDSZ19", "永恒神木"}, {"TR_FDSZ20", "鸿蒙紫晶"},
        };
        for (String[] p : pairs) m.put(p[0], p[1]);
        return Collections.unmodifiableMap(m);
    }

    // ---------------- 界面物品 ----------------

    private static final ItemStack PURPLE_ITEM = buildInfoPaper(
            "§d§l▣ 兑换道源清单",
            new String[]{
                    "§e━━━ §f0.5道源/个 §e━━━",
                    "§f金·锋寂  木·森罗  水·渊澜",
                    "§f火·烬灭  土·尘寰  光·曜辰",
                    "§f暗·幽湮  雷·霆殛  冰·雪殇",
                    "§e━━━ §f0.05道源/个 §e━━━",
                    "§f糖然牌基础物质  糖精  初级糖之核心",
                    "§f糖渣  万用瓶  粗糖水",
                    "§f初级能量核心·半成品  初级能量核心",
                    "§f俏了滋牌冷却剂",
                    "§e━━━ §f0.1道源/个 §e━━━",
                    "§f余恨砂  哈气",
                    "§e━━━ §f0.2道源/个 §e━━━",
                    "§f碧息石  黄泉引",
                    "§e━━━ §f1道源/个 §e━━━",
                    "§f初级神明化合物  命轨星屑  致密哈气",
                    "§e━━━ §f4道源/个 §e━━━",
                    "§f哈基仙  造物匣原片",
                    "§e━━━ §f5道源/个 §e━━━",
                    "§f九色琉璃珠  太虚枢机  猫王",
                    "§e━━━ §f50道源/个 §e━━━",
                    "§f星纹铁  月华砂  灵犀玉  青冥石",
                    "§f紫电晶  玄冰魄  赤炎髓  风灵羽",
                    "§e━━━ §f400道源/个 §e━━━",
                    "§f天机丝  太一真水  鸿蒙尘  万象铜",
                    "§f涅槃火种  九幽铁  星辰钢",
                    "§e━━━ §f2000道源/个 §e━━━",
                    "§f混沌源核  造化玉碟碎片  大道之种",
                    "§f永恒神木  鸿蒙紫晶",
                    "§7可在脚本 EXCHANGE_RATES 中添加",
            });

    private static final ItemStack GONGDE_ITEM = buildInfoPaper(
            "§6§l▣ 兑换功德清单",
            new String[]{
                    "§e━━━ §f1功德/个 §e━━━",
                    "§f灵蕴草穗  凝露花  赤炎果",
                    "§f寒霜参  紫电藤果  金髓稻穗",
                    "§e━━━ §f5功德/个 §e━━━",
                    "§f青木芝  玄水莲子  厚土根",
                    "§f曜光葵  幽影菇",
                    "§e━━━ §f30功德/个 §e━━━",
                    "§f雷音竹  冰心梅  火灵椒",
                    "§e━━━ §f100功德/个 §e━━━",
                    "§f风语藤果  星辰麦穗",
                    "§7可在脚本 GONGDE_RATES 中添加",
            });

    private static final ItemStack CONFIRM_BUTTON = buildConfirmButton();

    /** 确认按钮的显示名（用于校验点击的确实是它）。 */
    private static final String CONFIRM_NAME = "✔ 确认兑换";

    private static ItemStack buildInfoPaper(String name, String[] loreLines) {
        ItemStack it = new ItemStack(Material.PAPER);
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

    private static ItemStack buildConfirmButton() {
        ItemStack it = new ItemStack(Material.DISPENSER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy("§a§l" + CONFIRM_NAME));
            meta.lore(Arrays.asList(
                    legacy("§7将可兑换材料放入输入槽"),
                    legacy("§7点击此处将材料兑换为道源/功德"),
                    legacy("§7需要背包中存在已绑定的问天玉碟")));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    /** 当前打开的兑换界面。 */
    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();

    private static final Random RANDOM = new Random();

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"onUse".equals(fn)) return null;
        if (args.length == 0 || !(args[0] instanceof PlayerInteractEvent event)) {
            return Boolean.FALSE;
        }
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                return Boolean.FALSE;
            }
            handleUse(event.getPlayer());
            return Boolean.TRUE;
        } catch (Throwable t) {
            TT.log("天道货币兑换处理出错: " + t);
            return Boolean.FALSE;
        }
    }

    // ------------------------------------------------------------------
    // 右键：绑定 / 打开
    // ------------------------------------------------------------------

    private static void handleUse(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return;

        SlimefunItem sfItem = SlimefunItem.getByItem(item);
        if (sfItem == null || !CARD_ID.equalsIgnoreCase(sfItem.getId())) return;

        // 数量必须为 1，否则 PDC 绑定只会作用于其中一颗
        if (item.getAmount() != 1) {
            player.sendMessage(DY_PREFIX + "§c请将问天玉碟分离为1个后再使用！");
            return;
        }

        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        String owner = getCardOwner(item);

        if (owner == null) {
            // 未绑定 → 绑定
            if (hasBoundCardInInventory(player.getInventory(), uuid)) {
                player.sendMessage(DY_PREFIX + "§c你已经持有一块绑定的问天玉碟！一人一碟，请勿多持。");
                return;
            }
            bindCard(item, uuid);
            File dataFile = dataFile(name);
            if (dataFile.exists()) {
                double old = getDaoyuan(name);
                updateDaoyuanLore(item, name, old);
                player.sendMessage(DY_PREFIX + "§a问天玉碟已重新绑定至 §e" + name
                        + " §a，已同步原有道源：§b" + num(old) + "道源");
            } else {
                setDaoyuan(name, 0);
                updateDaoyuanLore(item, name, 0);
                player.sendMessage(DY_PREFIX + "§a问天玉碟已绑定至 §e" + name + " §a，初始道源：§b0");
            }
            openExchangeGui(player);
            return;
        }

        if (!owner.equals(uuid)) {
            player.sendMessage(DY_PREFIX + "§c一人一碟，请将不属于你的问天玉碟归还！");
            return;
        }

        openExchangeGui(player);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    private static File dataFile(String playerName) {
        return new File(AddonPaths.configDir("天道货币"), playerName + ".json");
    }

    private static Map<String, Object> loadData(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(dataFile(playerName));
        return data == null ? new LinkedHashMap<>() : data;
    }

    private static void saveData(String playerName, Map<String, Object> data) {
        if (!AddonPaths.writeJson(dataFile(playerName), data)) {
            TT.log("[天道货币] 保存失败 player=" + playerName);
        }
    }

    /**
     * 读道源余额。
     *
     * <p>完整复刻原脚本的双字段调和规则：{@code daoyuanExact} 是权威值（字符串，避开浮点
     * 表示问题），{@code daoyuan} 是兼容用的数值字段。两者明显不一致时取较大者——
     * 这是老档从纯数值字段迁移过来时的取舍。</p>
     */
    private static double getDaoyuan(String playerName) {
        Map<String, Object> data = loadData(playerName);
        Object rawExact = data.get("daoyuanExact");
        Object rawNum = data.get("daoyuan");

        if (rawExact == null) return toDouble(rawNum, 0);
        double exact = toDouble(rawExact, Double.NaN);
        double num = toDouble(rawNum, Double.NaN);
        if (Double.isNaN(exact)) return toDouble(rawNum, 0);
        if (Double.isNaN(num)) return exact;
        if (exact == 0 && num > 0) return num;
        if (num > exact) {
            double diff = num - exact;
            double relative = diff / Math.max(1, Math.abs(exact));
            if (relative > 1e-9) return num;
        }
        return exact;
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

    private static void setDaoyuan(String playerName, double daoyuan) {
        Map<String, Object> data = loadData(playerName);
        data.put("daoyuan", daoyuan);
        data.put("daoyuanExact", num(daoyuan));
        data.putIfAbsent("gongde", 0);
        saveData(playerName, data);
    }

    private static double getGongde(String playerName) {
        return toDouble(loadData(playerName).get("gongde"), 0);
    }

    private static void setGongde(String playerName, double gongde) {
        Map<String, Object> data = loadData(playerName);
        data.put("gongde", gongde);
        data.putIfAbsent("daoyuan", 0);
        saveData(playerName, data);
    }

    /**
     * 按 JS 的 {@code String(number)} 语义格式化数值：整数不带小数点。
     *
     * <p>Java 的 {@code String.valueOf(2.0)} 会得到 {@code "2.0"}，而 JS 得到 {@code "2"}，
     * 直接拼接会写进存档影响后续比对，因此统一走这里。</p>
     */
    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------
    // 玉碟绑定与 lore
    // ------------------------------------------------------------------

    private static String getCardOwner(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || CARD_OWNER_KEY == null) return null;
            return meta.getPersistentDataContainer()
                    .get(CARD_OWNER_KEY, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean bindCard(ItemStack item, String uuid) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || CARD_OWNER_KEY == null) return false;
            meta.getPersistentDataContainer().set(CARD_OWNER_KEY, PersistentDataType.STRING, uuid);
            item.setItemMeta(meta);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 刷新玉碟 lore 的器主 / 道源余额 / 功德余额三行（下标 2、3、4）。 */
    private static void updateDaoyuanLore(ItemStack item, String playerName, double daoyuan) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            while (lore.size() < 5) lore.add(legacy("§7"));

            lore.set(2, legacy("§f[§e器主§f]§b " + playerName));
            lore.set(3, legacy("§f[§e道源余额§f]§b " + num(daoyuan) + "道源"));
            lore.set(4, legacy("§f[§e功德余额§f]§6 " + num(getGongde(playerName)) + "功德"));
            meta.lore(lore);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // lore 刷新失败不影响余额
        }
    }

    /** 物品的粘液 id（走 PDC，等价原脚本先查 {@code slimefun:slimefun_item} 再退回注册表）。 */
    private static String sfId(ItemStack stack) {
        return TT.sfIdOf(stack);
    }

    /** 背包里找属于该玩家的已绑定玉碟。 */
    private static Card findBoundCard(org.bukkit.inventory.Inventory inv, Player player) {
        if (inv == null) return null;
        String uuid = player.getUniqueId().toString();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = sfId(stack);
            if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
            String owner = getCardOwner(stack);
            if (uuid.equals(owner)) {
                return new Card(stack, i);
            }
        }
        return null;
    }

    private static boolean hasBoundCardInInventory(org.bukkit.inventory.Inventory inv, String uuid) {
        if (inv == null) return false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = sfId(stack);
            if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
            if (uuid.equals(getCardOwner(stack))) return true;
        }
        return false;
    }

    /** 把背包里所有该玩家的玉碟 lore 刷成最新余额。 */
    private static void syncAllCards(org.bukkit.inventory.Inventory inv, String uuid,
                                     String name, double newDaoyuan) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = sfId(stack);
            if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
            if (uuid.equals(getCardOwner(stack))) updateDaoyuanLore(stack, name, newDaoyuan);
        }
    }

    /** 找到的玉碟（物品 + 槽位）。 */
    private record Card(ItemStack item, int slot) {}

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private static void openExchangeGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, legacy(GUI_TITLE));

        for (int slot : OUTER_SLOTS) {
            String colorName = OUTER_COLORS[RANDOM.nextInt(OUTER_COLORS.length)];
            Material mat = Material.matchMaterial(colorName);
            if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
            ItemStack pane = new ItemStack(mat);
            ItemMeta meta = pane.getItemMeta();
            if (meta != null) {
                meta.displayName(legacy("§0"));
                pane.setItemMeta(meta);
            }
            inv.setItem(slot, pane);
        }
        inv.setItem(CONFIRM_SLOT, CONFIRM_BUTTON.clone());
        inv.setItem(PURPLE_SLOT, PURPLE_ITEM.clone());
        inv.setItem(GONGDE_INFO_SLOT, GONGDE_ITEM.clone());

        try {
            player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.5f);
        } catch (Throwable ignored) {
            // 忽略
        }
        ACTIVE.add(inv);
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // 兑换
    // ------------------------------------------------------------------

    private static void processExchange(Player player, Inventory inv) {
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            int totalItems = 0;

            // 统计输入槽
            for (int slot : INPUT_SLOTS) {
                ItemStack stack = inv.getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;
                String id = sfId(stack);
                if (id == null) continue;
                if (!EXCHANGE_RATES.containsKey(id) && !GONGDE_RATES.containsKey(id)) continue;
                counts.merge(id, stack.getAmount(), Integer::sum);
                totalItems += stack.getAmount();
            }

            if (totalItems <= 0) {
                player.sendMessage(DY_PREFIX + "§c输入槽中没有可兑换的材料！");
                try {
                    player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                return;
            }

            String uuid = player.getUniqueId().toString();
            String name = player.getName();

            if (findBoundCard(player.getInventory(), player) == null) {
                player.sendMessage(DY_PREFIX + "§c背包中没有已绑定的问天玉碟！");
                return;
            }

            double gainedDaoyuan = 0;
            double gainedGongde = 0;
            for (int slot : INPUT_SLOTS) {
                ItemStack stack = inv.getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;
                String id = sfId(stack);
                if (id == null) continue;
                Double dr = EXCHANGE_RATES.get(id);
                if (dr != null) gainedDaoyuan += stack.getAmount() * dr;
                Double gr = GONGDE_RATES.get(id);
                if (gr != null) gainedGongde += stack.getAmount() * gr;
                inv.setItem(slot, null);
            }

            double newDaoyuan = getDaoyuan(name) + gainedDaoyuan;
            double newGongde = getGongde(name) + gainedGongde;
            setDaoyuan(name, newDaoyuan);
            setGongde(name, newGongde);
            syncAllCards(player.getInventory(), uuid, name, newDaoyuan);

            try {
                org.bukkit.Location loc = player.getLocation();
                loc.getWorld().spawnParticle(Particle.END_ROD, loc, 15, 0.4, 0.4, 0.4, 0.15);
                loc.getWorld().playSound(loc, "entity.experience_orb.pickup", 0.6f, 1.3f);
            } catch (Throwable ignored) {
                // 特效失败不影响兑换
            }

            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                parts.add("§b" + e.getValue() + "个 §e"
                        + ITEM_NAMES.getOrDefault(e.getKey(), e.getKey()));
            }
            StringBuilder gain = new StringBuilder();
            if (gainedDaoyuan > 0) {
                gain.append("获得 §b").append(num(gainedDaoyuan))
                        .append("道源§a，当前道源：§b").append(num(newDaoyuan)).append("道源§a；");
            }
            if (gainedGongde > 0) {
                gain.append("获得 §6").append(num(gainedGongde))
                        .append("功德§a，当前功德：§6").append(num(newGongde)).append("功德");
            }
            player.sendMessage(DY_PREFIX + "§a本次转化了 " + String.join("§a，", parts)
                    + "§a，" + gain);
        } catch (Throwable t) {
            TT.log("天道货币兑换结算出错: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new GuiListener());
    }

    /** 兑换界面的事件。 */
    private static final class GuiListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;

                org.bukkit.inventory.Inventory clicked = event.getClickedInventory();
                int slot = event.getRawSlot();

                // 点到自己背包：不处理（允许正常整理）
                if (clicked != top) return;

                if (slot == CONFIRM_SLOT) {
                    event.setCancelled(true);
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem == null || clickedItem.getType().isAir()) return;
                    if (!isConfirmButton(clickedItem)) return;
                    processExchange(player, top);
                    return;
                }
                // 输入槽：允许自由放入/取出
                if (isInputSlot(slot)) return;

                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 关闭界面：把输入区的材料原样还给玩家，避免吞物。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory inv = event.getInventory();
                if (!ACTIVE.contains(inv)) return;

                if (event.getPlayer() instanceof Player player) {
                    for (int slot : INPUT_SLOTS) {
                        ItemStack item = inv.getItem(slot);
                        if (item == null || item.getType().isAir()) continue;
                        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                        if (leftover != null) {
                            for (ItemStack rest : leftover.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                            }
                        }
                        inv.setItem(slot, null);
                    }
                }
                ACTIVE.remove(inv);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static boolean isInputSlot(int slot) {
            for (int s : INPUT_SLOTS) {
                if (s == slot) return true;
            }
            return false;
        }

        /** 判断点击的是不是那块确认按钮（比去色后的显示名）。 */
        private static boolean isConfirmButton(ItemStack item) {
            try {
                ItemMeta meta = item.getItemMeta();
                if (meta == null || !meta.hasDisplayName()) return false;
                String raw = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
                return stripColors(raw).trim().equals(CONFIRM_NAME);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 去掉 {@code §} 与 {@code §x} 十六进制色码。 */
        private static String stripColors(String s) {
            if (s == null) return "";
            String t = s.replaceAll("§x(§[0-9a-fA-F]){6}", "");
            return t.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        ACTIVE.clear();
    }
}
