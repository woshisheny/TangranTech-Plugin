package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.machines.TTGenerator;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineFuel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 generators.yml → {@link TTGenerator}。
 *
 * <p>字段：{@code item_group / item / recipe_type / recipe / capacity / production /
 * input / output / hideAllRecipes / fuels}。燃料项：{@code item / seconds / output}。</p>
 */
public final class GeneratorsLoader {

    private GeneratorsLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "generators.yml");
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
                int capacity = Math.max(1, s.getInt("capacity", 1));
                int production = Math.max(1, s.getInt("production", 1));

                TTGenerator machine = new TTGenerator(g, new SlimefunItemStack(id, display),
                        RecipeTypes.resolve(s.getString("recipe_type", "NULL")),
                        Read.recipe(s.getConfigurationSection("recipe"), 9),
                        input, output, capacity, production,
                        TT.menus.get(id.toUpperCase(java.util.Locale.ROOT)));
                // 燃料必须构造后再下发：AGenerator 构造器末尾会调用 registerDefaultFuelTypes()，
                // 而此刻子类字段尚未初始化（详见 TTGenerator 类注释）。
                machine.registerFuels(readFuels(s.getConfigurationSection("fuels")));
                machine.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("generators.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("generators.yml: 注册 " + ok + ", 跳过 " + skip);
    }

    /** 解析燃料表；seconds 单位秒，内部换算为刻。缺失/非法条目跳过。 */
    static List<MachineFuel> readFuels(ConfigurationSection fuelsSec) {
        List<MachineFuel> out = new ArrayList<>();
        if (fuelsSec == null) return out;
        for (String key : fuelsSec.getKeys(false)) {
            ConfigurationSection f = fuelsSec.getConfigurationSection(key);
            if (f == null) continue;
            try {
                ItemStack input = Read.item(f.getConfigurationSection("item"), true);
                if (input == null) { TT.log("燃料 " + key + ": 缺少或错误的 item，跳过"); continue; }
                int seconds = f.getInt("seconds", 0);
                if (seconds < 0) { TT.log("燃料 " + key + ": seconds 非法，跳过"); continue; }
                ConfigurationSection outputSec = f.getConfigurationSection("output");
                ItemStack output = Read.item(outputSec, true);
                out.add(output == null
                        ? new MachineFuel(seconds * 2, input)
                        : new MachineFuel(seconds * 2, input, output));
            } catch (Exception e) {
                TT.log("燃料 " + key + " 解析失败: " + e);
            }
        }
        return out;
    }
}
