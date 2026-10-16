package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTRecipe;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 机器配方读取器：把 yml 的 {@code recipes} 段统一解析成 {@link TTRecipe} 列表。
 *
 * <p>糖然科技的配方段存在多种历史写法，这里全部兼容：
 * <ol>
 *   <li><b>编号输出</b>：{@code output: {1: {...}, 2: {...}}}（recipe_machines / template_machines / super_multi_block_machines）</li>
 *   <li><b>单物品输出</b>：{@code output: {material_type: ..., material: ...}}（mb_machines）</li>
 *   <li><b>输出列表</b>：{@code outputs: {1..N}} 或 {@code outputItem: {...}}（mat_generators）</li>
 * </ol>
 *
 * <p>输入统一为 {@code input: {1..N}}，每项可带 {@code amount / noConsume / damage}。</p>
 */
public final class Recipes {

    private Recipes() {}

    /** 数字键排序器：保证 "10" 排在 "9" 之后（字符串默认排序会把 "10" 排到 "2" 前）。 */
    private static final Comparator<String> NUMERIC = (a, b) -> {
        try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
        catch (NumberFormatException e) { return a.compareTo(b); }
    };

    /** 解析配方列表；任一条解析失败只跳过该条。 */
    public static List<TTRecipe> readAll(@Nullable ConfigurationSection recipesSec, int inputSize) {
        List<TTRecipe> out = new ArrayList<>();
        if (recipesSec == null) return out;
        for (String key : recipesSec.getKeys(false)) {
            ConfigurationSection r = recipesSec.getConfigurationSection(key);
            if (r == null) continue;
            TTRecipe rec = readOne(key, r, inputSize);
            if (rec != null) out.add(rec);
        }
        return out;
    }

    /**
     * 解析单条配方（output 段支持「编号列表」与「单物品」两种写法）。
     *
     * @param inputSize 输入槽数量（mb_machines 为发射器 9 槽；recipe_machines 取 yml input 槽数）
     */
    public static TTRecipe readOne(String name, ConfigurationSection r, int inputSize) {
        try {
            ConfigurationSection inputSec = r.getConfigurationSection("input");
            ItemStack[] input = Read.recipe(inputSec, inputSize);

            boolean[] noConsume = new boolean[input.length];
            int[] damage = new int[input.length];
            int[] inSlot = new int[input.length];
            java.util.Arrays.fill(inSlot, -1);
            if (inputSec != null) {
                for (int i = 0; i < input.length; i++) {
                    ConfigurationSection slot = inputSec.getConfigurationSection(String.valueOf(i + 1));
                    if (slot == null) continue;
                    noConsume[i] = slot.getBoolean("noConsume", false);
                    damage[i] = slot.getInt("damage", 0);
                    inSlot[i] = slot.getInt("slot", -1);
                }
            }

            int ticks = Math.max(0, r.getInt("seconds", 0)) * 2;
            ItemStack[] output;
            int[] chance;
            ConfigurationSection outputSec = r.getConfigurationSection("output");
            if (outputSec == null) {
                output = new ItemStack[0];
                chance = new int[0];
            } else if (outputSec.contains("1")) {
                List<String> keys = new ArrayList<>(outputSec.getKeys(false));
                keys.sort(NUMERIC);
                output = new ItemStack[keys.size()];
                chance = new int[keys.size()];
                for (int i = 0; i < keys.size(); i++) {
                    ConfigurationSection o = outputSec.getConfigurationSection(keys.get(i));
                    if (o == null) continue;
                    output[i] = Read.item(o, true);
                    chance[i] = clamp(o.getInt("chance", 100), 1, 100);
                }
            } else {
                ItemStack single = Read.item(outputSec, true);
                if (single == null) return null;
                output = new ItemStack[] { single };
                chance = new int[] { clamp(outputSec.getInt("chance", 100), 1, 100) };
            }
            boolean chooseOne = r.getBoolean("chooseOne", false);
            return new TTRecipe(ticks, input, output, noConsume, damage, chance, inSlot, chooseOne);
        } catch (Exception e) {
            TT.log("配方 " + name + " 解析失败: " + e);
            return null;
        }
    }

    /**
     * 解析 mat_generators 的产出配置（{@code outputs} 列表 与/或 单个 {@code outputItem}）。
     * 该机器无输入槽，产出按 {@code tickRate} 周期进行。
     */
    @Nullable
    public static TTRecipe readOutputs(ConfigurationSection section, int tickRate) {
        ConfigurationSection outputsSec = section.getConfigurationSection("outputs");
        ConfigurationSection outputItemSec = section.getConfigurationSection("outputItem");
        List<ItemStack> outs = new ArrayList<>();
        List<Integer> chances = new ArrayList<>();
        if (outputsSec != null) {
            List<String> keys = new ArrayList<>(outputsSec.getKeys(false));
            keys.sort(NUMERIC);
            for (String k : keys) {
                ConfigurationSection o = outputsSec.getConfigurationSection(k);
                if (o == null) continue;
                ItemStack it = Read.item(o, true);
                if (it == null) continue;
                outs.add(it);
                chances.add(clamp(o.getInt("chance", 100), 1, 100));
            }
        }
        if (outputItemSec != null) {
            ItemStack it = Read.item(outputItemSec, true);
            if (it != null) {
                outs.add(it);
                chances.add(clamp(outputItemSec.getInt("chance", 100), 1, 100));
            }
        }
        if (outs.isEmpty()) return null;
        int[] ch = chances.stream().mapToInt(Integer::intValue).toArray();
        int ticks = Math.max(1, tickRate);
        return new TTRecipe(ticks, new ItemStack[0], outs.toArray(new ItemStack[0]),
                new boolean[0], new int[0], ch, new int[0],
                section.getBoolean("chooseOne", false));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
