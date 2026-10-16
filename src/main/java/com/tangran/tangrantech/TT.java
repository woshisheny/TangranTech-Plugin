package com.tangran.tangrantech;

import com.tangran.tangrantech.machines.MenuDef;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * 全局静态持有（对齐 WorldTaste 的 WT / Komutech 的 KT）：插件实例、物品组表、自定义配方类型表、
 * 加载期「展示物品堆」表、菜单定义表。
 *
 * <p>{@link #preload} 是本架构的关键：加载前先把各内容文件里每个 id 的展示堆解析出来按大写 id 索引，
 * 后续任意文件解析配方 / 引用其它物品时都能立刻查到，从而让 items.yml 可以引用 mob_drops.yml、
 * 机器产物可以引用 items.yml，不受文件加载顺序限制。加载结束后由 Setup 清空释放。</p>
 */
public final class TT {

    private TT() {}

    public static TangranTechPlugin plugin;

    /** 展示物品堆：大写 id -> ItemStack（加载期填充，加载结束清空）。 */
    public static final Map<String, ItemStack> preload = new HashMap<>();

    /** 物品组：小写 key -> ItemGroup。 */
    public static final Map<String, ItemGroup> groups = new HashMap<>();

    /** 自定义配方类型：大写 id -> RecipeType。 */
    public static final Map<String, RecipeType> recipeTypes = new HashMap<>();

    /** 自定义菜单：id -> MenuDef（机器按 id 取用）。 */
    public static final Map<String, MenuDef> menus = new HashMap<>();

    public static void log(String msg) {
        if (plugin != null) plugin.getLogger().info(msg);
    }

    /** 按 item_group 取组（大小写不敏感）。 */
    public static ItemGroup group(String id) {
        if (id == null) return null;
        return groups.get(id.toLowerCase(Locale.ROOT));
    }

    /** 统一的大小写规范化：物品注册 id 一律大写（与原插件注册行为一致）。 */
    public static String upper(String id) {
        return id == null ? null : id.toUpperCase(Locale.ROOT);
    }

    /** 统一的大小写规范化：物品组 key 一律小写。 */
    public static String lower(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    /** 当前插件实例（供脚本层 / 工具类使用，避免到处传 plugin）。 */
    public static TangranTechPlugin plugin() {
        return plugin;
    }

    /**
     * 读取物品上的粘液 id（无则 null）。
     *
     * <p>等价于原 JS 脚本里的 {@code SlimefunItem.getByItem(item)?.getId()}，
     * 但只走一次 PDC 读取，比先取 SlimefunItem 再取 id 更省。</p>
     */
    public static String sfIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        try {
            return io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                .getItemDataService().getItemData(item.getItemMeta()).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按 id 取粘液物品；未注册返回 null。 */
    public static SlimefunItem sfItem(String id) {
        return id == null ? null : SlimefunItem.getById(id);
    }
}
