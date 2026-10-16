package com.tangran.tangrantech.items;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import org.bukkit.inventory.ItemStack;

/** 不可放置的普通物品（对应 RSC 默认 CustomUnplaceableItem，即 placeable 未置 true 时）。 */
public class TTUnplaceableItem extends SlimefunItem implements NotPlaceable {
    public TTUnplaceableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }
}
