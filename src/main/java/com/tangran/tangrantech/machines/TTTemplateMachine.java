package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 模板机器（template_machines.yml）：在 templateSlot 放入对应模板物品后，仅匹配该模板下的配方。
 * 不放模板则不合成；模板物品不被消耗。
 *
 * <p>两个可选加速/放大开关（原 RSC 同名配置项）：
 * <ul>
 *   <li>{@code fasterIfMoreTemplates}：模板堆叠越多速度越快；</li>
 *   <li>{@code moreOutputIfMoreTemplates}：产出数量 × 模板堆叠数。</li>
 * </ul>
 */
public class TTTemplateMachine extends TTRecipeMachine {

    private final int templateSlot;
    private final Map<String, List<TTRecipe>> byTemplate;
    private final boolean moreOutputIfMoreTemplates;
    private final boolean fasterIfMoreTemplates;
    /** 机器基础速度（yml 的 speed），用于每 tick 按模板堆叠数重算绝对速度，避免累乘。 */
    private final int baseSpeed;

    public TTTemplateMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             int[] input, int[] output, List<TTRecipe> allRecipes,
                             Map<String, List<TTRecipe>> byTemplate,
                             int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                             int templateSlot, boolean fasterIfMoreTemplates,
                             boolean moreOutputIfMoreTemplates) {
        super(group, item, rt, recipe, input, output, allRecipes, capacity, consumption, speed, menu, hideAll);
        this.templateSlot = templateSlot;
        this.byTemplate = byTemplate;
        this.fasterIfMoreTemplates = fasterIfMoreTemplates;
        this.moreOutputIfMoreTemplates = moreOutputIfMoreTemplates;
        this.baseSpeed = Math.max(1, speed);
        // super() 末尾重建 preset 时本类字段 templateSlot 尚未赋值（读到 0），
        // 导致真正的模板槽被背景封死。字段就绪后再重建一次 preset。
        createPreset(this, getInventoryTitle(), this::constructMenu);
    }

    /** 模板槽位（供外部/脚本层读取）。 */
    public int getTemplateSlot() { return templateSlot; }

    @Override
    protected Set<Integer> extraFunctionalSlots() {
        return Collections.singleton(templateSlot);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) {
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        if (tpl == null || tpl.getType().isAir()) return null;
        SlimefunItem sf = SlimefunItem.getByItem(tpl);
        if (sf == null) return null;
        List<TTRecipe> list = byTemplate.get(sf.getId().toUpperCase(java.util.Locale.ROOT));
        if (list == null || list.isEmpty()) return null;
        return matchRecipes(inv, list);
    }

    @Override
    protected void pushRecipeOutputs(Block b, BlockMenu inv, TTRecipe r) {
        if (!moreOutputIfMoreTemplates) {
            r.pushOutputs(inv, getOutputSlots());
            return;
        }
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        int mult = (tpl != null && !tpl.getType().isAir()) ? Math.max(1, tpl.getAmount()) : 1;
        r.pushOutputs(inv, getOutputSlots(), mult);
    }

    @Override
    protected void tick(Block b) {
        // fasterIfMoreTemplates：模板堆叠数加成处理速度。
        // 这里每 tick 从 baseSpeed 重算「绝对速度」，不能写成 getSpeed() * n —— getSpeed() 返回的
        // 是上一次被放大后的值，逐 tick 累乘会让速度指数级爆炸。
        if (fasterIfMoreTemplates) {
            me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv =
                    me.mrCookieSlime.Slimefun.api.BlockStorage.getInventory(b.getLocation());
            int n = 1;
            if (inv != null) {
                ItemStack tpl = inv.getItemInSlot(templateSlot);
                n = (tpl != null && !tpl.getType().isAir()) ? tpl.getAmount() : 0;
            }
            setProcessingSpeed(Math.max(1, baseSpeed * Math.max(1, n)));
        }
        super.tick(b);
    }
}
