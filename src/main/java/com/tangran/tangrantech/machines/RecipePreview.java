package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.groups.FlexItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.GuideHistory;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 配方详情预览（对齐 RSC 的 {@code MachineMenuPreviewGroup} + {@code RecipeViewListener}）。
 *
 * <p>链路与 RSC 一致：指南里点击「多物品输入 / 多物品输出」按钮（PDC 带配方下标，
 * 见 {@link TTRecipeMachine#RECIPE_INDEX_KEY}）→ 读出下标、定位机器 →
 * 按<b>机器真实菜单布局</b>铺开一条预览（输入铺输入槽、输出铺输出槽、进度槽放耗时）→
 * 玩家点返回回到机器页。</p>
 *
 * <p><b>为什么做成 {@link FlexItemGroup}</b>：预览界面本身要「混进」Slimefun 指南的
 * 历史栈（{@code profile.getGuideHistory().add(this, 0)}），这样返回按钮走
 * {@code history.goBack()} 就能精确回到机器页 —— 这是 RSC 的原设计。
 * 它从未注册进任何组，只作为历史条目存在（{@link #isVisible} 恒 false）。</p>
 */
public final class RecipePreview {

    private RecipePreview() {}

    /** RSC 的 {@code GUIDE_ITEM_OUTPUT_INDEX}：Slimefun 指南物品页固定在 16 号槽放机器物品。 */
    private static final int GUIDE_ITEM_OUTPUT_INDEX = 16;

    private static volatile boolean registered;

    /** 注册点击监听（幂等；由 {@link TTRecipeMachine} 构造时调用）。 */
    static synchronized void registerOnce() {
        if (registered) return;
        registered = true;
        Bukkit.getPluginManager().registerEvents(new ClickListener(), TT.plugin);
    }

    /** 打开某台机器的第 {@code index} 条配方预览。 */
    public static void open(Player p, TTRecipeMachine machine, int index) {
        if (p == null || machine == null) return;
        PlayerProfile profile = PlayerProfile.find(p).orElse(null);
        if (profile == null) { p.closeInventory(); return; }
        SlimefunGuideMode mode = SlimefunGuide.getDefaultMode();
        new Group(machine, index).open(p, profile, mode);
    }

    // ==================================================================
    // 点击监听（对齐 RSC RecipeViewListener）
    // ==================================================================

    private static final class ClickListener implements Listener {

        /**
         * 优先级必须是 LOW：Slimefun 内嵌的 {@code ChestMenu.MenuListener}（处理指南菜单点击）
         * 是无参 {@code @EventHandler}（NORMAL 优先级），且其 handleEvent 对返回 false 的
         * 槽位 handler 会 {@code setCancelled(true)}——我们的「多物品输入/输出」按钮走的正是
         * 空 handler（恒 false）。同优先级下按注册顺序执行（Slimefun 先启用先注册），事件
         * 到 NORMAL 阶段已被取消，{@code ignoreCancelled = true} 会把我们整个跳过。
         * RSC 的 {@code RecipeViewListener} 同样用 LOW 抢在取消之前，这是它能工作的原因。
         */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onClick(InventoryClickEvent e) {
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;
            ItemMeta meta = item.getItemMeta();
            Integer index = meta.getPersistentDataContainer()
                    .get(TTRecipeMachine.RECIPE_INDEX_KEY, PersistentDataType.INTEGER);
            if (index == null || index < 0) return;
            if (!(e.getWhoClicked() instanceof Player p)) return;

            // 指南物品页固定槽 16 放的是机器本体 —— 用它定位是哪台机器的配方。
            ItemStack machineItem = e.getInventory().getItem(GUIDE_ITEM_OUTPUT_INDEX);
            SlimefunItem sf = (machineItem == null) ? null : SlimefunItem.getByItem(machineItem);
            if (!(sf instanceof TTRecipeMachine machine)) {
                // 只在拿到配方按钮、却定位不到机器时打点（如 JEG 改版指南布局导致槽 16 不是机器物品）
                TT.log("[配方预览] 点到了配方按钮（索引 " + index + "）但定位机器失败，槽 16 物品: "
                        + (machineItem == null ? "空" : machineItem.getType().name()));
                return;
            }

            e.setCancelled(true);
            TT.log("[配方预览] " + p.getName() + " 打开 " + machine.getId() + " 第 " + index + " 条配方");
            open(p, machine, index);
        }
    }

    // ==================================================================
    // 预览界面（对齐 RSC MachineMenuPreviewGroup）
    // ==================================================================

    private static final class Group extends FlexItemGroup {

        private final TTRecipeMachine machine;
        private final int index;

        Group(TTRecipeMachine machine, int index) {
            // 这个组不进任何组树，key/图标只是占位
            super(org.bukkit.NamespacedKey.fromString("tangrantech:recipe_preview"),
                    new ItemStack(Material.STONE));
            this.machine = machine;
            this.index = index;
        }

        @Override
        public boolean isVisible(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
            return false;   // 只作为历史条目存在，不出现在指南浏览里
        }

        @Override
        public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
            profile.getGuideHistory().add(this, 0);

            List<TTRecipe> recipes = machine.getRecipes();
            if (index < 0 || index >= recipes.size()) return;
            TTRecipe recipe = recipes.get(index);

            ChestMenu inv = buildMenu(p, profile, mode, recipe);
            if (inv == null) return;
            inv.open(p);
        }

        /**
         * 按机器真实菜单布局构造预览：背景铺 {@code menu.items}，
         * 输入铺输入槽（inSlot 绑定优先）、输出铺输出槽、进度槽放耗时。
         */
        private ChestMenu buildMenu(Player p, PlayerProfile profile, SlimefunGuideMode mode, TTRecipe recipe) {
            MenuDef menu = machine.menu;

            int[] inputSlots = machine.inputSlots;
            int[] outputSlots = machine.outputSlots;
            int progressSlot = machine.progressSlot();

            // 背景槽集合（先只收集，不放置——尺寸要先算好）
            java.util.Set<Integer> placed = new java.util.HashSet<>();
            if (menu != null) placed.addAll(menu.items.keySet());

            // 功能槽集合
            java.util.Set<Integer> functional = new java.util.HashSet<>();
            for (int s : inputSlots) functional.add(s);
            for (int s : outputSlots) functional.add(s);
            if (progressSlot >= 0) functional.add(progressSlot);
            if (menu != null && menu.progressSlot >= 0) functional.add(menu.progressSlot);

            // 尺寸必须先于 ChestMenu 创建算好：单参 ChestMenu(String) 的 size = -1（自动推断模式），
            // getSize() = 9 × ceil(已登记物品数 / 9)——界面建好时只登记了少量物品就被定型成 27 格
            //（「只有三行」案）。必须用双参 ChestMenu(title, size) 显式定型，与真实机器界面一致。
            int declared = (menu != null && menu.size > 0) ? menu.size : 27;
            int maxSlot = declared - 1;
            for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
            for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
            if (progressSlot >= 0) maxSlot = Math.max(maxSlot, progressSlot);
            for (int s : placed) maxSlot = Math.max(maxSlot, s);
            int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));

            ChestMenu inv = new ChestMenu(menu != null ? menu.title
                    : Slimefun.getLocalization().getMessage(p, "guide.title.main"), size);

            // 1) 背景 / 装饰
            if (menu != null) {
                for (java.util.Map.Entry<Integer, ItemStack> en : menu.items.entrySet()) {
                    inv.addItem(en.getKey(), en.getValue(), ChestMenuUtils.getEmptyClickHandler());
                }
            }

            // 3) 空白格补背景
            for (int i = 0; i < size; i++) {
                if (!functional.contains(i) && !placed.contains(i)) {
                    inv.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
                }
            }

            // 4) 输入：inSlot 绑定优先，其余按顺序填未占用槽
            boolean[] usedInput = new boolean[inputSlots.length];
            ItemStack[] inputs = recipe.getInput();
            for (int i = 0; i < inputs.length; i++) {
                ItemStack need = inputs[i];
                if (need == null || need.getType().isAir()) continue;
                int bound = recipe.inSlot(i);
                if (bound >= 0 && bound < inputSlots.length && !usedInput[bound]) {
                    usedInput[bound] = true;
                    inv.addItem(inputSlots[bound], need.clone(), ChestMenuUtils.getEmptyClickHandler());
                }
            }
            for (int i = 0; i < inputs.length; i++) {
                ItemStack need = inputs[i];
                if (need == null || need.getType().isAir()) continue;
                if (recipe.inSlot(i) >= 0) continue;    // 绑定项上面铺过了
                int j = nextUnused(usedInput);
                if (j < 0) break;
                usedInput[j] = true;
                inv.addItem(inputSlots[j], need.clone(), ChestMenuUtils.getEmptyClickHandler());
            }

            // 5) 输出：按顺序铺，带概率的补概率 lore
            int oi = 0;
            ItemStack[] outputs = recipe.getOutput();
            for (ItemStack out : outputs) {
                if (out == null || out.getType().isAir()) continue;
                if (oi >= outputSlots.length) break;
                ItemStack give = out.clone();
                int chance = recipe.outputChance(oi);
                if (chance < 100) addLore(give, "§a有§b " + chance + "% §a的概率产出");
                inv.addItem(outputSlots[oi], give, ChestMenuUtils.getEmptyClickHandler());
                oi++;
            }

            // 6) 进度槽：显示耗时（对齐 RSC richFormatSeconds(ticks/2)）
            if (progressSlot >= 0) {
                ItemStack bar = (menu != null && menu.progressItem != null)
                        ? menu.progressItem.clone() : new ItemStack(Material.FLINT_AND_STEEL);
                addLore(bar, "§7耗时: §f" + (recipe.getTicks() / 2.0d) + " 秒");
                inv.addItem(progressSlot, bar, ChestMenuUtils.getEmptyClickHandler());
            }

            // 7) 返回按钮：优先空白玻璃板（RSC tryAddBackButton 语义）；糖然的 menus.yml 背景
            //    玻璃板多带名字（非空白），所以再放宽到「任何非功能槽的染色玻璃板背景」——
            //    装饰玻璃板不重要，覆盖掉换成返回按钮（用户指定的做法）；最后兜底未放置槽。
            int backSlot = -1;
            for (int i = 0; i < size && backSlot < 0; i++) {
                if (functional.contains(i)) continue;
                if (isBlankPane(inv.getItemInSlot(i))) backSlot = i;
            }
            for (int i = 0; i < size && backSlot < 0; i++) {
                if (functional.contains(i)) continue;
                ItemStack cur = inv.getItemInSlot(i);
                if (cur != null && cur.getType().name().endsWith("_STAINED_GLASS_PANE")) backSlot = i;
            }
            for (int i = 0; i < size && backSlot < 0; i++) {
                if (!functional.contains(i) && !placed.contains(i)) backSlot = i;
            }
            if (backSlot >= 0) {
                ItemStack back = ChestMenuUtils.getBackButton(p, "",
                        "§f左键: §7返回上一页", "§fShift + 左键: §7返回主菜单");
                inv.addItem(backSlot, back, (pl, slot, it, action) -> {
                    GuideHistory history = profile.getGuideHistory();
                    if (history.size() == 1 || action.isShiftClicked()) {
                        SlimefunGuide.openMainMenu(profile, mode, history.getMainMenuPage());
                        return false;
                    }
                    history.goBack(Slimefun.getRegistry().getSlimefunGuide(mode));
                    return false;
                });
            }

            // 8) 剩余空槽全部吞点击（对齐 RSC：预览界面里什么都不许拿）
            for (int i = 0; i < size; i++) {
                ItemStack stack = inv.getItemInSlot(i);
                if (stack == null || stack.getType() == Material.AIR) {
                    inv.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
                }
            }
            return inv;
        }

        private static int nextUnused(boolean[] used) {
            for (int i = 0; i < used.length; i++) if (!used[i]) return i;
            return -1;
        }

        /** RSC 的 isBackground：染色玻璃板 + 名字空白 + lore 空白，认为是可覆盖的背景。 */
        private static boolean isBlankPane(ItemStack stack) {
            if (stack == null || !stack.getType().name().endsWith("_STAINED_GLASS_PANE")) return false;
            if (!stack.hasItemMeta()) return false;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return false;
            String name = meta.getDisplayName();
            if (!ChatColor.stripColor(name).isBlank()) return false;
            List<String> lore = meta.getLore();
            if (lore == null || lore.isEmpty()) return true;
            for (String s : lore) if (!ChatColor.stripColor(s).isBlank()) return false;
            return true;
        }

        private static void addLore(ItemStack item, String line) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.add(LegacyComponentSerializer.legacySection().deserialize(line));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
    }
}
