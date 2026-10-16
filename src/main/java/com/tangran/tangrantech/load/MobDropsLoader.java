package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 mob_drops.yml：自定义生物掉落物。
 *
 * <p>掉落来源不是标准 recipe/recipe_type 字段，因此展示堆用「居中生物刷怪蛋」覆盖，
 * 配方类型固定为 {@link RecipeType#MOB_DROP}；实际掉落由 {@code MobDrops} 运行时表驱动。</p>
 */
public final class MobDropsLoader {

    private MobDropsLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "mob_drops.yml");
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
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                // 实机日志修正（TR_DLJX NPE）：Slimefun 的 RecipeType.registerMobDrop
                // 读 recipe[4]（中心槽）的 displayName 解析 EntityType（去色→大写→空格转下划线）。
                // 因此必须：① 9 槽布局、来源蛋居中；② 蛋的显示名 = 实体枚举名。
                // 原先 1 个元素的数组 → 中心槽 null → 加载期 NPE。
                ItemStack[] recipe = new ItemStack[9];
                RecipeType type = RecipeType.MOB_DROP;
                ItemStack center = namedEgg(s.getString("entity"));
                if (center != null) {
                    recipe[4] = center;
                } else {
                    // 实体名不可解析（registerMobDrop 会抛异常拖垮整个物品）：
                    // 退回无配方展示，掉落仍由 behavior.MobDrops 按 chance 驱动
                    type = RecipeType.NULL;
                }
                SlimefunItem item = new com.tangran.tangrantech.items.TTUnplaceableItem(
                        g, sfis, type, recipe);
                item.register(TT.plugin);
                registeredIds.add(id);
                String entity = s.getString("entity");
                int chance = s.getInt("chance", 100);
                if (entity != null && !entity.isEmpty()) {
                    com.tangran.tangrantech.behavior.MobDrops.add(entity, id, chance);
                }
                ok++;
            } catch (Exception e) {
                TT.log("mob_drops.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        TT.plugin.getLogger().info("mob_drops.yml: 注册 " + ok + ", 跳过 " + skip);

        // 防双倍掉落：注册成功后 Slimefun 原生表会 100% 掉落该物品，
        // 而我们的 behavior.MobDrops 按 yml 的 chance 概率掉——把原生表里自己的条目摘掉，
        // 掉落单一来源归 behavior.MobDrops。
        try {
            java.util.Set<String> ours = registeredIds;
            var drops = io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                    .getRegistry().getMobDrops();
            for (java.util.Set<ItemStack> set : drops.values()) {
                set.removeIf(st -> {
                    SlimefunItem it = SlimefunItem.getByItem(st);
                    return it != null && ours.contains(it.getId());
                });
            }
        } catch (Throwable t) {
            TT.log("[mob_drops] 清理 Slimefun 原生掉落表失败（可能双倍掉落）: " + t);
        }
    }

    /** 注册成功的 mob drop 物品 id（用于注册后摘掉 Slimefun 原生掉落表）。 */
    private static final java.util.Set<String> registeredIds = new java.util.HashSet<>();

    /**
     * 中心槽来源蛋：显示名 = 实体枚举名（Slimefun 据此 EntityType.valueOf）。
     *
     * <p>实体名不合法（valueOf 抛异常会让整个物品加载失败）或没有刷怪蛋材质时，
     * 退回无名蛋 + 普通配方占位——此时改用 {@link RecipeType#NULL}，
     * 掉落仍由 behavior.MobDrops 驱动，只是向导里不显示 MOB_DROP 配方。</p>
     *
     * @return recipe[4] 应放的物品；若实体名不可解析返回 null（调用方退回 NULL 配方类型）
     */
    private static ItemStack namedEgg(String entity) {
        String name = (entity == null) ? "" : entity.trim().toUpperCase(java.util.Locale.ROOT);
        org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
        org.bukkit.Material egg = org.bukkit.Material.matchMaterial(type.name() + "_SPAWN_EGG");
        ItemStack it = new ItemStack(egg != null ? egg : org.bukkit.Material.STONE);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }
}
