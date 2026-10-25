package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;

/**
 * 中章BOSS 专用挑战世界（tangran_space）。
 *
 * <h2>为什么要单独一个世界</h2>
 * <p>原设计每次挑战都在玩家身边「选址 → 建场 → 打完拆场」，主世界会留下临时建筑与
 * 场地记录文件，重启还要自清理。现在改为：一个独立的<b>虚空世界</b>（无地形、无生物、
 * 无昼夜），两座场地<b>常驻</b>其中，跨场次复用——不再逐场建造/拆除：</p>
 * <ul>
 *   <li><b>一阶段躲避场</b>：21×21×12（石砖地板 + 屏障壳 + 基岩标记），中心 (0, 80, 0)；</li>
 *   <li><b>二阶段石英场</b>：51×51×20（石英地板 + 屏障壳 + 基岩标记），中心 (80, 80, 0)，
 *       三/四阶段与双子经营共用这座场地。</li>
 * </ul>
 *
 * <p>两座场地由本类在启动时重置成模板，之后每场战斗结束（{@code restoreArena} 路径）
 * 只做「清掉本场改动 → 重建模板」——改动来源：一阶段玩家放的砂岩台阶、zqx 红石雷击、
 * 双子经营的树/土壤/矿石、玩家丢弃物等。清场与重建都是同步小成本（清场对空气快速跳过，
 * 真实改动通常只有几百格），且发生在挑战者已被送出场地之后，不再需要分帧。</p>
 *
 * <h2>单挑战</h2>
 * <p>本世界同时只服务一场战斗（{@link BossFightEntry#start} 入口处拒绝第二名挑战者），
 * 因此场地重置不存在并发冲突，{@code BossFight} 战局表在任何时刻至多一条。</p>
 */
public final class TangranSpace {

    /** 专用世界名。 */
    public static final String NAME = "tangran_space";

    // ---- 一阶段场地（Center.cy = 地板 Y；顶棚 = cy+11，与 BossPhaseOne.buildArena 同口径）----
    private static final int ONE_RADIUS = 10;
    private static final int ONE_HEIGHT = 12;
    /** 一阶段固定场地中心（x=0, 地板 Y=80, z=0）。 */
    public static final BossPhaseOne.Center ONE_CENTER = new BossPhaseOne.Center(0, 80, 0, 0);
    /** 一阶段固定场地区域（与 ONE_CENTER + buildArena 的几何完全一致）。 */
    public static final BossArena.Region REGION_ONE = new BossArena.Region(
            ONE_CENTER.cx() - ONE_RADIUS, ONE_CENTER.cx() + ONE_RADIUS,
            ONE_CENTER.cy(), ONE_CENTER.cy() + ONE_HEIGHT - 1,
            ONE_CENTER.cz() - ONE_RADIUS, ONE_CENTER.cz() + ONE_RADIUS);

    // ---- 二阶段场地 ----
    /** 二阶段场地中心（x=80, 地板 Y=80, z=0）。 */
    public static final int TWO_CX = 80;
    public static final int TWO_CY = 80;
    public static final int TWO_CZ = 0;
    private static final int TWO_HALF = 25;
    private static final int TWO_HEIGHT = 20;
    /** 二阶段固定场地区域（石英地板 + 20 格高屏障壳）。 */
    public static final BossArena.Region REGION_TWO = new BossArena.Region(
            TWO_CX - TWO_HALF, TWO_CX + TWO_HALF,
            TWO_CY, TWO_CY + TWO_HEIGHT - 1,
            TWO_CZ - TWO_HALF, TWO_CZ + TWO_HALF);

    private TangranSpace() {}

    /** 挑战入场点：一阶段场地内南侧、面朝 -Z（糖然升空侧）。 */
    public static Location entryOne() {
        return loc(0.5d, 81.0d, 6.5d, 180.0f);
    }

    /** 二阶段入场点：石英场中心地面（startArenaSequence 会在特效后正式传送）。 */
    public static Location entryTwo() {
        return loc(TWO_CX + 0.5d, TWO_CY + 1.0d, TWO_CZ + 0.5d, 0.0f);
    }

    private static Location loc(double x, double y, double z, float yaw) {
        World w = getOrCreate();
        return new Location(w, x, y, z, yaw, 0.0f);
    }

    /** 这个世界是不是专用挑战世界。 */
    public static boolean isSpace(World world) {
        return world != null && NAME.equals(world.getName());
    }

    /** 主世界（用于把人送出去的兜底落点）。 */
    public static Location mainWorldSpawn() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return (w == null) ? null : w.getSpawnLocation();
    }

    /** 取专用世界；不存在则创建（虚空生成器 + 静默规则）。 */
    public static synchronized World getOrCreate() {
        World w = Bukkit.getWorld(NAME);
        if (w != null) return w;
        WorldCreator c = new WorldCreator(NAME);
        c.environment(World.Environment.NORMAL);
        c.generateStructures(false);
        c.generator(new VoidGenerator());
        w = c.createWorld();
        if (w != null) {
            try {
                // 一个安静的战斗空间：无自然生物、无昼夜推进、无天气、无火蔓延、
                // 无幻翼/巡逻队/流浪商人；世界出生点放在一阶段场地内（兜底落点）
                w.setSpawnFlags(false, false);
                w.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
                w.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false);
                w.setGameRule(org.bukkit.GameRule.DO_INSOMNIA, false);
                w.setGameRule(org.bukkit.GameRule.DO_PATROL_SPAWNING, false);
                w.setGameRule(org.bukkit.GameRule.DO_TRADER_SPAWNING, false);
                w.setSpawnLocation(0, 81, 0);
            } catch (Throwable ignored) {
                // 规则设不上只影响观感，不阻断
            }
        }
        return w;
    }

    /**
     * 启动时把两座场地重置成模板（同步）。上一局若在服务器崩溃时中断，
     * 残留改动（红石块/树/台阶等）在这里一并清掉；此后每场结束由 restoreArena 重置。
     * 由 {@code BossBattleScript#registerListeners} 在启动自清理之后调用。
     */
    public static void resetAll() {
        try {
            World w = getOrCreate();
            if (w == null) return;
            BossArena.clearAll(w, REGION_ONE, null);
            BossPhaseOne.buildArena(w, ONE_CENTER, null);
        } catch (Throwable t) {
            BossLog.warn("tangran_space 一阶段场地重置失败: " + t);
        }
        try {
            World w = getOrCreate();
            if (w == null) return;
            BossArena.clearAll(w, REGION_TWO, null);
            buildTwoShell(w);
        } catch (Throwable t) {
            BossLog.warn("tangran_space 二阶段场地重置失败: " + t);
        }
    }

    /** 重置一阶段场地：清掉改动 → 重建模板（同步；调用时挑战者应已在场地之外）。 */
    public static void resetOne() {
        try {
            World w = getOrCreate();
            BossArena.clearAll(w, REGION_ONE, null);
            BossPhaseOne.buildArena(w, ONE_CENTER, null);
        } catch (Throwable t) {
            BossLog.warn("tangran_space 一阶段场地重置失败: " + t);
        }
    }

    /** 重置二阶段场地：清掉改动 → 重建石英场模板（同步）。 */
    public static void resetTwo() {
        try {
            World w = getOrCreate();
            BossArena.clearAll(w, REGION_TWO, null);
            buildTwoShell(w);
        } catch (Throwable t) {
            BossLog.warn("tangran_space 二阶段场地重置失败: " + t);
        }
    }

    /** 二阶段场地是否完好（中心地板还是石英）。 */
    public static boolean isTwoBuilt() {
        try {
            return getOrCreate().getBlockAt(TWO_CX, TWO_CY, TWO_CZ).getType() == Material.QUARTZ_BLOCK;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 兜底：二阶段场地被外力清掉（管理员清场等）时原地重建模板（同步，只补空气格）。 */
    public static void ensureBuiltTwo() {
        if (isTwoBuilt()) return;
        buildTwoShell(getOrCreate());
    }

    /**
     * 重建二阶段场地模板：石英地板 + 屏障壳（四壁 + 顶棚）+ 顶层正中基岩标记。
     * 只在空气格放置，绝不覆盖非空气方块（与 BossPhaseOne.buildArena 同一纪律）。
     */
    private static void buildTwoShell(World world) {
        try {
            int minY = REGION_TWO.minY();
            int maxY = REGION_TWO.maxY();
            List<int[]> positions = new ArrayList<>();
            for (int y = minY; y <= maxY; y++) {
                boolean floorLayer = (y == minY);
                boolean ceilLayer = (y == maxY);
                for (int x = REGION_TWO.minX(); x <= REGION_TWO.maxX(); x++) {
                    for (int z = REGION_TWO.minZ(); z <= REGION_TWO.maxZ(); z++) {
                        boolean shell = floorLayer || ceilLayer
                                || x == REGION_TWO.minX() || x == REGION_TWO.maxX()
                                || z == REGION_TWO.minZ() || z == REGION_TWO.maxZ();
                        if (shell) positions.add(new int[]{x, y, z, floorLayer ? 1 : 0});
                    }
                }
            }
            for (int[] pos : positions) {
                boolean isMarker = (pos[3] == 0 && pos[1] == maxY
                        && pos[0] == TWO_CX && pos[2] == TWO_CZ);
                Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
                if (!block.getType().isAir()) continue;
                block.setType(isMarker ? Material.BEDROCK
                        : (pos[3] == 1 ? Material.QUARTZ_BLOCK : Material.BARRIER), false);
            }
        } catch (Throwable t) {
            BossLog.warn("tangran_space 二阶段模板重建失败: " + t);
        }
    }

    /** 虚空区块生成器：什么都不生成（无地形/无基岩/无结构）。 */
    public static final class VoidGenerator extends org.bukkit.generator.ChunkGenerator {
        @Override
        public org.bukkit.generator.ChunkGenerator.ChunkData generateChunkData(
                org.bukkit.World world, java.util.Random random, int chunkX, int chunkZ,
                org.bukkit.generator.ChunkGenerator.BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
