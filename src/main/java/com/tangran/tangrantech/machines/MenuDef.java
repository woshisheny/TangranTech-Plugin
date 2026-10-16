package com.tangran.tangrantech.machines;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单定义（menus.yml 的单条记录）：机器按 {@code upper(id)} 从 {@code TT.menus} 取用，
 * 用于构造 {@code BlockMenuPreset} 的背景布局与进度条槽位。
 */
public final class MenuDef {

    /** 菜单 id（保持 yml 原始写法，取用时统一大写比对）。 */
    public final String id;
    /** 菜单标题（已翻译颜色码）。 */
    public final String title;
    /** 槽位 → 背景/装饰物品。 */
    public final Map<Integer, ItemStack> items = new HashMap<>();
    /** 进度条槽位，默认 22。 */
    public int progressSlot = 22;
    /** 进度条物品，默认黑色玻璃板。 */
    public ItemStack progressItem;
    /** 菜单大小；-1 或 0 表示由背景槽位自动推导。 */
    public int size = -1;
    /** 玩家背包是否可点击，默认 true。 */
    public boolean playerInvClickable = true;

    public MenuDef(String id, String title) {
        this.id = id;
        this.title = title;
        this.progressItem = new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.BLACK_STAINED_GLASS_PANE);
    }

    /** 该菜单实际使用的大小：显式给定则用之，否则按 9 的倍数向上取整到 54 上限。 */
    public int resolvedSize() {
        if (size > 0 && size % 9 == 0) return Math.min(size, 54);
        int max = -1;
        for (Integer slot : items.keySet()) if (slot > max) max = slot;
        if (max < 0) return 54;
        int rows = (max / 9) + 1;
        return Math.min(rows * 9, 54);
    }
}
