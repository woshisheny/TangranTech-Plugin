package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import java.util.Collections;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;

/**
 * 「挑战区域清除器」的原生实现（对应 {@code scripts/道法/清理挑战区域.js}，115 行）。
 *
 * <p>绑定物品 {@code Tr_tzqycqq}（粘液球材质）。用法：右键「挑战区域最上层屏障正中间那一格的
 * 基岩」→ 把整块挑战区域清空。</p>
 *
 * <ul>
 *   <li>不消耗道具、不检测 OP —— 谁拿着都能用；</li>
 *   <li>只清「识别出来的那一块挑战区域」，区域外的方块与实体一律不碰；</li>
 *   <li>展示框 / 发光展示框 / 画 及其依附方块会保留（由 BOSS战 侧的装饰格收集逻辑决定）。</li>
 * </ul>
 *
 * <h2>真正的清场逻辑在 BOSS战 侧</h2>
 * <p>本脚本只负责「取点击方块 → 调用 → 给玩家回执」，所以它天然依赖
 * {@link BossBattleBridge#purgeArenaByMarker}。原脚本在这里有一段「缺函数就
 * {@code eval(BOSS战.js)}」的兜底加载，并特别注明<b>绝对不要用文件 mtime 当重载依据</b>
 * （一改文件就整份重跑，会把别人正在打的场地的战局状态冲掉）。</p>
 * <p>Java 版不需要这套兜底：BOSS战 的原生实现会在插件启动阶段
 * {@code BossBattleBridge.register(...)}，本脚本只需判断「实现者在不在」。</p>
 *
 * <p>因此本脚本在 BOSS战 移植完成之前是「可加载但不可用」的状态：右键会提示
 * 「BOSS战脚本未能加载」。这是刻意的 —— 等价于原脚本在 BOSS战.js 不存在时的表现。</p>
 */
public final class ClearChallengeAreaScript implements NativeLifecycleScript {

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            if ("onUse".equals(fn) && args.length > 0
                    && args[0] instanceof PlayerInteractEvent event) {
                return handle(event);
            }
            return null;
        } catch (Throwable t) {
            TT.log("[清理挑战区域] invoke(" + fn + ") 异常: " + t);
            return Boolean.TRUE;
        }
    }

    private static Object handle(PlayerInteractEvent event) {
        // 只响应右键方块
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return Boolean.FALSE;

        Block block = event.getClickedBlock();
        if (block == null) return Boolean.FALSE;
        // 只认「基岩」标记格：其它方块一律不响应（不会误清别的地方）
        if (block.getType() != Material.BEDROCK) return Boolean.FALSE;

        Player player = event.getPlayer();
        if (player == null) return Boolean.FALSE;

        if (!BossBattleBridge.available()) {
            player.sendMessage("§c挑战区域清除器：BOSS战脚本未能加载，清场失败（详见控制台）。");
            TT.log("BOSS战 未注册实现，清场请求被拒绝");
            return Boolean.TRUE;
        }

        BossBattleBridge.PurgeResult res = BossBattleBridge.purgeArenaByMarker(player, block);
        if (res == null || !res.ok()) {
            String reason = (res == null) ? "未知" : String.valueOf(res.reason());
            if ("not_marker".equals(reason)) {
                player.sendMessage("§c这里不是挑战区域顶层正中间的基岩，识别不出挑战区域。");
            } else {
                player.sendMessage("§c清场失败：§7" + reason);
            }
            return Boolean.TRUE;
        }

        player.sendMessage("§a挑战区域已清空§7（" + res.size() + "）：方块 §f" + res.blocks()
                + "§7，实体 §f" + res.entities() + "§7，战局 §f" + res.fights());
        if (res.protectedCells() > 0) {
            player.sendMessage("§7展示框/画 及其依附方块已保留（保护格 §f"
                    + res.protectedCells() + "§7 个）");
        }
        try {
            player.playSound(player.getLocation(), "block.beacon.deactivate", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 音效失败不影响功能
        }
        // 不消耗道具：这里什么都不动
        return Boolean.TRUE;
    }

    @Override
    public Iterable<Listener> listeners() {
        // 入口走 ScriptItem → invoke("onUse") 这一条通道，无需自注册监听器
        return Collections.emptyList();
    }
}
