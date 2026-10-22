package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTRecipe;
import com.tangran.tangrantech.machines.TTSuperMultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 super_multi_block_machines.yml → {@link TTSuperMultiBlockMachine}。
 *
 * <p>字段：{@code item_group / item / recipe_type / recipe / structure(多层字符矩阵) /
 * mapping(字符→材质) / capacity / energyPerCraft / speed / input / output / recipes}。</p>
 */
public final class SuperMultiBlockMachinesLoader {

    private SuperMultiBlockMachinesLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "super_multi_block_machines.yml");
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
                // inputSize 必须是本机器的真实输入槽数——此前硬编码 9（照抄 mb_machines
                // 的发射器 9 槽），导致 TR_YTC（2 槽）等机器每条配方的输入数组里被
                // 填入大量 null 需求，配合旧 distinct 检查把所有配方无声拒绝
                // （「输入槽位被重复占用」案，2026-10-23）。
                List<TTRecipe> recipes = Recipes.readAll(s.getConfigurationSection("recipes"), input.length);

                List<List<List<String>>> structure = TTSuperMultiBlockMachine.parseStructure(s);
                TTSuperMultiBlockMachine.MappingResult mr =
                        TTSuperMultiBlockMachine.parseMapping(s.getConfigurationSection("mapping"), structure);

                TTSuperMultiBlockMachine.SuperFlags flags = new TTSuperMultiBlockMachine.SuperFlags(
                        s.getBoolean("displayProjectiles", true),
                        s.getBoolean("checkFormed", true),
                        s.getBoolean("openMenuWhenClickedParts", true),
                        s.getBoolean("noMenuWhenNotFormed", true),
                        s.getBoolean("allowSwitchDisplayLayer", true),
                        s.getBoolean("defaultNotice", true));

                TTSuperMultiBlockMachine machine = new TTSuperMultiBlockMachine(g,
                        new SlimefunItemStack(id, display),
                        RecipeTypes.resolve(s.getString("recipe_type", "NULL")),
                        Read.recipe(s.getConfigurationSection("recipe"), 9),
                        input, output, recipes,
                        Math.max(1, s.getInt("capacity", 1)),
                        Math.max(0, s.getInt("energyPerCraft", 0)),
                        Math.max(1, s.getInt("speed", 1)),
                        TT.menus.get(id.toUpperCase(java.util.Locale.ROOT)),
                        s.getBoolean("hideAllRecipes", false),
                        structure, mr.map, mr.core, flags);
                machine.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("super_multi_block_machines.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("super_multi_block_machines.yml: 注册 " + ok + ", 跳过 " + skip);
        if (ok > 0) {
            // 超多方块管理器：方向感知结构 / dirty 逐格检测 / 投影层 / 部件点击开菜单（RSC 逻辑复原）
            com.tangran.tangrantech.machines.superblock.SuperBlocks.registerOnce();
        }
    }
}
