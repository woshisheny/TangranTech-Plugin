package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ItemSpec;
import com.tangran.tangrantech.items.ScriptMachineItem;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.native_scripts.NativeScript;
import com.tangran.tangrantech.native_scripts.NativeScriptRegistry;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 machines.yml：脚本驱动的方块机器。
 *
 * <p>结构：{@code item_group / item / recipe_type / recipe / input / output / energy / script}。
 * 机器 GUI 取自 {@code menus.yml} 中同 id 的菜单；工作与交互逻辑交给 {@code script} 对应的原生实现。</p>
 */
public final class MachinesLoader {

    private MachinesLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "machines.yml");
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

                ItemSpec spec = ItemSpec.from(id, s);
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] recipe = Read.recipe(s.getConfigurationSection("recipe"), 9);

                NativeScript script = NativeScriptRegistry.get(spec.script);
                MenuDef menu = TT.menus.get(id.toUpperCase(java.util.Locale.ROOT));

                int capacity = spec.energyCapacity != null ? spec.energyCapacity : 0;
                String etype = spec.energyType;

                ScriptMachineItem item = new ScriptMachineItem(g, sfis, rt, recipe, script,
                        capacity, etype, toIntArray(s.getIntegerList("input")),
                        toIntArray(s.getIntegerList("output")), spec.tick);
                item.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("machines.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("machines.yml: 注册 " + ok + ", 跳过 " + skip);
    }

    static int[] toIntArray(List<Integer> list) {
        if (list == null) return new int[0];
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
