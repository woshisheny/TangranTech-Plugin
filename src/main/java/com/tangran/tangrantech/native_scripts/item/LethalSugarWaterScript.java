package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 「致死量全糖水」的原生实现（对应 {@code scripts/致死量全糖水.js}）。
 *
 * <p>物品 {@code TR_ZSLQTS}（喷溅药水材质）。投出后炸开的落点为中心，在
 * <b>水平 9×9、高 3 格</b>范围内结算：</p>
 * <ul>
 *   <li><b>无命名生物</b>：无视护甲、直接扣最大生命值的 20%（走 {@code setHealth}）。</li>
 *   <li><b>玩家</b>（投掷者本人除外）：同样扣 20% 最大生命，再获得速度 / 反胃 / 跳跃提升
 *       各 III 级、持续 8 秒；随机播一条整活提示；并进入「糖分惩罚」状态。</li>
 *   <li><b>糖分惩罚</b>：8 秒内每 0.5 秒检测一次位移，若几乎没动就再扣 1 点真实伤害；
 *       命中瞬间提示一次「爷们要运动起来！」，之后每 4 秒重复提醒。</li>
 * </ul>
 *
 * <p>关键适配点：</p>
 * <ol>
 *   <li><b>投掷必须自己实现</b>：原脚本注释已定位——RSC 对带 {@code script:} 的物品会在
 *       {@code PlayerRightClickEvent} 上无条件 {@code cancel()}，原版投掷永远不发生，
 *       所以脚本自己 {@code launchProjectile} 并手动扣物品。本插件的 {@code ScriptItem}
 *       走的是 {@code ScriptItemListener} → {@code onUse} 通道，<b>原版投掷不会被拦</b>，
 *       因此这里必须显式 {@code setCancelled(true)}，否则会出现「自定义投掷 + 原版投掷」
 *       双发、且多扣一颗。</li>
 *   <li><b>初速照抄原版抛射</b>：准星方向 + 俯仰抬高 20°，速度 0.5。{@code launchProjectile}
 *       默认 1.5 会飞得远得多。</li>
 *   <li><b>1.21 效果名变更</b>：{@code CONFUSION} → {@code NAUSEA}、
 *       {@code JUMP} → {@code JUMP_BOOST}（1.20.5 起），此处用新名。</li>
 * </ol>
 */
public final class LethalSugarWaterScript implements NativeLifecycleScript {

    /** 物品 id（yml 里写作 {@code Tr_zslqts}，比对统一转大写）。 */
    private static final String ITEM_ID = "TR_ZSLQTS";

    /** 首次伤害：目标最大生命值的百分比。 */
    private static final double INITIAL_DAMAGE_PERCENT = 20.0;
    /** buff 持续刻数 = 8 秒。 */
    private static final int BUFF_TICKS = 160;
    /** buff 等级（0 基），2 即游戏内显示的 III。 */
    private static final int BUFF_AMPLIFIER = 2;

    /** 作用范围：水平半径 4（→ 9×9）、垂直半径 1（→ 高 3）。 */
    private static final double RANGE_XZ = 4.0;
    private static final double RANGE_Y = 1.0;

    /** 糖分惩罚：检测周期（刻）= 0.5 秒。 */
    private static final int PUNISH_PERIOD = 10;
    /** 糖分惩罚：总检测次数（16 × 0.5 秒 = 8 秒）。 */
    private static final int PUNISH_STEPS = 16;
    /** 糖分惩罚：每 N 次检测提醒一次（8 × 0.5 秒 = 4 秒）。 */
    private static final int PUNISH_REMIND_EVERY = 8;
    /** 判定「没动」的位移平方阈值。 */
    private static final double MOVE_EPSILON_SQ = 0.01;
    /** 静止时每次扣除的真实伤害。 */
    private static final double PUNISH_DAMAGE = 1.0;

    /** 波纹扩散：最多绘制到半径 2（原脚本 step >= 2 即停）。 */
    private static final int RIPPLE_MAX_STEP = 2;
    /** 波纹采样角度步长（度）。 */
    private static final double RIPPLE_ANGLE_STEP = 15.0;

    /** 投掷初速（照抄原版抛射）。 */
    private static final double THROW_SPEED = 0.5;
    /** 投掷俯仰抬高角度（度）。 */
    private static final double THROW_PITCH_OFFSET_DEG = -20.0;

    private static final String[] MESSAGES = {
            "&4甜蜜暴击~",
            "&f兄弟给你糖丸了",
            "&d全糖去冰，送走喵~",
            "&6糖分阉割中ing...",
    };

    private static final String PUNISH_MESSAGE = "&7&l爷们要运动起来！燃烧掉多余糖分!";

    private static final Random RANDOM = new Random();

    /** 玩家 UUID → 进行中的糖分惩罚任务。 */
    private static final Map<UUID, BukkitTask> PUNISH_TASKS = new ConcurrentHashMap<>();

    /**
     * 物品标记键缓存。
     *
     * <p>原脚本用它做一个 PDC 兜底判定，但<b>全项目没有任何地方写入过这个键</b>，
     * 属于永不命中的死分支。这里保留同样的判定以求行为一致（代价可忽略）。</p>
     *
     * <p>刻意做成惰性解析：{@code NamespacedKey} 的构造器会校验插件实例非空，
     * 而本类可能在 {@code TT.plugin} 赋值之前就被类加载。</p>
     */
    private static volatile NamespacedKey markKey;

    private static NamespacedKey markKey() {
        NamespacedKey k = markKey;
        if (k != null) return k;
        try {
            k = new NamespacedKey(TT.plugin, "zslqts");
            markKey = k;
            return k;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"onUse".equals(fn)) return null;
        if (args.length == 0 || !(args[0] instanceof PlayerInteractEvent event)) {
            return Boolean.FALSE;
        }
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                return Boolean.FALSE;
            }
            // 只处理主手，避免副手事件重复投掷
            if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
                return Boolean.FALSE;
            }

            Player player = event.getPlayer();
            ItemStack stack = player.getInventory().getItemInMainHand();
            if (!isOurPotion(stack)) return Boolean.FALSE;

            // 必须先取消：否则原版会把同一瓶药水再丢一次，并额外扣掉一颗
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 事件不可取消时继续
            }
            throwPotion(player, stack);
            return Boolean.TRUE;
        } catch (Throwable t) {
            TT.log("致死量全糖水 onUse 出错: " + t);
            return Boolean.FALSE;
        }
    }

    // ------------------------------------------------------------------
    // 物品身份 / 投掷
    // ------------------------------------------------------------------

    /**
     * 判断物品是否为致死量全糖水。
     *
     * <p><b>刻意只认粘液 id（PDC）与自建标记，绝不用显示名</b>：原脚本注释记录过，
     * 改名后的原版喷溅药水在溅射判定里认不出 id，因此不会触发本脚本——这个「口子」是被刻意封住的。</p>
     */
    public static boolean isOurPotion(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        try {
            String id = TT.sfIdOf(item);
            if (id != null && ITEM_ID.equalsIgnoreCase(id)) return true;
        } catch (Throwable ignored) {
            // 继续走 PDC 兜底
        }
        try {
            NamespacedKey key = markKey();
            if (key != null && item.hasItemMeta()) {
                return item.getItemMeta().getPersistentDataContainer()
                        .has(key, PersistentDataType.STRING);
            }
        } catch (Throwable ignored) {
            // 无 PDC 支持
        }
        return false;
    }

    /** 丢出药水：自定义初速 + 手动扣物品 + 投掷音效。 */
    private static void throwPotion(Player player, ItemStack stack) {
        try {
            ThrownPotion potion = player.launchProjectile(ThrownPotion.class);
            if (potion == null) return;

            // 带上原物品（粘液 id 一并进投掷物，溅射判定靠它认物品）；数量固定 1
            ItemStack thrown = stack.clone();
            thrown.setAmount(1);
            potion.setItem(thrown);
            potion.setShooter(player);

            // 初速照抄原版抛射
            try {
                Location eye = player.getEyeLocation();
                double yaw = Math.toRadians(eye.getYaw());
                double pitch = Math.toRadians(eye.getPitch());
                double offset = Math.toRadians(THROW_PITCH_OFFSET_DEG);
                double vx = -Math.sin(yaw) * Math.cos(pitch);
                double vy = -Math.sin(pitch + offset);
                double vz = Math.cos(yaw) * Math.cos(pitch);
                double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (len == 0) len = 1.0;
                potion.setVelocity(new Vector(
                        vx / len * THROW_SPEED,
                        vy / len * THROW_SPEED,
                        vz / len * THROW_SPEED));
            } catch (Throwable ignored) {
                // 速度设置失败就用 launchProjectile 的默认值
            }

            consumeOne(player, stack);
            try {
                player.playSound(player.getLocation(),
                        "entity.splash_potion.throw", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 音效失败忽略
            }
        } catch (Throwable ignored) {
            // 投掷失败忽略
        }
    }

    /** 扣掉手里那一颗（创造模式不消耗，与原版一致）。 */
    private static void consumeOne(Player player, ItemStack stack) {
        try {
            if (stack == null || stack.getType().isAir()) return;
            try {
                if (player.getGameMode() == GameMode.CREATIVE) return;
            } catch (Throwable ignored) {
                // 取不到模式就按生存处理
            }
            if (stack.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 已废弃接口，失败无妨
            }
        } catch (Throwable ignored) {
            // 扣除失败忽略
        }
    }

    // ------------------------------------------------------------------
    // 伤害
    // ------------------------------------------------------------------

    /** 无视护甲的真实伤害（直接改血量）。 */
    private static void damageTrue(LivingEntity entity, double amount) {
        try {
            entity.setHealth(Math.max(0.0, entity.getHealth() - amount));
        } catch (Throwable ignored) {
            // 实体已消失
        }
    }

    /** 按最大生命值百分比造成真实伤害。 */
    private static void damageMaxHealthPercent(LivingEntity entity, double percent) {
        try {
            double amount = entity.getMaxHealth() * percent / 100.0;
            entity.setHealth(Math.max(0.0, entity.getHealth() - amount));
        } catch (Throwable ignored) {
            // 实体已消失
        }
    }

    // ------------------------------------------------------------------
    // 视觉 / 音效
    // ------------------------------------------------------------------

    /** 爆鸣滴答声 + 向外扩散的淡蓝色波纹（半径 1、2 各一圈）。 */
    private static void playExplosionEffects(Location center) {
        try {
            World world = center.getWorld();
            if (world == null) return;

            // 滴答声：用音符盒模仿，只播给范围内的玩家
            Collection<Entity> near = world.getNearbyEntities(center, RANGE_XZ, RANGE_Y, RANGE_XZ);
            for (Entity entity : near) {
                if (!(entity instanceof Player p)) continue;
                try {
                    p.playSound(p.getLocation(), "block.note_block.hat", 1.0f, 0.8f);
                    p.playSound(p.getLocation(), "block.note_block.hat", 0.9f, 1.2f);
                } catch (Throwable ignored) {
                    // 单个玩家失败跳过
                }
            }

            // 波纹：每秒一圈，画到半径 2 就停
            final BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(
                    NativePluginHelper.javaPlugin(), new Runnable() {
                        int step = 0;

                        @Override
                        public void run() {
                            try {
                                if (step >= RIPPLE_MAX_STEP) {
                                    cancel(holder[0]);
                                    return;
                                }
                                step++;
                                double radius = step;
                                for (double angle = 0; angle < 360; angle += RIPPLE_ANGLE_STEP) {
                                    double rad = Math.toRadians(angle);
                                    Location loc = center.clone().add(
                                            radius * Math.cos(rad), 0.5, radius * Math.sin(rad));
                                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc,
                                            2, 0.05, 0.05, 0.05, 0);
                                    world.spawnParticle(Particle.SPLASH, loc,
                                            2, 0.05, 0.05, 0.05, 0);
                                }
                            } catch (Throwable ignored) {
                                // 单圈失败跳过
                            }
                        }
                    }, 0L, 20L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void cancel(BukkitTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {
            // 已结束
        }
    }

    /** 给玩家上的三重 buff（速度 / 反胃 / 跳跃提升 III，8 秒）。 */
    private static void applyPlayerBuff(Player player) {
        try {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, BUFF_TICKS, BUFF_AMPLIFIER, false, true, true));
            // 1.20.5 起 CONFUSION 更名为 NAUSEA
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NAUSEA, BUFF_TICKS, BUFF_AMPLIFIER, false, true, true));
            // 1.20.5 起 JUMP 更名为 JUMP_BOOST
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.JUMP_BOOST, BUFF_TICKS, BUFF_AMPLIFIER, false, true, true));
        } catch (Throwable ignored) {
            // 效果添加失败不阻断后续
        }
    }

    // ------------------------------------------------------------------
    // 糖分惩罚
    // ------------------------------------------------------------------

    /** 开始（或重启）某玩家的糖分惩罚。 */
    private static void startSugarPunishment(Player player) {
        try {
            UUID id = player.getUniqueId();
            BukkitTask old = PUNISH_TASKS.remove(id);
            cancel(old);

            player.sendMessage(PUNISH_MESSAGE.replace('&', '§'));

            final Location[] lastLoc = {player.getLocation().clone()};
            final BukkitTask[] holder = new BukkitTask[1];

            holder[0] = Bukkit.getScheduler().runTaskTimer(
                    NativePluginHelper.javaPlugin(), new Runnable() {
                        int halfSec = 0;

                        @Override
                        public void run() {
                            try {
                                if (!player.isOnline() || player.isDead()
                                        || halfSec >= PUNISH_STEPS) {
                                    cancel(holder[0]);
                                    PUNISH_TASKS.remove(id);
                                    return;
                                }

                                halfSec++;

                                // 每 4 秒提醒一次
                                if (halfSec % PUNISH_REMIND_EVERY == 0) {
                                    player.sendMessage(PUNISH_MESSAGE.replace('&', '§'));
                                }

                                // 每 0.5 秒检测位移，没动就扣血
                                Location now = player.getLocation();
                                if (now.distanceSquared(lastLoc[0]) < MOVE_EPSILON_SQ) {
                                    damageTrue(player, PUNISH_DAMAGE);
                                } else {
                                    lastLoc[0] = now.clone();
                                }

                                if (halfSec >= PUNISH_STEPS) {
                                    cancel(holder[0]);
                                    PUNISH_TASKS.remove(id);
                                }
                            } catch (Throwable ignored) {
                                // 惩罚任务不抛异常
                            }
                        }
                    }, PUNISH_PERIOD, PUNISH_PERIOD);

            PUNISH_TASKS.put(id, holder[0]);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 溅射结算
    // ------------------------------------------------------------------

    /** 药水炸开时的范围结算。 */
    private static void handleSplash(PotionSplashEvent event) {
        try {
            ThrownPotion potion = event.getPotion();
            if (potion == null) return;
            if (!isOurPotion(potion.getItem())) return;

            Location center = potion.getLocation().clone();
            World world = center.getWorld();
            if (world == null) return;

            // 爆鸣声 + 向外扩散的淡蓝色波纹
            playExplosionEffects(center);

            // 糖天画戟丢出的致死量全糖水不伤害投掷者本人
            org.bukkit.projectiles.ProjectileSource shooter = potion.getShooter();
            UUID shooterId = (shooter instanceof Player sp) ? sp.getUniqueId() : null;

            Collection<Entity> entities = world.getNearbyEntities(
                    center, RANGE_XZ, RANGE_Y, RANGE_XZ);
            for (Entity entity : entities) {
                try {
                    if (!(entity instanceof LivingEntity living)) continue;

                    if (living instanceof Player player) {
                        if (shooterId != null && shooterId.equals(player.getUniqueId())) continue;
                        damageMaxHealthPercent(player, INITIAL_DAMAGE_PERCENT);
                        if (!player.isOnline() || player.isDead()) continue;

                        applyPlayerBuff(player);
                        player.sendMessage(MESSAGES[RANDOM.nextInt(MESSAGES.length)]
                                .replace('&', '§'));
                        startSugarPunishment(player);
                    } else {
                        // 只打无命名生物
                        String customName = living.getCustomName();
                        if (customName != null && !customName.isEmpty()) continue;
                        damageMaxHealthPercent(living, INITIAL_DAMAGE_PERCENT);
                    }
                } catch (Throwable ignored) {
                    // 单个目标失败跳过
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new SplashListener());
    }

    /** 溅射事件入口（投掷入口走 {@link #invoke}，与原脚本的 onUse 契约一致）。 */
    private static final class SplashListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onSplash(PotionSplashEvent event) {
            handleSplash(event);
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (BukkitTask task : PUNISH_TASKS.values()) {
            cancel(task);
        }
        PUNISH_TASKS.clear();
    }
}
