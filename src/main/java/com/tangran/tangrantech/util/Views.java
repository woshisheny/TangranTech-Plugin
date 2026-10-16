package com.tangran.tangrantech.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * {@code InventoryView} 取顶部容器的统一入口。
 *
 * <p>MC 1.21.2 起 {@link InventoryView} 由抽象类改为接口，对 view 直接调用方法在另一侧
 * 运行时会抛 {@link IncompatibleClassChangeError}（编译期按接口生成 invokeinterface，
 * 老服务端却是抽象类）。本项目目标环境为 1.21.x，直接按接口方式调用即可，
 * 这里保留统一入口以便将来需要兼容老端时只改一处。</p>
 */
public final class Views {

    private Views() {}

    /**
     * 取 view 的顶部容器。
     *
     * @param view {@code Player#getOpenInventory()} 等返回的 view 对象
     * @return 顶部容器；view 为 null 或类型不符时返回 null
     */
    public static Inventory top(Object view) {
        if (view instanceof InventoryView iv) {
            return iv.getTopInventory();
        }
        return null;
    }

    /**
     * 取 view 的标题。
     *
     * @param view view 对象
     * @return 标题；取不到时返回空串
     */
    public static String title(Object view) {
        if (view instanceof InventoryView iv) {
            try {
                return iv.getTitle();
            } catch (Throwable ignored) {
                return "";
            }
        }
        return "";
    }
}
