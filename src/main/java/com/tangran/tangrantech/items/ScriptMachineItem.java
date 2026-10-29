package com.tangran.tangrantech.items;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.native_scripts.NativeScript;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 脚本驱动的方块机器（machines.yml）。
 *
 * <p>机器的 GUI 由 {@code menus.yml} 中同 id 的菜单定义；工作逻辑（tick）与交互（onUse）
 * 全部交给绑定的原生脚本。</p>
 *
 * <p>注意：本类继承 {@link SlimefunItem} 而非 {@code AContainer}——它没有走原生
 * 容器机器的能量/进度基建，因此没有 {@code getInventoryTitle()}；标题直接取物品显示名。</p>
 */
public class ScriptMachineItem extends SlimefunItem implements EnergyNetComponent, InventoryBlock {

    private final NativeScript script;
    private final int capacity;
    private final String energyType;
    private final int[] input;
    private final int[] output;

    public ScriptMachineItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             NativeScript script, int capacity, String energyType,
                             int[] input, int[] output) {
        this(group, item, rt, recipe, script, capacity, energyType, input, output, true);
    }

    public ScriptMachineItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             NativeScript script, int capacity, String energyType,
                             int[] input, int[] output, boolean tick) {
        super(group, item, rt, recipe);
        this.script = script;
        this.capacity = capacity;
        this.energyType = energyType == null ? "CONSUMER" : energyType;
        this.input = input;
        this.output = output;
        createPreset(this, getInventoryTitle(), this::constructMenu);
        // tick: false 的机器（如注能器——纯投放物交互，没有任何每刻逻辑）
        // 不进 Slimefun 的 tick 周期，省一份每刻调度
        if (tick) installTicker();
    }

    /**
     * 安装 tick 调度。
     *
     * <p>原 JS 脚本在求值阶段自行注册监听；原生化后统一由这里补上 {@link BlockTicker}，
     * 脚本只实现 tick 逻辑。{@code SlimefunBlockData} 版本的 tick 是同步通道，
     * 先按方块坐标取菜单，取不到就本轮跳过（机器被破坏/未加载）。</p>
     *
     * <p><b>例外</b>：脚本若声明 {@link NativeScript#needsMenu()} 为 {@code false}
     * （界面自建、没有 menus.yml 条目的脚本机器），
     * 那么取不到菜单时<b>照样把 tick 递下去</b>，菜单参数传 {@code null}。
     * 否则这类机器一旦没被建出 BlockMenu 就会彻底不工作。</p>
     */
    private void installTicker() {
        addItemHandler(new BlockTicker() {
            @Override
            public void tick(Block b, SlimefunItem item,
                             com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData data) {
                BlockMenu menu = me.mrCookieSlime.Slimefun.api.BlockStorage.getInventory(b.getLocation());
                if (menu != null || (script != null && !script.needsMenu())) {
                    tickBlock(b, menu);
                }
            }

            @Override
            public boolean isSynchronized() {
                return true;
            }
        });
    }

    /**
     * 每刻把「方块 + 菜单」打包交给脚本的 {@code tick}。
     *
     * <p>{@code inv} 允许为 {@code null}：只对 {@link NativeScript#needsMenu()} 为
     * {@code false} 的脚本才会这样调用，它们自己知道不需要菜单。</p>
     */
    public void tickBlock(Block b, BlockMenu inv) {
        if (script == null) return;
        try {
            script.invoke("tick", new TickInfo(b, inv, false));
        } catch (Throwable t) {
            TT.log("脚本 " + script.getClass().getSimpleName() + " tick 出错: " + t);
        }
    }

    /** 本机器绑定的脚本实例。 */
    public NativeScript getScript() { return script; }

    /** 输入槽位。 */
    public int[] getInputSlots() { return input; }

    /** 输出槽位。 */
    public int[] getOutputSlots() { return output; }

    /** 交互派发：右键事件交给脚本的 {@code onUse}。 */
    public void onRightClick(org.bukkit.event.player.PlayerInteractEvent event) {
        if (script == null) return;
        try {
            script.invoke("onUse", event);
        } catch (Throwable t) {
            TT.log("脚本 " + script.getClass().getSimpleName() + " onUse 出错: " + t);
        }
    }

    /** GUI 标题：本类非容器机器，没有 getInventoryTitle()，直接取物品显示名。 */
    public String getInventoryTitle() {
        String n = getItemName();
        return (n == null || n.isEmpty()) ? getId() : n;
    }

    @Override
    public int getCapacity() { return Math.max(1, capacity); }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        // 未配 energy（capacity=0）= 非耗电机器（注能器/甘霖神龛/命轨织机等）：
        // 不进能源网络，Slimefun 不再为它们做能源网络登记与查询
        if (capacity <= 0) return EnergyNetComponentType.NONE;
        if ("GENERATOR".equalsIgnoreCase(energyType)) return EnergyNetComponentType.GENERATOR;
        if ("CAPACITOR".equalsIgnoreCase(energyType)) return EnergyNetComponentType.CAPACITOR;
        return EnergyNetComponentType.CONSUMER;
    }

    protected void constructMenu(BlockMenuPreset preset) {
        MenuDef menu = TT.menus.get(getId().toUpperCase(Locale.ROOT));
        if (menu == null) {
            // 无 menus.yml 条目：没有装饰可放，但预设必须仍覆盖全部功能槽——
            // ChestMenu 的真实尺寸只随「实际放置的物品」自动增长，不显式 setSize 的话
            // 预设会停在默认尺寸，输出槽超出即越界（同 TTRecipeMachine 的教训）
            int maxSlot = 26;
            for (int s : input) maxSlot = Math.max(maxSlot, s);
            for (int s : output) maxSlot = Math.max(maxSlot, s);
            preset.setSize(Math.min(54, Math.max(27, ((maxSlot / 9) + 1) * 9)));
            return;
        }

        // 进度槽与输入/输出槽重叠时弃用（与 TTRecipeMachine 同一防御）：
        // MenuDef.progressSlot 默认 22，而哈气收集器 / 甘霖神龛 / 轨迹织机的 output
        // 恰好覆盖 22。不拦的话进度占位（打火石）会赖在输出槽里 —— GUI 多一块垃圾，
        // 且脚本按「空槽」找输出位时永远跳过它，凭空少一个可用输出槽。
        // 冲突时先回退到 9（内容侧已查验这些机器的 9 槽空闲），9 也被占用才禁用。
        int pslot = menu.progressSlot;
        if (pslot >= 0 && overlapsFunctional(pslot)) pslot = 9;
        if (pslot >= 0 && overlapsFunctional(pslot)) {
            TT.log(getId() + " 的进度槽 " + pslot + " 与输入/输出槽重叠，无法展示进度，已禁用进度占位");
            pslot = -1;
        }

        Set<Integer> placed = new HashSet<>();
        for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
            preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
            placed.add(e.getKey());
        }

        Set<Integer> functional = new HashSet<>();
        for (int s : input) functional.add(s);
        for (int s : output) functional.add(s);
        if (pslot >= 0) functional.add(pslot);

        int declared = (menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : input) maxSlot = Math.max(maxSlot, s);
        for (int s : output) maxSlot = Math.max(maxSlot, s);
        if (pslot >= 0) maxSlot = Math.max(maxSlot, pslot);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        // ★ 显式 setSize（向上取整到 9 的倍数）：装饰只盖住声明的槽位，而输入/输出槽
        // 不放物品——声明装饰不在顶部行时，预设实际尺寸会小于功能槽（同 TTRecipeMachine
        // 的教训）。显式设一次，保证 inventory 覆盖全部功能槽。
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

        // menus.yml 显式配置的 progressbar 槽位是内容侧的刻意安排（如甘霖神龛/命轨织机的 8 号槽），
        // 即使该槽位已有装饰物品也要覆盖；默认回退槽（22/9）仍让位给装饰
        if (pslot >= 0 && (menu.progressFromConfig || !placed.contains(pslot))) {
            preset.addItem(pslot, menu.progressItem != null
                    ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL),
                    ChestMenuUtils.getEmptyClickHandler());
        }
    }

    /** 候选进度槽是否与输入/输出槽重叠（重叠则进度占位会赖在功能槽里）。 */
    private boolean overlapsFunctional(int slot) {
        if (slot < 0) return false;
        for (int s : input) if (s == slot) return true;
        for (int s : output) if (s == slot) return true;
        return false;
    }

    /** 交给脚本 tick 的上下文（字段名与原 JS 里访问的一致）。 */
    public static final class TickInfo {
        public final Block block;
        public final BlockMenu menu;
        public final boolean async;

        TickInfo(Block block, BlockMenu menu, boolean async) {
            this.block = block;
            this.menu = menu;
            this.async = async;
        }

        public Block block() { return block; }
        public BlockMenu menu() { return menu; }
    }
}
