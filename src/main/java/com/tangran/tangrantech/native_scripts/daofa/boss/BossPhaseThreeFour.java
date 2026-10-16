package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.AddonPaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Mob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentBridge;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;
import com.tangran.tangrantech.native_scripts.daofa.TwinTools;

/**
 * 三·四阶段（原脚本 10473-11022 行）：<b>双子全灭 → 糖然下场决斗（僵尸形态，血条剩余 ×100）
 * → 通关 → 糖然跪地 → 四阶段对话 → 糖然散去 / 场地消散 → 中章挑战彻底结束</b>。
 *
 * <h2>形态切换的三条安全纪律（原脚本用注释标过，都是真实事故）</h2>
 * <ol>
 *   <li><b>换形态前必须先置 {@code standSwapped} 并摘 {@code state.stand}</b> ——
 *       看门任务盯着本体，{@code EntityRemoveEvent} 也会拿本体反查战局；
 *       直接 {@code remove()} 会被当成「挑战结束」走收场，把整块场地连石英地板一起清空
 *       （原脚本注释：「糖然落地清场的元凶」）。</li>
 *   <li><b>装备原封过渡</b>：盔甲架 → 僵尸 → 跪像，六个槽位逐个克隆再装回去
 *       （糖然头颅 → 僵尸/跪像也长着糖然的头）。</li>
 *   <li><b>跪像装备槽全部禁用</b>：头颅/下界合金不能被玩家拿走（事件层还会再拦一道）。</li>
 * </ol>
 *
 * <h2>血量口径</h2>
 * <p>决斗体实体血量 = 血条剩余 ×100（双子打完剩 20 → 2000 血）；实体血量按比例折回血条读数。</p>
 */
public final class BossPhaseThreeFour {

    private BossPhaseThreeFour() {}

    /** 决斗主循环节拍（刻，20 = 每秒）。 */
    private static final int DUEL_PERIOD = 20;

    /** 技能节拍（每 6 秒一次）。 */
    private static final int DUEL_SKILL_PERIOD = 6;

    /** 在场火球上限（只留最近 8 颗）。 */
    private static final int MAX_FIREBALLS = 8;

    /** 清场任务每拍清几列。 */
    private static final int CLEAR_COLUMNS_PER_TICK = 2;

    /** 记录三阶段进度用：任务存档里「任务其八」的 id。 */
    private static final String TASK_FINAL_HOUR = "final_hour";

    // ==================================================================
    // 入口（BossPhaseTwo 的钩子与读档路由调）
    // ==================================================================

    /**
     * 双子全部战败 → 糖然过场（原脚本 {@code startTangranDuel}）：
     * 台词 → 清空场地内部（分帧）→ 盔甲架下坠 → 变身僵尸 → 决斗。
     */
    public static void startDuel(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            // 进入糖然三阶段（不再是二阶段）：血条换三阶段标题 + 红色
            state.tangran.phaseThreeStarted = true;
            // ★ 糖然下场：背包里必须是「中章装备」全套（四工具 + 破法弓 + 戮仙剑），缺的立刻补
            grantMidChapterKit(player, "糖然下场");
            BossPhaseTwo.refreshBar(state);
            BossFx.updateBarPlayers(state, player.getLocation());
            // 清空场地内部方块（容器连同物品一起清空；屏障围墙/顶与基岩标记保留）
            clearArenaForDescent(state);
            BossFx.sendLine(player, "§c[糖然]§b§l我的护法竟全部进入战败cg");
            scheduleLine(state, player, 60L, "§c[糖然]§b§l准备迎接我的怒火吧！");
            scheduleAction(state, 140L, () -> {
                if (BossFight.byKey(state.key) != state) return;   // 旧局的过场任务不要动新局
                startDescent(state, player);
            });
        } catch (Throwable t) {
            BossLog.warn("糖然过场异常: " + t);
        }
    }

    /**
     * 读档直开「阶段 3 = 糌然下场」（原脚本 {@code startTangranQuickStart}）：
     * 做和护法直开一样的准备，构场后直接进决斗。
     */
    public static void quickStart3(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            // ★ 见批次 4 硬约束 1：机制闸门由门面在建场地之前置位
            state.phaseTwo.combatActive = true;
            // 1) 清背包（备份 + 清空）
            try {
                BossInventory.beginBackup(player, state);
            } catch (Throwable t) {
                BossLog.warn("直开糖然：背包备份失败 " + t);
            }
            // 2) 上锁 / 清异火 / 硬关糖帝 / 饱和 / 收剑 + 发戮仙剑
            BossPhaseTwo.prepareChallenge(state, player, false, true);
            // 3) 音乐 + 构场 → 直接进决斗
            BossMusic.startPhaseTwo(state, player.getLocation(), state.phaseTwo.musicFile);
            BossPhaseTwo.startArenaSequence(state, player, () -> {
                if (BossFight.byKey(state.key) != state) return;
                // 前面的护法视为已打完：100 - 20(zqx125) - 20(Katarina) - 40(双子) = 20
                state.guardians.twin.defeated = true;
                state.tangran.hp = 20.0d;
                try {
                    BossProgress.markPhaseCleared(state.playerName, 2);
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    BossFx.sendLine(player, "§c[糖然]§b§l不！我的双子护法……");
                } catch (Throwable ignored) {
                    // 忽略
                }
                startDuel(state, player);
            });
        } catch (Throwable t) {
            BossLog.warn("直开糖然失败: " + t);
        }
    }

    /**
     * 读档直开「阶段 4 = 挑战彻底结束的过场」（原脚本 {@code startTangranPhase4QuickStart}）：
     * 同样的准备（不发剑）→ 构场（场地保留）→ 糖然跪在场地中央 → 直接从「借过一下」开始。
     */
    public static void quickStart4(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            // 只是过场，不会再开打
            state.phaseTwo.combatActive = false;
            // 1) 清背包（备份 + 清空，结束时由收场返还）
            try {
                BossInventory.beginBackup(player, state);
            } catch (Throwable t) {
                BossLog.warn("直开阶段4：背包备份失败 " + t);
            }
            // 2) 上锁 / 清异火 / 硬关糖帝 / 饱和 / 收剑（过场不需要戮仙剑）
            BossPhaseTwo.prepareChallenge(state, player, false, false);
            // 3) 构场 → 糖然跪地 → 从「借过一下」开始
            BossPhaseTwo.startArenaSequence(state, player, () -> {
                if (BossFight.byKey(state.key) != state) return;
                state.guardians.twin.defeated = true;
                state.tangran.phaseTwoStarted = true;
                // deadMob 传场地里那具召唤用盔甲架：跪像继承它的全套装备（糖然头颅+下界合金）；
                // 位置强制放在场地中央的石英地板上（那具盔甲架本来悬在场地最高处）
                Entity gearStand = state.stand;
                Location kneelLoc = null;
                BossArena.Region r = state.arena.twoRegion;
                World w = state.arena.twoWorld;
                if (r != null && w != null) {
                    kneelLoc = new Location(w,
                            r.minX() + (r.maxX() - r.minX()) / 2.0d + 0.5d,
                            r.minY() + 1.0d,
                            r.minZ() + (r.maxZ() - r.minZ()) / 2.0d + 0.5d);
                }
                enterPhase4(state, player, gearStand, kneelLoc);
                startPhase4Sequence(state, player);
            });
        } catch (Throwable t) {
            BossLog.warn("直开阶段4失败: " + t);
        }
    }

    // ==================================================================
    // 场地内部清空（原脚本 xuClearArenaForDescent：分帧，每拍 2 列）
    // ==================================================================

    /**
     * 清「场地内部」（作物、灵化方块、玩家放的东西），<b>不拆场地本身</b>：
     * 屏障围墙/顶保留，顶层正中间的基岩标记保留（挑战区域清除器还要靠它反推）。
     */
    private static void clearArenaForDescent(BossState state) {
        try {
            World world = state.arena.twoWorld;
            BossArena.Region r = state.arena.twoRegion;
            if (world == null || r == null) return;
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] x = {r.minX()};
            int midX = (r.minX() + r.maxX()) / 2;
            int midZ = (r.minZ() + r.maxZ()) / 2;
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(holder[0]);
                        return;
                    }
                    for (int step = 0; step < CLEAR_COLUMNS_PER_TICK && x[0] <= r.maxX(); step++, x[0]++) {
                        for (int z = r.minZ(); z <= r.maxZ(); z++) {
                            for (int y = r.minY() + 1; y <= r.maxY(); y++) {
                                Block b = world.getBlockAt(x[0], y, z);
                                String t = b.getType().name();
                                if (t.equals("AIR") || t.equals("CAVE_AIR") || t.equals("VOID_AIR")) continue;
                                // ★ 屏障围墙/顶保留（三阶段战斗还要围墙和顶）
                                if (t.equals("BARRIER")) continue;
                                // ★ 顶层正中间基岩标记保留（挑战区域清除器的落点）
                                if (t.equals("BEDROCK") && y == r.maxY()
                                        && x[0] == midX && z == midZ) {
                                    continue;
                                }
                                clearContainer(b);
                                b.setType(Material.AIR, false);
                            }
                        }
                    }
                    if (x[0] > r.maxX()) {
                        try {
                            for (org.bukkit.entity.Item item : state.phaseTwo.drops) {
                                BossState.removeEntity(item);
                            }
                            state.phaseTwo.drops.clear();
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        BossState.cancelTask(holder[0]);
                    }
                } catch (Throwable t) {
                    BossState.cancelTask(holder[0]);
                }
            }, 1L, 1L);
        } catch (Throwable t) {
            BossLog.warn("场地清空异常: " + t);
        }
    }

    /** 容器方块连同里面的东西一起清（原脚本：箱子/机器里的物品不能白留）。 */
    private static void clearContainer(Block b) {
        try {
            if (b.getState() instanceof org.bukkit.inventory.InventoryHolder holder) {
                holder.getInventory().clear();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 下坠与变身（原脚本 xuStartTangranDescent / xuTransformTangran / xuSpawnTangranZombie）
    // ==================================================================

    /** 下坠：盔甲架缓缓落到石英层，全程黑烟/灵魂火/雷声。 */
    private static void startDescent(BossState state, Player player) {
        try {
            // ★ 从这一刻起「盔甲架消失」就是正常流程（马上会换成僵尸），看门任务不许因此收场
            state.tangran.standSwapped = true;
            Entity stand = state.stand;
            World world = state.arena.twoWorld;
            if (stand == null || !stand.isValid()) {
                // 没有盔甲架就直接生成糖然僵尸
                state.tangran.standSwapped = true;
                spawnTangranZombie(state, player, null, null, null);
                return;
            }
            double startY = stand.getLocation().getY();
            double floorY = ((state.arena.twoRegion != null)
                    ? state.arena.twoRegion.minY() : startY - 3) + 1.0d;   // 石英层上方 1 格
            if (startY <= floorY + 0.05d) {
                transformTangran(state, player, stand);
                return;
            }
            int steps = Math.max(12, (int) Math.round((startY - floorY) / 0.12d));
            final int[] i = {0};
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.tangran.descentTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.tangran.descentTask);
                        state.tangran.descentTask = null;
                        return;
                    }
                    Entity cur = state.stand;
                    if (cur == null || !cur.isValid()) {
                        BossState.cancelTask(state.tangran.descentTask);
                        state.tangran.descentTask = null;
                        return;
                    }
                    i[0]++;
                    Location loc = cur.getLocation().clone();
                    loc.setY(startY - (startY - floorY) * (i[0] / (double) steps));
                    cur.teleport(loc);
                    try {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                                loc.clone().add(0, 1.0, 0), 14, 0.4, 0.6, 0.4, 0.02);
                        world.spawnParticle(Particle.SMOKE,
                                loc.clone().add(0, 0.4, 0), 10, 0.4, 0.5, 0.4, 0.02);
                        world.spawnParticle(Particle.END_ROD,
                                loc.clone().add(0, 1.4, 0), 4, 0.2, 0.2, 0.2, 0.01);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    if (i[0] % 4 == 0) {
                        try {
                            world.playSound(loc, "entity.lightning_bolt.thunder", 0.45f, 1.2f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    if (i[0] >= steps) {
                        BossState.cancelTask(state.tangran.descentTask);
                        state.tangran.descentTask = null;
                        transformTangran(state, player, cur);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 2L);
        } catch (Throwable t) {
            BossLog.warn("糖然下坠异常: " + t);
        }
    }

    /** 复制盔甲架六个槽位的装备（变身/跪像共用）。 */
    private static Map<String, ItemStack> copyGear(Entity mob) {
        java.util.Map<String, ItemStack> gear = new java.util.LinkedHashMap<>();
        try {
            EntityEquipment eq = mob instanceof LivingEntity le ? le.getEquipment() : null;
            if (eq != null) {
                EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                        EquipmentSlot.LEGS, EquipmentSlot.FEET,
                        EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};
                for (EquipmentSlot slot : slots) {
                    ItemStack it = eq.getItem(slot);
                    if (it != null && !it.getType().isAir()) {
                        gear.put(slot.name(), it.clone());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return gear;
    }

    private static void applyGear(LivingEntity target, Map<String, ItemStack> gear) {
        try {
            EntityEquipment eq = target.getEquipment();
            if (eq == null) return;
            for (Map.Entry<String, ItemStack> e : gear.entrySet()) {
                try {
                    eq.setItem(EquipmentSlot.valueOf(e.getKey()), e.getValue());
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 变身：复制装备 → 摘引用 → 移除盔甲架 → 原地生成糖然僵尸。 */
    private static void transformTangran(BossState state, Player player, Entity stand) {
        try {
            Location loc = stand.getLocation().clone();
            Map<String, ItemStack> gear = copyGear(stand);
            String name = stand.getCustomName();
            // ★ 必须先把 state.stand 摘掉再 remove()：本体一消失就会被当成「挑战结束」
            //   走收场 → 整块场地连石英地板一起被清空（原脚本注释：「糖然落地清场的元凶」）
            state.tangran.standSwapped = true;
            state.stand = null;
            BossState.removeEntity(stand);
            spawnTangranZombie(state, player, loc, gear, name);
        } catch (Throwable t) {
            BossLog.warn("糖然变身异常: " + t);
        }
    }

    /** 糖然僵尸：血条剩余 ×100 = 身体血量。 */
    private static void spawnTangranZombie(BossState state, Player player, Location loc,
                                           Map<String, ItemStack> gear, String name) {
        try {
            World world = state.arena.twoWorld;
            BossArena.Region r = state.arena.twoRegion;
            if (loc == null && world != null && r != null) {
                loc = new Location(world,
                        r.minX() + (r.maxX() - r.minX()) / 2.0d + 0.5d,
                        r.minY() + 1.0d,
                        r.minZ() + (r.maxZ() - r.minZ()) / 2.0d + 0.5d);
            } else if (loc == null && world != null && player != null) {
                loc = player.getLocation();
            }
            if (loc == null) return;
            double barHp = Math.max(1.0d, Math.min(BossState.PHASE_TWO_MAX_HP,
                    state.tangran.hp));
            double mobHp = 100.0d * barHp;   // 剩余血条 ×100（双子打完剩 20 → 2000 血）
            Zombie ent = world.spawn(loc, Zombie.class);
            // 装备原封过渡（含糖然头颅 → 僵尸也长着糖然的头）
            applyGear(ent, gear);
            try {
                var attr = ent.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null) attr.setBaseValue(mobHp);
            } catch (Throwable ignored) {
                // 忽略
            }
            ent.setHealth(mobHp);
            ent.setCustomName((name != null) ? name : "§c§l糖然");
            ent.setCustomNameVisible(true);
            ent.setRemoveWhenFarAway(false);
            ent.setCanPickupItems(false);
            if (player != null) ent.setTarget(player);
            state.tangran.duelMob = ent;
            state.tangran.duelBarHp = barHp;
            state.tangran.duelMaxHp = mobHp;
            BossLog.warn("糖然本体下场：血条 " + (int) barHp + "/" + (int) BossState.PHASE_TWO_MAX_HP
                    + " → 实体 " + (int) mobHp);
            BossFx.updateBarPlayers(state, loc);
            try {
                world.playSound(loc, "entity.wither.spawn", 1.0f, 0.8f);
                world.strikeLightningEffect(loc);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 90, 1.2, 1.2, 1.2, 0.06);
                world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.sendMessage("§c[糖然]§b§l来吧，战胜我！");
            } catch (Throwable ignored) {
                // 忽略
            }
            startDuelTask(state, player);
        } catch (Throwable t) {
            BossLog.warn("糖然变身失败: " + t);
        }
    }

    // ==================================================================
    // 决斗主循环（原脚本 startTangranDuelTask / xuTangranDuelMove）
    // ==================================================================

    private static void startDuelTask(BossState state, Player player) {
        try {
            BossState.cancelTask(state.tangran.duelTask);
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] sec = {0};
            state.tangran.duelTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.tangran.duelTask);
                        state.tangran.duelTask = null;
                        return;
                    }
                    Entity mob = state.tangran.duelMob;
                    if (mob == null || !mob.isValid() || mob.isDead()) {
                        BossState.cancelTask(state.tangran.duelTask);
                        state.tangran.duelTask = null;
                        return;
                    }
                    Player p = (player != null && player.isOnline()) ? player
                            : Bukkit.getPlayerExact(state.playerName);
                    if (p == null) return;
                    if (mob instanceof Mob m) {
                        m.setTarget(p);
                    }
                    double hp = (mob instanceof LivingEntity le) ? le.getHealth() : 0;
                    double barHp = Math.max(0, Math.ceil(hp / state.tangran.duelMaxHp
                            * state.tangran.duelBarHp));
                    state.tangran.hp = barHp;
                    BossPhaseTwo.refreshBar(state);
                    // 场上依旧随机刷新加强生命甘露（没了就换位置再刷一瓶）
                    BossKatarina.spawnDuelNectar(state);
                    // 技能每 6 秒一次（原脚本 d.tick % 6）
                    sec[0]++;
                    if (sec[0] % DUEL_SKILL_PERIOD == 0) {
                        duelMove(state, p, mob);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, DUEL_PERIOD, DUEL_PERIOD);
        } catch (Throwable t) {
            BossLog.warn("决斗主循环启动失败: " + t);
        }
    }

    /** 糖然技能：瞬身背刺 5 / 震荡波 3 + 击退 / 火球（伤害在事件层钉成 2）。 */
    private static void duelMove(BossState state, Player player, Entity mob) {
        try {
            World world = mob.getWorld();
            int roll = (int) (Math.random() * 3);
            if (roll == 0) {
                // 瞬身背刺：传送到玩家背后 1.4 格 + 5 点直伤
                Vector dir = player.getLocation().getDirection().normalize().multiply(-1.4);
                Location back = player.getLocation().clone().add(dir.getX(), 0, dir.getZ());
                back.setYaw(player.getLocation().getYaw() + 180);
                mob.teleport(back);
                try {
                    world.spawnParticle(Particle.SMOKE, back, 30, 0.3, 0.6, 0.3, 0.05);
                    world.playSound(back, "entity.enderman.teleport", 1.0f, 0.8f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                player.damage(5.0d);
                player.sendMessage("§c[糖然]§b§l躲得掉吗？");
            } else if (roll == 1) {
                // 震荡波：3 点直伤 + 径向击退
                try {
                    world.spawnParticle(Particle.EXPLOSION, mob.getLocation(), 1, 0, 0, 0, 0);
                    world.playSound(mob.getLocation(), "entity.generic.explode", 0.8f, 0.7f);
                    Vector v = player.getLocation().toVector()
                            .subtract(mob.getLocation().toVector()).normalize().multiply(1.1);
                    v.setY(0.6);
                    player.setVelocity(v);
                } catch (Throwable ignored) {
                    // 忽略
                }
                player.damage(3.0d);
                player.sendMessage("§c[糖然]§b§l站稳了。");
            } else {
                // 火球：伤害由事件层固定为 2（登记进决斗火球表，只留最近 8 颗）
                try {
                    LivingEntity le = (mob instanceof LivingEntity m) ? m : null;
                    if (le == null) return;
                    Location eye = le.getEyeLocation();
                    Vector dir2 = player.getLocation().add(0, 1, 0).toVector()
                            .subtract(eye.toVector()).normalize();
                    LargeFireball fb = world.spawn(eye.clone().add(dir2.clone().multiply(0.8)),
                            LargeFireball.class);
                    fb.setDirection(dir2);
                    fb.setYield(0.0f);
                    fb.setIsIncendiary(false);
                    List<Entity> fireballs = state.tangran.duelFireballs;
                    fireballs.add(fb);
                    while (fireballs.size() > MAX_FIREBALLS) {
                        fireballs.remove(0);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 通关与四阶段（原脚本 xuFinishPhaseTwo / xuEnterPhase4 / xuStartPhase4Sequence）
    // ==================================================================

    /** 决斗体死亡（事件层 {@code EntityDeathEvent} 调，原脚本 {@code xuOnServantDeath} 的决斗分支）。 */
    public static boolean onDuelMobDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        try {
            Entity dead = event.getEntity();
            for (BossState st : BossFight.all()) {
                if (st.tangran.duelMob == null || !st.tangran.duelMob.equals(dead)) continue;
                event.getDrops().clear();
                event.setDroppedExp(0);
                Player owner = Bukkit.getPlayerExact(st.playerName);
                String cause = "?";
                try {
                    if (dead instanceof LivingEntity le && le.getLastDamageCause() != null) {
                        cause = le.getLastDamageCause().getCause().name();
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                BossLog.warn("糖然本体倒下（死因=" + cause + "）→ 进入通关过场");
                if (owner != null) {
                    finishPhaseTwo(owner, st, dead);
                }
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 三阶段（糖然）通关 → 四阶段过场。 */
    private static void finishPhaseTwo(Player player, BossState state, Entity deadMob) {
        try {
            enterPhase4(state, player, deadMob, null);
            startPhase4Sequence(state, player);
        } catch (Throwable t) {
            BossLog.warn("三阶段结算异常: " + t);
        }
    }

    /**
     * 进入四阶段：通关记账 + 天道证明 + 糖然跪地
     * （原脚本 {@code xuEnterPhase4}）。
     *
     * @param deadMob   自然流程 = 被击倒的糖然僵尸；读档流程 = 召唤用盔甲架（装备原样搬上跪像）
     * @param forcedLoc 读档流程强制把跪像放在石英地板上
     */
    private static void enterPhase4(BossState state, Player player, Entity deadMob, Location forcedLoc) {
        try {
            BossProgress.markPhaseCleared(state.playerName, 3);
            BossProgress.markPhaseCleared(state.playerName, 2);
            BossProgress.markPhaseCleared(state.playerName, 4);
            // 双子账本：不清理（物资点数与购买记录都保留，跨局累积）；只把效果收掉
            try {
                Player upOwner = Bukkit.getPlayerExact(state.playerName);
                if (upOwner != null) {
                    BossKatarina.clearUpgrades(upOwner);
                    RealmScript.challengeLock(upOwner, false);   // 挑战结束，糖帝属性开关还原
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            state.tangran.hp = 0.0d;
            BossPhaseTwo.refreshBar(state);
            TwinMinigame.stop(state);
            state.phaseTwo.combatActive = false;
            // 天道证明
            try {
                writeTianDaoProof(player);
            } catch (Throwable t) {
                BossLog.warn("天道证明写入失败: " + t);
            }
            // 糖然跪地、低头（本体换成跪姿盔甲架，装备原封过渡）
            state.tangran.standSwapped = true;
            kneel(state, player, deadMob, forcedLoc);
        } catch (Throwable t) {
            BossLog.warn("四阶段入场结算异常: " + t);
        }
    }

    /** 四阶段过场对话 + 任务其八 + 糖然散去/场地消散/彻底结束（原脚本 {@code xuStartPhase4Sequence}）。 */
    private static void startPhase4Sequence(BossState state, Player player) {
        try {
            // 四阶段对话（读档阶段 4 也从这一句开始）
            scheduleLine(state, player, 40L, "§f[你] §f借过一下");
            scheduleLine(state, player, 80L, "§f[你] §f你也不过如此嘛");
            scheduleLine(state, player, 120L, "§c[糖然]§c§l……好，好得很");
            scheduleLine(state, player, 160L, "§c[糖然]§c§l我认可你了。");
            scheduleLine(state, player, 200L, "§e[旁白] §f天道证明已记录");
            scheduleLine(state, player, 240L, "§c[糖然]§c§l中章之后，还有更远的路，去吧！");
            scheduleLine(state, player, 280L, "§e[旁白] §f请进入升维之路。");
            // ★ 旁白说完 → 把「任务其八·终焉之刻」标记为已完成
            //   （不动 ascensionStage：升维之路的阶梯是否可升，看的是任务 completed）
            scheduleAction(state, 300L, () -> {
                if (BossFight.byKey(state.key) != state) return;
                completeTask(player, TASK_FINAL_HOUR);
            });
            // 糖然散去 + 战斗场地消散
            scheduleAction(state, 340L, () -> dissolve(state));
            scheduleAction(state, 400L, () -> {
                try {
                    // ★ 这一拍可能是「上一局」的收尾任务：玩家已经开了新局就不要再动状态
                    if (BossFight.byKey(state.key) != state) return;
                    player.playSound(player.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.0f);
                    player.sendMessage("§a§l四阶段挑战结束，中章挑战彻底结束！");
                } catch (Throwable ignored) {
                    // 忽略
                }
                BossBattleScript.forceStop(state, "四阶段挑战结束");
            });
        } catch (Throwable t) {
            BossLog.warn("四阶段过场异常: " + t);
        }
    }

    // ==================================================================
    // 任务其八 / 天道证明（跨脚本存档，字段原样保留）
    // ==================================================================

    /**
     * 把某个任务标记为已完成：只往 {@code completed} 里补一个 id，
     * <b>别的字段原样保留</b>（这份文件与 任务.js / 升维之路.js 共用）。
     */
    private static void completeTask(Player player, String taskId) {
        try {
            if (player == null || taskId == null) return;
            Map<String, Object> data = AddonPaths.readJson(
                    new java.io.File(AddonPaths.configDir("任务"), player.getName() + ".json"));
            boolean has = false;
            List<Object> completed = new ArrayList<>();
            if (data != null && data.get("completed") instanceof List<?> list) {
                for (Object o : list) {
                    completed.add(o);
                    if (String.valueOf(o).equals(taskId)) has = true;
                }
            }
            if (!has) {
                completed.add(taskId);
                if (data == null) {
                    data = new java.util.LinkedHashMap<>();
                }
                data.put("completed", completed);
                AddonPaths.writeJson(
                        new java.io.File(AddonPaths.configDir("任务"), player.getName() + ".json"), data);
            }
            BossLog.warn("任务进度：已完成 " + taskId + "（" + player.getName() + "）"
                    + (has ? "（本来就有）" : ""));
        } catch (Throwable t) {
            BossLog.warn("写入任务完成进度失败: " + t);
        }
    }

    /**
     * 天道证明：{@code addon_configs/糖然科技/天道证明/<玩家名>.json}
     * （升维之路等脚本读它来放行后续内容）。
     */
    private static void writeTianDaoProof(Player player) {
        try {
            if (player == null) return;
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("uuid", player.getUniqueId().toString());
            data.put("name", player.getName());
            data.put("phase3Cleared", true);
            data.put("finalHour", true);
            data.put("time", System.currentTimeMillis());
            AddonPaths.writeJson(
                    new java.io.File(AddonPaths.configDir("天道证明"), player.getName() + ".json"), data);
        } catch (Throwable t) {
            BossLog.warn("天道证明写入失败: " + t);
        }
    }

    // ==================================================================
    // 糖然跪地与散去（原脚本 xuTangranKneel / xuTangranDissolve）
    // ==================================================================

    /** 糖然跪地：换成同位置、同装备的跪姿盔甲架（双腿折起、身体下压、头低下）。 */
    private static void kneel(BossState state, Player player, Entity deadMob, Location forcedLoc) {
        try {
            World world = (deadMob != null) ? deadMob.getWorld() : state.arena.twoWorld;
            if (world == null) return;
            Location loc = (forcedLoc != null) ? forcedLoc.clone()
                    : (deadMob != null ? deadMob.getLocation().clone()
                    : player.getLocation().clone());
            // 抄装备
            Map<String, ItemStack> gear = copyGear(deadMob);
            String name = (deadMob != null) ? deadMob.getCustomName() : null;
            // ★ 如果换掉的就是「召唤用盔甲架」，先摘引用再 remove：否则看门任务
            //   会以为糖然本体没了 → 判成挑战结束收场，把建好的战斗场地一起清掉
            if (state.stand != null && state.stand.equals(deadMob)) {
                state.tangran.standSwapped = true;
                state.stand = null;
            }
            BossState.removeEntity(deadMob);
            ArmorStand stand = world.spawn(loc, ArmorStand.class);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setInvulnerable(true);
            stand.setCustomName((name != null) ? name : "§c§l糖然");
            stand.setCustomNameVisible(true);
            // 跪姿：大腿前折、小腿回收，整体下压；头低下去
            stand.setRightLegPose(new EulerAngle(-1.45, 0.15, 0.1));
            stand.setLeftLegPose(new EulerAngle(-1.45, -0.15, -0.1));
            stand.setBodyPose(new EulerAngle(0.42, 0, 0));
            stand.setHeadPose(new EulerAngle(0.55, 0, 0));
            stand.setRightArmPose(new EulerAngle(-0.35, 0, -0.25));
            stand.setLeftArmPose(new EulerAngle(-0.35, 0, 0.25));
            stand.teleport(loc.clone().add(0, -0.42, 0));
            // 装备原封过渡
            applyGear(stand, gear);
            // ★ 装备槽全部禁用：跪像身上的糖然头颅/下界合金不能被玩家拿走
            stand.setDisabledSlots(new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND});
            state.tangran.kneelStand = stand;
            try {
                world.playSound(loc, "entity.wither.hurt", 1.0f, 0.6f);
                world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.6, 0), 30, 0.4, 0.5, 0.4, 0.03);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable t) {
            BossLog.warn("糖然跪姿异常: " + t);
        }
    }

    /** 糖然散去 + 战斗场地消散（整块含地板，分帧清）。 */
    private static void dissolve(BossState state) {
        try {
            World world = state.arena.twoWorld;
            BossArena.Region r = state.arena.twoRegion;
            // 糖然散去
            Entity stand = state.tangran.kneelStand;
            Location loc = (stand != null && stand.isValid()) ? stand.getLocation().clone() : null;
            if (loc != null && world != null) {
                try {
                    world.spawnParticle(Particle.SOUL, loc.clone().add(0, 0.9, 0), 60, 0.5, 0.9, 0.5, 0.04);
                    world.spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.9, 0), 40, 0.5, 0.9, 0.5, 0.03);
                    world.playSound(loc, "entity.enderman.teleport", 1.0f, 0.7f);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            BossState.removeEntity(stand);
            state.tangran.kneelStand = null;
            // 场地消散：只清这一块场地（含石英地板），绝不外扩；分帧清理，避免卡服
            if (world != null && r != null) {
                Plugin plugin = TT.plugin();
                if (plugin != null) {
                    final int[] x = {r.minX()};
                    BukkitTask[] holder = new BukkitTask[1];
                    holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                        try {
                            for (int step = 0; step < CLEAR_COLUMNS_PER_TICK && x[0] <= r.maxX(); step++, x[0]++) {
                                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                                    for (int y = r.minY(); y <= r.maxY(); y++) {
                                        Block b = world.getBlockAt(x[0], y, z);
                                        String t = b.getType().name();
                                        if (t.equals("AIR") || t.equals("CAVE_AIR") || t.equals("VOID_AIR")) continue;
                                        clearContainer(b);
                                        b.setType(Material.AIR, false);
                                    }
                                }
                            }
                            if (x[0] > r.maxX()) {
                                BossState.cancelTask(holder[0]);
                            }
                        } catch (Throwable t) {
                            BossState.cancelTask(holder[0]);
                        }
                    }, 1L, 1L);
                }
                try {
                    world.playSound(new Location(world, (r.minX() + r.maxX()) / 2.0d,
                            r.minY() + 1, (r.minZ() + r.maxZ()) / 2.0d),
                            "block.beacon.deactivate", 1.0f, 0.6f);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable t) {
            BossLog.warn("糖然散去异常: " + t);
        }
    }

    // ==================================================================
    // 中章装备补发（原脚本 xuGrantMidChapterKit）
    // ==================================================================

    /** 补齐「中章装备」全套（四工具 + 破法弓 + 戮仙剑），缺什么补什么。 */
    public static int grantMidChapterKit(Player player, String reason) {
        int given = 0;
        try {
            if (player == null || !player.isOnline()) return 0;
            // 1) 四件中章工具（已有就不重复发）
            try {
                given += Math.max(0, TwinTools.ensureTools(player));
            } catch (Throwable ignored) {
                // 忽略
            }
            // 2) 破法弓（已有就不重复发）
            try {
                if (!TwinMinigame.hasPofabow(player)) {
                    ItemStack bow = Pofabow.make();
                    var left = player.getInventory().addItem(bow);
                    if (!left.isEmpty()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), bow);
                    }
                    given++;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 3) 戮仙剑
            try {
                if (!EquipmentBridge.hasLuxianSword(player)) {
                    if (EquipmentBridge.giveLuxianSword(player)) {
                        given++;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (given > 0) {
                BossLog.warn((reason == null ? "糖然下场" : reason) + "：补齐中章装备 "
                        + given + " 件（" + player.getName() + "）");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return given;
    }

    // ==================================================================
    // 事件层规则（批次 8 并表）
    // ==================================================================

    /**
     * 决斗体打玩家：固定 1 点（原脚本 12820-12824）。
     *
     * @return true = 已接管
     */
    public static boolean duelMobHit(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return false;
            BossState st = BossFight.bySummoner(victim);
            if (st == null || st.tangran.duelMob == null) return false;
            Entity damager = event.getDamager();
            if (damager == null || !st.tangran.duelMob.equals(damager)) return false;
            event.setDamage(1.0d);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 糖然火球：伤害固定 2（原脚本 12826-12834；火球登记在决斗火球表里）。
     *
     * @return true = 已接管
     */
    public static boolean duelFireballHit(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return false;
            BossState st = BossFight.bySummoner(victim);
            if (st == null) return false;
            Entity damager = event.getDamager();
            if (damager == null) return false;
            for (Entity fb : st.tangran.duelFireballs) {
                if (fb != null && fb.equals(damager)) {
                    event.setDamage(2.0d);
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static void scheduleLine(BossState state, Player player, long delay, String text) {
        try {
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.cutscene.dialogueTasks.add(Bukkit.getScheduler().runTaskLater(plugin,
                    () -> BossFx.sendLine(player, text), delay));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void scheduleAction(BossState state, long delay, Runnable action) {
        try {
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.cutscene.dialogueTasks.add(Bukkit.getScheduler().runTaskLater(plugin,
                    action, delay));
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
