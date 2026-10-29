package com.tangran.tangrantech.machines;

import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 面向「逻辑工艺（LogiTech）」堆叠机识别的配方载体。
 *
 * <p><b>为什么需要它</b>：LogiTech 的 {@code RecipeSupporter.transferRSCRecipes}
 * 只对<b>类名以 {@code CustomMachineRecipe} 结尾</b>的 {@link MachineRecipe} 走 RSC 兼容通道
 * （`rsc.getClass().getName().endsWith("CustomMachineRecipe")`），并反射读取四个
 * 公共字段：{@code forDisplay} / {@code chances} / {@code noConsume} / {@code chooseOneIfHas}。
 * 缺失任一字段会抛异常并被 LogiTech 吞掉 → <b>整条配方被丢弃</b>，所以字段必须真实存在。</p>
 *
 * <p>本类只作为「递交给 LogiTech 的中间载体」，糖然科技自身运行时仍用 {@link TTRecipe}；
 * 转换后 LogiTech 会把概率产出包成它自己的 {@code ProbItemStack}、把 noConsume 输入
 * 写进 {@code StackMachineRecipe}，从而在堆叠机里保留概率与「只校验不消耗」语义。</p>
 */
public final class TTCustomMachineRecipe extends MachineRecipe {

    /** RSC 兼容字段：展示配方标记（true 时 LogiTech 直接丢弃）。本类构造的恒为 false。 */
    public final boolean forDisplay;
    /** RSC 兼容字段：每个产出项的概率（1-100），长度必须与 {@code getOutput()} 对齐。 */
    public final List<Integer> chances;
    /** RSC 兼容字段：不消耗产出的输入下标（LogiTech 用 Iterable 兜底读取）。 */
    public final List<Integer> noConsume;
    /** RSC 兼容字段：命中产出中随机只取一个。 */
    public final boolean chooseOneIfHas;

    public TTCustomMachineRecipe(int ticks, ItemStack[] input, ItemStack[] output,
                                 List<Integer> chances, List<Integer> noConsume, boolean chooseOne) {
        super(Math.max(0, ticks), input, output);
        this.forDisplay = false;
        this.chances = List.copyOf(chances);
        this.noConsume = List.copyOf(noConsume);
        this.chooseOneIfHas = chooseOne;
    }

    /** 由糖然科技内部配方转换（概率与 noConsume 逐项照搬）。 */
    public static TTCustomMachineRecipe of(TTRecipe r) {
        ItemStack[] in = r.getInput() == null ? new ItemStack[0] : r.getInput();
        ItemStack[] out = r.getOutput() == null ? new ItemStack[0] : r.getOutput();
        List<Integer> chances = new ArrayList<>(out.length);
        for (int i = 0; i < out.length; i++) {
            chances.add(Math.max(1, Math.min(100, r.outputChance(i))));
        }
        List<Integer> noConsume = new ArrayList<>();
        for (int i = 0; i < in.length; i++) {
            if (r.isNoConsume(i)) noConsume.add(i);
        }
        return new TTCustomMachineRecipe(Math.max(0, r.getTicks()), in, out, chances, noConsume, r.isChooseOne());
    }
}
