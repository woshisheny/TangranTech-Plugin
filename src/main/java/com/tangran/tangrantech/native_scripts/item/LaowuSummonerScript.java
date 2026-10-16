package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Ocelot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「老吴召唤器」的原生实现（对应 {@code scripts/老吴召唤器.js}）。一整套「猫咪对战」小游戏。
 *
 * <p>右键手持 {@code TR_LW} 消耗一个，在身边生成一只「温顺的小猫咪」（已驯服 + 认主，
 * 否则未驯服的猫会像豹猫一样怕玩家、窜得飞快）；若 3.5 格内已有其它小猫咪（含刚生成的
 * 这只，共 ≥2 只），立刻开战：</p>
 *
 * <ol>
 *   <li>参战的小猫咪全部被替换成「进击的老吴」（{@link Ocelot}，继承原有血量，设为信任玩家、
 *       不因远离而消失），进入 {@link #startBattle} 的每刻循环：互相锁定最近的对手、
 *       靠近到 1.2 格内撕咬（每只猫的咬合 tick 错开 7t，避免同 tick 同归于尽），
 *       离战场中心超过 8 格会被拽回。</li>
 *   <li>只剩一只时结算：它变回 {@link Cat} 并改名为「获胜的小猫咪」（60 血），
 *       开始放爱心粒子，同时挂一个「猫王守望」任务。</li>
 *   <li>猫王守望每 20 刻扫描 5 格内其它小猫咪，凑够对手就进入 {@link #startCatBattle} 的
 *       猫王战：每 120 刻回 6 点血，咬合伤害提升到 4 点。胜者改名「猫王」并染成红猫。</li>
 *   <li>手持 {@code TR_MWBZQ} 右键猫王：被抓伤（掉 15 血）+ 反胃 V / 失明 / 缓慢 各 5 秒，
 *       并掉落一份 {@code TR_MW}，猫王消失。</li>
 * </ol>
 *
 * <p>与 1.21 的适配：{@code LivingEntity#setMaxHealth} 已移除、{@code Attribute.GENERIC_MAX_HEALTH}
 * 已更名为 {@link Attribute#MAX_HEALTH}，因此血量上限改走
 * {@code getAttribute(Attribute.MAX_HEALTH).setBaseValue(...)}。</p>
 */
public final class LaowuSummonerScript implements NativeLifecycleScript {

    private static final String ITEM_ID = "TR_LW";
    /** 抓挠猫王需要的物品。 */
    private static final String KING_ITEM_ID = "TR_MWBZQ";
    /** 抓挠猫王后掉落的物品。 */
    private static final String REWARD_ID = "TR_MW";

    private static final String TAME_NAME = "&b&l温顺的小猫咪";
    private static final String ANGRY_NAME = "&4&l进击的老吴";
    private static final String WINNER_NAME = "&6&l获胜的&b&l温顺的小猫咪";
    private static final String KING_NAME = "&0猫王";

    /** 名字比对用的纯文本（去色后）。 */
    /**
     * 猫的角色标记键。
     *
     * <p>原脚本靠 {@code customName} 判断身份，有两个硬伤：<b>玩家用铁砧改名就能伪造</b>；
     * 且「获胜的温顺的小猫咪」这个名字<b>包含</b>「温顺的小猫咪」子串 —— 纯名字比对只能靠
     * 「严格相等 vs 包含」这种细节才能区分开。改用 PDC 存角色后身份判断是 O(1) 且改不掉。</p>
     *
     * <p>读身份时<b>优先 PDC、回落名字</b>：服务器上已有的老猫没有标记，仍然按名字认得出来，
     * 玩法不会因为这次改动而中断。</p>
     */
    private static final NamespacedKey ROLE_KEY =
            NamespacedKey.fromString("tangrantech:lw_role");
    private static final String ROLE_TAME = "TAME";
    private static final String ROLE_ANGRY = "ANGRY";
    private static final String ROLE_WINNER = "WINNER";
    private static final String ROLE_KING = "KING";

    private static final String TAME_PLAIN = "温顺的小猫咪";
    private static final String WINNER_PLAIN = "获胜的温顺的小猫咪";
    private static final String KING_PLAIN = "猫王";

    /** 猫王守望半径（格）。 */
    private static final double KING_RANGE = 5.0;
    /** 爱心粒子周期（刻）。 */
    private static final long HEART_INTERVAL = 60L;
    /** 普通对战咬合伤害。 */
    private static final double BITE_DAMAGE = 2.0;
    /** 猫王战咬合伤害。 */
    private static final double KING_BITE_DAMAGE = 4.0;
    /** 咬合距离平方阈值（2.5 格）。 */
    private static final double BITE_RANGE_SQ = 6.25;
    /** 搜猫半径（格）。 */
    private static final double SEARCH_RADIUS = 3.5;

    /** 位移速度（格/刻）= 6 格/秒。 */
    private static final double BATTLE_MOVE_SPEED = 0.3;
    /** 离中心超过该距离就被拽回。 */
    private static final double BATTLE_LEASH = 8.0;
    /** 靠近目标时的停止距离（格）。 */
    private static final double APPROACH_STOP = 1.2;
    /** 咬合间隔（刻）。 */
    private static final int BITE_INTERVAL = 20;
    /** 每只猫错开的 tick 偏移，避免同 tick 一起死。 */

    /** 猫王战回血周期（刻）与回血量。 */
    private static final int KING_HEAL_INTERVAL = 120;
    private static final double KING_HEAL_AMOUNT = 6.0;

    /** 小猫咪初始血量。 */
    private static final double CAT_HEALTH = 30.0;
    /** 获胜小猫咪 / 猫王战的血量上限。 */
    private static final double WINNER_HEALTH = 60.0;
    /** 血量下限（Bukkit 不允许 0 以下，0 即死亡）。 */
    private static final double MIN_HEALTH = 0.5;

    /** 抓伤玩家造成的伤害与 debuff 时长。 */
    private static final double SCRATCH_DAMAGE = 15.0;
    private static final int DEBUFF_TICKS = 100;

    /** 进行中的对战（两类共用同一个列表，与原脚本一致）。 */
    private static final List<Battle> BATTLES = new ArrayList<>();
    /** 正在参与猫王战的实体，避免被守望任务重复拉入。 */
    private static final Set<UUID> CAT_BATTLE_SET = ConcurrentHashMap.newKeySet();
    /** 需要持续飘爱心的猫（全局一个任务统一处理，见 {@link #addHeartCat}）。 */
    private static final Set<UUID> HEART_CATS = ConcurrentHashMap.newKeySet();
    /** 爱心心跳任务（惰性启动，全局唯一）。 */
    private static BukkitTask heartTask;

    @Override
    public Object invoke(String fn, Object... args) {
        // 原脚本只靠监听器驱动，没有对外回调函数
        return null;
    }

    // ------------------------------------------------------------------
    // 物品
    // ------------------------------------------------------------------

    /** 判断物品是否为老吴召唤器。 */
    public static boolean isLwItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String id = TT.sfIdOf(item);
        if (id != null) return ITEM_ID.equalsIgnoreCase(id);
        ItemStack ref = refOf(ITEM_ID);
        return ref != null && ref.isSimilar(item);
    }

    /** 判断物品是否为指定粘液物品。 */
    private static boolean isSfItem(ItemStack item, String upperId) {
        if (item == null || item.getType().isAir()) return false;
        String id = TT.sfIdOf(item);
        if (id != null) return id.toUpperCase(java.util.Locale.ROOT).equals(upperId);
        ItemStack ref = refOf(upperId);
        return ref != null && ref.isSimilar(item);
    }

    private static ItemStack refOf(String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        return sf == null ? null : sf.getItem();
    }

    /** 优先从非手持槽位扣掉一个召唤器。 */
    private static boolean consumeOneLw(Player player) {
        try {
            org.bukkit.inventory.PlayerInventory inv = player.getInventory();
            int heldSlot = inv.getHeldItemSlot();
            for (int i = 0; i < 36; i++) {
                if (i == heldSlot) continue;
                ItemStack stack = inv.getItem(i);
                if (!isLwItem(stack)) continue;
                takeOne(inv, i, stack);
                return true;
            }
            ItemStack hand = inv.getItemInMainHand();
            if (isLwItem(hand)) {
                if (hand.getAmount() > 1) {
                    hand.setAmount(hand.getAmount() - 1);
                    inv.setItemInMainHand(hand);
                } else {
                    inv.setItemInMainHand(null);
                }
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static void takeOne(org.bukkit.inventory.PlayerInventory inv, int slot, ItemStack stack) {
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        } else {
            inv.setItem(slot, null);
        }
    }

    // ------------------------------------------------------------------
    // 血量
    // ------------------------------------------------------------------

    /**
     * 设置最大生命与当前生命。
     *
     * <p>1.21 移除了 {@code setMaxHealth}，必须走属性；
     * {@code Attribute.GENERIC_MAX_HEALTH} 也已更名为 {@link Attribute#MAX_HEALTH}。</p>
     */
    private static void setHealth(LivingEntity entity, double health, double maxHealth) {
        try {
            AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) attr.setBaseValue(maxHealth);
        } catch (Throwable ignored) {
            // 属性不可用时只改当前血量
        }
        try {
            entity.setHealth(Math.max(MIN_HEALTH, Math.min(health, maxHealth)));
        } catch (Throwable ignored) {
            // 实体已消失
        }
    }

    /** 只给当前血量，最大生命按 30 处理（对齐原脚本 {@code parseFloat(undefined) → NaN → 30}）。 */
    private static void setHealth(LivingEntity entity, double health) {
        setHealth(entity, health, CAT_HEALTH);
    }

    // ------------------------------------------------------------------
    // 名称判定
    // ------------------------------------------------------------------

    /** 写角色标记。 */
    private static void setRole(Entity entity, String role) {
        try {
            if (entity == null || ROLE_KEY == null) return;
            entity.getPersistentDataContainer().set(ROLE_KEY, PersistentDataType.STRING, role);
        } catch (Throwable t) {
            TT.log("[老吴召唤器] 写角色标记失败: " + t);
        }
    }

    /** 读角色标记；没有标记（老猫 / 其它插件生成的猫）返回 null。 */
    private static String roleOf(Entity entity) {
        try {
            if (entity == null || ROLE_KEY == null) return null;
            return entity.getPersistentDataContainer().get(ROLE_KEY, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 去色后的自定义名；无名字返回 null。 */
    private static String plainName(Entity entity) {
        try {
            String name = entity.getCustomName();
            if (name == null) return null;
            return ChatColor.stripColor(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 是否为「温顺的小猫咪」（不含「获胜的」「猫王」）。 */
    private static boolean isTameCat(Entity entity) {
        try {
            if (entity == null || entity.getType() != EntityType.CAT) return false;
            String role = roleOf(entity);
            if (role != null) return ROLE_TAME.equals(role);
            // 老猫没有标记 → 回落名字，与原脚本一致（严格相等，不会把「获胜的…」算进来）
            return TAME_PLAIN.equals(plainName(entity));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否为「获胜的温顺的小猫咪」。 */
    private static boolean isWinnerCat(Entity entity) {
        try {
            if (entity == null || entity.getType() != EntityType.CAT) return false;
            String role = roleOf(entity);
            if (role != null) return ROLE_WINNER.equals(role);
            String plain = plainName(entity);
            return plain != null && plain.contains(WINNER_PLAIN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否为「猫王」。 */
    private static boolean isCatKing(Entity entity) {
        try {
            if (entity == null || entity.getType() != EntityType.CAT) return false;
            String role = roleOf(entity);
            if (role != null) return ROLE_KING.equals(role);
            String plain = plainName(entity);
            return plain != null && plain.contains(KING_PLAIN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 找 loc 周围的「温顺的小猫咪」。
     *
     * <p>用 {@code getNearbyEntitiesByType} 直接只取猫 —— 原脚本取回全部实体再逐个过滤，
     * 周围实体多（刷怪场、密集动物群）时会白遍历一遍。</p>
     */
    private static List<Entity> findTameCatsNear(Location loc) {
        List<Entity> found = new ArrayList<>();
        try {
            World world = loc.getWorld();
            if (world == null) return found;
            for (Cat cat : world.getNearbyEntitiesByType(Cat.class, loc, SEARCH_RADIUS)) {
                if (isTameCat(cat)) found.add(cat);
            }
        } catch (Throwable t) {
            TT.log("[老吴召唤器] 检索周围猫咪失败: " + t);
        }
        return found;
    }

    // ------------------------------------------------------------------
    // 爱心粒子
    // ------------------------------------------------------------------

    /**
     * 让某只猫持续飘爱心。
     *
     * <p>原脚本<b>每只猫起一个定时任务</b>（猫一多就是几十个任务排在调度器里）。这里改成
     * 「全局一个心跳任务 + 一张猫 id 表」：任务数从 O(猫数) 降到 O(1)，而视觉效果完全一致
     * —— 都是每 {@link #HEART_INTERVAL} 刻在该猫头顶飘一次爱心。</p>
     */
    private static void addHeartCat(Entity cat) {
        if (cat == null) return;
        HEART_CATS.add(cat.getUniqueId());
        ensureHeartTask();
    }

    private static void ensureHeartTask() {
        if (heartTask != null) return;
        Plugin plugin = NativePluginHelper.javaPlugin();
        if (plugin == null) return;
        heartTask = Bukkit.getScheduler().runTaskTimer(
                plugin, LaowuSummonerScript::tickHearts, 20L, HEART_INTERVAL);
    }

    /** 心跳任务：给表里所有还活着的猫飘爱心，顺手清掉已消失的。 */
    private static void tickHearts() {
        if (HEART_CATS.isEmpty()) return;
        for (UUID id : new ArrayList<>(HEART_CATS)) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || e.isDead() || !e.isValid()) {
                HEART_CATS.remove(id);
                continue;
            }
            try {
                e.getWorld().spawnParticle(Particle.HEART,
                        e.getLocation().add(0, 0.7, 0), 5, 0.3, 0.3, 0.3, 0.05);
            } catch (Throwable t) {
                TT.log("[老吴召唤器] 爱心粒子失败: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 位移
    // ------------------------------------------------------------------

    /** 离中心太远就拽回来（避免「咬一口就跑」）。 */
    private static void leashToCenter(LivingEntity entity, Location center, double maxDist) {
        try {
            if (entity == null || center == null) return;
            Location loc = entity.getLocation();
            double dx = loc.getX() - center.getX();
            double dz = loc.getZ() - center.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= maxDist * maxDist) return;
            double d = Math.sqrt(d2);
            if (d <= 0) return;
            entity.teleport(center.clone().add(dx / d * maxDist, 0, dz / d * maxDist));
        } catch (Throwable ignored) {
            // 跨世界等异常
        }
    }

    /** 朝目标迈一步，走到 stopDist 内就停；同时转向目标。 */
    private static void stepToward(LivingEntity entity, LivingEntity target, double stopDist) {
        try {
            if (entity == null || target == null) return;
            Location a = entity.getLocation();
            Location b = target.getLocation();
            if (!a.getWorld().equals(b.getWorld())) return;
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (!(len > 0.001)) return;
            if (len <= stopDist) return;

            double step = Math.min(BATTLE_MOVE_SPEED, Math.max(0.05, len - stopDist));
            Location dest = a.clone().add(dx / len * step, 0, dz / len * step);
            dest.setYaw((float) (Math.atan2(-dx, dz) * 180.0 / Math.PI));
            dest.setPitch(0f);
            entity.teleport(dest);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 成员位置的平均点（只在开战时算一次，与原脚本一致）。 */
    private static Location battleCenter(List<LivingEntity> members) {
        try {
            if (members == null || members.isEmpty()) return null;
            double x = 0;
            double y = 0;
            double z = 0;
            for (LivingEntity m : members) {
                Location l = m.getLocation();
                x += l.getX();
                y += l.getY();
                z += l.getZ();
            }
            Location c = members.get(0).getLocation().clone();
            c.setX(x / members.size());
            c.setY(y / members.size());
            c.setZ(z / members.size());
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 实体变身
    // ------------------------------------------------------------------

    /**
     * 把猫变成「人驯服 + 有主人」的状态。
     *
     * <p>原脚本注释点明了原因：未驯服的猫会像豹猫一样怕玩家、窜得飞快，
     * 导致「温顺的小猫咪」根本追不上、也留不住。</p>
     */
    private static void makeTameCat(Cat cat, Location nearLoc) {
        try {
            if (cat == null) return;
            try {
                cat.setTamed(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                cat.setSitting(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                Location loc = (nearLoc != null) ? nearLoc : cat.getLocation();
                Collection<Player> ps = cat.getWorld().getNearbyPlayers(loc, SEARCH_RADIUS);
                Player first = null;
                for (Player p : ps) {
                    first = p;
                    break;
                }
                if (first != null) cat.setOwner(first);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 小猫咪 → 进击的老吴（保留血量）。 */
    private static Ocelot convertCatToOcelot(Cat cat) {
        try {
            Location loc = cat.getLocation();
            World world = cat.getWorld();
            double health = cat.getHealth();
            cat.remove();

            Ocelot ocelot = world.spawn(loc, Ocelot.class);
            ocelot.setCustomName(color(ANGRY_NAME));
            ocelot.setCustomNameVisible(true);
            setRole(ocelot, ROLE_ANGRY);
            setHealth(ocelot, health);
            try {
                ocelot.setTrusting(true);
            } catch (Throwable ignored) {
                // 不再怕玩家乱窜
            }
            try {
                ocelot.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                ocelot.setPersistent(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            return ocelot;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 普通对战（小猫咪 → 老吴）
    // ------------------------------------------------------------------

    /**
     * 开战：把参战的小猫咪全部变成老吴。
     *
     * <p>不足 2 只时不开战——只有 1 只就把血量补回 30，与原脚本一致。</p>
     */
    private static void startBattle(List<Entity> cats) {
        List<LivingEntity> members = new ArrayList<>();
        for (Entity e : cats) {
            if (!(e instanceof Cat cat)) continue;
            Ocelot o = convertCatToOcelot(cat);
            if (o != null) members.add(o);
        }

        if (members.size() < 2) {
            if (members.size() == 1) {
                setHealth(members.get(0), CAT_HEALTH);
            }
            return;
        }

        Battle battle = new Battle(members, battleCenter(members));
        BATTLES.add(battle);
        battle.task = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> tickBattle(battle), 0L, 1L);
    }

    /**
     * 这只猫本刻该不该咬。
     *
     * <p>原脚本的相位是「成员数组下标 × 7」，而 {@code alive} 列表每逢有猫死亡就会重建
     * —— 下标一变，同一只猫的咬合相位跟着跳变（表现为「某只猫突然和别人同步咬」）。
     * 改用 UUID 的 hash 定相位：与数组无关，一只猫的节奏从出生到死亡都稳定，
     * 同时仍然保证不同猫之间是错开的（不会同 tick 一起结算导致同归于尽）。</p>
     */
    private static boolean biteReady(long age, Entity self) {
        try {
            int phase = Math.floorMod(self.getUniqueId().hashCode(), BITE_INTERVAL);
            return Math.floorMod(age + phase, BITE_INTERVAL) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 普通对战的一次推进。 */
    private static void tickBattle(Battle battle) {
        try {
            battle.age++;

            List<LivingEntity> alive = aliveMembers(battle);
            if (alive.isEmpty()) {
                cancelBattle(battle);
                BATTLES.remove(battle);
                return;
            }
            if (alive.size() == 1) {
                finishBattle(battle, alive.get(0));
                return;
            }

            for (int i = 0; i < alive.size(); i++) {
                LivingEntity self = alive.get(i);
                LivingEntity target = nearestOther(alive, i);
                if (target == null) continue;
                lockTarget(self, target);
                leashToCenter(self, battle.center, BATTLE_LEASH);
                stepToward(self, target, APPROACH_STOP);

                // 咬合相位由 UUID 决定（见 biteReady）：各猫错开，且不随成员数组变化而跳变
                if (biteReady(battle.age, self)
                        && self.getLocation().distanceSquared(target.getLocation()) <= BITE_RANGE_SQ) {
                    try {
                        target.damage(BITE_DAMAGE, self);
                        self.swingMainHand();
                    } catch (Throwable ignored) {
                        // 目标已消失
                    }
                }
            }
        } catch (Throwable ignored) {
            // 对战任务不抛异常
        }
    }

    /** 结算普通对战：胜者变回小猫咪并改名「获胜的小猫咪」。 */
    private static void finishBattle(Battle battle, LivingEntity winner) {
        try {
            cancelBattle(battle);
            BATTLES.remove(battle);

            Location loc = winner.getLocation();
            World world = winner.getWorld();
            winner.remove();

            Cat cat = world.spawn(loc, Cat.class);
            cat.setCustomName(color(WINNER_NAME));
            cat.setCustomNameVisible(true);
            setRole(cat, ROLE_WINNER);
            setHealth(cat, WINNER_HEALTH, WINNER_HEALTH);
            makeTameCat(cat, loc);
            addHeartCat(cat);
            startKingWatchTask(cat);

            final UUID catUuid = cat.getUniqueId();
            // 6 次进食音效，每次间隔 10 刻
            for (int i = 0; i < 6; i++) {
                NativePluginHelper.runLater(() -> {
                    try {
                        Collection<Player> ps = world.getNearbyPlayers(loc, SEARCH_RADIUS);
                        for (Player p : ps) {
                            try {
                                p.playSound(loc, "entity.generic.eat", 1.0f, 1.0f);
                            } catch (Throwable ignored) {
                                // 单个玩家失败跳过
                            }
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }, i * 10L);
            }

            // 60 刻后把血量补满（玩家可能在此期间打到它）
            NativePluginHelper.runLater(() -> {
                try {
                    Entity e = Bukkit.getEntity(catUuid);
                    if (e instanceof LivingEntity le && !le.isDead()) {
                        setHealth(le, WINNER_HEALTH, WINNER_HEALTH);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 60L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 猫王守望：每 20 刻找一次 5 格内的其它「获胜的小猫咪」，凑够就开猫王战。 */
    private static void startKingWatchTask(Cat cat) {
        final UUID uuid = cat.getUniqueId();
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> {
                    try {
                        if (CAT_BATTLE_SET.contains(uuid)) return;

                        Entity e = Bukkit.getEntity(uuid);
                        if (e == null || e.isDead() || !isWinnerCat(e)) {
                            if (holder[0] != null) holder[0].cancel();
                            return;
                        }

                        List<Entity> found = new ArrayList<>();
                        Collection<Entity> near = e.getWorld().getNearbyEntities(
                                e.getLocation(), KING_RANGE, KING_RANGE, KING_RANGE);
                        for (Entity o : near) {
                            if (o == e) continue;
                            if (isWinnerCat(o) && !CAT_BATTLE_SET.contains(o.getUniqueId())) {
                                found.add(o);
                            }
                        }

                        if (!found.isEmpty()) {
                            List<Entity> all = new ArrayList<>();
                            all.add(e);
                            all.addAll(found);
                            if (holder[0] != null) holder[0].cancel();
                            startCatBattle(all);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }, 40L, 20L);
    }

    // ------------------------------------------------------------------
    // 猫王战
    // ------------------------------------------------------------------

    /** 开猫王战：所有成员不因远离而消失，并在每 120 刻回血。 */
    private static void startCatBattle(List<Entity> cats) {
        List<LivingEntity> members = new ArrayList<>();
        for (Entity e : cats) {
            if (!(e instanceof LivingEntity le)) continue;
            members.add(le);
            CAT_BATTLE_SET.add(le.getUniqueId());
            try {
                le.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                le.setPersistent(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        Battle battle = new Battle(members, battleCenter(members));
        BATTLES.add(battle);
        battle.task = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> tickCatBattle(battle), 0L, 1L);
    }

    /** 猫王战的一次推进。 */
    private static void tickCatBattle(Battle battle) {
        try {
            battle.age++;

            List<LivingEntity> alive = aliveMembers(battle);
            if (alive.isEmpty()) {
                cancelBattle(battle);
                BATTLES.remove(battle);
                for (LivingEntity m : battle.members) {
                    CAT_BATTLE_SET.remove(m.getUniqueId());
                }
                return;
            }
            if (alive.size() == 1) {
                finishCatBattle(battle, alive.get(0));
                return;
            }

            // 每 120 刻全体回 6 血并播进食音效
            if (battle.age % KING_HEAL_INTERVAL == 0) {
                for (LivingEntity o : alive) {
                    try {
                        setHealth(o, o.getHealth() + KING_HEAL_AMOUNT, WINNER_HEALTH);
                        Collection<Player> ps = o.getWorld()
                                .getNearbyPlayers(o.getLocation(), SEARCH_RADIUS);
                        for (Player p : ps) {
                            try {
                                p.playSound(o.getLocation(), "entity.generic.eat", 1.0f, 1.0f);
                            } catch (Throwable ignored) {
                                // 单个玩家失败跳过
                            }
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }

            for (int i = 0; i < alive.size(); i++) {
                LivingEntity self = alive.get(i);
                LivingEntity target = nearestOther(alive, i);
                if (target == null) continue;
                lockTarget(self, target);
                leashToCenter(self, battle.center, BATTLE_LEASH);
                stepToward(self, target, APPROACH_STOP);

                // 咬合相位由 UUID 决定（见 biteReady），避免同归于尽
                if (biteReady(battle.age, self)
                        && self.getLocation().distanceSquared(target.getLocation()) <= BITE_RANGE_SQ) {
                    try {
                        target.damage(KING_BITE_DAMAGE, self);
                        self.swingMainHand();
                    } catch (Throwable ignored) {
                        // 目标已消失
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 结算猫王战：胜者改名「猫王」并染红。 */
    private static void finishCatBattle(Battle battle, LivingEntity winner) {
        try {
            cancelBattle(battle);
            BATTLES.remove(battle);
            for (LivingEntity m : battle.members) {
                CAT_BATTLE_SET.remove(m.getUniqueId());
            }

            try {
                winner.setCustomName(color(KING_NAME));
                winner.setCustomNameVisible(true);
                setRole(winner, ROLE_KING);
            } catch (Throwable ignored) {
                // 忽略
            }
            // 猫王也要驯服，否则玩家一靠近就窜走（右键抓挠需要靠近）
            if (winner instanceof Cat cat) {
                makeTameCat(cat, cat.getLocation());
                try {
                    cat.setCatType(Cat.Type.RED);
                } catch (Throwable ignored) {
                    // 猫型设置失败不影响流程
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 对战公共
    // ------------------------------------------------------------------

    /** 取仍然存活（且未被移除）的成员。 */
    private static List<LivingEntity> aliveMembers(Battle battle) {
        List<LivingEntity> alive = new ArrayList<>();
        for (LivingEntity m : battle.members) {
            try {
                Entity e = Bukkit.getEntity(m.getUniqueId());
                if (e instanceof LivingEntity le && !le.isDead()) alive.add(le);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return alive;
    }

    /**
     * 让实体锁定目标。
     *
     * <p>{@code setTarget} 定义在 {@link org.bukkit.entity.Mob} 上而不是 {@link LivingEntity}，
     * 而战斗成员统一按 {@code LivingEntity} 存放（原脚本靠鸭子类型绕过了这个区别），
     * 所以这里做一次类型收窄。</p>
     */
    private static void lockTarget(LivingEntity entity, LivingEntity target) {
        if (entity instanceof org.bukkit.entity.Mob mob) {
            try {
                mob.setTarget(target);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 取列表中除第 index 个之外最近的一个。 */    private static LivingEntity nearestOther(List<LivingEntity> alive, int index) {
        LivingEntity self = alive.get(index);
        LivingEntity target = null;
        double best = -1;
        for (int j = 0; j < alive.size(); j++) {
            if (j == index) continue;
            LivingEntity other = alive.get(j);
            try {
                double d = self.getLocation().distanceSquared(other.getLocation());
                if (best < 0 || d < best) {
                    best = d;
                    target = other;
                }
            } catch (Throwable ignored) {
                // 跨世界等异常跳过
            }
        }
        return target;
    }

    private static void cancelBattle(Battle battle) {
        if (battle.task != null) {
            try {
                battle.task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
    }

    // ------------------------------------------------------------------
    // 抓挠猫王
    // ------------------------------------------------------------------

    /** 抓伤玩家：掉血 + 反胃 V / 失明 / 缓慢 各 5 秒。 */
    private static void applyDebuffs(Player player) {
        try {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NAUSEA, DEBUFF_TICKS, 4));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, DEBUFF_TICKS, 0));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, DEBUFF_TICKS, 0));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 手持 {@code TR_MWBZQ} 右键猫王。 */
    private static void handleKingInteract(Player player, Entity king) {
        try {
            double hp = player.getHealth();
            player.setHealth(Math.max(MIN_HEALTH, hp - SCRATCH_DAMAGE));
        } catch (Throwable ignored) {
            try {
                player.damage(SCRATCH_DAMAGE);
            } catch (Throwable ignored2) {
                // 忽略
            }
        }

        applyDebuffs(player);

        try {
            player.sendMessage(color("&4&l被抓伤了"));
        } catch (Throwable ignored) {
            // 忽略
        }

        try {
            SlimefunItem mw = SlimefunItem.getById(REWARD_ID);
            if (mw != null) {
                ItemStack stack = mw.getItem().clone();
                stack.setAmount(1);
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                if (leftovers != null && !leftovers.isEmpty()) {
                    for (ItemStack rest : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        try {
            if (king != null && !king.isDead()) king.remove();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 召唤
    // ------------------------------------------------------------------

    /** 右键召唤器：扣一个 → 生成小猫咪 → 看周围是否够对手开战。 */
    private static void handleInteract(Player player) {
        if (!consumeOneLw(player)) return;

        Location spawnLoc = player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        Cat cat = null;
        try {
            cat = player.getWorld().spawn(spawnLoc, Cat.class);
        } catch (Throwable ignored) {
            try {
                cat = player.getWorld().spawn(player.getLocation().add(0, 0.5, 0), Cat.class);
            } catch (Throwable ignored2) {
                cat = null;
            }
        }
        if (cat == null) return;

        cat.setCustomName(color(TAME_NAME));
        cat.setCustomNameVisible(true);
        setRole(cat, ROLE_TAME);
        setHealth(cat, CAT_HEALTH);
        // 驯服 + 认主：不会怕玩家跑掉
        makeTameCat(cat, spawnLoc);

        List<Entity> nearby = findTameCatsNear(cat.getLocation());
        if (nearby.size() >= 2) {
            startBattle(nearby);
        } else {
            addHeartCat(cat);
        }
    }

    /** 把 {@code &} 颜色码转成 {@code §}。 */
    private static String color(String str) {
        try {
            return ChatColor.translateAlternateColorCodes('&', str);
        } catch (Throwable ignored) {
            return str;
        }
    }

    /** 一次对战（普通 / 猫王共用）。 */
    private static final class Battle {
        final List<LivingEntity> members;
        final Location center;
        int age;
        BukkitTask task;

        Battle(List<LivingEntity> members, Location center) {
            this.members = members;
            this.center = center;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.unmodifiableList(List.of(new InteractListener(), new EntityListener()));
    }

    /** 右键物品派发。 */
    private static final class InteractListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
                if (!isLwItem(event.getItem())) return;
                // 对着方块右键时取消，避免同时触发方块本身
                if (action == Action.RIGHT_CLICK_BLOCK) {
                    try {
                        event.setCancelled(true);
                    } catch (Throwable ignored) {
                        // 事件不可取消
                    }
                }
                handleInteract(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 右键实体派发（抓挠猫王）。 */
    private static final class EntityListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            try {
                Entity clicked = event.getRightClicked();
                if (!isCatKing(clicked)) return;

                Player player = event.getPlayer();
                // 只认触发那一只手拿的物品
                ItemStack handItem = player.getInventory().getItem(event.getHand());
                if (!isSfItem(handItem, KING_ITEM_ID)) return;

                handleKingInteract(player, clicked);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (Battle battle : new ArrayList<>(BATTLES)) {
            cancelBattle(battle);
        }
        BATTLES.clear();
        CAT_BATTLE_SET.clear();
    }
}
