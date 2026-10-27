package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 「道法/终刻合成台」的原生实现（对应 {@code scripts/道法/终刻合成台.js}）。玩家侧的配方合成界面。
 *
 * <p>入口有两处（与原脚本一致）：{@code groups.yml} 的按钮（{@code Tr_zkhct}）与物品
 * {@code script:}，两条都走「校验天道证明 → 打开主界面」。</p>
 *
 * <p><b>准入</b>：只要求 {@code addon_configs/糖然科技/天道证明/<玩家名>.json} 存在
 * （即击败过 BOSS 糖然）。原脚本注释记录过为什么<b>不</b>要求升维进度——那会打环：
 * 升维之路第九阶要交糖心，糖心只能在终刻合成台合成，合成台又要求升维 9，永远进不来。</p>
 *
 * <p><b>三张界面</b>：主界面（42 格材料区 + 第 8 列控制列 + 第 9 列成品）、查看配方列表
 * （每页 45 条）、配方详情（只读）。</p>
 *
 * <p><b>合成规则</b>（严格按槽位，强对称）：配方要求的槽位必须物品正确且数量足够；
 * 材料区里<b>非配方槽位必须为空</b>；只扣配方要求的数量，多余材料留在原槽不吞。</p>
 *
 * <p>存档 {@code addon_configs/糖然科技/终刻合成台/<玩家名>.json}，记录槽位内容与当前选择的配方，
 * 关界面即落盘、合成成功立刻落盘（防崩服丢东西）。注意编译器（管理员侧）用的是同目录下的
 * {@code <玩家名>_编译器.json}，两者文件名不同、互不干扰。</p>
 *
 * <p><b>与本项目的差异（重要）</b>：原脚本只扫磁盘上的两个配方目录。本插件的 jar 里自带
 * {@code data/终刻合成台配方/*.json}（初始配方），因此 {@link #loadRecipes} 在扫完磁盘后
 * 还会读一遍 jar 内资源，把磁盘上没出现过的 id 补进来——否则随插件发布的配方永远看不到。</p>
 */
public final class FinalCraftingTableScript implements NativeLifecycleScript {

    /** 一页展示的配方数（前 5 行）。 */
    private static final int PAGE_SIZE = 45;

    /** 材料区：第 1~7 列（每行 6 行）。 */
    private static final int[] CRAFT_SLOTS = buildCraftSlots();
    private static final int[] COL8 = {7, 16, 25, 34, 43, 52};
    private static final int[] COL9 = {8, 17, 26, 35, 44, 53};

    private static final int SLOT_BOOK = 7;
    private static final int SLOT_SELECTED = 16;
    private static final int SLOT_START = 25;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_PAGE = 47;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private static final int SLOT_SUB_FIRST = 7;
    private static final int SLOT_RESULT = 8;

    private static final int MENU_SIZE = 54;
    private static final int MAX_STACK = 64;

    private static final String TITLE_MAIN = Colors.c("{#e879f9}&l终{#c026d3}&l刻{#7c3aed}&l合{#6366f1}&l成{#0ea5e9}&l台");
    private static final String TITLE_LIST = Colors.c("{#a78bfa}&l查{#8b5cf6}&l看{#7c3aed}&l配{#6d28d9}&l方");
    private static final String TITLE_DETAIL = Colors.c("{#c084fc}&l配{#a855f7}&l方{#9333ea}&l详{#7e22ce}&l情");

    /** 玩家名 → 会话。 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static int[] buildCraftSlots() {
        int[] out = new int[42];
        int i = 0;
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 7; c++) out[i++] = r * 9 + c;
        }
        return out;
    }

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        if (!player.isOnline()) return Boolean.FALSE;
                        if (!checkPerm(player)) return Boolean.TRUE;
                        openMain(player);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        Action a = event.getAction();
                        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
                            return Boolean.FALSE;
                        }
                        Player player = event.getPlayer();
                        if (!player.isOnline()) return Boolean.FALSE;
                        if (!checkPerm(player)) return Boolean.TRUE;
                        // 合成台若绑定成物品，右键只该开界面：取消原版交互防止双发
                        // （当前 yml 只从指南按钮组进入，这里是为将来的物品绑定兜底）
                        try {
                            event.setCancelled(true);
                        } catch (Throwable ignored) {
                            // 事件不可取消时忽略
                        }
                        NativePluginHelper.runLater(() -> openMain(player), 1L);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                case "onOpen", "onClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        if (!player.isOnline()) return null;
                        if (!checkPerm(player)) return null;
                        NativePluginHelper.runLater(() -> openMain(player), 1L);
                    }
                    return null;
                }
                case "onClose" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        Session s = SESSIONS.get(player.getName());
                        if (s != null && s.mainInv != null) savePlayer(player, s.mainInv, s);
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[终刻合成台] invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 物品身份（与编译器同一套语义）
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

    private static String displayName(ItemStack stack) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String plain = Colors.strip(
                        LegacyComponentSerializer.legacySection().serialize(meta.displayName()));
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

    /** 手上物品是否与配方记录的身份一致（kind 必须相同，id 忽略大小写）。 */
    private static boolean sameItem(ItemStack stack, ItemInfo info) {
        try {
            if (stack == null || info == null || stack.getType().isAir()) return false;
            ItemInfo cur = itemInfo(stack);
            if (cur == null) return false;
            if (!cur.kind().equals(info.kind())) return false;
            return cur.id().equalsIgnoreCase(info.id());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 按身份造物品。 */
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
            int n = Math.max(1, Math.min(MAX_STACK, amount));
            it.setAmount(n);
            return it;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按 id 取粘液物品拷贝（原大小写 / 全大写 / 全小写轮试）。 */
    private static ItemStack slimefunItem(String id) {
        try {
            if (id == null || id.isBlank()) return null;
            for (String candidate : new String[]{
                    id, id.toUpperCase(Locale.ROOT), id.toLowerCase(Locale.ROOT)}) {
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

    /** 槽位 → 「第X列第Y行」（用于报错提示）。 */
    private static String slotName(int slot) {
        return "第" + ((slot % 9) + 1) + "列第" + ((slot / 9) + 1) + "行";
    }

    private static boolean isIn(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    private static boolean isCraftSlot(int slot) { return isIn(CRAFT_SLOTS, slot); }

    private static boolean isCol8(int slot) { return isIn(COL8, slot); }

    private static boolean isCol9(int slot) { return isIn(COL9, slot); }

    // ------------------------------------------------------------------
    // 配方
    // ------------------------------------------------------------------

    /** 一条配方：id（成品 id 大写）+ 原始 json。 */
    private record RecipeEntry(String id, Map<String, Object> recipe) {

        Map<String, Object> result() {
            Map<String, Object> r = Json.obj(recipe, "result");
            return r;
        }

        List<Object> ingredients() {
            return Json.arr(recipe, "ingredients");
        }
    }

    /**
     * 扫出全部配方（同 id 去重，磁盘目录优先，其次 jar 内随插件发布的初始配方）。
     */
    private static List<RecipeEntry> loadRecipes() {
        List<RecipeEntry> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();

        // ① 磁盘：主配方目录 → 备用目录
        for (File dir : new File[]{AddonPaths.finalCraftRecipeDir(),
                AddonPaths.finalCraftRecipeAltDir()}) {
            try {
                File[] files = dir.isDirectory() ? dir.listFiles() : null;
                if (files == null) continue;
                for (File f : files) {
                    if (f == null || !f.getName().endsWith(".json")) continue;
                    addRecipe(out, seen, Json.parseObject(readText(f)), f.getName());
                }
            } catch (Throwable ignored) {
                // 单目录失败继续
            }
        }

        // ② jar 内 data/终刻合成台配方/*.json（随插件发布的初始配方）
        try {
            for (String name : AddonPaths.listBundledFiles(AddonPaths.DIR_FINAL_CRAFT_RECIPE)) {
                if (!name.endsWith(".json")) continue;
                String text = AddonPaths.readBundledText(
                        AddonPaths.DIR_FINAL_CRAFT_RECIPE + "/" + name);
                if (text == null) continue;
                addRecipe(out, seen, Json.parseObject(text), name + "（内置）");
            }
        } catch (Throwable ignored) {
            // 无内置配方目录
        }

        out.sort(Comparator.comparing(RecipeEntry::id));
        return out;
    }

    /** 校验「结构像配方」后收进列表；同 id 只取第一个。 */
    private static void addRecipe(List<RecipeEntry> out, Map<String, Boolean> seen,
                                  Map<String, Object> data, String source) {
        // 只认结构像配方的文件（玩家存档也可能落在这些目录里，靠这个过滤）
        if (data == null) return;
        Map<String, Object> result = Json.obj(data, "result");
        if (result.isEmpty() || result.get("id") == null) return;
        if (!(data.get("ingredients") instanceof List)) return;

        String rid = String.valueOf(result.get("id")).toUpperCase(Locale.ROOT);
        if (rid.isBlank() || seen.containsKey(rid)) return;
        seen.put(rid, Boolean.TRUE);
        out.add(new RecipeEntry(rid, data));
    }

    private static String readText(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RecipeEntry findRecipeById(String id) {
        if (id == null) return null;
        for (RecipeEntry e : loadRecipes()) {
            if (e.id().equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 会话 / 存档
    // ------------------------------------------------------------------

    private static final class Session {
        Inventory mainInv;
        ItemInfo selectedInfo;
        String recipeId;
        Map<String, Object> recipe;
        int page;
        List<RecipeEntry> recs;
        RecipeEntry detail;
    }

    private static Session session(Player player) {
        return SESSIONS.computeIfAbsent(player.getName(), k -> new Session());
    }

    private static File saveFile(String name) {
        return new File(AddonPaths.finalCraftSaveDir(), PlayerKeys.key(name) + ".json");
    }

    /** 把 {@code {槽位: {kind,id,amount}}} 摆回界面（只允许 1-7 列与第 9 列）。 */
    private static void restoreSlots(Inventory inv, Map<String, Object> slots) {
        try {
            if (inv == null || slots == null) return;
            for (Map.Entry<String, Object> e : slots.entrySet()) {
                int slot;
                try {
                    slot = Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (!isCraftSlot(slot) && !isCol9(slot)) continue;
                ItemInfo info = ItemInfo.from(e.getValue());
                if (info == null) continue;
                int amount = 1;
                if (e.getValue() instanceof Map<?, ?> m && m.get("amount") != null) {
                    amount = toInt(m.get("amount"), 1);
                }
                ItemStack st = buildStack(info, amount);
                if (st != null) inv.setItem(slot, st);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 收集 1-7 列 + 第 9 列（按槽位）。 */
    private static Map<String, Object> collectSlots(Inventory inv) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (int slot : CRAFT_SLOTS) putSlot(out, inv, slot);
            for (int slot : COL9) putSlot(out, inv, slot);
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

    private static boolean savePlayer(Player player, Inventory inv, Session sess) {
        try {
            if (player == null || inv == null) return false;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("v", 1);
            data.put("name", player.getName());
            data.put("slots", collectSlots(inv));
            data.put("selected", (sess != null && sess.selectedInfo != null)
                    ? sess.selectedInfo.toJson() : null);
            data.put("updatedAt", System.currentTimeMillis());
            return AddonPaths.writeJson(saveFile(player.getName()), data);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /**
     * 只要求「天道之证」。
     *
     * <p>原脚本注释记录过这里的取舍：早期还要求 {@code ascensionStage == 9}，但那会打环——
     * 升维之路第九阶（获取糖心）需要交 1 个糖心，而糖心只能在终刻合成台合成，
     * 合成台又要求升维 9，玩家永远进不来。因此升维进度的要求被取消。</p>
     */
    private static boolean checkPerm(Player player) {
        try {
            if (player == null || !player.isOnline()) return false;
            String name = player.getName();

            File proof = new File(AddonPaths.heavenProofDir(), PlayerKeys.key(name) + ".json");
            if (!AddonPaths.docExists(proof)) {
                player.sendMessage("§c请先击败BOSS糖然");
                return false;
            }

            // 目录 + 玩家存档：只补，不覆盖
            File f = saveFile(name);
            if (!AddonPaths.docExists(f)) {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("v", 1);
                init.put("name", name);
                init.put("slots", new LinkedHashMap<String, Object>());
                init.put("selected", null);
                init.put("updatedAt", System.currentTimeMillis());
                AddonPaths.writeJson(f, init);
            }
            return true;
        } catch (Throwable t) {
            TT.log("[终刻合成台] 权限校验异常: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 界面物品
    // ------------------------------------------------------------------

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private static ItemStack button(String matName, String name, List<String> loreLines) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(Colors.c(name)));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(legacy(Colors.c(line)));
                meta.lore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack yellowPane() {
        return button("YELLOW_STAINED_GLASS_PANE", "§0", null);
    }

    private static ItemStack grayPane() {
        return button("GRAY_STAINED_GLASS_PANE", "§0", null);
    }

    /** 给已有物品追加 lore（不改名字）。 */
    private static ItemStack addLore(ItemStack item, List<String> lines) {
        try {
            if (item == null || lines == null || lines.isEmpty()) return item;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) lore.addAll(existing);
            for (String line : lines) lore.add(legacy(Colors.c(line)));
            meta.lore(lore);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 忽略
        }
        return item;
    }

    /** 主界面第 8 列该长什么样（画界面 / 修第 8 列都用同一份，避免两处对不上）。 */
    private static List<ItemStack> col8Items(Session sess) {
        List<ItemStack> out = new ArrayList<>();
        out.add(button("KNOWLEDGE_BOOK", "§b§l查看配方", List.of(
                "§7点开查看所有已记录的配方",
                "§7左键：查看具体配方",
                "§7右键：选择并一键补料")));

        ItemStack sel = null;
        if (sess != null && sess.selectedInfo != null) {
            sel = buildStack(sess.selectedInfo, 1);
        }
        if (sel != null) {
            addLore(sel, List.of("§7当前选择：" + displayName(sel),
                    "§7材料摆好、数量充足后点「点击开始合成」"));
            out.add(sel);
        } else {
            out.add(button("BARRIER", "§c§l还未选择合成物品",
                    List.of("§7点第 8 列第 1 格的「查看配方」挑一个")));
        }

        out.add(button("LIME_STAINED_GLASS_PANE", "§a§l点击开始合成", List.of(
                "§7必须严格按配方槽位摆放（强对称）",
                "§7数量充足才会合成",
                "§7只扣配方要求的数量，多余材料留在原槽")));
        out.add(yellowPane());
        out.add(yellowPane());
        out.add(yellowPane());
        return out;
    }

    private static void drawMain(Inventory inv, Session sess) {
        try {
            if (inv == null) return;
            List<ItemStack> items = col8Items(sess);
            for (int i = 0; i < COL8.length && i < items.size(); i++) {
                inv.setItem(COL8[i], items.get(i));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static boolean sameButton(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) return false;
            if (a.getType() != b.getType()) return false;
            return displayName(a).equals(displayName(b));
        } catch (Throwable ignored) {
            return false;
        }
    }

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
     * <p>原脚本注释说明：解除 shift 快速放入之后，原版有可能把东西塞到第 8 列，
     * 这里把「不该在那儿的」还给玩家并恢复按钮（第 8 列永远由我们重建，玩家拿不走）。</p>
     */
    private static void fixCol8(Player player, Inventory inv, Session sess) {
        try {
            if (player == null || inv == null) return;
            List<ItemStack> want = col8Items(sess);
            for (int i = 0; i < COL8.length; i++) {
                int slot = COL8[i];
                ItemStack expected = want.get(i);
                ItemStack cur = inv.getItem(slot);

                if (cur == null || cur.getType().isAir()) {
                    if (expected != null) inv.setItem(slot, expected);
                    continue;
                }
                if (expected != null && sameButton(cur, expected)) {
                    // 同款：多了的数量还回去（比如 shift 塞进来的第 2 个黄色玻璃板）
                    // 不能按 MAX_STACK 截断：giveBack 会把装不下的掉脚下，截断等于吞物
                    int extra = cur.getAmount() - expected.getAmount();
                    if (extra > 0) {
                        ItemStack back = cur.clone();
                        back.setAmount(extra);
                        giveBack(player, back);
                        inv.setItem(slot, expected);
                    }
                    continue;
                }
                // 完全不是该在的东西 → 还给玩家，恢复按钮
                giveBack(player, cur.clone());
                inv.setItem(slot, expected);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 打开（或复用）主界面。 */
    private static void openMain(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            Session sess = session(player);
            Inventory inv = sess.mainInv;
            if (inv == null) {
                MenuHolder holder = new MenuHolder(MenuKind.MAIN);
                inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(TITLE_MAIN));
                holder.attach(inv);
                sess.mainInv = inv;

                Map<String, Object> data = Json.parseObject(readText(saveFile(player.getName())));
                if (!data.isEmpty()) {
                    if (data.get("slots") instanceof Map<?, ?> rawSlots) {
                        Map<String, Object> slots = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : rawSlots.entrySet()) {
                            slots.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        restoreSlots(inv, slots);
                    }
                    ItemInfo sel = ItemInfo.from(data.get("selected"));
                    if (sel != null) {
                        sess.selectedInfo = sel;
                        RecipeEntry entry = findRecipeById(sel.id());
                        if (entry != null) {
                            sess.recipe = entry.recipe();
                            sess.recipeId = entry.id();
                        }
                    }
                }
            }
            drawMain(inv, sess);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[终刻合成台] 打开失败: " + t);
            player.sendMessage("§c终刻合成台打开失败，请找管理员。");
        }
    }

    // ------------------------------------------------------------------
    // 一键补料
    // ------------------------------------------------------------------

    /** 从玩家背包按配方补料（只补配方要求的槽位，不动玩家已放好的东西）。 */
    private static int autoFill(Player player, Inventory inv, Map<String, Object> recipe) {
        int moved = 0;
        try {
            if (player == null || inv == null || recipe == null) return 0;
            PlayerInventory pInv = player.getInventory();

            for (Object raw : Json.arr(recipe, "ingredients")) {
                ItemInfo ing = ItemInfo.from(raw);
                if (ing == null) continue;
                int slot = toInt(((Map<?, ?>) raw).get("slot"), -1);
                if (slot < 0) continue;
                int need = Math.max(1, toInt(((Map<?, ?>) raw).get("amount"), 1));

                ItemStack cur = inv.getItem(slot);
                int have = 0;
                if (cur != null && !cur.getType().isAir()) {
                    if (!sameItem(cur, ing)) continue;   // 槽里有别的物品：不覆盖
                    have = cur.getAmount();
                }
                int lack = need - have;
                if (lack <= 0) continue;

                int got = takeFromInventory(pInv, ing, lack);
                if (got <= 0) continue;
                ItemStack st = buildStack(ing, have + got);
                if (st != null) {
                    inv.setItem(slot, st);
                    moved += got;
                }
            }
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 已废弃接口
            }
        } catch (Throwable t) {
            TT.log("[终刻合成台] 一键补料异常: " + t);
        }
        return moved;
    }

    /** 从玩家背包里拿 need 个匹配物品，返回实际拿到数量。 */
    private static int takeFromInventory(PlayerInventory pInv, ItemInfo info, int need) {
        int got = 0;
        try {
            if (pInv == null || info == null || need <= 0) return 0;
            int size = pInv.getSize();
            for (int i = 0; i < size && got < need; i++) {
                ItemStack st = pInv.getItem(i);
                if (st == null || st.getType().isAir()) continue;
                if (!sameItem(st, info)) continue;

                int take = Math.min(st.getAmount(), need - got);
                got += take;
                int left = st.getAmount() - take;
                if (left <= 0) {
                    pInv.setItem(i, null);
                } else {
                    st.setAmount(left);
                    pInv.setItem(i, st);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return got;
    }

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    /** 第 9 列对 {@code out} 的可用空间总量：同类堆叠剩余空间 + 空格数 × 每组上限。 */
    private static int outputSpace(Inventory inv, ItemStack out) {
        int space = 0;
        try {
            if (inv == null || out == null) return 0;
            ItemInfo outInfo = itemInfo(out);
            for (int slot : COL9) {
                ItemStack st = inv.getItem(slot);
                if (st == null || st.getType().isAir()) {
                    space += MAX_STACK;
                    continue;
                }
                if (outInfo != null && sameItem(st, outInfo)) {
                    space += Math.max(0, MAX_STACK - st.getAmount());
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return space;
    }

    /**
     * 把成品放进第 9 列：先填同类堆叠的剩余空间，再按每组上限填空格；放不下返回 false。
     *
     * <p>原实现（{@code findOutputSlot} + {@code setAmount(min(MAX_STACK, 现有 + 产出))}）
     * 在同类堆叠剩余空间装不下整个产出时（如槽里 60 个、产出 10 个），多出的部分被
     * 静默蒸发。这里先算总容量再分配，部分填满同类堆叠、余量进空格，任何情况不丢数。</p>
     */
    private static boolean placeOutput(Inventory inv, ItemStack out) {
        try {
            if (inv == null || out == null) return false;
            if (outputSpace(inv, out) < out.getAmount()) return false;
            ItemInfo outInfo = itemInfo(out);

            int remain = out.getAmount();
            // ① 并入同类堆叠（允许只填一部分，剩余继续找槽）
            for (int slot : COL9) {
                if (remain <= 0) break;
                ItemStack st = inv.getItem(slot);
                if (st == null || st.getType().isAir()) continue;
                if (outInfo == null || !sameItem(st, outInfo)) continue;
                int free = MAX_STACK - st.getAmount();
                if (free <= 0) continue;
                int add = Math.min(remain, free);
                st.setAmount(st.getAmount() + add);
                remain -= add;
                inv.setItem(slot, st);
            }
            // ② 剩下的按每组上限进空格
            for (int slot : COL9) {
                if (remain <= 0) break;
                ItemStack st = inv.getItem(slot);
                if (st != null && !st.getType().isAir()) continue;
                int put = Math.min(remain, MAX_STACK);
                ItemStack placing = out.clone();
                placing.setAmount(put);
                inv.setItem(slot, placing);
                remain -= put;
            }
            return remain <= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void craft(Player player, Inventory inv, Session sess) {
        try {
            if (player == null || inv == null || sess == null || sess.recipe == null) {
                player.sendMessage("§c请先点第 8 列第 1 格的「查看配方」，左键查看、右键选择要合成的东西。");
                return;
            }
            Map<String, Object> rec = sess.recipe;
            List<Object> ings = Json.arr(rec, "ingredients");
            if (ings.isEmpty()) {
                player.sendMessage("§c这个配方没有记录材料。");
                return;
            }

            // ① 配方槽位：物品要对、数量要够
            String problem = null;
            Map<Integer, Boolean> used = new LinkedHashMap<>();
            for (Object raw : ings) {
                ItemInfo ing = ItemInfo.from(raw);
                if (ing == null) continue;
                int slot = toInt(((Map<?, ?>) raw).get("slot"), -1);
                if (slot < 0) continue;
                used.put(slot, Boolean.TRUE);
                int need = Math.max(1, toInt(((Map<?, ?>) raw).get("amount"), 1));

                ItemStack st = inv.getItem(slot);
                if (st == null || st.getType().isAir()) {
                    problem = slotName(slot) + " 缺少材料";
                    break;
                }
                if (!sameItem(st, ing)) {
                    problem = slotName(slot) + " 放的材料不对";
                    break;
                }
                if (st.getAmount() < need) {
                    problem = slotName(slot) + " 数量不足（需要 " + need + " 个）";
                    break;
                }
            }

            // ② 非配方槽位必须为空（强对称摆放）
            if (problem == null) {
                for (int slot : CRAFT_SLOTS) {
                    if (used.containsKey(slot)) continue;
                    ItemStack extra = inv.getItem(slot);
                    if (extra != null && !extra.getType().isAir()) {
                        problem = slotName(slot) + " 有多余物品（配方要求严格按槽位摆放）";
                        break;
                    }
                }
            }
            if (problem != null) {
                player.sendMessage("§c" + problem);
                return;
            }

            // ③ 成品与输出位
            ItemInfo resultInfo = ItemInfo.from(rec.get("result"));
            if (resultInfo == null) {
                player.sendMessage("§c成品取不出来（配方里的物品 id 可能已经变了）。");
                return;
            }
            int resultAmount = Math.max(1, toInt(Json.obj(rec, "result").get("amount"), 1));
            ItemStack out = buildStack(resultInfo, resultAmount);
            if (out == null) {
                player.sendMessage("§c成品取不出来（配方里的物品 id 可能已经变了）。");
                return;
            }
            // ③ 成品与输出位：先查总容量再动手，放不下就不扣料
            if (outputSpace(inv, out) < out.getAmount()) {
                player.sendMessage("§c第九列已经满了，先把成品取走再合成。");
                return;
            }

            // ④ 只扣配方要求的数量：多余的材料留在原槽（不能把玩家多放的材料吞了）
            for (Object raw : ings) {
                int slot = toInt(((Map<?, ?>) raw).get("slot"), -1);
                if (slot < 0) continue;
                int need = Math.max(1, toInt(((Map<?, ?>) raw).get("amount"), 1));
                ItemStack st = inv.getItem(slot);
                if (st == null || st.getType().isAir()) continue;
                int leftAmt = st.getAmount() - need;
                if (leftAmt <= 0) {
                    inv.setItem(slot, null);
                } else {
                    st.setAmount(leftAmt);
                    inv.setItem(slot, st);
                }
            }

            // ⑤ 放成品（容量上一步已预检；真放不下就退给玩家，绝不蒸发）
            if (!placeOutput(inv, out)) {
                TT.log("[终刻合成台] 成品放置失败，退给玩家: " + out);
                giveBack(player, out);
            }

            try {
                player.playSound(player.getLocation(), "block.beacon.activate", 1.0f, 1.4f);
                player.playSound(player.getLocation(), "ui.toast.challenge_complete", 0.7f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
            player.sendMessage("§a合成成功：§f" + displayName(out)
                    + (out.getAmount() > 1 ? (" §7x" + out.getAmount()) : ""));
            // 立刻落盘，防崩服丢东西
            savePlayer(player, inv, sess);
        } catch (Throwable t) {
            TT.log("[终刻合成台] 合成异常: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 查看配方（列表 / 详情）
    // ------------------------------------------------------------------

    private static void openList(Player player, int page) {
        try {
            Session sess = session(player);
            List<RecipeEntry> recs = loadRecipes();
            int total = Math.max(1, (int) Math.ceil(recs.size() / (double) PAGE_SIZE));
            page = Math.max(0, Math.min(page, total - 1));
            sess.recs = recs;
            sess.page = page;

            MenuHolder holder = new MenuHolder(MenuKind.LIST);
            Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(TITLE_LIST));
            holder.attach(inv);

            for (int i = 0; i < PAGE_SIZE; i++) {
                int idx = page * PAGE_SIZE + i;
                if (idx >= recs.size()) {
                    inv.setItem(i, grayPane());
                    continue;
                }
                RecipeEntry entry = recs.get(idx);
                ItemInfo ri = ItemInfo.from(entry.result());
                ItemStack st = (ri == null) ? null
                        : buildStack(ri, Math.max(1, toInt(entry.result().get("amount"), 1)));
                if (st == null) {
                    inv.setItem(i, button("BARRIER", "§c配方成品取不出来", List.of("§8" + entry.id())));
                    continue;
                }
                addLore(st, List.of("§7左键：查看具体配方",
                        "§7右键：选择它并一键补料", "§8" + entry.id()));
                inv.setItem(i, st);
            }

            inv.setItem(SLOT_PREV, button("ARROW", "§e上一页", null));
            inv.setItem(SLOT_PAGE, button("PAPER", "§7第 " + (page + 1) + " / " + total + " 页",
                    List.of("§7共 " + recs.size() + " 个配方")));
            inv.setItem(SLOT_BACK, button("ARROW", "§e返回上一级菜单", null));
            inv.setItem(SLOT_NEXT, button("ARROW", "§e下一页", null));
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[终刻合成台] 打开查看配方失败: " + t);
        }
    }

    private static void openDetail(Player player, RecipeEntry entry) {
        try {
            Session sess = session(player);
            sess.detail = entry;

            MenuHolder holder = new MenuHolder(MenuKind.DETAIL);
            Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(TITLE_DETAIL));
            holder.attach(inv);

            for (Object raw : entry.ingredients()) {
                ItemInfo ing = ItemInfo.from(raw);
                if (ing == null) continue;
                int slot = toInt(((Map<?, ?>) raw).get("slot"), -1);
                if (slot < 0) continue;
                ItemStack st = buildStack(ing, Math.max(1, toInt(((Map<?, ?>) raw).get("amount"), 1)));
                if (st != null) inv.setItem(slot, st);
            }
            ItemInfo ri = ItemInfo.from(entry.result());
            if (ri != null) {
                ItemStack out = buildStack(ri, Math.max(1, toInt(entry.result().get("amount"), 1)));
                if (out != null) inv.setItem(SLOT_RESULT, out);
            }
            inv.setItem(SLOT_SUB_FIRST, button("ARROW", "§e返回上一级菜单", null));
            for (int r = 2; r <= 6; r++) {
                inv.setItem(COL8[r - 1], yellowPane());
            }
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[终刻合成台] 打开具体配方失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return (int) Math.round(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** 三种菜单的持有者，用于识别「这是我们的哪张界面」。 */
    private enum MenuKind { MAIN, LIST, DETAIL }

    static final class MenuHolder implements InventoryHolder {
        final MenuKind kind;
        private Inventory inventory;

        MenuHolder(MenuKind kind) {
            this.kind = kind;
        }

        void attach(Inventory inv) { this.inventory = inv; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new MenuListener());
    }

    /** 三张界面的交互。 */
    private static final class MenuListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !(top.getHolder() instanceof MenuHolder holder)) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;
                int slot = event.getRawSlot();

                switch (holder.kind) {
                    case MAIN -> handleMain(player, top, slot, event);
                    case LIST -> handleList(player, top, slot, event);
                    case DETAIL -> handleDetail(player, top, slot, event);
                }
            } catch (Throwable t) {
                TT.log("[终刻合成台] 点击处理异常: " + t);
            }
        }

        private static void handleMain(Player player, Inventory top, int slot,
                                       InventoryClickEvent event) {
            Session sess = session(player);
            if (slot >= MENU_SIZE) {
                // 玩家自己的背包格：shift 快速放入 / 拖拽都放行，
                // 放完下一拍统一修第 8 列（只保证玩家拿不走第 8 列的东西）
                NativePluginHelper.runLater(
                        () -> fixCol8(player, top, session(player)), 1L);
                return;
            }
            if (isCol8(slot)) {
                event.setCancelled(true);
                if (slot == SLOT_BOOK) {
                    openList(player, 0);
                    return;
                }
                if (slot == SLOT_START) {
                    craft(player, top, sess);
                }
                return;
            }
            // 1-7 列 / 第 9 列：正常放取；双击会把第 8 列的按钮抓到光标上，这里拦掉
            try {
                if (event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            NativePluginHelper.runLater(() -> fixCol8(player, top, session(player)), 1L);
        }

        private static void handleList(Player player, Inventory top, int slot,
                                       InventoryClickEvent event) {
            event.setCancelled(true);
            Session sess = session(player);
            if (slot == SLOT_PREV) {
                openList(player, sess.page - 1);
                return;
            }
            if (slot == SLOT_NEXT) {
                openList(player, sess.page + 1);
                return;
            }
            if (slot == SLOT_BACK) {
                openMain(player);
                return;
            }
            if (slot < 0 || slot >= PAGE_SIZE) return;
            int idx = sess.page * PAGE_SIZE + slot;
            if (sess.recs == null || idx >= sess.recs.size()) return;
            RecipeEntry entry = sess.recs.get(idx);

            if (event.isRightClick()) {
                // 右键：选择 + 回主界面 + 按配方从背包一键补料
                sess.selectedInfo = ItemInfo.from(entry.result());
                sess.recipeId = entry.id();
                sess.recipe = entry.recipe();

                if (sess.mainInv == null) {
                    MenuHolder h = new MenuHolder(MenuKind.MAIN);
                    Inventory mainInv = Bukkit.createInventory(h, MENU_SIZE, legacy(TITLE_MAIN));
                    h.attach(mainInv);
                    sess.mainInv = mainInv;
                }
                drawMain(sess.mainInv, sess);
                int moved = autoFill(player, sess.mainInv, entry.recipe());
                player.openInventory(sess.mainInv);

                ItemInfo ri = ItemInfo.from(entry.result());
                ItemStack preview = (ri == null) ? null : buildStack(ri, 1);
                player.sendMessage("§a已选择合成物品：§f"
                        + (preview == null ? entry.id() : displayName(preview))
                        + (moved > 0 ? ("§a，已从背包补充 §f" + moved + " §a个材料")
                        : "§7（背包里没有可补的材料）"));
                return;
            }
            openDetail(player, entry);
        }

        private static void handleDetail(Player player, Inventory top, int slot,
                                         InventoryClickEvent event) {
            event.setCancelled(true);
            if (slot == SLOT_SUB_FIRST) {
                openList(player, session(player).page);
            }
        }

        /** 拖拽：主界面只拦第 8 列；列表 / 详情只读，全拦。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !(top.getHolder() instanceof MenuHolder holder)) return;
                boolean readOnly = (holder.kind != MenuKind.MAIN);

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
                    if (s == null) continue;
                    if (readOnly || isCol8(s)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 关闭主界面时落盘。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !(top.getHolder() instanceof MenuHolder holder)) return;
                if (holder.kind != MenuKind.MAIN) return;
                if (!(event.getPlayer() instanceof Player player)) return;
                savePlayer(player, top, session(player));
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 下线时落盘。 */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            try {
                Player player = event.getPlayer();
                Session sess = SESSIONS.get(player.getName());
                if (sess != null && sess.mainInv != null) savePlayer(player, sess.mainInv, sess);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        SESSIONS.clear();
    }
}
