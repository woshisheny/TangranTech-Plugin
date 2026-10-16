package com.tangran.tangrantech.items;

import com.tangran.tangrantech.native_scripts.NativeScript;
import com.tangran.tangrantech.native_scripts.NativeScriptRegistry;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

/**
 * 按 {@link ItemSpec}（脚本 + 属性）分派物品子类。
 *
 * <p>对齐 RSC ItemReader 的分支：写了 {@code script:} 的条目绑定到原生脚本实现，
 * 其余按 {@code placeable} 决定可否放置。</p>
 */
public final class ScriptItemFactory {

    private ScriptItemFactory() {}

    public static SlimefunItem create(ItemSpec spec, ItemGroup group, SlimefunItemStack sfis,
                                      RecipeType rt, ItemStack[] recipe) {
        if (spec.script != null && !spec.script.isEmpty()) {
            NativeScript script = NativeScriptRegistry.get(spec.script);
            if (script != null) {
                return spec.placeable
                        ? new ScriptItem(group, sfis, rt, recipe, script)
                        : new UnplaceableScriptItem(group, sfis, rt, recipe, script);
            }
        }
        return spec.placeable
                ? new TTItem(group, sfis, rt, recipe)
                : new TTUnplaceableItem(group, sfis, rt, recipe);
    }
}
