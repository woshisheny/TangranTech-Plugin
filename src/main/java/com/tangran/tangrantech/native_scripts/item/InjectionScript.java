package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 「注能器」的原生实现（对应 {@code scripts/注能.js}）。
 *
 * <p>玩法：把「初级能量核心·半成品」（{@code tr_cjnlhx_bcp}）丢在注能器（{@code tr_znq}）上，
 * 物品会螺旋上升 120 刻（升高 4 格、半径 0.5、顺时针 2 圈），随后消失并按
 * 80% 概率为堆叠中的每一份产出「初级能量核心」（{@code TR_CJNLHX}）。</p>
 *
 * <p>两个实现要点（原脚本也是这么处理的）：</p>
 * <ul>
 *   <li>丢出瞬间掉落物实体可能尚未生成，因此用最多 5 次、每次间隔 1 刻的重试去定位实体；</li>
 *   <li>动画期间把掉落物设为不可拾取、无重力、无敌，避免被玩家或漏斗提前带走。</li>
 * </ul>
 */
public final class InjectionScript implements NativeLifecycleScript {

    /** 注能器自身的粘液 id。 */
    private static final String MACHINE_ID = "TR_ZNQ";
    /** 半成品 id。 */
    private static final String HALF_ID = "TR_CJNLHX_BCP";
    /** 成品 id。 */
    private static final String FULL_ID = "TR_CJNLHX";

    private static final int FLIGHT_TICKS = 120;
    private static final double RISE = 4.0;
    private static final double RADIUS = 0.5;
    private static final double SUCCESS_CHANCE = 0.8;
    private static final int SEARCH_RANGE = 3;
    private static final int RESOLVE_RETRIES = 5;

    private static final Random RANDOM = new Random();

    /** 正在动画中的掉落物实体 UUID，避免重试逻辑重复接手同一份。 */
    private static final Set<UUID> PROCESSING = Collections.synchronizedSet(new HashSet<>());

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /** 玩家丢出物品的入口。 */
    public static void handleDrop(Player player, Item dropped, ItemStack stack, Location dropLoc) {
        String id = sfIdOf(stack);
        if (id == null || !HALF_ID.equalsIgnoreCase(id)) return;
        if (dropLoc == null) return;

        Location below = dropLoc.clone().subtract(0, 1, 0);
        Location machineCenter = findMachineBlock(below);
        if (machineCenter == null) return;

        resolveItemEntity(player, dropLoc, stack, RESOLVE_RETRIES, machineCenter);
    }

    /** 取物品上的粘液 id（无则 null）。 */
    public static String sfIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        try {
            return io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                .getItemDataService().getItemData(item.getItemMeta()).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在给定坐标 ±3 格内查找注能器方块，返回其中心坐标。 */
    private static Location findMachineBlock(Location center) {
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dy = -SEARCH_RANGE; dy <= SEARCH_RANGE; dy++) {
            for (int dx = -SEARCH_RANGE; dx <= SEARCH_RANGE; dx++) {
                for (int dz = -SEARCH_RANGE; dz <= SEARCH_RANGE; dz++) {
                    try {
                        Location loc = new Location(world, cx + dx, cy + dy, cz + dz);
                    if (StorageCacheUtils.isBlock(loc, MACHINE_ID)) {
                        return loc.clone().add(0.5, 0.5, 0.5);
                    }
                    } catch (Throwable ignored) {
                        // 单格查询失败继续扫描
                    }
                }
            }
        }
        return null;
    }

    /** 在掉落点附近定位匹配 item 实体；带重试（原脚本 1.5 格半径）。 */
    private static Item findMatchingItemEntity(World world, Location loc, ItemStack original) {
        double radius = 1.5;
        for (Entity ent : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(ent instanceof Item item)) continue;
            try {
                ItemStack stack = item.getItemStack();
                if (stack == null) continue;
                String id = sfIdOf(stack);
                if (id != null && HALF_ID.equalsIgnoreCase(id)) return item;
                if (original != null && stack.isSimilar(original)) return item;
            } catch (Throwable ignored) {
                // 继续检查其它实体
            }
        }
        return null;
    }

    /** 延迟重试定位掉落物实体，成功后启动飞行动画。 */
    private static void resolveItemEntity(Player player, Location dropLoc, ItemStack stack,
                                          int remaining, Location machineCenter) {
        Runnable attempt = new Runnable() {
            @Override
            public void run() {
                try {
                    World world = dropLoc.getWorld();
                    Item found = findMatchingItemEntity(world, dropLoc, stack);
                    if (found != null && found.isValid()) {
                        UUID uid = found.getUniqueId();
                        if (PROCESSING.contains(uid)) return;
                        startFlight(found, player, machineCenter);
                        return;
                    }
                    if (remaining > 0) {
                        resolveItemEntity(player, dropLoc, stack, remaining - 1, machineCenter);
                    }
                } catch (Throwable t) {
                    TT.log("注能掉落物定位出错: " + t);
                }
            }
        };
        Bukkit.getScheduler().runTaskLater(TT.plugin, attempt, 1L);
    }

    /** 启动螺旋上升动画，结束时结算产物。 */
    private static void startFlight(Item itemEntity, Player player, Location startLoc) {
        World world = itemEntity.getWorld();
        PROCESSING.add(itemEntity.getUniqueId());

        try {
            itemEntity.setPickupDelay(Short.MAX_VALUE);
            itemEntity.setGravity(false);
            itemEntity.setInvulnerable(true);
        } catch (Throwable ignored) {
            // 属性设置失败不阻断动画
        }

        final int[] tick = {0};
        final org.bukkit.scheduler.BukkitTask[] taskRef = new org.bukkit.scheduler.BukkitTask[1];

        taskRef[0] = Bukkit.getScheduler().runTaskTimer(TT.plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    if (!itemEntity.isValid()) {
                        cancel(taskRef);
                        PROCESSING.remove(itemEntity.getUniqueId());
                        return;
                    }
                    if (tick[0] > FLIGHT_TICKS) {
                        cancel(taskRef);
                        Location loc = itemEntity.getLocation().clone();
                        ItemStack raw = itemEntity.getItemStack();
                        itemEntity.remove();
                        PROCESSING.remove(itemEntity.getUniqueId());

                        int amount = (raw != null) ? raw.getAmount() : 1;
                        for (int i = 0; i < amount; i++) {
                            if (RANDOM.nextDouble() < SUCCESS_CHANCE) {
                                SlimefunItem sf = SlimefunItem.getById(FULL_ID);
                                if (sf != null) {
                                    world.dropItemNaturally(loc, sf.getItem().clone());
                                }
                            }
                        }

                        if (RANDOM.nextDouble() < SUCCESS_CHANCE) {
                            playSound(player, "entity_tnt_primed");
                        } else {
                            playSound(player, "item.totem.use");
                        }
                        return;
                    }

                    double progress = (double) tick[0] / FLIGHT_TICKS;
                    double angle = -2.0 * Math.PI * progress;
                    double x = startLoc.getX() + RADIUS * Math.cos(angle);
                    double z = startLoc.getZ() + RADIUS * Math.sin(angle);
                    double y = startLoc.getY() + RISE * progress;
                    itemEntity.teleport(new Location(world, x, y, z));
                    world.spawnParticle(Particle.END_ROD, itemEntity.getLocation(), 2, 0.02, 0.02, 0.02, 0.0);
                    tick[0]++;
                } catch (Throwable t) {
                    TT.log("注能动画出错: " + t);
                }
            }
        }, 0L, 1L);
    }

    private static void cancel(org.bukkit.scheduler.BukkitTask[] ref) {
        try {
            if (ref[0] != null) ref[0].cancel();
        } catch (Throwable ignored) {
            // 已结束的任务忽略
        }
    }

    private static void playSound(Player player, String sound) {
        try {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 音效缺失忽略
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new DropListener());
    }

    /** 丢出监听：与原脚本 registerEvent(PlayerDropItemEvent, ...) 等价。 */
    private static final class DropListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            try {
                Item dropped = event.getItemDrop();
                if (dropped == null) return;
                ItemStack stack = dropped.getItemStack();
                if (stack == null) return;
                Location dropLoc = dropped.getLocation().clone();
                handleDrop(event.getPlayer(), dropped, stack, dropLoc);
            } catch (Throwable t) {
                TT.log("注能处理出错: " + t);
            }
        }
    }
}
