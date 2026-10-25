package com.tangran.tangrantech.items;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

/**
 * 普通物品（placeable 未置 true 的条目）。
 *
 * <p><b>历史口径变更（2026-10-25）</b>：本类原先实现 {@code NotPlaceable}——但该接口
 * 在本 Slimefun 构建里的实际效果是「放置照常进行，只是<b>不写粘液方块数据</b>」，
 * 玩家把可放置的粘液材料摆出去后，方块是纯原版的（脚本/结构都无法识别）。
 * 现在去掉该接口，让普通物品（含方块材质的粘液材料）走 Slimefun 原生放置流程：
 * 放置即写入粘液数据，破坏时由 Slimefun 默认流程归还本体（{@code getDrops()} 返回
 * 本体并取消原版掉落），<b>不要</b>再挂 {@code SimpleBlockBreakHandler} 手动补掉——
 * 那会导致掉两份（实机踩过）。类名保留以对应 RSC 的 placeable 语义，避免内容侧大面积改名。</p>
 */
public class TTUnplaceableItem extends SlimefunItem {
    public TTUnplaceableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }
}
