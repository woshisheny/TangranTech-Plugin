package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 物质生成器 / 造物匣（mat_generators.yml，130 条）。
 *
 * <p>对齐 RSC {@code CustomMaterialGenerator}：无输入槽，机器每 {@code tickRate} 刻消耗
 * {@code per} 点能量，把 {@code outputs}/{@code outputItem} 产出推入 output 槽位。
 * {@code chooseOne=true} 时从产出列表中随机挑一个，否则全部产出；每项可带 {@code chance} 概率。
 */
public class TTMaterialGenerator extends TTRecipeMachine {

    /** 产出槽位中用于显示机器状态的槽位（-1 = 无状态槽）。 */
    private final int statusSlot;

    public TTMaterialGenerator(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                               int[] input, int[] output, List<TTRecipe> recipes,
                               int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                               int statusSlot) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        this.statusSlot = statusSlot;
        // 状态槽需要吞掉点击（对齐 RSC onNewInstance：menu.addMenuClickHandler(status, emptyHandler)）
        createPreset(this, getInventoryTitle(), this::constructMenu);
    }

    /** 状态槽（运行期显示机器状态用）；-1 表示未配置。 */
    public int getStatusSlot() { return statusSlot; }

    @Override
    protected java.util.Set<Integer> extraFunctionalSlots() {
        return statusSlot >= 0 ? java.util.Collections.singleton(statusSlot)
                : java.util.Collections.emptySet();
    }

    @Override
    public List<ItemStack> getDisplayRecipes() {
        return super.getDisplayRecipes();
    }
}
