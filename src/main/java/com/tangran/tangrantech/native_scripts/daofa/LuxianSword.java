package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;

/**
 * 「戮仙剑」—— {@code scripts/道法/装备.js} 的第一部分（约 630 行）。
 *
 * <p>玩法：手持戮仙剑右键「御剑」（玩家进创造飞行、脚下悬一把剑影模型），WASD 平移、
 * 前后移动时高度跟随准星、空格上升、Shift 下降；飞行中撞到生物每 0.1 秒结算一次
 * 「3% 最大生命 + 每级锋利 0.05%」的伤害。拿着剑攻击任意目标会引落无焰雷霆（视觉）。</p>
 *
 * <h2>几个必须保留的细节（原脚本踩过的坑）</h2>
 * <ol>
 *   <li><b>水平推进只用视线的水平投影</b>。若直接用整条 3D 方向 normalize 后乘 0.75，
 *       水平分量 = 0.75·cos(pitch)，低头越狠水平越慢（「低头掉速」）。所以「高度跟随准星」
 *       那一份不从水平向量里出，而是按 {@code look.getY()} 单独补。</li>
 *   <li><b>直视正下方/正上方时水平投影退化成 0</b>，此时要用 yaw 补方向，
 *       否则玩家会固定往南推。</li>
 *   <li>御剑是创造飞行，收剑时必须<b>恢复玩家原本的允许飞行/正在飞行状态</b>，
 *       否则会把普通玩家的飞行能力一直开着。</li>
 *   <li>Katarina 阶段（BOSS战给玩家打的 metadata {@code TR_BOSS_NO_SWORD_RIDE}）强制下剑。</li>
 *   <li>剑的物品 id 在注册表里是大写（{@code Tr_lxj} → {@code TR_LXJ}），
 *       直接按原文查会拿到 null、退化成普通木剑 —— 所以按大小写变体轮试。</li>
 * </ol>
 *
 * <h2>与 BOSS战 的接口</h2>
 * <p>原脚本通过 {@code globalThis} 暴露 {@code makeLuxianSword / isLuxianSword /
 * hasLuxianSword / giveLuxianSword / startLuxianSwordRide / stopLuxianSwordRide}。
 * Java 里由本类提供同名 {@code public static} 方法，BOSS战 直接调用。</p>
 */
public final class LuxianSword {

    private LuxianSword() {}

    /** 戮仙剑的粘液 id（注册表里会被大写化，查询时轮试大小写）。 */
    private static final String SWORD_ID = "Tr_lxj";

    /** 显示名。 */
    private static final String SWORD_NAME = "§c§l戮仙剑";
    /** 名字里用于识别的关键词。 */
    private static final String NAME_KEY = "戮仙剑";

    /** lore 的标识行（判断是否已补过 lore）。 */
    private static final String LORE_KEY = "【戮仙剑】";

    private static final String[] LORE = {
            "§7【戮仙剑】",
            "§7攻击目标会引落无焰雷霆。",
            "§7右键御剑 / 收剑下剑，WASD 移动。",
            "§7前后移动时高度跟随准星。",
            "§7Shift 逐渐降落。",
            "§7御剑撞击生物：每 0.1 秒造成 3% 最大生命伤害，每 1 级锋利额外 +0.05%。"};

    /** 撞击伤害的基准比例。 */
    private static final double RAM_BASE_RATIO = 0.03;
    /** 每级锋利附加的比例。 */
    private static final double RAM_SHARPNESS_RATIO = 0.0005;
    /** 同一目标的撞击间隔（刻）——0.1 秒。 */
    private static final int RAM_INTERVAL_TICKS = 2;
    /** 水平推进速度。 */
    private static final double MOVE_SPEED = 0.75;

    /** 玩家 UUID → 御剑状态。 */
    private static final Map<String, Ride> RIDES = new ConcurrentHashMap<>();

    /** 一次御剑会话。 */
    private static final class Ride {
        final Player player;
        BukkitTask task;
        ItemDisplay display;
        final boolean originalAllowFlight;
        final boolean originalFlying;
        int tick;
        final Map<String, Integer> ramCooldown = new ConcurrentHashMap<>();

        Ride(Player player) {
            this.player = player;
            this.originalAllowFlight = player.getAllowFlight();
            this.originalFlying = player.isFlying();
        }
    }

    // ==================================================================
    // 附魔与物品
    // ==================================================================

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    /** 只升不降地补附魔。 */
    private static boolean enchAtLeast(ItemMeta meta, Enchantment ench, int minLevel) {
        try {
            int cur = 0;
            try {
                cur = meta.getEnchantLevel(ench);
            } catch (Throwable ignored) {
                cur = 0;
            }
            if (cur >= minLevel) return false;
            meta.addEnchant(ench, minLevel, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 清掉 RSC 用来做「发光外观」的痕迹。
     *
     * <p>RSC 给物品加隐藏附魔再打 {@code HIDE_ENCHANTS}，结果我们自己补的真附魔也一起被藏起来。
     * 所以补附魔前先把这层伪装摘掉。</p>
     */
    private static boolean stripRscGlow(ItemMeta meta) {
        boolean changed = false;
        try {
            if (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA)) {
                meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return changed;
    }

    private static String plainName(ItemMeta meta) {
        try {
            if (meta != null && meta.hasDisplayName()) {
                return LegacyComponentSerializer.legacySection().serialize(meta.displayName());
            }
        } catch (Throwable ignored) {
            // 无视
        }
        return null;
    }

    /** 补上戮仙剑的附魔、名称与 lore（都只补不覆盖）。 */
    public static ItemStack applyEnchant(ItemStack item) {
        try {
            if (item == null) return null;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            boolean changed = false;

            if (!meta.isUnbreakable()) {
                meta.setUnbreakable(true);
                changed = true;
            }
            changed |= enchAtLeast(meta, Enchantment.SHARPNESS, 5);
            changed |= enchAtLeast(meta, Enchantment.UNBREAKING, 5);
            changed |= enchAtLeast(meta, Enchantment.SMITE, 5);
            changed |= enchAtLeast(meta, Enchantment.BANE_OF_ARTHROPODS, 5);
            changed |= stripRscGlow(meta);

            String dn = plainName(meta);
            if (dn == null || dn.isEmpty()) {
                meta.displayName(legacy(SWORD_NAME));
                changed = true;
            }
            if (!hasLore(meta, LORE_KEY)) {
                List<Component> lore = new ArrayList<>(LORE.length);
                for (String line : LORE) lore.add(legacy(line));
                meta.lore(lore);
                changed = true;
            }
            if (changed) item.setItemMeta(meta);
        } catch (Throwable t) {
            TT.log("[装备/戮仙剑] 补附魔失败: " + t);
        }
        return item;
    }

    private static boolean hasLore(ItemMeta meta, String key) {
        try {
            List<Component> lore = meta.lore();
            if (lore == null) return false;
            for (Component c : lore) {
                String s = LegacyComponentSerializer.legacySection().serialize(c);
                if (s.contains(key)) return true;
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return false;
    }

    /**
     * 按 id 取粘液物品拷贝。
     *
     * <p>注册表里的 id 会被转成大写，因此要按「原样 / 全大写 / 全小写」轮试；
     * 都取不到再扫一遍注册表做大小写不敏感匹配。</p>
     */
    static ItemStack slimefunItem(String id) {
        if (id == null || id.isBlank()) return null;
        for (String candidate : new String[]{
                id, id.toUpperCase(Locale.ROOT), id.toLowerCase(Locale.ROOT)}) {
            try {
                SlimefunItem sf = SlimefunItem.getById(candidate);
                if (sf != null && sf.getItem() != null) return sf.getItem().clone();
            } catch (Throwable ignored) {
                // 换下一种写法
            }
        }
        try {
            // 注册表里 id → SlimefunItem 的映射（原脚本这里遍历的是 Map，拿到的其实是
            // "key=value" 字符串、永远匹配不上；改成遍历 keySet 才是它的本意）
            var ids = io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                    .getRegistry().getSlimefunItemIds();
            for (String candidate : ids.keySet()) {
                if (!String.valueOf(candidate).equalsIgnoreCase(id)) continue;
                SlimefunItem sf = ids.get(candidate);
                if (sf == null) sf = SlimefunItem.getById(candidate);
                if (sf != null && sf.getItem() != null) {
                    TT.log("[装备] 粘液物品 " + id + " 从注册表扫到实际 id：" + candidate);
                    return sf.getItem().clone();
                }
            }
        } catch (Throwable ignored) {
            // 注册表不可用
        }
        return null;
    }

    /** 造一把戮仙剑（优先用注册的物品，取不到退化成木剑）。 */
    public static ItemStack make() {
        ItemStack sword = slimefunItem(SWORD_ID);
        if (sword == null) sword = new ItemStack(Material.WOODEN_SWORD);
        return applyEnchant(sword);
    }

    /** 是不是戮仙剑（只认木剑材质 + 名字含关键词）。 */
    public static boolean isSword(ItemStack item) {
        try {
            if (item == null || item.getType() != Material.WOODEN_SWORD) return false;
            String name = plainName(item.getItemMeta());
            return name != null && name.contains(NAME_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasSword(Player player) {
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (isSword(inv.getItem(i))) return true;
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return false;
    }

    public static boolean giveSword(Player player) {
        try {
            ItemStack sword = make();
            var leftover = player.getInventory().addItem(sword);
            for (ItemStack rest : leftover.values()) {
                if (rest != null) player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            player.updateInventory();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 剑影模型
    // ==================================================================

    public static ItemDisplay spawnDisplay(World world, Location loc) {
        try {
            ItemDisplay display = world.spawn(loc, ItemDisplay.class);
            display.setItemStack(make());
            try {
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            } catch (Throwable ignored) {
                // 老版本没有这个枚举值
            }
            try {
                display.setBillboard(Display.Billboard.FIXED);
            } catch (Throwable ignored) {
                // 忽略
            }
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            try {
                Quaternionf rotate = new Quaternionf();
                // 放平剑身，让它指向实体前方
                rotate.rotateX(1.5707964f);
                display.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f), rotate,
                        new Vector3f(2f, 2f, 2f), new Quaternionf()));
                display.setInterpolationDuration(0);
            } catch (Throwable ignored) {
                // 变换失败只影响外观
            }
            return display;
        } catch (Throwable t) {
            TT.log("[装备/戮仙剑] 生成剑影失败: " + t);
            return null;
        }
    }

    // ==================================================================
    // 御剑
    // ==================================================================

    /** 手上这把剑的锋利等级（取背包里所有戮仙剑的最高值）。 */
    private static int sharpnessLevel(Player player) {
        int best = 0;
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (!isSword(it)) continue;
                ItemMeta meta = it.getItemMeta();
                if (meta == null) continue;
                int lv = 0;
                try {
                    lv = meta.getEnchantLevel(Enchantment.SHARPNESS);
                } catch (Throwable ignored) {
                    lv = 0;
                }
                if (lv > best) best = lv;
            }
        } catch (Throwable ignored) {
            // 视为 0
        }
        return best;
    }

    /** 撞击结算：1.8 格内的生物每 0.1 秒挨一次。 */
    private static void applyRamDamage(Player player, Ride ride) {
        try {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world == null) return;

            for (var e : world.getNearbyEntities(loc, 1.8, 1.8, 1.8)) {
                if (!(e instanceof LivingEntity living)) continue;
                if (living.equals(player)) continue;
                try {
                    if (!living.isValid() || living.isDead()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    if (living.isInvulnerable()) continue;
                } catch (Throwable ignored) {
                    // 继续
                }

                String uuid = living.getUniqueId().toString();
                Integer last = ride.ramCooldown.get(uuid);
                if (last != null && (ride.tick - last) < RAM_INTERVAL_TICKS) continue;
                ride.ramCooldown.put(uuid, ride.tick);

                // 删掉无敌帧，保证每 0.1 秒都能结算
                try {
                    living.setMaximumNoDamageTicks(0);
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    living.setNoDamageTicks(0);
                } catch (Throwable ignored) {
                    // 忽略
                }

                double maxHp = 20;
                try {
                    maxHp = living.getHealth() > 0 ? living.getMaxHealth() : 20;
                } catch (Throwable ignored) {
                    maxHp = 20;
                }
                double amount = maxHp * (RAM_BASE_RATIO + RAM_SHARPNESS_RATIO * sharpnessLevel(player));
                if (!(amount > 0)) amount = 1;
                try {
                    living.damage(amount, player);
                } catch (Throwable ignored) {
                    try {
                        living.damage(amount);
                    } catch (Throwable ignored2) {
                        // 完全打不动就放弃
                    }
                }
            }
        } catch (Throwable ignored) {
            // 撞击结算失败不致命
        }
    }

    /** 上剑。 */
    public static void startRide(Player player) {
        try {
            String key = player.getUniqueId().toString();
            if (RIDES.containsKey(key)) return;
            if (!hasSword(player)) return;

            Ride ride = new Ride(player);
            RIDES.put(key, ride);

            try {
                player.setAllowFlight(true);
                player.setFlying(true);
            } catch (Throwable ignored) {
                // 忽略
            }

            ride.display = spawnDisplay(player.getWorld(), player.getLocation().clone().add(0, -0.3, 0));

            Plugin plugin = Bukkit.getPluginManager().getPlugin("TangranTech");
            if (plugin == null) {
                // 没有插件实例就没法排任务，直接收尾免得留下半截状态
                stopRide(player);
                return;
            }
            ride.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickRide(ride), 0L, 1L);
        } catch (Throwable t) {
            RIDES.remove(player.getUniqueId().toString());
            TT.log("[装备/戮仙剑] 御剑失败: " + t);
        }
    }

    /** 每刻推进。 */
    private static void tickRide(Ride ride) {
        Player player = ride.player;
        try {
            String key = player.getUniqueId().toString();
            if (!RIDES.containsKey(key) || !player.isOnline() || player.isDead()) {
                stopRide(player);
                return;
            }

            var input = player.getCurrentInput();
            Vector look = player.getLocation().getDirection();
            Vector dir = new Vector(look.getX(), 0, look.getZ());

            // 水平投影退化（直视正上/正下）时用 yaw 补方向，否则会固定往南推
            if (dir.lengthSquared() < 0.001) {
                double yaw = Math.toRadians(player.getLocation().getYaw());
                dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
            } else {
                dir = dir.normalize();
            }
            Vector right = new Vector(-dir.getZ(), 0, dir.getX());
            if (right.lengthSquared() > 0.001) right = right.normalize();

            // forwardAxis：+1 前进 / -1 后退 / 0 只有平移 —— 决定「高度跟随准星」要不要加
            int forwardAxis = 0;
            Vector move = new Vector(0, 0, 0);
            if (input != null) {
                if (input.isForward()) {
                    move.add(dir);
                    forwardAxis += 1;
                }
                if (input.isBackward()) {
                    move.subtract(dir);
                    forwardAxis -= 1;
                }
                if (input.isRight()) move.add(right);
                if (input.isLeft()) move.subtract(right);
            }
            if (move.lengthSquared() > 0.001) move.normalize().multiply(MOVE_SPEED);
            else move = new Vector(0, 0, 0);

            // 垂直分量单独累加，不参与水平归一化，水平才不会被俯仰角摊薄
            double vertical = 0;
            if (forwardAxis != 0) vertical += look.getY() * MOVE_SPEED * forwardAxis;
            if (input != null && input.isJump()) vertical += MOVE_SPEED;
            if (vertical != 0) move.setY(vertical);
            // Shift 下降：与空格上升同口径（覆盖式），按住就稳定以 0.75 下降
            if (input != null && input.isSneak()) move.setY(-MOVE_SPEED);

            try {
                player.setAllowFlight(true);
                player.setFlying(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            player.setVelocity(move);
            player.setFallDistance(0);

            if (ride.display != null && ride.display.isValid()) {
                Location displayLoc = player.getLocation().clone().add(0, -0.3, 0);
                displayLoc.setYaw(player.getLocation().getYaw() - 45);
                displayLoc.setPitch(0);
                ride.display.teleport(displayLoc);
            }

            ride.tick++;
            applyRamDamage(player, ride);
        } catch (Throwable ignored) {
            // 单刻异常不能让任务停摆
            try {
                if (ride.task != null) ride.task.cancel();
            } catch (Throwable ignored2) {
                // 忽略
            }
        }
    }

    /** 下剑：取消任务、删剑影、恢复飞行状态。 */
    public static void stopRide(Player player) {
        try {
            String key = player.getUniqueId().toString();
            Ride ride = RIDES.remove(key);
            if (ride == null) return;
            try {
                if (ride.task != null) ride.task.cancel();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (ride.display != null && ride.display.isValid()) ride.display.remove();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.setFlying(ride.originalFlying);
                player.setAllowFlight(ride.originalAllowFlight);
                player.setFallDistance(0);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 是否正在御剑。 */
    public static boolean isRiding(Player player) {
        return player != null && RIDES.containsKey(player.getUniqueId().toString());
    }

    // ==================================================================
    // 事件
    // ==================================================================

    /** 右键上/下剑；攻击目标引雷；上线/切换手持/拾取时补附魔。 */
    static final class ListenerImpl implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteract(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
                ItemStack item = event.getItem();
                if (!isSword(item)) return;

                Player player = event.getPlayer();
                // 不管通过什么途径获得，只要用这把剑就补上附魔
                try {
                    ItemStack main = player.getInventory().getItemInMainHand();
                    if (isSword(main)) applyEnchant(main);
                    ItemStack off = player.getInventory().getItemInOffHand();
                    if (isSword(off)) applyEnchant(off);
                } catch (Throwable ignored) {
                    // 忽略
                }

                event.setCancelled(true);

                // Katarina 阶段：强制下剑且禁止再上剑（BOSS战 会给玩家打上这个 metadata）
                try {
                    if (player.hasMetadata(NO_SWORD_RIDE_KEY)) {
                        stopRide(player);
                        player.sendMessage("§cKatarina 阶段无法御剑！");
                        return;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }

                if (isRiding(player)) stopRide(player);
                else startRide(player);
            } catch (Throwable t) {
                TT.log("[装备/戮仙剑] 右键处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageByEntityEvent event) {
            try {
                if (!(event.getDamager() instanceof Player player)) return;
                ItemStack item = player.getInventory().getItemInMainHand();
                if (!isSword(item)) return;
                applyEnchant(item);

                var victim = event.getEntity();
                if (victim.equals(player)) return;

                // 原 RSC 对带 script 的物品会取消攻击事件（与「带 script 就取消右键」同一套机制），
                // 导致拿着真·戮仙剑砍 Katarina 一点伤害都没有。这里只对本局「可被近战打伤」的
                // 目标把取消撤销 —— 由 BOSS战 通过 BossBattleBridge 判定放行名单。
                try {
                    if (BossBattleBridge.swordCanUncancelDamage(player, victim) && event.isCancelled()) {
                        event.setCancelled(false);
                    }
                } catch (Throwable ignored) {
                    // 判定失败就维持原样
                }

                victim.getWorld().strikeLightningEffect(victim.getLocation());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            try {
                enchantAll(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onItemHeld(PlayerItemHeldEvent event) {
            try {
                Player p = event.getPlayer();
                ItemStack it = p.getInventory().getItem(event.getNewSlot());
                if (isSword(it)) applyEnchant(it);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPickup(EntityPickupItemEvent event) {
            try {
                if (!(event.getEntity() instanceof Player)) return;
                ItemStack stack = event.getItem().getItemStack();
                if (isSword(stack)) applyEnchant(stack);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 把背包里的戮仙剑全部补一遍附魔（登录时用）。 */
    static void enchantAll(Player player) {
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && isSword(it)) applyEnchant(it);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (Ride ride : new ArrayList<>(RIDES.values())) {
            try {
                stopRide(ride.player);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        RIDES.clear();
    }

    // ==================================================================
    // 「禁止御剑」标记（谁打上的谁负责摘）
    // ==================================================================

    /**
     * 「本阶段禁止御剑」的 metadata 键（Katarina 阶段由 BOSS战 打上）。
     *
     * <p>写成公开常量是为了让 BOSS战 侧不用再抄一遍字面量 ——
     * 两边字符串写错一个字符就是「打了标记但拦不住」这种最难查的 bug。</p>
     */
    public static final String NO_SWORD_RIDE_KEY = "TR_BOSS_NO_SWORD_RIDE";

    /** 打上 / 摘掉「禁止御剑」标记；打上时顺手把正在骑的剑收掉。 */
    public static boolean setNoSwordRide(Player player, boolean banned) {
        if (player == null) return false;
        try {
            if (banned) {
                player.setMetadata(NO_SWORD_RIDE_KEY,
                        new org.bukkit.metadata.FixedMetadataValue(TT.plugin, "1"));
                stopRide(player);              // 已经在飞的要立刻收掉，不能等下一条 tick
                return true;
            }
            return clearNoSwordRide(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 摘掉「禁止御剑」标记。
     *
     * <p>收场与「重载后残留恢复」都要调它 —— metadata <b>活得过脚本重载</b>，
     * 而重载后没人记得摘，玩家就会被永久禁止御剑。</p>
     *
     * @return 原本有标记（本次摘掉了）返回 true
     */
    public static boolean clearNoSwordRide(Player player) {
        if (player == null) return false;
        try {
            if (!player.hasMetadata(NO_SWORD_RIDE_KEY)) return false;
            player.removeMetadata(NO_SWORD_RIDE_KEY,
                    com.tangran.tangrantech.TT.plugin);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
