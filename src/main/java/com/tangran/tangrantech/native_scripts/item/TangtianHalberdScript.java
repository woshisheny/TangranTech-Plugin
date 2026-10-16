package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 「糖天画戟」的原生实现（对应 {@code scripts/糖天画戟.js}）。一把武器的主动技能。
 *
 * <p>手持 {@code TR_TTHJ} 时的两个技能，冷却互相独立：</p>
 * <ol>
 *   <li><b>右键</b>：按玩家丢药水的曲线发射一瓶 {@code TR_ZSLQTS}，播放 pling，冷却 5 秒。</li>
 *   <li><b>蹲下右键</b>：进入蓄力，每 2 刻刷新一次前方 160° / 半径 6 格的扇形粒子与绿色闪烁标题
 *       （33% → 66% → 99% → 100%）；满 60 刻后对扇形内所有非自身生物造成
 *       {@code 1145 + 目标最大生命 ×14%} 伤害，并播爆炸/铁轨/雷声混合音效，冷却 6 秒。
 *       中途松蹲、切换武器、死亡或下线则取消蓄力且<em>不</em>进入冷却。</li>
 *   <li><b>左键额外伤害</b>：命中时追加 {@code 10 + 目标最大生命 ×2%} 的虚空伤害；被技能打死的
 *       非玩家生物有 60% 概率掉落 {@code TR_YHS}。</li>
 * </ol>
 *
 * <p>与 1.21 的适配：{@code Vector#rotateAroundY} 在 1.21 起入参由 {@code float}
 * 改为 {@code double}，这里直接传 double；标题改走 Adventure，避免使用已废弃的
 * {@code sendTitle}。</p>
 */
public final class TangtianHalberdScript implements NativeLifecycleScript {

    private static final String ITEM_ID = "TR_TTHJ";
    private static final String POTION_ID = "TR_ZSLQTS";
    private static final String DROP_ID = "TR_YHS";

    /** 丢药水冷却（ms）。 */
    private static final long THROW_COOLDOWN_MS = 5000L;
    /** 蓄力释放后的冷却（ms）。原脚本注释写「10 秒」，但代码是 6000，以代码为准。 */
    private static final long CHARGE_COOLDOWN_MS = 6000L;

    /** 蓄力总时长（刻），每 2 刻推进一次。 */
    private static final int CHARGE_TICKS = 60;
    /** 蓄力推进步长（刻）。 */
    private static final int CHARGE_STEP = 2;
    /** 标题显示时长（刻）。 */
    private static final int TITLE_STAY_TICKS = 5;

    /** 扇形的半径（格）与张角（度）。 */
    private static final double SECTOR_RADIUS = 6.0;
    private static final double SECTOR_ANGLE = 160.0;
    /** 扇形粒子绘制的角度步长与半径步长。 */
    private static final double SECTOR_ANGLE_STEP = 10.0;
    private static final double SECTOR_RADIUS_STEP = 1.5;

    /** 音效可及半径（格）。 */
    private static final double SOUND_RADIUS = 10.0;

    /** 蓄力横扫基础伤害。 */
    private static final double SWEEP_DAMAGE = 1145.0;
    /** 蓄力横扫附加伤害：目标最大生命值的百分比。 */
    private static final double SWEEP_DAMAGE_PERCENT = 14.0;

    /** 左键追击基础伤害与百分比。 */
    private static final double BONUS_DAMAGE = 10.0;
    private static final double BONUS_DAMAGE_PERCENT = 2.0;

    /** 技能击杀非玩家生物时掉落 {@code TR_YHS} 的概率。 */
    private static final double DROP_CHANCE = 0.6;

    /** 方向向量长度平方的零阈值（与脚本里的 0.0001 一致）。 */
    private static final double EPSILON_SQ = 0.0001;

    /** 玩家 UUID → 丢药水冷却截止时间（ms）。 */
    private static final Map<UUID, Long> THROW_COOLDOWNS = new ConcurrentHashMap<>();
    /** 玩家 UUID → 蓄力冷却截止时间（ms）。 */
    private static final Map<UUID, Long> CHARGE_COOLDOWNS = new ConcurrentHashMap<>();
    /** 玩家 UUID → 进行中的蓄力。 */
    private static final Map<UUID, Charge> CHARGING = new ConcurrentHashMap<>();
    /** 被糖天画戟技能命中的实体，用于在死亡事件里判断是否需要掉落。 */
    private static final Set<UUID> SKILL_HIT = ConcurrentHashMap.newKeySet();
    /** 防止左键追加伤害触发的二次事件递归。 */
    private static final Set<UUID> BONUS_GUARD = ConcurrentHashMap.newKeySet();

    private static final java.util.Random RANDOM = new java.util.Random();

    @Override
    public Object invoke(String fn, Object... args) {
        // 原脚本只靠监听器驱动，没有对外回调函数
        return null;
    }

    /** 把 {@code &} 颜色码转成 {@code §}。 */
    private static String colorize(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    /**
     * 判断物品是否为糖天画戟。
     *
     * <p><b>刻意只认粘液 id，绝不用显示名兜底</b>：原脚本注释记录过这个坑——
     * 早期加了「名字里含『糖天画戟』就放行」的兜底，等于铁砧改名即可白拿技能效果。</p>
     */
    public static boolean isWeapon(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        try {
            String id = TT.sfIdOf(item);
            return id != null && ITEM_ID.equalsIgnoreCase(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把武器设为不可破坏（避免高频技能把耐久打光）。 */
    private static void ensureUnbreakable(Player player, ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && !meta.isUnbreakable()) {
                meta.setUnbreakable(true);
                item.setItemMeta(meta);
                player.getInventory().setItemInMainHand(item);
            }
        } catch (Throwable ignored) {
            // 元数据不可用时忽略
        }
    }

    /** 冷却提示。 */
    private static void sendCooldownMessage(Player player, long endTime) {
        try {
            String remain = String.format("%.1f",
                    Math.max(0, endTime - System.currentTimeMillis()) / 1000.0);
            player.sendMessage(colorize("&7&l冷却中(&4&l" + remain + "…&7&l)"));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 技能 1：丢出一瓶 {@code TR_ZSLQTS}。 */
    private static void throwPotion(Player player) {
        try {
            long now = System.currentTimeMillis();
            UUID id = player.getUniqueId();
            long end = THROW_COOLDOWNS.getOrDefault(id, 0L);
            if (now < end) {
                sendCooldownMessage(player, end);
                return;
            }

            SlimefunItem sf = SlimefunItem.getById(POTION_ID);
            if (sf == null) return;
            ItemStack stack = sf.getItem().clone();

            // 模仿玩家丢药水的曲线
            ThrownPotion potion = player.launchProjectile(ThrownPotion.class);
            if (potion == null) return;
            potion.setItem(stack);
            potion.setShooter(player);

            player.playSound(player.getLocation(), "block.note_block.pling", 1.0f, 2.0f);
            THROW_COOLDOWNS.put(id, now + THROW_COOLDOWN_MS);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 目标是否落在以 center 为顶点、朝向 baseDir 的扇形内（只考虑水平面）。 */
    private static boolean isInSector(Location center, Vector baseDir, Location targetLoc,
                                      double radius, double angleDeg) {
        try {
            Vector to = targetLoc.toVector().subtract(center.toVector());
            to.setY(0);
            if (to.lengthSquared() > radius * radius) return false;
            // 与顶点重合视作命中
            if (to.lengthSquared() < EPSILON_SQ) return true;

            Vector base = baseDir.clone();
            base.setY(0);
            base.normalize();
            Vector norm = to.normalize();
            double dot = base.dot(norm);
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot))) * 180.0 / Math.PI;
            return angle <= angleDeg / 2.0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 绘制扇形蓄力粒子：每 10° 一条射线，射线上每 1.5 格一组粒子。 */
    private static void spawnSectorParticles(Player player) {
        try {
            World world = player.getWorld();
            Location center = player.getLocation().clone().add(0, 1, 0);
            Vector base = player.getLocation().getDirection().clone();
            base.setY(0);
            if (base.lengthSquared() < EPSILON_SQ) base.setZ(1);
            base.normalize();

            // 用 double 累加复刻 JS 的浮点步进（angle += 10 / r += 1.5）
            for (double angle = -SECTOR_ANGLE / 2.0; angle <= SECTOR_ANGLE / 2.0;
                 angle += SECTOR_ANGLE_STEP) {
                Vector dir = base.clone().rotateAroundY(angle * Math.PI / 180.0);
                for (double r = 1.0; r <= SECTOR_RADIUS; r += SECTOR_RADIUS_STEP) {
                    try {
                        Location loc = center.clone().add(dir.getX() * r, 0, dir.getZ() * r);
                        world.spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0.1, 0.1, 0.1, 0);
                        world.spawnParticle(Particle.CRIT, loc, 1, 0.1, 0.1, 0.1, 0);
                        world.spawnParticle(Particle.FLAME, loc, 1, 0.1, 0.1, 0.1, 0);
                    } catch (Throwable ignored) {
                        // 单点粒子失败跳过
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 对半径内玩家播音效（注意：播在每个玩家自己的位置上，与原脚本一致）。 */
    private static void playSoundToNearby(World world, Location center,
                                          String sound, float volume, float pitch, double radius) {
        try {
            for (Player p : world.getPlayers()) {
                try {
                    if (p.getLocation().distance(center) <= radius) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                } catch (Throwable ignored) {
                    // 跨世界等异常跳过
                }
            }
        } catch (Throwable ignored) {
            // 无玩家
        }
    }

    /** 蓄力进度对应的百分比（原脚本的三段阈值）。 */
    private static int getChargePercent(int ticks) {
        if (ticks < 20) return 33;
        if (ticks < 40) return 66;
        if (ticks < 58) return 99;
        return 100;
    }

    /** 技能 2：开始蓄力。 */
    private static void startCharge(Player player) {
        try {
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            long end = CHARGE_COOLDOWNS.getOrDefault(id, 0L);
            if (now < end) {
                sendCooldownMessage(player, end);
                return;
            }
            if (CHARGING.containsKey(id)) return;

            Charge charge = new Charge(player, id);
            CHARGING.put(id, charge);

            charge.task = org.bukkit.Bukkit.getScheduler().runTaskTimer(
                    NativePluginHelper.javaPlugin(), () -> tickCharge(charge), 0L, CHARGE_STEP);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 蓄力的一次推进。 */
    private static void tickCharge(Charge charge) {
        try {
            Player player = charge.player;

            // 玩家死亡 / 离线 / 松蹲 / 切换武器 => 取消蓄力，且不进入冷却
            if (!player.isOnline() || player.isDead() || !player.isSneaking()
                    || !isWeapon(player.getInventory().getItemInMainHand())) {
                cancelCharge(charge);
                return;
            }

            charge.ticks += CHARGE_STEP;

            if (charge.ticks >= CHARGE_TICKS) {
                cancelCharge(charge);
                executeSweep(player);
                CHARGE_COOLDOWNS.put(charge.id,
                        System.currentTimeMillis() + CHARGE_COOLDOWN_MS);
                return;
            }

            // 绿色闪烁标题
            int percent = getChargePercent(charge.ticks);
            String color = ((int) Math.floor(charge.ticks / 5.0) % 2 == 0) ? "§a" : "§2";
            showChargeTitle(player, color + "蓄力中:" + percent + "%");

            spawnSectorParticles(player);

            float pitch = (float) (0.5 + percent / 100.0);
            playSoundToNearby(player.getWorld(), player.getLocation(),
                    "block.note_block.pling", 0.8f, pitch, SOUND_RADIUS);
        } catch (Throwable ignored) {
            // 蓄力任务不抛异常
        }
    }

    /** 取消蓄力（幂等）。 */
    private static void cancelCharge(Charge charge) {
        CHARGING.remove(charge.id);
        if (charge.task != null) {
            try {
                charge.task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
    }

    /** 显示蓄力标题（0 / 5 / 0 刻）。 */
    private static void showChargeTitle(Player player, String text) {
        try {
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection();
            player.showTitle(net.kyori.adventure.title.Title.title(
                    ser.deserialize(text),
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ZERO,
                            java.time.Duration.ofMillis(TITLE_STAY_TICKS * 50L),
                            java.time.Duration.ZERO)));
        } catch (Throwable ignored) {
            // 标题失败不影响蓄力
        }
    }

    /** 蓄力完成：扇形横扫 + 混合音效。 */
    private static void executeSweep(Player player) {
        try {
            World world = player.getWorld();
            Location center = player.getLocation().clone();
            Vector baseDir = player.getLocation().getDirection().clone();
            baseDir.setY(0);
            if (baseDir.lengthSquared() < EPSILON_SQ) baseDir.setZ(1);
            baseDir.normalize();

            // 最终横扫粒子
            spawnSectorParticles(player);

            Collection<Entity> nearby = world.getNearbyEntities(center,
                    SECTOR_RADIUS, SECTOR_RADIUS, SECTOR_RADIUS);
            for (Entity entity : nearby) {
                try {
                    if (!(entity instanceof LivingEntity living)) continue;
                    // 蓄力横扫不伤害自己
                    if (living == player) continue;
                    if (!isInSector(center, baseDir, living.getLocation(),
                            SECTOR_RADIUS, SECTOR_ANGLE)) continue;

                    // 每个目标独立计算：1145 + 目标最大生命 ×14%
                    double damage = SWEEP_DAMAGE + living.getMaxHealth() * SWEEP_DAMAGE_PERCENT / 100.0;
                    UUID eid = living.getUniqueId();
                    SKILL_HIT.add(eid);
                    living.damage(damage, player);
                    // 没死就不需要走掉落判定，及时清掉避免集合膨胀
                    if (!living.isDead()) {
                        SKILL_HIT.remove(eid);
                    }
                } catch (Throwable ignored) {
                    // 单个目标失败跳过
                }
            }

            playSoundToNearby(world, center, "entity.generic.explode", 1.5f, 0.8f, SOUND_RADIUS);
            playSoundToNearby(world, center, "block.rail", 1.5f, 1.2f, SOUND_RADIUS);
            playSoundToNearby(world, center, "entity.lightning_bolt.thunder",
                    1.5f, 0.7f, SOUND_RADIUS);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 左键命中：追加虚空伤害。 */
    private static void applyLeftClickBonus(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof Player damager)) return;
            if (!(event.getEntity() instanceof LivingEntity entity)) return;
            if (entity == damager) return;
            if (event.isCancelled()) return;

            EntityDamageEvent.DamageCause cause = event.getCause();
            if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                return;
            }

            ItemStack item = damager.getInventory().getItemInMainHand();
            if (!isWeapon(item)) return;
            ensureUnbreakable(damager, item);

            UUID eid = entity.getUniqueId();
            if (BONUS_GUARD.contains(eid)) return;

            double extra = BONUS_DAMAGE + entity.getMaxHealth() * BONUS_DAMAGE_PERCENT / 100.0;

            // 延后一刻施加，避免与本次伤害结算互相干扰
            NativePluginHelper.runLater(() -> {
                try {
                    if (!entity.isValid() || entity.isDead()) return;
                    BONUS_GUARD.add(eid);
                    entity.setNoDamageTicks(0);
                    entity.damage(extra, damager);
                    entity.setNoDamageTicks(entity.getMaximumNoDamageTicks());
                } catch (Throwable ignored) {
                    // 目标已消失
                } finally {
                    BONUS_GUARD.remove(eid);
                }
            }, 0L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 技能击杀：非玩家生物 60% 概率掉落 {@code TR_YHS}。 */
    private static void handleDeath(EntityDeathEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            UUID eid = entity.getUniqueId();
            if (!SKILL_HIT.remove(eid)) return;
            // 掉落只针对非玩家生物
            if (entity instanceof Player) return;

            if (RANDOM.nextDouble() < DROP_CHANCE) {
                SlimefunItem sf = SlimefunItem.getById(DROP_ID);
                if (sf != null) {
                    Location loc = entity.getLocation();
                    loc.getWorld().dropItemNaturally(loc, sf.getItem().clone());
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 一次蓄力的状态。 */
    private static final class Charge {
        final Player player;
        final UUID id;
        BukkitTask task;
        int ticks;

        Charge(Player player, UUID id) {
            this.player = player;
            this.id = id;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new EventListener());
    }

    /** 全部事件入口。 */
    public static final class EventListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
                Player player = event.getPlayer();
                ItemStack item = event.getItem() != null
                        ? event.getItem()
                        : player.getInventory().getItemInMainHand();
                if (!isWeapon(item)) return;
                ensureUnbreakable(player, item);

                if (player.isSneaking()) {
                    startCharge(player);
                } else {
                    throwPotion(player);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDamageByEntity(EntityDamageByEntityEvent event) {
            applyLeftClickBonus(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(EntityDeathEvent event) {
            handleDeath(event);
        }
    }

    /** 供调试/卸载使用：取消全部蓄力。 */
    public static void shutdown() {
        for (Charge charge : CHARGING.values()) {
            cancelCharge(charge);
        }
        CHARGING.clear();
        SKILL_HIT.clear();
        BONUS_GUARD.clear();
    }
}
