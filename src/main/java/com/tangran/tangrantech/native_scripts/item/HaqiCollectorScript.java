package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 「哈气收集器」的原生实现（对应 {@code scripts/哈气收集.js}）。
 *
 * <p>监听 {@link EntityDamageEvent}（MONITOR）：当名为「进击的老吴」的豹猫（OCELOT）受到
 * 有效伤害时，在半径 3 格内查找 {@code TR_HQSJQ} 机器；每台有足够电量的机器扣 500 J，
 * 累计 5 次充能后向 {@code OUTPUT_SLOTS} 产出一个 {@code TR_HQ}。</p>
 *
 * <p>这里的伤害事件没有「被取消」的语义（只是观测），所以用 MONITOR 且手动判
 * {@code isCancelled()}，与脚本原写法一致。</p>
 */
public final class HaqiCollectorScript implements NativeLifecycleScript {

    private static final String MACHINE_ID = "TR_HQSJQ";
    private static final String OUTPUT_ID = "TR_HQ";
    private static final int NEED_HITS = 5;
    private static final int CHARGE_COST = 500;
    private static final int RANGE = 3;
    private static final int[] OUTPUT_SLOTS = {21, 22, 23, 30, 31, 32};
    private static final String OLDWU_NAME = "进击的老吴";

    /** 机器坐标 → 已累计命中次数。 */
    private static final Map<String, Integer> HIT_COUNT = new HashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /** 坐标键：world:x:y:z。 */
    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ':' + loc.getBlockX() + ':' + loc.getBlockY() + ':' + loc.getBlockZ();
    }

    /** 清空某坐标的累计（放置/破坏机器时调用）。 */
    public static void reset(Location loc) {
        HIT_COUNT.remove(locKey(loc));
    }

    private static boolean isTargetOcelot(Entity entity) {
        if (entity == null || entity.getType() != EntityType.OCELOT) return false;
        try {
            String name = entity.getCustomName();
            if (name == null) return false;
            return ChatColor.stripColor(name).contains(OLDWU_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Location> findMachinesNear(Location center) {
        List<Location> result = new ArrayList<>();
        var world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dy = -RANGE; dy <= RANGE; dy++) {
            for (int dx = -RANGE; dx <= RANGE; dx++) {
                for (int dz = -RANGE; dz <= RANGE; dz++) {
                    try {
                        Location loc = new Location(world, cx + dx, cy + dy, cz + dz);
                        if (StorageCacheUtils.isBlock(loc, MACHINE_ID)) {
                            result.add(loc);
                        }
                    } catch (Throwable ignored) {
                        // 单个坐标查询失败不影响整体扫描
                    }
                }
            }
        }
        return result;
    }

    /** 产出：优先堆叠到已有的同类格；否则放入第一个空格。放不下就丢弃本轮产出。 */
    private static void outputResult(BlockMenu menu) {
        SlimefunItem sf = SlimefunItem.getById(OUTPUT_ID);
        if (sf == null) return;

        for (int slot : OUTPUT_SLOTS) {
            ItemStack item = menu.getItemInSlot(slot);
            if (item == null || item.getType().isAir()) continue;
            SlimefunItem existing = SlimefunItem.getByItem(item);
            if (existing != null && OUTPUT_ID.equalsIgnoreCase(existing.getId())
                    && item.getAmount() < item.getMaxStackSize()) {
                item.setAmount(item.getAmount() + 1);
                menu.replaceExistingItem(slot, item);
                return;
            }
        }

        for (int slot : OUTPUT_SLOTS) {
            ItemStack item = menu.getItemInSlot(slot);
            if (item == null || item.getType().isAir()) {
                ItemStack stack = sf.getItem().clone();
                stack.setAmount(1);
                menu.replaceExistingItem(slot, stack);
                return;
            }
        }
    }

    /** 单次伤害事件的处理。 */
    public static void handleHit(Entity entity) {
        if (!isTargetOcelot(entity)) return;
        List<Location> machines = findMachinesNear(entity.getLocation());
        if (machines.isEmpty()) return;

        for (Location loc : machines) {
            try {
                // getSfItem 已标记待删除，用 getSlimefunItem 替代（语义相同）
                SlimefunItem machine = StorageCacheUtils.getSlimefunItem(loc);
                if (!(machine instanceof EnergyNetComponent component)) continue;

                int charge = component.getCharge(loc);
                if (charge < CHARGE_COST) continue;

                component.removeCharge(loc, CHARGE_COST);

                String key = locKey(loc);
                int hits = HIT_COUNT.getOrDefault(key, 0) + 1;
                if (hits >= NEED_HITS) {
                    HIT_COUNT.put(key, 0);
                    BlockMenu menu = StorageCacheUtils.getMenu(loc);
                    if (menu != null) outputResult(menu);
                } else {
                    HIT_COUNT.put(key, hits);
                }
            } catch (Throwable t) {
                TT.log("哈气收集处理出错: " + t);
            }
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new HitListener());
    }

    /** 伤害观测：与原脚本 MONITOR 优先级的 registerEvent 等价。 */
    private static final class HitListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDamage(EntityDamageEvent event) {
            try {
                if (event.isCancelled()) return;
                if (event.getFinalDamage() <= 0) return;
                handleHit(event.getEntity());
            } catch (Throwable t) {
                TT.log("哈气收集伤害监听出错: " + t);
            }
        }
    }
}
