package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * 入口脚本「糖然牌基础物质获取」：直接打开「糖然的种植园」（{@link SugarPlantation}）。
 *
 * <p>原先这里是一个 54 格菜单，靠「80% 概率出一颗糖、点它拿一颗糖」发糖；2026-10-26
 * 改为种植园玩法（种甘蔗 → 收获糖然牌基础物质），因此旧菜单整体移除——发糖逻辑
 * 现在只在 {@link SugarPlantation} 里，避免两处各有一份发奖入口。</p>
 */
public final class TangranBasicMatterScript implements NativeLifecycleScript {

    @Override
    public Object invoke(String fn, Object... args) {
        switch (fn) {
            case "onUse":
                if (args.length > 0 && args[0] instanceof org.bukkit.event.player.PlayerInteractEvent e) {
                    SugarPlantation.open(e.getPlayer());
                }
                return false;
            case "onButtonGroupClick":
                if (args.length > 0 && args[0] instanceof Player p) {
                    SugarPlantation.open(p);
                }
                return true;
            default:
                return null;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 种植园的点击/拖拽监听器在这里一并登记（它没有独立的 yml script 绑定）
        return List.of(SugarPlantation.listener());
    }
}
