package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentBridge;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;

/**
 * 第三位护法 · 双子（原脚本 7003-7666 行）：<b>[天虚]D_RKIN（恼鬼）+ [天实]DrofSunShine（悦灵）
 * 同场，两条 BossBar 各 300 血，只吃破法弓计数器伤害，两个都倒下才算通关</b>。
 *
 * <h2>与前两位护法的三个本质差别</h2>
 * <ul>
 *   <li><b>两个本体、两条血条、两个计数器</b>（{@code slot.body} / {@code slot.body2}、
 *       {@code slot.bar} / {@code slot.bar2}、{@code slot.hpA} / {@code slot.hpB}）。
 *       先倒下的那位只退场并关掉自己那条血条，另一位继续打。</li>
 *   <li><b>本体不吃任何实体伤害</b>：血量被追踪任务钉在满血，扣血完全走计数器
 *       （破法弓的箭「擦到」就结算，不依赖命中判定/碰撞箱）。因此
 *       {@link #canBeDamaged} 恒 {@code false}，事件层对双子本体的伤害一律取消。</li>
 *   <li><b>头顶戴皮肤的隐形盔甲架</b>（恼鬼/悦灵模型不渲染装备）：{@code marker=true}
 *       取消碰撞箱，否则玩家的箭会打在无敌的展示架上，看着就像「打不动双子」。</li>
 * </ul>
 *
 * <h2>登场链路</h2>
 * <pre>
 * Katarina 退场 → arriveNext(D_RKIN) → arriveRouter → startArrival
 *   → 两句台词 + 战斗曲换 nzs.json（批次 9 前如实不播）+ √/×（choiceStage=twin_start）
 *   → √：发四件中章工具 + 太极召唤阵（双手粒子线 → 地面阴阳太极 3 秒）
 *        → spawnGuardian → GuardianManager.install → spawn（双本体 + 双血条 + 追踪 + 经营钩子）
 *   → ×：糖然嘲讽 → 收场
 * </pre>
 *
 * <h2>与经营小游戏（批次 6b）的接缝</h2>
 * <p>双子阶段的「天虚召唤虚仆 / 天实收购」是独立的一整块（原脚本 {@code xu*} 前缀 174 个函数），
 * 排在批次 6b。本类只留两个钩子方法（{@link #setXuGameHook}），未接入时双子照常能打，
 * 只是没有虚仆与交易菜单。</p>
 */
public final class BossTwin implements BossGuardian {

    // ==================================================================
    // 常量（原脚本 7006-7018 / 7035-7036 / 7677 行）
    // ==================================================================

    /** 每位双子护法的血量上限。 */
    public static final double MAX_HP = 300.0d;

    /** 太极图案半径（鱼眼离圆心 R/2 = 2 格）。 */
    private static final double TAIJI_RADIUS = 4.0d;

    /** 太极阵持续时长（刻，60 = 3 秒）。 */
    private static final int TAIJI_TICKS = 60;

    /** 小号盔甲架「头部中心」相对自身坐标的高度（正常 1.44，小号砍半）。 */
    private static final double STAND_HEAD_Y = 0.72d;

    /** 破法弓扫掠：以本体眼睛为中心搜多远的箭。 */
    private static final double ARROW_SWEEP_RADIUS = 4.0d;

    /** 破法弓扫掠：箭离眼睛多近算「擦到」。 */
    private static final double ARROW_GRAZE_RADIUS = 1.5d;

    /** 「这支箭已经结算过」的 PDC 键（同一支箭只扣一次计数器）。 */
    private static NamespacedKey twinHitKey() {
        return new NamespacedKey(TT.plugin, "twin_arrow_hit");
    }

    /** 双子登场时糖然血条（已打完 zqx125 + Katarina）。 */
    private static final double ARRIVAL_HP = 60.0d;

    /** 双子全灭时糖然血条再扣（60 → 20；决斗时 ×100 变 2000，批次 7）。 */
    private static final double DEFEAT_HP_DROP = 40.0d;

    // ==================================================================
    // 6b 接缝：经营小游戏
    // ==================================================================

    /** 经营小游戏钩子（批次 6b 注册；未注册时双子照常能打，只是没有虚仆与交易）。 */
    public interface XuGameHook {
        /** 双子登场后启动（天虚召唤虚仆 / 天实可交易）。 */
        void start(Player player, BossState state);

        /** 双子阶段结束 / 收场时停掉（撤召唤任务、清虚仆与灵化方块标记）。 */
        void stop(BossState state);

        /** 双子全灭时收掉玩家在账本买的属性加成（只在本阶段生效，记录保留）。 */
        default void clearUpgrades(Player player) {
            // 默认什么都不做
        }
    }

    private static volatile XuGameHook xuGameHook;

    /** 注册经营小游戏（批次 6b 调用；只应注册一次）。 */
    public static void setXuGameHook(XuGameHook hook) {
        xuGameHook = hook;
    }

    /** 当前经营钩子（BossKatarina 的 clearUpgrades 转发用）。 */
    public static XuGameHook xuGame() {
        return xuGameHook;
    }

    // ==================================================================
    // 实例状态
    // ==================================================================

    private final BossState state;
    private final String key;

    /** 本局槽位（双子共用天虚的槽位）。 */
    private BossState.GuardianSlot slot;

    /** 本局玩家（可能不在线）。 */
    private Player player;

    /** 是否已整对倒地（结算只做一次）。 */
    private boolean defeated;

    public BossTwin(BossState state) {
        this.state = state;
        this.key = state.key;
    }

    // ==================================================================
    // 静态入口：登场路由 / √× / 计数器伤害
    // ==================================================================

    /**
     * {@link BossPhaseTwo.NextArrival} 的路由实现里的双子分支。
     *
     * <p>由 {@code BossKatarina.arriveRouter} 在 {@code guardianId} 是
     * {@code D_RKIN} / {@code DrofSunShine} 时调用（原脚本
     * {@code defeatPhaseTwoGuardian} 的 {@code startTwinArrivalSequence}）。</p>
     */
    public static void startArrival(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            state.guardians.activeId = BossState.GID_TWIN_A;
            state.guardians.twin.defeated = false;
            // 糖然血条：已经打完 zqx125 与 Katarina → 60/100
            state.tangran.hp = ARRIVAL_HP;
            BossPhaseTwo.refreshBar(state);
            BossFx.updateBarPlayers(state, player.getLocation());
            // 双子阶段一开场就允许御剑（摘掉 Katarina 留下的禁骑标记）
            try {
                LuxianSword.clearNoSwordRide(player);
            } catch (Throwable ignored) {
                // 忽略
            }

            BossFx.sendLine(player, "§c[糖然]§b§lKatarina也败于你手，倒是小瞧你了！");
            scheduleLine(state, player, 40L, "§c[糖然]§b§l接下来我将不遗余力干掉你！");
            // 这句说完：战斗曲换成鸟之诗（nzs.json）—— 已在播就不打断重播（批次 9）
            scheduleAction(state, 80L, () -> {
                BossMusic.switchPhaseTwo(state, player, "nzs.json");
            });
            // 说完后照旧给 √ / × 选项：√ → 开始召唤特效；× → 糖然嘲讽并结束挑战
            scheduleAction(state, 100L, () -> {
                state.choiceStage = "twin_start";
                state.choiceMade = false;
                BossFightEntry.sendChoiceOptions(player);
            });
        } catch (Throwable t) {
            BossLog.warn("双子护法登场序列失败: " + t);
        }
    }

    /**
     * √：发四件中章工具 → 启动太极召唤阵
     * （原脚本 {@code handleAccept} 的 {@code twin_start} 分支）。
     */
    public static void handleAccept(BossState state, Player player) {
        try {
            BossFx.sendLine(player, "§c[糖然]§b§l那就让你看看，什么叫双子护法。");
            scheduleAction(state, 20L, () -> {
                try {
                    int n = EquipmentBridge.giveTwinTools(player);
                    if (n <= 0) {
                        BossLog.warn("双子工具发放失败（数量 0）");
                    }
                } catch (Throwable t) {
                    BossLog.warn("双子工具发放异常: " + t);
                }
            });
            scheduleAction(state, 30L, () -> startTaijiRitual(state, player));
        } catch (Throwable t) {
            BossLog.warn("双子登场接受处理失败: " + t);
        }
    }

    /** ×：糖然嘲讽 → 直接结束挑战（原脚本 {@code handleDecline} 的 {@code twin_start} 分支）。 */
    public static void handleDecline(BossState state, Player player) {
        BossFx.sendLine(player, "§c[糖然]§b§l呵，连登场的胆量都没有？菜就多练。");
        scheduleAction(state, 40L, () -> BossPhaseTwo.fail(state, player, "你选择了放弃双子护法的挑战。"));
    }

    /**
     * 破法弓箭矢命中双子（装备侧回调入口，原脚本 {@code tangranTwinArrowHit}）。
     *
     * <p>与轮询扫掠（{@link #arrowSweep}）走同一个 {@link #arrowHit} 结算，
     * PDC 标记保证同一支箭只扣一次。头顶展示架命中时先反查回真实本体。</p>
     *
     * @return true = 挑战侧已接管（装备侧不要按常规结算）
     */
    public static boolean arrowHitByEntity(Entity target, Player shooter, Entity arrow, double damage) {
        try {
            if (target == null) return false;
            BossState st = BossFight.byEntity(target);
            if (st == null) return false;
            BossState.GuardianSlot twinSlot = st.guardians.twin;
            Entity host = null;
            if (same(twinSlot.body, target)) {
                host = target;                       // 天虚本体
            } else if (same(twinSlot.body2, target)) {
                host = target;                       // 天实本体
            } else {
                host = twinSlot.displayHost(target); // 头顶展示架 → 反查本体
            }
            if (host == null) return false;
            return arrowHit(st, host, shooter, arrow, damage);
        } catch (Throwable t) {
            BossLog.warn("双子计数器伤害派发失败: " + t);
            return false;
        }
    }

    // ==================================================================
    // BossGuardian 接口
    // ==================================================================

    @Override
    public String id() {
        return BossState.GID_TWIN_A;
    }

    @Override
    public String title() {
        return "天虚";
    }

    @Override
    public double maxHp() {
        return MAX_HP;
    }

    /**
     * 太极阵收尾后装配双子（原脚本 {@code spawnTwinGuardians}）。
     *
     * <p>由 {@link GuardianManager#install} 调用；{@code target} 是阵法中心，
     * 场地信息缺失时退化为玩家位置。</p>
     */
    @Override
    public void spawn(Player player, Location target) {
        this.player = player;
        this.slot = state.guardians.twin;
        try {
            org.bukkit.World world = (state.arena.twoWorld != null)
                    ? state.arena.twoWorld : (target != null ? target.getWorld() : null);
            if (world == null) {
                BossLog.warn("双子召唤失败：没有世界");
                return;
            }
            double cx;
            double cy;
            double cz;
            double eye = TAIJI_RADIUS / 2;
            Location taiji = state.arena.taijiCenter;
            BossArena.Region region = state.arena.twoRegion;
            if (taiji != null && taiji.getWorld() != null) {
                // 阵法中心（仪式里算好并夹在场地内的位置）
                cx = taiji.getX();
                cz = taiji.getZ();
                cy = taiji.getY();
            } else if (region != null) {
                cx = region.minX() + (region.maxX() - region.minX()) / 2.0d + 0.5d;
                cy = region.minY() + 1.0d;
                cz = region.minZ() + (region.maxZ() - region.minZ()) / 2.0d + 0.5d;
            } else if (target != null) {
                cx = target.getX();
                cy = target.getY() + 1.0d;
                cz = target.getZ();
            } else {
                BossLog.warn("双子召唤失败：没有落点");
                return;
            }

            // 阴鱼眼 = 太极中心 +Z（天虚）；阳鱼眼 = 太极中心 −Z（天实）
            org.bukkit.entity.LivingEntity a = spawnUnit(world, cx, cy, cz + eye,
                    BossState.GID_TWIN_A, "天虚", true);
            org.bukkit.entity.LivingEntity b = spawnUnit(world, cx, cy, cz - eye,
                    BossState.GID_TWIN_B, "天实", false);
            if (a == null || b == null) {
                BossLog.warn("双子护法登场失败：本体生成失败");
            }
            // 头顶展示头颅的隐形盔甲架（marker：取消碰撞箱，见类注释）
            slot.displayA = spawnHeadDisplay(a, BossState.GID_TWIN_A, "天虚");
            slot.displayB = spawnHeadDisplay(b, BossState.GID_TWIN_B, "天实");

            slot.body = a;
            slot.body2 = b;
            state.guardians.activeId = BossState.GID_TWIN_A;
            slot.defeated = false;
            slot.maxHp = MAX_HP;
            slot.hp = MAX_HP;
            slot.damageable = false;     // 本体不吃任何实体伤害（只吃破法弓计数器）
            slot.shieldCount = 0;
            // 血量以计数器为准（本体血量被钉在满血，只用来显示）
            slot.hpA = MAX_HP;
            slot.hpB = MAX_HP;
            slot.downA = false;
            slot.downB = false;

            // 两条 BossBar：[天虚]D_RKIN n/300（蓝）、[天实]DrofSunShine n/300（紫）
            try {
                BossState.removeBar(slot.bar);
                slot.bar = null;
                BossState.removeBar(slot.bar2);
                slot.bar2 = null;
                org.bukkit.boss.BossBar bar1 = Bukkit.createBossBar(
                        barTitle(BossState.GID_TWIN_A, MAX_HP),
                        org.bukkit.boss.BarColor.BLUE, org.bukkit.boss.BarStyle.SOLID);
                bar1.setProgress(1.0d);
                bar1.setVisible(true);
                slot.bar = bar1;
                org.bukkit.boss.BossBar bar2 = Bukkit.createBossBar(
                        barTitle(BossState.GID_TWIN_B, MAX_HP),
                        org.bukkit.boss.BarColor.PURPLE, org.bukkit.boss.BarStyle.SOLID);
                bar2.setProgress(1.0d);
                bar2.setVisible(true);
                slot.bar2 = bar2;
            } catch (Throwable t) {
                BossLog.warn("双子血条创建失败: " + t);
            }
            BossFx.updateBarPlayers(state, new Location(world, cx, cy, cz));

            // 双子阶段：允许正常御剑（若上一场 Katarina 挂了禁骑标记，这里摘掉）
            try {
                LuxianSword.clearNoSwordRide(player);
            } catch (Throwable ignored) {
                // 忽略
            }

            startTrackingTask();
            // 双子经营小游戏（批次 6b 接入；未接入时跳过）
            XuGameHook hook = xuGame();
            if (hook != null) {
                try {
                    hook.start(player, state);
                } catch (Throwable t) {
                    BossLog.warn("双子经营启动异常: " + t);
                }
            }
            try {
                clearGroundDrops();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                world.playSound(new Location(world, cx, cy, cz), "entity.wither.spawn", 0.8f, 1.4f);
            } catch (Throwable ignored) {
                // 忽略
            }
            BossLog.warn("召唤护法：双子（天虚 D_RKIN + 天实 DrofSunShine） 各 "
                    + (int) MAX_HP + " 血");
        } catch (Throwable t) {
            BossLog.warn("双子护法登场异常: " + t);
        }
    }

    /**
     * 双子本体恒不可被近战打伤（计数器伤害走 {@link #damage} 与 {@link #arrowHit}）。
     */
    @Override
    public boolean canBeDamaged(Entity victim) {
        return false;
    }

    /**
     * 计数器伤害（OP 指令等外部路径；原脚本 {@code setTangranBossGuardianHp}）。
     *
     * <p>双子的计数器伤害正常入口是破法弓（{@link #arrowHit}）—— 每条箭只结算一次、
     * 且能指定打中哪一位。本方法只服务外部调试：天虚活着扣天虚，否则扣天实。</p>
     */
    @Override
    public boolean damage(Player damager, double amount) {
        try {
            if (slot == null || defeated) return false;
            boolean useB = !alive(slot.body) && alive(slot.body2);
            return applyCounterDamage(useB, Math.max(1, Math.round(amount)), damager);
        } catch (Throwable t) {
            BossLog.warn("双子扣血失败: " + t);
            return false;
        }
    }

    /**
     * 双子全灭：记录进度、写「阶段二通关」、压糖然血条 40 点，然后呼叫糖然下场决斗
     * （原脚本 {@code defeatPhaseTwoGuardian} 的 {@code D_RKIN} 分支 + 公共部分）。
     */
    @Override
    public void defeat(Player player) {
        if (state == null || defeated || slot == null) return;
        defeated = true;
        slot.defeated = true;
        this.player = (player != null) ? player : this.player;

        try {
            BossProgress.markGuardianDefeated(state.playerName, BossState.GID_TWIN_A);
        } catch (Throwable ignored) {
            // 忽略
        }
        // 双子全部战败 = 阶段二（护法）通关：读档菜单里「阶段 3」才会解锁
        try {
            BossProgress.markPhaseCleared(state.playerName, 2);
        } catch (Throwable ignored) {
            // 忽略
        }

        stopAll();
        clearGroundDrops();
        BossState.removeEntity(slot.body);
        slot.body = null;
        BossState.removeEntity(slot.body2);
        slot.body2 = null;
        BossState.removeEntity(slot.displayA);
        slot.displayA = null;
        BossState.removeEntity(slot.displayB);
        slot.displayB = null;
        BossState.removeBar(slot.bar);
        slot.bar = null;
        BossState.removeBar(slot.bar2);
        slot.bar2 = null;
        slot.hpA = 0.0d;
        slot.hpB = 0.0d;

        // 糖然血条：双子一次性扣 40（100 → 80 → 60 → 20）
        state.tangran.hp = Math.max(0.0d, state.tangran.hp - DEFEAT_HP_DROP);
        BossPhaseTwo.refreshBar(state);
        Location at = (this.player != null) ? this.player.getLocation() : null;
        if (at != null) BossFx.updateBarPlayers(state, at);

        try {
            Player p = this.player;
            if (p != null && p.isOnline()) {
                p.getWorld().playSound(p.getLocation(), "entity.skeleton.death", 1.0f, 0.7f);
                BossFx.sendLine(p, "§c[糖然]§b§l不！我的双子护法……");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        // 糖然亲自下场与玩家决斗（三阶段，批次 7 接入）
        scheduleAction(state, 40L,
                () -> BossPhaseTwo.startTangranDuel(state, this.player));
    }

    /** 停掉全部机制任务、移除双本体与双血条、清场（收场时必须幂等）。 */
    @Override
    public void cleanup() {
        defeated = true;
        stopAll();
        if (slot != null) {
            BossState.removeEntity(slot.body);
            slot.body = null;
            BossState.removeEntity(slot.body2);
            slot.body2 = null;
            BossState.removeEntity(slot.displayA);
            slot.displayA = null;
            BossState.removeEntity(slot.displayB);
            slot.displayB = null;
            BossState.removeBar(slot.bar);
            slot.bar = null;
            BossState.removeBar(slot.bar2);
            slot.bar2 = null;
        }
    }

    // ==================================================================
    // 太极召唤阵（原脚本 startTwinTaijiRitual / drawTaijiPattern / drawTwinSummonLines）
    // ==================================================================

    /** 双手粒子线垂到地面 → 地面阴阳太极 3 秒 → 双鱼眼现出双子。 */
    private static void startTaijiRitual(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            stopTaiji(state);
            BossArena.Region region = state.arena.twoRegion;
            org.bukkit.World world = (state.arena.twoWorld != null)
                    ? state.arena.twoWorld : player.getWorld();
            Entity stand = state.stand;
            Location base = (stand != null && stand.isValid())
                    ? stand.getLocation() : player.getLocation();
            double R = TAIJI_RADIUS;
            double cx = base.getX();
            double cz = base.getZ();
            double floorY = (region != null) ? (region.minY() + 1.08) : (base.getY() + 0.1);
            // 图案夹在场地内，别穿出屏障
            if (region != null) {
                cx = Math.max(region.minX() + R + 1.5, Math.min(region.maxX() - R - 1.5, cx));
                cz = Math.max(region.minZ() + R + 1.5, Math.min(region.maxZ() - R - 1.5, cz));
            }
            // 阵法中心记到 taijiCenter：spawn 按它落位（原脚本 state.twinTaiji）
            state.arena.taijiCenter = new Location(world, cx, floorY, cz);
            final double fcx = cx;
            final double fcz = cz;
            final double ffloor = floorY;

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            slotOf(state).taijiTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(slotOf(state).taijiTask);
                        slotOf(state).taijiTask = null;
                        return;
                    }
                    tick[0]++;
                    // 1) 糖然左右手的两条粒子线（前 1.2 秒伸出，之后保留一截）
                    if (tick[0] <= 24) drawSummonLines(state, player, tick[0]);
                    // 2) 地面阴阳太极图案（每刻都在重画）
                    drawTaijiPattern(world, fcx, ffloor, fcz, R);
                    // 3) 从圆心往上收束的灵气
                    for (int a = 0; a < 8; a++) {
                        double ang = a * Math.PI / 4 + tick[0] * 0.05;
                        double rr = R * (0.25 + 0.12 * (a % 3));
                        double py = ffloor + 0.3 + Math.sin(tick[0] * 0.12 + a) * 0.5;
                        BossFx.dust(world, fcx + Math.cos(ang) * rr, py, fcz + Math.sin(ang) * rr,
                                (a % 2 == 0) ? Color.BLACK : Color.WHITE, 1.35f);
                    }
                    if (tick[0] >= TAIJI_TICKS) {
                        BossState.cancelTask(slotOf(state).taijiTask);
                        slotOf(state).taijiTask = null;
                        spawnGuardian(state, player);
                    }
                } catch (Throwable t) {
                    BossLog.warn("双子召唤阵异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("双子召唤阵启动失败: " + t);
        }
    }

    /** 停太极阵任务。 */
    private static void stopTaiji(BossState state) {
        BossState.GuardianSlot slot = slotOf(state);
        BossState.cancelTask(slot.taijiTask);
        slot.taijiTask = null;
    }

    /** 太极阴阳判定（isBlack=true → 阴鱼/黑）。 */
    private static boolean taijiIsBlack(double u, double v, double R) {
        double sr = R / 2;
        if (v > 0) {
            double dx1 = u;
            double dz1 = v - sr;
            return (dx1 * dx1 + dz1 * dz1) <= sr * sr;
        }
        double dx2 = u;
        double dz2 = v + sr;
        return (dx2 * dx2 + dz2 * dz2) > sr * sr;
    }

    /** 在地面画出阴阳太极：填色 + 外圆 + S 分界 + 两个鱼眼。 */
    private static void drawTaijiPattern(org.bukkit.World world, double cx, double y, double cz, double R) {
        // 1) 填色（极坐标网格，按阴阳判定选颜色）
        for (double rr = 0.6; rr <= R; rr += 0.7) {
            for (double a = 0; a < Math.PI * 2; a += 0.42) {
                double u = Math.cos(a) * rr;
                double v = Math.sin(a) * rr;
                BossFx.dust(world, cx + u, y, cz + v,
                        taijiIsBlack(u, v, R) ? Color.BLACK : Color.WHITE, 1.35f);
            }
        }
        // 2) 外圆
        int steps = 90;
        for (int i = 0; i < steps; i++) {
            double ang = i * Math.PI * 2 / steps;
            double ox = Math.cos(ang) * R;
            double ov = Math.sin(ang) * R;
            BossFx.dust(world, cx + ox, y, cz + ov,
                    taijiIsBlack(ox, ov, R) ? Color.BLACK : Color.WHITE, 1.35f);
        }
        // 3) S 分界线
        double sr = R / 2;
        for (int s = 0; s <= steps; s++) {
            double t = Math.PI * (s / (double) steps);
            BossFx.dust(world, cx - Math.sin(t) * sr, y, cz + sr + Math.cos(t) * sr,
                    Color.BLACK, 1.35f);
            BossFx.dust(world, cx + Math.sin(t) * sr, y, cz - sr - Math.cos(t) * sr,
                    Color.WHITE, 1.35f);
        }
        // 4) 两个鱼眼：阴鱼眼（+Z，黑鱼头里的白点）/ 阳鱼眼（−Z，白鱼头里的黑点）
        for (int k = 0; k < 2; k++) {
            double sign = (k == 0) ? 1 : -1;
            double ex = cx;
            double ez = cz + sign * sr;
            Color eyeDust = (sign > 0) ? Color.WHITE : Color.BLACK;
            double eyeR = R / 6;
            for (int q = 0; q < 12; q++) {
                double qa = q * Math.PI * 2 / 12;
                BossFx.dust(world, ex + Math.cos(qa) * eyeR, y, ez + Math.sin(qa) * eyeR,
                        eyeDust, 1.35f);
            }
            try {
                world.spawnParticle(org.bukkit.Particle.END_ROD, ex, y + 0.2, ez, 3, 0.1, 0.05, 0.1, 0.0);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 糖然左右手各一条粒子线垂到地面：左手黑（阴/天虚）、右手白（阳/天实）。 */
    private static void drawSummonLines(BossState state, Player player, int tick) {
        try {
            Entity stand = state.stand;
            if (stand == null || !stand.isValid()) return;
            org.bukkit.World world = stand.getWorld();
            Location loc = stand.getLocation();
            double yaw = loc.getYaw() * Math.PI / 180.0d;
            double rx = Math.cos(yaw);
            double rz = Math.sin(yaw);
            double side = 0.55;
            double hy = loc.getY() + 1.15;                 // 手的高度（假人约 1.9 高）
            double lx = loc.getX() - rx * side;
            double lz = loc.getZ() - rz * side;
            double gx = loc.getX() + rx * side;
            double gz = loc.getZ() + rz * side;
            BossArena.Region region = state.arena.twoRegion;
            double floorY = (region != null) ? (region.minY() + 1.05) : (loc.getY() - 0.2);
            double grow = Math.min(1.0d, tick / 14.0d);    // 约 0.7 秒延伸到底
            double bottom = hy - (hy - floorY) * grow;
            for (double yy = hy; yy >= bottom; yy -= 0.2) {
                BossFx.dust(world, lx, yy, lz, Color.BLACK, 1.35f);
                BossFx.dust(world, gx, yy, gz, Color.WHITE, 1.35f);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 阵法收尾：装配双子（原脚本 {@code spawnTwinGuardians} 的入口部分）。
     *
     * <p>{@code activeId} 必须在 install 之前落到 {@code D_RKIN}
     * （{@link GuardianManager#install} 按它选工厂）。</p>
     */
    private static void spawnGuardian(BossState state, Player player) {
        state.guardians.activeId = BossState.GID_TWIN_A;
        Location target = state.arena.taijiCenter;
        if (target == null) target = state.arena.twoCenter;
        if (target == null && state.stand != null && state.stand.isValid()) {
            target = state.stand.getLocation();
        }
        if (target == null) target = player.getLocation();
        GuardianManager.install(state, player, target);
    }

    // ==================================================================
    // 本体与展示架（原脚本 spawnTwinUnit / spawnTwinHeadDisplay / faceTwinToPlayer）
    // ==================================================================

    /** 单个双子本体：天虚 = 恼鬼 / 天实 = 悦灵（AI 关 + 无敌，血量钉 300 供显示）。 */
    private static org.bukkit.entity.LivingEntity spawnUnit(org.bukkit.World world, double x, double y, double z,
                                                            String id, String title, boolean vex) {
        try {
            org.bukkit.entity.LivingEntity boss = vex
                    ? world.spawn(new Location(world, x, y, z), Vex.class)
                    : world.spawn(new Location(world, x, y, z), Allay.class);
            boss.setCustomName("§c§l[" + title + "]§e§l" + id);
            boss.setCustomNameVisible(true);
            boss.setPersistent(true);
            boss.setRemoveWhenFarAway(false);
            boss.setCanPickupItems(false);
            if (boss instanceof org.bukkit.entity.Mob mob) {
                mob.setAware(false);       // 召唤期：AI 关掉
            }
            boss.setInvulnerable(true);    // 无敌状态（只吃计数器伤害）
            boss.setGravity(false);
            boss.setCollidable(false);
            boss.setSilent(true);
            try {
                var maxHp = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (maxHp != null) {
                    maxHp.setBaseValue(MAX_HP);
                    boss.setHealth(MAX_HP);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 恼鬼：取消「限时存在」（原版唤魔者召唤的恼鬼会到点消失）
            try {
                if (boss instanceof Vex v) {
                    v.setLimitedLifetime(false);
                    v.setLifeTicks(20 * 60 * 60);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return boss;
        } catch (Throwable t) {
            BossLog.warn("双子本体生成失败（" + id + "）：" + t);
            return null;
        }
    }

    /** 隐形盔甲架：戴护法皮肤头颅，跟着本体悬浮。 */
    private static Entity spawnHeadDisplay(org.bukkit.entity.LivingEntity mob, String id, String title) {
        try {
            if (mob == null || !mob.isValid()) return null;
            Location loc = mob.getLocation().clone();
            loc.setY(displayY(mob));
            ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class);
            stand.setInvisible(true);      // 隐形但仍渲染装备
            stand.setSmall(true);
            // ★ Marker：取消盔甲架的碰撞箱。否则玩家的箭/近战会打在「头顶展示头颅」上
            //   （它是无敌的），看着就像「打不动双子」。装备渲染不受影响。
            stand.setMarker(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCollidable(false);
            stand.setPersistent(true);
            stand.setCustomName("§c§l[" + title + "]" + id + " 头颅");
            stand.setCustomNameVisible(false);
            try {
                var eq = stand.getEquipment();
                if (eq != null) {
                    eq.setHelmet(BossGuardianSkin.head(id, null, null));
                    eq.setHelmetDropChance(0.0f);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return stand;
        } catch (Throwable t) {
            BossLog.warn("双子头颅展示生成失败（" + id + "）：" + t);
            return null;
        }
    }

    /** 展示头颅的 Y：以小号盔甲架的头部中心对齐本体的眼睛高度。 */
    private static double displayY(org.bukkit.entity.LivingEntity mob) {
        try {
            return mob.getEyeLocation().getY() - STAND_HEAD_Y + HEAD_ADJ;
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            return mob.getLocation().getY() + 0.05 + HEAD_ADJ;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 头颅微调（原脚本 {@code headAdj = -0.08}，负数 = 往下压）。 */
    private static final double HEAD_ADJ = -0.08d;

    /** 本体 + 展示架始终看向玩家（本体转身体，盔甲架转身体 + 头部俯仰）。 */
    private static void faceTwinToPlayer(Player player, org.bukkit.entity.LivingEntity mob, Entity stand) {
        try {
            if (mob == null || !mob.isValid()) return;
            Location eLoc = mob.getLocation();
            Location eye = player.getEyeLocation();
            double dx = eye.getX() - eLoc.getX();
            double dy = eye.getY() - eLoc.getY();
            double dz = eye.getZ() - eLoc.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yawF = (float) (Math.atan2(-dx, dz) * 180.0d / Math.PI);
            float pitchF = (float) (-Math.atan2(dy, horiz) * 180.0d / Math.PI);
            mob.setRotation(yawF, pitchF);
            if (stand == null || !stand.isValid()) return;
            // 头颅与本体头部重合：每刻按本体的眼睛高度重算 Y
            Location tgt = new Location(stand.getWorld(), eLoc.getX(), displayY(mob),
                    eLoc.getZ(), yawF, 0);
            boolean needMove = true;
            try {
                Location sl = stand.getLocation();
                needMove = (sl.distanceSquared(tgt) > 0.0009)
                        || (Math.abs(sl.getYaw() - yawF) > 1.5);
            } catch (Throwable ignored) {
                needMove = true;
            }
            if (needMove) {
                stand.teleport(tgt);
            }
            // 盔甲的俯仰走头部姿态（传送的 pitch 不会带动头盔）
            if (stand instanceof ArmorStand as) {
                as.setHeadPose(new EulerAngle(pitchF * Math.PI / 180.0d, 0, 0));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 追踪任务（原脚本 startTwinTrackingTask / xuRefreshTwinBars / xuTwinArrowSweep）
    // ==================================================================

    /**
     * 双子阶段守护：朝向/头颅每 2 拍一次、血量钉满血、血条只在变化时发包、
     * 破法弓的箭靠近即结算、每 20 拍锁定目标。
     */
    private void startTrackingTask() {
        try {
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            slot.trackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated) {
                        BossState.cancelTask(slot.trackTask);
                        slot.trackTask = null;
                        return;
                    }
                    org.bukkit.entity.LivingEntity a = living(slot.body);
                    org.bukkit.entity.LivingEntity b = living(slot.body2);
                    if (!alive(a) && !alive(b)) {
                        BossState.cancelTask(slot.trackTask);
                        slot.trackTask = null;
                        return;
                    }
                    Player p = (player != null && player.isOnline())
                            ? player : Bukkit.getPlayerExact(state.playerName);
                    tick[0]++;
                    // 朝向/展示头颅每 2 拍做一次（原脚本的性能注释：包量很大）
                    if (tick[0] % 2 == 0) {
                        if (alive(a)) {
                            faceTwinToPlayer(p, a, slot.displayA);
                        } else {
                            BossState.removeEntity(slot.displayA);
                            slot.displayA = null;
                        }
                        if (alive(b)) {
                            faceTwinToPlayer(p, b, slot.displayB);
                        } else {
                            BossState.removeEntity(slot.displayB);
                            slot.displayB = null;
                        }
                    }
                    // 本体不吃实体伤害：血量钉死在满血，扣血完全走计数器
                    if (alive(a)) {
                        pinHealth(a);
                    }
                    if (alive(b)) {
                        pinHealth(b);
                    }
                    refreshBars();
                    // 破法弓的箭靠近双子即结算（不依赖命中判定/碰撞箱）
                    arrowSweep();
                    if (tick[0] % 20 == 0 && p != null && p.isOnline() && !p.isDead()) {
                        // AI 是关着的，setTarget 只是保持口径与原脚本一致（无害）
                        if (alive(a) && a instanceof org.bukkit.entity.Mob ma) {
                            ma.setTarget(p);
                        }
                        if (alive(b) && b instanceof org.bukkit.entity.Mob mb) {
                            mb.setTarget(p);
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("双子护法追踪任务启动失败: " + t);
        }
    }

    /** 本体血量钉回满血（显示用）。 */
    private static void pinHealth(Entity mob) {
        try {
            if (mob instanceof org.bukkit.entity.LivingEntity le) {
                if (le.getHealth() < le.getMaxHealth()) {
                    le.setHealth(le.getMaxHealth());
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 血条刷新：一切以计数器 hpA / hpB 为准（原脚本 {@code xuRefreshTwinBars}）。 */
    private void refreshBars() {
        try {
            if (alive(slot.body)) {
                int hpA = (int) Math.max(0, Math.round(slot.hpA));
                if (hpA != slot.lastBarA) {
                    slot.lastBarA = hpA;
                    slot.hp = hpA;
                    if (slot.bar != null) {
                        slot.bar.setTitle(barTitle(BossState.GID_TWIN_A, hpA));
                        slot.bar.setProgress(Math.max(0.0d, Math.min(1.0d, hpA / MAX_HP)));
                    }
                }
            }
            if (alive(slot.body2)) {
                int hpB = (int) Math.max(0, Math.round(slot.hpB));
                if (hpB != slot.lastBarB) {
                    slot.lastBarB = hpB;
                    if (slot.bar2 != null) {
                        slot.bar2.setTitle(barTitle(BossState.GID_TWIN_B, hpB));
                        slot.bar2.setProgress(Math.max(0.0d, Math.min(1.0d, hpB / MAX_HP)));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 破法弓的箭：每拍扫一次双子周围，擦到就结算
     * （原脚本 {@code xuTwinArrowSweep}）。
     */
    private void arrowSweep() {
        try {
            org.bukkit.entity.LivingEntity[] list = {living(slot.body), living(slot.body2)};
            for (org.bukkit.entity.LivingEntity t : list) {
                if (!alive(t)) continue;
                Entity found = null;
                Entity foreign = null;   // 玩家的箭、但不认成破法弓 → 记一条限流诊断
                try {
                    Location eye = t.getEyeLocation();
                    for (Entity e : t.getWorld().getNearbyEntities(eye, ARROW_SWEEP_RADIUS,
                            ARROW_SWEEP_RADIUS, ARROW_SWEEP_RADIUS)) {
                        String tn;
                        try {
                            tn = e.getType().name();
                        } catch (Throwable e2) {
                            continue;
                        }
                        if (!"ARROW".equals(tn) && !"SPECTRAL_ARROW".equals(tn)) continue;
                        if (!graze(e, eye, ARROW_GRAZE_RADIUS)) continue;
                        if (EquipmentBridge.isPofabowArrow(e)) {
                            found = e;
                            break;
                        }
                        if (foreign == null) {
                            try {
                                if (e instanceof org.bukkit.entity.AbstractArrow aa
                                        && aa.getShooter() instanceof Player) {
                                    foreign = e;
                                }
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    found = null;
                }
                // 玩家的箭擦过双子、却没被认成破法弓：限流诊断（「打不动」时一眼看出卡在哪）
                if (foreign != null) {
                    logForeignArrowDiagnostic(state, foreign);
                }
                if (found == null) continue;
                if (!(found instanceof org.bukkit.entity.AbstractArrow arrow)) continue;
                if (!(arrow.getShooter() instanceof Player shooter)) continue;
                double dmg = EquipmentBridge.pfgTwinArrowDamage(found, shooter);
                arrowHit(state, t, shooter, found, dmg);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 这支箭这一刻有没有「擦到」目标（上一刻→这一刻的线段最短距离，防高速箭一步跨过）。 */
    private static boolean graze(Entity arrow, Location eyeLoc, double r) {
        try {
            Location a = arrow.getLocation();
            double vx = 0, vy = 0, vz = 0;
            try {
                var v = arrow.getVelocity();
                vx = v.getX();
                vy = v.getY();
                vz = v.getZ();
            } catch (Throwable ignored) {
                // 忽略
            }
            double px = a.getX() - vx, py = a.getY() - vy, pz = a.getZ() - vz;
            double dx = a.getX() - px, dy = a.getY() - py, dz = a.getZ() - pz;
            double len2 = dx * dx + dy * dy + dz * dz;
            double cx = eyeLoc.getX(), cy = eyeLoc.getY(), cz = eyeLoc.getZ();
            double t = 0;
            if (len2 > 0.000001d) {
                t = ((cx - px) * dx + (cy - py) * dy + (cz - pz) * dz) / len2;
                if (t < 0) t = 0;
                else if (t > 1) t = 1;
            }
            double qx = px + dx * t, qy = py + dy * t, qz = pz + dz * t;
            double d2 = (cx - qx) * (cx - qx) + (cy - qy) * (cy - qy) + (cz - qz) * (cz - qz);
            return d2 <= (r * r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 破法弓命中结算：删箭 + 扣计数器；清零则该位倒地，两条都空 → 整对退场
     * （原脚本 {@code tangranTwinArrowHit}）。
     */
    private static boolean arrowHit(BossState state, Entity twin, Player player,
                                    Entity arrow, double dmg) {
        try {
            if (state == null || twin == null) return false;
            // 认箭：不是破法弓射的就不管
            if (!EquipmentBridge.isPofabowArrow(arrow)) return false;
            BossState.GuardianSlot twinSlot = state.guardians.twin;
            boolean isB = same(twinSlot.body2, twin);
            boolean isA = !isB && same(twinSlot.body, twin);
            if (!isA && !isB) return false;

            // 同一支箭只结算一次（命中事件与轮询都可能想结算，PDC 打标记）
            NamespacedKey hitKey = twinHitKey();
            boolean done = false;
            try {
                done = arrow.getPersistentDataContainer().has(hitKey, PersistentDataType.STRING);
            } catch (Throwable ignored) {
                done = false;
            }
            if (done) {
                BossState.removeEntity(arrow);
                return true;
            }
            try {
                arrow.getPersistentDataContainer().set(hitKey, PersistentDataType.STRING, "1");
            } catch (Throwable ignored) {
                // 忽略
            }
            // 箭一定删掉：双子本体不吃实体伤害
            BossState.removeEntity(arrow);

            double before = isB ? twinSlot.hpB : twinSlot.hpA;
            if (before <= 0) return true;
            double d = Math.max(1, Math.round(dmg));
            double after = Math.max(0, before - d);
            if (isB) {
                twinSlot.hpB = after;
                twinSlot.lastBarB = -1;
            } else {
                twinSlot.hpA = after;
                twinSlot.lastBarA = -1;
            }

            // 日志：每场第一次命中 + 打空倒地各记一条（确认这条链路真的走通了）
            if (before >= MAX_HP || after <= 0) {
                BossLog.warn("双子破法弓命中：" + (isB ? BossState.GID_TWIN_B : BossState.GID_TWIN_A)
                        + " -" + (int) d + " → " + (int) after + "/" + (int) MAX_HP
                        + (after <= 0 ? "（倒地）" : ""));
            }
            try {
                Location loc = twin.getLocation();
                loc.getWorld().spawnParticle(org.bukkit.Particle.CRIT,
                        loc.getX(), loc.getY() + 0.8, loc.getZ(), 8, 0.25, 0.3, 0.25, 0.06);
                loc.getWorld().playSound(loc, "entity.arrow.hit_player", 0.9f, 1.3f);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (after <= 0) {
                // 计数器清零：本体倒地（真实死亡与计数器两条路都走 handleDown，down 标记防重复）
                BossState.removeEntity(twin);
                handleDown(state, isB, player);
            }
            return true;
        } catch (Throwable t) {
            BossLog.warn("双子破法弓命中结算异常: " + t);
            return false;
        }
    }

    /**
     * 单个双子倒地结算（原脚本 {@code xuHandleTwinDown}）。
     *
     * <p>{@code downA} / {@code downB} 保证只结算一次；另一位还活着就继续打，
     * 两条都空 → {@link #defeat}。</p>
     */
    private static void handleDown(BossState state, boolean isB, Player player) {
        try {
            if (state == null) return;
            BossState.GuardianSlot twinSlot = state.guardians.twin;
            Player owner = (player != null) ? player : Bukkit.getPlayerExact(state.playerName);

            if (isB) {
                if (twinSlot.downB) return;
                twinSlot.downB = true;
                twinSlot.hpB = 0.0d;
                twinSlot.lastBarB = 0;
                BossState.removeEntity(twinSlot.body2);
                twinSlot.body2 = null;
                BossState.removeBar(twinSlot.bar2);
                twinSlot.bar2 = null;
                if (!alive(twinSlot.body)) {
                    // 静态审查修正：召唤者离线也要结算（defeat 实现对 null player 容错，
                    // 各自回落 state.playerName）——否则两个计数器都空了战局却悬空
                    GuardianManager.defeat(state, owner);
                }
            } else {
                if (twinSlot.downA) return;
                twinSlot.downA = true;
                twinSlot.hpA = 0.0d;
                twinSlot.lastBarA = 0;
                BossState.removeEntity(twinSlot.body);
                twinSlot.body = null;
                BossState.removeBar(twinSlot.bar);
                twinSlot.bar = null;
                if (!alive(twinSlot.body2)) {
                    // 静态审查修正：同上，离线也结算
                    GuardianManager.defeat(state, owner);
                }
            }
        } catch (Throwable t) {
            BossLog.warn("双子倒地结算异常: " + t);
        }
    }

    /** 计数器伤害（OP 指令路径；正常入口是破法弓 {@link #arrowHit}）。 */
    private boolean applyCounterDamage(boolean useB, double amount, Player damager) {
        double before = useB ? slot.hpB : slot.hpA;
        if (before <= 0) return false;
        double after = Math.max(0, before - amount);
        if (useB) {
            slot.hpB = after;
            slot.lastBarB = -1;
        } else {
            slot.hpA = after;
            slot.lastBarA = -1;
        }
        if (after <= 0) {
            handleDown(state, useB, damager != null ? damager : player);
        }
        return true;
    }

    // ==================================================================
    // 收尾 / 杂项
    // ==================================================================

    /** 停全部双子任务（追踪 + 太极阵 + 经营钩子）。 */
    private void stopAll() {
        BossState.cancelTask(slot.trackTask);
        slot.trackTask = null;
        BossState.cancelTask(slot.taijiTask);
        slot.taijiTask = null;
        XuGameHook hook = xuGame();
        if (hook != null) {
            try {
                hook.stop(state);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 清掉场上全部本局掉落物（原脚本 {@code clearPhaseTwoGroundDrops} 的双子口径）。 */
    private void clearGroundDrops() {
        try {
            for (org.bukkit.entity.Item item : state.phaseTwo.drops) {
                BossState.removeEntity(item);
            }
            state.phaseTwo.drops.clear();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 血条标题：{@code [天虚]D_RKIN [n/300]} / {@code [天实]DrofSunShine [n/300]}。 */
    private static String barTitle(String guardianId, double hp) {
        String title = BossState.GID_TWIN_B.equalsIgnoreCase(guardianId) ? "天实" : "天虚";
        int n = (int) Math.max(0, Math.min(MAX_HP, Math.round(hp)));
        return "§c§l[" + title + "]§e§l" + guardianId + " §f[§d" + n + "§f/§a"
                + (int) MAX_HP + "§f]";
    }

    /** 「玩家的箭擦过但没认成破法弓」的限流诊断（5 秒一条）。 */
    private static void logForeignArrowDiagnostic(BossState state, Entity foreign) {
        try {
            long now = System.currentTimeMillis();
            Long last = FOREIGN_ARROW_LOG.get(state.key);
            if (last != null && now - last < 5000L) return;
            FOREIGN_ARROW_LOG.put(state.key, now);
            String held = "null";
            try {
                if (foreign instanceof org.bukkit.entity.AbstractArrow aa
                        && aa.getShooter() instanceof Player sh) {
                    org.bukkit.inventory.ItemStack it = sh.getInventory().getItemInMainHand();
                    held = (it == null) ? "null" : it.getType().name();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            BossLog.warn("双子破法弓诊断：玩家的箭擦过双子但没认成破法弓（手持=" + held + "）");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 诊断限流表（key → 上次记录时间）。 */
    private static final java.util.Map<String, Long> FOREIGN_ARROW_LOG =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean alive(Entity e) {
        try {
            return e != null && e.isValid() && !e.isDead();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Entity → LivingEntity 的窄化（不是生物就 null）。 */
    private static org.bukkit.entity.LivingEntity living(Entity e) {
        try {
            return (e instanceof org.bukkit.entity.LivingEntity le) ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean same(Entity a, Entity b) {
        if (a == null || b == null) return false;
        try {
            return a.equals(b);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个战局还是「当前局」吗。 */
    private boolean isMine() {
        return BossFight.byKey(key) == state;
    }

    /** 按战局取双子槽位（静态方法里用）。 */
    private static BossState.GuardianSlot slotOf(BossState state) {
        return state.guardians.twin;
    }

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
