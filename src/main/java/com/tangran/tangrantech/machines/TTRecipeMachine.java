package com.tangran.tangrantech.machines;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 电力配方机器（recipe_machines.yml）。继承 {@link AContainer} 复用能量/进度/GUI 基建，
 * 自定义 {@link #findNextRecipe} 与 {@link #tick} 以支持 {@link TTRecipe} 的
 * 概率产出、noConsume 与工具耐久消耗。
 */
public class TTRecipeMachine extends AContainer implements RecipeDisplayItem, PowerConsumer {

    protected final int[] inputSlots;
    protected final int[] outputSlots;
    /** GUI 槽 → 在 inputSlots 中的位置（-1 = 非输入槽）。构造期预算，避免每 tick 重建 Map。 */
    protected final int[] posBySlot;
    protected final List<TTRecipe> recipes;
    protected final MenuDef menu;
    protected final boolean hideAll;
    protected final ItemStack progressBar;
    /** 解析后的进度槽；与输入/输出槽冲突时为 -1。 */
    protected final int progressSlotResolved;
    /** 进行中配方（按方块），完成时取此处做概率滚动。 */
    protected final Map<org.bukkit.Location, TTRecipe> active = new ConcurrentHashMap<>();

    /** 全部已注册实例（加载收尾统一剔除引用禁用物品的配方）。 */
    private static final List<TTRecipeMachine> INSTANCES = new ArrayList<>();

    public TTRecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                           int[] inputSlots, int[] outputSlots, List<TTRecipe> recipes,
                           int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.posBySlot = buildPosBySlot(inputSlots);
        // 复制一份可变副本：加载收尾会按「引用禁用物品」剔除条目，入参可能是不可变列表
        this.recipes = (recipes == null) ? new ArrayList<>() : new ArrayList<>(recipes);
        this.menu = menu;
        this.hideAll = hideAll;
        INSTANCES.add(this);
        this.progressBar = (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
        // 进度槽冲突防御：菜单漏配 progressbar 时回退 22，而某些大型工作台输入槽恰好覆盖 22，
        // 默认进度占位会叠进输入格成为「来路不明又点不走的物品」。重叠即弃用并告警。
        int ps = (menu != null && menu.progressSlot >= 0) ? menu.progressSlot : 22;
        if (overlapsFunctional(ps)) {
            TT.log(getId() + " 的进度槽 " + ps + " 与输入/输出槽重叠（menus.yml 缺 progressbar 配置？），已禁用进度占位");
            ps = -1;
        }
        this.progressSlotResolved = ps;
        setProcessingSpeed(Math.max(1, speed));
        setCapacity(Math.max(1, capacity));
        setEnergyConsumption(Math.max(1, Math.min(consumption, Math.max(1, capacity))));
        // AContainer 在 super() 中已用 this::constructMenu 建过 preset（此时字段尚未赋值），
        // 这里字段就绪后重建 preset（覆盖前一个），并补设进度条。
        createPreset(this, getInventoryTitle(), this::constructMenu);
        getMachineProcessor().setProgressBar(progressBar);
    }

    @Override
    public int getConsumption() { return getEnergyConsumption(); }

    @Override
    public int[] getInputSlots() { return inputSlots; }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    /** 全部配方（供大配方菜单展示与运行期筛选）。 */
    public List<TTRecipe> getRecipes() { return recipes; }

    @Override
    public String getMachineIdentifier() { return getId(); }

    @Override
    public ItemStack getProgressBar() { return progressBar; }

    @Override
    protected void registerDefaultRecipes() { /* 配方由本类自行管理 */ }

    /** 预算「GUI 槽 → 输入位」映射：数组下标即槽号，值为在 inputSlots 中的位置，-1 表示非输入槽。 */
    private static int[] buildPosBySlot(int[] inputSlots) {
        if (inputSlots == null || inputSlots.length == 0) return new int[0];
        int max = -1;
        for (int s : inputSlots) max = Math.max(max, s);
        if (max < 0) return new int[0];
        int[] map = new int[max + 1];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < inputSlots.length; i++) {
            int s = inputSlots[i];
            if (s >= 0 && s < map.length) map[s] = i;
        }
        return map;
    }

    /**
     * 候选进度槽是否与输入/输出槽重叠。
     *
     * <p>只比对输入/输出：进度槽在赋值前调用本方法，此时 {@code progressSlotResolved}
     * 仍是 blank final，读它会触发「可能尚未初始化」编译错误。</p>
     */
    private boolean overlapsFunctional(int slot) {
        if (slot < 0) return false;
        for (int s : inputSlots) if (s == slot) return true;
        for (int s : outputSlots) if (s == slot) return true;
        return false;
    }

    /**
     * 完成一次合成后的产出推送。子类（如模板机器）可覆盖以按模板堆叠数放大产出。
     *
     * <p>{@link #tick(Block)} 必须走这里而不是直接调 {@code recipe.pushOutputs(...)}，
     * 否则子类的放大逻辑不会生效。</p>
     */
    protected void pushRecipeOutputs(Block b, BlockMenu inv, TTRecipe r) {
        if (r != null) r.pushOutputs(inv, outputSlots);
    }

    /** 进度槽（子类可覆盖；返回 -1 表示无进度显示）。 */
    protected int progressSlot() { return progressSlotResolved; }

    /** 额外的可交互槽位（不会被背景填充阻挡），子类可覆盖。 */
    protected Set<Integer> extraFunctionalSlots() { return java.util.Collections.emptySet(); }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // super() 阶段会提前调用一次（字段为 null），此时跳过；由构造器末尾重建 preset 时再真正构建。
        if (inputSlots == null || outputSlots == null) return;

        Set<Integer> placed = new java.util.HashSet<>();
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }

        Set<Integer> functional = new java.util.HashSet<>();
        for (int s : inputSlots) functional.add(s);
        for (int s : outputSlots) functional.add(s);
        int pslot = progressSlot();
        if (pslot >= 0) functional.add(pslot);
        Set<Integer> extra = extraFunctionalSlots();

        // 尺寸必须覆盖所有功能槽与装饰槽：BlockMenuPreset 按「已放置物品」自动定尺寸，
        // 而输出槽只挂 click handler、不放置物品。若某功能槽超出自动尺寸，运行期
        // getItemInSlot/consumeItem 会越界。此处按最大槽位向上取整到 9 的倍数（≤54）。
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
        for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
        if (pslot >= 0) maxSlot = Math.max(maxSlot, pslot);
        for (int s : extra) maxSlot = Math.max(maxSlot, s);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));

        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i) && !extra.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }

        // 输出槽允许取出：必须用 AdvancedMenuClickHandler——普通 MenuClickHandler 的第三参数是
        // e.getCurrentItem()（槽内物品），左键提起会被误判为放入而取消；Advanced 版本才能拿到
        // 玩家光标 e.getCursor()（空手点击 = AIR → 放行提起）。
        for (int i : outputSlots) {
            preset.addMenuClickHandler(i, new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, org.bukkit.entity.Player player,
                                       int slot, ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }

                @Override
                public boolean onClick(org.bukkit.entity.Player player, int slot, ItemStack cursor,
                                       me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }
            });
        }

        if (pslot >= 0 && !placed.contains(pslot)) {
            preset.addItem(pslot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), inputSlots);
                    inv.dropItems(b.getLocation(), outputSlots);
                    Set<Integer> extra = extraFunctionalSlots();
                    if (!extra.isEmpty()) {
                        int[] extraSlots = extra.stream().mapToInt(Integer::intValue).toArray();
                        inv.dropItems(b.getLocation(), extraSlots);
                    }
                }
                active.remove(b.getLocation());
                getMachineProcessor().endOperation(b);
            }
        };
    }

    @Override
    protected void tick(Block b) {
        BlockMenu inv = BlockStorage.getInventory(b.getLocation());
        if (inv == null) return;
        int pslot = progressSlot();
        CraftingOperation op = getMachineProcessor().getOperation(b);
        if (op == null) {
            MachineRecipe next = findNextRecipe(inv);
            if (next instanceof TTRecipe tr) {
                op = new CraftingOperation(next);
                getMachineProcessor().startOperation(b, op);
                active.put(b.getLocation(), tr);
                if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            }
            return;
        }
        if (!takeCharge(b.getLocation())) return;
        if (!op.isFinished()) {
            if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            op.addProgress(getSpeed());
            return;
        }
        TTRecipe r = active.remove(b.getLocation());
        // 走 pushRecipeOutputs 而非直接 pushOutputs，保证子类（模板机器）的放大逻辑生效
        pushRecipeOutputs(b, inv, r);
        if (pslot >= 0) inv.replaceExistingItem(pslot, progressBar);
        getMachineProcessor().endOperation(b);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) { return matchRecipes(inv, recipes); }

    /** 在给定配方列表中匹配并消耗输入。 */
    protected MachineRecipe matchRecipes(BlockMenu inv, List<TTRecipe> recipeList) {
        Match m = findMatch(inv, recipeList);
        if (m == null) return null;
        consumeMatch(inv, m);
        return m.recipe;
    }

    /** 匹配结果：命中的配方 + 各输入项选中的输入槽位置。 */
    protected static final class Match {
        final TTRecipe recipe;
        final int[] chosen;
        Match(TTRecipe recipe, int[] chosen) { this.recipe = recipe; this.chosen = chosen; }
    }

    /** 仅匹配与校验，不消耗输入。 */
    protected Match findMatch(BlockMenu inv, List<TTRecipe> recipeList) {
        int[] slots = inputSlots;
        int slotCount = slots.length;
        ItemStack[] slotItems = new ItemStack[slotCount];
        for (int s = 0; s < slotCount; s++) {
            ItemStack it = inv.getItemInSlot(slots[s]);
            slotItems[s] = (it == null) ? null : ItemStackWrapper.wrap(it);
        }
        // 所有输入槽为空时不可能命中任何配方（注册配方至少含 1 个非空输入），直接返回
        boolean anyInput = false;
        for (ItemStack si : slotItems) {
            if (si != null) { anyInput = true; break; }
        }
        if (!anyInput) return null;

        boolean[] claimed = new boolean[slotCount];
        for (TTRecipe recipe : recipeList) {
            ItemStack[] inputs = recipe.getInput();
            int n = inputs.length;
            int[] chosen = new int[n];
            int matched = 0;
            boolean failed = false;
            Arrays.fill(claimed, false);
            for (int i = 0; i < n; i++) {
                chosen[i] = -1;
                ItemStack need = inputs[i];
                if (need == null) { matched++; continue; }
                int bound = recipe.inSlot(i);
                if (bound >= 0) {
                    int pos = (bound < posBySlot.length) ? posBySlot[bound] : -1;
                    if (pos < 0 || claimed[pos]) { failed = true; break; }
                    ItemStack in = slotItems[pos];
                    if (matches(in, need, recipe, i)) {
                        chosen[i] = pos;
                        claimed[pos] = true;
                        matched++;
                    } else { failed = true; break; }
                } else {
                    // best-fit：选余量最小的可用槽，把大堆留给需求量更大的输入项
                    int best = -1;
                    int bestAmount = Integer.MAX_VALUE;
                    for (int s = 0; s < slotCount; s++) {
                        if (claimed[s]) continue;
                        ItemStack in = slotItems[s];
                        if (!matches(in, need, recipe, i)) continue;
                        if (in.getAmount() < bestAmount) {
                            best = s;
                            bestAmount = in.getAmount();
                            if (bestAmount == need.getAmount()) break;
                        }
                    }
                    if (best >= 0) {
                        chosen[i] = best;
                        claimed[best] = true;
                        matched++;
                    } else { failed = true; break; }
                }
            }
            if (failed || matched != n) continue;
            // 同一物理槽不允许被多个配方位消耗（总量足够也不共享），防止越量消耗吞物品
            int distinct = 0;
            for (int i = 0; i < n; i++) {
                boolean dup = false;
                for (int j = 0; j < i; j++) if (chosen[i] == chosen[j]) { dup = true; break; }
                if (!dup) distinct++;
            }
            if (distinct != n) continue;
            // 输出放不下时跳过本配方尝试下一个，不整体放弃
            if (!InvUtils.fitAll(inv.toInventory(), recipe.getOutput(), outputSlots)) continue;
            // tick 可能异步：消耗前对选中槽位的实时内容再校验，避免竞态吞错物品
            boolean stillValid = true;
            for (int i = 0; i < n; i++) {
                if (recipe.isNoConsume(i) || chosen[i] < 0) continue;
                ItemStack live = inv.getItemInSlot(slots[chosen[i]]);
                if (!matches(live, inputs[i], recipe, i)) { stillValid = false; break; }
            }
            if (!stillValid) continue;
            return new Match(recipe, chosen);
        }
        return null;
    }

    /** 单项输入比较：数量足够 + 类型相似；工具类（damage>0）忽略耐久。 */
    private static boolean matches(ItemStack in, ItemStack need, TTRecipe recipe, int i) {
        if (in == null || in.getAmount() < need.getAmount()) return false;
        if (recipe.inputDamage(i) > 0) return toolSimilarIgnoreDamage(in, need);
        return SlimefunUtils.isItemSimilar(in, need, true);
    }

    /** 耐久类工具输入比较：忽略损耗值；粘液物品按 id 一致。 */
    private static boolean toolSimilarIgnoreDamage(ItemStack in, ItemStack need) {
        if (in == null || in.getType() != need.getType()) return false;
        SlimefunItem sfNeed = SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            SlimefunItem sfIn = SlimefunItem.getByItem(in);
            return sfIn != null && sfIn.getId().equals(sfNeed.getId());
        }
        if (need.hasItemMeta() && need.getItemMeta().hasDisplayName()) {
            org.bukkit.inventory.meta.ItemMeta m = in.getItemMeta();
            return m != null && m.hasDisplayName()
                    && m.getDisplayName().equals(need.getItemMeta().getDisplayName());
        }
        return true;
    }

    /** 消耗已匹配配方的输入（跳过 noConsume；damage>0 走耐久扣点）。 */
    protected void consumeMatch(BlockMenu inv, Match m) {
        if (m == null) return;
        ItemStack[] inputs = m.recipe.getInput();
        for (int i = 0; i < inputs.length; i++) {
            if (m.recipe.isNoConsume(i) || m.chosen[i] < 0) continue;
            int damage = m.recipe.inputDamage(i);
            int slot = inputSlots[m.chosen[i]];
            if (damage > 0) damageTool(inv, slot, damage);
            else inv.consumeItem(slot, inputs[i].getAmount());
        }
    }

    /**
     * 加载收尾统一清理：剔除「引用了被禁用/未加载物品」的配方。
     *
     * <p>yml 里的配方可以互相引用，若某物品因条件未满足而未注册，直接按引用取物品会拿 null，
     * 运行期匹配时空指针会把整台机器拖死。这里统一在下发之前剔除。</p>
     */
    public static void filterDisabledRecipes() {
        int removed = 0;
        for (TTRecipeMachine m : INSTANCES) {
            if (m.recipes == null) continue;
            int before = m.recipes.size();
            m.recipes.removeIf(TTRecipe::referencesDisabledItem);
            removed += before - m.recipes.size();
        }
        if (removed > 0) {
            TT.log("已剔除 " + removed + " 条引用禁用/缺失物品的机器配方");
        }
    }

    private static void damageTool(BlockMenu inv, int slot, int damage) {
        ItemStack item = inv.getItemInSlot(slot);
        if (item == null || item.getType().isAir()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable damageable)
                || item.getType().getMaxDurability() <= 0 || meta.isUnbreakable()) {
            return;
        }
        int newDamage = damageable.getDamage() + damage;
        if (newDamage >= item.getType().getMaxDurability()) {
            inv.replaceExistingItem(slot, null);
            return;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = item.clone();
        updated.setItemMeta(damageable);
        inv.replaceExistingItem(slot, updated);
    }
}
