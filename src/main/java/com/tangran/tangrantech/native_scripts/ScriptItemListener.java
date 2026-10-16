package com.tangran.tangrantech.native_scripts;

import com.tangran.tangrantech.items.ScriptItem;
import com.tangran.tangrantech.items.ScriptMachineItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 物品交互派发：把右键事件转发给持有物品绑定的原生脚本 {@code onUse}。
 *
 * <p>对应原 RSC 由脚本自行注册的 PlayerInteractEvent 监听。</p>
 */
public final class ScriptItemListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item != null && !item.getType().isAir()) {
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf instanceof ScriptItem scriptItem) {
                scriptItem.onRightClick(e);
                return;
            }
            if (sf instanceof ScriptMachineItem machine) {
                // 手持同类机器物品右键 = 「放置」动作，交给 Slimefun 处理；
                // 但仍往下走一次方块派发：脚本自己会用 isCancelled / 材质判定决定理不理
                machine.onRightClick(e);
            }
        }

        // 已放置的脚本机器方块：徒手（或拿着别的东西）右键也要能开界面。
        //
        // 原 RSC 版由 RSC 给机器注册右键回调，玩家放下机器后<b>空手</b>右键就能打开；
        // 而 ScriptItemListener 只看「手里拿的东西」，放置后的机器方块手里什么都没有
        // ⇒ 不补这一条，堆叠机放下后就再也点不开了。
        //
        // 用 e.isCancelled() 去重：脚本的 onUse 一旦处理过就会 setCancelled(true)，
        // 这里就不会再派发第二次（避免「手持机器物品右键另一台机器」时双触发）。
        if (e.isCancelled()) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        SlimefunItem placed;
        try {
            placed = BlockStorage.check(block);
        } catch (Throwable t) {
            return;
        }
        if (placed instanceof ScriptMachineItem machine) {
            machine.onRightClick(e);
        }
    }
}
