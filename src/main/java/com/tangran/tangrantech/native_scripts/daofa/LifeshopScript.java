package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.tangran.tangrantech.util.Colors;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import com.tangran.tangrantech.native_scripts.daofa.LifeshopCatalog;
import com.tangran.tangrantech.native_scripts.daofa.PlayerAssets;

/**
 * 「道法/浮生当铺」的原生实现（对应 {@code scripts/道法/浮生当铺.js}，2192 行）。
 * 以<b>道源</b>为货币的道源商城，商品目录与定价见 {@link LifeshopCatalog}。
 *
 * <h2>六套界面</h2>
 * <ol>
 *   <li><b>主菜单</b> —— 原版 / 粘液科技 两个入口（外圈随机彩色玻璃装饰）；</li>
 *   <li><b>粘液科技</b> —— 材料类 / 道具类 / 灵圃 / 灵植种子 / 机器类 / 浮生绝品；</li>
 *   <li><b>原版</b> —— 11 个原版子分类 + 搜索入口；</li>
 *   <li><b>浮生绝品</b> —— 异火购买类 / 丹方购买类；</li>
 *   <li><b>分类商品</b> —— 每页 28 件（第 2~5 行、每行 7 格）；</li>
 *   <li><b>搜索结果</b> —— 与分类商品同布局。</li>
 * </ol>
 *
 * <h2>购买的前置条件（缺一不可）</h2>
 * <ol>
 *   <li>背包里要有<b>已绑定本人</b>的问天玉碟（{@code TR_WTYD}）—— 没有就拒绝；</li>
 *   <li>道源够（按最小单位 1/10000 比较，避免小数价被四舍五入成 0 消耗）；</li>
 *   <li>功德够（双货币商品）；</li>
 *   <li>{@code needItems} 里的额外材料够。</li>
 * </ol>
 * <p>限购商品（异火 {@code limited}、丹方 {@code danfang}）不发放实物，只往玩家的
 * 异火/丹方权限里记一笔「已掌握」，并全服广播。</p>
 *
 * <h2>扣款用 BigDecimal</h2>
 * <p>道源余额以字符串存于 {@code daoyuanExact}，扣款走
 * {@code BigDecimal(余额) - BigDecimal(单价) × BigDecimal(数量)}。
 * 若先转 double 再扣，大额余额（十万级）乘上小数单价会掉尾数 —— 原脚本在注释里明确
 * 记录过这个问题。</p>
 *
 * <h2>与原脚本的差异</h2>
 * <ul>
 *   <li>会话上下文改用 {@link ShopHolder}（挂在 Inventory 上），不再维护
 *       {@code activeInventories} / {@code INV_DATA} 两张全局表；{@code InventoryCloseEvent}
 *       因此也不需要注册。</li>
 *   <li>道源 / 功德 / 玉碟 / 异火权限 / 丹方权限的读写统一走 {@link PlayerAssets}。</li>
 *   <li>商品初始化（扫全量材质 + 配方递归定价）耗时较长，因此放在首次访问时懒加载，
 *       期间界面上会提示「数据加载中」——原脚本是在脚本求值阶段同步跑完的。</li>
 * </ul>
 */
public final class LifeshopScript implements NativeLifecycleScript {

    /** 界面标题（主菜单）。 */
    private static final String SHOP_TITLE = "{#d4f1f9}&l浮{#a8e6f0}&l生{#7fd4e6}&l当{#5bb8d8}&l铺";

    /** 聊天前缀。 */
    private static final String PREFIX = "§f[§x§F§F§D§7§0§0道§x§F§F§A§B§8§0源§f] ";

    /** 商品展示槽：第 2~5 行、每行中间 7 格，共 28 格。 */
    private static final int[] PRODUCT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    /** 槽位 → 商品在本页内的序号。 */
    private static final Map<Integer, Integer> SLOT_INDEX = buildSlotIndex();

    private static Map<Integer, Integer> buildSlotIndex() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < PRODUCT_SLOTS.length; i++) m.put(PRODUCT_SLOTS[i], i);
        return Map.copyOf(m);
    }

    private static final int PREV_SLOT = 47;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_SLOT = 45;

    /** 主菜单关闭按钮。 */
    private static final int MAIN_CLOSE_SLOT = 49;
    /** 子菜单返回按钮。 */
    private static final int SUB_BACK_SLOT = 49;
    /** 原版子菜单里的搜索入口。 */
    private static final int VANILLA_SEARCH_SLOT = 45;

    /** 道源按最小单位比较的放大倍数（原脚本 {@code DAOYUAN_SCALE}）。 */
    private static final long DAOYUAN_SCALE = 10_000L;

    /** 「尽量买」用的巨大数量。 */
    private static final int BUY_AS_MANY_AS_POSSIBLE = 999_999;

    /** 装饰用随机彩色玻璃颜色池。 */
    private static final String[] GLASS_COLORS = {
            "RED_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE",
            "LIGHT_BLUE_STAINED_GLASS_PANE", "BLUE_STAINED_GLASS_PANE",
            "PURPLE_STAINED_GLASS_PANE", "MAGENTA_STAINED_GLASS_PANE",
            "PINK_STAINED_GLASS_PANE"};

    /** 异火购买成功的庆祝音高序列。 */
    private static final float[] FANFARE_PITCHES = {0.5f, 0.6f, 0.7f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f};
    private static final long[] FANFARE_DELAYS = {0, 2, 4, 6, 8, 10, 14, 18};

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    private static final String TYPE_MAIN = "main";
    private static final String TYPE_SLIMEFUN = "slimefun_sub";
    private static final String TYPE_VANILLA = "vanilla_sub";
    private static final String TYPE_JUEPIN = "juepin_sub";
    private static final String TYPE_CAT = "cat";
    private static final String TYPE_SEARCH = "search";

    /** 菜单上下文（挂在 Inventory 上，事件里靠 {@code getHolder()} 还原）。 */
    private static final class ShopHolder implements InventoryHolder {
        final String type;
        String cat;
        int page;
        String parent = TYPE_MAIN;
        String query = "";
        Inventory inv;

        ShopHolder(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** 等待在聊天栏输入搜索词的原版玩家 UUID。 */
    private static final Set<String> SEARCH_PENDING = ConcurrentHashMap.newKeySet();

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        Action action = event.getAction();
                        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                            return Boolean.FALSE;
                        }
                        openMain(event.getPlayer());
                        return Boolean.FALSE;
                    }
                    return Boolean.FALSE;
                }
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        openMain(player);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[浮生当铺] invoke(" + fn + ") 异常: " + t);
            return Boolean.FALSE;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new ShopListener());
    }

    @Override
    public void registerListeners(Plugin plugin) {
        // 商品表在这里预热：一次性扫全量材质 + 配方递归定价可能要几百毫秒到几秒。
        // 不放在静态块里，是为了保证 TT.plugin 与配方注册表都已就绪。
        try {
            LifeshopCatalog.init();
        } catch (Throwable t) {
            TT.log("[浮生当铺] 商品表初始化失败: " + t);
        }
    }

    // ==================================================================
    // 界面打开
    // ==================================================================

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    private static void openMenu(Player player, Inventory inv) {
        try {
            player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.5f);
        } catch (Throwable ignored) {
            // 音效失败不影响功能
        }
        player.openInventory(inv);
    }

    private static void openMain(Player player) {
        if (!LifeshopCatalog.isReady()) {
            // 懒加载兜底：/reload 之后 registerListeners 可能尚未跑到
            try {
                LifeshopCatalog.init();
            } catch (Throwable t) {
                TT.log("[浮生当铺] 商品表初始化失败: " + t);
            }
        }
        if (!LifeshopCatalog.isReady()) {
            player.sendMessage(PREFIX + "§e浮生当铺商品数据正在加载，请稍候再试");
            return;
        }
        ShopHolder holder = new ShopHolder(TYPE_MAIN);
        Inventory inv = buildMainMenu(holder);
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openSlimefunMenu(Player player) {
        ShopHolder holder = new ShopHolder(TYPE_SLIMEFUN);
        Inventory inv = buildSubMenu(holder, "§b粘液科技",
                LifeshopCatalog.SUBCATEGORIES, "§e返回主菜单");
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openVanillaMenu(Player player) {
        ShopHolder holder = new ShopHolder(TYPE_VANILLA);
        Inventory inv = buildSubMenu(holder, "§a原版",
                LifeshopCatalog.VANILLA_SUBCATEGORIES, "§e返回主菜单");
        // 搜索入口
        inv.setItem(VANILLA_SEARCH_SLOT, named(new ItemStack(Material.NAME_TAG), "§b搜索", List.of(
                "§7点击后在聊天栏输入原版物品ID或名称",
                "§7支持输入物品英文显示名或ID")));
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openJuepinMenu(Player player) {
        ShopHolder holder = new ShopHolder(TYPE_JUEPIN);
        Inventory inv = buildSubMenu(holder, "§6浮生绝品",
                LifeshopCatalog.JUE_SUBCATEGORIES, "§e返回粘液科技");
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openCategory(Player player, String catId, int page, String parent) {
        ShopHolder holder = new ShopHolder(TYPE_CAT);
        holder.cat = catId;
        holder.page = page;
        holder.parent = parent;
        Inventory inv = buildCategoryMenu(holder, player.getName(), List.of());
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openSearch(Player player, List<LifeshopCatalog.Def> results,
                                   String query, int page) {
        ShopHolder holder = new ShopHolder(TYPE_SEARCH);
        holder.query = query;
        holder.page = page;
        Inventory inv = buildCategoryMenu(holder, null, results);
        holder.inv = inv;
        openMenu(player, inv);
    }

    // ==================================================================
    // 界面构建
    // ==================================================================

    private static ItemStack named(ItemStack item, String name, List<String> loreLines) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreLines.size());
                for (String line : loreLines) lore.add(legacy(line));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 随机彩色玻璃（名字为空串，纯装饰）。 */
    private static ItemStack randomGlass() {
        String color = GLASS_COLORS[(int) (Math.random() * GLASS_COLORS.length)];
        Material mat = Material.matchMaterial(color);
        return named(new ItemStack(mat == null ? Material.GRAY_STAINED_GLASS_PANE : mat), "§0", null);
    }

    /** 用随机彩色玻璃填满除排除槽位以外的全部格子。 */
    private static void fillRandomGlass(Inventory inv, Set<Integer> exclude) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (exclude.contains(slot)) continue;
            inv.setItem(slot, randomGlass());
        }
    }

    private static ItemStack pageButton(String name, String material) {
        Material mat = Material.matchMaterial(material);
        return named(new ItemStack(mat == null ? Material.STONE : mat), name, null);
    }

    private static Inventory buildMainMenu(ShopHolder holder) {
        Inventory inv = Bukkit.createInventory(holder, 54, legacy(Colors.c(SHOP_TITLE)));

        Set<Integer> categorySlots = new LinkedHashSet<>();
        for (LifeshopCatalog.Category c : LifeshopCatalog.CATEGORIES) categorySlots.add(c.slot());
        fillRandomGlass(inv, categorySlots);

        for (LifeshopCatalog.Category cat : LifeshopCatalog.CATEGORIES) {
            Material mat = Material.matchMaterial(cat.icon());
            int count = LifeshopCatalog.itemCount(cat.id());
            // 原脚本的两个特例：粘液科技的「商品数」只算材料类+道具类；
            // 原版的商品数要把 11 个子分类加起来（它自己的列表永远是空的）。
            if ("slimefun".equals(cat.id())) {
                count = LifeshopCatalog.itemCount("material") + LifeshopCatalog.itemCount("item");
            } else if ("vanilla".equals(cat.id())) {
                count = LifeshopCatalog.vanillaTotal();
            }
            inv.setItem(cat.slot(), named(new ItemStack(mat == null ? Material.STONE : mat),
                    cat.name(), List.of(cat.lore(), "§7当前商品数：§e" + count, "§e点击进入")));
        }

        inv.setItem(MAIN_CLOSE_SLOT, pageButton("§c关闭菜单", "BARRIER"));
        return inv;
    }

    /** 二级菜单（粘液科技 / 原版 / 浮生绝品共用一套布局）。 */
    private static Inventory buildSubMenu(ShopHolder holder, String title,
                                          List<LifeshopCatalog.Category> subs, String backName) {
        Inventory inv = Bukkit.createInventory(holder, 54, legacy(title));
        // 干净风格：前 45 格留空
        for (int i = 0; i < 45; i++) inv.setItem(i, null);
        for (int s = 45; s < 54; s++) inv.setItem(s, pageButton("§0", "GRAY_STAINED_GLASS_PANE"));

        for (LifeshopCatalog.Category sub : subs) {
            Material mat = Material.matchMaterial(sub.icon());
            List<String> lore = new ArrayList<>();
            lore.add(sub.lore());
            if ("juepin".equals(sub.id())) {
                lore.add("§7当前商品数：§e"
                        + (LifeshopCatalog.itemCount("yanhuo") + LifeshopCatalog.itemCount("danfang"))
                        + "（异火+丹方）");
            } else {
                lore.add("§7当前商品数：§e" + LifeshopCatalog.itemCount(sub.id()));
            }
            lore.add("§e点击进入");
            inv.setItem(sub.slot(), named(new ItemStack(mat == null ? Material.STONE : mat),
                    sub.name(), lore));
        }
        inv.setItem(SUB_BACK_SLOT, pageButton(backName, "BARRIER"));
        return inv;
    }

    /**
     * 分类商品界面（搜索结果界面共用）。
     *
     * @param explicit 非空时用它当商品列表（搜索用）；为空则按 {@code holder.cat} 取目录
     */
    private static Inventory buildCategoryMenu(ShopHolder holder, String playerName,
                                               List<LifeshopCatalog.Def> explicit) {
        boolean isSearch = TYPE_SEARCH.equals(holder.type);
        List<LifeshopCatalog.Def> list = isSearch ? explicit : LifeshopCatalog.items(holder.cat);
        int pageCount = Math.max(1, (list.size() + LifeshopCatalog.PAGE_SIZE - 1) / LifeshopCatalog.PAGE_SIZE);
        int page = Math.max(0, Math.min(holder.page, pageCount - 1));
        holder.page = page;

        String title = isSearch ? ("§a搜索：" + holder.query) : LifeshopCatalog.categoryName(holder.cat);
        Inventory inv = Bukkit.createInventory(holder, 54, legacy(title));

        Set<Integer> reserved = new LinkedHashSet<>();
        for (int s : PRODUCT_SLOTS) reserved.add(s);
        reserved.add(PREV_SLOT);
        reserved.add(NEXT_SLOT);
        reserved.add(BACK_SLOT);
        fillRandomGlass(inv, reserved);

        int start = page * LifeshopCatalog.PAGE_SIZE;
        for (int i = 0; i < PRODUCT_SLOTS.length; i++) {
            int index = start + i;
            if (index >= list.size()) break;
            LifeshopCatalog.Def def = list.get(index);

            ItemStack stack;
            boolean locked = playerName != null
                    && ((def.limited && PlayerAssets.hasYanhuo(playerName, def.id))
                    || (def.danfang && PlayerAssets.hasDanfang(playerName, def.id)));
            if (locked) {
                stack = named(new ItemStack(Material.BARRIER), "§7已购买",
                        List.of("§c您已掌握" + gradientName(def)));
            } else {
                stack = buildProductStack(def);
            }
            if (stack != null) inv.setItem(PRODUCT_SLOTS[i], stack);
        }

        if (page > 0) inv.setItem(PREV_SLOT, pageButton("§e上一页", "ARROW"));
        else inv.setItem(PREV_SLOT, randomGlass());

        if (page < pageCount - 1) inv.setItem(NEXT_SLOT, pageButton("§e下一页", "ARROW"));
        else inv.setItem(NEXT_SLOT, randomGlass());

        inv.setItem(BACK_SLOT, pageButton(isSearch ? "§a返回原版" : "§a返回分类", "BARRIER"));
        return inv;
    }

    /** 商品的展示名（优先用它自带的自定义名，带色）。 */
    private static String gradientName(LifeshopCatalog.Def def) {
        try {
            ItemStack stack = LifeshopCatalog.cleanStack(def);
            if (stack != null) {
                String n = PlayerAssets.displayNameOf(stack, null);
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Throwable ignored) {
            // 落到 def.name
        }
        return LifeshopCatalog.itemName(def);
    }

    /** 商品在 GUI 里的展示（名字 + 价格 + 额外需求 + 操作提示）。 */
    private static ItemStack buildProductStack(LifeshopCatalog.Def def) {
        ItemStack stack = LifeshopCatalog.cleanStack(def);
        if (stack == null) return null;

        List<String> lore = new ArrayList<>();
        double priceDao = def.priceDao;
        double priceGongde = def.priceGongde;

        if (priceDao > 0 && priceGongde > 0) {
            lore.add("§7价格：§b" + PlayerAssets.jsNum(priceDao) + " 道源 + §6"
                    + PlayerAssets.jsNum(priceGongde) + " 功德/个");
        } else if (priceDao > 0) {
            lore.add("§7价格：§b" + PlayerAssets.jsNum(priceDao) + " 道源/个");
        } else if (priceGongde > 0) {
            lore.add("§7价格：§6" + PlayerAssets.jsNum(priceGongde) + " 功德/个");
        } else {
            lore.add("§7价格：§a免费");
        }

        if (def.needItems != null) {
            for (Map.Entry<String, Integer> e : def.needItems.entrySet()) {
                lore.add("§7额外需求：§d" + LifeshopCatalog.needItemName(e.getKey()) + " x" + e.getValue());
            }
        }

        if (def.danfang) {
            lore.add("§e点击购买丹方");
            lore.add("§c每人限购 1 个");
        } else {
            lore.add("§e点击购买 " + Math.max(1, def.giveAmount) + " 个");
            if (def.limited) {
                lore.add("§c每人限购 1 个");
            } else {
                lore.add("§e蹲下左键购买一组");
                lore.add("§e蹲下右键尽量购买");
            }
        }
        return named(stack, def.name, lore);
    }

    // ==================================================================
    // 点击分发
    // ==================================================================

    private static ShopHolder holderOf(Inventory inv) {
        if (inv == null) return null;
        InventoryHolder h = inv.getHolder();
        return (h instanceof ShopHolder sh) ? sh : null;
    }

    private static void handleClick(InventoryClickEvent event, Inventory top, ShopHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        Inventory clicked = event.getClickedInventory();

        // 点击自己背包 / GUI 外：一律拦掉 shift（防止把物品塞进只读菜单）
        if (clicked != top) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        switch (holder.type) {
            case TYPE_MAIN -> handleMain(player, slot);
            case TYPE_SLIMEFUN -> handleSlimefun(player, slot);
            case TYPE_VANILLA -> handleVanilla(player, slot);
            case TYPE_JUEPIN -> handleJuepin(player, slot);
            case TYPE_CAT -> handleCategory(player, event, holder, slot, false);
            case TYPE_SEARCH -> handleCategory(player, event, holder, slot, true);
            default -> {
                // 未知类型：已取消事件
            }
        }
    }

    private static void handleMain(Player player, int slot) {
        if (slot == MAIN_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        for (LifeshopCatalog.Category cat : LifeshopCatalog.CATEGORIES) {
            if (slot != cat.slot()) continue;
            switch (cat.id()) {
                case "slimefun" -> openSlimefunMenu(player);
                case "vanilla" -> openVanillaMenu(player);
                default -> openCategory(player, cat.id(), 0, TYPE_MAIN);
            }
            return;
        }
    }

    private static void handleSlimefun(Player player, int slot) {
        if (slot == SUB_BACK_SLOT) {
            openMain(player);
            return;
        }
        for (LifeshopCatalog.Category sub : LifeshopCatalog.SUBCATEGORIES) {
            if (slot != sub.slot()) continue;
            if ("juepin".equals(sub.id())) openJuepinMenu(player);
            else openCategory(player, sub.id(), 0, "slimefun");
            return;
        }
    }

    private static void handleVanilla(Player player, int slot) {
        if (slot == VANILLA_SEARCH_SLOT) {
            SEARCH_PENDING.add(player.getUniqueId().toString());
            player.closeInventory();
            player.sendMessage(PREFIX + "§a请在聊天栏输入要购买的原版物品ID或名称：");
            player.sendMessage("§7例如：DIAMOND、钻石、Stone、Oak Log");
            return;
        }
        if (slot == SUB_BACK_SLOT) {
            openMain(player);
            return;
        }
        for (LifeshopCatalog.Category sub : LifeshopCatalog.VANILLA_SUBCATEGORIES) {
            if (slot == sub.slot()) {
                openCategory(player, sub.id(), 0, "vanilla");
                return;
            }
        }
    }

    private static void handleJuepin(Player player, int slot) {
        if (slot == SUB_BACK_SLOT) {
            openSlimefunMenu(player);
            return;
        }
        for (LifeshopCatalog.Category sub : LifeshopCatalog.JUE_SUBCATEGORIES) {
            if (slot == sub.slot()) {
                openCategory(player, sub.id(), 0, "juepin");
                return;
            }
        }
    }

    private static void handleCategory(Player player, InventoryClickEvent event,
                                       ShopHolder holder, int slot, boolean isSearch) {
        boolean isSearchMenu = isSearch || TYPE_SEARCH.equals(holder.type);
        List<LifeshopCatalog.Def> list = isSearchMenu
                ? LifeshopCatalog.searchVanilla(holder.query)
                : LifeshopCatalog.items(holder.cat);

        if (slot == BACK_SLOT) {
            if (isSearchMenu) {
                openVanillaMenu(player);
                return;
            }
            switch (holder.parent == null ? TYPE_MAIN : holder.parent) {
                case "juepin" -> openJuepinMenu(player);
                case "slimefun" -> openSlimefunMenu(player);
                case "vanilla" -> openVanillaMenu(player);
                default -> openMain(player);
            }
            return;
        }
        if (slot == PREV_SLOT && holder.page > 0) {
            rePage(player, holder, list, holder.page - 1, isSearchMenu);
            return;
        }
        if (slot == NEXT_SLOT) {
            int pageCount = Math.max(1, (list.size() + LifeshopCatalog.PAGE_SIZE - 1)
                    / LifeshopCatalog.PAGE_SIZE);
            if (holder.page < pageCount - 1) {
                rePage(player, holder, list, holder.page + 1, isSearchMenu);
            }
            return;
        }

        Integer index = SLOT_INDEX.get(slot);
        if (index == null) return;
        int itemIndex = holder.page * LifeshopCatalog.PAGE_SIZE + index;
        if (itemIndex >= list.size()) return;

        LifeshopCatalog.Def def = list.get(itemIndex);
        ClickType click = event.getClick();
        int amount = Math.max(1, def.giveAmount);
        if (click == ClickType.SHIFT_LEFT) {
            ItemStack proto = LifeshopCatalog.cleanStack(def);
            amount = proto == null ? 64 : Math.max(1, proto.getMaxStackSize());
        } else if (click == ClickType.SHIFT_RIGHT) {
            amount = BUY_AS_MANY_AS_POSSIBLE;
        }
        buyItem(player, def, amount);
    }

    private static void rePage(Player player, ShopHolder holder,
                               List<LifeshopCatalog.Def> list, int page, boolean isSearch) {
        if (isSearch) {
            openSearch(player, list, holder.query, page);
        } else {
            openCategory(player, holder.cat, page, holder.parent);
        }
    }

    // ==================================================================
    // 购买
    // ==================================================================

    /**
     * 购买一件商品。
     *
     * @param requestedAmount 想买的数量；{@link #BUY_AS_MANY_AS_POSSIBLE} 表示「有多少钱买多少」
     */
    private static void buyItem(Player player, LifeshopCatalog.Def def, int requestedAmount) {
        UUID uuid = player.getUniqueId();
        String uuidStr = uuid.toString();
        String name = player.getName();

        // 1) 必须有绑定本人的问天玉碟
        ItemStack card = PlayerAssets.findBoundCard(player.getInventory(), uuid);
        if (card == null) {
            player.sendMessage(PREFIX + "§c背包中没有已绑定的问天玉碟！");
            return;
        }

        // 2) 限购检查
        if (def.limited && PlayerAssets.hasYanhuo(name, def.id)) {
            player.sendMessage("§c您已掌握" + gradientName(def));
            playSound(player, "entity.villager.no", 1.0f, 1.0f);
            return;
        }
        if (def.danfang && PlayerAssets.hasDanfang(name, def.id)) {
            player.sendMessage("§c您已拥有" + gradientName(def));
            playSound(player, "entity.villager.no", 1.0f, 1.0f);
            return;
        }

        // 3) 余额
        String daoyuanExact = PlayerAssets.daoyuanExact(name);
        double daoyuan = toDouble(daoyuanExact, 0);
        double gongde = PlayerAssets.gongde(name);

        double needDaoyuan = def.priceDao;
        double needGongde = def.priceGongde;
        long daoyuanUnits = Math.round(daoyuan * DAOYUAN_SCALE);
        long needDaoyuanUnits = Math.round(needDaoyuan * DAOYUAN_SCALE);

        if (needDaoyuanUnits > 0 && daoyuanUnits < needDaoyuanUnits) {
            player.sendMessage(PREFIX + "§c道源不足！需要 §b" + PlayerAssets.jsNum(needDaoyuan)
                    + "道源§c，当前 §b" + PlayerAssets.jsNum(daoyuan) + "道源");
            return;
        }
        if (needGongde > 0 && gongde < needGongde) {
            player.sendMessage(PREFIX + "§c功德不足！需要 §6" + PlayerAssets.jsNum(needGongde)
                    + "功德§c，当前 §6" + PlayerAssets.jsNum(gongde) + "功德");
            return;
        }

        // 4) 能买多少（道源 / 功德 / 额外材料三者取最小）
        int maxAffordable = BUY_AS_MANY_AS_POSSIBLE;
        if (needDaoyuanUnits > 0) {
            maxAffordable = Math.min(maxAffordable, (int) (daoyuanUnits / needDaoyuanUnits));
        }
        if (needGongde > 0) {
            maxAffordable = Math.min(maxAffordable, (int) (gongde / needGongde));
        }
        if (def.needItems != null) {
            for (Map.Entry<String, Integer> e : def.needItems.entrySet()) {
                int per = Math.max(1, e.getValue());
                maxAffordable = Math.min(maxAffordable, countInInventory(player, e.getKey()) / per);
            }
        }
        if (def.danfang) maxAffordable = Math.min(maxAffordable, 1);

        int amount = Math.min(requestedAmount, maxAffordable);
        if (amount <= 0) {
            sendShortfall(player, def, daoyuan, gongde, needDaoyuanUnits, daoyuanUnits, needGongde);
            return;
        }

        // 5) 发货（限购商品不发实物，只记资格）
        int added;
        if (def.limited || def.danfang) {
            added = 1;
        } else {
            added = giveItem(player, def, amount);
            if (added <= 0) {
                player.sendMessage(PREFIX + "§c背包已满，无法购买！");
                return;
            }
        }

        // 6) 扣额外材料
        StringBuilder itemCostMsg = new StringBuilder();
        if (def.needItems != null) {
            for (Map.Entry<String, Integer> e : def.needItems.entrySet()) {
                int needTotal = Math.max(1, e.getValue()) * added;
                if (!takeFromInventory(player, e.getKey(), needTotal)) {
                    // 理论上不会走到这里（前面已按可取数量裁剪）
                    player.sendMessage(PREFIX + "§c额外材料不足，购买已取消！");
                    return;
                }
                itemCostMsg.append("、§d").append(needTotal).append("个")
                        .append(LifeshopCatalog.needItemName(e.getKey()));
            }
        }

        // 7) 扣款（BigDecimal，避免大额余额乘小数单价丢精度）
        double costGongde = added * needGongde;
        String newDaoyuanExact;
        String costDaoyuan;
        try {
            BigDecimal needBD = new BigDecimal(PlayerAssets.jsNum(needDaoyuan));
            BigDecimal balanceBD = new BigDecimal(daoyuanExact);
            BigDecimal costBD = needBD.multiply(new BigDecimal(String.valueOf(added)));
            BigDecimal newBD = balanceBD.subtract(costBD);
            newDaoyuanExact = newBD.toPlainString();
            costDaoyuan = costBD.toPlainString();
        } catch (NumberFormatException ex) {
            TT.log("[浮生当铺] 余额格式异常，购买已取消 player=" + name + " exact=" + daoyuanExact);
            player.sendMessage(PREFIX + "§c余额数据异常，购买已取消，请联系管理。");
            return;
        }
        double newGongde = gongde - costGongde;

        PlayerAssets.setDaoyuanExact(name, newDaoyuanExact);
        if (needGongde > 0) PlayerAssets.setGongde(name, newGongde);
        PlayerAssets.syncAllCards(player.getInventory(), uuid, name, newDaoyuanExact);

        StringBuilder costMsg = new StringBuilder("消耗 §b").append(costDaoyuan).append("道源");
        if (costGongde > 0) costMsg.append("、§6").append(PlayerAssets.jsNum(costGongde)).append("功德");
        if (itemCostMsg.length() > 0) costMsg.append(itemCostMsg);

        StringBuilder remainMsg = new StringBuilder("剩余 §b").append(newDaoyuanExact).append("道源");
        if (needGongde > 0) {
            remainMsg.append("、§6").append(PlayerAssets.jsNum(newGongde)).append("功德");
        }

        if (def.danfang) {
            PlayerAssets.addDanfang(name, def.id);
            playFanfare(player);
            broadcast(player, def);
            player.sendMessage(PREFIX + "§a您已掌握" + gradientName(def) + "§a，" + costMsg + "§a，"
                    + remainMsg);
            return;
        }
        if (def.limited) {
            PlayerAssets.addYanhuo(name, def.id);
            playFanfare(player);
            broadcast(player, def);
            player.sendMessage(PREFIX + "§a您已掌握" + gradientName(def) + "§a，" + costMsg + "§a，"
                    + remainMsg);
            return;
        }

        player.sendMessage(PREFIX + "§a购买成功！获得 §b" + added + "个"
                + LifeshopCatalog.itemName(def) + "§a，" + costMsg + "§a，" + remainMsg);
        playSound(player, "entity.experience_orb.pickup", 0.6f, 1.2f);
    }

    /** 买不起时的「差在哪」提示。 */
    private static void sendShortfall(Player player, LifeshopCatalog.Def def,
                                      double daoyuan, double gongde,
                                      long needDaoyuanUnits, long daoyuanUnits, double needGongde) {
        List<String> missing = new ArrayList<>();
        if (needDaoyuanUnits > 0 && daoyuanUnits < needDaoyuanUnits) {
            missing.add("道源需要 " + PlayerAssets.jsNum(def.priceDao) + "，当前 "
                    + PlayerAssets.jsNum(daoyuan));
        }
        if (needGongde > 0 && gongde < needGongde) {
            missing.add("功德需要 " + PlayerAssets.jsNum(needGongde) + "，当前 "
                    + PlayerAssets.jsNum(gongde));
        }
        if (def.needItems != null) {
            for (Map.Entry<String, Integer> e : def.needItems.entrySet()) {
                int need = Math.max(1, e.getValue());
                int have = countInInventory(player, e.getKey());
                if (have < need) {
                    missing.add(LifeshopCatalog.needItemName(e.getKey()) + "需要 " + need
                            + "，当前 " + have);
                }
            }
        }
        if (!missing.isEmpty()) {
            player.sendMessage(PREFIX + "§c购买失败：§f" + String.join("；", missing));
        } else {
            player.sendMessage(PREFIX + "§c货币或额外材料不足，买不了这个商品！");
        }
    }

    /** 发货，返回实际放入背包的数量（背包满就停，不会把东西丢地上）。 */
    private static int giveItem(Player player, LifeshopCatalog.Def def, int amount) {
        ItemStack proto = LifeshopCatalog.cleanStack(def);
        if (proto == null) return 0;
        int max = Math.max(1, proto.getMaxStackSize());
        int added = 0;
        int left = amount;

        while (left > 0) {
            int n = Math.min(left, max);
            ItemStack stack = proto.clone();
            stack.setAmount(n);
            var leftover = player.getInventory().addItem(stack);
            int leftAmt = 0;
            for (ItemStack rest : leftover.values()) {
                if (rest != null) leftAmt += rest.getAmount();
            }
            int got = n - leftAmt;
            added += got;
            left -= n;
            if (got < n) break;
        }
        return added;
    }

    // ==================================================================
    // 背包物品统计（含副手）
    // ==================================================================

    /** 统计背包里某粘液 id 的总数（前 36 格 + 副手）。 */
    private static int countInInventory(Player player, String sfId) {
        if (sfId == null) return 0;
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = TT.sfIdOf(stack);
            if (PlayerAssets.sameId(id, sfId)) count += stack.getAmount();
        }
        ItemStack off = inv.getItem(40);
        if (off != null && !off.getType().isAir()) {
            String offId = TT.sfIdOf(off);
            if (PlayerAssets.sameId(offId, sfId)) count += off.getAmount();
        }
        return count;
    }

    /** 从背包扣掉指定数量的粘液物品；数量不足返回 false（不做任何改动）。 */
    private static boolean takeFromInventory(Player player, String sfId, int amount) {
        if (countInInventory(player, sfId) < amount) return false;
        PlayerInventory inv = player.getInventory();
        int left = amount;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (!PlayerAssets.sameId(TT.sfIdOf(stack), sfId)) continue;
            int take = Math.min(stack.getAmount(), left);
            int remain = stack.getAmount() - take;
            if (remain <= 0) inv.setItem(i, null);
            else stack.setAmount(remain);
            left -= take;
        }
        if (left > 0) {
            ItemStack off = inv.getItem(40);
            if (off != null && !off.getType().isAir()
                    && PlayerAssets.sameId(TT.sfIdOf(off), sfId)) {
                int take = Math.min(off.getAmount(), left);
                int remain = off.getAmount() - take;
                if (remain <= 0) inv.setItem(40, null);
                else off.setAmount(remain);
                left -= take;
            }
        }
        return left <= 0;
    }

    // ==================================================================
    // 广播与特效
    // ==================================================================

    /** 购买绝品时的音高递增庆祝（原脚本 {@code playYanhuoFanfare}）。 */
    private static void playFanfare(Player player) {
        for (int i = 0; i < FANFARE_PITCHES.length; i++) {
            float pitch = FANFARE_PITCHES[i];
            NativePluginHelper.runLater(
                    () -> playSound(player, "block.note_block.pling", 1.0f, pitch),
                    FANFARE_DELAYS[i]);
        }
    }

    /** 全服广播「某某已获取某某」。 */
    private static void broadcast(Player player, LifeshopCatalog.Def def) {
        String msg = "§f[§x§F§F§D§7§0§0道§x§F§F§A§B§8§0源§f] §e" + player.getName()
                + " §a已获取 " + gradientName(def);
        for (Player online : Bukkit.getOnlinePlayers()) {
            try {
                online.sendMessage(msg);
            } catch (Throwable ignored) {
                // 单个玩家发送失败忽略
            }
        }
    }

    private static void playSound(Player player, String key, float volume, float pitch) {
        try {
            if (player.isOnline()) player.playSound(player.getLocation(), key, volume, pitch);
        } catch (Throwable ignored) {
            // 音效失败不影响功能
        }
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

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        SEARCH_PENDING.clear();
    }

    // ==================================================================
    // 事件
    // ==================================================================

    /**
     * 商城界面事件 + 搜索输入。
     *
     * <p>聊天事件是<b>异步</b>的，开界面必须在主线程，所以命中搜索时用
     * {@link NativePluginHelper#runOnMain(Runnable)} 回到主线程。</p>
     */
    private static final class ShopListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = event.getView().getTopInventory();
                ShopHolder holder = holderOf(top);
                if (holder == null) return;
                handleClick(event, top, holder);
            } catch (Throwable t) {
                TT.log("[浮生当铺] 点击处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                if (holderOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @SuppressWarnings("deprecation")
        @EventHandler(priority = EventPriority.NORMAL)
        public void onChat(AsyncPlayerChatEvent event) {
            try {
                Player player = event.getPlayer();
                String uuid = player.getUniqueId().toString();
                if (!SEARCH_PENDING.remove(uuid)) return;

                event.setCancelled(true);
                String query = event.getMessage();
                List<LifeshopCatalog.Def> results = LifeshopCatalog.searchVanilla(query);
                if (results.isEmpty()) {
                    player.sendMessage(PREFIX + "§c未找到原版物品：" + query);
                    return;
                }
                NativePluginHelper.runOnMain(() -> {
                    if (player.isOnline()) openSearch(player, results, query, 0);
                });
            } catch (Throwable ignored) {
                // 聊天处理失败不能让异常冒到事件总线
            }
        }
    }
}
