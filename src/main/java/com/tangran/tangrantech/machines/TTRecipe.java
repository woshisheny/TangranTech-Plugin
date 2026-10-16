package com.tangran.tangrantech.machines;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.Random;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 机器配方（recipe_machines / template_machines / mb_machines / super_multi_block_machines 共用）。
 *
 * <p>继承 Slimefun 的 {@link MachineRecipe} 以便直接交给 {@code MachineProcessor}
 * 驱动进度条与完成回调。在原 JS/RSC 语义之上保留三个关键特性：
 * <ul>
 *   <li><b>noConsume</b>：输入项标记为 {@code noConsume: true} 时只校验不消耗（工具类模板）；</li>
 *   <li><b>damage</b>：输入项带 {@code damage} 时按耐久扣点而非整件消耗；</li>
 *   <li><b>chance / chooseOne</b>：输出项带 {@code chance}（0-100）按概率产出；
 *       {@code chooseOne: true} 时从候选中随机只产一个。</li>
 * </ul>
 */
public final class TTRecipe extends MachineRecipe {

    private static final Random RANDOM = new Random();

    private final ItemStack[] input;
    private final ItemStack[] output;
    private final boolean[] noConsume;
    private final int[] damage;
    private final int[] chance;
    /** 输入项绑定的 GUI 槽位（-1 = 不限槽位）。 */
    private final int[] inSlot;
    private final boolean chooseOne;

    public TTRecipe(int ticks, ItemStack[] input, ItemStack[] output, boolean[] noConsume,
                    int[] damage, int[] chance, int[] inSlot, boolean chooseOne) {
        super(Math.max(0, ticks), input, output);
        this.input = input;
        this.output = output;
        this.noConsume = noConsume;
        this.damage = damage;
        this.chance = chance;
        this.inSlot = inSlot;
        this.chooseOne = chooseOne;
    }

    @Override
    public ItemStack[] getInput() { return input; }

    @Override
    public ItemStack[] getOutput() { return output; }

    /** 该输入项是否不消耗。 */
    public boolean isNoConsume(int i) {
        return noConsume != null && i >= 0 && i < noConsume.length && noConsume[i];
    }

    /** 该输入项的耐久扣点（0 = 不按耐久处理）。 */
    public int inputDamage(int i) {
        return damage != null && i >= 0 && i < damage.length ? damage[i] : 0;
    }

    /** 该输出项的产出概率（0-100，缺省 100）。 */
    public int outputChance(int i) {
        return chance != null && i >= 0 && i < chance.length ? chance[i] : 100;
    }

    /** 该输入项绑定的 GUI 槽位（-1 = 不限）。 */
    public int inSlot(int i) {
        return inSlot != null && i >= 0 && i < inSlot.length ? inSlot[i] : -1;
    }

    /** 是否「随机只产一个」。 */
    public boolean isChooseOne() { return chooseOne; }

    /**
     * 把产出推入输出槽：按 {@code chance} 掷点，未命中的格位留空；{@code chooseOne} 时只产一个。
     * 输出槽满时停止（物品不凭空消失，等玩家取走后再产）。
     */
    public void pushOutputs(BlockMenu inv, int[] outputSlots) {
        pushOutputs(inv, outputSlots, 1);
    }

    /** @param multiplier 产出倍率（模板机器用）；≤1 视为 1。 */
    public void pushOutputs(BlockMenu inv, int[] outputSlots, int multiplier) {
        int mult = Math.max(1, multiplier);
        int onlyIndex = -1;
        if (chooseOne) {
            int candidates = 0;
            for (int i = 0; i < output.length; i++) {
                ItemStack o = output[i];
                if (o == null || o.getType().isAir()) continue;
                if (RANDOM.nextInt(100) >= outputChance(i)) continue;
                candidates++;
                if (RANDOM.nextInt(candidates) == 0) onlyIndex = i;
            }
            if (onlyIndex < 0) return;
        }
        for (int i = 0; i < output.length && i < outputSlots.length; i++) {
            if (chooseOne && i != onlyIndex) continue;
            ItemStack out = output[i];
            if (out == null || out.getType().isAir()) continue;
            if (RANDOM.nextInt(100) >= outputChance(i)) continue;
            ItemStack give = out.clone();
            if (mult > 1) {
                long scaled = (long) give.getAmount() * mult;
                give.setAmount((int) Math.min(scaled, give.getMaxStackSize()));
            }
            int before = give.getAmount();
            inv.pushItem(give, outputSlots);
            if (give.getAmount() >= before) break;
        }
    }

    /** 读取物品上的粘液 id（无则 null）。 */
    public static String sfIdOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        try {
            return Slimefun.getItemDataService().getItemData(it.getItemMeta()).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该 id 是否已注册且未被禁用。 */
    public static boolean isAvailable(String id) {
        if (id == null) return false;
        SlimefunItem sf = SlimefunItem.getById(id);
        return sf != null && !sf.isDisabled();
    }

    /** 配方的任一输入或输出引用了未注册/已禁用的粘液物品时返回 true。 */
    public boolean referencesDisabledItem() {
        for (int i = 0; i < input.length; i++) {
            String id = sfIdOf(input[i]);
            if (id != null && !isAvailable(id)) return true;
        }
        for (ItemStack out : output) {
            String id = sfIdOf(out);
            if (id != null && !isAvailable(id)) return true;
        }
        return false;
    }
}
