package com.tangran.tangrantech.native_scripts;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.Locale;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 粘液科技桥接层：把原 JS 脚本里反复编写的「取物品 id / 读方块数据」逻辑收敛成一处。
 *
 * <p>原脚本通过 {@code Java.type('...SlimefunItem')}、{@code Java.type('...BlockStorage')}、
 * {@code Java.type('...StorageCacheUtils')} 直接操作；原生化后统一走这里，行为一致且便于集中修坑。</p>
 */
public final class SlimefunBridge {

    private SlimefunBridge() {}

    /** 取物品上的粘液 id；非粘液物品返回 null。 */
    @Nullable
    public static String itemId(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        try {
            return Slimefun.getItemDataService().getItemData(item.getItemMeta()).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按 id 取已注册物品；未注册返回 null。 */
    @Nullable
    public static SlimefunItem byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        return SlimefunItem.getById(id.toUpperCase(Locale.ROOT));
    }

    /** 读取方块上存储的粘液 id；非粘液方块返回 null。 */
    @Nullable
    public static String blockId(@Nullable Block block) {
        if (block == null) return null;
        try {
            return BlockStorage.checkID(block);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读取位置上存储的粘液 id；非粘液方块返回 null。 */
    @Nullable
    public static String blockId(@Nullable Location loc) {
        if (loc == null) return null;
        return blockId(loc.getBlock());
    }

    /** 判断物品是否为指定粘液 id（大小写不敏感）。 */
    public static boolean isItem(@Nullable ItemStack item, @Nullable String id) {
        String actual = itemId(item);
        return actual != null && id != null && actual.equalsIgnoreCase(id);
    }

    /** 判断方块是否为指定粘液 id（大小写不敏感）。 */
    public static boolean isBlock(@Nullable Block block, @Nullable String id) {
        String actual = blockId(block);
        return actual != null && id != null && actual.equalsIgnoreCase(id);
    }

    /** 日志输出（挂到插件 logger，带 [tt] 前缀便于过滤）。 */
    public static void log(String msg) {
        TT.log("[tt] " + msg);
    }
}
