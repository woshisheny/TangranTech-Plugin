package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * 一阶段挑战：<b>21×21×12 空中躲避场</b>，60 秒内保住 10 条命。
 *
 * <h2>四种伤害来源（照抄原脚本）</h2>
 * <table border="1">
 *   <caption>机制与参数</caption>
 *   <tr><th>机制</th><th>预警</th><th>伤害判定</th><th>密度</th></tr>
 *   <tr><td>粒子激光</td><td>白色 + 滋滋声（预警 30→10 刻）</td><td>变红后按三种几何判定</td>
 *       <td>间隔 {@code lerp(70,18,进度)/3}（约为原来的 3 倍）</td></tr>
 *   <tr><td>飞天猪猪导弹</td><td>无（沿轴直线飞，不追踪）</td><td>玩家碰撞箱与猪碰撞箱相交</td>
 *       <td>间隔 {@code lerp(90,26,进度)/2}（约为原来的 2 倍）</td></tr>
 *   <tr><td>砂岩台阶</td><td>—</td><td>不造成伤害，是<b>给玩家换高度躲激光</b>的</td>
 *       <td>间隔 {@code lerp(60,22,进度)}</td></tr>
 *   <tr><td>幻魔者尖牙</td><td>—</td><td>{@code biteAt} 那一拍判定，水平 1.15 / 竖直 2.0</td>
 *       <td>间隔 {@code lerp(100,40,进度)}</td></tr>
 * </table>
 *
 * <h2>★ 三条必须保留的判定细节</h2>
 * <ol>
 *   <li><b>激光三种几何要分开判</b>：
 *       <ul>
 *         <li>{@code v}（竖直光柱）—— 判定 X/Z 各 0.8 内<b>且</b> Y 在 {@code [yMin-0.5, yMax+0.5]}；</li>
 *         <li>{@code x}（横向，固定 Z）—— 先判 {@code |ΔY| < 1.6}，再判 {@code |ΔZ| < 0.9}；</li>
 *         <li>{@code z}（纵向，固定 X）—— 同上，换成 {@code |ΔX| < 0.9}。</li>
 *       </ul>
 *       三种的阈值都不一样，合并成一个判定就会出现「激光明明穿过玩家却不掉命」。</li>
 *   <li><b>受击有 20 拍无敌</b>（{@code invulnUntil = elapsed + 20}）。没有它，
 *       一发猪导弹贴脸能一帧扣光 10 条命。</li>
 *   <li><b>二段跳用「允许飞行 + 拦截飞行开关」实现</b>：
 *       挑战期间临时 {@code setAllowFlight(true)}，玩家按空格触发
 *       {@code PlayerToggleFlightEvent} 时<b>取消事件并改成向上弹射</b>
 *       （{@code dir.x*0.7, 0.9, dir.z*0.7}），一局只在落地后重置。
 *       直接 {@code setFlying(true)} 会让玩家真的飞起来 —— 那就不是「二段跳」而是「创造飞行」。</li>
 * </ol>
 *
 * <h2>场地怎么找</h2>
 * <p>在玩家周围 13 个候选偏移里挑一块<b>「21×21 区域最高非空气方块上方」</b>的空间，
 * 要求：① 不超过世界逻辑高度；② <b>不和别人正在打的场地重叠</b>（多玩家并行）；
 * ③ 与玩家当前高度差最小。全部候选都不行才退回玩家正下方。</p>
 */
public final class BossPhaseOne {

    private BossPhaseOne() {}

    /** 场地半径（21×21）。 */
    private static final int RADIUS = 10;

    /** 场地高度（地板 → 顶层屏障）。 */
    private static final int HEIGHT = 12;

    /** 生命上限。 */
    private static final int MAX_LIVES = 10;

    /** 挑战时长（刻）：60 秒。 */
    private static final int DURATION = 1200;

    /** 主循环步长：原脚本每 2 刻推一拍，{@code elapsed} 也按 +2 累加。 */
    private static final long CHALLENGE_PERIOD = 2L;

    /** 每 20 拍才刷一次新机制（进度与冷却都以 20 拍为粒度）。 */
    private static final int SPAWN_PERIOD = 20;

    /** 受击无敌帧数。 */
    private static final int INVULN_TICKS = 20;

    /** 岩台阶存活拍数。 */
    private static final int BLOCK_LIFE = 260;

    /** 猪导弹存活拍数。 */
    private static final int PIG_LIFE = 260;

    /** 候选场地中心偏移（相对玩家，原脚本那 13 个）。 */
    private static final int[][] OFFSETS = {
            {0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8},
            {8, 8}, {8, -8}, {-8, 8}, {-8, -8},
            {15, 0}, {-15, 0}, {0, 15}, {0, -15},
    };

    /** 一次候选场地。 */
    public record Center(int cx, int cy, int cz, int diff) {}

    // ==================================================================
    // 启动
    // ==================================================================

    /**
     * 启动一阶段（幂等：{@code phaseOne.started} 已为 true 时直接返回）。
     *
     * @return 是否真的启动了（场地找不到等原因会返回 false）
     */
    public static boolean start(BossState state, Player player) {
        try {
            if (state == null || player == null || !player.isOnline()) return false;
            if (state.phaseOne.started) return false;

            Center center = findArena(player);
            if (center == null) {
                BossFx.sendLine(player, "§c附近没有足够的空域生成挑战区域。");
                return false;
            }
            state.phaseOne.started = true;
            state.arena.oneStarted = true;

            World world = player.getWorld();
            buildArena(world, center, state);

            // 玩家：21×21 的 +Z 边中点，朝向对面（-Z）；糖然悬在 8 格高的另一侧
            Location playerLoc = new Location(world, center.cx() + 0.5d,
                    center.cy() + 1.0d, center.cz() + 9.5d);
            Location bossLoc = new Location(world, center.cx() + 0.5d,
                    center.cy() + 8.0d, center.cz() - 9.5d);
            double pdx = bossLoc.getX() - playerLoc.getX();
            double pdy = bossLoc.getY() - playerLoc.getY();
            double pdz = bossLoc.getZ() - playerLoc.getZ();
            playerLoc.setYaw((float) (Math.atan2(-pdx, pdz) * 180.0d / Math.PI));
            playerLoc.setPitch((float) (-Math.atan2(pdy, Math.sqrt(pdx * pdx + pdz * pdz))
                    * 180.0d / Math.PI));

            // 这是系统传送，不要触发「离开挑战区域」失败
            state.ignoringTeleport = true;
            try {
                player.teleport(playerLoc);
            } catch (Throwable ignored) {
                // 忽略
            }
            final String key = state.key;
            BukkitTask clear = TT.plugin == null ? null
                    : Bukkit.getScheduler().runTaskLater(TT.plugin,
                            () -> state.ignoringTeleport = false, 20L);
            if (clear == null) state.ignoringTeleport = false;

            try {
                if (state.stand != null && state.stand.isValid()) state.stand.teleport(bossLoc);
            } catch (Throwable ignored) {
                // 忽略
            }
            faceSummoner(state.stand, player);

            player.sendMessage("§b§l阶段一挑战即将开始，请阅读规则。");
            try {
                player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
            startCountdown(state, player);
            return true;
        } catch (Throwable t) {
            BossLog.warn("阶段一挑战开始失败: " + t);
            return false;
        }
    }

    /** 让盔甲架正对玩家（原脚本 {@code faceSummoner}）。 */
    private static void faceSummoner(org.bukkit.entity.Entity stand, Player player) {
        try {
            if (stand == null || player == null) return;
            Location loc = stand.getLocation();
            double dx = player.getLocation().getX() - loc.getX();
            double dy = player.getLocation().getY() - loc.getY();
            double dz = player.getLocation().getZ() - loc.getZ();
            float yaw = (float) (Math.atan2(-dx, dz) * 180.0d / Math.PI);
            float pitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))
                    * 180.0d / Math.PI);
            stand.setRotation(yaw, pitch);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 场地：选址 / 搭建 / 还原
    // ==================================================================

    /** 这块 21×21 区域的最高非空气方块 Y。 */
    private static int maxSolidY(World world, int cx, int cz, int radius) {
        int maxY = -9999;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                int y = world.getHighestBlockYAt(x, z);
                if (y > maxY) maxY = y;
            }
        }
        return maxY;
    }

    /**
     * 新场地和「别人正在打的场地」有没有重叠（阶段一 / 阶段二 / 双子经营共用一套判定）。
     *
     * <p><b>这条必须保留</b>：原脚本注释写明「多玩家并行」。
     * 不判的话 A 的场地会盖在 B 正在打的场地上，B 的地板瞬间消失 = 直接掉虚空。</p>
     */
    public static boolean regionBlocked(BossState self, World world,
                                        int x1, int x2, int y1, int y2, int z1, int z2) {
        try {
            for (BossState st : BossFight.all()) {
                if (st == null || st == self) continue;
                if (overlaps(world, st.arena.twoWorld, st.arena.twoRegion, x1, x2, y1, y2, z1, z2)) {
                    return true;
                }
                if (overlaps(world, st.arena.oneWorld, st.arena.oneRegion, x1, x2, y1, y2, z1, z2)) {
                    return true;
                }
                if (overlaps(world, st.minigame.world, st.minigame.region, x1, x2, y1, y2, z1, z2)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static boolean overlaps(World world, World arenaWorld, BossArena.Region r,
                                    int x1, int x2, int y1, int y2, int z1, int z2) {
        if (r == null) return false;
        if (arenaWorld != null && !arenaWorld.getName().equals(world.getName())) return false;
        return x1 <= r.maxX() && x2 >= r.minX() && y1 <= r.maxY() && y2 >= r.minY()
                && z1 <= r.maxZ() && z2 >= r.minZ();
    }

    /** 找一块 21×21 的空域。 */
    public static Center findArena(Player player) {
        try {
            World world = player.getWorld();
            Location loc = player.getLocation();
            int px = loc.getBlockX();
            int py = loc.getBlockY();
            int pz = loc.getBlockZ();
            int maxBuildY = world.getLogicalHeight() - 7;

            Center best = null;
            for (int[] off : OFFSETS) {
                int cx = px + off[0];
                int cz = pz + off[1];
                int maxY = maxSolidY(world, cx, cz, RADIUS);
                int cy = maxY + 1;
                if (cy > maxBuildY) continue;
                if (regionBlocked(null, world,
                        cx - RADIUS, cx + RADIUS, cy, cy + HEIGHT - 1, cz - RADIUS, cz + RADIUS)) {
                    continue;
                }
                int diff = Math.abs(cy - py);
                if (best == null || diff < best.diff()) {
                    best = new Center(cx, cy, cz, diff);
                }
            }
            if (best == null) {
                // 全部候选都被占：退回玩家正下方
                int maxY2 = maxSolidY(world, px, pz, RADIUS);
                int cy2 = maxY2 + 1;
                if (cy2 <= maxBuildY && !regionBlocked(null, world,
                        px - RADIUS, px + RADIUS, cy2, cy2 + HEIGHT - 1,
                        pz - RADIUS, pz + RADIUS)) {
                    best = new Center(px, cy2, pz, Math.abs(cy2 - py));
                }
            }
            return best;
        } catch (Throwable t) {
            BossLog.warn("寻找阶段一场地失败: " + t);
            return null;
        }
    }

    /**
     * 搭场地：石砖地板 + 四周 10 格屏障 + 顶层屏障封顶（正中间那格是<b>基岩标记</b>）。
     *
     * <p>三条纪律（照抄原脚本）：</p>
     * <ol>
     *   <li><b>只在原方块是 AIR 时放置</b>，绝不替换非空气方块 —— 否则会把地形里的
     *       树/建筑/别人的机器挖掉；</li>
     *   <li><b>展示框/画 的占位格与依附格一律跳过</b>（见 {@link BossArena#collectDecorativeCells}）
     *       —— 方块一压就把框挤掉；</li>
     *   <li><b>标记格上原本有装饰物时仍然放基岩</b>并写一条日志 —— 那个标记是
     *       「清理挑战区域」反推整块场地的唯一依据，不能没有。</li>
     * </ol>
     */
    public static void buildArena(World world, Center center, BossState state) {
        try {
            int cx = center.cx();
            int cy = center.cy();
            int cz = center.cz();
            BossArena.Region region = new BossArena.Region(
                    cx - RADIUS, cx + RADIUS, cy, cy + HEIGHT - 1, cz - RADIUS, cz + RADIUS);

            java.util.Set<String> protectedCells = BossArena.collectDecorativeCells(world, region);

            // 地板
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    if (protectedCells.contains(BossArena.cellKey(x, cy, z))) continue;
                    Block floor = world.getBlockAt(x, cy, z);
                    if (floor.getType() == Material.AIR) floor.setType(Material.STONE_BRICKS, false);
                }
            }
            // 四周屏障：高 10 格
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    boolean border = x == region.minX() || x == region.maxX()
                            || z == region.minZ() || z == region.maxZ();
                    if (!border) continue;
                    for (int y = cy + 1; y <= cy + 10; y++) {
                        if (protectedCells.contains(BossArena.cellKey(x, y, z))) continue;
                        Block wall = world.getBlockAt(x, y, z);
                        if (wall.getType() == Material.AIR) wall.setType(Material.BARRIER, false);
                    }
                }
            }
            // 顶层封顶（防二段跳逃逸），正中间那格是基岩标记
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    boolean isMarker = (x == cx && z == cz);
                    if (!isMarker && protectedCells.contains(BossArena.cellKey(x, cy + 11, z))) continue;
                    if (isMarker && protectedCells.contains(BossArena.cellKey(x, cy + 11, z))) {
                        BossLog.warn("阶段一场地：标记格上原本有展示框/画，已被基岩标记替换（"
                                + x + "," + (cy + 11) + "," + z + "）");
                    }
                    Block ceiling = world.getBlockAt(x, cy + 11, z);
                    if (ceiling.getType() == Material.AIR) {
                        ceiling.setType(isMarker ? Material.BEDROCK : Material.BARRIER, false);
                    }
                }
            }

            state.arena.oneWorld = world;
            state.arena.oneRegion = region;
            BossArena.saveRecord(world, region, cx, cy + 11, cz, state.key);
        } catch (Throwable t) {
            BossLog.warn("搭建阶段一场地失败: " + t);
        }
    }

    /** 还原场地：把整块区域清成空气（只清自己那一块）。 */
    public static void restoreArena(BossState state) {
        try {
            if (state == null || state.arena.oneWorld == null || state.arena.oneRegion == null) return;
            World world = state.arena.oneWorld;
            BossArena.Region region = state.arena.oneRegion;
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        } catch (Throwable t) {
            BossLog.warn("还原阶段一挑战区域失败: " + t);
        }
        state.arena.oneWorld = null;
        state.arena.oneRegion = null;
    }

    // ==================================================================
    // 倒计时
    // ==================================================================

    private static void startCountdown(BossState state, Player player) {
        try {
            state.phaseOne.countdownValue = 10;
            state.phaseOne.active = false;

            // 先发规则，给玩家 10 秒阅读
            player.sendMessage("§e§l【阶段一挑战机制】");
            player.sendMessage("§c· 粒子激光：横向、纵向都会出现，先有滋滋预警声，"
                    + "变红后造成伤害，利用砂岩台阶改变高度躲避。");
            player.sendMessage("§d· 飞天猪猪导弹：会从横向、纵向直线飞过，不要碰到猪的碰撞箱。");
            player.sendMessage("§6· 尖牙袭击：幻魔者尖牙会从地面钻出，注意躲开。");
            player.sendMessage("§b· 二段跳：空中再按一次跳跃，可以二段跳。");
            player.sendMessage("§f· 60 秒内保住 10 条命即可通过一阶段。");
            player.sendMessage("§7倒计时结束后挑战正式开始。");

            org.bukkit.plugin.Plugin plugin = TT.plugin;
            if (plugin == null) return;
            state.phaseOne.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.phaseOne.countdownTask);
                        state.phaseOne.countdownTask = null;
                        return;
                    }
                    if (state.phaseOne.countdownValue > 0) {
                        player.sendTitle("§e§l" + state.phaseOne.countdownValue,
                                "§7阶段一挑战即将开始", 0, 20, 5);
                        try {
                            player.playSound(player.getLocation(), "block.note_block.pling",
                                    0.8f, 1.0f + (10 - state.phaseOne.countdownValue) * 0.05f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        state.phaseOne.countdownValue--;
                        return;
                    }
                    BossState.cancelTask(state.phaseOne.countdownTask);
                    state.phaseOne.countdownTask = null;

                    // BGM：播在场地中心（原脚本取 region.minX+10.5, minY+2, minZ+10.5）
                    BossArena.Region region = state.arena.oneRegion;
                    if (region != null) {
                        Location musicCenter = new Location(state.arena.oneWorld,
                                region.minX() + 10.5d, region.minY() + 2.0d,
                                region.minZ() + 10.5d);
                        BossMusic.startPhaseOne(state, musicCenter);
                    }
                    startChallenge(state, player);
                    try {
                        player.sendTitle("§a§l开始！", "§7坚持 60 秒", 0, 20, 10);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    try {
                        player.playSound(player.getLocation(),
                                "entity.ender_dragon.growl", 1.0f, 0.7f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                } catch (Throwable t) {
                    BossLog.warn("阶段一倒计时异常: " + t);
                    BossState.cancelTask(state.phaseOne.countdownTask);
                    state.phaseOne.countdownTask = null;
                }
            }, 0L, 20L);
        } catch (Throwable t) {
            BossLog.warn("阶段一倒计时启动失败: " + t);
        }
    }

    // ==================================================================
    // 挑战主循环
    // ==================================================================

    private static void startChallenge(BossState state, Player player) {
        try {
            state.phaseOne.active = true;
            state.phaseOne.lives = MAX_LIVES;
            state.phaseOne.maxLives = MAX_LIVES;
            state.phaseOne.elapsed = 0L;
            state.phaseOne.invulnUntil = 0L;
            state.phaseOne.duration = DURATION;
            state.phaseOne.lasers.clear();
            state.phaseOne.pigs.clear();
            state.phaseOne.blocks.clear();
            state.phaseOne.spikes.clear();
            state.phaseOne.doubleJumpUsed = false;
            state.phaseOne.laserTypeIndex = 0;
            state.phaseOne.pigAxisIndex = 0;
            state.phaseOne.laserCooldown = 20;
            state.phaseOne.missileCooldown = 40;
            state.phaseOne.blockCooldown = 20;
            state.phaseOne.spikeCooldown = 40;

            // 临时放开飞行权限（二段跳的载体），收场时按原样还回去
            try {
                state.phaseOne.originalAllowFlight = player.getAllowFlight();
                state.phaseOne.originalFlying = player.isFlying();
                player.setAllowFlight(true);
                player.setFlying(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            updateLives(state, player);
            try {
                if (state.bar != null) state.bar.setProgress(1.0d);
            } catch (Throwable ignored) {
                // 忽略
            }

            org.bukkit.plugin.Plugin plugin = TT.plugin;
            if (plugin == null) return;
            state.phaseOne.challengeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state || !state.phaseOne.active) {
                        BossState.cancelTask(state.phaseOne.challengeTask);
                        state.phaseOne.challengeTask = null;
                        return;
                    }
                    state.phaseOne.elapsed += 2L;

                    // 落地就重置二段跳
                    if (player.isOnline() && player.isOnGround()) {
                        state.phaseOne.doubleJumpUsed = false;
                        try {
                            if (!player.getAllowFlight()) player.setAllowFlight(true);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }

                    updateLasers(state, player);
                    updatePigs(state, player);
                    updateBlocks(state);
                    updateSpikes(state, player);

                    if (state.phaseOne.elapsed % SPAWN_PERIOD == 0) {
                        long remaining = Math.max(0L, state.phaseOne.duration - state.phaseOne.elapsed);
                        try {
                            if (state.bar != null) {
                                state.bar.setProgress(remaining / (double) state.phaseOne.duration);
                            }
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        updateLives(state, player);
                        double progress = Math.min(1.0d,
                                state.phaseOne.elapsed / (double) state.phaseOne.duration);
                        updateSpawns(state, player, progress, SPAWN_PERIOD);
                    }

                    if (state.phaseOne.elapsed >= state.phaseOne.duration) {
                        BossState.cancelTask(state.phaseOne.challengeTask);
                        state.phaseOne.challengeTask = null;
                        if (state.phaseOne.lives > 0) {
                            handleSuccess(state, player);
                        } else {
                            fail(state, player, "挑战时间结束，但你的生命已耗尽。");
                        }
                    }
                } catch (Throwable t) {
                    BossLog.warn("阶段一挑战循环异常: " + t);
                }
            }, 0L, CHALLENGE_PERIOD);
        } catch (Throwable t) {
            BossLog.warn("阶段一挑战初始化失败: " + t);
        }
    }

    // ==================================================================
    // 生命 / 伤害
    // ==================================================================

    private static void updateLives(BossState state, Player player) {
        try {
            player.sendActionBar("§c❤ 生命: " + state.phaseOne.lives + "/" + state.phaseOne.maxLives);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 扣一条命（带无敌帧；扣光就判负）。 */
    public static void damage(BossState state, Player player, String source) {
        try {
            if (state == null || player == null) return;
            if (!state.phaseOne.active) return;
            if (state.phaseOne.elapsed < state.phaseOne.invulnUntil) return;
            if (state.phaseOne.lives <= 0) return;
            state.phaseOne.lives--;
            state.phaseOne.invulnUntil = state.phaseOne.elapsed + INVULN_TICKS;
            updateLives(state, player);
            try {
                player.playSound(player.getLocation(), "entity.player.hurt", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.getWorld().spawnParticle(Particle.CRIT,
                        player.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (state.phaseOne.lives <= 0) {
                fail(state, player, "你的生命已耗尽。");
            }
        } catch (Throwable t) {
            BossLog.warn("阶段一扣命失败（" + source + "）: " + t);
        }
    }

    // ==================================================================
    // 机制一：粒子激光
    // ==================================================================

    private static void drawLaser(BossState state, BossState.Laser laser, Color pointColor) {
        try {
            World world = state.arena.oneWorld;
            BossArena.Region region = state.arena.oneRegion;
            if (world == null || region == null) return;
            Particle.DustOptions dust = new Particle.DustOptions(pointColor, 0.9f);
            if ("v".equals(laser.kind)) {
                for (int y = laser.yMin; y <= laser.yMax; y++) {
                    world.spawnParticle(Particle.DUST,
                            laser.x + 0.5d, y + 0.5d, laser.z + 0.5d,
                            1, 0, 0, 0, 0, dust);
                }
            } else if ("x".equals(laser.kind)) {
                for (int x = region.minX(); x <= region.maxX(); x++) {
                    world.spawnParticle(Particle.DUST,
                            x + 0.5d, laser.y + 0.5d, laser.value + 0.5d,
                            1, 0, 0, 0, 0, dust);
                }
            } else {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    world.spawnParticle(Particle.DUST,
                            laser.value + 0.5d, laser.y + 0.5d, z + 0.5d,
                            1, 0, 0, 0, 0, dust);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void spawnLaser(BossState state, double progress) {
        try {
            BossArena.Region region = state.arena.oneRegion;
            if (region == null) return;
            int kindIndex = state.phaseOne.laserTypeIndex % 3;
            state.phaseOne.laserTypeIndex = kindIndex + 1;
            String kind = kindIndex == 0 ? "x" : (kindIndex == 1 ? "z" : "v");
            long now = state.phaseOne.elapsed;
            int warnTicks = (int) Math.round(lerp(30, 10, progress));
            int activeTicks = (int) Math.round(lerp(16, 26, progress));

            BossState.Laser laser = new BossState.Laser();
            laser.kind = kind;
            laser.warnUntil = now + warnTicks;
            laser.activeUntil = now + warnTicks + activeTicks;
            laser.hit = false;
            laser.activated = false;
            laser.lastSizzle = 0;

            if ("v".equals(kind)) {
                laser.x = region.minX() + 2
                        + (int) Math.floor(Math.random() * (region.maxX() - region.minX() - 4));
                laser.z = region.minZ() + 2
                        + (int) Math.floor(Math.random() * (region.maxZ() - region.minZ() - 4));
                laser.yMin = region.minY() + 1;
                laser.yMax = region.minY() + 10;
            } else {
                laser.y = region.minY() + 1 + (int) Math.floor(Math.random() * 4);
                if ("x".equals(kind)) {
                    laser.value = region.minZ() + 2
                            + (int) Math.floor(Math.random() * (region.maxZ() - region.minZ() - 4));
                } else {
                    laser.value = region.minX() + 2
                            + (int) Math.floor(Math.random() * (region.maxX() - region.minX() - 4));
                }
            }
            state.phaseOne.lasers.add(laser);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void updateLasers(BossState state, Player player) {
        try {
            long now = state.phaseOne.elapsed;
            List<BossState.Laser> list = state.phaseOne.lasers;
            for (int i = list.size() - 1; i >= 0; i--) {
                BossState.Laser laser = list.get(i);
                if (now >= laser.activeUntil) {
                    list.remove(i);
                    continue;
                }
                if (now >= laser.warnUntil) {
                    if (!laser.activated) {
                        laser.activated = true;
                        try {
                            player.playSound(player.getLocation(),
                                    "entity.firework_rocket.launch", 1.0f, 1.2f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    drawLaser(state, laser, Color.RED);
                    if (!laser.hit && player.isOnline() && !player.isDead()) {
                        Location pl = player.getLocation();
                        boolean hit = false;
                        if ("v".equals(laser.kind)) {
                            hit = Math.abs(pl.getX() - (laser.x + 0.5d)) < 0.8d
                                    && Math.abs(pl.getZ() - (laser.z + 0.5d)) < 0.8d
                                    && pl.getY() >= laser.yMin - 0.5d
                                    && pl.getY() <= laser.yMax + 0.5d;
                        } else if (Math.abs(pl.getY() - laser.y) < 1.6d) {
                            if ("x".equals(laser.kind)) {
                                hit = Math.abs(pl.getZ() - laser.value) < 0.9d;
                            } else {
                                hit = Math.abs(pl.getX() - laser.value) < 0.9d;
                            }
                        }
                        if (hit) {
                            laser.hit = true;
                            damage(state, player, "laser");
                        }
                    }
                } else {
                    if (laser.lastSizzle == 0 || now - laser.lastSizzle >= 10) {
                        laser.lastSizzle = now;
                        try {
                            player.playSound(player.getLocation(),
                                    "block.fire.extinguish", 0.35f, 1.6f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    drawLaser(state, laser, Color.WHITE);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 机制二：飞天猪猪导弹
    // ==================================================================

    private static void spawnPig(BossState state, Player player, double progress) {
        try {
            BossArena.Region region = state.arena.oneRegion;
            World world = state.arena.oneWorld;
            if (region == null || world == null) return;
            int axisIndex = state.phaseOne.pigAxisIndex % 3;
            state.phaseOne.pigAxisIndex = axisIndex + 1;
            String axis = axisIndex == 0 ? "x" : (axisIndex == 1 ? "z" : "y");
            int dir = Math.random() < 0.5d ? 1 : -1;

            double x;
            double z;
            double y;
            if ("y".equals(axis)) {
                x = region.minX() + 1 + Math.random() * (region.maxX() - region.minX() - 2);
                z = region.minZ() + 1 + Math.random() * (region.maxZ() - region.minZ() - 2);
                y = dir > 0 ? region.minY() + 1 : region.minY() + 10;
            } else if ("x".equals(axis)) {
                z = region.minZ() + 1 + Math.random() * (region.maxZ() - region.minZ() - 2);
                x = dir > 0 ? region.minX() + 0.5d : region.maxX() - 0.5d;
                y = region.minY() + 1 + (int) Math.floor(Math.random() * 4);
            } else {
                x = region.minX() + 1 + Math.random() * (region.maxX() - region.minX() - 2);
                z = dir > 0 ? region.minZ() + 0.5d : region.maxZ() - 0.5d;
                y = region.minY() + 1 + (int) Math.floor(Math.random() * 4);
            }

            Location loc = new Location(world, x, y, z);
            Pig pig = world.spawn(loc, Pig.class);
            pig.setGravity(false);
            pig.setAware(false);
            pig.setSilent(true);
            pig.setInvulnerable(true);
            pig.setPersistent(false);
            pig.setCustomName("§d飞天猪猪");
            pig.setCustomNameVisible(false);

            BossState.PigMissile m = new BossState.PigMissile();
            m.entity = pig;
            m.axis = axis;
            m.dir = dir;
            m.speed = lerp(0.22d, 0.55d, progress);
            m.expire = (int) state.phaseOne.elapsed + PIG_LIFE;
            state.phaseOne.pigs.add(m);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void updatePigs(BossState state, Player player) {
        try {
            long now = state.phaseOne.elapsed;
            BossArena.Region region = state.arena.oneRegion;
            List<BossState.PigMissile> list = state.phaseOne.pigs;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (!state.phaseOne.active) return;
                BossState.PigMissile m = list.get(i);
                Pig pig = (m.entity instanceof Pig p) ? p : null;
                if (pig == null || !pig.isValid() || now >= m.expire) {
                    BossState.removeEntity(pig);
                    list.remove(i);
                    continue;
                }

                // 直线横/纵/竖移动，不追踪玩家
                Location loc = pig.getLocation();
                if ("x".equals(m.axis)) loc.setX(loc.getX() + m.dir * m.speed);
                else if ("z".equals(m.axis)) loc.setZ(loc.getZ() + m.dir * m.speed);
                else loc.setY(loc.getY() + m.dir * m.speed);
                try {
                    pig.teleport(loc);
                } catch (Throwable ignored) {
                    // 忽略
                }

                boolean outOfBounds;
                if ("x".equals(m.axis)) {
                    outOfBounds = loc.getX() < region.minX() - 1.5d
                            || loc.getX() > region.maxX() + 1.5d;
                } else if ("z".equals(m.axis)) {
                    outOfBounds = loc.getZ() < region.minZ() - 1.5d
                            || loc.getZ() > region.maxZ() + 1.5d;
                } else {
                    outOfBounds = loc.getY() < region.minY() - 0.5d
                            || loc.getY() > region.minY() + 11.5d;
                }
                if (outOfBounds) {
                    BossState.removeEntity(pig);
                    list.remove(i);
                    continue;
                }

                // 玩家碰到猪的碰撞箱扣命
                boolean collided;
                try {
                    BoundingBox pb = player.getBoundingBox();
                    BoundingBox qb = pig.getBoundingBox();
                    collided = pb.overlaps(qb);
                } catch (Throwable ignored) {
                    Location pl = player.getLocation();
                    Location ql = pig.getLocation();
                    double dx = pl.getX() - ql.getX();
                    double dy = pl.getY() - ql.getY();
                    double dz = pl.getZ() - ql.getZ();
                    collided = (dx * dx + dy * dy + dz * dz) < 1.0d;
                }
                if (collided) {
                    damage(state, player, "pig");
                    BossState.removeEntity(pig);
                    list.remove(i);
                    continue;
                }

                try {
                    state.arena.oneWorld.spawnParticle(Particle.FLAME,
                            pig.getLocation().add(0, 0.8d, 0), 2, 0.1, 0.1, 0.1, 0.01);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 机制三：砂岩台阶（不造成伤害，是给玩家换高度的）
    // ==================================================================

    private static void spawnBlock(BossState state) {
        try {
            BossArena.Region region = state.arena.oneRegion;
            World world = state.arena.oneWorld;
            if (region == null || world == null) return;
            int x = region.minX() + 2
                    + (int) Math.floor(Math.random() * (region.maxX() - region.minX() - 4));
            int z = region.minZ() + 2
                    + (int) Math.floor(Math.random() * (region.maxZ() - region.minZ() - 4));
            int y = region.minY() + 1 + (int) Math.floor(Math.random() * 4);
            // 2×2 一小片
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int bx = x + dx;
                    int bz = z + dz;
                    Block block = world.getBlockAt(bx, y, bz);
                    if (block.getType() == Material.AIR) {
                        block.setType(Material.SANDSTONE_SLAB, false);
                        BossState.BlockSpot spot = new BossState.BlockSpot();
                        spot.x = bx;
                        spot.y = y;
                        spot.z = bz;
                        spot.expire = (int) state.phaseOne.elapsed + BLOCK_LIFE;
                        state.phaseOne.blocks.add(spot);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void updateBlocks(BossState state) {
        try {
            long now = state.phaseOne.elapsed;
            World world = state.arena.oneWorld;
            List<BossState.BlockSpot> list = state.phaseOne.blocks;
            for (int i = list.size() - 1; i >= 0; i--) {
                BossState.BlockSpot b = list.get(i);
                if (now >= b.expire) {
                    try {
                        Block block = world.getBlockAt(b.x, b.y, b.z);
                        if (block.getType() == Material.SANDSTONE_SLAB) {
                            block.setType(Material.AIR, false);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    list.remove(i);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 机制四：幻魔者尖牙
    // ==================================================================

    private static void spawnSpikes(BossState state, Player player) {
        try {
            BossArena.Region region = state.arena.oneRegion;
            World world = state.arena.oneWorld;
            if (region == null || world == null || player == null) return;

            Location pl = player.getLocation();
            double dirX = Math.random() * 2 - 1;
            double dirZ = Math.random() * 2 - 1;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len < 0.1d) {
                dirX = 1;
                dirZ = 0;
                len = 1;
            }
            dirX /= len;
            dirZ /= len;

            int count = 4 + (int) Math.floor(Math.random() * 3);   // 4~6 颗
            for (int i = 0; i < count; i++) {
                double offset = (i - (count - 1) / 2.0d) * 1.25d;
                double fx = pl.getX() + dirX * offset + (Math.random() - 0.5) * 0.3;
                double fz = pl.getZ() + dirZ * offset + (Math.random() - 0.5) * 0.3;
                fx = Math.max(region.minX() + 1.5d, Math.min(region.maxX() - 0.5d, fx));
                fz = Math.max(region.minZ() + 1.5d, Math.min(region.maxZ() - 0.5d, fz));
                double fy = region.minY() + 1;

                Location loc = new Location(world, fx, fy, fz);
                EvokerFangs fang = world.spawn(loc, EvokerFangs.class);
                int delay = 10 + i * 3;
                try {
                    fang.setAttackDelay(delay);
                } catch (Throwable ignored) {
                    // 忽略
                }

                BossState.Spike spike = new BossState.Spike();
                spike.entity = fang;
                spike.x = fx;
                spike.y = fy;
                spike.z = fz;
                spike.biteAt = (int) state.phaseOne.elapsed + delay;
                spike.expire = spike.biteAt + 10;
                spike.hit = false;
                state.phaseOne.spikes.add(spike);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void updateSpikes(BossState state, Player player) {
        try {
            long now = state.phaseOne.elapsed;
            List<BossState.Spike> list = state.phaseOne.spikes;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (!state.phaseOne.active) return;
                BossState.Spike spike = list.get(i);

                if (!spike.hit && now >= spike.biteAt
                        && player.isOnline() && !player.isDead()) {
                    Location pl = player.getLocation();
                    if (pl.getY() < spike.y + 2.0d
                            && Math.abs(pl.getX() - spike.x) < 1.15d
                            && Math.abs(pl.getZ() - spike.z) < 1.15d) {
                        spike.hit = true;
                        damage(state, player, "fang");
                    }
                }

                if (now >= spike.expire) {
                    BossState.removeEntity(spike.entity);
                    list.remove(i);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 产出调度
    // ==================================================================

    private static void updateSpawns(BossState state, Player player,
                                     double progress, int deltaTicks) {
        try {
            state.phaseOne.laserCooldown -= deltaTicks;
            state.phaseOne.missileCooldown -= deltaTicks;
            state.phaseOne.blockCooldown -= deltaTicks;
            state.phaseOne.spikeCooldown -= deltaTicks;

            // 激光密度约为原来的 3 倍
            int laserInterval = Math.max(5, (int) Math.round(lerp(70, 18, progress) / 3.0d))
                    + (int) Math.floor(Math.random() * 3);
            int laserSpawned = 0;
            while (state.phaseOne.laserCooldown <= 0 && laserSpawned < 6) {
                spawnLaser(state, progress);
                state.phaseOne.laserCooldown += laserInterval;
                laserSpawned++;
            }

            // 猪猪导弹产出概率约为原来的 2 倍
            int missileInterval = Math.max(8, (int) Math.round(lerp(90, 26, progress) / 2.0d))
                    + (int) Math.floor(Math.random() * 6);
            int missileSpawned = 0;
            while (state.phaseOne.missileCooldown <= 0 && missileSpawned < 6) {
                spawnPig(state, player, progress);
                state.phaseOne.missileCooldown += missileInterval;
                missileSpawned++;
            }

            if (state.phaseOne.blockCooldown <= 0) {
                spawnBlock(state);
                state.phaseOne.blockCooldown =
                        (int) Math.round(lerp(60, 22, progress))
                                + (int) Math.floor(Math.random() * 10);
            }

            if (state.phaseOne.spikeCooldown <= 0) {
                spawnSpikes(state, player);
                state.phaseOne.spikeCooldown =
                        (int) Math.round(lerp(100, 40, progress))
                                + (int) Math.floor(Math.random() * 12);
            }
        } catch (Throwable t) {
            BossLog.warn("阶段一产出调度异常: " + t);
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ==================================================================
    // 收场 / 成功 / 失败
    // ==================================================================

    /** 停掉一阶段：任务、实体、方块、飞行权限（原脚本 {@code stopPhaseOneChallenge}）。 */
    public static void stop(BossState state) {
        try {
            if (state == null) return;
            state.phaseOne.active = false;
            BossState.cancelTask(state.phaseOne.countdownTask);
            state.phaseOne.countdownTask = null;
            BossState.cancelTask(state.phaseOne.challengeTask);
            state.phaseOne.challengeTask = null;

            for (BossState.PigMissile m : state.phaseOne.pigs) {
                BossState.removeEntity(m.entity);
            }
            state.phaseOne.pigs.clear();

            World world = state.arena.oneWorld;
            if (world != null) {
                for (BossState.BlockSpot b : state.phaseOne.blocks) {
                    try {
                        Block block = world.getBlockAt(b.x, b.y, b.z);
                        if (block.getType() == Material.SANDSTONE_SLAB) {
                            block.setType(Material.AIR, false);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
            state.phaseOne.blocks.clear();
            state.phaseOne.lasers.clear();

            for (BossState.Spike s : state.phaseOne.spikes) {
                BossState.removeEntity(s.entity);
            }
            state.phaseOne.spikes.clear();

            Player player = (state.playerName == null)
                    ? null : Bukkit.getPlayerExact(state.playerName);
            if (player != null) {
                boolean allow = state.phaseOne.originalAllowFlight;
                player.setAllowFlight(allow);
                player.setFlying(allow && state.phaseOne.originalFlying);
                try {
                    player.sendActionBar(" ");
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable t) {
            BossLog.warn("停止阶段一失败: " + t);
        }
    }

    /** 挑战成功：写进度、停挑战、停 BGM、还原场地，然后等玩家缓降落地再进二阶段。 */
    private static void handleSuccess(BossState state, Player player) {
        try {
            BossProgress.markPhaseCleared(state.playerName, 1);
        } catch (Throwable ignored) {
            // 忽略
        }
        stop(state);
        BossMusic.stopPhaseOne(state);
        try {
            BossState.cancelTask(state.tangran.watchTask);
            state.tangran.watchTask = null;
        } catch (Throwable ignored) {
            // 忽略
        }
        restoreArena(state);
        state.phaseOne.active = false;
        try {
            if (state.bar != null) state.bar.setVisible(false);
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            player.sendMessage("§a§l阶段一挑战成功！");
            player.playSound(player.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 忽略
        }
        // 糖然留在原地，等玩家缓降落地后再进入二阶段
        BossPhaseTwo.beginFromPhaseOne(state, player);
    }

    /** 挑战失败。 */
    public static void fail(BossState state, Player player, String reason) {
        try {
            stop(state);
            BossMusic.stopPhaseOne(state);
            restoreArena(state);
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            player.sendMessage("§c挑战失败：" + reason);
            player.sendMessage("§e你可以再次点击任务的“开始挑战中章BOSS！”重新挑战。");
        } catch (Throwable ignored) {
            // 忽略
        }
        BossBattleScript.forceStop(state, "阶段一失败：" + reason);
    }

    // ==================================================================
    // 二段跳
    // ==================================================================

    /**
     * 二段跳（原脚本的 {@code PlayerToggleFlightEvent} 处理器）。
     *
     * <p>「挑战期间允许飞行」只是为了拿到那个飞行开关事件，<b>真正要做的是取消它并改成弹射</b> ——
     * 不取消的话玩家会真的飞起来，那就不是二段跳而是创造飞行。</p>
     *
     * @return 是否处理了（处理了调用方就应当取消事件）
     */
    public static boolean onToggleFlight(BossState state, Player player) {
        try {
            if (state == null || player == null) return false;
            if (!state.phaseOne.active) return false;
            try {
                player.setFlying(false);
                player.setAllowFlight(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (state.phaseOne.doubleJumpUsed) return true;
            state.phaseOne.doubleJumpUsed = true;
            try {
                Vector dir = player.getLocation().getDirection();
                player.setVelocity(new Vector(dir.getX() * 0.7d, 0.9d, dir.getZ() * 0.7d));
                player.playSound(player.getLocation(), "entity.ender_dragon.flap", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable t) {
            BossLog.warn("二段跳处理失败: " + t);
            return false;
        }
    }
}
