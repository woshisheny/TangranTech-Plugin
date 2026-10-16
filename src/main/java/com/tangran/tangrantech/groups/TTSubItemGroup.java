package com.tangran.tangrantech.groups;

import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 糖然科技的子分类组（对应 RSC {@code groups.yml} 里 {@code type: sub}）。
 *
 * <h2>为什么要子类化（实机问题）</h2>
 * <p>本服的 Slimefun（f172239 本地化版）指南主界面的分组收集逻辑
 * （{@code SurvivalSlimefunGuide#getVisibleItemGroups}）对非 Flex 组用的是
 * <b>旧 API {@code ItemGroup#isHidden}</b> 过滤；而新版 API 的
 * {@link SubItemGroup} 把可见性迁移到了 {@code isVisible}（恒 {@code false}），
 * <b>没有覆写 {@code isHidden}</b> —— 于是原生 SubItemGroup 全部被当作
 * 「可见」平铺到指南主界面，与 RSC 原版「子分类只出现在父组页面里」的观感完全不同。</p>
 *
 * <p>修法：覆写 {@link #isHidden(Player)} 恒 {@code true}，让 fork 的主界面过滤生效；
 * 父组（{@link NestedItemGroup}）内页的子分类列表走的是它自己的 subGroups 渲染，
 * 不受 {@code isHidden} 影响，点击仍经 {@code SlimefunGuide.openItemGroup} 正常打开。</p>
 *
 * <p>与 RSC 的差异：RSC 是把所有组都做成自己的 Flex 子类完全接管指南渲染；
 * 本插件用「SubItemGroup 子类 + isHidden」达到同样的用户可见效果，
 * 保留 Slimefun 原生的父组页面渲染（按钮条/间距条不还原，见转化报告 8 的记录）。</p>
 */
public class TTSubItemGroup extends SubItemGroup {

    public TTSubItemGroup(NamespacedKey key, NestedItemGroup parent, ItemStack display, int tier) {
        super(key, parent, display, tier);
    }

    /** 主界面隐藏（fork 指南按 isHidden 过滤非 Flex 组；父组内页不受影响）。 */
    @Override
    public boolean isHidden(Player p) {
        return true;
    }
}
