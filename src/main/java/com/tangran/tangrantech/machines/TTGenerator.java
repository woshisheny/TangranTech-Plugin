package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AGenerator;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineFuel;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 发电机（generators.yml）。继承 Slimefun 原生 {@link AGenerator}：
 * 在输入槽放入燃料后按 {@code production}（J/tick）发电，燃料可有副产物（如铀→镎）。
 *
 * <p>两个继承自父类的坑，这里必须绕开：</p>
 * <ol>
 *   <li><b>燃料不能在 {@code registerDefaultFuelTypes()} 里注册。</b>该方法由
 *       {@link AGenerator} 构造器末尾调用，而 Java 初始化顺序是「先跑 super() 再赋值子类字段」，
 *       那时本类的 {@code fuels} 还是 null。Slimefun 自带发电机不受影响是因为它们用常量燃料表。
 *       改为构造器返回后由 loader 调 {@link #registerFuels(List)} 下发。</li>
 *   <li><b>不能无脑重建 preset。</b>{@link AGenerator} 的 GUI 绘制方法 {@code constructMenu}
 *       是 <em>private</em>，本类同名方法不会覆盖它；若 menus.yml 里没有本机器条目就直接
 *       {@code createPreset} 覆盖，会把父类画好的边框/输入槽贴图/进度条全冲掉。
 *       因此只在确实存在 {@link MenuDef} 时才重建。</li>
 * </ol>
 */
public class TTGenerator extends AGenerator {

    private final int[] input;
    private final int[] output;
    private final int capacity;
    private final int production;
    private final MenuDef menu;

    public TTGenerator(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                       int[] input, int[] output, int capacity, int production, MenuDef menu) {
        super(group, item, rt, recipe);
        this.input = input;
        this.output = output;
        this.capacity = capacity;
        this.production = production;
        this.menu = menu;
        setCapacity(Math.max(1, capacity));
        setEnergyProduction(Math.max(0, production));
        if (menu != null) {
            // init() 由 BlockMenuPreset 构造器同步调用，此处字段已完整
            createPreset(this, getInventoryTitle(), this::constructMenu);
        }
    }

    @Override
    public int[] getInputSlots() {
        // ★ 空值守卫：AGenerator 的构造器会自建预设并调 constructMenu，其中经虚方法
        //   getOutputSlots() 取输出槽——此刻本类字段尚未赋值（super 还没返回），
        //   返回 null 会让父类的 for 循环 NPE（TR_CJHFYD 注册失败案）。
        //   super 阶段返回空数组即可（自建预设随后会被本类构造器末尾的 createPreset 覆盖）。
        return (input == null) ? new int[0] : input;
    }

    @Override
    public int[] getOutputSlots() {
        return (output == null) ? new int[0] : output;
    }

    @Override
    public int getCapacity() { return Math.max(1, capacity); }

    @Override
    public int getEnergyProduction() { return production; }

    @Override
    public ItemStack getProgressBar() {
        return (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
    }

    /**
     * 故意留空：燃料由 {@link #registerFuels(List)} 在构造完成后下发。
     *
     * <p>父类会在构造器末尾调用本方法，那时子类字段尚未初始化（详见类注释第 1 条）。</p>
     */
    @Override
    protected void registerDefaultFuelTypes() { }

    /**
     * 注册燃料表。必须在构造器返回后、{@code register()} 之前调用。
     *
     * @param fuels 燃料列表；null 或空表示无燃料
     */
    public void registerFuels(List<MachineFuel> fuels) {
        if (fuels == null) return;
        for (MachineFuel f : fuels) {
            if (f != null) registerFuel(f);
        }
    }

    /** 按 menus.yml 布局绘制 GUI；无 menus.yml 条目时退化为「只保证覆盖功能槽」。 */
    protected void constructMenu(BlockMenuPreset preset) {
        if (menu == null) {
            int maxSlot = 26;
            for (int s : input) maxSlot = Math.max(maxSlot, s);
            for (int s : output) maxSlot = Math.max(maxSlot, s);
            preset.setSize(Math.min(54, Math.max(27, ((maxSlot / 9) + 1) * 9)));
            return;
        }
        Set<Integer> placed = new HashSet<>();
        for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
            preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
            placed.add(e.getKey());
        }

        Set<Integer> functional = new HashSet<>();
        for (int s : input) functional.add(s);
        for (int s : output) functional.add(s);
        if (menu.progressSlot >= 0) functional.add(menu.progressSlot);

        // 尺寸必须覆盖所有功能槽：BlockMenuPreset 按「已放置物品」自动定尺寸，
        // 而输出槽只挂 click handler、不放置物品，超出自动尺寸会越界。
        int declared = (menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : input) maxSlot = Math.max(maxSlot, s);
        for (int s : output) maxSlot = Math.max(maxSlot, s);
        if (menu.progressSlot >= 0) maxSlot = Math.max(maxSlot, menu.progressSlot);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        // ★ 显式 setSize（向上取整到 9 的倍数）：同 TTRecipeMachine 的教训——
        // 装饰不盖住顶部行时预设实际尺寸会小于功能槽，取输出槽即越界。
        preset.setSize(Math.min(54, Math.max(9, ((size + 8) / 9) * 9)));

        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }

        // 输出槽允许取出（必须用 Advanced 版本才能拿到光标物品判断空手提起）
        for (int i : output) {
            preset.addMenuClickHandler(i,
                    new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                        @Override
                        public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e,
                                               org.bukkit.entity.Player player, int slot, ItemStack cursor,
                                               me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                            return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                        }

                        @Override
                        public boolean onClick(org.bukkit.entity.Player player, int slot, ItemStack cursor,
                                               me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                            return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                        }
                    });
        }

        if (menu.progressSlot >= 0 && !placed.contains(menu.progressSlot)) {
            preset.addItem(menu.progressSlot, getProgressBar(), ChestMenuUtils.getEmptyClickHandler());
        }
    }
}
