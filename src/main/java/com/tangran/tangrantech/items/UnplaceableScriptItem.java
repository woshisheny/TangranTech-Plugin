package com.tangran.tangrantech.items;

import com.tangran.tangrantech.native_scripts.NativeScript;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import org.bukkit.inventory.ItemStack;

/** 绑定了行为脚本、且不可放置的物品（placeable 未置 true 时的默认形态）。 */
public class UnplaceableScriptItem extends ScriptItem implements NotPlaceable {

    public UnplaceableScriptItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                                 NativeScript script) {
        super(group, item, rt, recipe, script);
    }
}
