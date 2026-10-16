package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.machines.TTRecipe;
import com.tangran.tangrantech.machines.TTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 recipe_machines.yml → {@link TTRecipeMachine}。
 *
 * <p>字段：{@code item_group / item / recipe_type / recipe / capacity / energyPerCraft /
 * speed / input / output / hideAllRecipes / recipes}。</p>
 */
public final class RecipeMachinesLoader {

    private RecipeMachinesLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "recipe_machines.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            // Slimefun 物品 Id 必须全大写（实机日志：Tr_xryyrl 注册失败）——循环内统一归一化
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            // 注册 id 大写化必须放在读 section 之后——Bukkit YAML 的段查找大小写敏感，
            // 先大写再查会把 yml 里的混合大小写键（Tr_xxx）全部查空（实机：注册 0 跳过 0）
            id = id.toUpperCase(java.util.Locale.ROOT);
            try {
                ItemGroup g = TT.group(s.getString("item_group"));
                if (g == null) { MissingItems.record("物品组缺失:" + s.getString("item_group")); skip++; continue; }
                ItemStack display = TT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { MissingItems.record("无展示物品:" + id); skip++; continue; }

                int[] input = MachinesLoader.toIntArray(s.getIntegerList("input"));
                int[] output = MachinesLoader.toIntArray(s.getIntegerList("output"));
                List<TTRecipe> recipes = Recipes.readAll(s.getConfigurationSection("recipes"), 9);

                TTRecipeMachine machine = new TTRecipeMachine(g, new SlimefunItemStack(id, display),
                        RecipeTypes.resolve(s.getString("recipe_type", "NULL")),
                        Read.recipe(s.getConfigurationSection("recipe"), 9),
                        input, output, recipes,
                        Math.max(1, s.getInt("capacity", 1)),
                        Math.max(0, s.getInt("energyPerCraft", 0)),
                        Math.max(1, s.getInt("speed", 1)),
                        TT.menus.get(id.toUpperCase(java.util.Locale.ROOT)),
                        s.getBoolean("hideAllRecipes", false));
                machine.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("recipe_machines.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("recipe_machines.yml: 注册 " + ok + ", 跳过 " + skip);
    }
}
