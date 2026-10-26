package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentScript;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineScript;

/**
 * 「道法/BOSS战」的门面（原脚本 13452 行 / 465 个函数）。
 *
 * <h2>当前进度</h2>
 * <table border="1">
 *   <caption>分层进度</caption>
 *   <tr><th>层</th><th>类</th><th>状态</th></tr>
 *   <tr><td>日志</td><td>{@link BossLog}</td><td>✅</td></tr>
 *   <tr><td>存档层</td><td>{@link BossProgress}</td><td>✅</td></tr>
 *   <tr><td>场地层</td><td>{@link BossArena}</td><td>✅</td></tr>
 *   <tr><td>状态容器</td><td>{@link BossState}</td><td>✅（按模块切开，见类注释）</td></tr>
 *   <tr><td>战局表</td><td>{@link BossFight}</td><td>✅（把原脚本 32 处线性遍历换成索引）</td></tr>
 *   <tr><td>背包安全网</td><td>{@link BossInventory}</td><td>✅</td></tr>
 *   <tr><td>门面 + 桥接</td><td>本类</td><td>✅ 已接入</td></tr>
 *   <tr><td>护法（三位全部）</td><td>{@link Zqx125Guardian} / {@link BossKatarina} / {@link BossTwin}</td><td>✅</td></tr>
 *   <tr><td>阶段一</td><td>{@link BossPhaseOne}</td><td>✅</td></tr>
 *   <tr><td>战斗入口</td><td>{@link BossFightEntry}</td><td>✅（含二阶段入口分派）</td></tr>
 *   <tr><td>阶段二</td><td>{@link BossPhaseTwo}</td><td>✅ 石英场 + 戮仙剑序列 + 召唤仪式 + zqx125 战</td></tr>
 *   <tr><td>双子经营 / 天实商店</td><td>{@link TwinLedger} / {@link TwinMinigame} / {@link TwinShop}</td><td>✅（账本 / 虚仆 / 资源 / 挑战道具 / 商店 / 增益 / 吸血 / 水瓶链）</td></tr>
 *   <tr><td>阶段三 / 四</td><td>{@link BossPhaseThreeFour}</td><td>✅（糖然决斗 / 跪像 / 天道证明）</td></tr>
 *   <tr><td>事件层公共规则</td><td>{@link BossEventRules}</td><td>✅（防收纳三道锁 / 防爆防火 / 展示架收场等 18 条）</td></tr>
 *   <tr><td>BGM / 皮肤 / 启动自清理</td><td>{@link BossMusic} / {@link BossGuardianSkin} / {@link BossArena}</td><td>✅（批次 9/10）</td></tr>
 * </table>
 *
 * <h2>入口从哪来</h2>
 * <p>BOSS战 <b>没有任何 items.yml / machines.yml / groups.yml 绑定</b> ——
 * 它是纯「被调用」脚本（原脚本暴露了 28 个 {@code globalThis.*} 给别的脚本调）。
 * 唯一入口是「道法/任务」的挑战按钮 → {@code BossBattleBridge.start(...)}，
 * 以及阶段内的 {@code /tangranboss_accept} / {@code _decline} 命令。</p>
 *
 * <h2>本类承担的三件「安全网」职责（都不是玩法，而是防丢东西）</h2>
 * <ol>
 *   <li>{@link #forceStop} —— 统一的收场入口（幂等）。原脚本的收场逻辑散在
 *       「清场器 / 玩家退出 / 出错兜底 / 定时任务自检」四处，各写一遍、
 *       各自漏一两个任务句柄。这里收敛成一个方法，谁要收场都调它。</li>
 *   <li>{@link #recoverStranded} —— 重载后把「已经不在任何战局里、却还带着挑战锁」的
 *       玩家解锁。不做的话这些人会被永久压制属性。</li>
 *   <li>加入 / 重生时的备份返还（见内部 {@link JoinListener}）—— 不做的话
 *       中途退出过的玩家背包就永远回不来了。</li>
 * </ol>
 */
public final class BossBattleScript implements NativeLifecycleScript {

    @Override
    public void registerListeners(Plugin plugin) {
        BossFight.init();
        // 护法注册表：zqx125（批次 2）+ Katarina（批次 5）+ 双子（批次 6）
        GuardianManager.register(BossState.GID_ZQX125, Zqx125Guardian::new);
        GuardianManager.register(BossState.GID_KATARINA, BossKatarina::new);
        GuardianManager.register(BossState.GID_TWIN_A, BossTwin::new);
        // 下一位护法登场序列：Katarina / 双子都已接入（路由器见 BossKatarina.arriveRouter）
        BossPhaseTwo.setNextArrival(BossKatarina::arriveRouter);
        // 双子全灭后的糖然下场决斗（批次 7）
        BossPhaseTwo.setTangranDuelStart(BossPhaseThreeFour::startDuel);
        // 双子经营小游戏（批次 6b/6c）：启停钩子；通关时收掉账本买的增益
        BossTwin.setXuGameHook(new BossTwin.XuGameHook() {
            @Override
            public void start(Player player, BossState state) {
                TwinMinigame.start(player, state);
            }

            @Override
            public void stop(BossState state) {
                TwinMinigame.stop(state);
            }

            @Override
            public void clearUpgrades(Player player) {
                TwinShop.clearUpgrades(player);
            }
        });
        BossBattleBridge.register(new Impl());

        // 启动自清理：先还原残留场地 + 收残留实体（批次 10），
        // 再把没归还的备份还了、解锁滞留玩家。顺序有讲究 ——
        // 先清场（涉及方块/实体），再还物品，再解锁（涉及属性），
        // 反过来也行，但这个顺序让异常更容易归因。
        // cleanupStaleStartup 仍会清理旧版本在主世界留下的场地记录；
        // 专用世界（tangran_space）的常驻场地则由 resetAll 重建/重置成干净模板。
        int stale = BossArena.cleanupStaleStartup();
        int restored = BossInventory.restoreOrphans();
        int unlocked = recoverStranded();
        TangranSpace.resetAll();

        BossLog.warn("BOSS战 已全量接入：日志/存档/场地/战局/背包安全网/阶段一/二阶段(zqx125+Katarina+双子)"
                + "/三·四阶段/天实商店/账本/事件层/BGM 就绪"
                + (stale > 0 ? "；自清理残留 " + stale + " 项" : "")
                + (restored > 0 ? "；返还孤儿背包 " + restored + " 份" : "")
                + (unlocked > 0 ? "；解锁滞留玩家 " + unlocked + " 名" : ""));
    }

    @Override
    public Iterable<Listener> listeners() {
        // 阶段战斗的事件层随各阶段一起做（原脚本 34 个注册点 → 按「事件 + 优先级」合并成 27 个 handler）。
        // 这里只挂四类「现在就必须有」的：
        //   ① JoinListener     —— 背包/锁/禁御剑的恢复（安全网）；
        //   ② ItemInteractListener —— 生命甘露与反击之盾的右键（甘露材质是虚弱药水，
        //      没有入口玩家右键会真的喝出虚弱，而不是回血）；
        //   ③ ArenaListener    —— 一阶段的完整性：二段跳 / 逃逸判定 / 退出 / 死亡。
        //      没有 ③ 一阶段等于没有：玩家飞出屏障、或者退出再进就能跳过整场挑战。
        //   ④ KatarinaListener —— Katarina 的两条限伤规则 + 火球弹反（挥砍）。
        //      ⚠️ 批次 8 统一整理事件层时，把这三条按「事件 + 优先级」并进总表，
        //         不要重复注册（Katarina 的规则见 BossKatarina 类注释）。
        return List.of(new JoinListener(), new ItemInteractListener(),
                new ArenaListener(), new ChoiceCommandListener(), new KatarinaListener(),
                new TwinMinigameListener(), new TwinShop.ShopListener(), new BossEventRules.Rules(),
                new BossArenaProtector.Rules(), new SurrenderChatListener());
    }

    @Override
    public Object invoke(String fn, Object... args) {
        // BOSS战 没有物品入口，onUse 不会被调用；阶段内的接受/拒绝由命令监听处理。
        return null;
    }

    // ==================================================================
    // 安全网 1：统一收场
    // ==================================================================

    /**
     * 强制收掉一局（幂等）。
     *
     * <p>对应原脚本「清场器里那一大段逐个 cancel + remove + 摘血条 + 解锁 + 还背包」
     * （原脚本 2542 行附近）。收场顺序：</p>
     * <ol>
     *   <li><b>先置 {@code removing} 并摘出战局表</b> —— 原脚本各定时器开头都有
     *       「我还在这张表里吗」的守卫，摘表即自停；置标记同时挡住重入。</li>
     *   <li>收定时任务（按模块逐个收，见 {@link BossState#cancelAll()}）。</li>
     *   <li>收实体（本体 / 护法 / 展示架 / 箭矢 / 仆从 / 御剑展示体）。</li>
     *   <li>收血条（<b>只摘这一局的</b>，绝不 clearAll）。</li>
     *   <li>解锁 + 结算御剑 + 还背包。</li>
     * </ol>
     *
     * @param reason 收场原因，只写日志（玩家看不到，避免刷屏）
     */
    public static void forceStop(BossState state, String reason) {
        if (state == null) return;
        if (state.removing) return;          // 幂等：重复调只生效一次
        state.removing = true;

        try {
            // 1) 摘表：让各定时任务的自检守卫把它们自己停下来
            BossFight.remove(state);

            // 2) 先收护法：它自己的一堆机制任务、本体与血条由它清，
            //    而且它的 cleanup 可能还要读场地数据（例如把红石块复原成石英），
            //    必须赶在「清场地 / 摘战局」之前。
            GuardianManager.discard(state);

            // 3) 收定时任务
            state.cancelAll();

            // 3) 收实体
            BossState.removeEntity(state.stand);
            state.stand = null;
            BossState.removeEntity(state.tangran.duelMob);
            state.tangran.duelMob = null;
            BossState.removeEntity(state.tangran.kneelStand);
            state.tangran.kneelStand = null;
            for (BossState.GuardianSlot slot : state.guardians.all()) {
                BossState.removeEntity(slot.body);
                BossState.removeEntity(slot.body2);
                BossState.removeEntity(slot.displayA);
                BossState.removeEntity(slot.displayB);
                slot.body = null;
                slot.body2 = null;
                slot.displayA = null;
                slot.displayB = null;
            }
            // 4) 收血条
            BossState.removeBar(state.bar);
            state.bar = null;
            for (BossState.GuardianSlot slot : state.guardians.all()) {
                BossState.removeBar(slot.bar);
                slot.bar = null;
                BossState.removeBar(slot.bar2);   // 双子第二位（天实）的血条
                slot.bar2 = null;
            }

            // 4.5) 二阶段收尾：停 BGM + 还原石英场（原脚本 removeBossStand 的
            //      stopPhaseTwoMusic → restorePhaseTwoArena 顺序）。
            //      不做的话强制收场会留下一座浮空石英场，只有重启后的自清理能捡。
            try {
                BossMusic.stopPhaseTwo(state);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                // 两座常驻场地都重置回模板（清掉本场改动 → 重建）：
                // 一阶段场地在胜利路径不再清（玩家要在场内缓降/过场），统一在收场重置
                BossPhaseOne.restoreArena(state);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                BossPhaseTwo.restoreArena(state);
            } catch (Throwable ignored) {
                // 忽略
            }

            // 5) 解锁 + 御剑 + 背包
            Player owner = null;
            try {
                owner = Bukkit.getPlayerExact(state.playerName);
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner != null) {
                // 专用世界：把挑战者送回入口前的位置（失败/退出/通关收场统一走这里）。
                // 玩家已死亡时传送不可靠——复活落点由 JoinListener 的 Respawn 处理兜底。
                try {
                    if (TangranSpace.isSpace(owner.getWorld())) {
                        org.bukkit.Location back = state.arena.returnLoc;
                        if (back == null || back.getWorld() == null) back = TangranSpace.mainWorldSpawn();
                        if (back != null) {
                            state.ignoringTeleport = true;
                            owner.teleport(back);
                            BukkitTask clearTp = (TT.plugin == null) ? null
                                    : Bukkit.getScheduler().runTaskLater(TT.plugin,
                                            () -> state.ignoringTeleport = false, 20L);
                            if (clearTp == null) state.ignoringTeleport = false;
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 御剑：收场时一定要下剑，否则玩家会带着飞行状态掉进虚空
                try {
                    com.tangran.tangrantech.native_scripts.daofa.LuxianSword.stopRide(owner);
                } catch (Throwable ignored) {
                    // 装备脚本未加载 / 未骑乘：忽略
                }
                // 二阶段饱和：挑战期间永久饱和，收场必须摘掉，否则玩家一直不掉饥饿度
                try {
                    BossPhaseTwo.clearSaturation(owner);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // Katarina 阶段的玩家增益（速度 III / 跳跃提升 I）：护法轮换期间保持，
                // 整局收场才摘（与原脚本 removeBossStand 的顺序一致）
                try {
                    BossKatarina.clearBuffs(owner);
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    BossChallengeState.removeLock(owner);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 背包：还不了（掉落中 / 已下线）就留着文件，等加入 / 重生时补还
                try {
                    BossInventory.restore(owner);
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    LuxianSword.clearNoSwordRide(owner);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            BossChallengeState.clear(state.playerName);

            BossLog.warn("收场（" + reason + "）：" + BossFight.describe(state));
        } catch (Throwable t) {
            BossLog.warn("收场失败（" + reason + "）：" + t);
        }
    }

    // ==================================================================
    // 安全网 2：重载后的滞留玩家
    // ==================================================================

    /**
     * 重载后解锁「已经不在任何战局里、却还带着挑战标记」的玩家
     * （原脚本 {@code xuRecoverStrandedPlayers}）。
     *
     * <p>为什么必须做：战局表是<b>纯内存</b>的。脚本一重载，战局全丢，
     * 但玩家身上的 {@code TR_BOSS_PHASE2_LOCK} / {@code TR_BOSS_NO_SWORD_RIDE}
     * 是 metadata，<b>活得过重载</b>。不还原的话这些玩家会被永久压制属性、永远不能御剑。</p>
     *
     * @return 解锁的人数
     */
    public static int recoverStranded() {
        int n = 0;
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null || !p.isOnline()) continue;
                try {
                if (BossFight.isActive(p)) continue;   // 正常挑战中的：不动
                boolean touched = false;
                if (BossChallengeState.removeLock(p)) touched = true;
                if (LuxianSword.clearNoSwordRide(p)) touched = true;
                // Katarina 增益也活得过重载：滞留玩家一并摘掉
                if (p.hasPotionEffect(PotionEffectType.SPEED)
                        || p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    BossKatarina.clearBuffs(p);
                    touched = true;
                }
                BossChallengeState.clear(p.getName());
                    if (touched) {
                        n++;
                        try {
                            // 滞留玩家若人还在专用世界里（重载期间战局丢失），送回主世界
                            if (TangranSpace.isSpace(p.getWorld())) {
                                org.bukkit.Location back = TangranSpace.mainWorldSpawn();
                                if (back != null) p.teleport(back);
                            }
                            p.sendMessage("§e[糖然科技] §f检测到挑战已中断：挑战锁已解除、"
                                    + "境界属性开关已还原；异火需要重新选择。");
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个玩家失败不影响其它人
                }
            }
            if (n > 0) BossLog.warn("启动自清理：解除 " + n + " 名玩家的残留挑战状态");
        } catch (Throwable t) {
            BossLog.warn("残留挑战状态恢复失败: " + t);
        }
        return n;
    }

    /**
     * 一阶段（以及所有阶段）的完整性事件。
     *
     * <p>四条，缺任何一条一阶段都能被「合法绕开」：</p>
     * <ol>
     *   <li><b>二段跳</b>：挑战期间临时放开飞行权限只是为了拿到那个飞行开关事件，
     *       真正要做的是取消它并改成向上弹射。不取消的话玩家会真的飞起来。</li>
     *   <li><b>逃逸判定</b>：传送/移动出场地即判负。
     *       <b>{@code ignoringTeleport} 豁免只读不写回 false</b> ——
     *       原脚本注释说明：一次脚本传送可能命中多套监听（热重载会泄漏），
     *       第一套把 true 吃掉后，后面几套会把它当「玩家逃跑」。
     *       置回由调用方在传送完成后统一做（一阶段是 20 刻后）。</li>
     *   <li><b>退出</b>：先把飞行权限收掉再判负，否则玩家会带着飞行状态退出。</li>
     *   <li><b>死亡</b>：<b>延后 1 tick 再判</b> —— 厚土异火/图腾等免死效果会取消死亡，
     *       若当场判负会把「假死」当成真死，提前清空战斗状态导致背包备份无法归还。</li>
     * </ol>
     */
    private static final class ArenaListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onToggleFlight(PlayerToggleFlightEvent event) {
            try {
                BossState st = BossFight.bySummoner(event.getPlayer());
                if (st == null) return;
                // onToggleFlight 内部自己判「是不是一阶段进行中」，
                // 返回 true 才取消事件 —— 不是一阶段时绝不去动玩家的飞行权限。
                if (BossPhaseOne.onToggleFlight(st, event.getPlayer())) {
                    event.setCancelled(true);
                }
            } catch (Throwable t) {
                BossLog.warn("二段跳派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            try {
                Player p = event.getPlayer();
                BossState st = BossFight.bySummoner(p);
                if (st == null) return;
                if (st.ignoringTeleport) return;      // 系统传送：不判逃逸
                Location from = event.getFrom();
                Location to = event.getTo();
                if (to == null) return;
                if (from.getWorld() != null && to.getWorld() != null
                        && from.getWorld().getName().equals(to.getWorld().getName())
                        && from.distanceSquared(to) < 0.01d) {
                    return;                             // 原地转向：不算传送
                }
                BossPhaseOne.fail(st, p, "你离开了挑战区域。");
            } catch (Throwable t) {
                BossLog.warn("逃逸判定失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onQuit(PlayerQuitEvent event) {
            try {
                Player quitting = event.getPlayer();
                BossState st = BossFight.bySummoner(quitting);
                if (st != null) {
                    // 先收飞行权限，别让玩家带着飞行状态退出
                    try {
                        boolean allow = st.phaseOne.originalAllowFlight;
                        quitting.setFlying(false);
                        quitting.setAllowFlight(allow);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    BossPhaseOne.fail(st, quitting, "你退出了游戏。");
                }
            } catch (Throwable t) {
                BossLog.warn("退出判负失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(PlayerDeathEvent event) {
            try {
                Player dead = event.getEntity();
                BossState st = BossFight.bySummoner(dead);
                if (st == null) return;
                // 二阶段发的挑战装备不要让玩家掉在地上（否则复活后还能捡回来）
                if (BossChallengeState.isPhaseTwoLocked(dead)) {
                    try {
                        event.getDrops().clear();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                // 延后 1 tick：免死效果会取消死亡，假死不当真死
                BukkitTask t = TT.plugin == null ? null
                        : Bukkit.getScheduler().runTaskLater(TT.plugin, () -> {
                            try {
                                if (!dead.isDead()) return;
                                BossPhaseOne.fail(st, dead, "你已死亡。");
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }, 1L);
                if (t == null) BossPhaseOne.fail(st, dead, "你已死亡。");
            } catch (Throwable t) {
                BossLog.warn("死亡判负失败: " + t);
            }
        }
    }

    /**
     * 可点击命令：{@code /tangranboss_accept} 与 {@code /tangranboss_decline}。
     *
     * <p>原脚本是把两个 {@code Command} 注册进 {@code CommandMap}，
     * 并且每次脚本热重载都要先反注册旧的（否则重载一次多一套命令）。
     * Java 侧 {@link NativeLifecycle} 已保证幂等注册，所以这里直接用
     * {@code PlayerCommandPreprocessEvent} 拦命令、取消原事件，
     * 不再需要动态注册/反注册 Command 的那套把戏。</p>
     */
    private static final class ChoiceCommandListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
            try {
                String raw = event.getMessage().trim();
                while (raw.startsWith("/")) raw = raw.substring(1);
                String cmd = raw.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                if (cmd.equals("tangranboss_accept")) {
                    event.setCancelled(true);
                    BossFightEntry.handleAccept(event.getPlayer());
                } else if (cmd.equals("tangranboss_decline")) {
                    event.setCancelled(true);
                    BossFightEntry.handleDecline(event.getPlayer());
                }
            } catch (Throwable t) {
                BossLog.warn("点击命令派发失败: " + t);
            }
        }
    }

    /**
     * 聊天投降口令：「我不玩了」→ 终止挑战并送回原位。
     *
     * <p>开局时（{@link BossFightEntry#start}）会提示这条口令。匹配口径：整条消息
     * 去掉首尾空白后<b>全等</b>「我不玩了」——不做包含匹配，聊天里聊到这四个字
     * 不能把人踢出场。没有战局的玩家发送时按普通聊天原样放行。</p>
     *
     * <p>收场统一走 {@link #forceStop}：它自带「专用世界 → 回程位置（returnLoc）」
     * 的传送，本监听不重复搬人。聊天事件在异步线程触发，实体收场与传送必须回主线程，
     * 回主线程时按 key 重查战局表——异步窗口内战局可能已被其它路径收掉，
     * 拿旧对象强收会绕过幂等标记 {@code removing} 的自检。</p>
     */
    private static final class SurrenderChatListener implements Listener {

        private static final String PHRASE = "我不玩了";

        @EventHandler(priority = EventPriority.NORMAL)
        @SuppressWarnings("deprecation")
        public void onChat(AsyncPlayerChatEvent event) {
            try {
                String msg = event.getMessage();
                if (msg == null || !PHRASE.equals(msg.trim())) return;
                Player player = event.getPlayer();
                BossState st = BossFight.bySummoner(player);
                if (st == null) st = BossFight.byName(player.getName());
                if (st == null) return;      // 没在挑战：普通聊天放行
                event.setCancelled(true);
                player.sendMessage("§e[糖然科技] §f已收到，正在为你终止挑战并送回原位...");
                String key = st.key;
                String name = st.playerName;
                com.tangran.tangrantech.native_scripts.NativePluginHelper.runOnMain(() -> {
                    try {
                        BossState live = BossFight.byKey(key);
                        if (live != null) {
                            BossBattleScript.forceStop(live, "玩家在聊天栏发送「我不玩了」主动放弃");
                        }
                        Player back = (name == null) ? null : Bukkit.getPlayerExact(name);
                        if (back != null && back.isOnline()) {
                            back.sendMessage("§a[糖然科技] 挑战已终止，你已被送回原位。");
                        }
                    } catch (Throwable t) {
                        BossLog.warn("聊天投降收场失败: " + t);
                    }
                });
            } catch (Throwable ignored) {
                // 聊天处理失败不能让异常冒到事件总线
            }
        }
    }

    /** 二阶段道具右键：生命甘露 / 加强甘露 / 反击之盾。 */
    private static final class ItemInteractListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onInteract(PlayerInteractEvent event) {
            try {
                if (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                    return;
                }
                // ① 水瓶链优先：玻璃瓶/水瓶/铁桶 右键 耕地/炼药锅（批次 6c）。
                //    返回 true = 已接管（原脚本 xuOnWaterInteract 排在甘露前面）。
                if (TwinShop.onWaterInteract(event)) return;
                // ② 二阶段道具右键：入口自己会按 tick + 道具种类去重，这里不做二次判断
                BossPhaseTwoItems.handleInteract(event);
            } catch (Throwable t) {
                BossLog.warn("二阶段道具右键派发失败: " + t);
            }
        }
    }

    /**
     * Katarina 阶段的三条事件规则（原脚本 12747-12788 / 12969-12972 行）。
     *
     * <ol>
     *   <li><b>护法本体不可打时一律取消伤害</b>（{@code EntityDamageEvent}，LOWEST）——
     *       zqx125 完全无敌（血量只在血条上）；本体另有 {@code setInvulnerable} 双保险。</li>
     *   <li><b>盾存在时限伤 1</b>（{@code EntityDamageByEntityEvent}，LOWEST）——
     *       Katarina 的三面环绕盾是她唯一的防御机制，不压伤害的话 600 血几十刀就没了。</li>
     *   <li><b>护法打玩家固定 1 点</b>（同事件 LOWEST 的第二个方法）——
     *       一个优先级只能有一个方法，所以拆开（原脚本同样拆了两遍）。</li>
     *   <li><b>挥砍弹反</b>（{@code PlayerArmSwingEvent}，NORMAL）——
     *       手持戮仙剑朝火球挥砍把它弹回她身上。</li>
     * </ol>
     */
    private static final class KatarinaListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onGuardianDamaged(org.bukkit.event.entity.EntityDamageEvent event) {
            try {
                Entity victim = event.getEntity();
                BossState st = BossFight.byEntity(victim);
                if (st == null) return;
                BossState.GuardianSlot slot = st.guardians.slotOf(victim);
                // 本体存在但「不可被打」= 这一位是演出道具（zqx125）：任何来源的伤害都取消
                if (slot != null && !slot.damageable) {
                    event.setCancelled(true);
                }
            } catch (Throwable t) {
                BossLog.warn("护法免判定派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerHitGuardian(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                Entity victim = event.getEntity();
                BossState st = BossFight.byEntity(victim);
                if (st == null) return;
                BossState.GuardianSlot slot = st.guardians.slotOf(victim);
                // Katarina：盾牌还在 → 任何攻击都只算 1 点
                if (slot != null && slot.damageable && slot.shieldCount > 0) {
                    event.setDamage(1.0d);
                }
            } catch (Throwable t) {
                BossLog.warn("护法限伤派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onGuardianHitPlayer(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                if (!(event.getEntity() instanceof Player victim)) return;
                BossState st = BossFight.bySummoner(victim);
                if (st == null) return;
                Entity damager = event.getDamager();
                if (damager == null) return;
                for (BossState.GuardianSlot slot : st.guardians.all()) {
                    if (slot.body == null && slot.body2 == null) continue;
                    try {
                        if ((slot.body != null && slot.body.equals(damager))
                                || (slot.body2 != null && slot.body2.equals(damager))) {
                            // 护法打玩家：固定 1 点（Katarina 与将来的双子都算）
                            event.setDamage(1.0d);
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 实体已卸载：当作不匹配
                    }
                }
            } catch (Throwable t) {
                BossLog.warn("护法打玩家限派遣发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onArmSwing(io.papermc.paper.event.player.PlayerArmSwingEvent event) {
            try {
                // 左键挥砍：弹反火球（Katarina 阶段）。
                // ★ Paper 1.21.x 起 PlayerArmSwingEvent 在 io.papermc.paper.event.player 包下
                //   （org.bukkit 里那个已不存在），批次 8 整理事件层时注意统一 import。
                BossKatarina.tryDeflect(event.getPlayer());
            } catch (Throwable t) {
                BossLog.warn("火球弹反派发失败: " + t);
            }
        }
    }

    /**
     * 双子经营小游戏的事件规则（批次 6b；原脚本对应 handler 的双子份额）。
     *
     * <ol>
     *   <li><b>虚仆死亡</b>（{@code EntityDeathEvent}，NORMAL）—— 清掉落与经验，虚币走账本；</li>
     *   <li><b>资源破坏</b>（{@code BlockBreakEvent}，NORMAL）—— 入账 + 不掉落 + 立刻补回；</li>
     *   <li><b>挑战道具拾取</b>（{@code EntityPickupItemEvent}，NORMAL）—— 不进背包直接给效果；</li>
     *   <li><b>杀戮光环</b>（{@code EntityDamageByEntityEvent}，HIGHEST）—— 秒杀虚仆视为玩家击杀；</li>
     *   <li><b>守护之盾</b>（{@code EntityDamageEvent}，HIGHEST）—— 30 秒内免疫一切伤害。</li>
     * </ol>
     * ⚠️ 批次 8 统一整理事件层时并入总表，不要重复注册。
     */
    private static final class TwinMinigameListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
            try {
                // 决斗体（糖然僵尸）死亡优先：三阶段通关 → 四阶段过场（原脚本 xuOnServantDeath 的顺序）
                if (BossPhaseThreeFour.onDuelMobDeath(event)) return;
                TwinMinigame.onServantDeath(event);
            } catch (Throwable t) {
                BossLog.warn("虚仆死亡派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
            try {
                TwinMinigame.onBlockBreak(event.getPlayer(), event.getBlock(), event);
            } catch (Throwable t) {
                BossLog.warn("双子资源破坏派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
            try {
                TwinMinigame.onChallengePickup(event);
            } catch (Throwable t) {
                BossLog.warn("双子挑战道具拾取派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onAuraKill(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                TwinMinigame.auraKill(event);
            } catch (Throwable t) {
                BossLog.warn("杀戮光环派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onServantHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                TwinMinigame.servantHit(event);
            } catch (Throwable t) {
                BossLog.warn("虚仆打玩家派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDuelMobHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                // 决斗体（糖然僵尸）打玩家：固定 1 点（原脚本 12820-12824）
                BossPhaseThreeFour.duelMobHit(event);
            } catch (Throwable t) {
                BossLog.warn("决斗体打玩家派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDuelFireballHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                // 糖然火球：固定 2 点（原脚本 12826-12834）
                BossPhaseThreeFour.duelFireballHit(event);
            } catch (Throwable t) {
                BossLog.warn("决斗火球派发失败: " + t);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onGuardShield(org.bukkit.event.entity.EntityDamageEvent event) {
            try {
                if (event.getEntity() instanceof Player p && TwinMinigame.buffActive(p, "shield")) {
                    // 守护之盾：30 秒内免疫一切伤害（不限来源）
                    event.setCancelled(true);
                }
            } catch (Throwable t) {
                BossLog.warn("守护之盾派发失败: " + t);
            }
        }
    }

    /** 加入事件：补还备份 + 解锁 + 收掉「不在挑战中却带着挑战道具」的残留。 */
    private static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            try {
                Player p = event.getPlayer();
                if (p == null) return;
                // 要么正在挑战（那份备份还在服役），要么把孤儿备份还回去
                if (!BossFight.isActive(p)) {
                    BossInventory.restore(p);
                    BossChallengeState.removeLock(p);
                    LuxianSword.clearNoSwordRide(p);
                    // 上一次挑战中途退出的话，包里可能还留着盾/甘露
                    BossPhaseTwoItems.cleanResidual(p);
                    // 专用世界安全网：战局已不在（中途退出/重载丢失），人却留在挑战空间里
                    if (TangranSpace.isSpace(p.getWorld())) {
                        org.bukkit.Location back = TangranSpace.mainWorldSpawn();
                        if (back != null) {
                            p.teleport(back);
                            p.sendMessage("§7已从挑战空间送回主世界。");
                        }
                    }
                }
                // 账本里买过的增益：只在本局双子阶段生效，重连时重套/收掉（批次 6c/8）
                TwinShop.onJoin(p);
            } catch (Throwable t) {
                BossLog.warn("加入时恢复失败: " + t);
            }
        }

        /**
         * 复活落点兜底：挑战空间里死亡（虚空坠落等）且收场时的传送未能生效的话，
         * 玩家会复活在 tangran_space 的世界出生点——重定向回主世界，别让人被困在
         * 屏障围起来的场地里。
         */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
            try {
                if (TangranSpace.isSpace(event.getRespawnLocation().getWorld())
                        && !BossFight.isActive(event.getPlayer())) {
                    org.bukkit.Location back = TangranSpace.mainWorldSpawn();
                    if (back != null) event.setRespawnLocation(back);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ==================================================================
    // 桥接实现
    // ==================================================================

    /** 护法名单（顺序即界面顺序）。 */
    private static final List<BossBattleBridge.Guardian> GUARDIANS = List.of(
            new BossBattleBridge.Guardian("zqx125", "zqx125", null, null, false),
            new BossBattleBridge.Guardian("Katarina", "Katarina", null, null, false),
            // 护法三 = 双子护法：[天虚]D_RKIN 与 [天实]DrofSunShine 共用一场战斗，菜单图标用恶魂之泪
            new BossBattleBridge.Guardian("D_RKIN", "三 · 双子护法", "GHAST_TEAR",
                    List.of(new BossBattleBridge.Guardian.Member("天虚", "D_RKIN"),
                            new BossBattleBridge.Guardian.Member("天实", "DrofSunShine")),
                    false));

    private static final class Impl implements BossBattleBridge.Provider {

        /**
         * 从头开始挑战（阶段 1）。
         *
         * <p>这是「道法/任务」里那个「开始挑战中章BOSS！」按钮的落点，
         * 也是整个 BOSS战 <b>唯一的外部位入口</b>。</p>
         */
        @Override
        public boolean start(Player player) {
            return BossFightEntry.start(player, 1);
        }

        /**
         * 从指定阶段开始（原脚本 {@code startTangranBossPhase}）。
         *
         * <p>阶段 2 需要「阶段 1 已通关」（与原脚本一致），起局后由
         * {@link BossFightEntry#enterPhaseTwoEntry} 分派；阶段 3 = 跳过双子直接糖然下场、
         * 阶段 4 = 四阶段过场（均走 {@link BossPhaseThreeFour}）。</p>
         */
        @Override
        public boolean startPhase(Player player, int phase) {
            if (phase <= 1) return BossFightEntry.start(player, 1);
            if (phase == 2) {
                if (!BossBattleBridge.readProgress(player.getName()).phase1Cleared()) {
                    try {
                        player.sendMessage("§c尚未通关第一阶段，无法挑战第二阶段。");
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return false;
                }
                return BossFightEntry.start(player, 2);
            }
            if (phase == 3) {
                if (!BossBattleBridge.readProgress(player.getName()).phase2Cleared()) {
                    try {
                        player.sendMessage("§c尚未打完护法阶段，无法直接挑战糖然阶段。");
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return false;
                }
                return BossFightEntry.start(player, 2, BossState.GID_SKIP_TO_TANGRAN3);
            }
            if (phase == 4) {
                if (!BossBattleBridge.readProgress(player.getName()).phase3Cleared()) {
                    try {
                        player.sendMessage("§c尚未通关糖然阶段，无法读取第四阶段。");
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return false;
                }
                return BossFightEntry.start(player, 2, BossState.GID_SKIP_TO_TANGRAN4);
            }
            notReady(player, "阶段 " + phase + " 读档");
            return false;
        }

        /**
         * 从指定护法开始（阶段二读档，原脚本 {@code startTangranBossPhaseFromGuardian}）。
         *
         * <p>只放行<b>已注册</b>的护法（当前 zqx125 / Katarina）—— 双子属于批次 6，
         * 未注册时如实提示，而不是起一局然后卡死。</p>
         */
        @Override
        public boolean startPhaseFromGuardian(Player player, String guardianId) {
            if (player == null) return false;
            if (guardianId != null && GuardianManager.isRegistered(guardianId)) {
                return BossFightEntry.start(player, 2, guardianId);
            }
            notReady(player, "从护法 " + guardianId + " 开始挑战（批次 5/6）");
            return false;
        }

        /**
         * 该玩家是否已在挑战中。
         *
         * <p>这一条<b>现在已经能如实回答</b>了（战局层已就绪），不再是无条件 false。</p>
         */
        @Override
        public boolean isActive(Player player) {
            return BossFight.isActive(player);
        }

        @Override
        public int givePofabow(Player player) {
            // 破法弓的发放属于二阶段装备流程（批次 4）。此时如实返回 0，
            // 而不是「先给一把再说」—— 那会让玩家在没有挑战的情况下白拿道具。
            return 0;
        }

        @Override
        public int giveMidChapterKit(Player player, String reason) {
            // 同上（中章装备全套随二阶段一起做）
            return 0;
        }

        @Override
        public List<BossBattleBridge.Guardian> guardians() {
            return GUARDIANS;
        }

        @Override
        public boolean hasBeatenGuardian(Player player, String guardianId) {
            try {
                return player != null
                        && BossProgress.hasBeatenGuardian(player.getName(), guardianId);
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public ItemStack makeGuardianHead(String guardianId, String name, List<String> lore) {
            // 头颅皮肤层未移植 → 返回 null，调用方退化成普通头颅（与原脚本「拿不到皮肤」一致）
            return null;
        }

        @Override
        public void log(String message) {
            BossLog.warn(message);
        }

        // ------------------------------------------------------------------
        // 装备侧回调（现已可用）
        // ------------------------------------------------------------------

        /**
         * 近战是否可以撤销伤害取消（原脚本 {@code tangranSwordCanUncancelDamage}）。
         *
         * <p><b>这里是本次拆分收益最直接的一处。</b>原脚本的实现是：</p>
         * <pre>
         *   if (st.phaseTwoGuardianDamageable !== true) return false;
         *   var g = st.phaseTwoGuardian;            // ← 被三位护法共用的那个字段
         *   if (g === victim) return true;
         * </pre>
         * <p>因为字段共用，双子阶段「可打」的标志若是没同步，Katarina 就会挨不出伤害
         * （原作者为此打了一个「先查 {@code phaseTwoGuardianId} 判阶段」的补丁）。</p>
         * <p>现在的写法是<b>读当前生效槽位自己的 {@code damageable}</b>：</p>
         * <ul>
         *   <li>zqx125 上场 → {@code damageable=false} → 不放行（完全无敌，血量只在血条上）；</li>
         *   <li>Katarina 上场 → {@code damageable=true} → 放行；</li>
         *   <li>双子上场 → {@code damageable=false} → 不放行（只吃破法弓计数器）。</li>
         * </ul>
         * <p>等价于原脚本补丁后的行为，但不再依赖「字段指向谁」这种推断。</p>
         */
        @Override
        public boolean swordCanUncancelDamage(Player damager, Entity victim) {
            try {
                if (damager == null || victim == null) return false;
                BossState st = BossFight.bySummoner(damager);
                if (st == null) return false;
                // 走护法自己的口径：zqx125 无敌 / Katarina 可打 / 双子只吃计数器
                return GuardianManager.canBeDamaged(st, victim);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /**
         * 双子头顶的展示盔甲架 → 真实本体（原脚本 {@code tangranTwinFromDisplay}）。
         *
         * <p>原脚本线性扫全部战局找 {@code twinDisplayA / B}；这里走实体索引 + 槽位自己的
         * {@code displayHost()}，同时把「是哪一位本体」这件事也一并给出（A→第一位、B→第二位）。</p>
         */
        @Override
        public Entity twinFromDisplay(Entity display) {
            try {
                if (display == null) return null;
                BossState st = BossFight.byEntity(display);
                if (st == null) return null;
                // 双子的展示盔甲架还没注册进实体索引时也要能反查到，所以按槽位扫
                for (BossState.GuardianSlot slot : st.guardians.all()) {
                    Entity host = slot.displayHost(display);
                    if (host != null) return host;
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        /**
         * 破法弓箭矢命中双子护法（原脚本 {@code tangranTwinArrowHit}）：
         * 改扣 BossBar 计数器（双子本体不吃实体伤害）。
         */
        @Override
        public boolean twinArrowHit(Entity target, Player shooter, Entity arrow, double damage) {
            return BossTwin.arrowHitByEntity(target, shooter, arrow, damage);
        }

        /**
         * 双子经营小游戏是否进行中（原脚本 {@code xuStageActive}）。
         *
         * <p>进行中意味着：破坏方块不掉落、也不自动入包（由装备侧据此放行/拦截）。</p>
         */
        @Override
        public boolean xuStageActive(Player player) {
            try {
                if (player == null) return false;
                BossState st = BossFight.bySummoner(player);
                return st != null && st.minigame.running();
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 计数器伤害入口（原脚本 {@code damageTangranBossGuardian}，OP 指令与举盾挡箭都用它）。 */
        public boolean damageGuardian(Player player, double amount) {
            try {
                BossState st = BossFight.bySummoner(player);
                return st != null && GuardianManager.damage(st, player, amount);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 未移植的阶段入口统一提示（不静默，免得玩家以为是「点了没反应」）。 */
        private static void notReady(Player player, String what) {
            try {
                if (player != null && player.isOnline()) {
                    player.sendMessage("§c[糖然科技] BOSS战 的" + what
                            + "流程尚未原生化完成（护法与阶段机制待续），暂不能开始挑战。");
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static boolean same(Entity a, Entity b) {
            if (a == null || b == null) return false;
            try {
                return a.equals(b);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /**
     * 插件卸载时收掉所有战局并清表（{@link NativeScript#onUnload()} 钩子，
     * 由 {@code NativeScriptRegistry#shutdown()} 逐脚本调用）。
     *
     * <p>不收的后果：战局表（含 World/Entity 引用）与残留实体一起活过重载，
     * 「场上还有活着的挑战本体」安全闸被自己的残留实体关死，
     * 启动自清理与孤儿背包返还全部失效。静态 shutdown() 时代全库无调用方，
     * 卸载链实际断裂——现已改挂接口钩子，onDisable 必达。</p>
     */
    @Override
    public void onUnload() {
        try {
            for (BossState st : BossFight.all()) {
                forceStop(st, "插件卸载");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossFight.shutdown();
        BossChallengeState.shutdown();
        TT.log("BOSS战 已卸载");
    }
}
