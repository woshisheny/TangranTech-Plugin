package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * 「糖然二阶段挑战」的跨脚本共享状态。
 *
 * <p>原 JS 版把这份状态挂在 {@code globalThis} 上（{@code TR_XU_FIGHT_ACTIVE}）+
 * 玩家 metadata（{@code TR_BOSS_PHASE2_LOCK}），由 {@code BOSS战.js} 写、{@code 境界.js} 读。
 * 原生化后没有 {@code globalThis}，改由这个类承担：一个静态的「挑战中」时间窗表，
 * 外加玩家 metadata 锁的读写。</p>
 *
 * <p><b>为什么要两份</b>：原脚本注释记录过——metadata 被清掉的一瞬间不能让糖帝加成
 * 短暂「回归一下」，因此除 metadata 之外再加一道每 0.5 秒续期、15 秒过期的标记。
 * 两道都判，任一生效即视为挑战中。</p>
 *
 * <p>依赖方向是单向的：{@code 境界} 依赖本类，{@code BOSS战} 也依赖本类；
 * 两者之间不直接互相引用。</p>
 */
public final class BossChallengeState {

    private BossChallengeState() {}

    /** 玩家 metadata 锁的键（沿用原脚本的键名）。 */
    public static final String PHASE2_LOCK_KEY = "TR_BOSS_PHASE2_LOCK";

    /** 默认的「挑战中」有效窗口（ms），与 BOSS战 的续期周期 0.5 秒 / 15 秒过期一致。 */
    public static final long DEFAULT_ACTIVE_WINDOW_MS = 15_000L;

    /** 玩家名 → 挑战状态到期的绝对时间戳（ms）。 */
    private static final Map<String, Long> ACTIVE_UNTIL = new ConcurrentHashMap<>();

    /** BOSS战 每 0.5 秒调一次续期。 */
    public static void markActive(String playerName) {
        markActive(playerName, System.currentTimeMillis() + DEFAULT_ACTIVE_WINDOW_MS);
    }

    /** 指定到期时刻的续期。 */
    public static void markActive(String playerName, long untilMillis) {
        if (playerName == null) return;
        ACTIVE_UNTIL.put(playerName, untilMillis);
    }

    /** 挑战是否仍在有效窗口内。 */
    public static boolean isActive(String playerName) {
        if (playerName == null) return false;
        Long until = ACTIVE_UNTIL.get(playerName);
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            ACTIVE_UNTIL.remove(playerName);
            return false;
        }
        return true;
    }

    /** 主动结束（挑战收场时调）。 */
    public static void clear(String playerName) {
        if (playerName != null) ACTIVE_UNTIL.remove(playerName);
    }

    /**
     * 该玩家是否处于「二阶段挑战」中（metadata 锁 ∪ 时间窗标记）。
     *
     * <p>任意一路生效即视为锁定。任何异常都按「未锁定」处理，避免因为探测失败把玩家
     * 的属性永久压掉。</p>
     */
    public static boolean isPhaseTwoLocked(Player player) {
        if (player == null) return false;
        try {
            if (player.hasMetadata(PHASE2_LOCK_KEY)) return true;
        } catch (Throwable ignored) {
            // 落到时间窗判断
        }
        return isActive(player.getName());
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        ACTIVE_UNTIL.clear();
    }

    // ------------------------------------------------------------------
    // metadata 锁的读写（原脚本 setBossPhaseTwoLock / 残留恢复都用它）
    // ------------------------------------------------------------------

    /**
     * 设置 / 摘掉玩家身上的 metadata 锁（原脚本 {@code setBossPhaseTwoLock}）。
     *
     * @return 状态是否发生了变化
     */
    public static boolean setLock(Player player, boolean locked) {
        if (player == null) return false;
        try {
            if (locked) {
                if (player.hasMetadata(PHASE2_LOCK_KEY)) return false;
                player.setMetadata(PHASE2_LOCK_KEY,
                        new org.bukkit.metadata.FixedMetadataValue(TT.plugin, "1"));
                return true;
            }
            return removeLock(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 摘掉 metadata 锁。
     *
     * <p>收场与「重载后残留恢复」都用它。之所以要求传 plugin 实例：
     * {@code removeMetadata} 是按 (键, 所属插件) 配对的，用错实例摘不掉。</p>
     *
     * @return 原本有锁（本次摘掉了）返回 true
     */
    public static boolean removeLock(Player player) {
        if (player == null) return false;
        try {
            if (!player.hasMetadata(PHASE2_LOCK_KEY)) return false;
            player.removeMetadata(PHASE2_LOCK_KEY, TT.plugin);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
