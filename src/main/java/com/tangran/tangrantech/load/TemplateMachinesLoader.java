package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.machines.TTRecipe;
import com.tangran.tangrantech.machines.TTTemplateMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 template_machines.yml → {@link TTTemplateMachine}。
 *
 * <p>配方按「模板物品 id」分组：玩家在 templateSlot 放入对应模板后，机器仅匹配该模板下的配方，
 * 模板不被消耗。字段：{@code templateSlot / consumption / capacity / input / output /
 * fasterIfMoreTemplates / moreOutputIfMoreTemplates / hideAllRecipes / recipes}。</p>
 */
public final class TemplateMachinesLoader {

    private TemplateMachinesLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "template_machines.yml");
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

                // 两级：模板 id → (配方 id → 配方)
                Map<String, List<TTRecipe>> byTemplate = new LinkedHashMap<>();
                List<TTRecipe> all = new ArrayList<>();
                ConfigurationSection recipesSec = s.getConfigurationSection("recipes");
                if (recipesSec != null) {
                    for (String tplKey : recipesSec.getKeys(false)) {
                        ConfigurationSection tplSec = recipesSec.getConfigurationSection(tplKey);
                        if (tplSec == null) continue;
                        List<TTRecipe> list = new ArrayList<>();
                        for (String recKey : tplSec.getKeys(false)) {
                            ConfigurationSection r = tplSec.getConfigurationSection(recKey);
                    if (r == null) continue;
                    TTRecipe rec = Recipes.readOne(recKey, r, input.length > 0 ? input.length : 9);
                    if (rec != null) { list.add(rec); all.add(rec); }
                        }
                        byTemplate.put(tplKey.toUpperCase(Locale.ROOT), list);
                    }
                }

                TTTemplateMachine machine = new TTTemplateMachine(g, new SlimefunItemStack(id, display),
                        RecipeTypes.resolve(s.getString("recipe_type", "NULL")),
                        Read.recipe(s.getConfigurationSection("recipe"), 9),
                        input, output, all, byTemplate,
                        Math.max(1, s.getInt("capacity", 1)),
                        Math.max(0, s.getInt("consumption", 0)),
                        Math.max(1, s.getInt("speed", 1)),
                        TT.menus.get(id.toUpperCase(java.util.Locale.ROOT)),
                        s.getBoolean("hideAllRecipes", false),
                        s.getInt("templateSlot", 10),
                        s.getBoolean("fasterIfMoreTemplates", false),
                        s.getBoolean("moreOutputIfMoreTemplates", false));
                machine.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("template_machines.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("template_machines.yml: 注册 " + ok + ", 跳过 " + skip);
    }
}
