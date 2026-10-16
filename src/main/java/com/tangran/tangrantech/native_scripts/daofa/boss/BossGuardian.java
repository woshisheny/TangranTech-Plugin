package com.tangran.tangrantech.native_scripts.daofa.boss;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 护法策略接口（原脚本里「zqx125 / Katarina / 双子」三段几乎重复的召唤与机制代码）。
 *
 * <h2>为什么用策略接口而不是 if-else</h2>
 * <p>原脚本用<b>一个 {@code phaseTwoGuardian} 字段被三位护法共用</b>，
 * 再靠 {@code getPhaseTwoGuardianId(state)} 在几十个分支里判「现在是谁」。
 * 后果（作者自己在代码注释里写过）：</p>
 * <blockquote>
 *   只按字段比对的话会把 Katarina 也当成双子（双子不吃实体伤害），
 *   结果戮仙剑砍她一点伤害都没有 —— 这就是「打不动 Katarina」的元凶。
 * </blockquote>
 * <p>策略化之后：每位护法一个实现类，<b>「现在是谁」由 {@code activeId} 显式表达</b>，
 * 「这位能不能被打」由各自的 {@link #canBeDamaged} 回答，不再靠「字段指向谁」去推断。</p>
 *
 * <h2>实例的生命周期</h2>
 * <p>{@link GuardianManager} <b>为每一局创建一个新实例</b>（不是全局单例）。
 * 这跟原脚本一致 —— 原脚本的护法状态也存在 {@code state} 上、每局一份。
 * 单例会让「上一局的箭矢节拍」漏到下一局，那种 bug 极难复现。</p>
 */
public interface BossGuardian {

    /** 护法 id（与 {@code BossState} 的常量一致：{@code zqx125} / {@code Katarina} / {@code D_RKIN}）。 */
    String id();

    /** 封号（BossBar 与本体名字前缀里的 {@code [天殛]} / {@code [天裁]} / {@code [天虚]}）。 */
    String title();

    /** 血量上限。 */
    double maxHp();

    /**
     * 召唤本体 + 血条 + 启动该护法的全部机制。
     *
     * @param player 本局玩家（可能为 {@code null}：读档直开时也要能跑）
     * @param target 召唤位置（糖然正前方）
     */
    void spawn(Player player, Location target);

    /**
     * 这个实体是否<b>可以被近战打伤</b>。
     *
     * <p>供装备侧的 {@code swordCanUncancelDamage} 回调使用。
     * zqx125 完全无敌（血量只在 BossBar 上）→ 恒 {@code false}；
     * Katarina 可打 → 本体有效时为 {@code true}；双子只吃破法弓计数器 → {@code false}。</p>
     */
    boolean canBeDamaged(Entity victim);

    /**
     * 「计数器类」伤害（举盾挡箭给护法造成的伤害 / 破法弓命中 / OP 指令直接扣）。
     *
     * @return 是否真的扣动了（血条不存在、护法已退场都算没扣动）
     */
    boolean damage(Player damager, double amount);

    /** 血量归零：记录进度、停机制、播放退场台词并把糖然血条往下压。 */
    void defeat(Player player);

    /** 停掉全部机制任务、移除本体与血条、清场（收场时必须幂等）。 */
    void cleanup();

    /** 供工厂注册用。 */
    @FunctionalInterface
    interface Factory {
        /**
         * 为一局创建一个护法实例。
         *
         * @param state 本局状态（护法把自己的状态存在各自的 {@code GuardianSlot} 上）
         */
        BossGuardian create(BossState state);
    }
}
