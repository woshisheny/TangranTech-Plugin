package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentBridge;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;
import com.tangran.tangrantech.native_scripts.daofa.ToolState;
import com.tangran.tangrantech.native_scripts.daofa.TwinTools;

/**
 * 「道法/装备」的门面（对应 {@code scripts/道法/装备.js}，3038 行）。
 *
 * <p>原脚本是一个 3000 行的单文件，装了三套互不相干的装备系统。原生化时按功能拆开，
 * 本类只做三件事：<b>注册监听器</b>、<b>启动每刻任务</b>、<b>把能力注册进
 * {@link EquipmentBridge}</b>，让 BOSS战 能调用。</p>
 *
 * <table border="1">
 *   <caption>拆分结果</caption>
 *   <tr><th>模块</th><th>类</th><th>内容</th></tr>
 *   <tr><td>戮仙剑</td><td>{@link LuxianSword}</td><td>御剑飞行、引雷、撞击伤害、附魔保障</td></tr>
 *   <tr><td>糖b四工具</td><td>{@link TwinTools}</td><td>范围挖矿/连锁伐木/范围铲土/范围耕地、功能菜单</td></tr>
 *   <tr><td>破法弓</td><td>{@link Pofabow}</td><td>特殊箭、9 项功能开关、药水箭选择、每刻箭结算</td></tr>
 *   <tr><td>开关存档</td><td>{@link ToolState}</td><td>四工具与破法弓共用的玩家存档</td></tr>
 * </table>
 *
 * <h2>为什么不再需要「监听器注册表」那套把戏</h2>
 * <p>原脚本里有一段相当绕的逻辑：它把监听器塞进 Bukkit 的 {@code ServicesManager}，
 * 用 {@code CopyOnWriteArrayList} / {@code CopyOnWriteArraySet} 当「键」，
 * 并在每次求值时先卸载旧监听器。原因是<b>同一个脚本会被 eval 多次</b>
 * （{@code items.yml} 的 {@code script:} 一次、{@code BOSS战.js} 里再 {@code eval} 一次），
 * 不这样处理就会出现「御剑翻倍、引雷两次」。</p>
 * <p>Java 版由 {@code NativeLifecycle} 保证<b>同一实现类只注册一次</b>，这套把戏整体不需要了。</p>
 *
 * <h2>关于「RSC 会取消带 script 物品的右键」</h2>
 * <p>原脚本里破法弓要在 {@code HIGHEST} 优先级把取消撤销（{@code setCancelled(false)}），
 * 否则原版拉弓根本不会发生。本插件不存在 RSC 那层取消，但这段撤销<b>予以保留</b> ——
 * 它只会把「别的插件取消掉的事件」恢复，而破法弓确实需要那颗右键事件。
 * 戮仙剑与四工具的 {@code setCancelled(true)} 同样保留（它们要阻止原版右键动作）。</p>
 */
public final class EquipmentScript implements NativeLifecycleScript {

    @Override
    public Object invoke(String fn, Object... args) {
        // 装备脚本本身没有 onUse 之类的脚本钩子：三套装备的行为全部由自注册的监听器接管。
        // 物品上挂 script 只是原 RSC 用来触发「加载期求值」的手段。
        return null;
    }

    @Override
    public Iterable<Listener> listeners() {
        return List.of(
                new LuxianSword.ListenerImpl(),
                new TwinTools.ListenerImpl(),
                new Pofabow.ListenerImpl());
    }

    @Override
    public void registerListeners(Plugin plugin) {
        if (plugin == null) return;
        // 破法弓的每刻任务：箭的寿命 / 轨迹 / 末影人预结算
        Pofabow.startTask(plugin);
        // 把三套装备的能力暴露给 BOSS战 与 任务
        EquipmentBridge.register(new ProviderImpl());
    }

    /** {@link EquipmentBridge.Provider} 的实现：把三套装备的能力转出去。 */
    private static final class ProviderImpl implements EquipmentBridge.Provider {

        @Override
        public int giveTwinTools(Player player) {
            return TwinTools.giveTools(player);
        }

        @Override
        public boolean hasLuxianSword(Player player) {
            return LuxianSword.hasSword(player);
        }

        @Override
        public boolean giveLuxianSword(Player player) {
            return LuxianSword.giveSword(player);
        }

        @Override
        public boolean isTwinTool(ItemStack stack, String toolId) {
            return TwinTools.isTool(stack, toolId);
        }

        // ---- 以下是 default 方法的实现 ----

        @Override
        public ItemStack makeTwinTool(String toolId, Player player) {
            return TwinTools.make(toolId, player);
        }

        @Override
        public int ensureTwinTools(Player player) {
            return TwinTools.ensureTools(player);
        }

        @Override
        public boolean twinSmeltActive(Player player) {
            try {
                if (player == null) return false;
                ItemStack tool = player.getInventory().getItemInMainHand();
                String id = TwinTools.defIdOf(tool);
                if (id == null) return false;
                if (!TwinTools.supportsSmelt(id)) return false;
                return TwinTools.stateOf(player, id).smelt();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public ItemStack makeLuxianSword() {
            return LuxianSword.make();
        }

        @Override
        public boolean isLuxianSword(ItemStack stack) {
            return LuxianSword.isSword(stack);
        }

        @Override
        public void startLuxianRide(Player player) {
            LuxianSword.startRide(player);
        }

        @Override
        public void stopLuxianRide(Player player) {
            LuxianSword.stopRide(player);
        }

        @Override
        public boolean isPofabow(ItemStack stack) {
            return Pofabow.isBow(stack);
        }

        @Override
        public boolean isPofabowArrow(org.bukkit.entity.Entity entity) {
            return entity instanceof org.bukkit.entity.Arrow arrow && Pofabow.isOurArrow(arrow);
        }

        @Override
        public double pfgTwinArrowDamage(org.bukkit.entity.Entity arrow, Player player) {
            if (arrow instanceof org.bukkit.entity.Arrow a) return Pofabow.twinArrowDamage(a, player);
            return 1.0;
        }
    }

    /** 卸载钩子（原为 static shutdown()，全库无调用方；现挂 {@code NativeScript#onUnload()}）。 */
    @Override
    public void onUnload() {
        try {
            LuxianSword.shutdown();
            TwinTools.shutdown();
            Pofabow.shutdown();
            ToolState.shutdown();
        } catch (Throwable t) {
            TT.log("[装备] 卸载清理失败: " + t);
        }
    }
}
