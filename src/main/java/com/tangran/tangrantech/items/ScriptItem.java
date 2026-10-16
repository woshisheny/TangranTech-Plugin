package com.tangran.tangrantech.items;

import com.tangran.tangrantech.native_scripts.NativeScript;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

/**
 * 绑定了行为脚本的物品：右键（PlayerInteractEvent）时把事件转发给脚本的 {@code onUse}。
 *
 * <p>对齐 RSC：RSC 在物品段读到 {@code script:} 后，由脚本的
 * {@code function onUse(event)} 处理交互。原生化后同样保留这个回调契约，
 * 由 {@code native_scripts} 包下的 Java 实现类提供具体逻辑。</p>
 */
public class ScriptItem extends SlimefunItem {

    private final NativeScript script;

    public ScriptItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                      NativeScript script) {
        super(group, item, rt, recipe);
        this.script = script;
    }

    /** 本物品绑定的脚本实例。 */
    public NativeScript getScript() { return script; }

    /** 右键交互入口：转发给脚本的 {@code onUse}。 */
    public void onRightClick(org.bukkit.event.player.PlayerInteractEvent event) {
        if (script != null) {
            try {
                script.invoke("onUse", event);
            } catch (Throwable t) {
                com.tangran.tangrantech.TT.log("脚本 " + script.getClass().getSimpleName()
                        + " 执行 onUse 出错: " + t);
            }
        }
    }
}
