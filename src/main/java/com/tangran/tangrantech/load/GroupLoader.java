package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.groups.TTButtonGroup;
import com.tangran.tangrantech.groups.TTNestedGroup;
import com.tangran.tangrantech.groups.TTSubItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 groups.yml：nested / sub / button / seasonal。
 *
 * <h2>渲染口径（实机截图对照后重写，对齐 RSC）</h2>
 * <p>RSC 的父组页面是<b>自绘</b>的：全部子项按 {@code tier} 摆进 54 格页面
 * （{@code slot = tier-1 - 54×页号}，超页翻页），装饰按钮就是页面里的间隔条，
 * 功能按钮点击执行 {@code actions}——原生 NestedItemGroup 页面画不了这些。
 * 因此本 loader：</p>
 * <ul>
 *   <li>{@code type: nested} → {@link TTNestedGroup}（自绘父页面，主界面唯一入口）；</li>
 *   <li>{@code type: sub} / {@code seasonal} / 其它带 parent 的 → {@link TTSubItemGroup}
 *       （挂父组、主界面隐藏；点击由父页面分发 {@code SlimefunGuide.openItemGroup}）；</li>
 *   <li>{@code type: button}：<b>不再注册为独立物品组</b>（那会平铺到主界面）——
 *       改为父页面里的一个条目，点击执行 {@code actions}（{@code TTButtonGroup.runActions}）；
 *       无 actions 的纯装饰按钮就是页面里的间隔条原样画出；</li>
 *   <li>展示物品解析失败一律用兜底物品补位，<b>绝不跳过</b>——否则 tier 槽位错位、
 *       子分类退化为普通组平铺主界面（实机两轮回归的教训）。</li>
 * </ul>
 */
public final class GroupLoader {

    private GroupLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "groups.yml");

        // 第一遍：nested 根组（自绘父页面）
        Map<String, TTNestedGroup> roots = new HashMap<>();
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (!type.equals("nested") && !type.equals("parent")) continue;
                roots.put(key.toLowerCase(Locale.ROOT), registerNested(key, s));
            } catch (Exception e) {
                TT.log("groups " + key + " 注册失败，跳过: " + e);
            }
        }

        // 第二遍：全部子项按 yml 顺序建条目（tier 定位）
        int subs = 0, buttons = 0, decorations = 0;
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (type.equals("nested") || type.equals("parent")) continue;

                String parentId = s.getString("parent");
                TTNestedGroup root = (parentId != null) ? roots.get(parentId.toLowerCase(Locale.ROOT)) : null;
                if (root == null) {
                    // 没有 nested 父组的组：按普通组注册（不会进自绘页面）
                    registerStandalone(key, s, type);
                    continue;
                }

                int tier = s.getInt("tier", 3);
                ItemStack display = Read.item(s.getConfigurationSection("item"), false);
                if (display == null) {
                    TT.log("groups " + key + ": 展示物品解析失败，用兜底物品补位（保证 tier 槽位不空）");
                    display = TTNestedGroup.fallbackDisplay(key);
                }

                if (type.equals("button")) {
                    List<String> actions = s.getStringList("actions");
                    if (actions == null || actions.isEmpty()) {
                        // 纯装饰按钮：RSC 页面里的间隔条，原样画出、点击无效
                        root.addEntry(tier, display, null);
                        decorations++;
                    } else {
                        root.addEntry(tier, display, (p, profile, mode) ->
                                TTButtonGroup.runActions(p, profile, mode, key, actions));
                        buttons++;
                    }
                    // 按钮也进 TT.groups：open_itemgroup 动作可能以它为目标
                    TT.groups.put(key.toLowerCase(Locale.ROOT), new TTButtonGroup(
                            nsKey(key), display, tier, actions));
                    continue;
                }

                // sub / seasonal / normal：挂为 SubItemGroup（主界面隐藏，父页面分发打开）
                TTSubItemGroup g = new TTSubItemGroup(nsKey(key), root, display, tier);
                g.register(TT.plugin);
                TT.groups.put(key.toLowerCase(Locale.ROOT), g);
                root.addEntry(tier, display, (p, profile, mode) ->
                        SlimefunGuide.openItemGroup(profile, g, mode, 1));
                subs++;
            } catch (Exception e) {
                TT.log("groups " + key + " 注册失败，跳过: " + e);
            }
        }

        TT.plugin.getLogger().info("groups.yml: 根组 " + roots.size()
                + "，子分类 " + subs + "，功能按钮 " + buttons
                + "，装饰按钮 " + decorations + "（自绘父页面 " + roots.size() + " 页起）");
    }

    /** 根组：自绘父页面（展示物品解析失败也强制注册，防子分类平铺）。 */
    private static TTNestedGroup registerNested(String key, ConfigurationSection s) {
        ItemStack display = Read.item(s.getConfigurationSection("item"), false);
        if (display == null) {
            TT.log("groups " + key + ": 展示物品解析失败，用兜底物品注册根组");
            display = TTNestedGroup.fallbackDisplay(key);
        }
        int tier = s.getInt("tier", 0);
        TTNestedGroup g = new TTNestedGroup(nsKey(key), display, tier, pageTitle());
        g.register(TT.plugin);
        TT.groups.put(key.toLowerCase(Locale.ROOT), g);
        return g;
    }

    /** 页面标题（对齐 RSC：用指南主标题本地化）。 */
    private static String pageTitle() {
        try {
            return Slimefun.getLocalization().getMessage("guide.title.main");
        } catch (Throwable ignored) {
            return "§d§l糖然科技";
        }
    }

    /** 没有 nested 父组的组：按普通组注册（进指南主界面，RSC 语义一致）。 */
    private static void registerStandalone(String key, ConfigurationSection s, String type) {
        ItemStack display = Read.item(s.getConfigurationSection("item"), false);
        if (display == null) {
            TT.log("groups " + key + ": 展示物品解析失败，已跳过");
            return;
        }
        int tier = s.getInt("tier", 3);
        ItemGroup g = new ItemGroup(nsKey(key), display, tier);
        g.register(TT.plugin);
        TT.groups.put(key.toLowerCase(Locale.ROOT), g);
    }

    private static NamespacedKey nsKey(String key) {
        return new NamespacedKey(TT.plugin, key.toLowerCase(Locale.ROOT));
    }
}
