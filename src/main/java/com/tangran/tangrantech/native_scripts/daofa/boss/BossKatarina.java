package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;

/**
 * 第二位护法 Katarina（原脚本 5004-6083 行）：<b>蓝色登场阵法 → 凋灵骷髅本体 600 血 →
 * 三面环绕盾（限伤 1）→ 火球弹反破盾 → 剑气风暴 → 加强甘露</b>。
 *
 * <h2>与 zqx125 的本质差别：本体是「真生物」</h2>
 * <p>zqx125 的本体是演出道具（无敌、AI 全关、血量只在 BossBar 上，一切走计数器）；
 * Katarina 的本体是<b>保留 AI 的凋灵骷髅</b>，会自己追击/攻击玩家，600 血就是 BossBar 的血，
 * 玩家拿戮仙剑可以真实砍她。因此：</p>
 * <ul>
 *   <li>{@link #canBeDamaged} 对本体答 {@code true}（装备侧的「撤销伤害取消」据此放行，
 *       这就是「打不动 Katarina」那个历史 bug 的修复点）；</li>
 *   <li>掉血有<b>两条路</b>：原版真实伤害（近战，事件层在盾存在时压到 1 点）与
 *       计数器伤害（弹反火球 / OP 指令，走 {@link #damage}）；</li>
 *   <li><b>死亡检测必须自己盯</b>：原脚本只给双子接了 {@code EntityDeathEvent}，
 *       Katarina 被真实伤害打死时没有任何结算（潜藏 bug：场面会卡在一具尸体和一个 80/100 的血条）。
 *       本实现由追踪任务发现本体失效即走 {@link #defeat}（见 {@link #startTrackingTask} 的偏差说明）。</li>
 * </ul>
 *
 * <h2>状态机（原脚本 {@code kataMode}）</h2>
 * <pre>
 *   shields ──3 面盾全破(弹反)──▶ stun(5s,AI 关,受伤×2)
 *      ▲                              │
 *      │                              ▼
 *   summon(3s,AI 关,重刷盾) ◀── normal(5s,AI 开)
 * </pre>
 * <p>每完整破一轮盾 {@code cycle++}：火球每轮 +2 发（上限 10）、速度 +0.1；
 * 剑气风暴每轮 +1 道、速度 +0.08、间隔 −2 秒（下限 5 秒）。</p>
 *
 * <h2>本类不注册任何监听器</h2>
 * <p>挥砍弹反（{@code PlayerArmSwingEvent}）与两条限伤规则由 {@link BossBattleScript} 的
 * {@code KatarinaListener} 持有（事件注册统一在门面），届时批次 8 合并事件层时不要重复注册。</p>
 */
public final class BossKatarina implements BossGuardian {

    // ==================================================================
    // 常量（原脚本 5147-5166 / 2847-2852 行）
    // ==================================================================

    /** Katarina 血量上限（本体血量就是 BossBar 血量）。 */
    public static final double MAX_HP = 600.0d;

    /** 环绕盾面数。 */
    private static final int SHIELD_COUNT = 3;

    /** 盾牌环绕半径（格）。 */
    private static final double SHIELD_RADIUS = 1.7d;

    /** 盾牌相对本体的高度。 */
    private static final double SHIELD_Y = 1.3d;

    /** 三面盾全破时的巨额伤害。 */
    private static final int BREAK_DAMAGE = 50;

    /** 虚弱（破盾硬直）时长（刻）。 */
    private static final int STUN_TICKS = 100;

    /** 硬直后恢复正常时长（刻）。 */
    private static final int NORMAL_TICKS = 100;

    /** 恢复正常后召唤新盾的静止时长（刻）。 */
    private static final int SUMMON_TICKS = 60;

    /** 火球开阵间隔（刻；整轮打完重新计时）。 */
    private static final int FIRE_INTERVAL = 240;

    /** 阵眼施法特效时长（刻）。 */
    private static final int FIRE_CHARGE = 80;

    /** 一轮内两发火球之间的间隔（刻）。 */
    private static final int FIRE_GAP = 20;

    /** 火球砸到玩家的伤害。 */
    private static final int FIRE_DAMAGE = 2;

    /** 弹反命中「常态」Katarina 的伤害。 */
    private static final int FIRE_HIT_NORMAL = 5;

    /** 弹反命中「虚弱中」Katarina 的伤害。 */
    private static final int FIRE_HIT_STUN = 10;

    /** 剑气风暴间隔（刻；每破一轮盾 −40）。 */
    private static final int STORM_INTERVAL = 300;

    /** 剑气风暴伤害。 */
    private static final int STORM_DAMAGE = 1;

    /** 加强甘露存在时长（刻，20 秒）。 */
    private static final int NECTAR_LIFETIME = 400;

    /** 甘露被捡走后下一瓶的延迟（刻，10 秒）。 */
    private static final int NECTAR_DELAY = 200;

    /** 玩家增益时长（刻，3 分钟，每 100 刻刷新）。 */
    private static final int BUFF_TICKS = 20 * 60 * 3;

    // ---- 登场阵法（原脚本 2847-2852） ----

    /** 阵法半径（糖然头顶）。 */
    private static final double RITUAL_RADIUS = 5.0d;

    /** 圆框阶段时长（刻）。 */
    private static final int CIRCLE_TICKS = 40;

    /** 五角星阶段时长（刻）。 */
    private static final int STAR_TICKS = 60;

    /** 光柱阶段时长（刻）。 */
    private static final int BEAM_TICKS = 60;

    /** 糖然头顶到阵法中心的偏移。 */
    private static final double RITUAL_TOP_DY = 2.6d;

    // ---- 弹反判定（原脚本 5423-5482） ----

    /** 挥砍弹反的最大距离平方（3.6 格内才弹得到）。 */
    private static final double DEFLECT_RANGE_SQ = 13.0d;

    /** 视线与「眼→火球」方向的最小点积（必须「盯准它挥砍」）。 */
    private static final double DEFLECT_DOT = 0.35d;

    /** 弹反后火球的速度。 */
    private static final double DEFLECT_SPEED = 1.4d;

    /** 火球最长存活（刻）。 */
    private static final int FIREBALL_MAX_AGE = 200;

    /** 火球消失后的补判前瞻（格）。 */
    private static final double FIREBALL_LEAD = 2.2d;

    /** 弹反火球打中本体的水平半径平方。 */
    private static final double FIREBALL_HIT_BODY_SQ = 5.3d;

    /** 弹反火球打中本体的高度容差。 */
    private static final double FIREBALL_HIT_BODY_DY = 1.8d;

    /** 弹反火球扫掠判定的半径平方。 */
    private static final double FIREBALL_SWEEP_SQ = 7.0d;

    /** 火球消失补判打中本体的半径平方。 */
    private static final double FIREBALL_GHOST_BODY_SQ = 7.5d;

    /** 火球消失补判打中玩家的半径平方。 */
    private static final double FIREBALL_GHOST_PLAYER_SQ = 4.0d;

    /** 火球撞玩家的距离平方（活着时）。 */
    private static final double FIREBALL_HIT_PLAYER_SQ = 2.0d;

    // ---- 剑气风暴（原脚本 5634-5770） ----

    /** 剑气风暴间隔下限（刻，5 秒）。 */
    private static final int STORM_INTERVAL_MIN = 100;

    /** 每波剑气数量上限。 */
    private static final int STORM_MAX_COUNT = 6;

    /** 剑气速度上限。 */
    private static final double STORM_SPEED_MAX = 1.2d;

    /** 剑气撞到玩家的水平半径平方（0.75 格内）。 */
    private static final double STORM_HIT_SQ = 0.56d;

    /** 剑气粒子上沿（高于玩家脚 +1.75 视为跳起来躲开了）。 */
    private static final double STORM_TOP = 1.75d;

    /** 剑气粒子下沿（低于玩家脚 −0.25 不算碰到）。 */
    private static final double STORM_BOTTOM = -0.25d;

    // ==================================================================
    // 实例状态（每位护法每局一份，见 BossGuardian 的注释）
    // ==================================================================

    private final BossState state;
    private final String key;

    /** 本局槽位（Katarina 专用）。 */
    private BossState.GuardianSlot slot;

    /** 本局玩家（可能不在线）。 */
    private Player player;

    /** 机制主循环任务（原脚本 {@code kataMainTask}）。 */
    private BukkitTask kitTask;

    /** 阶段守护任务（原脚本 {@code phaseTwoKatarinaTrackTask}）。 */
    private BukkitTask trackTask;

    /** 状态机：{@code shields} / {@code stun} / {@code normal} / {@code summon}。 */
    private String mode = "shields";

    /** 当前状态已持续拍数。 */
    private int modeTick;

    /** 完整破盾轮数（火球数量/风暴强度都按它升）。 */
    private int cycle;

    /** 三面环绕盾（展示实体；顺序即被击破的顺序，pop 从尾部）。 */
    private final List<ItemDisplay> shields = new ArrayList<>();

    /** 在飞的火球。 */
    private final List<Fireball> fireballs = new ArrayList<>();

    /** 火球施法/发射状态（null = 没在开阵）。 */
    private Charge fireCharge;

    /** 剑气风暴车道。 */
    private final List<Storm> storms = new ArrayList<>();

    /** 当前那瓶加强甘露（null = 场上没有）。 */
    private org.bukkit.entity.Item nectar;

    /** 甘露已存在拍数。 */
    private int nectarAge;

    /** 甘露消失后等待拍数。 */
    private int nectarWait;

    /**
     * AI 开关的去重状态（null = 从未设置过）。
     *
     * <p>原脚本注释：{@code setAI} 每 tick 重复调用会不断重置她的 AI/目标，导致她站着不动。
     * 所以只在状态变化时调用一次。1.21 起 {@code setAI} 改名 {@code setAware}。</p>
     */
    private Boolean aiState;

    /** 是否已击败（结算只做一次）。 */
    private boolean defeated;

    public BossKatarina(BossState state) {
        this.state = state;
        this.key = state.key;
    }

    // ==================================================================
    // 静态入口：登场路由 / 弹反 / 增益清理（事件层与门面调）
    // ==================================================================

    /**
     * {@link BossPhaseTwo.NextArrival} 的路由实现。
     *
     * <p>Katarina 登场走本类；双子（批次 6）走 {@link BossTwin#startArrival}。
     * 再往后的「糖然下场决斗」不是护法登场，走 {@link BossPhaseTwo#startTangranDuel} 的独立钩子。</p>
     */
    public static void arriveRouter(BossState state, Player player, String guardianId) {
        if (state == null) return;
        if (BossState.GID_KATARINA.equalsIgnoreCase(guardianId)) {
            startArrival(state, player);
            return;
        }
        if (BossState.isTwinStage(guardianId)) {
            BossTwin.startArrival(state, player);
            return;
        }
        // 其它：还没有实现者 → 与 BossPhaseTwo 的默认行为一致，如实收场
        BossLog.warn("护法退场后的登场序列尚未接入：" + guardianId);
        try {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c[糖然科技] 下一位护法（" + guardianId
                        + "）的登场序列尚未原生化完成，本局在此收场，背包已返还。");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossBattleScript.forceStop(state, "下一位护法未实现：" + guardianId);
    }

    /**
     * 登场阵法：糖然头顶圆框 → 五角星生长 → 顶点光柱 → 3 秒后召唤本体
     * （原脚本 {@code startKatarinaArrivalSequence}）。
     *
     * <p>开阵同时播七句规则旁白（禁御剑 / 环绕盾限伤 / 弹反破盾 / 虚弱 / 剑气风暴 / 加强甘露），
     * 与原脚本一致 —— 旁白就是这一阶段的玩法说明。</p>
     */
    public static void startArrival(BossState state, Player player) {
        try {
            if (BossFight.byKey(state.key) != state) return;
            Entity stand = state.stand;
            if (stand == null || !stand.isValid()) return;
            org.bukkit.World world = stand.getWorld();
            Location top = stand.getLocation().clone().add(0, RITUAL_TOP_DY, 0);

            BossFx.sendLine(player, "§c[糖然]§b§lKatarina 的挑战规则，听好了：");
            scheduleLine(state, player, 40L, "§c[糖然]§b§l这里禁止御剑，你必须用戮仙剑击败她。");
            scheduleLine(state, player, 80L, "§c[糖然]§b§l她身边有护盾环绕，护盾存在时你只能对她造成 1 点伤害。");
            scheduleLine(state, player, 120L, "§c[糖然]§b§l她会召唤火球轰你——用戮仙剑挥砍火球即可弹反，能击碎她的护盾。");
            scheduleLine(state, player, 160L, "§c[糖然]§b§l护盾全部破碎时，她将受到重创并陷入虚弱。");
            scheduleLine(state, player, 200L, "§c[糖然]§b§l场地边缘会有剑气风暴从一端扫向另一端，跳起来就能躲开，"
                    + "被碰到扣 1 血；每击破她一整轮护盾，剑气风暴会变多变快喵😀");
            scheduleLine(state, player, 240L, "§c[糖然]§b§l地上的加强生命甘露能回 8 血，但只存在 20 秒，动作快些。");

            // 读档直开（跳过 zqx125）时：糖然血条按已打完 zqx125 的 80/100 走。
            // zqx125 正常退场那条路已经在 defeat 里压过血条，这里不重复压。
            if (!BossState.GID_ZQX125.equalsIgnoreCase(state.guardians.activeId)) {
                state.tangran.hp = 80.0d;
                BossPhaseTwo.refreshBar(state);
                BossFx.updateBarPlayers(state, top);
            }

            Color frame = Color.fromRGB(60, 120, 255);
            Color star = Color.fromRGB(120, 190, 255);
            Color beam = Color.fromRGB(170, 220, 255);
            // 光柱要一路蔓延到石英块层：按糖然头顶到地面的真实高度算
            final double beamHeight;
            BossArena.Region region = state.arena.twoRegion;
            if (region != null) {
                beamHeight = Math.max(6.0d, (top.getY() - (region.minY() + 1)) + 1.0d);
            } else {
                beamHeight = 20.0d;
            }

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            final String[] phase = {"circle"};
            // 阵法任务挂在槽位的 bodyTask 上（原脚本 phaseTwoKatarinaTask 的同位字段）：
            // 阵法期间战局被收掉时，BossState.cancelAll() 能连它一起收
            state.guardians.katarina.bodyTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.guardians.katarina.bodyTask);
                        state.guardians.katarina.bodyTask = null;
                        return;
                    }
                    Entity cur = state.stand;
                    if (cur == null || !cur.isValid()) {
                        BossState.cancelTask(state.guardians.katarina.bodyTask);
                        state.guardians.katarina.bodyTask = null;
                        return;
                    }
                    tick[0]++;
                    if ("circle".equals(phase[0])) {
                        drawCircle(world, top, RITUAL_RADIUS, tick[0] / 40.0d, frame, 1.2f);
                        if (tick[0] >= CIRCLE_TICKS) {
                            phase[0] = "star";
                            tick[0] = 0;
                        }
                        return;
                    }
                    if ("star".equals(phase[0])) {
                        double t = Math.min(1.0d, tick[0] / (double) STAR_TICKS);
                        drawCircle(world, top, RITUAL_RADIUS, tick[0] / 50.0d, frame, 1.2f);
                        drawStar(world, top, RITUAL_RADIUS * t, tick[0] / 30.0d, star, 1.3f);
                        if (tick[0] >= STAR_TICKS) {
                            phase[0] = "beam";
                            tick[0] = 0;
                        }
                        return;
                    }
                    if ("beam".equals(phase[0])) {
                        drawStar(world, top, RITUAL_RADIUS, tick[0] / 30.0d, beam, 2.6f);
                        drawCircle(world, top, RITUAL_RADIUS, tick[0] / 50.0d, beam, 2.6f);
                        drawPillars(world, top, RITUAL_RADIUS, tick[0] / 30.0d, beam, 2.6f, beamHeight);
                        if (tick[0] >= BEAM_TICKS) {
                            BossState.cancelTask(state.guardians.katarina.bodyTask);
                            state.guardians.katarina.bodyTask = null;
                            spawnGuardian(state, player);
                        }
                    }
                } catch (Throwable t) {
                    BossLog.warn("Katarina 登场阵法异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("Katarina 登场阵法启动失败: " + t);
        }
    }

    /**
     * 阵法收尾：装配并召唤本体（原脚本 {@code spawnKatarinaGuardian} 的入口部分）。
     *
     * <p>{@code activeId} 必须<b>在 install 之前</b>落到 Katarina ——
     * {@link GuardianManager#install} 按它选工厂。原脚本改 id 的点在
     * {@code spawnKatarinaGuardian} 里（装配之后），这里提前到装配之前，
     * 否则 install 会去找 zqx125 的工厂。</p>
     */
    private static void spawnGuardian(BossState state, Player player) {
        state.guardians.activeId = BossState.GID_KATARINA;
        Location target;
        BossArena.Region region = state.arena.twoRegion;
        if (region != null && state.arena.twoWorld != null) {
            target = new Location(state.arena.twoWorld,
                    region.minX() + (region.maxX() - region.minX()) / 2.0d + 0.5d,
                    region.minY() + 1.0d,
                    region.minZ() + (region.maxZ() - region.minZ()) / 2.0d + 0.5d);
        } else if (state.stand != null && state.stand.isValid()) {
            target = state.stand.getLocation().clone().subtract(0, RITUAL_TOP_DY, 0);
        } else {
            target = (player != null) ? player.getLocation() : null;
        }
        if (target == null) {
            BossLog.warn("Katarina 装配失败：没有落点");
            return;
        }
        GuardianManager.install(state, player, target);
    }

    /**
     * 挥砍弹反（事件层 {@code PlayerArmSwingEvent} 调，原脚本 {@code tryKatarinaFireballDeflect}）。
     *
     * <p>三个条件全满足才弹得动：火球在 3.6 格内、玩家准星对着它（点积 ≥ 0.35）、
     * 手里是戮仙剑（主手优先，副手兜底）。一次挥砍只弹一发。</p>
     */
    public static boolean tryDeflect(Player player) {
        try {
            if (player == null) return false;
            BossState st = BossFight.bySummoner(player);
            if (st == null) return false;
            if (!BossState.GID_KATARINA.equalsIgnoreCase(st.guardians.activeId)) return false;
            if (!(st.guardians.active instanceof BossKatarina k)) return false;
            return k.deflectOne(player);
        } catch (Throwable t) {
            BossLog.warn("火球弹反派发失败: " + t);
            return false;
        }
    }

    /** 摘掉玩家增益（收场 / 滞留恢复时调，原脚本 {@code clearKatarinaPlayerBuffs}）。 */
    public static void clearBuffs(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            player.removePotionEffect(PotionEffectType.SPEED);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 场上没有加强甘露时刷一瓶（三阶段决斗也沿用：原脚本 {@code startKatarinaNectar} 的复用）。
     *
     * @return true = 这次真的刷了一瓶
     */
    public static boolean spawnDuelNectar(BossState state) {
        try {
            if (state == null || state.arena.twoRegion == null) return false;
            // 场上已有加强甘露就不重刷（按道具标签扫，与 BossPhaseTwoItems 的口径一致）
            for (org.bukkit.entity.Item it : state.phaseTwo.drops) {
                if (it == null || !it.isValid()) continue;
                if (BossPhaseTwoItems.NECTAR_PLUS.equals(BossPhaseTwoItems.kindOf(it.getItemStack()))) {
                    return false;
                }
            }
            Location loc = BossFx.randomArenaPoint(state, false);
            if (loc == null) return false;
            return BossPhaseTwoItems.spawnDrop(state, BossPhaseTwoItems.createKatarinaNectar(),
                    loc, true) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 收掉双子阶段的属性加成（经经营小游戏钩子转发；批次 6c 实装后生效）。 */
    public static void clearUpgrades(Player player) {
        try {
            BossTwin.XuGameHook hook = BossTwin.xuGame();
            if (hook != null) {
                hook.clearUpgrades(player);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // BossGuardian 接口
    // ==================================================================

    @Override
    public String id() {
        return BossState.GID_KATARINA;
    }

    @Override
    public String title() {
        return "天裁";
    }

    @Override
    public double maxHp() {
        return MAX_HP;
    }

    /**
     * 召唤本体 + 血条 + 启动全部机制（原脚本 {@code spawnKatarinaGuardian}）。
     *
     * <p>由 {@link GuardianManager#install} 在本局装配时调用；
     * {@code target} 是登场阵法算好的落点（场地正中央石英层），
     * 场地信息缺失时退化为 target 本身。</p>
     */
    @Override
    public void spawn(Player player, Location target) {
        this.player = player;
        this.slot = state.guardians.katarina;
        try {
            org.bukkit.World world = (state.arena.twoWorld != null)
                    ? state.arena.twoWorld : (target != null ? target.getWorld() : null);
            if (world == null) {
                BossLog.warn("Katarina 召唤失败：没有世界");
                return;
            }
            Location spawnLoc = null;
            BossArena.Region region = state.arena.twoRegion;
            if (region != null) {
                spawnLoc = new Location(world,
                        region.minX() + (region.maxX() - region.minX()) / 2.0d + 0.5d,
                        region.minY() + 1.0d,
                        region.minZ() + (region.maxZ() - region.minZ()) / 2.0d + 0.5d);
            } else if (target != null) {
                spawnLoc = target.clone();
            }
            if (spawnLoc == null) {
                BossLog.warn("Katarina 召唤失败：没有落点");
                return;
            }

            WitherSkeleton boss = world.spawn(spawnLoc, WitherSkeleton.class);
            boss.setCustomName("§c§l[天裁]§e§lKatarina");
            boss.setCustomNameVisible(true);
            boss.setPersistent(true);
            boss.setRemoveWhenFarAway(false);
            boss.setCanPickupItems(false);
            boss.setAware(true);            // 保留 AI：会自己追击/攻击玩家
            boss.setInvulnerable(false);    // 可被打
            boss.setCollidable(true);
            try {
                var maxHealth = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(MAX_HP);
                    boss.setHealth(MAX_HP);
                }
            } catch (Throwable t) {
                BossLog.warn("Katarina 本体血量设置失败: " + t);
            }

            // 装备：正版头颅 + 整套肋骨/绿宝石纹饰紫皮革 + 一把铁剑（掉落率全 0）
            try {
                var equip = boss.getEquipment();
                if (equip != null) {
                    equip.setHelmet(BossGuardianSkin.head(id(), null, null));
                    equip.setChestplate(katarinaArmor(Material.LEATHER_CHESTPLATE));
                    equip.setLeggings(katarinaArmor(Material.LEATHER_LEGGINGS));
                    equip.setBoots(katarinaArmor(Material.LEATHER_BOOTS));
                    equip.setItemInMainHand(new ItemStack(Material.IRON_SWORD, 1));
                    equip.setHelmetDropChance(0.0f);
                    equip.setChestplateDropChance(0.0f);
                    equip.setLeggingsDropChance(0.0f);
                    equip.setBootsDropChance(0.0f);
                    equip.setItemInMainHandDropChance(0.0f);
                }
            } catch (Throwable t) {
                BossLog.warn("Katarina 装备设置失败: " + t);
            }

            slot.body = boss;
            // 本体 id 落到 activeId：近战放行 / 弹反入口都按它判「现在是不是 Katarina 阶段」
            state.guardians.activeId = BossState.GID_KATARINA;
            slot.defeated = false;
            slot.maxHp = MAX_HP;
            slot.hp = MAX_HP;
            slot.damageable = true;      // 可被玩家真实攻击（盾牌期间限伤 1）
            slot.shieldCount = SHIELD_COUNT;

            // BossBar：[天裁]Katarina n/600（蓝色）
            try {
                BossState.removeBar(slot.bar);
                org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                        barTitle(), org.bukkit.boss.BarColor.BLUE,
                        org.bukkit.boss.BarStyle.SOLID);
                bar.setProgress(1.0d);
                bar.setVisible(true);
                slot.bar = bar;
            } catch (Throwable t) {
                BossLog.warn("Katarina 血条创建失败: " + t);
            }
            updateBarTitle();
            BossFx.updateBarPlayers(state, spawnLoc);

            // Katarina 阶段：清掉戮仙剑剑影、强制下剑、禁止再上剑
            try {
                LuxianSword.stopRide(player);
            } catch (Throwable ignored) {
                // 装备脚本未加载 / 未骑乘：忽略
            }
            try {
                LuxianSword.setNoSwordRide(player, true);
            } catch (Throwable ignored) {
                // 忽略
            }
            clearSwordDisplays();

            startTrackingTask();
            applyBuffs(player);
            // 开场清掉上一阶段残留的道具（包括飘在空中的甘露）
            clearGroundDrops();
            startKit();
            try {
                BossFx.sendLine(player, "§c[糖然]§b§l出来吧，Katarina。");
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                world.playSound(spawnLoc, "entity.wither.spawn", 0.8f, 1.4f);
            } catch (Throwable ignored) {
                // 忽略
            }
            BossLog.warn("召唤护法：Katarina（天裁） 血量上限 " + (int) MAX_HP);
        } catch (Throwable t) {
            BossLog.warn("Katarina 登场失败: " + t);
        }
    }

    @Override
    public boolean canBeDamaged(Entity victim) {
        try {
            if (slot == null || !slot.damageable) return false;
            return slot.body != null && slot.body.equals(victim);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 计数器伤害（OP 指令等外部路径；原脚本 {@code setTangranBossGuardianHp}）。
     *
     * <p>弹反火球的伤害不走这里（它在结算里直接改血量，见 {@link #resolveFireballHit}）——
     * 但两条路最终都汇到 {@link #applyCounterDamage}，口径一致。</p>
     */
    @Override
    public boolean damage(Player damager, double amount) {
        try {
            if (slot == null || defeated) return false;
            applyCounterDamage(amount);
            return true;
        } catch (Throwable t) {
            BossLog.warn("Katarina 扣血失败: " + t);
            return false;
        }
    }

    /**
     * 血量归零：记录进度、停机制、压糖然血条 20 点，然后呼叫双子登场
     * （原脚本 {@code defeatPhaseTwoGuardian} 的 Katarina 分支 + 公共部分）。
     */
    @Override
    public void defeat(Player player) {
        if (state == null || defeated || slot == null) return;
        defeated = true;
        slot.defeated = true;
        this.player = (player != null) ? player : this.player;

        try {
            BossProgress.markGuardianDefeated(state.playerName, id());
        } catch (Throwable ignored) {
            // 忽略
        }

        stopKit();
        clearGroundDrops();
        BossState.removeEntity(slot.body);
        slot.body = null;
        BossState.removeBar(slot.bar);
        slot.bar = null;
        slot.hp = 0.0d;

        // 糖然血条：Katarina 退场扣 20（80 → 60；双子退场再扣 40，批次 6）
        state.tangran.hp = Math.max(0.0d, state.tangran.hp - 20.0d);
        BossPhaseTwo.refreshBar(state);
        Location at = (this.player != null) ? this.player.getLocation() : null;
        if (at != null) BossFx.updateBarPlayers(state, at);

        try {
            Player p = this.player;
            if (p != null && p.isOnline()) {
                p.getWorld().playSound(p.getLocation(), "entity.skeleton.death", 1.0f, 0.7f);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        // 双子登场序列（批次 6 注册 arriveRouter 的那个分支）
        BossPhaseTwo.arriveNext(state, this.player, BossState.GID_TWIN_A);
    }

    /** 停掉全部机制任务、移除本体与血条、清场（收场时必须幂等）。 */
    @Override
    public void cleanup() {
        defeated = true;
        stopKit();
        BossState.removeEntity(slot != null ? slot.body : null);
        if (slot != null) {
            slot.body = null;
            BossState.removeBar(slot.bar);
            slot.bar = null;
        }
        // 注意：玩家增益（速度/跳跃）不在这里摘 —— 原脚本只在整局收场
        // （removeBossStand）与滞留恢复时摘，护法轮换期间增益保持。
    }

    // ==================================================================
    // 登场阵法绘制（原脚本 drawKatarinaCircle / drawKatarinaStar / drawKatarinaPillars）
    // ==================================================================

    /** 圆框：72 步采样一圈。 */
    private static void drawCircle(org.bukkit.World world, Location center, double radius,
                                   double spin, Color dust, float size) {
        int steps = 72;
        for (int i = 0; i < steps; i++) {
            double a = spin * Math.PI * 2 + i * Math.PI * 2 / steps;
            BossFx.dust(world, center.getX() + Math.cos(a) * radius, center.getY(),
                    center.getZ() + Math.sin(a) * radius, dust, size);
        }
    }

    /** 五角星：顶点按 0→2→4→1→3→0 连线（经典五角星路径）。 */
    private static void drawStar(org.bukkit.World world, Location center, double radius,
                                 double spin, Color dust, float size) {
        double[] vx = new double[5];
        double[] vz = new double[5];
        for (int v = 0; v < 5; v++) {
            double ang = spin + v * Math.PI * 2 / 5 - Math.PI / 2;
            vx[v] = center.getX() + Math.cos(ang) * radius;
            vz[v] = center.getZ() + Math.sin(ang) * radius;
        }
        int[] order = {0, 2, 4, 1, 3, 0};
        int seg = 12;
        for (int e = 0; e < 5; e++) {
            double ax = vx[order[e]], az = vz[order[e]];
            double bx = vx[order[e + 1]], bz = vz[order[e + 1]];
            for (int s = 0; s < seg; s++) {
                double t = s / (double) seg;
                BossFx.dust(world, ax + (bx - ax) * t, center.getY(), az + (bz - az) * t,
                        dust, size);
            }
        }
    }

    /** 5 个顶点向下的光柱：竖直线取点 + 随机半径采样，模拟有粗细。 */
    private static void drawPillars(org.bukkit.World world, Location center, double radius,
                                    double spin, Color dust, float size, double height) {
        for (int v = 0; v < 5; v++) {
            double ang = spin + v * Math.PI * 2 / 5 - Math.PI / 2;
            double bx = center.getX() + Math.cos(ang) * radius;
            double bz = center.getZ() + Math.sin(ang) * radius;
            for (double y = 0; y <= height; y += 0.25) {
                double rr = Math.random() * 0.45;
                double ra = Math.random() * Math.PI * 2;
                BossFx.dust(world, bx + Math.cos(ra) * rr, center.getY() - y,
                        bz + Math.sin(ra) * rr, dust, size);
            }
        }
    }

    // ==================================================================
    // 机制主循环（原脚本 startKatarinaKit）
    // ==================================================================

    /** 启动盾牌 / 火球 / 风暴 / 甘露 / 增益的總控。 */
    private void startKit() {
        try {
            mode = "shields";
            modeTick = 0;
            cycle = 0;
            fireballs.clear();
            storms.clear();
            nectar = null;
            nectarAge = 0;
            nectarWait = 0;
            spawnShields();

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            final int[] nextFire = {40 + (int) Math.floor(Math.random() * FIRE_INTERVAL)};
            final int[] nextStorm = {STORM_INTERVAL};
            kitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        stopKit();
                        return;
                    }
                    Entity g = slot.body;
                    if (g == null || !g.isValid()) {
                        stopKit();
                        return;
                    }
                    Player p = this.player;
                    if (p == null || !p.isOnline()) {
                        stopKit();
                        return;
                    }
                    tick[0]++;

                    // 强制锁定玩家为目标（否则她可能因为重置/距离而站着不动）
                    if (tick[0] % 20 == 0 && p.isOnline() && !p.isDead()) {
                        if (!"stun".equals(mode) && !"summon".equals(mode)) {
                            try {
                                ((WitherSkeleton) g).setTarget(p);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    }
                    updateShields(tick[0]);
                    updateFireballs(p);
                    updateStorms(p);
                    // 甘露的地面光柱 + 磁吸（她这一阶段没有 zqx125 的粒子任务，自己跑）
                    if (tick[0] % 4 == 0) markDrops(p);
                    // 玩家增益常驻：速度 III + 跳跃提升 I（每 5 秒刷新一次）
                    if (tick[0] % 100 == 1) applyBuffs(p);

                    // ---- 状态机 ----
                    modeTick++;
                    if ("stun".equals(mode)) {
                        setAI(false);
                        if (modeTick >= STUN_TICKS) {
                            mode = "normal";
                            modeTick = 0;
                            setAI(true);
                        }
                    } else if ("normal".equals(mode)) {
                        setAI(true);
                        if (modeTick >= NORMAL_TICKS) {
                            mode = "summon";
                            modeTick = 0;
                            setAI(false);
                            try {
                                p.sendMessage("§cKatarina 开始召唤新的护盾！");
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    } else if ("summon".equals(mode)) {
                        setAI(false);
                        if (modeTick >= SUMMON_TICKS) {
                            mode = "shields";
                            modeTick = 0;
                            spawnShields();
                            try {
                                p.sendMessage("§bKatarina 的护盾重新展开（本轮限伤恢复）！");
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    } else {
                        // shields：盾还在，保持 AI
                        setAI(true);
                    }

                    // ---- 火球：施法特效 4 秒 → 一发一发发射，每发间隔 1 秒 ----
                    nextFire[0]--;
                    if (nextFire[0] <= 0) {
                        nextFire[0] = FIRE_INTERVAL;
                        if (fireCharge == null && !"stun".equals(mode) && !"summon".equals(mode)) {
                            fireCharge = new Charge();
                        }
                    }
                    if (fireCharge != null) {
                        Charge charge = fireCharge;
                        charge.tick++;
                        Location eye = fireEye();
                        if (eye != null) {
                            drawFireCircle(eye, tick[0] * 0.1d);
                            try {
                                eye.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                                        eye.getX(), eye.getY(), eye.getZ(),
                                        2, 0.05, 0.05, 0.05, 0.005);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            if (charge.firing) {
                                try {
                                    eye.getWorld().spawnParticle(org.bukkit.Particle.LAVA,
                                            eye.getX(), eye.getY(), eye.getZ(),
                                            1, 0.12, 0.12, 0.12, 0.0);
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                            }
                        }
                        if (!charge.firing) {
                            if (charge.tick >= FIRE_CHARGE) {
                                charge.firing = true;
                                charge.tick = 0;
                                charge.left = fireballCount();
                            }
                        } else if (charge.tick % FIRE_GAP == 1) {
                            if (charge.left > 0) {
                                launchFireball(p);
                                charge.left--;
                            } else {
                                fireCharge = null;
                                nextFire[0] = FIRE_INTERVAL;   // 整轮打完，重新计时
                            }
                        }
                    }

                    // ---- 剑气风暴 ----
                    nextStorm[0]--;
                    if (nextStorm[0] <= 0) {
                        nextStorm[0] = stormInterval();
                        spawnStormWave();
                        try {
                            p.sendMessage("§c剑气风暴来袭！跳起来躲开！");
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }

                    // ---- 加强甘露：存在 20 秒；没捡走换位置重刷；捡走 10 秒后下一瓶 ----
                    if (nectar != null && nectar.isValid()) {
                        nectarAge++;
                        if (nectarAge >= NECTAR_LIFETIME) {
                            BossState.removeEntity(nectar);
                            nectar = null;
                            nectarWait = 0;
                        }
                    } else if (nectar != null) {
                        nectar = null;
                        nectarWait = 0;
                    } else {
                        nectarWait++;
                        if (nectarWait >= NECTAR_DELAY) {
                            nectarWait = 0;
                            spawnNectar();
                        }
                    }
                } catch (Throwable t) {
                    BossLog.warn("Katarina 机制异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("Katarina 机制启动失败: " + t);
        }
    }

    /** 停總控（原脚本 {@code stopKatarinaKit} 的机制部分；本体/血条由 cleanup 收）。 */
    private void stopKit() {
        BossState.cancelTask(kitTask);
        kitTask = null;
        clearShields();
        for (Fireball f : fireballs) {
            BossState.removeEntity(f.entity);
        }
        fireballs.clear();
        storms.clear();
        fireCharge = null;
        BossState.removeEntity(nectar);
        nectar = null;
    }

    // ==================================================================
    // 环绕盾（原脚本 spawnKatarinaShields / updateKatarinaShields / breakKatarinaShield）
    // ==================================================================

    /** 刷三面盾（展示实体，不可见碰撞，只做视觉 + 限伤标记）。 */
    private void spawnShields() {
        try {
            Entity g = slot.body;
            if (g == null || !g.isValid()) return;
            clearShields();
            for (int i = 0; i < SHIELD_COUNT; i++) {
                ItemDisplay d = g.getWorld().spawn(g.getLocation(), ItemDisplay.class);
                try {
                    d.setItemStack(shieldItem());
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                } catch (Throwable ignored) {
                    // 老版本没有这个枚举值
                }
                try {
                    d.setBillboard(Display.Billboard.FIXED);
                } catch (Throwable ignored) {
                    // 忽略
                }
                d.setGravity(false);
                d.setInvulnerable(true);
                d.setPersistent(false);
                shields.add(d);
            }
            slot.shieldCount = SHIELD_COUNT;
        } catch (Throwable t) {
            BossLog.warn("Katarina 盾牌生成失败: " + t);
        }
    }

    /** 盾牌道具（原脚本 {@code createKatarinaShieldItem}）。 */
    private static ItemStack shieldItem() {
        ItemStack item = new ItemStack(Material.SHIELD, 1);
        try {
            var meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(BossFx.legacy("§b§l护盾"));
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return item;
    }

    /** 收掉全部盾牌展示实体。 */
    private void clearShields() {
        for (ItemDisplay d : shields) {
            BossState.removeEntity(d);
        }
        shields.clear();
    }

    /** 盾牌环绕本体（每拍转 0.07 弧度，正面朝外对着玩家方向）。 */
    private void updateShields(int tick) {
        try {
            Entity g = slot.body;
            if (g == null || !g.isValid() || shields.isEmpty()) return;
            Location loc = g.getLocation();
            for (int i = 0; i < shields.size(); i++) {
                ItemDisplay d = shields.get(i);
                if (d == null || !d.isValid()) continue;
                double a = tick * 0.07d + i * (Math.PI * 2 / Math.max(1, shields.size()));
                double sx = loc.getX() + Math.cos(a) * SHIELD_RADIUS;
                double sz = loc.getZ() + Math.sin(a) * SHIELD_RADIUS;
                Location dl = new Location(loc.getWorld(), sx, loc.getY() + SHIELD_Y, sz);
                // 盾牌环绕 Katarina：让「正面」朝外对着玩家、握把朝内（即朝向本体）
                double dx = loc.getX() - sx;
                double dz = loc.getZ() - sz;
                if (Math.abs(dx) + Math.abs(dz) > 0.001) {
                    dl.setYaw((float) (Math.atan2(-dx, dz) * 180.0d / Math.PI));
                } else {
                    dl.setYaw((float) (a * 180.0d / Math.PI) + 270);
                }
                dl.setPitch(0f);
                try {
                    d.teleport(dl);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 破一面盾；全破后：巨额伤害 + 解除限伤 + 5 秒虚弱
     * （原脚本 {@code breakKatarinaShield}）。
     */
    private void breakShield(Player player) {
        try {
            if (slot.shieldCount <= 0) return;
            if (!shields.isEmpty()) {
                BossState.removeEntity(shields.remove(shields.size() - 1));
            }
            slot.shieldCount = Math.max(0, slot.shieldCount - 1);
            try {
                player.sendMessage("§b护盾被击破！剩余 §e" + slot.shieldCount + " §b面");
            } catch (Throwable ignored) {
                // 忽略
            }
            if (slot.shieldCount == 0) {
                // 巨额伤害 + 5 秒虚弱（本体血量保底 1：这一下不直接打死她）
                double next = Math.max(0.0d, entityHp() - BREAK_DAMAGE);
                slot.hp = next;
                syncEntityHealth(Math.max(1.0d, next));
                updateBarTitle();
                mode = "stun";
                modeTick = 0;
                cycle++;
                try {
                    player.sendMessage("§c所有护盾破碎！Katarina 受到 " + BREAK_DAMAGE
                            + " 点伤害并陷入虚弱！");
                    player.playSound(player.getLocation(), "entity.ender_dragon.hurt", 1.0f, 1.2f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                setAI(false);
            }
        } catch (Throwable t) {
            BossLog.warn("Katarina 破盾异常: " + t);
        }
    }

    /** AI 开关去重（原脚本 {@code setKatarinaAI}；只在状态变化时调用）。 */
    private void setAI(boolean on) {
        try {
            Entity g = slot.body;
            if (g == null || !g.isValid()) return;
            if (aiState == null || aiState != on) {
                aiState = on;
                ((WitherSkeleton) g).setAware(on);
            }
            if (!on) {
                try {
                    g.setVelocity(new Vector(0, 0, 0));
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 火球（原脚本 startKatarinaFireball / launchKatarinaFireball / updateKatarinaFireballs）
    // ==================================================================

    /** 阵眼：后背头上方（本体朝向后方 0.8 格、上方 2.2 格）。 */
    private Location fireEye() {
        try {
            Entity g = slot.body;
            if (g == null || !g.isValid()) return null;
            Location loc = g.getLocation();
            double yaw = loc.getYaw() * Math.PI / 180.0d;
            double backX = Math.sin(yaw);
            double backZ = -Math.cos(yaw);
            return new Location(loc.getWorld(),
                    loc.getX() + backX * 0.8d,
                    loc.getY() + 2.2d,
                    loc.getZ() + backZ * 0.8d);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 竖着的法阵：在竖直平面内画圆（半径 1.1）。 */
    private void drawFireCircle(Location eye, double angle) {
        double r = 1.1d;
        int steps = 36;
        Color dust = Color.ORANGE;
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps;
            double px = Math.cos(a) * r;
            double py = Math.sin(a) * r;
            double px2 = px * Math.cos(angle) - py * Math.sin(angle) * 0.2;
            BossFx.dust(eye.getWorld(), eye.getX() + px2, eye.getY() + py,
                    eye.getZ() + px * Math.sin(angle), dust, 1.6f);
        }
    }

    /** 每轮火球数量：第 0 轮 3 发，之后每轮 +2，最多 10 发。 */
    private int fireballCount() {
        return Math.min(10, 3 + cycle * 2);
    }

    /** 单发火球：朝玩家当前位置瞄准（每发都重新瞄）。 */
    private void launchFireball(Player p) {
        try {
            Entity g = slot.body;
            Location eye = fireEye();
            if (g == null || !g.isValid() || eye == null) return;
            double speed = Math.min(1.5d, 0.85d + cycle * 0.1d);
            Vector dir = p.getLocation().clone().add(0, 1.0d, 0).toVector()
                    .subtract(eye.toVector());
            if (dir.lengthSquared() < 0.0001d) return;
            dir.normalize();
            LargeFireball fb = eye.getWorld().spawn(eye, LargeFireball.class);
            fb.setDirection(dir);
            fb.setYield(0);                 // 爆炸不破坏方块
            fb.setIsIncendiary(false);      // 不产生火焰
            try {
                fb.setShooter((LivingEntity) g);
            } catch (Throwable ignored) {
                // 忽略
            }
            fb.setGravity(false);
            fb.setVelocity(dir.clone().multiply(speed));
            Fireball f = new Fireball();
            f.entity = fb;
            fireballs.add(f);
            try {
                eye.getWorld().playSound(eye, "entity.blaze.shoot", 1.0f, 0.9f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable t) {
            BossLog.warn("Katarina 火球发射失败: " + t);
        }
    }

    /** 一次挥砍弹掉一发火球（原脚本 {@code tryKatarinaFireballDeflect} 的单发部分）。 */
    private boolean deflectOne(Player player) {
        try {
            Entity g = slot.body;
            if (g == null || !g.isValid()) return false;
            for (Fireball f : fireballs) {
                Entity e = f.entity;
                boolean ok;
                try {
                    ok = e != null && e.isValid();
                } catch (Throwable err) {
                    ok = false;
                }
                if (!ok || f.deflected) continue;
                Location eyeLoc;
                Location fLoc;
                try {
                    eyeLoc = player.getEyeLocation();
                    fLoc = e.getLocation();
                } catch (Throwable err) {
                    continue;
                }
                if (eyeLoc == null || fLoc == null) continue;
                if (fLoc.distanceSquared(eyeLoc) > DEFLECT_RANGE_SQ) continue;
                // 火球必须在身前：玩家得「打到」火球（盯准它挥砍），而不是背对着也弹反
                Vector look = eyeLoc.getDirection();
                Vector toBall = fLoc.clone().add(0, 0.4d, 0).toVector().subtract(eyeLoc.toVector());
                if (look != null && toBall.lengthSquared() > 0.0001d) {
                    toBall.normalize();
                    double dot = look.getX() * toBall.getX() + look.getY() * toBall.getY()
                            + look.getZ() * toBall.getZ();
                    if (dot < DEFLECT_DOT) continue;
                }
                ItemStack held = player.getInventory().getItemInMainHand();
                if (!LuxianSword.isSword(held)) {
                    ItemStack off = player.getInventory().getItemInOffHand();
                    held = LuxianSword.isSword(off) ? off : null;
                }
                if (held == null) continue;

                f.deflected = true;
                Vector dir = g.getLocation().clone().add(0, 1.2d, 0).toVector()
                        .subtract(e.getLocation().toVector());
                if (dir.lengthSquared() > 0.0001d) {
                    dir.normalize();
                    // setDirection 在 Fireball 上（Entity 没有这个方法）
                    if (e instanceof LargeFireball fb) {
                        try {
                            fb.setDirection(dir);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    e.setVelocity(dir.multiply(DEFLECT_SPEED));
                }
                try {
                    player.playSound(player.getLocation(), "entity.blaze.hurt", 1.0f, 1.4f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 剑刃命中火球的瞬间特效
                try {
                    Location fl = e.getLocation();
                    fl.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK,
                            fl.getX(), fl.getY(), fl.getZ(), 3, 0.2, 0.2, 0.2, 0);
                    fl.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                            fl.getX(), fl.getY(), fl.getZ(), 14, 0.25, 0.25, 0.25, 0.06);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 只要弹反成功就播报（哪怕这一发没打到她）
                try {
                    player.sendMessage("§a您成功弹反了火球！");
                } catch (Throwable ignored) {
                    // 忽略
                }
                return true;
            }
        } catch (Throwable t) {
            BossLog.warn("火球弹反异常: " + t);
        }
        return false;
    }

    /** 每拍推进/判定全部火球（原脚本 {@code updateKatarinaFireballs}）。 */
    private void updateFireballs(Player p) {
        try {
            List<Fireball> keep = new ArrayList<>();
            Entity g = slot.body;
            for (Fireball f : fireballs) {
                Entity e = f.entity;
                boolean valid;
                try {
                    valid = e != null && e.isValid();
                } catch (Throwable err) {
                    valid = false;
                }
                if (!valid) {
                    // 火球已被原版引爆/移除：用「上一帧位置 + 速度」的线段补判是否撞上目标
                    if (f.lastX != null) {
                        double ex = f.lastX + f.vx * FIREBALL_LEAD;
                        double ey = f.lastY + f.vy * FIREBALL_LEAD;
                        double ez = f.lastZ + f.vz * FIREBALL_LEAD;
                        if (f.deflected && g != null && g.isValid()) {
                            Location gp = g.getLocation().add(0, 1.0d, 0);
                            if (pointSegDist2(gp.getX(), gp.getY(), gp.getZ(),
                                    f.lastX, f.lastY, f.lastZ, ex, ey, ez) <= FIREBALL_GHOST_BODY_SQ) {
                                resolveFireballHit(p, null);
                                continue;
                            }
                        }
                        if (!f.deflected && p.isOnline() && !p.isDead()) {
                            Location pp = p.getLocation().add(0, 1.0d, 0);
                            if (pointSegDist2(pp.getX(), pp.getY(), pp.getZ(),
                                    f.lastX, f.lastY, f.lastZ, ex, ey, ez) <= FIREBALL_GHOST_PLAYER_SQ) {
                                BossFx.directDamage(p, FIRE_DAMAGE);
                                BossFx.hurtFeedback(p);
                                continue;
                            }
                        }
                    }
                    continue;
                }
                f.age++;
                if (f.age > FIREBALL_MAX_AGE) {
                    BossState.removeEntity(e);
                    continue;
                }
                Location loc = e.getLocation();
                if (loc == null) continue;
                Double prevX = f.lastX;
                Double prevY = f.lastY;
                Double prevZ = f.lastZ;
                // 火球打到玩家：固定 2 伤害
                if (!f.deflected && p.isOnline() && !p.isDead()) {
                    double d2p;
                    try {
                        d2p = loc.distanceSquared(p.getLocation().add(0, 1, 0));
                    } catch (Throwable err) {
                        d2p = 99;
                    }
                    if (d2p <= FIREBALL_HIT_PLAYER_SQ) {
                        BossFx.directDamage(p, FIRE_DAMAGE);
                        BossFx.hurtFeedback(p);
                        try {
                            loc.getWorld().createExplosion(loc.getX(), loc.getY(), loc.getZ(),
                                    0, false, false);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        BossState.removeEntity(e);
                        continue;
                    }
                }
                // 弹反后的火球打到 Katarina 本体
                if (f.deflected && g != null && g.isValid()) {
                    Location gp = g.getLocation().add(0, 1.0d, 0);
                    boolean hitG = false;
                    double gdx = loc.getX() - gp.getX();
                    double gdz = loc.getZ() - gp.getZ();
                    double gdy = Math.abs(loc.getY() - gp.getY());
                    // ① 本体判定：水平 2.3 格内、高度落在她身体范围内
                    if ((gdx * gdx + gdz * gdz) <= FIREBALL_HIT_BODY_SQ && gdy <= FIREBALL_HIT_BODY_DY) {
                        hitG = true;
                    }
                    // ② 扫掠判定：上一帧 → 本帧 + 2 格前瞻，擦到身体也算
                    if (!hitG && prevX != null) {
                        double nex = loc.getX() + f.vx * 2.0d;
                        double ney = loc.getY() + f.vy * 2.0d;
                        double nez = loc.getZ() + f.vz * 2.0d;
                        if (pointSegDist2(gp.getX(), gp.getY(), gp.getZ(),
                                prevX, prevY, prevZ, nex, ney, nez) <= FIREBALL_SWEEP_SQ) {
                            hitG = true;
                        }
                    }
                    if (hitG) {
                        resolveFireballHit(p, loc);
                        try {
                            loc.getWorld().createExplosion(loc.getX(), loc.getY(), loc.getZ(),
                                    0, false, false);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        BossState.removeEntity(e);
                        continue;
                    }
                }
                // 记录上一帧位置与速度（供火球被原版提前移除时的补判使用）
                try {
                    Vector vel = e.getVelocity();
                    f.vx = vel.getX();
                    f.vy = vel.getY();
                    f.vz = vel.getZ();
                } catch (Throwable ignored) {
                    // 忽略
                }
                f.lastX = loc.getX();
                f.lastY = loc.getY();
                f.lastZ = loc.getZ();
                // 弹反后的火球继续飞：拖尾（一眼看出它已经被弹反了）
                if (f.deflected) {
                    try {
                        loc.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                                loc.getX(), loc.getY(), loc.getZ(), 2, 0.05, 0.05, 0.05, 0.0);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                keep.add(f);
            }
            fireballs.clear();
            fireballs.addAll(keep);
        } catch (Throwable t) {
            BossLog.warn("Katarina 火球推进异常: " + t);
        }
    }

    /**
     * 弹反命中的统一结算：破 1 面盾（有盾时）+ 本体伤害（常态 5 / 虚弱 10）+ 播报
     * （原脚本 {@code resolveKatarinaFireballHit}）。
     */
    private void resolveFireballHit(Player p, Location hit) {
        try {
            // 虚弱 = 盾全破后的 stun 硬直窗口（判定用命中那一刻的状态）
            boolean stunnedThen = "stun".equals(mode);
            double fireDmg = stunnedThen ? FIRE_HIT_STUN : FIRE_HIT_NORMAL;
            breakShield(p);
            // 以本体当前血量结算，避免与近战掉血 / 破盾 50 伤害错位
            applyCounterDamage(fireDmg);
            try {
                p.sendMessage(stunnedThen
                        ? ("§d弹反命中虚弱的 Katarina，造成 §c" + (int) fireDmg + " §d点伤害！")
                        : ("§d弹反命中 Katarina，造成 §c" + (int) fireDmg + " §d点伤害！"));
                p.playSound(p.getLocation(), "entity.player.attack.strong", 1.0f,
                        stunnedThen ? 0.7f : 1.1f);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (hit != null) {
                try {
                    org.bukkit.World w = p.getWorld();
                    w.spawnParticle(org.bukkit.Particle.FLAME,
                            hit.getX(), hit.getY(), hit.getZ(), 24, 0.35, 0.35, 0.35, 0.09);
                    w.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME,
                            hit.getX(), hit.getY(), hit.getZ(), 18, 0.3, 0.3, 0.3, 0.05);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable t) {
            BossLog.warn("Katarina 弹反结算异常: " + t);
        }
    }

    /** 点到线段距离平方（原脚本 {@code kataPointSegDist2}）。 */
    private static double pointSegDist2(double px, double py, double pz,
                                        double ax, double ay, double az,
                                        double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double apx = px - ax;
        double apy = py - ay;
        double apz = pz - az;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = (len2 > 0.0001d) ? ((apx * abx + apy * aby + apz * abz) / len2) : 0;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        double cx = ax + abx * t - px;
        double cy = ay + aby * t - py;
        double cz = az + abz * t - pz;
        return cx * cx + cy * cy + cz * cz;
    }

    // ==================================================================
    // 剑气风暴（原脚本 spawnKatarinaStormWave / updateKatarinaStorms）
    // ==================================================================

    /** 风暴间隔（刻）：15 秒起步，每轮 −2 秒，最快 5 秒。 */
    private int stormInterval() {
        return Math.max(STORM_INTERVAL_MIN, STORM_INTERVAL - cycle * 40);
    }

    /** 生成一波剑气：整波同轴同向，从一端切向另一端。 */
    private void spawnStormWave() {
        try {
            BossArena.Region region = state.arena.twoRegion;
            if (region == null) return;
            int n = Math.min(STORM_MAX_COUNT, 2 + cycle);
            double speed = Math.min(STORM_SPEED_MAX, 0.55d + cycle * 0.08d);
            double gap = 6 + cycle * 2;
            List<Storm> placed = new ArrayList<>();
            // 一次只出一个方向：整波剑气群要么全是 X 轴扫、要么全是 Z 轴扫
            // （同时来 X 轴和 Z 轴两群几乎没法躲，一轴成群才有「跳/走位绕开」的空间）
            boolean alongX = Math.random() < 0.5;
            int dir = Math.random() < 0.5 ? 1 : -1;
            double minEdge = alongX ? region.minZ() : region.minX();
            double maxEdge = alongX ? region.maxZ() : region.maxX();
            for (int i = 0; i < n; i++) {
                double fixed = 0;
                int tries = 0;
                while (tries < 24) {
                    tries++;
                    fixed = alongX
                            ? region.minX() + 1 + Math.random() * (region.maxX() - region.minX() - 2)
                            : region.minZ() + 1 + Math.random() * (region.maxZ() - region.minZ() - 2);
                    boolean okGap = true;
                    for (Storm s : placed) {
                        if (s.alongX == alongX && Math.abs(s.fixed - fixed) < gap) {
                            okGap = false;
                            break;
                        }
                    }
                    if (okGap) break;
                }
                Storm lane = new Storm();
                lane.alongX = alongX;
                lane.fixed = fixed;
                lane.pos = (dir > 0) ? (minEdge - 1.5) : (maxEdge + 1.5);
                lane.dir = dir;
                lane.speed = speed;
                placed.add(lane);
                storms.add(lane);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 推进全部剑气并做碰撞判定（原脚本 {@code updateKatarinaStorms}）。 */
    private void updateStorms(Player p) {
        try {
            BossArena.Region region = state.arena.twoRegion;
            org.bukkit.World world = state.arena.twoWorld;
            if (region == null || world == null) return;
            List<Storm> keep = new ArrayList<>();
            double floorY = region.minY() + 1;
            for (Storm lane : storms) {
                lane.pos += lane.speed * lane.dir;
                double minPos = lane.alongX ? region.minZ() : region.minX();
                double maxPos = lane.alongX ? region.maxZ() : region.maxX();
                // 剑气沿「整条线的长度方向」铺开：alongX=true 时线沿 X 轴，扫动方向是 Z
                double startA = lane.alongX ? (region.minX() + 1) : (region.minZ() + 1);
                double endA = lane.alongX ? (region.maxX() - 1) : (region.maxZ() - 1);
                // 采样点：沿扫动方向 5 层 × 沿整条线长每 1.2 格（与画面粒子同一套坐标）
                // 性能优化：pts 列表原先只服务于碰撞判定——改成直算 + 先做「玩家离
                // 扫动面多远」的快速拒绝，绝大多数 tick 零分配零采样
                Location plNow = null;
                try {
                    plNow = p.getLocation();
                } catch (Throwable ignored) {
                    // 忽略
                }
                double[] collSlices = {-1.0, -0.5, 0, 0.5, 1.0};
                boolean hitNow = false;
                if (!lane.hit && plNow != null && p.isOnline() && !p.isDead()) {
                    double fx = plNow.getX();
                    double fz = plNow.getZ();
                    double fy = plNow.getY();
                    // 快速拒绝：玩家到扫动面的距离超过 2.5 格（最厚 slice 1.0 + 命中半径）就没必要采样
                    double playerAlong = lane.alongX ? fz : fx;
                    if (Math.abs(playerAlong - lane.pos) <= 2.6d) {
                        int lenSamples = Math.max(2, (int) Math.floor((endA - startA) / 1.2));
                        double span = endA - startA;
                        outer:
                        for (double collSlice : collSlices) {
                            double alongPos = lane.pos + collSlice;
                            for (int s = 0; s <= lenSamples; s++) {
                                double alongLen = startA + span * (s / (double) lenSamples);
                                double px = lane.alongX ? alongLen : alongPos;
                                double pz = lane.alongX ? alongPos : alongLen;
                                double qdx = px - fx;
                                double qdz = pz - fz;
                                if ((qdx * qdx + qdz * qdz) > STORM_HIT_SQ) continue;
                                // 粒子贴地一层 ±0.9：跳起来（脚底高于粒子顶）就躲开了
                                if ((floorY + 0.95d) < (fy + STORM_BOTTOM)) continue;
                                if ((floorY + 0.05d) > (fy + STORM_TOP)) continue;
                                hitNow = true;
                                try {
                                    world.spawnParticle(org.bukkit.Particle.CRIT, fx, floorY + 0.5d, fz,
                                            14, 0.3, 0.3, 0.3, 0.12);
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                                break outer;
                            }
                        }
                    }
                }
                if (hitNow) {
                    lane.hit = true;
                    BossFx.directDamage(p, STORM_DAMAGE);
                    BossFx.hurtFeedback(p);
                    try {
                        p.playSound(plNow, "entity.player.attack.sweep", 1.2f, 0.8f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                // 粒子：每层 2 次批量调用沿整条线长铺开粒子墙，玩家附近再加密一层
                double midLen = (startA + endA) / 2;
                double halfLen = (endA - startA) / 2;
                double[] drawSlices = {-1.0, 0, 1.0};
                for (double dPos0 : drawSlices) {
                    double dPos = lane.pos + dPos0;
                    double cxp = lane.alongX ? midLen : dPos;
                    double czp = lane.alongX ? dPos : midLen;
                    double ox = lane.alongX ? halfLen : 0.2;
                    double oz = lane.alongX ? 0.2 : halfLen;
                    try {
                        world.spawnParticle(org.bukkit.Particle.CRIT, cxp, floorY + 0.45, czp,
                                20, ox, 0.35, oz, 0.01);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    if (plNow != null) {
                        double ox2 = lane.alongX ? 5.0 : 0.2;
                        double oz2 = lane.alongX ? 0.2 : 5.0;
                        double cxp2 = lane.alongX ? plNow.getX() : dPos;
                        double czp2 = lane.alongX ? dPos : plNow.getZ();
                        try {
                            world.spawnParticle(org.bukkit.Particle.CRIT, cxp2, floorY + 0.8, czp2,
                                    8, ox2, 0.35, oz2, 0.01);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    try {
                        world.spawnParticle(org.bukkit.Particle.SWEEP_ATTACK,
                                lane.alongX ? midLen : dPos, floorY + 0.95,
                                lane.alongX ? dPos : midLen,
                                1, ox, 0, oz, 0);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                boolean out = (lane.dir > 0) ? (lane.pos > maxPos + 2) : (lane.pos < minPos - 2);
                if (!out) keep.add(lane);
            }
            storms.clear();
            storms.addAll(keep);
        } catch (Throwable t) {
            BossLog.warn("Katarina 剑气风暴推进异常: " + t);
        }
    }

    // ==================================================================
    // 加强甘露 / 掉落物（原脚本 startKatarinaNectar / clearPhaseTwoGroundDrops）
    // ==================================================================

    /** 刷一瓶加强甘露（钉在石英层上；没捡走会换位置，捡走 10 秒后下一瓶）。 */
    private void spawnNectar() {
        try {
            Location loc = BossFx.randomArenaPoint(state, false);
            if (loc == null) return;
            org.bukkit.entity.Item entity = BossPhaseTwoItems.spawnDrop(
                    state, BossPhaseTwoItems.createKatarinaNectar(), loc, true);
            if (entity == null) return;
            nectar = entity;
            nectarAge = 0;
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 清掉场上全部本局掉落物（含 zqx125 阶段残留的甘露/盾）。 */
    private void clearGroundDrops() {
        try {
            for (org.bukkit.entity.Item item : state.phaseTwo.drops) {
                BossState.removeEntity(item);
            }
            state.phaseTwo.drops.clear();
            BossState.removeEntity(state.phaseTwo.shieldEntity);
            state.phaseTwo.shieldEntity = null;
            state.phaseTwo.shieldActive = false;
            state.phaseTwo.shieldTimer = 0;
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 掉落物磁吸 + 剔除（每 4 拍一次，与 zqx125 的光柱任务同口径）。 */
    private void markDrops(Player p) {
        try {
            BossPhaseTwoItems.pruneDrops(state, state.phaseTwo.drops);
            if (state.phaseTwo.drops.isEmpty()) return;
            if (p == null || !p.isOnline()) return;
            BossPhaseTwoItems.magnetize(state, p, state.phaseTwo.drops);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 阶段守护任务（原脚本 startKatarinaTrackingTask）
    // ==================================================================

    /**
     * 本体血量 → BossBar 同步 + 压制御剑 + <b>死亡检测</b>。
     *
     * <p>★ 与原脚本的偏差（有意补洞）：原脚本的 {@code EntityDeathEvent} 只给双子护法接了结算
     * （{@code xuHandleTwinDown} 对非双子直接 return），Katarina 被真实伤害打死时
     * <b>没有任何退场流程</b> —— 场面会卡在一具尸体和一个不再变化的血条上。
     * 这里由本任务发现本体失效即走 {@link #defeat}（{@code defeated} 保证只结算一次；
     * 计数器路径归零时也走同一个方法）。</p>
     */
    private void startTrackingTask() {
        try {
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            trackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated) {
                        BossState.cancelTask(trackTask);
                        trackTask = null;
                        return;
                    }
                    Entity k = slot.body;
                    boolean ok;
                    try {
                        ok = (k != null) && k.isValid();
                    } catch (Throwable err) {
                        ok = false;
                    }
                    if (!ok) {
                        // 本体没了（被真实伤害打死 / 被异常清除）→ 退场结算
                        BossState.cancelTask(trackTask);
                        trackTask = null;
                        Player p = (player != null && player.isOnline())
                                ? player : Bukkit.getPlayerExact(state.playerName);
                        defeat(p);
                        return;
                    }
                    // 本体血量 → BossBar（600 上限，靠玩家真实输出掉血）
                    try {
                        double h = Math.max(0, Math.round(((LivingEntity) k).getHealth()));
                        if (h != slot.hp) {
                            slot.hp = h;
                            updateBarTitle();
                            Player p = (player != null && player.isOnline())
                                    ? player : Bukkit.getPlayerExact(state.playerName);
                            if (p != null) BossFx.updateBarPlayers(state, p.getLocation());
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    tick[0]++;
                    if (tick[0] % 10 == 0) {
                        try {
                            LuxianSword.stopRide(player);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    // 性能优化：剑影清扫 10t → 40t（getNearbyEntities 是 120³ 盒查询；
                    // 残留剑影不参与玩法判定，2 秒一扫完全够）
                    if (tick[0] % 40 == 0) {
                        clearSwordDisplays();
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 5L);
        } catch (Throwable t) {
            BossLog.warn("Katarina 阶段守护任务启动失败: " + t);
        }
    }

    // ==================================================================
    // 血量口径（原脚本 getPhaseTwoGuardianHpNow / setTangranBossGuardianHp）
    // ==================================================================

    /** 本体当前血量（本体有效时读真实血量，否则回落计数器）。 */
    private double entityHp() {
        try {
            Entity g = slot.body;
            if (g != null && g.isValid() && g instanceof LivingEntity le) {
                return Math.max(0, Math.round(le.getHealth()));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return slot.hp;
    }

    /** 把计数器血量同步到本体（本体血量就是 BossBar 血量）。 */
    private void syncEntityHealth(double hp) {
        try {
            Entity g = slot.body;
            if (g != null && g.isValid() && g instanceof LivingEntity le) {
                le.setHealth(Math.max(0.0d, Math.min(MAX_HP, hp)));
            }
        } catch (Throwable ignored) {
            // 本体已消失：计数器口径仍然有效
        }
    }

    /**
     * 计数器伤害：夹到 [0, 600] → 同步本体 → 刷血条 → 归零走退场
     * （原脚本 {@code setTangranBossGuardianHp}）。
     */
    private void applyCounterDamage(double amount) {
        double next = Math.max(0.0d, Math.min(slot.maxHp, Math.round(entityHp() - Math.max(0, amount))));
        slot.hp = next;
        if (next <= 0.0d) {
            updateBarTitle();
            defeat(player);
            return;
        }
        syncEntityHealth(next);
        updateBarTitle();
    }

    /** 刷新护法血条标题与进度（原脚本 {@code updateGuardianBarTitle} 的 Katarina 口径）。 */
    private void updateBarTitle() {
        try {
            if (slot.bar == null) return;
            double max = (slot.maxHp > 0) ? slot.maxHp : MAX_HP;
            double hp = slot.hp;
            slot.bar.setTitle(barTitle());
            slot.bar.setProgress(Math.max(0.0d, Math.min(1.0d, hp / max)));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 血条标题：{@code [天裁]Katarina [n/600]}（封号红 + 名字黄，血量紫/上限绿）。 */
    private String barTitle() {
        int n = (int) Math.max(0, Math.min(MAX_HP, Math.round(slot.hp)));
        return "§c§l[天裁]§e§lKatarina §f[§d" + n + "§f/§a" + (int) MAX_HP + "§f]";
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /** 玩家增益：速度 III + 跳跃提升 I（背包栏状态区真实显示）。 */
    private void applyBuffs(Player p) {
        try {
            if (p == null || !p.isOnline() || p.isDead()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    BUFF_TICKS, 2, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,
                    BUFF_TICKS, 0, false, true, true));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 清掉场地内残留的戮仙剑剑影（原脚本 {@code clearLuxianSwordDisplays}）。 */
    private void clearSwordDisplays() {
        try {
            org.bukkit.World world;
            Location center;
            if (player != null && player.isOnline()) {
                world = player.getWorld();
                center = player.getLocation();
            } else if (state.arena.twoWorld != null && state.arena.twoCenter != null) {
                world = state.arena.twoWorld;
                center = new Location(world, state.arena.twoCenter.getX() + 0.5,
                        state.arena.twoCenter.getY() + 1, state.arena.twoCenter.getZ() + 0.5);
            } else {
                return;
            }
            BossArena.Region region = state.arena.twoRegion;
            int removed = 0;
            for (Entity e : world.getNearbyEntities(center, 60, 60, 60)) {
                if (!(e instanceof ItemDisplay display)) continue;
                // 性能优化：先做廉价的区域裁剪，再做身份判定（ItemDisplay 本就稀少）
                if (region != null) {
                    Location loc = e.getLocation();
                    if (loc.getX() < region.minX() - 3 || loc.getX() > region.maxX() + 3
                            || loc.getZ() < region.minZ() - 3 || loc.getZ() > region.maxZ() + 3
                            || loc.getY() < region.minY() - 5 || loc.getY() > region.maxY() + 5) {
                        continue;
                    }
                }
                // 只认粘液 id（硬约定 #3）：原实现按展示物物品的显示名 contains「戮仙剑」
                // 判定，会误删玩家自己放的、展示带该字样物品的 ItemDisplay；且真剑若被
                // 铁砧改名反而漏删。展示物持的就是真剑物品本身，走 canonical 判定即可。
                if (!LuxianSword.isSword(display.getItemStack())) continue;
                BossState.removeEntity(display);
                removed++;
            }
            if (removed > 0) BossLog.warn("清理残留戮仙剑剑影 x" + removed);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** Katarina 的装备：肋骨纹 + 绿宝石纹饰的紫色皮革，不可破坏。 */
    private static ItemStack katarinaArmor(Material material) {
        try {
            ItemStack item = new ItemStack(material, 1);
            var meta = item.getItemMeta();
            if (meta != null) {
                try {
                    if (meta instanceof LeatherArmorMeta leather) {
                        leather.setColor(Color.fromRGB(128, 40, 200));
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    if (meta instanceof ArmorMeta armor) {
                        armor.setTrim(new ArmorTrim(TrimMaterial.EMERALD, TrimPattern.RIB));
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                meta.setUnbreakable(true);
                item.setItemMeta(meta);
            }
            return item;
        } catch (Throwable t) {
            return new ItemStack(material, 1);
        }
    }

    /** 这个战局还是「当前局」吗（定时任务自检守卫的统一口径）。 */
    private boolean isMine() {
        return BossFight.byKey(key) == state;
    }

    /** 排一条对白（记到 dialogueTasks，收场时一起收）。 */
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

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    /** 一门在飞的火球（原脚本 {@code kataFireballs} 的元素）。 */
    private static final class Fireball {
        Entity entity;
        boolean deflected;
        int age;
        /** 上一帧位置与速度（火球被原版提前移除时的补判用；null = 还没有上一帧）。 */
        Double lastX;
        Double lastY;
        Double lastZ;
        double vx;
        double vy;
        double vz;
    }

    /** 火球开阵状态（原脚本 {@code kataFireCharge}）。 */
    private static final class Charge {
        int tick;
        boolean firing;
        int left;
    }

    /** 一道剑气车道（原脚本 {@code kataStorms} 的元素）。 */
    private static final class Storm {
        /** true = 线沿 X 轴铺开、扫动方向是 Z。 */
        boolean alongX;
        /** 车道在「长度方向」上的固定坐标。 */
        double fixed;
        /** 扫动方向上的当前位置。 */
        double pos;
        /** 1 / -1。 */
        int dir;
        /** 每刻移动格数。 */
        double speed;
        /** 是否已经伤过玩家（一道剑气只结算一次）。 */
        boolean hit;
    }
}
