package com.tangran.tangrantech.machines;

import io.github.thebusybiscuit.slimefun4.api.events.MultiBlockCraftEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.ItemUtils;
import io.github.thebusybiscuit.slimefun4.libraries.paperlib.PaperLib;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 多方块机器（mb_machines.yml）。继承 Slimefun 原生 {@link MultiBlockMachine}，
 * 右键结构时检查发射器内物品、消耗并产出。逻辑对齐 RSC CustomMultiBlockMachine。
 */
public class TTMultiBlockMachine extends MultiBlockMachine {

    private final int workIndex;
    private final BlockFace dispenserFace;
    @Nullable
    private final SoundEffect craftSound;

    public TTMultiBlockMachine(ItemGroup group, SlimefunItemStack item, ItemStack[] structure,
                               Map<ItemStack[], ItemStack> recipes, int work, @Nullable SoundEffect sound) {
        super(group, item, structure, BlockFace.SELF);
        this.workIndex = work - 1;
        this.craftSound = sound;
        // 发射器相对 work 槽位的方位：由 3x3 结构里 DISPENSER 相对 work 的位置推导。
        // work 是 1..9 的槽位号（第 1 行 1-3、第 2 行 4-6、第 3 行 7-9）。
        this.dispenserFace = dispenserFaceGet();
        for (Map.Entry<ItemStack[], ItemStack> e : recipes.entrySet()) {
            addRecipe(e.getKey(), e.getValue());
        }
    }

    @Override
    public void onInteract(Player p, Block block) {
        ItemStack[] recipe = getRecipe();
        if (workIndex < 0 || workIndex >= recipe.length) return;
        Material material = recipe[workIndex].getType();
        if (!block.getType().equals(material)) return;
        Block disBlock = block.getRelative(dispenserFace);
        BlockState bs = PaperLib.getBlockState(disBlock, false).getState();
        if (!(bs instanceof Dispenser dispenser)) return;

        Inventory inv = dispenser.getInventory();
        ItemStack[] contents = inv.getContents();
        List<ItemStack[]> inputs = RecipeType.getRecipeInputList(this);

        for (ItemStack[] input : inputs) {
            if (!isCraftable(contents, input)) continue;
            ItemStack output = RecipeType.getRecipeOutputList(this, input).clone();
            MultiBlockCraftEvent event = new MultiBlockCraftEvent(p, this, input, output);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !SlimefunUtils.canPlayerUseItem(p, event.getOutput(), true)) return;
            ItemStack finalOutput = event.getOutput();
            // 兜底：产物若为无 id 展示物品（加载期未注册），按外观反查注册物品重建
            if (finalOutput != null && SlimefunItem.getByItem(finalOutput) == null) {
                SlimefunItem rebuilt = com.tangran.tangrantech.util.Stacks.findRegisteredByAppearance(finalOutput);
                if (rebuilt != null) finalOutput = rebuilt.getItem().clone();
            }
            Inventory fakeInv = createVirtualInventory(inv);
            Inventory outputInv = findOutputInventory(finalOutput, disBlock, inv, fakeInv);
            if (outputInv == null) {
                Slimefun.getLocalization().sendMessage(p, "machines.full-inventory", true);
                return;
            }
            int limit = Math.min(input.length, contents.length);
            for (int j = 0; j < limit; j++) {
                ItemStack item = contents[j];
                if (item != null && item.getType() != Material.AIR) {
                    ItemUtils.consumeItem(item, input[j].getAmount(), true);
                }
            }
            if (craftSound != null) craftSound.playAt(block);
            outputInv.addItem(finalOutput);
            return;
        }

        if (inv.isEmpty()) {
            Slimefun.getLocalization().sendMessage(p, "machines.inventory-empty", true);
        } else {
            Slimefun.getLocalization().sendMessage(p, "machines.pattern-not-found", true);
        }
    }

    /**
     * 克隆一份「已消耗一格」的影子背包。
     *
     * <p>传给 {@link #findOutputInventory} 用于判断产物能否落袋：直接在真实背包上试放会污染数据，
     * 因此先复制一份并假定输入已被消耗。</p>
     */
    private Inventory createVirtualInventory(Inventory inv) {
        Inventory fake = Bukkit.createInventory(null, 9, net.kyori.adventure.text.Component.text("Fake Inventory"));
        ItemStack[] contents = inv.getContents();
        for (int j = 0; j < contents.length && j < 9; j++) {
            ItemStack stack = contents[j];
            if (stack != null) {
                stack = stack.clone();
                ItemUtils.consumeItem(stack, true);
            }
            fake.setItem(j, stack);
        }
        return fake;
    }

    private boolean isCraftable(ItemStack[] contents, ItemStack[] recipe) {
        int limit = Math.min(contents.length, recipe.length);
        for (int j = 0; j < limit; j++) {
            ItemStack have = contents[j];
            ItemStack need = recipe[j];
            if (!SlimefunUtils.isItemSimilar(have, need, true)) {
                if (!SlimefunUtils.isItemSimilar(have, need, false)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 根据 work 槽位在 3x3 结构中的位置，反推发射器相对它的方位。
     *
     * <p>结构数组按下标 0..8 对应槽位 1..9：{@code center-3} 上一行、{@code center-1} 左一列、
     * {@code center+1} 右一列、{@code center+3} 下一行。</p>
     */
    private BlockFace dispenserFaceGet() {
        ItemStack[] is = getRecipe();
        int center = workIndex;
        if (center - 3 >= 0) {
            ItemStack o1 = is[center - 3];
            if (o1 != null && o1.getType() == Material.DISPENSER) return BlockFace.UP;
        }
        if (center - 1 >= 0) {
            ItemStack o2 = is[center - 1];
            if (o2 != null && o2.getType() == Material.DISPENSER) return BlockFace.EAST;
        }
        if (center + 1 >= 9) return BlockFace.SELF;
        ItemStack o3 = is[center + 1];
        if (o3 != null && o3.getType() == Material.DISPENSER) return BlockFace.WEST;
        if (center + 3 < 9) {
            ItemStack o4 = is[center + 3];
            if (o4 != null && o4.getType() == Material.DISPENSER) return BlockFace.DOWN;
        }
        return BlockFace.SELF;
    }
}
