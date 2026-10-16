package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTMultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 加载 mb_machines.yml → {@link TTMultiBlockMachine}。
 *
 * <p>字段：{@code item_group / item / recipe(3x3 结构) / work / sound / recipes}；
 * 配方段输出支持「编号列表」与「单物品」两种写法。</p>
 */
public final class MultiBlockMachinesLoader {

    private MultiBlockMachinesLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "mb_machines.yml");
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

                Map<ItemStack[], ItemStack> recipes = new LinkedHashMap<>();
                ConfigurationSection recipesSec = s.getConfigurationSection("recipes");
                if (recipesSec != null) {
                    for (String key : recipesSec.getKeys(false)) {
                        ConfigurationSection r = recipesSec.getConfigurationSection(key);
                        if (r == null) continue;
                        ItemStack[] in = Read.recipe(r.getConfigurationSection("input"), 9);
                        // mb_machines 的 output 既可能是编号列表，也可能是单个物品段
                        ItemStack out = Read.item(r.getConfigurationSection("output"), true);
                        if (out != null) recipes.put(in, out);
                    }
                }

                int work = s.getInt("work", 5);
                SoundEffect sound = parseSound(s.getString("sound"));

                TTMultiBlockMachine machine = new TTMultiBlockMachine(g, new SlimefunItemStack(id, display),
                        Read.recipe(s.getConfigurationSection("recipe"), 9), recipes, work, sound);
                machine.register(TT.plugin);
                ok++;
            } catch (Exception e) {
                TT.log("mb_machines.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("mb_machines.yml: 注册 " + ok + ", 跳过 " + skip);
    }

    /** 解析声音名；未知或缺失返回 null（静默）。 */
    static SoundEffect parseSound(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return SoundEffect.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            TT.log("未知 SoundEffect: " + name + "（已忽略声音）");
            return null;
        }
    }
}
