package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 「道法/终刻合成台编译器」的原生实现（对应 {@code scripts/道法/终刻合成台编译器.js}）。
 * 管理员专用的配方编辑界面。
 *
 * <p>界面 9×6：第 1~7 列（42 格）放材料，第 8 列是控制列（第 1 格 = 保存配方，
 * 其余 5 格 = 无名字黄色玻璃板，不可触碰），第 9 列第 1 格放成品。</p>
 *
 * <p>点「保存配方」→ 写
 * {@code addons/糖然科技 TangranTech/终刻合成台配方/<成品 sfid 或 mcid>.json}，
 * 供「道法/终刻合成台」读取。</p>
 *
 * <p>关闭界面时把内容存成草稿 {@code addon_configs/糖然科技/终刻合成台/<管理员名>_编译器.json}，
 * 下次打开自动摆回，误关不丢东西。</p>
 *
 * <p><b>只有 OP 能用</b>：非 OP 打开/保存都会被拦下并关界面，日志按玩家 10 秒限流一条。</p>
 *
 * <p>与原脚本的差异：原脚本在 {@code onUse} 里延时 1 刻再开界面，「避开 RSC / 原版对这次交互的
 * 后续处理」；本插件的 {@code onUse} 由 {@code ScriptItemListener} 在 NORMAL 优先级派发，
 * 同样保留 1 刻延时以免和同 tick 的其它处理抢窗口。</p>
 */
public final class FinalCraftingCompilerScript implements NativeLifecycleScript {

    private static final long ZK_VERBOSE_LOG_INTERVAL_MS = 10_000L;

    /** 第 1~7 列（42 格）材料区。 */
    private static final int[] CRAFT_SLOTS = buildCraftSlots();
    /** 第 8 列（控制列）。 */
    private static final int[] COL8 = {7, 16, 25, 34, 43, 52};
    /** 第 9 列（成品列）。 */
    private static final int[] COL9 = {8, 17, 26, 35, 44, 53};

    /** 第 8 列第 1 格：保存配方。 */
    private static final int SLOT_SAVE = 7;
    /** 第 9 列第 1 格：成品。 */
    private static final int SLOT_RESULT = 8;

    private static final int MENU_SIZE = 54;

    /** 草稿文件版本号。 */
    private static final int DRAFT_VERSION = 1;
    /** 配方文件版本号。 */
    private static final int RECIPE_VERSION = 1;

    private static final String TITLE_EDIT = color(
            "{#e879f9}&l终{#c026d3}&l刻{#7c3aed}&l合{#6366f1}&l成{#0ea5e9}&l台&d&l编译器");

    /** 玩家名 → 编辑会话。 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** 玩家名 → 上次「被拦截」日志的时间戳，用于限流。 */
    private static final Map<String, Long> BLOCK_LOG = new ConcurrentHashMap<>();

    private static int[] buildCraftSlots() {
        int[] out = new int[42];
        int i = 0;
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 7; c++) {
                out[i++] = r * 9 + c;
            }
        }
        return out;
    }

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onButtonGroupClick" -> {
                    // 原脚本注释确认：groups.yml 的按钮点击真正走这个入口
                    if (!(args.length > 0 && args[0] instanceof Player player)) return Boolean.FALSE;
                    if (!player.isOnline()) return Boolean.FALSE;
                    if (!checkOp(player, "点击编译器按钮")) return Boolean.TRUE;
                    openEditor(player);
                    return Boolean.TRUE;
                }
                case "onUse" -> {
                    if (!(args.length > 0 && args[0] instanceof PlayerInteractEvent event)) {
                        return Boolean.FALSE;
                    }
                    Action action = event.getAction();
                    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                        return Boolean.FALSE;
                    }
                    Player player = event.getPlayer();
                    if (!checkOp(player, "右键编译器")) return Boolean.TRUE;
                    // 延后 1 刻再开界面：避开同 tick 的其它交互处理
                    NativePluginHelper.runLater(() -> openEditor(player), 1L);
                    return Boolean.TRUE;
                }
                case "onOpen", "onClick" -> {
                    // 菜单打开 / 菜单按钮点击：同样直接进编译器界面
                    if (args.length > 0 && args[0] instanceof Player player) {
                        if (!checkOp(player, "菜单打开编译器")) return null;
                        NativePluginHelper.runLater(() -> openEditor(player), 1L);
                    }
                    return null;
                }
                case "onClose" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        Session sess = SESSIONS.get(player.getName());
                        if (sess != null && sess.editInv != null) {
                            saveDraft(player, sess.editInv);
                        }
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            log("invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    /** {@code {#rrggbb}} → {@code §x§r§r§g§g§b§b}；{@code &x} → {@code §x}。 */
    static String color(String s) {
        return Colors.c(s);
    }

    /** 去掉所有颜色码。 */
    static String strip(String s) {
        return Colors.strip(s);
    }

    /** 物品的展示名（去色）；无自定义名时退回材质名。 */
    private static String displayName(ItemStack stack) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String plain = strip(meta.getDisplayName());
                if (!plain.isEmpty()) return plain;
            }
        } catch (Throwable ignored) {
            // 退到材质名
        }
        try {
            return stack.getType().name();
        } catch (Throwable t) {
            return "未知物品";
        }
    }

    // ------------------------------------------------------------------
    // 物品身份
    // ------------------------------------------------------------------

    /** 物品身份：粘液物品记 {@code sf + 大写 id}，普通物品记 {@code mc + 材质名}。 */
    private record ItemInfo(String kind, String id, String name) {

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("id", id);
            m.put("name", name);
            return m;
        }

        static ItemInfo from(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) return null;
            Object kind = map.get("kind");
            Object id = map.get("id");
            if (kind == null || id == null) return null;
            Object name = map.get("name");
            return new ItemInfo(String.valueOf(kind), String.valueOf(id),
                    name == null ? String.valueOf(id) : String.valueOf(name));
        }
    }

    /** 识别物品身份；空气/无法识别返回 null。 */
    private static ItemInfo itemInfo(ItemStack stack) {
        try {
            if (stack == null || stack.getType().isAir()) return null;
            SlimefunItem sf = SlimefunItem.getByItem(stack);
            if (sf != null) {
                String sid = String.valueOf(sf.getId());
                if (!sid.isBlank()) {
                    return new ItemInfo("sf", sid.toUpperCase(Locale.ROOT), displayName(stack));
                }
            }
            return new ItemInfo("mc", stack.getType().name(), displayName(stack));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 按 id 取粘液物品的拷贝。
     *
     * <p>原脚本注释提到「RSC 注册表 id 大写的坑」，因此这里对原大小写、全大写、全小写
     * 各试一次。命名空间前缀变体是 RSC 时代的产物，本插件统一按无前缀 id 注册，
     * 不再需要。</p>
     */
    private static ItemStack slimefunItem(String id) {
        try {
            if (id == null || id.isBlank()) return null;
            String[] tries = {id, id.toUpperCase(Locale.ROOT), id.toLowerCase(Locale.ROOT)};
            for (String candidate : tries) {
                SlimefunItem sf;
                try {
                    sf = SlimefunItem.getById(candidate);
                } catch (Throwable ignored) {
                    continue;
                }
                if (sf == null) continue;
                ItemStack it = sf.getItem();
                if (it != null) return it.clone();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 按身份造物品（把草稿摆回界面用）。 */
    private static ItemStack buildStack(ItemInfo info, int amount) {
        try {
            if (info == null) return null;
            ItemStack it;
            if ("sf".equals(info.kind())) {
                it = slimefunItem(info.id());
            } else {
                Material mat = Material.matchMaterial(info.id());
                it = (mat == null) ? null : new ItemStack(mat);
            }
            if (it == null) return null;
            int n = amount;
            if (n < 1) n = 1;
            if (n > 64) n = 64;
            it.setAmount(n);
            return it;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 槽位判定
    // ------------------------------------------------------------------

    private static boolean isIn(int[] slots, int slot) {
        for (int s : slots) {
            if (s == slot) return true;
        }
        return false;
    }

    private static boolean isCraftSlot(int slot) { return isIn(CRAFT_SLOTS, slot); }

    private static boolean isCol8(int slot) { return isIn(COL8, slot); }

    private static boolean isCol9(int slot) { return isIn(COL9, slot); }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    /** 编辑器界面的持有者，用于识别「这是我们的界面」。 */
    static final class EditorHolder implements InventoryHolder {
        private Inventory inventory;

        void attach(Inventory inv) { this.inventory = inv; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    /** 玩家的编辑会话。 */
    private static final class Session {
        Inventory editInv;
    }

    /** 第 8 列应该长什么样（画界面 / 修第 8 列共用）。 */
    private static List<ItemStack> col8Items() {
        List<ItemStack> out = new ArrayList<>();
        out.add(button("WRITABLE_BOOK", "§a§l保存配方", List.of(
                "§7把第 1-7 列的材料 + 第 9 列第一格的成品存成配方",
                "§7文件名 = 成品的 mcid / sfid",
                "§7存放：addons/糖然科技 TangranTech/终刻合成台配方/")));
        for (int i = 0; i < 5; i++) out.add(yellowPane());
        return out;
    }

    private static ItemStack button(String matName, String name, List<String> loreLines) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(color(name)));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(legacy(color(line)));
                meta.lore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack yellowPane() {
        return button("YELLOW_STAINED_GLASS_PANE", "§0", null);
    }

    private static net.kyori.adventure.text.Component legacy(String sectionText) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(sectionText);
    }

    /** 画第 8 列。 */
    private static void drawEditor(Inventory inv) {
        try {
            if (inv == null) return;
            List<ItemStack> items = col8Items();
            for (int i = 0; i < COL8.length && i < items.size(); i++) {
                inv.setItem(COL8[i], items.get(i));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 两个按钮是不是同一个（材质 + 去色名字）。 */
    private static boolean sameButton(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) return false;
            if (a.getType() != b.getType()) return false;
            return displayName(a).equals(displayName(b));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把物品还给玩家（背包满就掉脚下）。 */
    private static void giveBack(Player player, ItemStack stack) {
        try {
            if (player == null || stack == null) return;
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (leftover != null && !leftover.isEmpty()) {
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 第 8 列只归我们管。
     *
     * <p>原脚本注释说明：解除 shift 快速放入的限制后，原版可能把东西塞到第 8 列，
     * 这里把不该在的东西还给玩家并恢复按钮。</p>
     */
    private static void fixCol8(Player player, Inventory inv) {
        try {
            if (player == null || inv == null) return;
            List<ItemStack> want = col8Items();
            for (int i = 0; i < COL8.length; i++) {
                int slot = COL8[i];
                ItemStack expected = want.get(i);
                ItemStack cur = inv.getItem(slot);

                if (cur == null || cur.getType().isAir()) {
                    if (expected != null) inv.setItem(slot, expected);
                    continue;
                }
                if (expected != null && sameButton(cur, expected)) {
                    int extra = cur.getAmount() - expected.getAmount();
                    if (extra > 0) {
                        ItemStack back = cur.clone();
                        back.setAmount(Math.min(extra, 64));
                        giveBack(player, back);
                        inv.setItem(slot, expected);
                    }
                    continue;
                }
                giveBack(player, cur.clone());
                inv.setItem(slot, expected);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 打开（或复用）编辑器界面。 */
    private static void openEditor(Player player) {
        try {
            if (!checkOp(player, "打开编译器界面")) return;

            Session sess = SESSIONS.computeIfAbsent(player.getName(), k -> new Session());
            Inventory inv = sess.editInv;
            if (inv == null) {
                EditorHolder holder = new EditorHolder();
                inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(TITLE_EDIT));
                holder.attach(inv);
                sess.editInv = inv;

                Map<String, Object> draft = AddonPaths.readJson(draftFile(player.getName()));
                if (draft != null && draft.get("slots") instanceof Map<?, ?> slots) {
                    restoreSlots(inv, slots);
                }
            }
            drawEditor(inv);
            player.openInventory(inv);
            log("打开编译器：" + player.getName() + "（OP）");
        } catch (Throwable t) {
            log("打开编译器失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 草稿
    // ------------------------------------------------------------------

    private static File draftFile(String name) {
        return new File(AddonPaths.finalCraftDraftDir(), name + "_编译器.json");
    }

    /** 把草稿里记的槽位还原到界面上。 */
    private static void restoreSlots(Inventory inv, Map<?, ?> slots) {
        try {
            for (Map.Entry<?, ?> e : slots.entrySet()) {
                int slot;
                try {
                    slot = Integer.parseInt(String.valueOf(e.getKey()).trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (!isCraftSlot(slot) && !isCol9(slot)) continue;
                ItemInfo info = ItemInfo.from(e.getValue());
                if (info == null) continue;

                int amount = 1;
                if (e.getValue() instanceof Map<?, ?> m && m.get("amount") != null) {
                    try {
                        amount = (int) Double.parseDouble(String.valueOf(m.get("amount")));
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }
                ItemStack st = buildStack(info, amount);
                if (st != null) inv.setItem(slot, st);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 收集界面上材料区 + 成品列的内容。 */
    private static Map<String, Object> collectSlots(Inventory inv) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (int slot : CRAFT_SLOTS) {
                putSlot(out, inv, slot);
            }
            for (int slot : COL9) {
                putSlot(out, inv, slot);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    private static void putSlot(Map<String, Object> out, Inventory inv, int slot) {
        ItemStack st = inv.getItem(slot);
        ItemInfo info = itemInfo(st);
        if (info == null) return;
        Map<String, Object> m = info.toJson();
        m.put("amount", st.getAmount());
        out.put(String.valueOf(slot), m);
    }

    private static boolean saveDraft(Player player, Inventory inv) {
        try {
            if (player == null || inv == null) return false;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("v", DRAFT_VERSION);
            data.put("slots", collectSlots(inv));
            data.put("updatedAt", System.currentTimeMillis());
            return AddonPaths.writeJson(draftFile(player.getName()), data);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /** 只有 OP 能用；不是 OP 就提示 + 限流记日志 + 关界面。 */
    private static boolean checkOp(Player player, String why) {
        try {
            if (player == null || !player.isOnline()) return false;
            if (player.isOp()) return true;

            player.sendMessage("§c只有服务器 OP 才能使用终刻合成台编译器。");
            long now = System.currentTimeMillis();
            String name = player.getName();
            if (now - BLOCK_LOG.getOrDefault(name, 0L) >= ZK_VERBOSE_LOG_INTERVAL_MS) {
                BLOCK_LOG.put(name, now);
                log("拦截非 OP：" + name + " — " + why);
            }
            try {
                player.closeInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 保存配方
    // ------------------------------------------------------------------

    private static void saveRecipeFromEditor(Player player, Inventory inv) {
        try {
            if (!checkOp(player, "保存配方")) return;
            if (inv == null) return;

            ItemStack result = inv.getItem(SLOT_RESULT);
            if (result == null || result.getType().isAir()) {
                player.sendMessage("§c请把成品放在第 9 列第 1 格。");
                return;
            }
            ItemInfo rInfo = itemInfo(result);
            if (rInfo == null) {
                player.sendMessage("§c成品物品识别失败。");
                return;
            }

            List<Map<String, Object>> ings = new ArrayList<>();
            for (int slot : CRAFT_SLOTS) {
                ItemStack st = inv.getItem(slot);
                if (st == null || st.getType().isAir()) continue;
                ItemInfo ii = itemInfo(st);
                if (ii == null) continue;
                Map<String, Object> m = ii.toJson();
                m.put("slot", slot);
                m.put("amount", st.getAmount());
                ings.add(m);
            }
            if (ings.isEmpty()) {
                player.sendMessage("§c请至少在第 1-7 列放一种材料。");
                return;
            }

            Map<String, Object> rJson = rInfo.toJson();
            rJson.put("amount", result.getAmount());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("v", RECIPE_VERSION);
            data.put("result", rJson);
            data.put("ingredients", ings);
            data.put("savedBy", player.getName());
            data.put("savedAt", System.currentTimeMillis());

            File file = new File(AddonPaths.finalCraftRecipeDir(), rInfo.id() + ".json");
            if (!AddonPaths.writeJson(file, data)) {
                player.sendMessage("§c配方保存失败，看控制台日志。");
                return;
            }

            player.sendMessage("§a配方已保存：§f" + file.getName() + "§7（材料 "
                    + ings.size() + " 种，成品 " + rInfo.name() + " x" + result.getAmount() + "）");
            try {
                player.playSound(player.getLocation(),
                        "ui.toast.challenge_complete", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
            log("配方已保存：" + file.getName() + "（by " + player.getName() + "）");
            saveDraft(player, inv);
        } catch (Throwable t) {
            log("保存配方异常: " + t);
            player.sendMessage("§c配方保存异常，看控制台日志。");
        }
    }

    private static void log(String msg) {
        try {
            TT.log("[糖然科技/终刻合成台编译器] " + msg);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new EditorListener());
    }

    /** 编辑器界面的事件。 */
    private static final class EditorListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (!(top != null && top.getHolder() instanceof EditorHolder)) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;
                int slot = event.getRawSlot();

                // 非 OP：取消点击 + 立刻关掉界面
                if (!player.isOp()) {
                    event.setCancelled(true);
                    checkOp(player, "非 OP 点进了编译器界面");
                    return;
                }

                if (slot >= MENU_SIZE) {
                    // 解除了 shift 快速放入的限制：放行，下一拍统一修第 8 列
                    NativePluginHelper.runLater(() -> fixCol8(player, top), 1L);
                    return;
                }

                if (isCol8(slot)) {
                    event.setCancelled(true);
                    if (slot == SLOT_SAVE) saveRecipeFromEditor(player, top);
                    return;
                }

                try {
                    if (event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
                } catch (Throwable ignored) {
                    // 忽略
                }
                NativePluginHelper.runLater(() -> fixCol8(player, top), 1L);
            } catch (Throwable t) {
                log("点击处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !(top.getHolder() instanceof EditorHolder)) return;

                java.util.Set<Integer> slots;
                try {
                    slots = event.getRawSlots();
                } catch (Throwable ignored) {
                    event.setCancelled(true);
                    return;
                }
                if (slots == null) {
                    event.setCancelled(true);
                    return;
                }
                for (Integer s : slots) {
                    if (s != null && isCol8(s)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !(top.getHolder() instanceof EditorHolder)) return;
                if (!(event.getPlayer() instanceof Player player)) return;
                saveDraft(player, top);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            try {
                Player player = event.getPlayer();
                Session sess = SESSIONS.get(player.getName());
                if (sess != null && sess.editInv != null) saveDraft(player, sess.editInv);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        SESSIONS.clear();
        BLOCK_LOG.clear();
    }
}
