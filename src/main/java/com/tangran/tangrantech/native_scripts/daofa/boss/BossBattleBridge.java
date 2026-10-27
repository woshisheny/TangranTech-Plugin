package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;
import com.tangran.tangrantech.native_scripts.daofa.TwinTools;

/**
 * 「道法/任务」与「道法/BOSS战」之间的桥接。
 *
 * <p>原 JS 版里 {@code 任务.js} 通过 {@code globalThis.startTangranBoss}、
 * {@code globalThis.getTangranGuardianRoster} 这类全局函数直连 {@code BOSS战.js}，
 * 甚至在缺函数时用 {@code eval(BOSS战.js)} 现场补加载。Java 里没有 {@code globalThis}，
 * 因此改为<b>注册式桥接</b>：BOSS战 移植完成后调用 {@link #register} 注册实现，
 * 任务这边只依赖本类。</p>
 *
 * <p>没有实现者时（BOSS战 尚未原生化）所有动作都安全降级：查询返回空、动作返回 false
 * 并提示「BOSS战脚本未加载或未实现」，与原脚本的行为一致。</p>
 *
 * <p><b>进度存档是直接读的</b>，不走桥接：{@code addon_configs/糖然科技/BOSS战/<玩家名>.json}
 * 的 {@code cleared} 数组（已通关的阶段号）。这样即使 BOSS战 未移植，
 * 任务的「通关三阶段后任务八显示已完成」也能正确工作。</p>
 */
public final class BossBattleBridge {

    private BossBattleBridge() {}

    /** 玩家在 BOSS战 里的进度。 */
    public record Progress(List<Integer> cleared) {

        public boolean has(int phase) {
            return cleared.contains(phase);
        }

        public boolean phase1Cleared() { return has(1); }

        public boolean phase2Cleared() { return has(2); }

        public boolean phase3Cleared() { return has(3); }

        public boolean phase4Cleared() { return has(4); }

        /**
         * 该阶段是否已解锁。
         *
         * <p>规则：阶段 1 永远可打；其余需要「前一阶段已通关」。</p>
         */
        public boolean unlocked(int phase) {
            return phase == 1 || has(phase - 1);
        }
    }

    /** 一位护法（含双子护法的成员列表）。 */
    public record Guardian(String id, String name, String iconMaterial,
                           List<Member> members, boolean unimplemented) {

        /** 双子护法的一位成员：封号 + 名字。 */
        public record Member(String title, String id) {}
    }

    /** BOSS战 侧需要提供的能力。 */
    public interface Provider {

        /** 从头开始挑战（阶段 1）。 */
        boolean start(Player player);

        /** 从指定阶段开始。 */
        boolean startPhase(Player player, int phase);

        /** 从指定护法开始（阶段 2 的读档）。 */
        boolean startPhaseFromGuardian(Player player, String guardianId);

        /** 该玩家是否已在挑战中。 */
        boolean isActive(Player player);

        /** 补发破法弓，返回本次实际发出的件数（已有则 0）。 */
        int givePofabow(Player player);

        /** 补发「中章装备全套」，返回本次实际发出的件数。 */
        int giveMidChapterKit(Player player, String reason);

        /** 护法名单（顺序即界面顺序）。 */
        List<Guardian> guardians();

        /** 该玩家是否已击败过这位护法。 */
        boolean hasBeatenGuardian(Player player, String guardianId);

        /** 造一个带皮肤与 lore 的护法头颅；拿不到皮肤时返回普通头颅。 */
        ItemStack makeGuardianHead(String guardianId, String name, List<String> lore);

        /** 写一条 BOSS战 的日志。 */
        void log(String message);

        // ------------------------------------------------------------------
        // 以下是「装备」侧回调 BOSS战 的能力。
        // 全部给 default 实现：BOSS战 尚未原生化时装备照常工作（只是少了与挑战联动的部分）。
        // ------------------------------------------------------------------

        /**
         * 近战攻击是否可以撤销 RSC 的伤害取消。
         *
         * <p>RSC 对带 script 的物品会无条件取消攻击事件，导致拿真·戮仙剑砍 Katarina
         * 一点伤害都没有。只有本局「可被近战打伤」的目标才应放行 —— 双子（只吃计数器伤害）、
         * zqx125（无敌）都不在名单里。</p>
         */
        default boolean swordCanUncancelDamage(Player damager, org.bukkit.entity.Entity victim) {
            return false;
        }

        /**
         * 破法弓箭矢命中双子护法：改成扣 BossBar 计数器。
         *
         * @return true 表示挑战侧已接管这次伤害，装备侧不要再按常规结算
         */
        default boolean twinArrowHit(org.bukkit.entity.Entity target, Player shooter,
                                     org.bukkit.entity.Entity arrow, double damage) {
            return false;
        }

        /** 双子头顶的展示盔甲架 → 真实本体；不是则返回 null。 */
        default org.bukkit.entity.Entity twinFromDisplay(org.bukkit.entity.Entity display) {
            return null;
        }

        /** 「双子经营」小游戏是否进行中（进行中：破坏方块不掉落、也不自动入包）。 */
        default boolean xuStageActive(Player player) {
            return false;
        }

        /** 把收割到的作物按「掉落数量」记进天实账本。 */
        default void xuLedgerCropBlock(Player player, org.bukkit.block.Block block,
                                       java.util.Collection<ItemStack> drops) {
            // 默认什么都不记
        }
    }

    private static volatile Provider provider;

    /** 由 BOSS战 的原生实现调用（在插件启动阶段）。 */
    public static void register(Provider impl) {
        provider = impl;
    }

    /** 当前是否有实现者。 */
    public static boolean available() {
        return provider != null;
    }

    private static Provider p() {
        return provider;
    }

    // ------------------------------------------------------------------
    // 进度（直接读存档，不依赖实现者）
    // ------------------------------------------------------------------

    /** 读玩家的 BOSS战 通关进度；文件缺失或损坏时返回空进度。 */
    public static Progress readProgress(String playerName) {
        try {
            File f = new File(AddonPaths.configDir("BOSS战"), PlayerKeys.key(playerName) + ".json");
            Map<String, Object> data = AddonPaths.readJson(f);
            if (data == null) return new Progress(new ArrayList<>());
            List<Integer> cleared = new ArrayList<>();
            for (Object o : Json.arr(data, "cleared")) {
                if (o instanceof Number n) cleared.add(n.intValue());
            }
            return new Progress(Collections.unmodifiableList(cleared));
        } catch (Throwable ignored) {
            return new Progress(new ArrayList<>());
        }
    }

    // ------------------------------------------------------------------
    // 动作（缺实现者时安全降级）
    // ------------------------------------------------------------------

    public static boolean start(Player player) {
        Provider impl = p();
        return impl != null && safe(() -> impl.start(player), false);
    }

    public static boolean startPhase(Player player, int phase) {
        Provider impl = p();
        return impl != null && safe(() -> impl.startPhase(player, phase), false);
    }

    public static boolean startPhaseFromGuardian(Player player, String guardianId) {
        Provider impl = p();
        return impl != null && safe(() -> impl.startPhaseFromGuardian(player, guardianId), false);
    }

    public static boolean isActive(Player player) {
        Provider impl = p();
        return impl != null && safe(() -> impl.isActive(player), false);
    }

    public static int givePofabow(Player player) {
        Provider impl = p();
        return impl == null ? 0 : safe(() -> impl.givePofabow(player), 0);
    }

    public static int giveMidChapterKit(Player player, String reason) {
        Provider impl = p();
        return impl == null ? 0 : safe(() -> impl.giveMidChapterKit(player, reason), 0);
    }

    public static List<Guardian> guardians() {
        Provider impl = p();
        if (impl == null) return List.of();
        List<Guardian> list = safe(impl::guardians, List.of());
        return list == null ? List.of() : list;
    }

    public static boolean hasBeatenGuardian(Player player, String guardianId) {
        Provider impl = p();
        return impl != null && safe(() -> impl.hasBeatenGuardian(player, guardianId), false);
    }

    public static ItemStack makeGuardianHead(String guardianId, String name, List<String> lore) {
        Provider impl = p();
        return impl == null ? null : safe(() -> impl.makeGuardianHead(guardianId, name, lore), null);
    }

    public static void log(String message) {
        Provider impl = p();
        if (impl != null) safe(() -> {
            impl.log(message);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    // ------------------------------------------------------------------
    // 装备 → BOSS战 的转发（供 {@code LuxianSword} / {@code Pofabow} / {@code TwinTools} 调用）
    // ------------------------------------------------------------------

    /** 近战是否可以撤销 RSC 的伤害取消。 */
    public static boolean swordCanUncancelDamage(Player damager, org.bukkit.entity.Entity victim) {
        Provider impl = p();
        return impl != null && safe(() -> impl.swordCanUncancelDamage(damager, victim), false);
    }

    /** 破法弓箭矢命中双子护法，是否已由挑战侧接管。 */
    public static boolean twinArrowHit(org.bukkit.entity.Entity target, Player shooter,
                                       org.bukkit.entity.Entity arrow, double damage) {
        Provider impl = p();
        return impl != null && safe(() -> impl.twinArrowHit(target, shooter, arrow, damage), false);
    }

    /** 双子头顶的展示盔甲架 → 真实本体。 */
    public static org.bukkit.entity.Entity twinFromDisplay(org.bukkit.entity.Entity display) {
        Provider impl = p();
        return impl == null ? null : safe(() -> impl.twinFromDisplay(display), null);
    }

    /** 双子经营小游戏是否进行中。 */
    public static boolean xuStageActive(Player player) {
        Provider impl = p();
        return impl != null && safe(() -> impl.xuStageActive(player), false);
    }

    /** 把收割到的作物记进天实账本。 */
    public static void xuLedgerCropBlock(Player player, org.bukkit.block.Block block,
                                        java.util.Collection<ItemStack> drops) {
        Provider impl = p();
        if (impl != null) safe(() -> {
            impl.xuLedgerCropBlock(player, block, drops);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    /** 统一的异常兜底：任何实现者的异常都不应冒泡到菜单交互里。 */
    private static <T> T safe(java.util.function.Supplier<T> action, T fallback) {
        try {
            T v = action.get();
            return v == null ? fallback : v;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 供卸载/调试使用。 */
    public static void shutdown() {
        provider = null;
    }
}
