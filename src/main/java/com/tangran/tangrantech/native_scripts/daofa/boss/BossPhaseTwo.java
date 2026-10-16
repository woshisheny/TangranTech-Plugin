package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;
import com.tangran.tangrantech.native_scripts.daofa.YanhuoSelectionScript;

/**
 * 二阶段门面：<b>一阶段成功 → 落地 → 对白/选项 → 清背包上锁 → 戮仙剑序列 → 51×51×20 石英场
 * → 召唤仪式 → zqx125 护法战</b>。
 *
 * <h2>这一批把什么接上了</h2>
 * <p>前三批（战局层 / 护法层 / 一阶段）各自都能跑，但<b>彼此不相连</b>：
 * 一阶段通过后只留一句日志；护法注册表里躺着 zqx125 却没有召唤方；
 * {@link GuardianManager#install} 自落地起就没有调用方。本类就是缺失的那条链：</p>
 * <pre>
 * BossPhaseOne.handleSuccess
 *   → beginFromPhaseOne            缓降 + 落地检测
 *   → startIntroDialogue           两句对白 + 烟花 + 【二阶段背包提示】+ √/×
 *   → handleAccept                 beginPhaseTwoInventoryBackup（失败可重试）
 *   → startProper                  紫血条 100/100 + 规则旁白
 *   → startChallenge               上锁 / 清异火 / 硬关糖帝 / 饱和 / 收御剑
 *   → beginLuxianSwordSequence     糖然抽剑花 20 拍 → 飞剑投掷 → 四句介绍
 *   → startCombat                  combatActive = true（护法机制任务的自检闸门）
 *   → startArenaSequence           传送门特效 → 选址 → 分批建场（400 块/刻）→ 传送入场
 *   → startSummonSequence          张手 → 双手白线 → 六芒星法阵转 3 秒
 *   → GuardianManager.install      Zqx125Guardian.spawn（射箭/雷击/铁砧/道具/光柱）
 * </pre>
 *
 * <h2>三条硬约束（原脚本用注释标过，这里用结构保证）</h2>
 * <ol>
 *   <li><b>{@code combatActive} 必须由本类在建场地之前置位</b> ——
 *       置位点在原脚本是 {@code startPhaseTwoCombat} / {@code startGuardianQuickStart}，
 *       而 {@link Zqx125Guardian} 的五个机制任务第一个自检就是它。
 *       不置位 ⇒ 护法召出来了但什么都不干（本类 {@link #startCombat} 第一行就是它）。</li>
 *   <li><b>背包备份失败时把 {@code choiceMade} 还回去</b> ——
 *       {@link BossInventory#beginBackup} 发现旧备份还不了会拒绝本次备份，
 *       这时必须让玩家能再点一次 √，而不是「点了没反应」。</li>
 *   <li><b>同一事件同优先级不合并</b> —— 本类不注册任何监听器；
 *       二阶段需要的事件（防收纳/禁交互/掉命）属于批次 8，届时按「事件+优先级」拆方法。</li>
 * </ol>
 *
 * <h2>与 Katarina / 双子 / 三·四阶段的接缝</h2>
 * <p>zqx125 退场由 {@link Zqx125Guardian#defeat} 播完四句台词，
 * 200 刻后调 {@link #arriveNext}。批次 5 用 {@link #setNextArrival} 接入
 * Katarina 的蓝色阵法登场；在接入之前，{@link #arriveNext} 如实收场并提示，
 * 而不是让玩家对着一具空场地发呆。</p>
 */
public final class BossPhaseTwo {

    private BossPhaseTwo() {}

    // ==================================================================
    // 常量（原脚本 1844-1846 / 2847-2857 行）
    // ==================================================================

    /** 场地半宽（2×25+1 = 51）。 */
    private static final int ARENA_HALF = 25;

    /** 场地高度（含地板层与天花板层）。 */
    private static final int ARENA_HEIGHT = 20;

    /** 召唤仪式：双手粒子线的推进速度（格/刻，0.2 = 4 格/秒）。 */
    private static final double LINE_SPEED = 0.2d;

    /** 召唤仪式：六芒星旋转时长（刻，60 = 3 秒）。 */
    private static final int STAR_TICKS = 60;

    /** 召唤仪式：六芒星半径（6×6）。 */
    private static final double STAR_RADIUS = 3.0d;

    /** 建场每批放置的方块数。 */
    private static final int BUILD_BATCH = 400;

    /** 建场粒子：最多保留多少个发光点。 */
    private static final int MAX_SPARKS = 30;

    /** 抽剑花总拍数（每拍 3 刻，共 60 刻 = 3 秒）。 */
    private static final int FLOURISH_TICKS = 20;

    /** 飞剑投掷每刻最大步长（格）。 */
    private static final double SWORD_STEP = 0.9d;

    /** 飞剑命中玩家的距离阈值。 */
    private static final double SWORD_HIT_DIST = 1.3d;

    /** 二阶段锁续周任务周期（刻，10 = 0.5 秒，与原脚本一致）。 */
    private static final int LOCK_WATCH_PERIOD = 10;

    /** 烟花的 9 个颜色（原脚本 SPIRAL_COLORS，一阶段/二阶段共用）。 */
    private static final Color[] PARTY_COLORS = {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE,
    };

    // ==================================================================
    // 批次 5 接缝：下一位护法的登场序列
    // ==================================================================

    /**
     * 护法退场后的「登场序列」钩子。
     *
     * <p>原脚本这里是 {@code defeatPhaseTwoGuardian} 里一句
     * {@code scheduleAction(state, 200, () -> startKatarinaArrivalSequence(...))}。
     * Java 侧不能让二阶段门面直接依赖还没写的 {@code BossKatarina}，
     * 所以抽成钩子：批次 5 实现后 {@link #setNextArrival} 一次接入。</p>
     */
    public interface NextArrival {
        /**
         * 下一位护法登场。
         *
         * @param state      本局状态
         * @param player     本局玩家（可能不在线：读档直开时也要能跑）
         * @param guardianId 即将登场的护法 id（{@code Katarina} / {@code D_RKIN}）
         */
        void arrive(BossState state, Player player, String guardianId);
    }

    private static volatile NextArrival nextArrival;

    /** 注册下一位护法的登场序列（批次 5 调用；只应注册一次）。 */
    public static void setNextArrival(NextArrival impl) {
        nextArrival = impl;
    }

    // ==================================================================
    // 批次 7 接缝：糖然亲自下场（三阶段决斗）
    // ==================================================================

    /**
     * 双子全部倒地后「糖然下场决斗」的钩子（原脚本 {@code startTangranDuel}）。
     *
     * <p>与 {@link NextArrival} 分开是因为它<b>不是护法登场</b>：双子倒地 =
     * 阶段二（护法）通关，接下来是糖然本人变成僵尸形态下场（三阶段，批次 7）。
     * 批次 7 用 {@link #setTangranDuelStart} 接入；未接入时如实收场。</p>
     */
    public interface TangranDuelStart {
        void start(BossState state, Player player);
    }

    private static volatile TangranDuelStart duelStart;

    /** 注册糖然下场决斗（批次 7 调用）。 */
    public static void setTangranDuelStart(TangranDuelStart impl) {
        duelStart = impl;
    }

    /** 呼叫糖然下场决斗（双子全倒时由 {@code BossTwin.defeat} 调用）。 */
    public static void startTangranDuel(BossState state, Player player) {
        TangranDuelStart impl = duelStart;
        if (impl != null) {
            try {
                impl.start(state, player);
                return;
            } catch (Throwable t) {
                BossLog.warn("糖然下场决斗异常: " + t);
            }
        }
        BossLog.warn("糖然下场决斗尚未接入（批次 7）");
        try {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c[糖然科技] 糖然亲自下场的决斗（三阶段）尚未原生化完成，"
                        + "本局在此收场，背包已返还。");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossBattleScript.forceStop(state, "糖然决斗未实现（批次 7）");
    }

    /**
     * 呼叫下一位护法的登场序列。
     *
     * <p>没有接入时（批次 5 之前）如实收场：提示 + {@link BossBattleScript#forceStop}。
     * 收场会还背包、摘血条、解锁 —— 玩家回到可自由行动的状态，
     * 比「场地还在、护法不来、锁还挂着」的假活状态好。</p>
     */
    public static void arriveNext(BossState state, Player player, String guardianId) {
        NextArrival impl = nextArrival;
        if (impl != null) {
            try {
                impl.arrive(state, player, guardianId);
                return;
            } catch (Throwable t) {
                BossLog.warn("下一位护法登场序列异常（" + guardianId + "）：" + t);
            }
        }
        BossLog.warn("护法退场后的登场序列尚未接入（批次 5）：" + guardianId);
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

    // ==================================================================
    // 一阶段成功后的衔接
    // ==================================================================

    /**
     * 一阶段挑战成功后的入口（原脚本 {@code handlePhaseOneSuccess} 的最后一行）。
     *
     * <p>糖然留在原地，等玩家缓降落地后再进二阶段 —— 直接切流程会让
     * 「还在空中飞」的玩家错过整段开场对白。</p>
     */
    public static void beginFromPhaseOne(BossState state, Player player) {
        startLandingWatcher(state, player);
    }

    /** 缓降 + 落地检测（原脚本 {@code startPhaseTwoLandingWatcher}）。 */
    private static void startLandingWatcher(BossState state, Player player) {
        try {
            giveSlowFallingUntilGround(player);
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.cutscene.landingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state)) {
                        BossState.cancelTask(state.cutscene.landingTask);
                        state.cutscene.landingTask = null;
                        return;
                    }
                    Entity stand = state.stand;
                    if (stand == null || !stand.isValid()) {
                        BossState.cancelTask(state.cutscene.landingTask);
                        state.cutscene.landingTask = null;
                        return;
                    }
                    if (player == null || !player.isOnline() || player.isDead()) {
                        BossState.cancelTask(state.cutscene.landingTask);
                        state.cutscene.landingTask = null;
                        return;
                    }
                    if (!player.isOnGround()) return;

                    BossState.cancelTask(state.cutscene.landingTask);
                    state.cutscene.landingTask = null;
                    try {
                        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    try {
                        player.setFallDistance(0f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }

                    // 糖然挪到玩家前方 8 格，正面对他（原脚本：落地后糖然走近致敬）
                    Location base = player.getLocation();
                    org.bukkit.util.Vector dir = base.getDirection();
                    if (dir.lengthSquared() < 0.001d) dir = new org.bukkit.util.Vector(0, 0, 1);
                    else dir = dir.normalize();
                    Location front = base.clone().add(dir.multiply(8));
                    front.setY(base.getY());
                    try {
                        stand.teleport(front);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    faceSummoner(stand, player);

                    applaud(state, player);
                    scheduleAction(state, 40L, () -> startIntroDialogue(state, player));
                } catch (Throwable t) {
                    BossState.cancelTask(state.cutscene.landingTask);
                    state.cutscene.landingTask = null;
                }
            }, 10L, 5L);
        } catch (Throwable t) {
            BossLog.warn("二阶段降落检测启动失败: " + t);
        }
    }

    /**
     * 二阶段开场对白 + 背包提示 + √/× 选项（原脚本 {@code startPhaseTwoIntroDialogue}）。
     *
     * <p>选项阶段名 {@code phase2_options} 是 {@link BossFightEntry#handleAccept}
     * 的路由依据 —— 同一个 √ 按钮，在阶段一开头是「开打」，在这里是「清背包进二阶段」。</p>
     */
    public static void startIntroDialogue(BossState state, Player player) {
        try {
            state.choiceStage = "phase2_intro";
            BossFx.sendLine(player, "§c[糖然]§b§l不错的反应速度,算你过关");
            startFireworkCelebration(state, player);
            scheduleLine(state, player, 40L, "§c[糖然]§b§l不过，第二阶段要来了哦");
            scheduleAction(state, 80L, () -> {
                state.choiceMade = false;
                state.choiceStage = "phase2_options";
                sendPhaseTwoInventoryWarning(player);
                BossFightEntry.sendChoiceOptions(player);
            });
        } catch (Throwable t) {
            BossLog.warn("二阶段开场对白失败: " + t);
        }
    }

    /** 二阶段背包提示（原脚本 {@code sendPhaseTwoInventoryWarning}，六行原文照抄）。 */
    private static void sendPhaseTwoInventoryWarning(Player player) {
        try {
            player.sendMessage("§e§l【二阶段背包提示】");
            player.sendMessage("§f进入二阶段前会暂时清空你的背包。");
            player.sendMessage("§f在挑战失败或者进入下一阶段的时候，物品会返还给您。");
            player.sendMessage("§f若您实在担忧，可以把背包内的物品妥善储存在箱子等容器中。");
            player.sendMessage("§c注意：进入二阶段后，无法与任何方块/界面交互，无法丢弃物品，"
                    + "丹药属性开关会被强制关闭，异火选择会被清除（挑战后可重新选择）。");
            player.sendMessage("§a您可以点击 [√] 开始挑战，也可以点击 [×] 放弃挑战。");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * √：备份背包 → 进入二阶段正流程（原脚本 {@code handleAccept} 的 {@code phase2_options} 分支）。
     *
     * @return true = 已开始；false = 备份失败（调用方应把 {@code choiceMade} 还回去让玩家重试）
     */
    public static boolean handleAccept(BossState state, Player player) {
        if (!BossInventory.beginBackup(player, state)) {
            try {
                player.sendMessage("§c背包备份失败，挑战未开始，请稍后重试。");
            } catch (Throwable ignored) {
                // 忽略
            }
            return false;
        }
        startProper(state, player);
        return true;
    }

    /** ×：一句「可惜，」然后收场（原脚本 {@code handleDecline} 的 {@code phase2_options} 分支）。 */
    public static void handleDecline(BossState state, Player player) {
        BossFx.sendLine(player, "§c[糖然]§b§l可惜，");
        scheduleAction(state, 40L, () -> fail(state, player, "你选择了放弃第二阶段。"));
    }

    // ==================================================================
    // 二阶段正流程
    // ==================================================================

    /**
     * 二阶段开始：紫血条 + BGM + 规则旁白（原脚本 {@code startPhaseTwoProper}）。
     *
     * <p>旁白六句全部与二阶段实际限制对齐（禁交互/禁界面/禁丢弃/压丹药/清异火）——
     * 原脚本注释特意标注「规则旁白：与二阶段实际限制保持一致」，改限制时要同步改这里。</p>
     */
    public static void startProper(BossState state, Player player) {
        try {
            state.choiceStage = "phase2";
            state.tangran.hp = BossState.PHASE_TWO_MAX_HP;
            try {
                if (state.bar != null) {
                    state.bar.setColor(org.bukkit.boss.BarColor.PURPLE);
                    state.bar.setVisible(true);
                }
            } catch (Throwable ignored) {
                // 血条可能还没建（读档直开由入口建）
            }
            refreshBar(state);
            Location at = (state.stand != null) ? state.stand.getLocation() : player.getLocation();
            BossFx.updateBarPlayers(state, at);

            BossMusic.startPhaseTwo(state, player.getLocation(), state.phaseTwo.musicFile);

            BossFx.sendLine(player, "§c[糖然]§b§l有趣");
            scheduleLine(state, player, 40L, "§c[糖然]§b§l接下来，则是战斗技巧考验");
            scheduleLine(state, player, 80L,
                    "§c[糖然]§b§l规矩先说清楚：挑战期间，禁止与任何方块交互，箱子、机器一律免谈。");
            scheduleLine(state, player, 120L, "§c[糖然]§b§l禁止打开任何界面，也禁止丢弃任何物品。");
            scheduleLine(state, player, 160L, "§c[糖然]§b§l你体内的丹药之力已被我压制，属性开关全部失效。");
            scheduleLine(state, player, 200L, "§c[糖然]§b§l你的异火已被我抹去，挑战期间生效不了，结束后可重新选择。");
            scheduleAction(state, 260L, () -> startChallenge(state, player));
        } catch (Throwable t) {
            BossLog.warn("二阶段正流程启动失败: " + t);
        }
    }

    /**
     * 二阶段挑战启动：上锁 / 清异火 / 硬关糖帝 / 饱和 / 收御剑 → 抽剑序列
     * （原脚本 {@code startPhaseTwoChallenge}）。
     *
     * <p>顺序有讲究：<b>先置 {@code phaseTwoStarted} 再上锁</b> ——
     * {@link BossPhaseTwoItems#handleInteract} 的入口判据就是它，
     * 置位晚了会出现「锁已生效但甘露点不动」的半程状态。</p>
     */
    public static void startChallenge(BossState state, Player player) {
        try {
            if (!isCurrent(state)) return;
            prepareChallenge(state, player, true, true);
        } catch (Throwable t) {
            BossLog.warn("二阶段挑战启动失败: " + t);
        }
    }

    /**
     * 二阶段准备的公共部分（原脚本 {@code startPhaseTwoChallenge} /
     * {@code startGuardianQuickStart} / 三·四阶段读档直开逐条相同的那一段）。
     *
     * @param withFlourish true = 走抽剑花 + 飞剑投掷序列（正常流程）；
     *                     false = 不演出发剑（读档直开）
     * @param grantSword   true = 发戮仙剑；false = 只收剑不发（四阶段过场用）
     */
    static void prepareChallenge(BossState state, Player player, boolean withFlourish, boolean grantSword) {
        state.tangran.phaseTwoStarted = true;

        // 两道锁 + 两个「硬关」：锁掉容器交互、写对方存档关掉属性开关
        BossChallengeState.setLock(player, true);
        YanhuoSelectionScript.challengeClear(player);
        RealmScript.challengeLock(player, true);
        // 双子阶段的属性加成只在双子阶段生效（账本购买记录保留）—— 属批次 6，此处无需动作
        RealmScript.forceOff(player);

        // 「挑战中」标记 + 0.5 秒续期（储物空间/异火/境界脚本靠它判断能不能开界面）
        BossChallengeState.markActive(state.playerName);
        startLockWatcher(state, player);

        // 饱和：挑战期间不会饿到掉血（回血上限仍由道具控制）
        applySaturation(player);
        // 生命回复限制已移除（原脚本的禁疗任务整个删掉了，这里连句柄都不留）

        // 进二阶段前若在御剑（脚踩戮仙剑），先收掉，避免和新发的剑打架/留剑影
        try {
            LuxianSword.stopRide(player);
        } catch (Throwable ignored) {
            // 装备脚本未加载 / 未骑乘：忽略
        }

        // 提前解析一次 zqx125 头颅：把「拿皮肤」可能得联网等待挪到召唤前
        try {
            BossGuardianSkin.head(BossState.GID_ZQX125, null, null);
        } catch (Throwable ignored) {
            // 皮肤解析失败时 summon 里会再走一次兜底通道
        }

        if (!grantSword) {
            return;   // 四阶段过场：只收剑不发（背包结束时会原样返还）
        }
        if (withFlourish) {
            beginSwordSequence(state, player);
        } else {
            LuxianSword.giveSword(player);
            state.phaseTwo.swordGranted = true;
        }
    }

    /** 二阶段锁续期（原脚本 {@code startBossLockWatcher}：每 0.5 秒续一次）。 */
    private static void startLockWatcher(BossState state, Player player) {
        try {
            BossState.cancelTask(state.phaseTwo.lockWatcher);
            state.phaseTwo.lockWatcher = null;
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.phaseTwo.lockWatcher = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state)) {
                        BossState.cancelTask(state.phaseTwo.lockWatcher);
                        state.phaseTwo.lockWatcher = null;
                        BossChallengeState.clear(state.playerName);
                        return;
                    }
                    // 锁可能在重载/异常里弄丢：每拍确保一次，并续期「挑战中」窗口
                    BossChallengeState.setLock(player, true);
                    BossChallengeState.markActive(state.playerName);
                    RealmScript.forceOff(player);
                } catch (Throwable ignored) {
                    // 忽略：下一拍还会再来
                }
            }, 1L, LOCK_WATCH_PERIOD);
        } catch (Throwable t) {
            BossLog.warn("二阶段锁续期启动失败: " + t);
        }
    }

    // ==================================================================
    // 戮仙剑序列（原脚本 beginLuxianSwordSequence / throwLuxianSword / startPhaseTwoSwordIntro）
    // ==================================================================

    /** 糖然抽剑花：主手换戮仙剑，20 拍（每拍 3 刻）刀花后掷向玩家。 */
    private static void beginSwordSequence(BossState state, Player player) {
        try {
            Entity stand = state.stand;
            if (stand == null || !stand.isValid()) return;
            try {
                if (stand instanceof ArmorStand as) {
                    as.getEquipment().setItemInMainHand(LuxianSword.make());
                }
            } catch (Throwable ignored) {
                // 装备槽设置失败不影响后续（只是少一段演出）
            }

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            state.sword.flourishTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state)) {
                        BossState.cancelTask(state.sword.flourishTask);
                        state.sword.flourishTask = null;
                        return;
                    }
                    Entity cur = state.stand;
                    if (cur == null || !cur.isValid() || !(cur instanceof ArmorStand as)) {
                        BossState.cancelTask(state.sword.flourishTask);
                        state.sword.flourishTask = null;
                        return;
                    }
                    // 刀花：右臂正弦摆动，左臂半举
                    double angle = Math.sin(tick[0] * 0.9d) * 0.9d;
                    as.setRightArmPose(new EulerAngle(-Math.PI / 2 + angle * 0.3, 0, angle));
                    as.setLeftArmPose(new EulerAngle(-Math.PI / 4, 0, -0.4));
                    tick[0]++;
                    if (tick[0] >= FLOURISH_TICKS) {
                        BossState.cancelTask(state.sword.flourishTask);
                        state.sword.flourishTask = null;
                        try {
                            as.setRightArmPose(EulerAngle.ZERO);
                            as.setLeftArmPose(EulerAngle.ZERO);
                            as.getEquipment().setItemInMainHand(null);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        throwSword(state, player);
                    }
                } catch (Throwable t) {
                    BossState.cancelTask(state.sword.flourishTask);
                    state.sword.flourishTask = null;
                }
            }, 3L, 3L);
        } catch (Throwable t) {
            BossLog.warn("戮仙剑抽剑失败: " + t);
        }
    }

    /** 飞剑投掷：ItemDisplay 沿直线飞向玩家眼睛，命中即发剑（原脚本 {@code throwLuxianSword}）。 */
    private static void throwSword(BossState state, Player player) {
        try {
            Entity stand = state.stand;
            if (stand == null || !stand.isValid()) {
                LuxianSword.giveSword(player);
                state.phaseTwo.swordGranted = true;
                startSwordIntro(state, player);
                return;
            }
            Location startLoc = stand.getLocation().clone().add(0, 1.5d, 0);
            org.bukkit.entity.ItemDisplay display = LuxianSword.spawnDisplay(player.getWorld(), startLoc);
            if (display == null) {
                // 展示实体刷不出来：直接发剑，别卡流程
                LuxianSword.giveSword(player);
                state.phaseTwo.swordGranted = true;
                startSwordIntro(state, player);
                return;
            }
            state.sword.projectileDisplay = display;

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.sword.projectileTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state) || !display.isValid()) {
                        BossState.cancelTask(state.sword.projectileTask);
                        state.sword.projectileTask = null;
                        return;
                    }
                    Location loc = display.getLocation();
                    Location target = player.getEyeLocation();
                    double dx = target.getX() - loc.getX();
                    double dy = target.getY() - loc.getY();
                    double dz = target.getZ() - loc.getZ();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < SWORD_HIT_DIST) {
                        BossState.cancelTask(state.sword.projectileTask);
                        state.sword.projectileTask = null;
                        BossState.removeEntity(display);
                        state.sword.projectileDisplay = null;
                        LuxianSword.giveSword(player);
                        state.phaseTwo.swordGranted = true;
                        startSwordIntro(state, player);
                        return;
                    }
                    double step = Math.min(SWORD_STEP, dist);
                    Location newLoc = loc.clone().add(
                            dx / dist * step, dy / dist * step, dz / dist * step);
                    newLoc.setYaw((float) (Math.atan2(-dx, dz) * 180.0d / Math.PI - 45));
                    newLoc.setPitch((float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0d / Math.PI));
                    display.teleport(newLoc);
                } catch (Throwable t) {
                    BossState.cancelTask(state.sword.projectileTask);
                    state.sword.projectileTask = null;
                }
            }, 1L, 1L);
        } catch (Throwable t) {
            BossLog.warn("飞剑投掷失败: " + t);
            LuxianSword.giveSword(player);
            state.phaseTwo.swordGranted = true;
            startSwordIntro(state, player);
        }
    }

    /** 戮仙剑四句介绍 → 200 刻后开打（原脚本 {@code startPhaseTwoSwordIntro}）。 */
    private static void startSwordIntro(BossState state, Player player) {
        try {
            scheduleLine(state, player, 0L, "§c[糖然]§b§l此剑名为戮仙剑。");
            scheduleLine(state, player, 40L, "§c[糖然]§b§l攻击目标会引落无焰雷霆。");
            scheduleLine(state, player, 80L, "§c[糖然]§b§l右键可化剑飞行，WASD 移动。");
            scheduleLine(state, player, 120L, "§c[糖然]§b§l前后移动时，高度会跟随你的准星。");
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.sword.introTask = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> startCombat(state, player), 200L);
        } catch (Throwable t) {
            BossLog.warn("戮仙剑介绍失败: " + t);
        }
    }

    /**
     * 二阶段战斗开始（原脚本 {@code startPhaseTwoCombat}）。
     *
     * <p>★ 本方法第一行就是 {@code combatActive = true} —— 这是护法五个机制任务的
     * 自检闸门（见类注释硬约束 1）。原脚本的置位点同样在构场之前。</p>
     */
    public static void startCombat(BossState state, Player player) {
        try {
            if (!isCurrent(state)) return;
            state.phaseTwo.combatActive = true;
            try {
                player.sendMessage("§c§l二阶段战斗开始！");
            } catch (Throwable ignored) {
                // 忽略
            }
            startArenaSequence(state, player, () -> startSummonSequence(state, player, null));
        } catch (Throwable t) {
            BossLog.warn("二阶段战斗启动失败: " + t);
        }
    }

    // ==================================================================
    // 二阶段场地（51×20×51，石英地板 + 屏障外壳）
    // ==================================================================

    /**
     * 传送门特效 → 20 刻后选址 → 分批建造 → 传送入场 → {@code onDone}
     * （原脚本 {@code startPhaseTwoArenaSequence}）。
     */
    public static void startArenaSequence(BossState state, Player player, Runnable onDone) {
        try {
            Location anchor = player.getLocation().clone();
            playPortalEffect(player.getWorld(), anchor);
            try {
                player.sendMessage("§5§l传送门展开中……");
            } catch (Throwable ignored) {
                // 忽略
            }
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.arena.twoBuildTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (!isCurrent(state)) return;
                    if (player == null || !player.isOnline()) return;
                    Found found = findArena(player);
                    if (found == null) {
                        try {
                            player.sendMessage("§c附近没有足够的空域构建二阶段场地！");
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        return;
                    }
                    startArenaBuild(state, player, found, anchor, onDone);
                } catch (Throwable t) {
                    BossLog.warn("二阶段场地定位失败: " + t);
                }
            }, 20L);
        } catch (Throwable t) {
            BossLog.warn("二阶段传送序列失败: " + t);
        }
    }

    /**
     * 选址：在玩家周围找一块「天然就在所有非空气方块上方」的空域
     * （原脚本 {@code findPhaseTwoArena}）。
     *
     * <p>候选点 5 个（本列 + 四轴各 26 格），取 {@code |cy - 玩家Y|} 最小者；
     * 与「别人正在打的场地」重叠（半宽 +1 的膨胀区）的一律跳过。</p>
     */
    private static Found findArena(Player player) {
        try {
            org.bukkit.World world = player.getWorld();
            Location loc = player.getLocation();
            int px = loc.getBlockX();
            int py = loc.getBlockY();
            int pz = loc.getBlockZ();
            int maxBuildY = world.getLogicalHeight() - (ARENA_HEIGHT + 4);

            int[][] offsets = {{0, 0}, {26, 0}, {-26, 0}, {0, 26}, {0, -26}};
            Found best = null;
            for (int[] off : offsets) {
                int cx = px + off[0];
                int cz = pz + off[1];
                int cy = arenaMaxY(world, cx, cz) + 1;
                if (cy + ARENA_HEIGHT - 1 > maxBuildY) continue;
                if (arenaBlockedByActiveFight(world, cx, cy, cz)) continue;
                int diff = Math.abs(cy - py);
                if (best == null || diff < best.diff()) best = new Found(cx, cy, cz, diff);
            }
            if (best == null) {
                // 兜底：本列最高方块上方
                int cy = arenaMaxY(world, px, pz) + 1;
                if (cy + ARENA_HEIGHT - 1 <= maxBuildY && !arenaBlockedByActiveFight(world, px, cy, pz)) {
                    best = new Found(px, cy, pz, Math.abs(cy - py));
                }
            }
            return best;
        } catch (Throwable t) {
            BossLog.warn("二阶段选址失败: " + t);
            return null;
        }
    }

    /** 一片 (2r+1)² 区域的最高非空气方块 Y（原脚本 {@code getArenaMaxY}）。 */
    private static int arenaMaxY(org.bukkit.World world, int cx, int cz) {
        int maxY = -9999;
        for (int x = cx - ARENA_HALF; x <= cx + ARENA_HALF; x++) {
            for (int z = cz - ARENA_HALF; z <= cz + ARENA_HALF; z++) {
                int y = world.getHighestBlockYAt(x, z);
                if (y > maxY) maxY = y;
            }
        }
        return maxY;
    }

    /** 新场地会不会和「别人正在打的场地」叠在一起（原脚本 {@code arenaBlockedByActiveFight}）。 */
    private static boolean arenaBlockedByActiveFight(org.bukkit.World world, int cx, int cy, int cz) {
        int half = ARENA_HALF + 1;
        int x1 = cx - half, x2 = cx + half;
        int y1 = cy, y2 = cy + ARENA_HEIGHT - 1;
        int z1 = cz - half, z2 = cz + half;
        for (BossState st : BossFight.all()) {
            if (st == null) continue;
            for (BossArena.Region r : st.arena.regions()) {
                // 同世界才判重叠（跨世界两块地永远不叠）
                org.bukkit.World other = (r == st.arena.twoRegion) ? st.arena.twoWorld : st.arena.oneWorld;
                if (other != null && other != world) continue;
                if (x1 <= r.maxX() && x2 >= r.minX()
                        && y1 <= r.maxY() && y2 >= r.minY()
                        && z1 <= r.maxZ() && z2 >= r.minZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 分批建造：每刻放 {@value #BUILD_BATCH} 块，玩家每刻被钉在原地（可转头）
     * （原脚本 {@code startPhaseTwoArenaBuild}）。
     *
     * <p>三个细节：</p>
     * <ul>
     *   <li><b>顶层正中间一格放基岩</b> = 挑战区域标记，「清理挑战区域」脚本靠右键它反推整块场地；</li>
     *   <li><b>装饰实体保护格跳过</b>（展示框/画及其依附格）—— 不跳的话框会被石英块挤掉；</li>
     *   <li><b>已有非空气方块跳过</b> —— 只在空气里长外壳，不破坏既有地形。</li>
     * </ul>
     */
    private static void startArenaBuild(BossState state, Player player, Found found,
                                        Location anchor, Runnable onDone) {
        Plugin plugin = TT.plugin();
        if (plugin == null) return;
        org.bukkit.World world = player.getWorld();
        int cx = found.cx(), cy = found.cy(), cz = found.cz();
        BossArena.Region region = new BossArena.Region(
                cx - ARENA_HALF, cx + ARENA_HALF,
                cy, cy + ARENA_HEIGHT - 1,
                cz - ARENA_HALF, cz + ARENA_HALF);

        // 预计算外壳坐标（按 y 从下到上，看起来像从地板长起来）
        List<int[]> positions = new ArrayList<>();
        for (int y = region.minY(); y <= region.maxY(); y++) {
            boolean onFloorLayer = (y == region.minY());
            boolean onCeilLayer = (y == region.maxY());
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    boolean onShell = onFloorLayer || onCeilLayer
                            || x == region.minX() || x == region.maxX()
                            || z == region.minZ() || z == region.maxZ();
                    if (onShell) positions.add(new int[]{x, y, z, onFloorLayer ? 1 : 0});
                }
            }
        }
        state.arena.twoWorld = world;
        state.arena.twoRegion = region;
        state.arena.twoCenter = new Location(world, cx, cy, cz);
        // 落盘记录：服务器直接重启也能在下次启动时自清理这块场地（每玩家一份，用玩家名命名）
        try {
            BossArena.saveRecord(world, region, cx, cy, cz, state.playerName);
        } catch (Throwable ignored) {
            // 记录写不出去不影响本局，只影响重启后的自清理
        }

        java.util.Set<String> protectedCells = BossArena.collectDecorativeCells(world, region);

        final int[] index = {0};
        final int[] tick = {0};
        state.arena.twoBuildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (!isCurrent(state)) {
                    BossState.cancelTask(state.arena.twoBuildTask);
                    state.arena.twoBuildTask = null;
                    return;
                }
                if (player == null || !player.isOnline()) {
                    BossState.cancelTask(state.arena.twoBuildTask);
                    state.arena.twoBuildTask = null;
                    return;
                }
                tick[0]++;

                // 1) 每刻把玩家拉回原地（保留当前视角）
                try {
                    Location cur = player.getLocation();
                    if (cur.getWorld() == anchor.getWorld() && cur.distanceSquared(anchor) > 0.01d) {
                        Location back = anchor.clone();
                        back.setYaw(cur.getYaw());
                        back.setPitch(cur.getPitch());
                        state.ignoringTeleport = true;
                        player.teleport(back);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }

                // 2) 分批放置外壳
                int placed = 0;
                List<int[]> sparks = new ArrayList<>();
                while (index[0] < positions.size() && placed < BUILD_BATCH) {
                    int[] pos = positions.get(index[0]++);
                    placed++;
                    boolean isMarker = (pos[3] == 0 && pos[1] == region.maxY()
                            && pos[0] == cx && pos[2] == cz);
                    if (!isMarker && protectedCells.contains(BossArena.cellKey(pos[0], pos[1], pos[2]))) {
                        continue;
                    }
                    org.bukkit.block.Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
                    if (!block.getType().isAir()) continue;
                    block.setType(isMarker ? Material.BEDROCK
                            : (pos[3] == 1 ? Material.QUARTZ_BLOCK : Material.BARRIER), false);
                    if (sparks.size() < MAX_SPARKS && index[0] % 25 == 0) sparks.add(pos);
                }

                // 3) 建造粒子 + 音效
                for (int[] sp : sparks) {
                    try {
                        world.spawnParticle(org.bukkit.Particle.PORTAL,
                                sp[0] + 0.5, sp[1] + 0.5, sp[2] + 0.5,
                                3, 0.35, 0.35, 0.35, 0.06);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                if (tick[0] % 5 == 0) {
                    try {
                        world.playSound(anchor, "block.beacon.charge", 0.7f, 1.2f + tick[0] * 0.01f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }

                // 4) 建完 → 传送入场
                if (index[0] >= positions.size()) {
                    BossState.cancelTask(state.arena.twoBuildTask);
                    state.arena.twoBuildTask = null;
                    try {
                        world.playSound(anchor, "block.beacon.activate", 1.0f, 1.0f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    finishArena(state, player, found, onDone);
                }
            } catch (Throwable t) {
                BossLog.warn("二阶段场地建造异常: " + t);
            }
        }, 0L, 1L);
    }

    /**
     * 传送入场：玩家落到靠 +Z 一侧，糖然搬到场地正中央最高处
     * （原脚本 {@code finishPhaseTwoArena}）。
     */
    private static void finishArena(BossState state, Player player, Found found, Runnable onDone) {
        try {
            org.bukkit.World world = player.getWorld();
            int cx = found.cx(), cy = found.cy(), cz = found.cz();
            // 玩家入场点：靠 +Z 一侧（离墙 1.5 格），面朝 -Z（场地中心的糖然）
            Location dest = new Location(world, cx + 0.5, cy + 1, cz + ARENA_HALF - 2.5);
            // 糖然：场地正中央、最高处（留出盔甲架约 2 格高度，避免顶进天花板）
            Location bossLoc = new Location(world, cx + 0.5, cy + ARENA_HEIGHT - 3, cz + 0.5);
            try {
                double dx = bossLoc.getX() - dest.getX();
                double dz = bossLoc.getZ() - dest.getZ();
                dest.setYaw((float) (Math.atan2(-dx, dz) * 180.0d / Math.PI));
                dest.setPitch(0f);
            } catch (Throwable ignored) {
                // 忽略
            }

            try {
                if (state.stand != null && state.stand.isValid()) {
                    bossLoc.setYaw((float) (Math.atan2(-(dest.getX() - bossLoc.getX()),
                            dest.getZ() - bossLoc.getZ()) * 180.0d / Math.PI));
                    bossLoc.setPitch(0f);
                    state.stand.teleport(bossLoc);
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            // 系统传送，别被判成「离开挑战区域」
            state.ignoringTeleport = true;
            try {
                player.teleport(dest);
            } catch (Throwable ignored) {
                // 忽略
            }
            Plugin plugin = TT.plugin();
            if (plugin != null) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> state.ignoringTeleport = false, 20L);
            }

            playPortalEffect(world, dest);
            try {
                player.playSound(dest, "block.portal.trigger", 0.4f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.setFallDistance(0f);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.sendMessage("§b§l二阶段场地已构建完毕！");
            } catch (Throwable ignored) {
                // 忽略
            }
            // 立刻按新场地范围刷新 BossBar 可见玩家（不用等 watchTask 的下一次心跳）
            refreshBar(state);
            BossFx.updateBarPlayers(state, dest);

            if (plugin != null && onDone != null) {
                Bukkit.getScheduler().runTaskLater(plugin, onDone, 10L);
            }
        } catch (Throwable t) {
            BossLog.warn("二阶段场地完成失败: " + t);
        }
    }

    /**
     * 还原二阶段场地：整块清空 + 删落盘记录（原脚本 {@code restorePhaseTwoArena}）。
     *
     * <p>整块清空（连双子阶段的太极图案、玩家在场地里放的方块一起）——
     * 只清屏障/石英会留下玩家放置物。装饰格照旧跳过。</p>
     */
    public static void restoreArena(BossState state) {
        if (state == null) return;
        org.bukkit.World world = state.arena.twoWorld;
        BossArena.Region region = state.arena.twoRegion;
        if (world == null || region == null) return;
        try {
            BossArena.clearAll(world, region, BossArena.collectDecorativeCells(world, region));
        } catch (Throwable t) {
            BossLog.warn("还原二阶段挑战场地失败: " + t);
        }
        state.arena.twoWorld = null;
        state.arena.twoRegion = null;
        state.arena.twoCenter = null;
        state.arena.taijiCenter = null;   // 双子太极阵中心（随场地一起清）
        // 只删自己那份记录（别人的圈地记录不能动）
        try {
            BossArena.deleteRecord(state.playerName);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 召唤仪式（原脚本 startPhaseTwoSummonSequence / summonPhaseTwoGuardian）
    // ==================================================================

    /**
     * 糖然张手 → 双手白色粒子线交汇 → 红色六芒星法阵转 3 秒 → 召唤护法
     * （原脚本 {@code startPhaseTwoSummonSequence}）。
     *
     * <p>召唤落点即 {@code onDone} 里 {@link GuardianManager#install} 的 target：
     * 场地正中央上方 1.1 格。护法不是 zqx125 时（读档直开）走
     * {@link #arriveNext} —— 登场阵法由护法自己的序列负责。</p>
     */
    public static void startSummonSequence(BossState state, Player player, Runnable onDone) {
        try {
            Entity stand = state.stand;
            Location center = state.arena.twoCenter;
            if (stand == null || !stand.isValid() || center == null) return;
            org.bukkit.World world = stand.getWorld();
            Location target = new Location(world, center.getX() + 0.5, center.getY() + 1.1, center.getZ() + 0.5);

            // 1) 双手张开 180°（T 字姿势）
            try {
                if (stand instanceof ArmorStand as) {
                    as.setArms(true);
                    as.setRightArmPose(new EulerAngle(-Math.PI / 2, Math.PI / 2, 0));
                    as.setLeftArmPose(new EulerAngle(-Math.PI / 2, -Math.PI / 2, 0));
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            // 2) 双手起点（肩膀高度，向左右各偏 0.7 格）
            Location standLoc = stand.getLocation();
            double yawRad = standLoc.getYaw() * Math.PI / 180.0d;
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double rightX = -dirZ;
            double rightZ = dirX;
            double armLen = 0.7d;
            double shoulderY = 1.35d;
            double[] rightHand = {
                    standLoc.getX() + rightX * armLen,
                    standLoc.getY() + shoulderY,
                    standLoc.getZ() + rightZ * armLen,
            };
            double[] leftHand = {
                    standLoc.getX() - rightX * armLen,
                    standLoc.getY() + shoulderY,
                    standLoc.getZ() - rightZ * armLen,
            };
            List<double[]> rightTrail = new ArrayList<>();
            List<double[]> leftTrail = new ArrayList<>();
            final String[] phase = {"lines"};
            final int[] starTick = {0};

            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            state.arena.twoSummonTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state)) {
                        BossState.cancelTask(state.arena.twoSummonTask);
                        state.arena.twoSummonTask = null;
                        return;
                    }
                    Entity cur = state.stand;
                    if (cur == null || !cur.isValid()) {
                        BossState.cancelTask(state.arena.twoSummonTask);
                        state.arena.twoSummonTask = null;
                        return;
                    }

                    if ("lines".equals(phase[0])) {
                        boolean rDone = BossFx.advanceTowards(rightHand, target, LINE_SPEED);
                        boolean lDone = BossFx.advanceTowards(leftHand, target, LINE_SPEED);
                        // 记录轨迹，让白色粒子「蔓延」成一条线（而不是只有一个亮点）
                        if (!rDone) rightTrail.add(new double[]{rightHand[0], rightHand[1], rightHand[2]});
                        if (!lDone) leftTrail.add(new double[]{leftHand[0], leftHand[1], leftHand[2]});
                        for (int ri = 0; ri < rightTrail.size(); ri += 3) {
                            double[] p = rightTrail.get(ri);
                            BossFx.dust(world, p[0], p[1], p[2], Color.WHITE, 1.2f);
                        }
                        for (int li = 0; li < leftTrail.size(); li += 3) {
                            double[] p = leftTrail.get(li);
                            BossFx.dust(world, p[0], p[1], p[2], Color.WHITE, 1.2f);
                        }
                        // 线头再补几颗，看起来更亮
                        try {
                            world.spawnParticle(org.bukkit.Particle.DUST,
                                    rightHand[0], rightHand[1], rightHand[2],
                                    3, 0.02, 0.02, 0.02, 0,
                                    new org.bukkit.Particle.DustOptions(Color.WHITE, 1.2f));
                            world.spawnParticle(org.bukkit.Particle.DUST,
                                    leftHand[0], leftHand[1], leftHand[2],
                                    3, 0.02, 0.02, 0.02, 0,
                                    new org.bukkit.Particle.DustOptions(Color.WHITE, 1.2f));
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        if (rDone && lDone) {
                            phase[0] = "star";
                            try {
                                world.playSound(target, "block.enchantment_table.use", 1.0f, 0.8f);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                        return;
                    }

                    if ("star".equals(phase[0])) {
                        double angle = (starTick[0] / (double) STAR_TICKS) * Math.PI * 2.0d;
                        BossFx.drawSixStarCircle(world, target, STAR_RADIUS, angle);
                        starTick[0]++;
                        if (starTick[0] >= STAR_TICKS) {
                            phase[0] = "done";
                            BossState.cancelTask(state.arena.twoSummonTask);
                            state.arena.twoSummonTask = null;
                            String gid = state.guardians.activeId;
                            if (BossState.GID_ZQX125.equalsIgnoreCase(gid)) {
                                GuardianManager.install(state, player, target);
                            } else {
                                // 读档直开后面的护法：登场阵法由它自己的序列负责
                                arriveNext(state, player, gid);
                            }
                            if (onDone != null) {
                                try {
                                    onDone.run();
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    BossLog.warn("二阶段召唤序列异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("二阶段召唤序列启动失败: " + t);
        }
    }

    // ==================================================================
    // 读档直开（原脚本 startGuardianQuickStart，zqx125 份额）
    // ==================================================================

    /**
     * 读档直开「从某位护法开始」：保留清背包 + 上锁 + 饱和 + 发剑的全部准备，
     * 跳过鼓掌/开场对白/规则旁白/剑介绍，直接构场 → 登场
     * （原脚本 {@code startGuardianQuickStart}）。
     *
     * <p>糖然血条按「已打完前面护法」的进度走：Katarina 之前 80/100，
     * 双子之前 60/100（双子退场再扣 40 的那段在批次 6）。</p>
     *
     * <p>★ 与原脚本的一处偏差（有意修正）：原脚本直开 zqx125 时，构场回调会走
     * {@code startKatarinaArrivalSequence}（直接把玩家扔给下一位），
     * 与它自己的注释「选中的护法 = 直接跳到他登场（zqx125 就是默认的第一位）」矛盾。
     * 这里按注释的意图来：<b>选谁就登场谁</b>。</p>
     */
    public static void quickStartGuardian(BossState state, Player player) {
        try {
            if (!isCurrent(state)) return;
            // ★ 见类注释硬约束 1：机制任务的自检闸门必须由本类在建场地之前置位
            state.phaseTwo.combatActive = true;

            String gid = state.guardians.activeId;
            if (!BossState.GID_ZQX125.equalsIgnoreCase(gid)) {
                state.tangran.hp = BossState.isTwinStage(gid) ? 60.0d : 80.0d;
                refreshBar(state);
                BossFx.updateBarPlayers(state, player.getLocation());
            }

            // 1) 清背包（备份 + 清空，和正常流程完全一致）
            try {
                BossInventory.beginBackup(player, state);
            } catch (Throwable t) {
                BossLog.warn("直开护法：背包备份失败 " + t);
            }
            // 2) 上锁 / 清异火 / 硬关糖帝 / 饱和 / 直接发剑（跳过抽剑演出）
            prepareChallenge(state, player, false, true);
            // 3) 音乐 + 构场 → 登场
            BossMusic.startPhaseTwo(state, player.getLocation(), state.phaseTwo.musicFile);
            startArenaSequence(state, player, () -> {
                if (BossState.GID_ZQX125.equalsIgnoreCase(state.guardians.activeId)) {
                    startSummonSequence(state, player, null);
                } else {
                    arriveNext(state, player, state.guardians.activeId);
                }
            });
        } catch (Throwable t) {
            BossLog.warn("读档直开护法失败: " + t);
        }
    }

    /**
     * 二阶段入口的分派（原脚本 {@code startBossFight} 的 {@code startPhase === 2} 尾部）。
     *
     * <p>由 {@link BossFightEntry} 在建好紫血条 / 注视 / 看门任务之后调用。
     * 阶段 3 / 阶段 4 的读档直开由 {@link BossPhaseThreeFour} 承接。</p>
     */
    public static void routeAfterEntry(BossState state, Player player) {
        try {
            if (state.guardians.startFromPhase4) {
                // 读档阶段 4：构场后直接从「借过一下」开始播四阶段过场（战斗场地保留）
                BossPhaseThreeFour.quickStart4(state, player);
                return;
            }
            if (state.guardians.startFromTangran) {
                // 读档阶段 3：直接糖然下场（跳过一次双子）
                BossPhaseThreeFour.quickStart3(state, player);
                return;
            }
            String gid = state.guardians.activeId;
            if (!BossState.GID_ZQX125.equalsIgnoreCase(gid)) {
                if (GuardianManager.isRegistered(gid)) {
                    quickStartGuardian(state, player);
                } else {
                    notReady(state, player, "护法 " + gid + "（批次 5/6）");
                }
                return;
            }
            // zqx125：二阶段开场 —— 先鼓掌，再进入二阶段对白
            applaud(state, player);
            scheduleAction(state, 40L, () -> startIntroDialogue(state, player));
        } catch (Throwable t) {
            BossLog.warn("二阶段入口分派失败: " + t);
        }
    }

    /** 未接入的读档入口：提示 + 收场（不静默，免得玩家以为是「点了没反应」）。 */
    private static void notReady(BossState state, Player player, String what) {
        try {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c[糖然科技] BOSS战 的" + what
                        + "流程尚未原生化完成，本局未能开始，请从阶段 1 挑战。");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossBattleScript.forceStop(state, "读档入口未实现：" + what);
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /** 挑战期间永久饱和，防止饿到掉血（原脚本 {@code applyPhaseTwoSaturation}）。 */
    public static void applySaturation(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            player.removePotionEffect(PotionEffectType.SATURATION);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION,
                    Integer.MAX_VALUE, 0, true, false, false));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 摘掉饱和（收场 / 挑战结束时调，原脚本 {@code clearPhaseTwoSaturation}）。 */
    public static void clearSaturation(Player player) {
        try {
            if (player != null) player.removePotionEffect(PotionEffectType.SATURATION);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 刷新糖然主血条标题与进度（原脚本 {@code updatePhaseTwoBossBarTitle}）。
     *
     *  三阶段（双子打完后糖然下场）换红色「糖然三阶段」标题（原脚本 phaseThreeBarTitle）。
     */
    public static void refreshBar(BossState state) {
        try {
            if (state == null || state.bar == null) return;
            double hp = state.tangran.hp;
            if (state.tangran.phaseThreeStarted) {
                int n = (int) Math.max(0, Math.min(BossState.PHASE_TWO_MAX_HP, Math.round(hp)));
                state.bar.setTitle("§c§l糖然三阶段 §f[§c" + n + "§f/§a"
                        + (int) BossState.PHASE_TWO_MAX_HP + "§f]");
                state.bar.setColor(org.bukkit.boss.BarColor.RED);
            } else {
                state.bar.setTitle(BossFx.phaseTwoBarTitle(hp));
            }
            state.bar.setProgress(Math.max(0.0d, Math.min(1.0d, hp / BossState.PHASE_TWO_MAX_HP)));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 挑战失败：两句提示 + 统一收场（原脚本 {@code failBossFight}）。 */
    public static void fail(BossState state, Player player, String reason) {
        try {
            if (player != null && player.isOnline()) {
                player.sendMessage("§c挑战失败：" + reason);
                player.sendMessage("§e你可以再次点击任务的“开始挑战中章BOSS！”重新挑战。");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossBattleScript.forceStop(state, reason);
    }

    /** 传送门特效（原脚本 {@code playPortalEffect}；音量刻意压低，别盖住战斗 BGM）。 */
    private static void playPortalEffect(org.bukkit.World world, Location loc) {
        try {
            world.spawnParticle(org.bukkit.Particle.PORTAL,
                    loc.getX(), loc.getY() + 1.0, loc.getZ(), 140, 1.5, 1.6, 1.5, 0.15);
            world.playSound(loc, "block.portal.travel", 0.35f, 1.0f);
            world.playSound(loc, "entity.enderman.teleport", 0.30f, 1.2f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 让盔甲架正对玩家（原脚本 {@code faceSummoner}）。 */
    private static void faceSummoner(Entity stand, Player player) {
        try {
            Location eye = player.getEyeLocation();
            Location at = stand.getLocation();
            double dx = eye.getX() - at.getX();
            double dy = eye.getY() - at.getY();
            double dz = eye.getZ() - at.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < 0.0001d && Math.abs(dy) < 0.0001d) return;
            at.setYaw((float) (Math.atan2(-dx, dz) * 180.0d / Math.PI));
            at.setPitch((float) (-Math.atan2(dy, horiz) * 180.0d / Math.PI));
            stand.teleport(at);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 鼓掌：双手有节奏地开合 16 拍（原脚本 {@code applaudArmorStand}）。 */
    private static void applaud(BossState state, Player player) {
        try {
            if (state.cutscene.applauseTask != null) return;
            Entity stand = state.stand;
            if (stand == null || !stand.isValid() || !(stand instanceof ArmorStand as)) return;
            try {
                as.setArms(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] tick = {0};
            state.cutscene.applauseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isCurrent(state)) {
                        BossState.cancelTask(state.cutscene.applauseTask);
                        state.cutscene.applauseTask = null;
                        return;
                    }
                    Entity cur = state.stand;
                    if (cur == null || !cur.isValid() || !(cur instanceof ArmorStand a)) {
                        BossState.cancelTask(state.cutscene.applauseTask);
                        state.cutscene.applauseTask = null;
                        return;
                    }
                    if (tick[0] % 2 == 0) {
                        // 双手叠在一起（向中间合拢）
                        a.setRightArmPose(new EulerAngle(-Math.PI / 2, -Math.PI / 3, 0));
                        a.setLeftArmPose(new EulerAngle(-Math.PI / 2, Math.PI / 3, 0));
                    } else {
                        a.setRightArmPose(new EulerAngle(-Math.PI / 2, Math.PI / 12, 0));
                        a.setLeftArmPose(new EulerAngle(-Math.PI / 2, -Math.PI / 12, 0));
                    }
                    tick[0]++;
                    if (tick[0] >= 16) {
                        try {
                            a.setRightArmPose(EulerAngle.ZERO);
                            a.setLeftArmPose(EulerAngle.ZERO);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        BossState.cancelTask(state.cutscene.applauseTask);
                        state.cutscene.applauseTask = null;
                    }
                } catch (Throwable t) {
                    BossState.cancelTask(state.cutscene.applauseTask);
                    state.cutscene.applauseTask = null;
                }
            }, 0L, 4L);
        } catch (Throwable t) {
            BossLog.warn("鼓掌演出启动失败: " + t);
        }
    }

    /** 烟花庆祝：每 10 刻两发，共 3 秒（原脚本 {@code startFireworkCelebration}）。 */
    private static void startFireworkCelebration(BossState state, Player player) {
        try {
            if (state.cutscene.fireworkTask != null) return;
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            final int[] remaining = {60};
            state.cutscene.fireworkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    Entity stand = state.stand;
                    if (stand == null || !stand.isValid() || player == null || !player.isOnline()) {
                        BossState.cancelTask(state.cutscene.fireworkTask);
                        state.cutscene.fireworkTask = null;
                        return;
                    }
                    for (int n = 0; n < 2; n++) {
                        Location loc = player.getLocation().clone().add(
                                (Math.random() - 0.5) * 6,
                                1 + Math.random() * 3,
                                (Math.random() - 0.5) * 6);
                        Firework fw = player.getWorld().spawn(loc, Firework.class);
                        FireworkMeta meta = fw.getFireworkMeta();
                        Color c1 = PARTY_COLORS[(int) Math.floor(Math.random() * PARTY_COLORS.length)];
                        Color c2 = PARTY_COLORS[(int) Math.floor(Math.random() * PARTY_COLORS.length)];
                        meta.addEffect(FireworkEffect.builder()
                                .withColor(c1, c2)
                                .with(FireworkEffect.Type.BALL_LARGE)
                                .trail(true)
                                .flicker(true)
                                .build());
                        meta.setPower(1);
                        fw.setFireworkMeta(meta);
                    }
                    remaining[0] -= 10;
                    if (remaining[0] <= 0) {
                        BossState.cancelTask(state.cutscene.fireworkTask);
                        state.cutscene.fireworkTask = null;
                    }
                } catch (Throwable t) {
                    BossState.cancelTask(state.cutscene.fireworkTask);
                    state.cutscene.fireworkTask = null;
                }
            }, 0L, 10L);
        } catch (Throwable t) {
            BossLog.warn("烟花庆祝启动失败: " + t);
        }
    }

    /** 10 分钟超长缓降 + 落地即摘（原脚本 {@code giveSlowFallingUntilGround}）。 */
    private static void giveSlowFallingUntilGround(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            if (player.isOnGround()) return;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                    12000, 0, true, false, false));
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            // 句柄挂到当前战局上（一阶段成功后表里必然有这一局）；
            // 没有战局就不挂 —— 效果自带 10 分钟过期，不会永久残留
            BossState st = BossFight.bySummoner(player);
            if (st == null) return;
            st.cutscene.slowFallTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (player == null || !player.isOnline() || player.isDead()) {
                        BossState.cancelTask(st.cutscene.slowFallTask);
                        st.cutscene.slowFallTask = null;
                        return;
                    }
                    if (player.isOnGround()) {
                        try {
                            player.setFallDistance(0f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        try {
                            player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        BossState.cancelTask(st.cutscene.slowFallTask);
                        st.cutscene.slowFallTask = null;
                    }
                } catch (Throwable ignored) {
                    BossState.cancelTask(st.cutscene.slowFallTask);
                    st.cutscene.slowFallTask = null;
                }
            }, 5L, 5L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 这个战局还是「当前局」吗（定时任务自检守卫的统一口径）。 */
    private static boolean isCurrent(BossState state) {
        return state != null && BossFight.byKey(state.key) == state;
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

    /** 排一个动作（记到 dialogueTasks，收场时一起收）。 */
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

    /** 选址结果（原脚本 {@code findPhaseTwoArena} 返回的 {@code {cx, cy, cz, diff}}）。 */
    private record Found(int cx, int cy, int cz, int diff) {}
}
