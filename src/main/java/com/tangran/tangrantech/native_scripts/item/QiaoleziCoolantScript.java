package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「俏了滋牌冷却剂获取」的原生实现（对应 {@code scripts/俏了滋牌冷却剂获取.js}）。
 *
 * <p>右键 {@code TR_ZXF}：消耗一个，把玩家移速锁 0（10 刻后恢复），同时在前方生成一个
 * 名为「§f§l彰学风」的雪傀儡并朝玩家视线方向匀速平移。100 刻内玩家追上（沿视线方向的
 * 投影坐标 ≥ 雪傀儡）则雪傀儡消失并掉落 {@code TR_QLZPLQJ}；追不上则提示
 * 「你跑不过我你信吗」并直接清除雪傀儡。</p>
 *
 * <p>冷却 2000ms，冷却中直接取消交互事件。原脚本在玩家退出时没有停掉遗留任务，
 * 这里补了 {@code PlayerQuitEvent} 清理，避免玩家下线后任务空转。</p>
 */
public final class QiaoleziCoolantScript implements NativeLifecycleScript {

    private static final String ITEM_ID = "TR_ZXF";
    private static final String REWARD_ID = "TR_QLZPLQJ";

    private static final long COOLDOWN_MS = 2000L;
    /** 定身时长（刻）。 */
    private static final long FREEZE_TICKS = 10L;
    /** 雪傀儡存活时长（刻）。 */
    private static final int FLIGHT_TICKS = 100;
    private static final double STEP = 0.35;

    /** 玩家 UUID → 上次使用时间（ms）。 */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    /** 玩家 UUID → 正在进行的雪傀儡移动任务。 */
    private static final Map<UUID, BukkitTask> ACTIVE_TASKS = new HashMap<>();
    /** 玩家 UUID → 正在场上的雪傀儡实体。 */
    private static final Map<UUID, Entity> ACTIVE_ENTITIES = new HashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /** 右键入口。 */
    public static void handle(Player player) {
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;

        if (!removeOneMatchingItem(player, ITEM_ID)) return;
        COOLDOWNS.put(player.getUniqueId(), now);

        freezeBriefly(player);
        spawnMovingGolem(player);
    }

    /** 短暂定身：移速置 0，FREEZE_TICKS 刻后恢复原值。 */
    private static void freezeBriefly(Player player) {
        float original = player.getWalkSpeed();
        player.setWalkSpeed(0f);
        NativePluginHelper.runLater(() -> {
            try {
                if (player.isOnline()) player.setWalkSpeed(original);
            } catch (Throwable ignored) {
                // 玩家已离线则无需恢复
            }
        }, FREEZE_TICKS);
    }

    /** 从背包移除一个指定 id 的粘液物品；成功返回 true。 */
    public static boolean removeOneMatchingItem(Player player, String sfid) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            String id = TT.sfIdOf(stack);
            if (id != null && id.equalsIgnoreCase(sfid)) {
                if (stack.getAmount() > 1) {
                    stack.setAmount(stack.getAmount() - 1);
                    inv.setItem(i, stack);
                } else {
                    inv.setItem(i, null);
                }
                return true;
            }
        }
        return false;
    }

    /** 生成向前匀速移动的雪傀儡。 */
    private static void spawnMovingGolem(Player player) {
        World world = player.getWorld();
        Location startLoc = player.getLocation().clone();
        startLoc.setY(startLoc.getY() + 1.0);

        // 先算好方向再赋给 blank final：分支里重复赋值会让 dir 无法被下面的匿名类捕获
        final org.bukkit.util.Vector dir;
        org.bukkit.util.Vector raw = startLoc.getDirection().clone();
        raw.setY(0);
        if (raw.lengthSquared() == 0) {
            dir = new org.bukkit.util.Vector(1, 0, 0);
        } else {
            dir = raw.normalize();
        }

        Entity entity = world.spawnEntity(startLoc, org.bukkit.entity.EntityType.SNOW_GOLEM);
        entity.setCustomName("§f§l彰学风");
        entity.setCustomNameVisible(true);
        entity.setPersistent(false);

        UUID uuid = player.getUniqueId();

        // 同一个玩家重复右键时，先清掉上一只，避免刷屏
        BukkitTask oldTask = ACTIVE_TASKS.remove(uuid);
        if (oldTask != null) {
            try {
                oldTask.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
        Entity old = ACTIVE_ENTITIES.remove(uuid);
        if (old != null && old.isValid()) {
            try {
                old.remove();
            } catch (Throwable ignored) {
                // 已移除
            }
        }

        ACTIVE_ENTITIES.put(uuid, entity);

        BukkitTask task = new BukkitRunnable() {
            private int ticks = 0;
            private boolean crossed = false;

            @Override
            public void run() {
                try {
                    ticks++;

                    Player online = Bukkit.getPlayer(player.getName());
                    if (online == null || !entity.isValid()) {
                        ACTIVE_TASKS.remove(uuid);
                        ACTIVE_ENTITIES.remove(uuid);
                        cancel();
                        return;
                    }

                    Location next = entity.getLocation().clone().add(dir.clone().multiply(STEP));
                    entity.teleport(next);

                    if (!crossed) {
                        double playerProj = online.getLocation().toVector().dot(dir);
                        double golemProj = entity.getLocation().toVector().dot(dir);
                        if (playerProj >= golemProj) {
                            crossed = true;
                            Location dropLoc = entity.getLocation().clone();
                            entity.remove();
                            ACTIVE_ENTITIES.remove(uuid);
                            giveReward(world, dropLoc, online);
                            cancel();
                            ACTIVE_TASKS.remove(uuid);
                            return;
                        }
                    }

                    if (ticks >= FLIGHT_TICKS) {
                        online.sendMessage("你跑不过我你信吗");
                        entity.remove();
                        ACTIVE_ENTITIES.remove(uuid);
                        cancel();
                        ACTIVE_TASKS.remove(uuid);
                    }
                } catch (Throwable t) {
                    TT.log("俏了滋牌冷却剂处理出错: " + t);
                }
            }
        }.runTaskTimer(NativePluginHelper.javaPlugin(), 0L, 1L);

        ACTIVE_TASKS.put(uuid, task);
    }

    /** 掉落奖励并播放雪花特效。 */
    private static void giveReward(World world, Location dropLoc, Player online) {
        SlimefunItem sf = SlimefunItem.getById(ITEM_ID);
        if (sf == null) {
            online.sendMessage("§c无法生成" + ITEM_ID + "物品，请联系管理");
            return;
        }
        ItemStack reward = sf.getItem().clone();
        world.dropItemNaturally(dropLoc, reward);
        online.sendMessage("§f§l彰学风：§7喏，冷却剂给你。");

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Location p = dropLoc.clone().add(dx, 0.5, dz);
                world.spawnParticle(Particle.SNOWFLAKE, p, 8, 0.3, 0.3, 0.3, 0.05);
            }
        }
        online.playSound(dropLoc, "block.amethyst_block.break", 0.7f, 1.0f);
    }

    /** 玩家退出时清掉遗留实体与任务，防止任务空转。 */
    public static void cleanup(UUID uuid) {
        BukkitTask task = ACTIVE_TASKS.remove(uuid);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
        Entity entity = ACTIVE_ENTITIES.remove(uuid);
        if (entity != null && entity.isValid()) {
            try {
                entity.remove();
            } catch (Throwable ignored) {
                // 已移除
            }
        }
        COOLDOWNS.remove(uuid);
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new UseListener());
    }

    /** 右键派发。 */
    private static final class UseListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
                ItemStack held = event.getItem();
                if (held == null || held.getType().isAir()) return;
                String id = TT.sfIdOf(held);
                if (id == null || !ITEM_ID.equalsIgnoreCase(id)) return;
                event.setCancelled(true);
                handle(event.getPlayer());
            } catch (Throwable t) {
                TT.log("俏了滋牌冷却剂交互出错: " + t);
            }
        }
    }
}
