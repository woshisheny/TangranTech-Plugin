package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * 物质生成器 / 造物匣（mat_generators.yml，130 条）。
 *
 * <p>对齐 RSC {@code CustomMaterialGenerator}：无输入槽，机器每 {@code tickRate} 刻消耗
 * {@code per} 点能量，把 {@code outputs}/{@code outputItem} 产出推入 output 槽位。
 * {@code chooseOne=true} 时从产出列表中随机挑一个，否则全部产出；每项可带 {@code chance} 概率。</p>
 *
 * <p>配置里的 {@code status: 0} 槽位就是 menus.yml 的 0 号装饰板（绿玻璃板，带空点击
 * 处理器），不做任何状态显示，也<b>不参与破坏掉落</b>——曾因把它算进
 * {@code extraFunctionalSlots} 导致每次拆机器都掉出一块绿玻璃板（2026-10-29 修复）。</p>
 */
public class TTMaterialGenerator extends TTRecipeMachine {

    public TTMaterialGenerator(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                               int[] input, int[] output, List<TTRecipe> recipes,
                               int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        createPreset(this, getInventoryTitle(), this::constructMenu);
    }

}
