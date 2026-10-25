package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ItemSpec;
import com.tangran.tangrantech.items.ScriptItemFactory;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载物品类配置。
 *
 * <p>糖然科技的 items.yml（消耗品/食材/装饰/交互道具）与 machines.yml（脚本驱动的可放置机器）
 * 结构一致，统一经 {@link ScriptItemFactory} 按脚本+属性分派物品子类。
 * 支持 register.conditions、lateInit（两遍）、id_alias、placeable、drop_from 等属性。</p>
 */
public final class ItemsLoader {

    private ItemsLoader() {}

    public static void load() {
        loadFile("items.yml");
    }

    public static void loadFile(String file) {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, file);
        int ok = 0, skip = 0;
        List<ConfigurationSection> late = new ArrayList<>();
        List<String> lateIds = new ArrayList<>();
        // 第一遍：非 lateInit
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            // 注册 id 大写化必须放在读 section 之后——Bukkit YAML 的段查找大小写敏感，
            // 先大写再查会把 yml 里的混合大小写键（Tr_xxx）全部查空（实机：注册 0 跳过 0）
            id = id.toUpperCase(java.util.Locale.ROOT);
            if (s.getBoolean("lateInit", false)) {
                late.add(s);
                lateIds.add(id);
                continue;
            }
            try {
                if (register(file, id, s)) ok++;
                else skip++;
            } catch (Exception e) {
                TT.log(file + " " + id + " 注册失败: " + e);
                skip++;
            }
        }
        // 第二遍：lateInit
        for (int i = 0; i < late.size(); i++) {
            String id = lateIds.get(i);
            try {
                if (register(file, id, late.get(i))) ok++;
                else skip++;
            } catch (Exception e) {
                TT.log(file + " " + id + "(lateInit) 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info(file + ": 注册 " + ok + ", 跳过 " + skip);
    }

    /** 通用物品注册（items / mob_drops 复用）。成功返回 true。 */
    static boolean register(String file, String id, ConfigurationSection s) {
        return register(file, id, s, null, null);
    }

    /**
     * 带展示覆盖的注册重载（mob_drops 用）：掉落来源展示不是标准 recipe/recipe_type 字段，
     * 由 MobDropsLoader 解析 entity/chance 后经覆盖参数注入；覆盖为 null 时行为不变。
     */
    static boolean register(String file, String id, ConfigurationSection s, RecipeType rtOverride,
                            ItemStack[] recipeOverride) {
        // 注册条件
        if (!RegisterConditions.pass(s)) return false;

        String effId = s.getString("id_alias", id);
        effId = effId.toUpperCase(java.util.Locale.ROOT);
        ItemGroup g = TT.group(s.getString("item_group"));
        if (g == null) {
            MissingItems.record("物品组缺失:" + s.getString("item_group"));
            return false;
        }

        ItemStack display = TT.preload.get(effId.toUpperCase(java.util.Locale.ROOT));
        if (display == null) display = TT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
        if (display == null) {
            MissingItems.record("无展示物品:" + effId);
            return false;
        }

        ItemSpec spec = ItemSpec.from(effId, s);
        SlimefunItemStack sfis = new SlimefunItemStack(effId, display);
        RecipeType rt = rtOverride != null ? rtOverride : RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
        ItemStack[] recipe = recipeOverride != null
                ? recipeOverride : Read.recipe(s.getConfigurationSection("recipe"), 9);

        SlimefunItem item = ScriptItemFactory.create(spec, g, sfis, rt, recipe);
        if (spec.vanilla) {
            try { item.setUseableInWorkbench(true); } catch (Throwable ignored) { }
        }
        // 破坏掉落不需要任何 handler：Slimefun 的 BlockListener 会调 getDrops()（基类默认
        // = 本体模板）并 setDropItems(false) 取消原版掉落，这里再手动补一份就是双倍掉落
        item.register(TT.plugin);
        return true;
    }
}
