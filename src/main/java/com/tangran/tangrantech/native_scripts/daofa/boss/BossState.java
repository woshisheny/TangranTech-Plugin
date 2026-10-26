package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

/**
 * 一局 BOSS 战的全部状态（原脚本 {@code state} 对象 / 约 155 个平铺字段）。
 *
 * <h2>为什么要拆模块，而不是照抄 155 个字段</h2>
 * <p>原脚本的 {@code state} 是<b>一个 155 个字段的平铺对象</b>。真正的问题不是「字段多」，
 * 而是<b>同一个字段被多个阶段复用</b>：</p>
 * <blockquote>
 *   {@code phaseTwoGuardian} 被三位护法共用 —— zqx125 上场时指向 zqx125 的骷髅、
 *   Katarina 上场时指向 Katarina、双子上场时指向天虚。伤害事件里只按「字段是不是本体」
 *   比对，于是 Katarina 被当成双子（双子不吃实体伤害）⇒
 *   <b>拿戮仙剑砍 Katarina 一点伤害都没有</b>。
 * </blockquote>
 * <p>作者后来打补丁修掉了（先查 {@code phaseTwoGuardianId} 判「这一局是不是双子阶段」再比对），
 * 但<b>结构上仍会复现</b>：只要还有「一个字段表示三种东西」，下一个改护法的人就会再踩一次。</p>
 *
 * <p>本类的做法：<b>每位护法一个 {@link GuardianSlot}，各自持有自己的本体 / 血条 / 血量 /
 * 是否可被打；再用 {@link GuardianModule#activeId} 显式指出「这一局现在是哪一位」</b>。
 * 于是「Katarina 是不是双子」变成一次 {@code activeId} 字符串比较，
 * 那类 bug 从结构上不可能再发生。</p>
 *
 * <h2>模块边界就是「清理边界」</h2>
 * <p>每个模块都实现 {@link Module#cancel()}。收场不再是原脚本那样 120 行平铺
 * {@code try { if (state.xxx) state.xxx.cancel(); } catch (e) {}}，
 * 而是 {@link #cancelAll()} 依次调各模块的 {@code cancel()} ——
 * 「收掉一阶段」「只收音乐」这类局部收场也因此变得可以单独调。</p>
 *
 * <h2>字段来源</h2>
 * <p>本类的字段名全部来自原脚本 {@code startBossFight()} 里那个对象字面量，
 * 括号里标注的是原字段名。模块划分参照了第三方代码评审给出的 7 模块方案
 * （见 {@code 转化报告.md} 第 9.2 节），但<b>只保留原脚本真实存在的字段</b>，
 * 未实现的机制不预建字段 —— 模块存在的意义就是「以后加字段只改自己那一块」。</p>
 */
public final class BossState {

    // ==================================================================
    // 常量（原脚本 1844-1846 / 2847 行）
    // ==================================================================

    /** 糖然本体（二阶段）血量上限。 */
    public static final double PHASE_TWO_MAX_HP = 100.0d;

    /** 护法血量上限（zqx125 / Katarina 共用；双子在各自的 Slot 上覆盖）。 */
    public static final double GUARDIAN_MAX_HP = 100.0d;

    /** 双子护法血量（破法弓侧 `twinMax=300` 与此对应）。 */
    public static final double TWIN_MAX_HP = 300.0d;

    /** 护法 id：第一位（无敌，只吃 BossBar 计数器）。 */
    public static final String GID_ZQX125 = "zqx125";

    /** 护法 id：第二位（可被近战打伤）。 */
    public static final String GID_KATARINA = "Katarina";

    /** 护法 id：第三位 · 双子（天虚）。 */
    public static final String GID_TWIN_A = "D_RKIN";

    /** 护法 id：第三位 · 双子（天实）。 */
    public static final String GID_TWIN_B = "DrofSunShine";

    /** 读档「直接从糖然三阶段开始」的特殊 pending id。 */
    public static final String GID_SKIP_TO_TANGRAN3 = "TANGRAN3";

    /** 读档「直接从四阶段过场开始」的特殊 pending id。 */
    public static final String GID_SKIP_TO_TANGRAN4 = "TANGRAN4";

    /** 这一局现在是不是双子阶段（原脚本 `gid === 'D_RKIN' || gid === 'DrofSunShine'`）。 */
    public static boolean isTwinStage(String guardianId) {
        return GID_TWIN_A.equals(guardianId) || GID_TWIN_B.equals(guardianId);
    }

    // ==================================================================
    // 身份 / 总控
    // ==================================================================

    /** 战局键 = 召唤者 UUID 字符串（原脚本 {@code getPlayerKey} 就是它）。 */
    public final String key;

    /** 召唤者当前名字（原 `name`）。 */
    public String playerName;

    /** 召唤者 UUID 字符串（原 `summonerUuid`）。 */
    public String summonerUuid;

    /** 糖然展示本体：无重力 · 无敌的盔甲架，也是「这场战局存在」的标记物（原 `stand`）。 */
    public Entity stand;

    /** 主血条（原 `bar`）。 */
    public BossBar bar;

    /** 读档选择是否已做出（原 `choiceMade`）。 */
    public boolean choiceMade;

    /** 读档所选的阶段（原 `choiceStage` 是字符串、`pendingPhase` 是数字，二者并存）。 */
    public String choiceStage = "phase1";

    /** 待进入的阶段（原 `pendingPhase`）。 */
    public int pendingPhase = 1;

    /** 系统传送豁免：阵型/切场期间由脚本硬推位置，避免与原版 AI 或其它插件打架（原 `ignoringTeleport`）。 */
    public boolean ignoringTeleport;

    /**
     * 收场进行中标记（原 `removing`）。
     *
     * <p>收场必须<b>幂等</b>：多条路径（拆方块 / 玩家退出 / 定时任务自检）都可能触发收场，
     * 没有这个标记会重复 remove 实体与重复 cancel 任务。</p>
     */
    public boolean removing;

    // ==================================================================
    // 模块
    // ==================================================================

    /** 场地（一阶段躲避场 + 二阶段石英场）。 */
    public final ArenaModule arena = new ArenaModule();

    /** 糖然本体（盔甲架 → 僵尸 → 跪像）。 */
    public final TangranModule tangran = new TangranModule();

    /** 护法（zqx125 / Katarina / 双子），每位一个独立槽位。 */
    public final GuardianModule guardians = new GuardianModule();

    /** 阶段一：21×21×12 躲避场（激光 / 飞天猪 / 尖牙 / 二阶跳 / 十条命）。 */
    public final PhaseOneModule phaseOne = new PhaseOneModule();

    /** 阶段二：51×51×20 石英场 + 背包备份 + 二阶段锁 + 音乐。 */
    public final PhaseTwoModule phaseTwo = new PhaseTwoModule();

    /** 双子「经营」小游戏（挖矿 / 伐木 / 种地 → 天实账本）。 */
    public final MinigameModule minigame = new MinigameModule();

    /** 御剑（戮仙剑飞行）。 */
    public final SwordModule sword = new SwordModule();

    /** 过场演出（注视 / 降临 / 鼓掌 / 烟花 / 对话）。 */
    public final CutsceneModule cutscene = new CutsceneModule();

    public BossState(String key) {
        this.key = key;
        this.summonerUuid = key;
    }

    // ==================================================================
    // 通用查询（供索引与各方复用，避免各自 getField 猜）
    // ==================================================================

    /** 这场战局是否已经结束（收场中 / 本体已失效）。 */
    public boolean isDead() {
        try {
            if (removing) return true;
            // 三/四阶段：糖然本体已被主动换成决斗体（僵尸）/ 跪像，stand 被摘置空。
            // 此时若仍以 stand 判活，isActive 恒 false，后果：
            // ① 玩家掉线重进 → JoinListener 走「!isActive」分支 → 背包被日常装偷换；
            // ② 重复召唤入口不拦截 → 进行中的局被 forceStop 强收后从一阶段重开。
            if (tangran.standSwapped) {
                Entity duel = tangran.duelMob;
                Entity kneel = tangran.kneelStand;
                if (duel == null && kneel == null) return false;  // 换形态过渡中：局还活着
                if (duel != null && !safeValid(duel)) return true;
                if (kneel != null && !safeValid(kneel)) return true;
                return false;
            }
            return stand == null || !stand.isValid();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean safeValid(Entity e) {
        try {
            return e != null && e.isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这个实体属于本局吗。
     *
     * <p>给 {@link BossFight} 的实体索引做「未命中时的现场核对」用。
     * <b>凡是「外部拿一个实体来反查是哪一局」的实体都必须在这里列全</b> ——
     * 原脚本对应的 32 处线性遍历里，有 8 处就是在干这件事
     * （本体 / 护法 / 护法二 / 箭矢 / 双子展示架 / 跪像）；
     * 漏列一个的后果是「那一类实体反查不到」，表现为某个联动静默失效。</p>
     *
     * <p>这里直接比对字段而<b>不构造中间集合</b>：这个方法在伤害事件里会被频繁调用，
     * 每次 build 一个 {@code List} 会白产生大量垃圾。</p>
     */
    public boolean owns(Entity entity) {
        if (entity == null) return false;
        if (sameEntity(stand, entity)) return true;
        if (sameEntity(tangran.duelMob, entity)) return true;
        if (sameEntity(tangran.kneelStand, entity)) return true;
        for (GuardianSlot slot : guardians.all()) {
            if (sameEntity(slot.body, entity)) return true;
            if (sameEntity(slot.body2, entity)) return true;
            if (sameEntity(slot.displayA, entity)) return true;
            if (sameEntity(slot.displayB, entity)) return true;
        }
        for (ArrowEntry a : guardians.arrows) {
            if (a != null && sameEntity(a.entity, entity)) return true;
        }
        return false;
    }

    private static boolean sameEntity(Entity a, Entity b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            return a.equals(b);
        } catch (Throwable ignored) {
            // 实体所在区块未加载：当作不同
            return false;
        }
    }

    /** 收掉本局注册过的全部定时任务（按模块逐个收，便于只收某一块）。 */
    public void cancelAll() {
        arena.cancel();
        tangran.cancel();
        guardians.cancel();
        phaseOne.cancel();
        phaseTwo.cancel();
        minigame.cancel();
        sword.cancel();
        cutscene.cancel();
    }

    /** 本局现在生效的护法槽位（没有则为 null）。 */
    public GuardianSlot activeGuardian() {
        return guardians.active();
    }

    // ==================================================================
    // 模块定义
    // ==================================================================

    /** 所有模块的公共契约：能被单独收掉。 */
    public interface Module {
        /** 取消本模块持有的全部定时任务（必须幂等）。 */
        void cancel();
    }

    /** 安全地取消一个任务。 */
    static void cancelTask(BukkitTask task) {
        try {
            if (task != null) task.cancel();
        } catch (Throwable ignored) {
            // 任务已结束 / 插件已卸载：忽略
        }
    }

    /** 安全地移除一个实体。 */
    static void removeEntity(Entity e) {
        try {
            if (e != null && e.isValid()) e.remove();
        } catch (Throwable ignored) {
            // 实体所在区块未加载：忽略
        }
    }

    /** 安全地清掉一条血条。 */
    static void removeBar(BossBar bar) {
        try {
            if (bar != null) {
                bar.removeAll();
                bar.setVisible(false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------

    /**
     * 场地模块（原 `arenaWorld` / `arenaRegion` / `phaseTwoArena*` 一族）。
     */
    public static final class ArenaModule implements Module {
        /** 一阶段避躲场所在世界（原 `arenaWorld`）。 */
        public World oneWorld;
        /** 一阶段避躲场区域（原 `arenaRegion`）。 */
        public BossArena.Region oneRegion;

        /**
         * 挑战者的回程位置：进入专用世界（tangran_space）前所站的位置。
         * 收场（失败/退出/通关）时由 forceStop 送回这里；null 时退回主世界出生点。
         */
        public Location returnLoc;

        /** 二阶段石英场所在世界（原 `phaseTwoArenaWorld`）。 */
        public World twoWorld;
        /** 二阶段石英场区域（原 `phaseTwoArenaRegion`）。 */
        public BossArena.Region twoRegion;
        /** 二阶段场地中心（原 `phaseTwoArenaCenter`）。 */
        public Location twoCenter;

        /**
         * 双子太极阵的中心（原 {@code state.twinTaiji}：cx / cy / cz / r）。
         *
         * <p>召唤阵仪式算好（夹在场地内）并落在这里，双子本体按「鱼眼 ±R/2」落位。
         * <b>不能复用 {@link #twoCenter}</b> —— 那个字段是「场地几何中心」，
         * 别的逻辑（召唤仪式定位、剑影清理的兜底锚点）都在读它。</p>
         */
        public Location taijiCenter;
        /** 场地搭建任务的句柄（原 `phaseTwoArenaTask`）。 */
        public BukkitTask twoBuildTask;
        /** 召唤护法任务的句柄（原 `phaseTwoSummonTask`）。 */
        public BukkitTask twoSummonTask;

        /** 一阶段避躲场是否已开始（原 `phaseOneStarted`）。 */
        public boolean oneStarted;

        /** 有没有二阶段场地（判定「这一局是否已经进入二阶段」）。 */
        public boolean hasTwo() {
            return twoRegion != null;
        }

        @Override
        public void cancel() {
            cancelTask(twoBuildTask);
            twoBuildTask = null;
            cancelTask(twoSummonTask);
            twoSummonTask = null;
        }
    }

    // ------------------------------------------------------------------

    /** 糖然本体的形态（原脚本用「换实体」表达，这里显式化）。 */
    public enum TangranForm {
        /** 悬空盔甲架（初始亮相 / 二阶段）。 */
        STAND,
        /** 僵尸形态（三阶段下场）。 */
        ZOMBIE,
        /** 跪像（四阶段）。 */
        KNEEL
    }

    /**
     * 糖然本体模块（原 `phaseTwoHp` / `tangranDuel` / `tangranKneelStand` 一族）。
     *
     * <p>注意血量的归属：这里的是<b>糖然自己的血</b>（原 `phaseTwoHp`），
     * 护法的血在 {@link GuardianSlot#hp}，两者不能混。</p>
     */
    public static final class TangranModule implements Module {
        /** 糖然当前形态。 */
        public TangranForm form = TangranForm.STAND;

        /** 当前血量（原 `phaseTwoHp`）。 */
        public double hp = PHASE_TWO_MAX_HP;

        /** 血量上限（原脚本固定 100，冗余存一份便于按局调整）。 */
        public double maxHp = PHASE_TWO_MAX_HP;

        /** 上一拍的血量，用于「掉血才更新血条」的判重（原 `phaseTwoLastHealth`）。 */
        public double lastHealth = -1.0d;

        /** 三阶段下场后的真实实体（原 `tangranDuel.mob`）。 */
        public Entity duelMob;

        /** 决斗体血量折算用的「血条剩余值」（原 `tangranDuel.barHp`：实体血量 → 血条读数按比例折算）。 */
        public double duelBarHp = PHASE_TWO_MAX_HP;

        /** 决斗体实体血量上限（原 `tangranDuel.maxHp`：血条剩余 ×100，受原版 MAX_HEALTH 上限 1024 截断）。 */
        public double duelMaxHp;

        /** 决斗主循环任务（原 `tangranDuel.task`）。 */
        public BukkitTask duelTask;

        /** 三阶段火球（伤害在事件层钉成 2；只留最近 8 颗）。 */
        public final List<Entity> duelFireballs = new ArrayList<>();

        /** 糖然下坠任务（原 `tangranDescentTask`）。 */
        public BukkitTask descentTask;

        /** 四阶段跪像实体（原 `tangranKneelStand`）。 */
        public Entity kneelStand;

        /** 本体主循环任务（原 `task`）。 */
        public BukkitTask task;
        /** 跟随注视任务（原 `watchTask`）。 */
        public BukkitTask watchTask;
        /** 视线追踪任务（原 `lookTask`）。 */
        public BukkitTask lookTask;
        /** 音乐任务（原 `musicTask`）。 */
        public BukkitTask musicTask;

        /** 本体是否已按「二阶段战斗态」替换过形态（原 `phaseTwoStarted`）。 */
        public boolean phaseTwoStarted;

        /**
         * 是否已进入糖然三阶段（原 {@code phaseThreeStarted}）：
         * 血条标题换「糖然三阶段」+ 红色。
         */
        public boolean phaseThreeStarted;

        /**
         * 本体是否被我们<b>主动换成别的形态</b>（盔甲架 → 僵尸 → 跪像）。
         *
         * <p>看门任务靠它区分「正常过场」与「本体被异常移除」：
         * 换形态时原盔甲架会消失，不标记的话每次切形态都会被判成挑战结束。
         * 由阶段三/四设置（批次 7）。</p>
         */
        public boolean standSwapped;

        @Override
        public void cancel() {
            cancelTask(task);
            task = null;
            cancelTask(watchTask);
            watchTask = null;
            cancelTask(lookTask);
            lookTask = null;
            cancelTask(musicTask);
            musicTask = null;
            cancelTask(duelTask);
            duelTask = null;
            cancelTask(descentTask);
            descentTask = null;
            for (Entity e : duelFireballs) removeEntity(e);
            duelFireballs.clear();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 护法模块 —— <b>本次拆分的核心</b>。
     *
     * <p>原脚本用一个 {@code phaseTwoGuardian} 字段被三位护法共用。这里改成
     * 「每位护法一个 {@link GuardianSlot}」+ {@link #activeId} 显式指出当前是哪一位：</p>
     * <ul>
     *   <li>想判「Katarina 能不能被打」→ {@code guardians.katarina.damageable}，
     *       与双子阶段完全无关；</li>
     *   <li>想判「这一局是不是双子阶段」→ {@link #isTwinStage()}；</li>
     *   <li>想判「砍的这个实体是谁」→ {@code guardians.slotOf(entity)}（走实体索引）。</li>
     * </ul>
     */
    public static final class GuardianModule implements Module {
        /** 这一局现在生效的护法 id（原 `phaseTwoGuardianId`，默认 zqx125）。 */
        public String activeId = GID_ZQX125;

        /** 读档排队中的护法 id（原 `TANGRAN_PENDING_GUARDIAN` 落到 state 上的部分）。 */
        public String pendingId;

        /** 读档「跳过前面护法、构场后直接走糖然三阶段」（原 `startFromTangran`）。 */
        public boolean startFromTangran;
        /** 读档「构场后直接从四阶段借过一下开始」（原 `startFromPhase4`）。 */
        public boolean startFromPhase4;

        public final GuardianSlot zqx125 = new GuardianSlot(GID_ZQX125);
        public final GuardianSlot katarina = new GuardianSlot(GID_KATARINA);
        public final GuardianSlot twin = new GuardianSlot(GID_TWIN_A);

        /**
         * 本局当前护法的<b>实现对象</b>（{@link BossGuardian}）。
         *
         * <p>存在这里而不是 {@link GuardianManager} 的 Map 里，是为了让实例的生命周期
         * 与战局天然一致 —— 战局被收掉，实例就跟着不可达，
         * 不存在「战局没了但注册表里还挂着一个还在跑定时任务的护法」这种泄漏。</p>
         */
        public BossGuardian active;

        /** zqx125 射出的箭（原 `phaseTwoArrows`）。 */
        public final List<ArrowEntry> arrows = new ArrayList<>();
        /** 箭矢循环任务（原 `phaseTwoArrowTask`）。 */
        public BukkitTask arrowTask;
        /** 箭矢节拍（原 `phaseTwoArrowTick`）。 */
        public int arrowTick;

        /** 这一局是不是双子阶段。 */
        public boolean isTwinStage() {
            return BossState.isTwinStage(activeId);
        }

        /** 当前生效的槽位。 */
        public GuardianSlot active() {
            return byId(activeId);
        }

        /** 按 id 取槽位（`DrofSunShine` 与 `D_RKIN` 同属双子）。 */
        public GuardianSlot byId(String id) {
            if (id == null) return null;
            if (GID_KATARINA.equalsIgnoreCase(id)) return katarina;
            if (GID_ZQX125.equalsIgnoreCase(id)) return zqx125;
            // 注意：必须写 BossState. 前缀 —— 本类自己也有一个无参的 isTwinStage()，
            // 不限定的话会解析成「调用本类那个无参方法」并因参数个数不符而编译失败。
            if (BossState.isTwinStage(id)) return twin;
            return null;
        }

        /** 全部槽位（顺序 = 护法顺序）。 */
        public List<GuardianSlot> all() {
            return List.of(zqx125, katarina, twin);
        }

        /** 这个实体是哪位护法的本体（含双子的第二位本体）；不是则 null。 */
        public GuardianSlot slotOf(Entity entity) {
            if (entity == null) return null;
            for (GuardianSlot slot : all()) {
                if (slot.matches(entity)) return slot;
            }
            return null;
        }

        /** 这个实体是不是某支被登记过的护法箭矢。 */
        public ArrowEntry arrowOf(Entity entity) {
            if (entity == null) return null;
            for (ArrowEntry a : arrows) {
                if (a == null || a.entity == null) continue;
                try {
                    if (a.entity.equals(entity)) return a;
                } catch (Throwable ignored) {
                    // 实体已卸载：忽略
                }
            }
            return null;
        }

        @Override
        public void cancel() {
            cancelTask(arrowTask);
            arrowTask = null;
            for (GuardianSlot slot : all()) slot.cancel();
            arrows.clear();
        }
    }

    /**
     * 一位护法的槽位。
     *
     * <p>每一位护法<b>只持有自己的东西</b>：原脚本里
     * {@code phaseTwoGuardian} / {@code phaseTwoGuardian2} / {@code phaseTwoGuardianBar} /
     * {@code phaseTwoTwinBar} / {@code phaseTwoGuardianHp} / {@code phaseTwoGuardianMaxHp} /
     * {@code phaseTwoGuardianDefeated} / {@code phaseTwoGuardianDamageable} 这 8 个字段
     * 被三位护法按需复用 —— 本类把它们各自一份，复用彻底消失。</p>
     */
    public static final class GuardianSlot implements Module {
        /** 护法 id（双子以天虚的 id 作为槽位 id）。 */
        public final String id;

        /** 护法本体（原 `phaseTwoGuardian`）。 */
        public Entity body;
        /** 双子第二位本体（原 `phaseTwoGuardian2`）；其它护法恒为 null。 */
        public Entity body2;

        /** 第一位本体头顶展示盔甲架（原 `twinDisplayA`，双子用）。 */
        public Entity displayA;
        /** 第二位本体头顶展示盔甲架（原 `twinDisplayB`，双子用）。 */
        public Entity displayB;

        /** 护法血条（原 `phaseTwoGuardianBar` / `phaseTwoTwinBar`）。 */
        public BossBar bar;

        /**
         * 双子第二位本体（天实）的血条（原 {@code phaseTwoTwinBar}）。
         *
         * <p>单本体护法恒为 {@code null}。与原脚本一致：双子是<b>两条独立的血条</b>，
         * 各 300 血，先倒下的那位只退场 + 关掉自己那条。</p>
         */
        public BossBar bar2;

        /** 当前血量（原 `phaseTwoGuardianHp`）。 */
        public double hp = GUARDIAN_MAX_HP;
        /** 血量上限（原 `phaseTwoGuardianMaxHp`）。 */
        public double maxHp = GUARDIAN_MAX_HP;

        /** 是否已被击败（原 `phaseTwoGuardianDefeated`）。 */
        public boolean defeated;

        /**
         * 这一位本局是否<b>可被近战打伤</b>（原 `phaseTwoGuardianDamageable`）。
         *
         * <p>只有 Katarina 为 true。zqx125 完全无敌（血量只在 BossBar 上），
         * 双子也只吃「破法弓计数器」伤害。原脚本靠一个共享标志表达，
         * 一旦「谁上场」的指针没同步，就会出现「打不动 Katarina」或「能砍死双子」。</p>
         */
        public boolean damageable;

        /** 环绕盾剩余面数（原 `kataShieldCount`；Katarina 专用，>0 时任何攻击只算 1 点）。 */
        public int shieldCount;

        /** 双子太极阵任务（原 `twinTaijiTask`）。 */
        public BukkitTask taijiTask;
        /** 本体主循环任务（原 `phaseTwoKatarinaTask` / `phaseTwoTwinTask` 等同位职责）。 */
        public BukkitTask bodyTask;
        /** 本体追踪任务（原 `phaseTwoKatarinaTrackTask`）。 */
        public BukkitTask trackTask;

        /**
         * 双子的两个计数器血量（原 `twinHpA` / `twinHpB`）。
         *
         * <p>双子是<b>两条独立的计数</b>（各 300），都清零才算倒地。
         * 单本体护法（zqx125 / Katarina）用 {@link #hp}。</p>
         */
        public double hpA = TWIN_MAX_HP;
        public double hpB = TWIN_MAX_HP;

        /**
         * 血条上一次显示的读数（原 `twinBarShownA` / `twinBarShownB`）。
         *
         * <p>双子阶段一拖就是好几分钟，原脚本为此加的去重：只在读数变化时
         * {@code setTitle} + {@code setProgress}（原来每拍 2+2 次发包，包量很大）。
         * {@code -1} = 从未显示过。</p>
         */
        public int lastBarA = -1;
        public int lastBarB = -1;

        /** 双子倒地标记（原 `twinDownA` / `twinDownB`）。 */
        public boolean downA;
        public boolean downB;

        public GuardianSlot(String id) {
            this.id = id;
        }

        /** 这个实体是不是本槽位的本体（含双子的第二位）。 */
        public boolean matches(Entity entity) {
            if (entity == null) return false;
            return same(body, entity) || same(body2, entity)
                    || same(displayA, entity) || same(displayB, entity);
        }

        /**
         * 这个展示盔甲架挂在哪一位本体上。
         *
         * <p>对应原脚本 {@code tangranTwinFromDisplay}：命中 A 返回第一位本体、
         * 命中 B 返回第二位本体；不是本槽位的展示架返回 {@code null}。</p>
         */
        public Entity displayHost(Entity display) {
            if (display == null) return null;
            if (same(displayA, display)) return body;
            if (same(displayB, display)) return body2;
            return null;
        }

        /** 双子两位本体是否都已倒地（计数器双双清零）。 */
        public boolean twinBothDown() {
            return downA && downB;
        }

        private static boolean same(Entity a, Entity b) {
            if (a == null) return false;
            try {
                return a.equals(b);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 双子两位本体都还活着？ */
        public boolean twinBothAlive() {
            return alive(body) && alive(body2);
        }

        private static boolean alive(Entity e) {
            try {
                return e != null && e.isValid();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public void cancel() {
            cancelTask(taijiTask);
            taijiTask = null;
            cancelTask(bodyTask);
            bodyTask = null;
            cancelTask(trackTask);
            trackTask = null;
        }
    }

    /** 一支登记在册的护法箭矢（原 `phaseTwoArrows` 里的元素）。 */
    public static final class ArrowEntry {
        /** 箭实体。 */
        public Entity entity;
        /** 是否已经结算过命中（避免同一支箭多次触发）。 */
        public boolean resolved;
        /** 发射时的节拍号，用于按拍清理过期箭矢。 */
        public int bornTick;

        /**
         * 剩余追踪拍数（0 = 不追踪）。
         *
         * <p>原脚本是 {@code homing: (trackSpeed > 0 ? 40 : 0)}，每拍减一 ——
         * 「追踪 2 秒」。这里保持同样的字段语义，<b>不能用别的字段凑</b>：
         * 一旦凑错，表现是「追踪箭不追踪」或「普通箭一直追着玩家拐弯」。</p>
         */
        public int homing;

        /** 追踪时的速度（格/刻）。 */
        public double homingSpeed;

        /** 上一拍位置（命中判定的「相对位移」必须有它，见 {@link BossFx#segmentsHitPlayer}）。 */
        public double lastX;
        public double lastY;
        public double lastZ;

        /** 是否已记录过上一拍位置（第一拍用「当前位置」当上一拍，避免第一拍就判定为长距离位移）。 */
        public boolean hasLast;

        public ArrowEntry() {}

        public ArrowEntry(Entity entity, int bornTick) {
            this.entity = entity;
            this.bornTick = bornTick;
        }
    }

    /** 一块在空中的铁砧（原 `phaseTwoAnvils` 里的元素）。 */
    public static final class Anvil {
        /** 掉落方块实体。 */
        public org.bukkit.entity.FallingBlock entity;
        /** 是否已经砸到过玩家（同一块只结算一次）。 */
        public boolean hitPlayer;
    }

    /**
     * 一波红石雷击（原 `phaseTwoStrikeGroups` 里的元素）。
     *
     * <p>「预警 → 落雷 → 复原」三态：{@code struck} 为 false 时只显示红石块，
     * 到 {@code strikeDelay} 才落雷并立刻把方块复原回石英（玩家看到的是「红一下 → 雷 → 没了」）。</p>
     */
    public static final class StrikeGroup {
        /** 本群占用的格子 {@code [x, y, z]}。 */
        public final List<int[]> blocks = new ArrayList<>();
        /** 已存活拍数。 */
        public int age;
        /** 是否已落雷。 */
        public boolean struck;
        /** 多少拍后落雷。 */
        public int strikeDelay;
        /** 最多存活多少拍（到点复原方块并移除）。 */
        public int life;
    }

    // ------------------------------------------------------------------

    /**
     * 阶段一：躲避场（原 `phaseOne*` / `challenge*` / `laser*` / `pig*` / `spike*` 一族）。
     */
    public static final class PhaseOneModule implements Module {
        public boolean active;
        public int lives = 10;
        public int maxLives = 10;
        public long elapsed;
        /** 一阶段总时长（原 `phaseOneDuration`，粘液刻）。 */
        public long duration = 1200L;
        public long invulnUntil;

        /**
         * 本局的阶段一实例（逻辑在 {@link BossPhaseOne}，数据都在本模块）。
         *
         * <p>为什么不把逻辑也塞进模块类：模块是纯数据（收场时按模块 cancel），
         * 逻辑单独一个类便于单测与阅读；两者通过这个引用相连，生命周期一致。</p>
         */
        public BossPhaseOne impl;

        /** 激光 / 飞天猪 / 方块 / 尖牙 的登记表。 */
        public final List<Laser> lasers = new ArrayList<>();
        public final List<PigMissile> pigs = new ArrayList<>();
        public final List<BlockSpot> blocks = new ArrayList<>();
        public final List<Spike> spikes = new ArrayList<>();

        /** 是否已经启动过（幂等：{@code start} 重复调只生效一次）。 */
        public boolean started;

        /** 玩家原本的飞行权限备份（挑战期间临时放开「允许飞行」以支持二段跳）。 */
        public boolean originalAllowFlight;
        public boolean originalFlying;

        /** 各类机制的节拍/冷却（原 `laserTypeIndex` / `pigAxisIndex` / `spikeCooldown` / 各种 Cooldown）。 */
        public int laserTypeIndex;
        public int pigAxisIndex;
        public int spikeCooldown;
        public int laserCooldown;
        public int missileCooldown;
        public int blockCooldown;
        public boolean doubleJumpUsed;

        /** 倒计时任务（原 `countdownTask` / `countdownValue`）。 */
        public BukkitTask countdownTask;
        public int countdownValue = 10;
        /** 挑战主循环（原 `challengeTask`）。 */
        public BukkitTask challengeTask;

        @Override
        public void cancel() {
            cancelTask(countdownTask);
            countdownTask = null;
            cancelTask(challengeTask);
            challengeTask = null;
            for (PigMissile m : pigs) removeEntity(m.entity);
            pigs.clear();
            for (Spike s : spikes) removeEntity(s.entity);
            spikes.clear();
            lasers.clear();
            blocks.clear();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 一条粒子激光。
     *
     * <p>三态：{@code warnUntil} 之前是<b>白色预警</b>（滋滋声），
     * 之后变<b>红色</b>并开始造成伤害，{@code activeUntil} 到时消失。
     * {@code hit} 保证同一条激光只扣一次命。</p>
     */
    public static final class Laser {
        /** {@code x}（横向扫掠）/ {@code z}（纵向扫掠）/ {@code v}（竖直光柱）。 */
        public String kind;
        public long warnUntil;
        public long activeUntil;
        public boolean hit;
        public boolean activated;
        public long lastSizzle;
        /** {@code v} 型：光柱中心 X/Z 与 Y 范围。 */
        public int x;
        public int z;
        public int yMin;
        public int yMax;
        /** {@code x}/{@code z} 型：激光高度与所在列/行。 */
        public int y;
        public int value;
    }

    /** 一发「飞天猪猪导弹」。 */
    public static final class PigMissile {
        public Entity entity;
        /** {@code x} / {@code z} / {@code y}：沿哪条轴飞。 */
        public String axis;
        /** 1 或 -1：方向。 */
        public int dir;
        /** 每 2 刻挪多少格（随进度 0.22 → 0.55）。 */
        public double speed;
        /** 到期拍数（同时用于「飞太久自动消失」）。 */
        public long expire;
    }

    /** 一块临时砂岩台阶（玩家踩它改变高度躲激光）。 */
    public static final class BlockSpot {
        public int x;
        public int y;
        public int z;
        public long expire;
    }

    /** 一颗幻魔者尖牙。 */
    public static final class Spike {
        public Entity entity;
        public double x;
        public double y;
        public double z;
        /** 哪一拍「咬下去」。 */
        public long biteAt;
        public long expire;
        public boolean hit;
    }

    // ------------------------------------------------------------------

    /**
     * 阶段二：石英场战斗（原 `phaseTwoStarted` / `phaseTwoCombatActive` /
     * `phaseTwoInventory*` / `phaseTwoSwordGranted` / `phaseTwoShield*` / `phaseTwoStrike*` /
     * `phaseTwoDrops` / `phaseTwoNoRegenTask` / `phaseTwoMusic*` 一族）。
     */
    public static final class PhaseTwoModule implements Module {
        /** 是否已进入二阶段战斗态（原 `phaseTwoCombatActive`）。 */
        public boolean combatActive;

        /** 背包是否已备份（原 `phaseTwoInventorySaved`）。 */
        public boolean inventorySaved;
        /** 备份文件（原 `phaseTwoInventoryFile`）。 */
        public java.io.File inventoryFile;

        /** 是否已发放戮仙剑（原 `phaseTwoSwordGranted`）。 */
        public boolean swordGranted;

        /** 环绕盾实体与状态（原 `phaseTwoShieldEntity` / `phaseTwoShieldActive` / `...Timer` / `...RaiseGrace`）。 */
        public Entity shieldEntity;
        public boolean shieldActive;
        public int shieldTimer;
        public int shieldRaiseGrace;

        /** 甘露档位（原 `phaseTwoNectarTierIndex`）。 */
        public int nectarTierIndex = -1;

        /** 天降铁砧的落点与任务（原 `phaseTwoAnvilTask` / `phaseTwoAnvilTick` / `phaseTwoAnvils`）。 */
        public BukkitTask anvilTask;
        public int anvilTick;
        /** 在空中的铁砧（同时在场有上限，防止服务器被实体拖垮）。 */
        public final List<Anvil> anvils = new ArrayList<>();

        /** 地面掉落物登记（原 `phaseTwoDrops`）。 */
        public final List<org.bukkit.entity.Item> drops = new ArrayList<>();

        /** 雷电分组（原 `phaseTwoStrikeGroups`）。 */
        public final List<StrikeGroup> strikeGroups = new ArrayList<>();

        /** 禁止回血任务（原 `phaseTwoNoRegenTask`）。 */
        public BukkitTask noRegenTask;
        /** 狂暴任务（原 `phaseTwoBerserkTask`）。 */
        public BukkitTask berserkTask;

        /** 音符盒 BGM 任务与进度（原 `phaseTwoMusicTask` / `...Tick` / `...Index` / `...File`）。 */
        public BukkitTask musicTask;
        public int musicTick;
        public int musicIndex;
        public String musicFile = "FEVER_PITCH.json";

        /**
         * 上一次处理道具右键的服务端 tick 与道具种类
         * （原 {@code phaseTwoUseTick} / {@code phaseTwoUseKind}）。
         *
         * <p>用于「一次右键只喝一瓶 / 只举一次盾」的去重，见
         * {@link BossPhaseTwoItems#handleInteract}。</p>
         */
        public int useTick = -1;
        public String useKind;

        /** 二阶段「锁」的续期任务（原脚本每 0.5 秒续期一次，见 `BossChallengeState`）。 */
        public BukkitTask lockWatcher;

        @Override
        public void cancel() {
            cancelTask(anvilTask);
            anvilTask = null;
            cancelTask(noRegenTask);
            noRegenTask = null;
            cancelTask(berserkTask);
            berserkTask = null;
            cancelTask(musicTask);
            musicTask = null;
            cancelTask(lockWatcher);
            lockWatcher = null;
            removeEntity(shieldEntity);
            shieldEntity = null;
            for (Entity e : drops) removeEntity(e);
            drops.clear();
            anvils.clear();
            strikeGroups.clear();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 双子「经营」小游戏（原 `xuGame` / `xuServants` / `xuChallengeItems`）。
     */
    public static final class MinigameModule implements Module {
        /** 小游戏状态（原 `xuGame`：tick / world / region / oreBlock / trees / soils）。 */
        public World world;
        public BossArena.Region region;
        public long tick;
        public org.bukkit.block.Block oreBlock;
        public final List<Object> trees = new ArrayList<>();
        public final List<Object> soils = new ArrayList<>();

        /** 仆从（原 `xuServants`）。 */
        public final List<Entity> servants = new ArrayList<>();
        /** 挑战用品（原 `xuChallengeItems`）。 */
        public final List<org.bukkit.inventory.ItemStack> challengeItems = new ArrayList<>();

        /** 小游戏主循环任务（原 `xuGameTick` 由外层调度，这里只留句柄）。 */
        public BukkitTask task;

        /** 有没有在跑（原脚本的 `st.xuGame` 非空判定）。 */
        public boolean running() {
            return region != null || task != null;
        }

        @Override
        public void cancel() {
            cancelTask(task);
            task = null;
            for (Entity e : servants) removeEntity(e);
            servants.clear();
            challengeItems.clear();
            trees.clear();
            soils.clear();
            oreBlock = null;
        }
    }

    // ------------------------------------------------------------------

    /** 御剑飞行（原 `swordRide*` / `swordProjectile*` / `swordFlourish*` / `swordIntro*` / `ride*`）。 */
    public static final class SwordModule implements Module {
        public boolean rideActive;
        /** 脚下那把剑的展示实体（原 `swordRideDisplay`）。 */
        public Entity rideDisplay;
        public BukkitTask rideTask;
        /** 剑气的展示实体（原 `swordProjectileDisplay`）。 */
        public Entity projectileDisplay;
        public BukkitTask projectileTask;
        public BukkitTask flourishTask;
        public BukkitTask introTask;

        /** 飞行状态备份（原 `rideOriginalAllowFlight` / `rideOriginalFlying`）。 */
        public boolean originalAllowFlight;
        public boolean originalFlying;
        /** 潜行累计刻（原 `rideSneakTicks`，用于「长按潜行下剑」）。 */
        public int sneakTicks;

        /** 御剑飞行是否正在进行 —— 双子 Katarina 阶段会设 `TR_BOSS_NO_SWORD_RIDE` 禁飞。 */
        public boolean active() {
            return rideActive;
        }

        @Override
        public void cancel() {
            cancelTask(rideTask);
            rideTask = null;
            cancelTask(projectileTask);
            projectileTask = null;
            cancelTask(flourishTask);
            flourishTask = null;
            cancelTask(introTask);
            introTask = null;
            removeEntity(rideDisplay);
            rideDisplay = null;
            removeEntity(projectileDisplay);
            projectileDisplay = null;
            rideActive = false;
        }
    }

    // ------------------------------------------------------------------

    /** 过场演出（原 `lookTask` 之外的 `landingTask` / `applauseTask` / `fireworkTask` / `dialogueTasks`）。 */
    public static final class CutsceneModule implements Module {
        public BukkitTask landingTask;
        public BukkitTask applauseTask;
        public BukkitTask fireworkTask;
        /**
         * 「缓降到底」守护任务（原脚本 {@code giveSlowFallingUntilGround} 里那个 5 刻轮询）。
         *
         * <p>原脚本没有存这个句柄（任务自取消，收场时最多多跑 5 刻）。
         * 这里存下来是为了让 {@link #cancel()} 能连它一起收 —— 收场后玩家可能还在空中，
         * 任务继续跑没有意义，而且它持有玩家引用。</p>
         */
        public BukkitTask slowFallTask;
        public final List<BukkitTask> dialogueTasks = new ArrayList<>();

        @Override
        public void cancel() {
            cancelTask(landingTask);
            landingTask = null;
            cancelTask(applauseTask);
            applauseTask = null;
            cancelTask(fireworkTask);
            fireworkTask = null;
            cancelTask(slowFallTask);
            slowFallTask = null;
            for (BukkitTask t : dialogueTasks) cancelTask(t);
            dialogueTasks.clear();
        }
    }
}
