package com.tangran.tangrantech.behavior;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 自定义生物掉落（mob_drops.yml）。
 *
 * <p>原 RSC 由脚本/内部表驱动掉落；原生化后保留同样的「按实体类型 + 概率」语义：
 * 生物死亡时按登记的 chance（0-100）掷点，命中则追加一件掉落物。</p>
 */
public final class MobDrops {

    private MobDrops() {}

    /** 单条掉落登记：物品 id + 概率。 */
    private static final class Entry {
        final String itemId;
        final int chance;
        Entry(String itemId, int chance) {
            this.itemId = itemId;
            this.chance = chance;
        }
    }

    /** entity 枚举名（大写）→ 掉落登记列表。 */
    private static final Map<String, List<Entry>> TABLE = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();

    /** 登记一条掉落。 */
    public static void add(String entity, String itemId, int chance) {
        if (entity == null || entity.isEmpty() || itemId == null || itemId.isEmpty()) return;
        TABLE.computeIfAbsent(entity.toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                .add(new Entry(TT.upper(itemId), Math.max(0, Math.min(100, chance))));
    }

    /** 注册掉落监听器。 */
    public static void register(org.bukkit.plugin.Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new DeathListener(), plugin);
    }

    private static final class DeathListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDeath(EntityDeathEvent e) {
            List<Entry> list = TABLE.get(e.getEntity().getType().name());
            if (list == null || list.isEmpty()) return;
            Player killer = e.getEntity().getKiller();
            for (Entry en : list) {
                if (RANDOM.nextInt(100) >= en.chance) continue;
                SlimefunItem sf = SlimefunItem.getById(en.itemId);
                if (sf == null) continue;
                ItemStack drop = sf.getItem().clone();
                if (killer != null) {
                    e.getDrops().add(drop);
                } else {
                    e.getEntity().getWorld().dropItemNaturally(e.getEntity().getLocation(), drop);
                }
            }
        }
    }
}
