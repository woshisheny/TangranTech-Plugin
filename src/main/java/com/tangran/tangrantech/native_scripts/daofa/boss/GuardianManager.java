package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 护法注册表与路由（原脚本里散落各处的「找当前护法 / 判当前是谁 / 扣护法血」逻辑）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li><b>注册表</b>：护法 id → 工厂。BOSS战 在加载期把三位护法各注册一次。</li>
 *   <li><b>装配</b>：{@link #install} 按 {@code state.guardians.activeId} 造出本局的护法实例，
 *       存进 {@link BossState.GuardianModule#active}，然后调 {@link BossGuardian#spawn}。</li>
 *   <li><b>路由</b>：把「按实体反查」「按玩家反查」的请求转给当前护法实例。</li>
 *   <li><b>装配的逆操作</b>：{@link #discard}（收场时调，幂等）。</li>
 * </ul>
 *
 * <h2>实例为什么存在 state 上而不是本类的 Map 里</h2>
 * <p>放 {@code state.guardians.active} 上，实例的生命周期与战局<b>天然一致</b> ——
 * 战局被收掉，实例就跟着不可达，不存在「战局没了但 Map 里还挂着一个护法」这种泄漏。
 * 若放本类的 {@code Map<String, BossGuardian>}，就必须在每条收场路径上记得删 key，
 * 漏一条就泄漏一个还在跑定时任务的护法。</p>
 */
public final class GuardianManager {

    private GuardianManager() {}

    /** 护法 id → 工厂（插入序保留，便于日志按顺序打印）。 */
    private static final Map<String, BossGuardian.Factory> FACTORIES = new LinkedHashMap<>();

    /** 注册一位护法（加载期调一次）。 */
    public static void register(String guardianId, BossGuardian.Factory factory) {
        if (guardianId == null || factory == null) return;
        FACTORIES.put(guardianId, factory);
    }

    /** 已注册的护法 id 列表（顺序即注册顺序）。 */
    public static java.util.List<String> registeredIds() {
        return java.util.List.copyOf(FACTORIES.keySet());
    }

    /** 这位护法是否已注册（未注册时装配会给出明确日志，而不是静默不刷）。 */
    public static boolean isRegistered(String guardianId) {
        return guardianId != null && FACTORIES.containsKey(guardianId);
    }

    // ==================================================================
    // 装配
    // ==================================================================

    /**
     * 为本局装配并召唤护法。
     *
     * @return 装配好的护法实例；护法未注册 / 已装配过则返回 {@code null}
     */
    public static BossGuardian install(BossState state, Player player, Location target) {
        try {
            if (state == null) return null;
            if (state.guardians.active != null) return state.guardians.active;   // 幂等
            String gid = state.guardians.activeId;
            BossGuardian.Factory factory = FACTORIES.get(gid);
            if (factory == null) {
                BossLog.warn("护法未注册，无法召唤：" + gid
                        + "（已注册：" + registeredIds() + "）");
                return null;
            }
            BossGuardian guardian = factory.create(state);
            state.guardians.active = guardian;
            // 槽位数据（血条 / 上限 / 可打标记）由护法在自己的 spawn 里填，
            // 这里不预填 —— 预填一份「看起来对」的值，反而会在 spawn 失败时留下假数据。
            guardian.spawn(player, target);
            BossLog.warn("召唤护法：" + gid + "（" + guardian.title() + "）"
                    + " 血量上限 " + guardian.maxHp());
            return guardian;
        } catch (Throwable t) {
            BossLog.warn("装配护法失败（" + state.guardians.activeId + "）：" + t);
            return null;
        }
    }

    /** 本局当前的护法实例（没有则 {@code null}）。 */
    public static BossGuardian of(BossState state) {
        return (state == null) ? null : state.guardians.active;
    }

    /** 收掉本局的护法（幂等；战局收场时调）。 */
    public static void discard(BossState state) {
        try {
            BossGuardian g = of(state);
            if (g == null) return;
            state.guardians.active = null;      // 先摘，保证 cleanup 里重入也只会跑一次
            g.cleanup();
        } catch (Throwable t) {
            BossLog.warn("收掉护法失败: " + t);
        }
    }

    // ==================================================================
    // 路由
    // ==================================================================

    /** 这个实体能否被近战打伤（转给当前护法；没有护法则 {@code false}）。 */
    public static boolean canBeDamaged(BossState state, Entity victim) {
        BossGuardian g = of(state);
        return g != null && safeBool(() -> g.canBeDamaged(victim));
    }

    /** 计数器伤害（举盾挡箭 / 破法弓 / OP 指令）。 */
    public static boolean damage(BossState state, Player damager, double amount) {
        BossGuardian g = of(state);
        return g != null && safeBool(() -> g.damage(damager, amount));
    }

    /** 护法血量归零。 */
    public static boolean defeat(BossState state, Player player) {
        BossGuardian g = of(state);
        if (g == null) return false;
        boolean ok = safeBool(() -> {
            g.defeat(player);
            return true;
        });
        discard(state);
        return ok;
    }

    private static boolean safeBool(java.util.function.BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (Throwable t) {
            BossLog.warn("护法调用异常: " + t);
            return false;
        }
    }

    /** 供卸载/调试使用。 */
    public static void shutdown() {
        FACTORIES.clear();
    }
}
