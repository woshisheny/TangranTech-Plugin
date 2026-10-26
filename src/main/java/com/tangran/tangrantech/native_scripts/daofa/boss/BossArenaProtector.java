package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 挑战专用世界（tangran_space）的场地保护（Dominion 式）。
 *
 * <h2>参考对象：LunaDeerMC/Dominion 领地插件的保护逻辑</h2>
 * <p>Dominion 把「领地内成员放行、其余一律拦」的口径拆成三类事件层
 * （源码 {@code events/player/Building/*}、{@code events/environment/*}）：</p>
 * <ol>
 *   <li><b>成员建造</b>（{@code Building/BreakBlock|Place*}）：非成员禁破坏/放置，
 *       成员不受限；</li>
 *   <li><b>环境破坏</b>（{@code Explosions/* / Fire/* / NaturalChanges/*}）：
 *       爆炸只摘领地内方块（保留实体伤害与特效）、火蔓延/液体流入/生物改方块取消；</li>
 *   <li><b>跨界机制</b>（{@code Mechanisms/PistonOutside/*}）：活塞推/拉的方块
 *       越过领地边界时取消，活塞本身留在界外不受影响。</li>
 * </ol>
 * <p>本类把同样的口径套到 tangran_space：<b>整个世界就是领地</b>，
 * 「成员」= 当前挑战者本人（管理员另行放行）。逐条对应：</p>
 * <table border="1">
 *   <caption>Dominion 保护面 → 本类 handler</caption>
 *   <tr><th>Dominion</th><th>本类</th></tr>
 *   <tr><td>Building/BreakBlock、Place*</td><td>{@code onBreak} / {@code onPlace}（非成员拦）</td></tr>
 *   <tr><td>Explosions/TNTExplode、Creeper* 等</td><td>{@code onEntityExplode}（只摘方块列表）</td></tr>
 *   <tr><td>Explosions/BlockExplode（床/重生锚）</td><td>{@code onBlockExplode}</td></tr>
 *   <tr><td>Fire/FireSpread</td><td>{@code onBlockSpread}</td></tr>
 *   <tr><td>NaturalChanges/FlowInProtection</td><td>{@code onLiquidFlow}</td></tr>
 *   <tr><td>CreatureBehavior/EnderMan、WitherBreakBlock</td><td>{@code onEntityChangeBlock}</td></tr>
 *   <tr><td>Mechanisms/PistonOutside（Extend/Retract）</td><td>{@code onPistonExtend} / {@code onPistonRetract}</td></tr>
 *   <tr><td>Building/BreakLiquid、PlaceLiquid</td><td>{@code onBucketEmpty} / {@code onBucketFill}（非成员拦）</td></tr>
 *   <tr><td>EntityProtection/*（领地内实体被外人打）</td><td>{@code onOutsiderDamageBoss}</td></tr>
 * </table>
 *
 * <h2>刻意不放行的几条口径核对（避免误伤玩法）</h2>
 * <ul>
 *   <li><b>重力方块（FallingBlock）放行</b>：天降铁砧（{@code BossState.Anvil}）靠
 *       FallingBlock 落地成形；按 EntityChangeBlockEvent 拦生物时必须排除它。</li>
 *   <li><b>成员的方块操作不拦</b>：双子经营的水源炼药锅（{@code TwinShop} 水瓶链）
 *       与资源采集走成员通道；二阶段前段的「禁交互」由 {@link BossEventRules} 自己管。</li>
 *   <li><b>爆炸只摘方块列表、不整体取消</b>：与 {@link BossEventRules#onExplode} 同口径，
 *       BOSS 的爆炸类攻击保留对玩家的伤害与特效。</li>
 *   <li><b>世界 gamerule 已关火蔓延/生物生成</b>：火蔓延与生物改方块两条是纵深防御
 *       （gamerule 被外部改掉时仍有事件层兜底）。</li>
 * </ul>
 */
public final class BossArenaProtector {

    private BossArenaProtector() {}

    /** 保护提示限流（1.5 秒一条，与 BossEventRules.warnLock 同口径）。 */
    private static final java.util.Map<String, Long> WARN_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 成员 = 挑战者本人；管理员始终放行。 */
    private static boolean allowed(Player player) {
        try {
            if (player == null || !player.isOnline()) return false;
            // 场地保护放行：OP / tangrantech.admin.arena / tangrantech.admin 任一成立
            if (com.tangran.tangrantech.util.Perms.arenaBypass(player)) return true;
            return BossFight.bySummoner(player) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void warn(Player player) {
        try {
            long now = System.currentTimeMillis();
            Long last = WARN_AT.get(player.getName());
            if (last != null && now - last < 1500L) return;
            WARN_AT.put(player.getName(), now);
            player.sendMessage("§c[糖然科技] 挑战场地受保护：只有当前挑战者可以在此操作。");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 清掉限流表条目（玩家退出时调用，避免表按历史玩家名无限增长）。 */
    private static void clearWarn(String playerName) {
        if (playerName != null) WARN_AT.remove(playerName);
    }

    /** 这格是否落在两座常驻场地里。 */
    private static boolean inResidentArena(Block b) {
        try {
            int x = b.getX();
            int y = b.getY();
            int z = b.getZ();
            return TangranSpace.REGION_ONE.contains(x, y, z)
                    || TangranSpace.REGION_TWO.contains(x, y, z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 活塞本体 / 被推拉方块 / 落点是否碰到常驻场地（Dominion PistonOutside 的跨界口径）。 */
    private static boolean touchesResidentArena(Block piston, List<Block> blocks, BlockFace dir) {
        try {
            if (piston != null && inResidentArena(piston)) return true;
            if (blocks == null) return false;
            for (Block b : blocks) {
                if (b == null) continue;
                if (inResidentArena(b)) return true;
                if (dir != null && inResidentArena(b.getRelative(dir))) return true;
            }
        } catch (Throwable ignored) {
            // 判不出来就按「碰到」处理，宁可多拦
            return true;
        }
        return false;
    }

    /** 投掷物的真实攻击者（箭 / 三叉戟 / 火球等取射手）。 */
    private static Player attackerOf(Entity damager) {
        try {
            if (damager instanceof Player p) return p;
            if (damager instanceof Projectile proj
                    && proj.getShooter() instanceof Player p) {
                return p;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 这个玩家是不是该局的召唤者。 */
    private static boolean isSummoner(BossState st, Player p) {
        try {
            if (st == null || p == null) return false;
            if (st.summonerUuid != null
                    && st.summonerUuid.equals(p.getUniqueId().toString())) return true;
            return st.playerName != null && st.playerName.equals(p.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 事件层（全部只在 tangran_space 生效，其余世界零开销）
    // ==================================================================

    public static final class Rules implements Listener {

        // ---- 成员建造：非成员禁破坏 / 放置（Dominion Building/*）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                if (allowed(event.getPlayer())) return;
                event.setCancelled(true);
                warn(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                if (allowed(event.getPlayer())) return;
                event.setCancelled(true);
                warn(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 桶装液体：非成员禁放 / 禁取（Dominion Building/BreakLiquid、PlaceLiquid）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBucketEmpty(PlayerBucketEmptyEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getPlayer().getWorld())) return;
                if (allowed(event.getPlayer())) return;
                event.setCancelled(true);
                warn(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBucketFill(PlayerBucketFillEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getPlayer().getWorld())) return;
                if (allowed(event.getPlayer())) return;
                event.setCancelled(true);
                warn(event.getPlayer());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 爆炸：只摘方块列表，保留实体伤害与特效（Dominion Explosions/*）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onEntityExplode(EntityExplodeEvent event) {
            try {
                Location l = event.getLocation();
                if (l == null || !TangranSpace.isSpace(l.getWorld())) return;
                if (event.blockList() != null && !event.blockList().isEmpty()) {
                    event.blockList().clear();
                    event.setYield(0f);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBlockExplode(BlockExplodeEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                if (event.blockList() != null && !event.blockList().isEmpty()) {
                    event.blockList().clear();
                    event.setYield(0f);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 火蔓延 / 液体流动：世界内一律取消（Dominion Fire/FireSpread、FlowInProtection）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBlockSpread(BlockSpreadEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onLiquidFlow(BlockFromToEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 生物改方块：玩家与重力方块（天降铁砧）放行，其余取消
        //      （Dominion CreatureBehavior/EnderMan、WitherBreakBlock）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onEntityChangeBlock(EntityChangeBlockEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                Entity e = event.getEntity();
                if (e instanceof Player || e instanceof FallingBlock) return;
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 活塞跨界：碰到常驻场地就取消（Dominion Mechanisms/PistonOutside）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onPistonExtend(BlockPistonExtendEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                if (touchesResidentArena(event.getBlock(), event.getBlocks(),
                        event.getDirection())) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onPistonRetract(BlockPistonRetractEvent event) {
            try {
                if (!TangranSpace.isSpace(event.getBlock().getWorld())) return;
                if (touchesResidentArena(event.getBlock(), event.getBlocks(),
                        event.getDirection())) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 领地内实体保护：外人（含投掷物）打战斗实体一律取消
        //      （Dominion EntityProtection/*；召唤者的攻击交给战斗自己的限伤规则）----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onOutsiderDamageBoss(EntityDamageByEntityEvent event) {
            try {
                Entity victim = event.getEntity();
                if (victim == null || !TangranSpace.isSpace(victim.getWorld())) return;
                BossState st = BossFight.byEntity(victim);
                if (st == null) return;
                Player attacker = attackerOf(event.getDamager());
                if (attacker == null) return;
                if (isSummoner(st, attacker)) return;
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 限流表清理（与 BossEventRules 的 LOCK_MSG 同口径）----

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuitCleanWarn(PlayerQuitEvent event) {
            try {
                if (event.getPlayer() != null) clearWarn(event.getPlayer().getName());
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
