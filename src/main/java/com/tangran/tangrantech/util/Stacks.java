package com.tangran.tangrantech.util;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/** ItemStack 辅助操作。 */
public final class Stacks {

    private Stacks() {}

    /** 外观反查索引：材质 → (显示名 → 物品)。加载末期构建一次，运行期分桶命中。 */
    private static final Map<Material, Map<String, SlimefunItem>> APPEARANCE_INDEX = new HashMap<>();

    /** 哨兵键：代表「无 displayName」的物品。 */
    private static final String NO_NAME = " ";

    /**
     * 构建外观反查索引（须在所有物品含 lateInit/机器/多方块注册完成后调用一次）。
     * putIfAbsent 保留注册顺序中第一个匹配项，与原「return first」语义一致。
     */
    public static void buildAppearanceIndex() {
        APPEARANCE_INDEX.clear();
        for (SlimefunItem sf : Slimefun.getRegistry().getAllSlimefunItems()) {
            ItemStack it = sf.getItem();
            if (it == null || it.getType().isAir()) continue;
            Material type = it.getType();
            ItemMeta m = it.getItemMeta();
            String key = (m != null && m.hasDisplayName()) ? m.getDisplayName() : NO_NAME;
            APPEARANCE_INDEX.computeIfAbsent(type, k -> new HashMap<>()).putIfAbsent(key, sf);
        }
    }

    /** 按「类型 + 显示名」匹配已注册 Slimefun 物品（用于还原无 id 的展示物品）。 */
    public static SlimefunItem findRegisteredByAppearance(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        Map<String, SlimefunItem> bucket = APPEARANCE_INDEX.get(stack.getType());
        if (bucket == null) return null;
        ItemMeta meta = stack.getItemMeta();
        String name = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : NO_NAME;
        return bucket.get(name);
    }

    /** 附魔发光（隐藏附魔纹）。 */
    public static void glow(ItemStack item) {
        if (item == null) return;
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(m);
    }

    /**
     * 安全扣除主手 1 个物品：数量减到 0 时清空槽位（set null），避免残留 0 数量「幽灵物品」。
     *
     * <p>直接 {@code setAmount(amount - 1)} 到 0 时，槽位仍持有 0 数量的原物品引用，其类型 /
     * Slimefun 绑定不变，会被 {@code SlimefunItem.getByItem} 或 {@code getType()} 继续识别为「存在」，
     * 从而被无消耗地重复利用。到 0 必须清空槽位。</p>
     */
    public static void consumeOneInMainHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInMainHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInMainHand(null);
        else it.setAmount(left);
    }

    /** 同 {@link #consumeOneInMainHand}，作用于副手。 */
    public static void consumeOneInOffHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInOffHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInOffHand(null);
        else it.setAmount(left);
    }

    /**
     * 对副手工具施加耐久损耗。
     *
     * @return true = 已按耐久处理（含损坏消失与无敌豁免）；false = 物品不可损耗（无耐久上限）
     */
    public static boolean damageToolInOffHand(PlayerInventory inv, int amount) {
        if (inv == null) return false;
        ItemStack it = inv.getItemInOffHand();
        if (it == null || it.getType().isAir()) return false;
        if (!(it.getItemMeta() instanceof Damageable damageable)) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        if (damageable.isUnbreakable()) return true;
        int newDamage = damageable.getDamage() + Math.max(1, amount);
        if (newDamage >= max) {
            inv.setItemInOffHand(null);
            return true;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = it.clone();
        updated.setItemMeta(damageable);
        inv.setItemInOffHand(updated);
        return true;
    }

    /** 同 {@link #damageToolInOffHand}，作用于主手。 */
    public static boolean damageToolInMainHand(PlayerInventory inv, int amount) {
        if (inv == null) return false;
        ItemStack it = inv.getItemInMainHand();
        if (it == null || it.getType().isAir()) return false;
        if (!(it.getItemMeta() instanceof Damageable damageable)) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        if (damageable.isUnbreakable()) return true;
        int newDamage = damageable.getDamage() + Math.max(1, amount);
        if (newDamage >= max) {
            inv.setItemInMainHand(null);
            return true;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = it.clone();
        updated.setItemMeta(damageable);
        inv.setItemInMainHand(updated);
        return true;
    }
}
