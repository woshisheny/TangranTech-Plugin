package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 战局表（原脚本 {@code globalThis.TANGRAN_BOSS_FIGHTS} / {@code BOSS_FIGHTS}）。
 *
 * <h2>原脚本的问题：32 处线性遍历</h2>
 * <p>原脚本所有「找一个战局」的场合都是 {@code for (var k in BOSS_FIGHTS) {...}}，
 * 实测<b>32 处</b>。逐处核对它们的判定条件，其实只有 8 类：</p>
 * <table border="1">
 *   <caption>原遍历的判定条件 → 本类提供的索引（处数按源码实测）</caption>
 *   <tr><th>原判定</th><th>处数</th><th>本类对应</th></tr>
 *   <tr><td>{@code st.summonerUuid === uuid}（含 {@code findActiveBossState}）</td><td>2</td>
 *       <td>{@link #BY_KEY}（原脚本的键本来就等于召唤者 UUID 字符串）</td></tr>
 *   <tr><td>{@code st.name === player.getName()}</td><td>6</td><td>{@link #BY_NAME}</td></tr>
 *   <tr><td>{@code st.stand / kneelStand / duel.mob === entity}</td><td>3</td>
 *       <td rowspan="4">{@link #byEntity}（缓存 + 未命中现场自愈）</td></tr>
 *   <tr><td>{@code st.phaseTwoGuardian / Guardian2 === entity}</td><td>1</td></tr>
 *   <tr><td>{@code st.twinDisplayA / B === entity}</td><td>2</td></tr>
 *   <tr><td>把某局的一串实体收进数组再比对</td><td>1</td></tr>
 *   <tr><td>护法箭矢（{@code phaseTwoArrows}）含该实体</td><td>1</td><td>{@link #byArrow}</td></tr>
 *   <tr><td>{@code st.phaseTwoArenaRegion} 含该坐标</td><td>5</td>
 *       <td>{@link #byArenaAt}（只扫「有场地」的那几局）</td></tr>
 *   <tr><td>{@code st.xuGame} 区域含该坐标</td><td>2</td>
 *       <td>{@link #byMinigameAt}（只扫「有经营小游戏」的那几局）</td></tr>
 *   <tr><td>{@code BOSS_FIGHTS[k] === state}（反查自己的键）</td><td>5</td>
 *       <td>{@link BossState#key} 直接读（原脚本要遍历才能反查，纯粹的浪费）</td></tr>
 *   <tr><td>「有没有任何战局」</td><td>1</td><td>{@link #any()}</td></tr>
 *   <tr><td>清场时收掉使用该场地的战局</td><td>1</td><td>{@link #purge}</td></tr>
 *   <tr><td><b>全局清理</b>（收虚仆 / 收挑战道具 / 建「活跃键」集合）</td><td>4</td>
 *       <td>{@link #all()} —— 这几处<b>本来就该遍历全部</b>，不需要索引，也不再逐条包 try/catch</td></tr>
 * </table>
 * <p>合计 2+6+3+1+2+1+1+5+2+5+1+1+4 = 34 &gt; 32：因为其中两处是同一行同时命中两类判定
 * （例如「本体或护法」写在一个循环里），归类会重复计入。以源码行数 32 为准。</p>
 *
 * <h2>实体索引为什么不「一次建好就完事」</h2>
 * <p>战局里的实体是<b>陆续产生</b>的（本体先出、护法后召唤、箭矢每拍在变、双子展示架阶段才建），
 * 想让外部每次都记得调「索引一下这个实体」，迟早会漏。所以本类的实体索引是
 * <b>「缓存 + 未命中现场自愈」</b>：</p>
 * <ol>
 *   <li>先查 {@link #BY_ENTITY}（命中即 O(1)）；</li>
 *   <li>未命中 → 直接扫各局的 {@link BossState#owns}（1~3 局 × 约 15 个实体的字段比对），
 *       扫到就顺手补进缓存。</li>
 * </ol>
 * <p>这样「查询结果永远正确」不依赖任何人记得索引，而稳态下又是 O(1)。
 * 之所以不做「整表重建」，是因为绝大多数伤害事件的受害者<b>不是</b>我们的实体，
 * 整表重建会被高频的无关事件反复触发。</p>
 *
 * <h2>与场地层的接线</h2>
 * <p>{@link BossArena} 不反向依赖本类，它在启动时等待本类注册一个回调：
 * {@link BossArena#registerRegionSource}（「哪些方块落在进行中的场地里」）。</p>
 * <p>（原先还注册过 {@code registerFightPurger}（「收掉使用这块场地的战局」），
 * 只服务「挑战区域清除器」道具的 {@code fights} 计数；该道具随挑战场独立维度化删除后
 * 一并移除，2026-10-26。）</p>
 */
public final class BossFight {

    private BossFight() {}

    /** 单实例锁：事件与定时任务都可能读写战局表。 */
    private static final Object LOCK = new Object();

    /** 战局键（召唤者 UUID 字符串）→ 战局。插入序保留，便于日志稳定。 */
    private static final Map<String, BossState> BY_KEY = new LinkedHashMap<>();

    /** 召唤者名字（小写）→ 战局。原脚本有 6 处按名字找。 */
    private static final Map<String, BossState> BY_NAME = new HashMap<>();

    /**
     * 实体 UUID → 战局。是<b>缓存</b>不是真相来源 —— 未命中会现场扫，见类注释。
     */
    private static final Map<UUID, BossState> BY_ENTITY = new HashMap<>();

    private static boolean callbacksRegistered;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 新建一局并登记。
     *
     * @param player 召唤者
     * @return 新战局（键 = 召唤者 UUID 字符串）
     */
    public static BossState create(Player player) {
        String key = keyOf(player);
        BossState state = new BossState(key);
        if (player != null) {
            state.playerName = player.getName();
            state.summonerUuid = key;
        }
        return state;
    }

    /** 原脚本 {@code getPlayerKey(player)} = 玩家 UUID 字符串。 */
    public static String keyOf(Player player) {
        try {
            return (player == null) ? null : player.getUniqueId().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 登记一局（原脚本 {@code BOSS_FIGHTS[key] = state}）。 */
    public static void put(BossState state) {
        if (state == null || state.key == null) return;
        synchronized (LOCK) {
            ensureCallbacks();
            BY_KEY.put(state.key, state);
            reindexName(state);
            // 实体索引在 put 时不预建：此刻实体还没召唤出来，建了也是空的；
            // 靠 byEntity 的现场自愈即可。
        }
    }

    /**
     * 移出一局（原脚本 {@code delete BOSS_FIGHTS[key]}）。
     *
     * <p>只动索引，<b>不动实体与任务</b> —— 收场顺序（先收实体再摘表，还是反之）
     * 由调用方决定，这里不替它做主。</p>
     */
    public static void remove(BossState state) {
        if (state == null) return;
        synchronized (LOCK) {
            if (state.key != null && BY_KEY.get(state.key) == state) {
                BY_KEY.remove(state.key);
            }
            // 名字索引可能指向它（名字被改过时），按值清一遍
            BY_NAME.values().removeIf(v -> v == state);
            BY_ENTITY.values().removeIf(v -> v == state);
        }
    }

    /** 按玩家名刷新名字索引（改名 / 首次 put 后调用）。 */
    private static void reindexName(BossState state) {
        if (state == null || state.playerName == null) return;
        BY_NAME.put(state.playerName.toLowerCase(Locale.ROOT), state);
    }

    /** 玩家改名 / 首次登记后同步名字索引。 */
    public static void refreshName(BossState state) {
        if (state == null) return;
        synchronized (LOCK) {
            BY_NAME.values().removeIf(v -> v == state);
            reindexName(state);
        }
    }

    /** 把场地层需要的回调挂上（幂等）。 */
    private static void ensureCallbacks() {
        if (callbacksRegistered) return;
        callbacksRegistered = true;
        try {
            BossArena.registerRegionSource(BossFight::activeRegions);
        } catch (Throwable t) {
            BossLog.warn("注册场地回调失败: " + t);
        }
    }

    /** 显式初始化（供插件启动阶段调用，避免第一次查战局时才挂回调）。 */
    public static void init() {
        synchronized (LOCK) {
            ensureCallbacks();
        }
    }

    /** 清空（卸载 / 重载用）。 */
    public static void shutdown() {
        synchronized (LOCK) {
            BY_KEY.clear();
            BY_NAME.clear();
            BY_ENTITY.clear();
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 全部进行中的战局（快照）。 */
    public static List<BossState> all() {
        synchronized (LOCK) {
            return new ArrayList<>(BY_KEY.values());
        }
    }

    /** 战局数。 */
    public static int count() {
        synchronized (LOCK) {
            return BY_KEY.size();
        }
    }

    /** 有没有任何战局（原脚本 3 处「active = true」的遍历）。 */
    public static boolean any() {
        return count() > 0;
    }

    /** 按键（召唤者 UUID 字符串）取。 */
    public static BossState byKey(String key) {
        if (key == null) return null;
        synchronized (LOCK) {
            return BY_KEY.get(key);
        }
    }

    /** 按召唤者取（键即 UUID；旧记录按名字命名时回落名字查）。 */
    public static BossState bySummoner(Player player) {
        if (player == null) return null;
        BossState st = byKey(keyOf(player));
        if (st != null) return st;
        return byName(player.getName());
    }

    /**
     * 按玩家名取（原脚本 6 处）。
     *
     * <p><b>这里刻意不做「这局还活着吗」的过滤</b>：原脚本的
     * {@code findBossStateByPlayerName} 就是把表里那条原样返回。
     * 过滤会引入一个隐性差异 —— 本体刚被拆掉、状态还没收场的那一瞬间，
     * 「按名字找得到」与「找不到」会得出不同答案，而这种差异只会在
     * 极窄的时间窗里出现，最难排查。有效性判断交给 {@link #isActive}。</p>
     */
    public static BossState byName(String name) {
        if (name == null) return null;
        synchronized (LOCK) {
            return BY_NAME.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 按实体反查战局（原脚本 8 处线性遍历）。
     *
     * <p>返回的可能是「靠字段命中的那一局」，也可能是「实体已失效所以整局作废」——
     * 都是原脚本的语义，不做额外加工。</p>
     */
    public static BossState byEntity(Entity entity) {
        if (entity == null) return null;
        UUID id;
        try {
            id = entity.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
        if (id == null) return null;

        BossState cached;
        synchronized (LOCK) {
            cached = BY_ENTITY.get(id);
            if (cached != null) {
                if (contains(cached, entity)) return cached;
                // 缓存过期（实体已被移除 / 那局已收场）：丢掉再现场扫
                BY_ENTITY.remove(id);
            }
        }
        // 未命中：现场扫各局的实体表（1~3 局 × 约 15 个实体），扫到就补缓存。
        for (BossState st : all()) {
            if (st == null) continue;
            if (contains(st, entity)) {
                synchronized (LOCK) {
                    BY_ENTITY.put(id, st);
                }
                return st;
            }
        }
        return null;
    }

    /** 这个实体是否属于该局（委托给 {@link BossState#owns}，避免中间集合）。 */
    private static boolean contains(BossState st, Entity entity) {
        try {
            return st.owns(entity);
        } catch (Throwable ignored) {
            // 实体所在区块未加载：当作不属于
            return false;
        }
    }

    /**
     * 这支箭是不是某局登记在册的护法箭矢。
     *
     * <p>与 {@link #byEntity} 分开是必要的：箭矢是<b>每拍都在变</b>的临时实体，
     * 放进实体缓存会不断产生「刚缓存就过期」的抖动。</p>
     */
    public static BossState byArrow(Entity arrow) {
        if (arrow == null) return null;
        for (BossState st : all()) {
            if (st == null) continue;
            if (st.guardians.arrowOf(arrow) != null) return st;
        }
        return null;
    }

    /**
     * 按坐标找「场地覆盖了这里」的战局（原脚本 7 处）。
     *
     * <p>只遍历<b>拥有场地的那几局</b>（通常 1~2 局），不是全部战局。</p>
     */
    public static BossState byArenaAt(World world, int x, int y, int z) {
        if (world == null) return null;
        for (BossState st : all()) {
            if (st == null) continue;
            try {
                if (st.arena.oneRegion != null && st.arena.oneWorld != null
                        && sameWorld(world, st.arena.oneWorld)
                        && st.arena.oneRegion.contains(x, y, z)) {
                    return st;
                }
                if (st.arena.twoRegion != null && st.arena.twoWorld != null
                        && sameWorld(world, st.arena.twoWorld)
                        && st.arena.twoRegion.contains(x, y, z)) {
                    return st;
                }
            } catch (Throwable ignored) {
                // 世界已卸载：忽略这一局
            }
        }
        return null;
    }

    /** 按坐标找「双子经营小游戏区域覆盖了这里」的战局（原脚本 2 处）。 */
    public static BossState byMinigameAt(World world, int x, int y, int z) {
        if (world == null) return null;
        for (BossState st : all()) {
            if (st == null) continue;
            try {
                BossState.MinigameModule g = st.minigame;
                if (!g.running() || g.region == null || g.world == null) continue;
                if (!sameWorld(world, g.world)) continue;
                if (g.region.contains(x, y, z)) return st;
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return null;
    }

    private static boolean sameWorld(World a, World b) {
        try {
            return a == b || (a != null && b != null && a.getName().equals(b.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 场地层回调
    // ==================================================================

    /** 当前所有进行中战局持有的区域（供 {@link BossArena#inAnyFightArena}）。 */
    public static List<BossArena.Region> activeRegions() {
        List<BossArena.Region> out = new ArrayList<>();
        for (BossState st : all()) {
            if (st == null) continue;
            try {
                out.addAll(st.arena.regions());
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return out;
    }

    /** 供调试：一局的可读摘要。 */
    public static String describe(BossState st) {
        if (st == null) return "(null)";
        return "key=" + st.key + " 玩家=" + st.playerName
                + " 阶段=" + st.pendingPhase
                + " 护法=" + st.guardians.activeId
                + " 双子阶段=" + st.guardians.isTwinStage()
                + " 二阶段场地=" + st.arena.hasTwo()
                + " 收场中=" + st.removing;
    }

    /** 供调试：所有战局的摘要行。 */
    public static List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (BossState st : all()) out.add(describe(st));
        return out;
    }

    /** 该玩家是否已在挑战中（原脚本 {@code isTangranBossActive} 的语义）。 */
    public static boolean isActive(Player player) {
        if (player == null) return false;
        BossState st = bySummoner(player);
        return st != null && !st.isDead();
    }

    /** 便捷：该玩家参与（召唤 或 被卷进）的战局数。 */
    public static int countFor(Player player) {
        return bySummoner(player) != null ? 1 : 0;
    }
}
