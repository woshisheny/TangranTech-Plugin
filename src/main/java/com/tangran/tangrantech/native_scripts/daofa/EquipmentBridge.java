package com.tangran.tangrantech.native_scripts.daofa;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;

/**
 * 「道法/任务」与「道法/装备」之间的桥接。
 *
 * <p>与 {@link BossBattleBridge} 同一套路：原 JS 版通过 {@code globalThis.giveTwinTools}、
 * {@code globalThis.hasLuxianSword} 等全局函数直连 {@code 装备.js}（缺函数时还会
 * {@code eval} 现场补加载）；Java 改为注册式桥接。</p>
 *
 * <p>装备.js 的原生实现完成后调用 {@link #register} 注册。没有实现者时所有动作安全降级，
 * 任务九的奖励校验会如实报告「还缺哪几件」。</p>
 */
public final class EquipmentBridge {

    private EquipmentBridge() {}

    /** 四件「双子工具」的物品 id（与 midChapterKitMissing 的认法一致）。 */
    public static final String[] TWIN_TOOL_IDS = {"Tr_ktg", "Tr_zjf", "Tr_fhc", "Tr_zdc"};
    /** 四件工具的中文名（报错提示用）。 */
    public static final String[] TWIN_TOOL_NAMES = {
            "鸿蒙开天镐", "太虚斩劫斧", "九幽覆海铲", "长生种道锄",
    };
    /** 破法弓的物品 id。 */
    public static final String BOW_ID = "TR_PFG";
    /** 戮仙剑的物品 id。 */
    public static final String SWORD_ID = "TR_LXJ";

    /** 装备侧需要提供的能力。 */
    public interface Provider {

        /** 补发四件双子工具，返回本次实际发出的件数（已有的不发）。 */
        int giveTwinTools(Player player);

        /** 该玩家是否已持有戮仙剑。 */
        boolean hasLuxianSword(Player player);

        /** 发放戮仙剑（已有则不动）。 */
        boolean giveLuxianSword(Player player);

        /**
         * 判断一件物品是不是指定的双子工具。
         *
         * <p>原脚本用它作为「按显示名认不出来」时的兜底（渐变名会把色码插在字与字之间，
         * 导致「鸿蒙开天镐」五个字并不连续，纯字符串匹配会失败）。</p>
         */
        boolean isTwinTool(ItemStack stack, String toolId);

        // ------------------------------------------------------------------
        // 以下是 BOSS战 侧回调「装备」的能力。全部给 default 实现，
        // 装备尚未就绪时 BOSS战 照常运行（只是不补发装备、不减伤）。
        // ------------------------------------------------------------------

        /** 造一件指定的双子工具（带附魔与套装 lore）。取不到返回 null。 */
        default ItemStack makeTwinTool(String toolId, Player player) {
            return null;
        }

        /** 补齐「中章装备全套」：缺哪件发哪件，返回本次补发的件数。 */
        default int ensureTwinTools(Player player) {
            return 0;
        }

        /** 手持的镐/铲是否开着「自动熔炼」（BOSS战 记「沙子→玻璃点」时要用）。 */
        default boolean twinSmeltActive(Player player) {
            return false;
        }

        /** 造一把戮仙剑。 */
        default ItemStack makeLuxianSword() {
            return null;
        }

        /** 是不是戮仙剑。 */
        default boolean isLuxianSword(ItemStack stack) {
            return false;
        }

        /** 强制上剑 / 下剑（BOSS战 的 Katarina 阶段要用）。 */
        default void startLuxianRide(Player player) {
            // 默认什么都不做
        }

        default void stopLuxianRide(Player player) {
            // 默认什么都不做
        }

        /** 是不是破法弓。 */
        default boolean isPofabow(ItemStack stack) {
            return false;
        }

        /** 这支箭是不是破法弓射出的（含标记与射手手持两条判据）。 */
        default boolean isPofabowArrow(org.bukkit.entity.Entity entity) {
            return false;
        }

        /** 破法弓箭矢对双子护法的伤害口径（双子按计数器掉血，够不到命中判定也照样结算）。 */
        default double pfgTwinArrowDamage(org.bukkit.entity.Entity arrow, Player player) {
            return 1.0;
        }
    }

    private static volatile Provider provider;

    public static void register(Provider impl) {
        provider = impl;
    }

    public static boolean available() {
        return provider != null;
    }

    public static int giveTwinTools(Player player) {
        Provider impl = provider;
        if (impl == null) return 0;
        try {
            return impl.giveTwinTools(player);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean hasLuxianSword(Player player) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.hasLuxianSword(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean giveLuxianSword(Player player) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.giveLuxianSword(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isTwinTool(ItemStack stack, String toolId) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.isTwinTool(stack, toolId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // BOSS战 → 装备 的转发
    // ------------------------------------------------------------------

    /** 造一件指定的双子工具。 */
    public static ItemStack makeTwinTool(String toolId, Player player) {
        Provider impl = provider;
        if (impl == null) return null;
        try {
            return impl.makeTwinTool(toolId, player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 补齐中章装备全套，返回补发件数。 */
    public static int ensureTwinTools(Player player) {
        Provider impl = provider;
        if (impl == null) return 0;
        try {
            return impl.ensureTwinTools(player);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 手持镐/铲是否开着自动熔炼。 */
    public static boolean twinSmeltActive(Player player) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.twinSmeltActive(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 造一把戮仙剑。 */
    public static ItemStack makeLuxianSword() {
        Provider impl = provider;
        if (impl == null) return null;
        try {
            return impl.makeLuxianSword();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isLuxianSword(ItemStack stack) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.isLuxianSword(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void startLuxianRide(Player player) {
        Provider impl = provider;
        if (impl == null) return;
        try {
            impl.startLuxianRide(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static void stopLuxianRide(Player player) {
        Provider impl = provider;
        if (impl == null) return;
        try {
            impl.stopLuxianRide(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static boolean isPofabow(ItemStack stack) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.isPofabow(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPofabowArrow(org.bukkit.entity.Entity entity) {
        Provider impl = provider;
        if (impl == null) return false;
        try {
            return impl.isPofabowArrow(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static double pfgTwinArrowDamage(org.bukkit.entity.Entity arrow, Player player) {
        Provider impl = provider;
        if (impl == null) return 1.0;
        try {
            return impl.pfgTwinArrowDamage(arrow, player);
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /** 供卸载/调试使用。 */
    public static void shutdown() {
        provider = null;
    }
}
