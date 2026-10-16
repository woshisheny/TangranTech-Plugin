package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 战斗入口：<b>召唤糖然 → 升空过场 → 开场对白 → √/× 选择 → 一阶段</b>。
 *
 * <h2>为什么这一类必须单独一个类</h2>
 * <p>原脚本的 {@code startBossFight} 是<b>一个 370 行的函数</b>，里面塞了四件互不相干的事：
 * 孤儿清理、本体生成、升空过场、按 {@code startPhase} 分派到一/二阶段。
 * 而它同时是<b>唯一入口</b>（BOSS战 没有任何 yml 绑定）——
 * 它不落地，前面几批做的护法、道具、一阶段<b>全都点不到</b>。</p>
 *
 * <h2>三条「清场」纪律（原脚本注释里的原话）</h2>
 * <ol>
 *   <li><b>同一个召唤者同时只能存在一个糖然</b>：开打前先把「带本玩家 owner 标记的」
 *       「附近无标记的」「全服无标记的」糖然各扫一遍清掉，否则会叠加出两个本体
 *       （原脚本为此写了三遍 while 循环，各带上限 20/20/100 防死循环）。</li>
 *   <li><b>清血条只清「这个玩家」的</b>，绝不 {@code clearAll} ——
 *       别人正在打的局不能被牵连（多玩家并行）。</li>
 *   <li><b>玩家本体上写 PDC 标记</b>（{@code tangran_boss_entity}）：跨脚本重载 /
 *       服务器重启也能查到「这个玩家当前有一局」，否则重载后会再叠一个糖然。</li>
 * </ol>
 *
 * <h2>升空过场的参数</h2>
 * <ul>
 *   <li>总时长 80 步 × 2 刻 = 8 秒，总共升 <b>8 格</b>（每步 +0.1）；</li>
 *   <li>每步在糖然两侧画螺旋粒子（半径 1.5，两条臂，颜色 9 色循环）；</li>
 *   <li><b>每秒在周围 20×20 内随机落 3~10 道雷</b>；</li>
 *   <li>到点全服广播「中章BOSS·糖然 已降临！」。</li>
 * </ul>
 */
public final class BossFightEntry {

    private BossFightEntry() {}

    /** 糖然本体在玩家 PDC 上的标记键（原脚本 {@code BOSS_PLAYER_ENTITY_KEY}）。 */
    public static final NamespacedKey PLAYER_ENTITY_KEY =
            new NamespacedKey(TT.plugin, "tangran_boss_entity");

    /** 糖然本体上的 owner 标记键（原脚本 {@code BOSS_OWNER_KEY}）。 */
    public static final NamespacedKey OWNER_KEY =
            new NamespacedKey(TT.plugin, "tangran_boss_owner");

    /** 螺旋粒子的 9 个颜色（原脚本 {@code SPIRAL_COLORS}）。 */
    private static final Color[] SPIRAL_COLORS = {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE,
    };

    /** 升空总步数（8 秒）。 */
    private static final int ASCENT_STEPS = 80;

    /** 每步升多少格。 */
    private static final double ASCENT_PER_STEP = 0.1d;

    /** 本体血量伪装值（真实血量在 BossBar 上，这只是「免得被顺手几刀秒掉」）。 */
    private static final double STAND_HEALTH = 1024.0d;

    /** 一阶段血条标题。 */
    private static final String PHASE_ONE_BAR = "§c§l糖然一阶段";

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 开始一场挑战。
     *
     * @param player 召唤者
     * @param startPhase 1 = 从头开始（一阶段）；2 = 直接二阶段（阶段二入口）
     * @return 是否成功起局
     */
    public static boolean start(Player player, int startPhase) {
        return start(player, startPhase, null);
    }

    /**
     * 开始一场挑战（可指定读档护法）。
     *
     * @param player     召唤者
     * @param startPhase 1 = 从头开始；2 = 直接二阶段
     * @param guardianId 读档直开的护法 id；{@code null} = 正常从 zqx125 开始。
     *                   传 {@link BossState#GID_SKIP_TO_TANGRAN3} /
     *                   {@link BossState#GID_SKIP_TO_TANGRAN4} 表示「跳过护法直接糖然三/四阶段」
     *                   （批次 7 接入，未接入时由 {@link BossPhaseTwo#routeAfterEntry} 如实收场）
     * @return 是否成功起局
     */
    public static boolean start(Player player, int startPhase, String guardianId) {
        try {
            if (player == null || !player.isOnline()) return false;

            // 同一召唤者同时只能存在一个糖然
            BossState existing = BossFight.bySummoner(player);
            if (existing != null) {
                try {
                    if (existing.stand != null && existing.stand.isValid()) {
                        player.sendMessage("§c你已经在挑战中章BOSS了！");
                        return false;
                    }
                } catch (Throwable ignored) {
                    // 本体已失效：继续走「清掉旧状态」
                }
                BossBattleScript.forceStop(existing, "重复召唤，清理失效旧局");
            }
            // 玩家身上有标记但表里没有（重载残留）：也不让开
            Entity marked = getMarkedStand(player);
            if (marked != null) {
                if (BossFight.byEntity(marked) != null) {
                    player.sendMessage("§c你已经在挑战中章BOSS了！");
                    return false;
                }
                BossState.removeEntity(marked);
                clearMarkedStand(player);
            }

            // 三遍清理：带标记的 → 附近无标记的 → 全服无标记的
            int removed = 0;
            Entity orphan = findStandByOwner(player);
            while (orphan != null && removed < 20) {
                BossState.removeEntity(orphan);
                removed++;
                orphan = findStandByOwner(player);
            }
            removed = 0;
            Entity unmarked = findNearbyUnmarkedStand(player);
            while (unmarked != null && removed < 20) {
                BossState.removeEntity(unmarked);
                removed++;
                unmarked = findNearbyUnmarkedStand(player);
            }
            removed = 0;
            Entity unmarkedGlobal = findAnyUnmarkedStand();
            while (unmarkedGlobal != null && removed < 100) {
                BossState.removeEntity(unmarkedGlobal);
                removed++;
                unmarkedGlobal = findAnyUnmarkedStand();
            }
            if (removed > 0) clearBarsFor(player);

            clearBarsFor(player);
            if (startPhase != 2) {
                player.sendMessage("§c九霄之外，雷海倒悬，虚空之中显化出中章BOSS");
            }

            Location eye = player.getEyeLocation();
            Location loc = eye.clone().add(eye.getDirection().multiply(6));
            double startY = loc.getY();

            ArmorStand stand = spawnStand(player, loc);
            if (stand == null) {
                player.sendMessage("§cBOSS召唤失败");
                return false;
            }

            String key = BossFight.keyOf(player);
            BossState state = new BossState(key);
            state.playerName = player.getName();
            state.summonerUuid = key;
            state.stand = stand;
            state.pendingPhase = startPhase;
            state.choiceStage = "phase1";
            state.choiceMade = false;
            BossFight.put(state);
            setMarkedStand(player, stand);

            if (startPhase == 2) {
                // 二阶段入口：紫血条 + 注视/看门任务 + 按读档目标分派
                // （构场 / 清背包上锁 / 召唤护法在 BossPhaseTwo）
                enterPhaseTwoEntry(state, player, guardianId);
                return true;
            }

            // 一阶段血条：只对周围玩家显示
            try {
                org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                        PHASE_ONE_BAR, org.bukkit.boss.BarColor.RED,
                        org.bukkit.boss.BarStyle.SOLID);
                bar.setProgress(1.0d);
                bar.setVisible(true);
                state.bar = bar;
                BossFx.updateBarPlayers(state, stand.getLocation());
            } catch (Throwable ignored) {
                // 忽略
            }

            try {
                player.playSound(player.getLocation(), "entity.wither.spawn", 0.8f, 0.8f);
            } catch (Throwable ignored) {
                // 忽略
            }

            startAscent(state, player, startY);
            return true;
        } catch (Throwable t) {
            BossLog.warn("BOSS 召唤失败: " + t);
            return false;
        }
    }

    // ==================================================================
    // 二阶段入口（原脚本 startBossFight 的 startPhase === 2 分支）
    // ==================================================================

    /**
     * 二阶段起局：读档目标落位 → 紫血条 → 注视/看门任务 → 分派
     * （原脚本 {@code startBossFight} 12385-12441 行）。
     *
     * <p>与一阶段入口的差别：<b>不播升空过场、不广播</b>（原脚本特意跳过），
     * 直接以「糖然已悬在玩家面前」的状态开局。</p>
     */
    private static void enterPhaseTwoEntry(BossState state, Player player, String guardianId) {
        // 读档目标 → state 标记（原脚本 TANGRAN_PENDING_GUARDIAN + metadata 双通道，Java 直接传参）
        if (BossState.GID_SKIP_TO_TANGRAN3.equals(guardianId)) {
            state.guardians.startFromTangran = true;
        } else if (BossState.GID_SKIP_TO_TANGRAN4.equals(guardianId)) {
            state.guardians.startFromPhase4 = true;
        } else if (guardianId != null) {
            state.guardians.activeId = guardianId;
        }
        state.phaseOne.started = true;      // 一阶段不再可入（原 phaseOneStarted）
        state.pendingPhase = 2;

        // 紫血条：糖然二阶段 100/100
        try {
            org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                    BossFx.phaseTwoBarTitle(BossState.PHASE_TWO_MAX_HP),
                    org.bukkit.boss.BarColor.PURPLE, org.bukkit.boss.BarStyle.SOLID);
            bar.setProgress(1.0d);
            bar.setVisible(true);
            state.bar = bar;
            BossFx.updateBarPlayers(state, state.stand.getLocation());
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            player.playSound(player.getLocation(), "entity.ender_dragon.growl", 0.7f, 0.7f);
        } catch (Throwable ignored) {
            // 忽略
        }
        startLookTask(state, state.stand, player);
        startWatchTask(state, state.stand);
        BossPhaseTwo.routeAfterEntry(state, player);
    }

    // ==================================================================
    // 本体
    // ==================================================================

    /** 生成糖然本体：无重力、无敌、不占碰撞、装备全锁。 */
    private static ArmorStand spawnStand(Player player, Location loc) {
        try {
            ArmorStand stand = player.getWorld().spawn(loc, ArmorStand.class);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
            stand.setCustomName("§c§l糖然");
            stand.setCustomNameVisible(true);
            try {
                stand.getPersistentDataContainer().set(OWNER_KEY, PersistentDataType.STRING,
                        player.getUniqueId().toString());
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                var equip = stand.getEquipment();
                if (equip != null) {
                    equip.setChestplate(trimmed(Material.NETHERITE_CHESTPLATE));
                    equip.setLeggings(trimmed(Material.NETHERITE_LEGGINGS));
                    equip.setBoots(trimmed(Material.NETHERITE_BOOTS));
                    equip.setHelmet(makeTangranHead());
                    // 装备槽全锁：玩家不能脱糖然的装备
                    stand.setDisabledSlots(new EquipmentSlot[]{
                            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                    });
                }
            } catch (Throwable t) {
                BossLog.warn("糖然装备设置失败: " + t);
            }
            return stand;
        } catch (Throwable t) {
            BossLog.warn("糖然本体生成失败: " + t);
            return null;
        }
    }

    private static ItemStack trimmed(Material material) {
        try {
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armor) {
                armor.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(
                        org.bukkit.inventory.meta.trim.TrimMaterial.QUARTZ,
                        org.bukkit.inventory.meta.trim.TrimPattern.SILENCE));
            }
            item.setItemMeta(meta);
            return item;
        } catch (Throwable t) {
            return new ItemStack(material, 1);
        }
    }

    /** 糖然的头颅（皮肤解析在批次 9，当前走「按名字出头颅」兜底通道）。 */
    private static ItemStack makeTangranHead() {
        return BossGuardianSkin.head("Tang_ran", null, null);
    }

    // ==================================================================
    // 升空过场
    // ==================================================================

    private static void startAscent(BossState state, Player player, double startY) {
        try {
            final int[] step = {0};
            org.bukkit.plugin.Plugin plugin = TT.plugin;
            if (plugin == null) return;
            state.tangran.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    Entity stand = state.stand;
                    if (stand == null || !stand.isValid()) {
                        BossState.cancelTask(state.tangran.task);
                        state.tangran.task = null;
                        BossBattleScript.forceStop(state, "升空途中本体消失");
                        return;
                    }
                    step[0]++;
                    Location cur = stand.getLocation();
                    cur.setY(startY + step[0] * ASCENT_PER_STEP);   // 8 秒升 8 格
                    faceLocation(cur, player);
                    stand.teleport(cur);
                    spawnSpiralParticles(cur, step[0]);
                    BossFx.updateBarPlayers(state, cur);

                    // 每秒在糖然周围 20×20 内随机落雷
                    if (step[0] % 10 == 0) strikeLightningAround(cur);

                    if (step[0] >= ASCENT_STEPS) {
                        BossState.cancelTask(state.tangran.task);
                        state.tangran.task = null;
                        broadcastArrival();
                        startDialogue(state, player);
                        startLookTask(state, stand, player);
                        startWatchTask(state, stand);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 2L, 2L);
        } catch (Throwable t) {
            BossLog.warn("升空过场启动失败: " + t);
        }
    }

    /** 让一个位置正对玩家眼睛（原脚本 {@code applyFacingToLocation}）。 */
    private static void faceLocation(Location loc, Player player) {
        try {
            Location target = player.getEyeLocation();
            double dx = target.getX() - loc.getX();
            double dy = target.getY() - loc.getY();
            double dz = target.getZ() - loc.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < 0.0001d && Math.abs(dy) < 0.0001d) return;
            loc.setYaw((float) (Math.atan2(-dx, dz) * 180.0d / Math.PI));
            loc.setPitch((float) (-Math.atan2(dy, horiz) * 180.0d / Math.PI));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 两条螺旋臂粒子（半径 1.5，颜色 9 色循环）。 */
    private static void spawnSpiralParticles(Location loc, int step) {
        try {
            if (loc.getWorld() == null) return;
            double angle = step * 0.55d;
            double radius = 1.5d;
            for (int arm = 0; arm < 2; arm++) {
                double a = angle + arm * Math.PI;
                double x = loc.getX() + Math.cos(a) * radius;
                double z = loc.getZ() + Math.sin(a) * radius;
                double y = loc.getY() + Math.sin(a * 2) * 0.6d;
                Color color = SPIRAL_COLORS[(step + arm * 3) % SPIRAL_COLORS.length];
                loc.getWorld().spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(color, 1.1f));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 周围 20×20 内随机落 3~10 道雷。 */
    private static void strikeLightningAround(Location center) {
        try {
            int count = 3 + (int) Math.floor(Math.random() * 8);
            for (int i = 0; i < count; i++) {
                double dx = Math.random() * 20 - 10;
                double dz = Math.random() * 20 - 10;
                center.getWorld().strikeLightningEffect(center.clone().add(dx, 0, dz));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void broadcastArrival() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage("§c§l中章BOSS·糖然 已降临！");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 让本体一直正对玩家（每 2 刻一次）。 */
    private static void startLookTask(BossState state, Entity stand, Player player) {
        try {
            org.bukkit.plugin.Plugin plugin = TT.plugin;
            if (plugin == null) return;
            state.tangran.lookTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state
                            || stand == null || !stand.isValid()) {
                        BossState.cancelTask(state.tangran.lookTask);
                        state.tangran.lookTask = null;
                        return;
                    }
                    Location loc = stand.getLocation();
                    faceLocation(loc, player);
                    stand.setRotation(loc.getYaw(), loc.getPitch());
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 2L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 看门任务：本体被<b>非正常途径</b>消掉时收场。
     *
     * <p>例外：{@code tangranStandSwapped}（换形态：盔甲架 → 僵尸 → 跪像）是正常过场，
     * 不能当挑战结束。该标记由阶段三/四设置（批次 7）。</p>
     */
    private static void startWatchTask(BossState state, Entity stand) {
        try {
            org.bukkit.plugin.Plugin plugin = TT.plugin;
            if (plugin == null) return;
            state.tangran.watchTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (stand == null || !stand.isValid()) {
                        BossState.cancelTask(state.tangran.watchTask);
                        state.tangran.watchTask = null;
                        if (state.tangran.standSwapped) {
                            // 正常过场：糖然已经换成别的形态了
                            return;
                        }
                        BossLog.warn("看门任务：糖然本体消失且非换形态 → 收场清场（"
                                + state.playerName + "）");
                        BossBattleScript.forceStop(state, "糖然本体被异常移除");
                        return;
                    }
                    BossFx.updateBarPlayers(state, stand.getLocation());
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 20L, 20L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 开场对白 → √/× → 一阶段
    // ==================================================================

    private static void startDialogue(BossState state, Player player) {
        scheduleLine(state, player, 0L, "§b§l😀糖神开播啦！😀");
        scheduleLine(state, player, 40L, "§c[糖然]§b§l绷住的是给");
        scheduleLine(state, player, 80L,
                "§c[糖然]§b§l你走到了这一步，应知一路不易，如此，你还是要继续挑战我吗。");
        scheduleAction(state, 120L, () -> sendChoiceOptions(player));
    }

    /** 可点击的 √/× 选项（Adventure 组件；失败时退回 tellraw）。 */
    public static void sendChoiceOptions(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            net.kyori.adventure.text.Component accept = net.kyori.adventure.text.Component
                    .text("[√]")
                    .color(net.kyori.adventure.text.format.TextColor.color(0x55FF55))
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            "/tangranboss_accept"))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            net.kyori.adventure.text.Component.text("继续挑战")));
            net.kyori.adventure.text.Component decline = net.kyori.adventure.text.Component
                    .text("[×]")
                    .color(net.kyori.adventure.text.format.TextColor.color(0xFF5555))
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            "/tangranboss_decline"))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            net.kyori.adventure.text.Component.text("结束挑战")));
            net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component
                    .empty().append(accept).append(net.kyori.adventure.text.Component.text("    "))
                    .append(decline);
            player.sendMessage(msg);
        } catch (Throwable t) {
            BossLog.warn("点击选项发送失败: " + t);
        }
    }

    /** 处理 √（原脚本 {@code handleAccept}）。 */
    public static void handleAccept(Player player) {
        try {
            BossState state = resolve(player);
            if (state == null) {
                player.sendMessage("§c未找到进行中的BOSS挑战，请重新召唤。");
                return;
            }
            // 双子护法选项（批次 6）：√ = 发四件中章工具并启动太极召唤阵
            if ("twin_start".equals(state.choiceStage)) {
                if (state.choiceMade) return;
                state.choiceMade = true;
                BossTwin.handleAccept(state, player);
                return;
            }
            // 二阶段选项（批次 4）：√ = 备份背包并进入二阶段正流程。
            // 备份失败时 BossPhaseTwo 返回 false，这里把 choiceMade 还回去让玩家能重点。
            if ("phase2_options".equals(state.choiceStage)) {
                if (state.choiceMade) return;
                state.choiceMade = true;
                if (!BossPhaseTwo.handleAccept(state, player)) {
                    state.choiceMade = false;
                }
                return;
            }
            if (state.choiceMade) return;
            state.choiceMade = true;
            BossFx.sendLine(player, "§c[糖然]§b§l这不是个明智的选择");
            scheduleLine(state, player, 40L, "§c[糖然]§b§l本关考验你躲避功夫");
            // 静态审查修正：start 失败（找不到空域等）时把 choiceMade 还回去，
            // 否则 √/× 双双失效、挑战卡死到玩家下线（原脚本此处同样没还，属移植继承 bug）
            scheduleAction(state, 100L, () -> {
                if (!BossPhaseOne.start(state, player)) {
                    state.choiceMade = false;
                }
            });
        } catch (Throwable t) {
            BossLog.warn("接受挑战处理失败: " + t);
        }
    }

    /** 处理 ×（原脚本 {@code handleDecline}）。 */
    public static void handleDecline(Player player) {
        try {
            BossState state = resolve(player);
            if (state == null) {
                player.sendMessage("§c未找到进行中的BOSS挑战，请重新召唤。");
                return;
            }
            // 双子护法选项（批次 6）：× = 糖然嘲讽 → 直接结束挑战
            if ("twin_start".equals(state.choiceStage)) {
                if (state.choiceMade) return;
                state.choiceMade = true;
                BossTwin.handleDecline(state, player);
                return;
            }
            // 二阶段选项（批次 4）：× = 一句「可惜，」然后收场还背包
            if ("phase2_options".equals(state.choiceStage)) {
                if (state.choiceMade) return;
                state.choiceMade = true;
                BossPhaseTwo.handleDecline(state, player);
                return;
            }
            if (state.choiceMade) return;
            state.choiceMade = true;
            BossFx.sendLine(player, "§c[糖然]§b§l菜就多练");
            scheduleAction(state, 40L,
                    () -> BossPhaseOne.fail(state, player, "你选择了放弃。"));
        } catch (Throwable t) {
            BossLog.warn("拒绝挑战处理失败: " + t);
        }
    }

    /** 按玩家找当前战局（先按键，再按名字兜底）。 */
    private static BossState resolve(Player player) {
        BossState st = BossFight.bySummoner(player);
        if (st != null) return st;
        return BossFight.byName(player.getName());
    }

    // ==================================================================
    // 计划任务小工具（统一记到 cutscene.dialogueTasks 上，收场时一起收）
    // ==================================================================

    private static void scheduleLine(BossState state, Player player, long delay, String text) {
        try {
            if (TT.plugin == null) return;
            state.cutscene.dialogueTasks.add(Bukkit.getScheduler().runTaskLater(
                    TT.plugin, () -> BossFx.sendLine(player, text), delay));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void scheduleAction(BossState state, long delay, Runnable action) {
        try {
            if (TT.plugin == null) return;
            state.cutscene.dialogueTasks.add(Bukkit.getScheduler().runTaskLater(
                    TT.plugin, action, delay));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 孤儿本体清理
    // ==================================================================

    /** 糖然本体上的名字判据（原脚本 {@code isTangranStandName}）。 */
    private static boolean isTangranStand(Entity e) {
        try {
            if (!(e instanceof ArmorStand)) return false;
            String name = e.getCustomName();
            return name != null && name.contains("糖然");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个本体是不是「某一局正在用的」（别人的局不能碰）。 */
    private static boolean usedByAnyFight(Entity e) {
        return BossFight.byEntity(e) != null;
    }

    /** 带本玩家 owner 标记的本体（先扫附近 64 格，再扫全服）。 */
    private static ArmorStand findStandByOwner(Player player) {
        try {
            String uuid = player.getUniqueId().toString();
            for (Entity e : player.getWorld().getNearbyEntities(
                    player.getLocation(), 64, 64, 64)) {
                if (!(e instanceof ArmorStand stand)) continue;
                String owner = stand.getPersistentDataContainer()
                        .get(OWNER_KEY, PersistentDataType.STRING);
                if (uuid.equals(owner) && stand.isValid()) return stand;
            }
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                    String owner = e.getPersistentDataContainer()
                            .get(OWNER_KEY, PersistentDataType.STRING);
                    if (uuid.equals(owner) && e.isValid()) return (ArmorStand) e;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 附近 64 格内「叫糖然但没有 owner 标记」的本体。 */
    private static ArmorStand findNearbyUnmarkedStand(Player player) {
        try {
            for (Entity e : player.getWorld().getNearbyEntities(
                    player.getLocation(), 64, 64, 64)) {
                if (!isTangranStand(e)) continue;
                if (usedByAnyFight(e)) continue;      // 别人的局：不碰
                String owner = e.getPersistentDataContainer()
                        .get(OWNER_KEY, PersistentDataType.STRING);
                if (owner == null || owner.isEmpty()) return (ArmorStand) e;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 全服「叫糖然但没有 owner 标记」的本体。 */
    private static ArmorStand findAnyUnmarkedStand() {
        try {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                    if (!isTangranStand(e)) continue;
                    if (usedByAnyFight(e)) continue;  // 别人的局：不碰
                    String owner = e.getPersistentDataContainer()
                            .get(OWNER_KEY, PersistentDataType.STRING);
                    if (owner == null || owner.isEmpty()) return (ArmorStand) e;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ==================================================================
    // 玩家身上的 PDC 标记
    // ==================================================================

    /** 记下「这个玩家当前的本体是哪个」。 */
    public static void setMarkedStand(Player player, Entity stand) {
        try {
            player.getPersistentDataContainer().set(PLAYER_ENTITY_KEY, PersistentDataType.STRING,
                    stand.getUniqueId().toString());
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 读标记；实体查不到（区块未加载）时返回 null。 */
    public static Entity getMarkedStand(Player player) {
        try {
            String id = player.getPersistentDataContainer()
                    .get(PLAYER_ENTITY_KEY, PersistentDataType.STRING);
            if (id == null || id.isEmpty()) return null;
            return Bukkit.getEntity(java.util.UUID.fromString(id));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 清标记。 */
    public static boolean clearMarkedStand(Player player) {
        try {
            if (!player.getPersistentDataContainer().has(PLAYER_ENTITY_KEY,
                    PersistentDataType.STRING)) {
                return false;
            }
            player.getPersistentDataContainer().remove(PLAYER_ENTITY_KEY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 糖然锚点：决斗怪 → 跪像 → 本体（血条可见性判定用）。 */
    public static Location anchorOf(BossState state, Entity fallbackStand) {
        try {
            if (state != null) {
                if (state.tangran.duelMob != null && state.tangran.duelMob.isValid()) {
                    return state.tangran.duelMob.getLocation();
                }
                if (state.tangran.kneelStand != null && state.tangran.kneelStand.isValid()) {
                    return state.tangran.kneelStand.getLocation();
                }
            }
            if (fallbackStand != null && fallbackStand.isValid()) {
                return fallbackStand.getLocation();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ==================================================================
    // 血条
    // ==================================================================

    /**
     * 只摘「这个玩家」身上的糖然血条，没人看就整条收掉。
     *
     * <p><b>绝不 {@code removeAll} 全部血条</b> —— 多玩家并行时会把别人正在看的血条一起收掉。</p>
     */
    public static void clearBarsFor(Player player) {
        try {
            if (player == null) return;
            // getBossBars() 返回的是迭代器（且已标记待删除），先收成列表再遍历：
            // 一边遍历一边 removePlayer 可能让迭代器失效
            // getBossBars() 是 Iterator<KeyedBossBar>（不是 Iterable，for-each 用不了），
            // 而且已标记待删除。先手动收成列表再遍历 —— 一边遍历一边 removePlayer
            // 可能让迭代器失效。
            List<org.bukkit.boss.BossBar> bars = new ArrayList<>();
            java.util.Iterator<org.bukkit.boss.KeyedBossBar> bit = Bukkit.getBossBars();
            while (bit.hasNext()) {
                org.bukkit.boss.KeyedBossBar kb = bit.next();
                if (kb != null) bars.add(kb);
            }
            for (org.bukkit.boss.BossBar bar : bars) {
                if (bar == null) continue;
                String title;
                try {
                    title = String.valueOf(bar.getTitle());
                } catch (Throwable ignored) {
                    continue;
                }
                if (!title.contains("糖然")) continue;
                bar.removePlayer(player);
                if (bar.getPlayers().isEmpty()) {
                    bar.setVisible(false);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 供卸载/调试使用。 */
    public static void shutdown() {
        // 无全局状态
    }
}
