package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Json;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * 「道法/BOSS战」的场地层：区域几何、装饰保护、清场、落盘记录。
 *
 * <p>对应原脚本的 {@code ARENA_SIZES}（区域常量已改由 {@link TangranSpace} 固定给出）/
 * {@code collectDecorativeCells} / {@code clearArenaRegionAll} / {@code clearArenaRegion} /
 * {@code getArenaRecordDir} / {@code saveArenaRecord} / {@code loadArenaRecord}。</p>
 *
 * <p><b>「按标记反推场地 + 右键基岩清场」这一整套已删除</b>（2026-10-26）：挑战场改为
 * 常驻的独立世界 {@code tangran_space} 后，场地不再「在玩家身边选址建造」，也就不需要
 * 反推；对应道具 {@code Tr_tzqyqcq}（挑战区域清除器）一并移除。原脚本的
 * {@code detectArenaByMarker} / {@code deleteArenaRecordsForMarker} /
 * {@code tangranPurgeArenaByMarker} 在原生版里已无对应实现。</p>
 *
 * <h2>三条「绝不动手」的纪律（原脚本注释里的原话）</h2>
 * <ol>
 *   <li><b>只清目标区域</b>，区域外的方块 / 实体一律不碰。</li>
 *   <li><b>展示框 / 发光展示框 / 画 及其依附方块保留</b>（见 {@link #collectDecorativeCells}）。</li>
 *   <li><b>血条只摘「这块场地那个玩家」的</b>，绝不 clearAll（多玩家并行安全）。</li>
 * </ol>
 *
 * <h2>为什么要落盘记录</h2>
 * <p>服务器<b>直接重启</b>（不是正常关服）时，内存里的战局表没了，
 * 但场地方块与实体还在。记录文件让下次启动能自清理掉这些残留。
 * <b>每个玩家一份</b>（{@code _arena_<玩家>.json}）—— 原脚本曾经用一个全局文件，
 * 结果「A 的清场把 B 正在打的场地抹掉」，这条是踩过的坑。</p>
 */
public final class BossArena {

    private BossArena() {}

    // ==================================================================
    // 区域
    // ==================================================================

    /** 场地格坐标范围（闭区间）。 */
    public record Region(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** 中心 X（取整）。 */
        public int centerX() {
            return Math.floorDiv(minX + maxX, 2);
        }

        /** 中心 Z（取整）。 */
        public int centerZ() {
            return Math.floorDiv(minZ + maxZ, 2);
        }

        /** 体积（格数）；清场时会遍历这么多格，仅用于日志与日志量级判断。 */
        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    // ==================================================================
    // 装饰保护
    // ==================================================================

    /** 某个坐标的格键（仅在内存里传递，不落盘）。 */
    public static String cellKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** 是不是「装饰实体」（展示框 / 画）——它们一律保留。 */
    public static boolean isDecorative(Entity entity) {
        try {
            return (entity instanceof ItemFrame) || (entity instanceof Painting);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 「场上还有活着的挑战本体吗」
    // ==================================================================

    /** 虚仆的 metadata 键（原脚本 {@code XU_META}）。 */
    public static final String XU_META = "Tr_xu_servant";

    /**
     * 双子本体（天虚恼鬼 / 天实悦灵）的 metadata 标记。
     *
     * <p>{@link #isBossBody} 的 VEX/ALLAY 类型兜底靠它做归属校验——
     * 没有这层标记时，一只野生的悦灵/恼鬼就能让 {@link #hasLiveBossAnywhere()}
     * 恒为真，把「重启自清理」与「孤儿备份返还」两道安全闸永久关死。</p>
     */
    public static final String TWIN_BODY_META = "Tr_boss_twin_body";

    /**
     * 战斗实体的 PDC 归属标记键（本体 / 展示架 / 道具掉落共用）。
     *
     * <p>为什么不用 metadata：metadata 不持久化，且按插件实例配对（/reload 后旧实例
     * 写的标记认不出、也删不掉）；PDC 存在实体数据里，对存活实体始终可识别。
     * 配合本体 {@code setPersistent(false)}，重启 / 崩溃后不会有残留本体——
     * 「场上还有活着的挑战本体」安全闸不会再被自己的残留实体关死。</p>
     */
    public static final String ENTITY_TAG_KEY = "tangran_boss_entity";

    private static volatile org.bukkit.NamespacedKey entityTagKey;

    private static org.bukkit.NamespacedKey entityTagKey() {
        org.bukkit.NamespacedKey k = entityTagKey;
        if (k == null) {
            k = new org.bukkit.NamespacedKey(TT.plugin, ENTITY_TAG_KEY);
            entityTagKey = k;
        }
        return k;
    }

    /** 给战斗实体打 PDC 归属标记（护法本体 / 展示架 / 双子本体都用）。 */
    public static void tagEntity(Entity e) {
        try {
            if (e == null || TT.plugin == null) return;
            e.getPersistentDataContainer().set(entityTagKey(),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        } catch (Throwable ignored) {
            // 打标失败不阻断生成
        }
    }

    /** 该实体是否带战斗归属标记（{@link #tagEntity} 写入）。 */
    public static boolean hasEntityTag(Entity e) {
        try {
            return e != null && e.getPersistentDataContainer().has(entityTagKey(),
                    org.bukkit.persistence.PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 实体最后已知位置所在区块是否尚未加载。
     *
     * <p>护法追踪任务用：本体 {@code isValid()} 为 false 有两种成因——真被杀 /
     * 被清除，或本体所在区块被卸载（persistent(false) 后卸载即消失）。后者直接
     * 走退场结算会把「玩家远离」当成击杀，白捡通关进度；区块没回来之前应当
     * 保持任务运行再观察。null 不算未加载（本就没本体，按真没了处理）。</p>
     */
    public static boolean inUnloadedChunk(Entity e) {
        if (e == null) return false;
        try {
            Location l = e.getLocation();
            org.bukkit.World w = l.getWorld();
            if (w == null) return false;
            return !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这个实体是不是「挑战本体」（糖然 / 护法 / 双子 / 虚仆）。
     *
     * <p>判定口径照抄原脚本 {@code xuHasLiveBossAnywhere} / {@code arenaHasLiveBoss}：
     * 优先看 metadata（虚仆 / 双子本体），再退到自定义名与实体类型兜底 ——
     * 名字判定的容错是刻意的：脚本重载后 metadata 可能还在，但名字一定还在。</p>
     */
    public static boolean isBossBody(Entity e) {
        if (e == null) return false;
        try {
            if (!e.isValid()) return false;
            if (e instanceof Player) return false;
            if (hasEntityTag(e)) return true;                        // PDC 归属标记（护法/双子/展示架）
            if (e.hasMetadata(XU_META)) return true;                 // 虚仆
            if (e.hasMetadata(TWIN_BODY_META)) return true;          // 双子本体（生成时打标）
            String name = "";
            try {
                name = String.valueOf(e.getCustomName());
            } catch (Throwable ignored) {
                name = "";
            }
            if (name.contains("糖然")) return true;                   // 糖然（盔甲架 / 僵尸 / 跪像）
            if (name.contains("[天")) return true;                    // 双子（含头顶展示盔甲架）
            if (name.contains("护法")) return true;
            String type = String.valueOf(e.getType());
            if ("VEX".equals(type) || "ALLAY".equals(type)) {
                // 双子本体的类型兜底（未命名时）必须带归属校验：
                // 野生的悦灵/恼幻（自然生成 / 宠物 / 唤魔者召唤）不认，
                // 否则 hasLiveBossAnywhere() 会被一只野生悦灵永久置真。
                return BossFight.byEntity(e) != null;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这块场地附近还有活着的挑战本体吗（原脚本 {@code arenaHasLiveBoss}）。
     *
     * <p><b>用途</b>：脚本重载会把内存里的战局状态全丢掉，但场地与本体还在原地。
     * 这种「重载残留的战局」<b>不能按普通残留清掉</b> ——
     * 否则正在挑战的玩家脚下地板会瞬间消失。</p>
     */
    public static boolean hasLiveBossNear(World world, double cx, double cy, double cz) {
        try {
            if (world == null) return false;
            Location center = new Location(world, cx + 0.5d, cy + 1.0d, cz + 0.5d);
            Collection<Entity> list = world.getNearbyEntities(center, 60, 45, 60);
            for (Entity e : list) {
                if (isBossBody(e)) return true;
            }
        } catch (Throwable ignored) {
            // 世界未加载：当作没有
        }
        return false;
    }

    /**
     * 全服还有活着的挑战本体吗（原脚本 {@code xuHasLiveBossAnywhere}）。
     *
     * <p><b>这是背包返还的安全闸门</b>：重载后内存里的战局全没了，
     * 若此时把 {@code phase2_backup_*.json} 当成「孤儿备份」还给玩家，
     * 就会把<b>正在挑战的那位玩家的备份吞掉</b>（他还得继续打完，
     * 那份备份是收场时要还的东西）。原脚本用这个判定整体跳过启动自清理。</p>
     */
    public static boolean hasLiveBossAnywhere() {
        try {
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                Collection<Entity> list;
                try {
                    list = world.getEntities();
                } catch (Throwable ignored) {
                    continue;
                }
                for (Entity e : list) {
                    if (isBossBody(e)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /**
     * 收集「要保留的格子」：装饰实体占的格 + 它的依附格。
     *
     * <p>画会横跨多格，所以用碰撞箱取格范围；展示框就一格。
     * 朝向决定依附格在「朝向的反方向」，但朝向符号不纠结 ——
     * 正反两个方向都记一遍（多记一格不会漏清什么，少记一格却会把方块拆掉、
     * 让展示框/画掉落变物品）。</p>
     */
    public static Set<String> collectDecorativeCells(World world, Region region) {
        Set<String> cells = new HashSet<>();
        try {
            if (world == null || region == null) return cells;
            double cx = (region.minX() + region.maxX()) / 2.0 + 0.5;
            double cy = (region.minY() + region.maxY()) / 2.0 + 0.5;
            double cz = (region.minZ() + region.maxZ()) / 2.0 + 0.5;
            double dx = (region.maxX() - region.minX()) / 2.0 + 2.5;
            double dy = (region.maxY() - region.minY()) / 2.0 + 2.5;
            double dz = (region.maxZ() - region.minZ()) / 2.0 + 2.5;
            Collection<Entity> list = world.getNearbyEntities(new Location(world, cx, cy, cz), dx, dy, dz);
            for (Entity e : list) {
                if (e == null || !e.isValid()) continue;
                if (!isDecorative(e)) continue;

                int[] xs;
                int[] ys;
                int[] zs;
                try {
                    BoundingBox box = e.getBoundingBox();
                    xs = new int[]{(int) Math.floor(box.getMinX()), (int) Math.floor(box.getMaxX())};
                    ys = new int[]{(int) Math.floor(box.getMinY()), (int) Math.floor(box.getMaxY())};
                    zs = new int[]{(int) Math.floor(box.getMinZ()), (int) Math.floor(box.getMaxZ())};
                } catch (Throwable ignored) {
                    Location el = e.getLocation();
                    xs = new int[]{el.getBlockX()};
                    ys = new int[]{el.getBlockY()};
                    zs = new int[]{el.getBlockZ()};
                }
                int fx = 0;
                int fy = 0;
                int fz = 0;
                try {
                    org.bukkit.block.BlockFace facing = ((org.bukkit.entity.Hanging) e).getFacing();
                    fx = facing.getModX();
                    fy = facing.getModY();
                    fz = facing.getModZ();
                } catch (Throwable ignored) {
                    // 拿不到朝向就只保占位格
                }
                for (int bx : xs) {
                    for (int by : ys) {
                        for (int bz : zs) {
                            cells.add(cellKey(bx, by, bz));
                            if (fx != 0 || fy != 0 || fz != 0) {
                                cells.add(cellKey(bx + fx, by + fy, bz + fz));
                                cells.add(cellKey(bx - fx, by - fy, bz - fz));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            BossLog.warn("收集装饰保护格失败: " + t);
        }
        return cells;
    }

    // ==================================================================
    // 清场
    // ==================================================================

    /**
     * 把区域内所有非空气方块清成空气（跳过保护格）。
     *
     * <p><b>性能提醒</b>：51×51×20 = 52020 格，逐格 {@code getBlockAt} 是几万个方块访问。
     * 但清场对空气只是 {@code getType} 快速跳过，真实改动通常只有几百格，
     * 同步执行实测在一拍以内（常驻场地每场结束的重置就走这里）。</p>
     *
     * @return 清掉的方块数
     */
    public static int clearAll(World world, Region region, Set<String> skipCells) {
        int n = 0;
        try {
            if (world == null || region == null) return 0;
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        if (skipCells != null && skipCells.contains(cellKey(x, y, z))) continue;
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (type == Material.AIR || type == Material.CAVE_AIR
                                || type == Material.VOID_AIR) {
                            continue;
                        }
                        try {
                            block.setType(Material.AIR, false);
                            n++;
                        } catch (Throwable ignored) {
                            // 单格失败不影响其它
                        }
                    }
                }
            }
        } catch (Throwable t) {
            BossLog.warn("清理场地方块失败: " + t);
        }
        return n;
    }

    /**
     * 只清场地特征方块（屏障 / 石英 / 基岩）。
     *
     * <p>还原与「启动自清理」共用。<b>基岩也一起清</b>：顶层正中间那格是场地模板里的
     * 基岩标记，残场里留一块基岩会让「这块地是不是已经拆干净了」的判断失真。</p>
     */
    public static int clearRegion(World world, Region region) {
        int n = 0;
        try {
            if (world == null || region == null) return 0;
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (type == Material.BARRIER || type == Material.QUARTZ_BLOCK
                                || type == Material.BEDROCK) {
                            block.setType(Material.AIR, false);
                            n++;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            BossLog.warn("清理场地特征方块失败: " + t);
        }
        return n;
    }

    // ==================================================================
    // 落盘记录
    // ==================================================================

    /** 记录目录（与进度存档同目录）。 */
    public static File recordDir() {
        return AddonPaths.configDir(BossProgress.DIR);
    }

    /** 某个玩家那一份场地记录文件。 */
    public static File recordFile(String id) {
        String k = (id == null || id.isEmpty()) ? "unknown" : id;
        return new File(recordDir(), "_arena_" + k + ".json");
    }

    /**
     * 存一份场地记录。
     *
     * @param cx 中心 X（通常等于标记格 X）
     * @param cy 中心 Y（<b>传 maxY</b>，即标记格 Y —— 删记录时就是按它比对的）
     * @param cz 中心 Z（通常等于标记格 Z）
     */
    public static void saveRecord(World world, Region region, int cx, int cy, int cz, String id) {
        try {
            if (world == null || region == null) return;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("world", world.getName());
            data.put("minX", region.minX());
            data.put("maxX", region.maxX());
            data.put("minY", region.minY());
            data.put("maxY", region.maxY());
            data.put("minZ", region.minZ());
            data.put("maxZ", region.maxZ());
            data.put("cx", cx);
            data.put("cy", cy);
            data.put("cz", cz);
            AddonPaths.writeJson(recordFile(id), data);
        } catch (Throwable t) {
            BossLog.warn("写场地记录失败 " + id + ": " + t);
        }
    }

    /** 读一份记录文件；非法（没有 world）返回 null。 */
    public static Map<String, Object> readRecord(File file) {
        try {
            if (file == null || !file.isFile()) return null;
            Map<String, Object> data = AddonPaths.readJson(file);
            if (data == null || data.get("world") == null) return null;
            return data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按玩家 id 读记录。 */
    public static Map<String, Object> loadRecord(String id) {
        return readRecord(recordFile(id));
    }

    /** 按玩家 id 删记录。 */
    public static void deleteRecord(String id) {
        try {
            File f = recordFile(id);
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按记录清掉一块残留场地，返回清掉的方块数。 */
    public static int clearFromRecord(Map<String, Object> rec) {
        try {
            if (rec == null) return 0;
            World w = Bukkit.getWorld(asString(rec.get("world")));
            if (w == null) return 0;
            Region region = new Region(
                    asInt(rec.get("minX")), asInt(rec.get("maxX")),
                    asInt(rec.get("minY")), asInt(rec.get("maxY")),
                    asInt(rec.get("minZ")), asInt(rec.get("maxZ")));
            return clearAll(w, region, null);
        } catch (Throwable t) {
            BossLog.warn("按记录清场失败: " + t);
            return 0;
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /** 所有记录文件（供启动自清理遍历）。 */
    public static List<File> listRecords() {
        List<File> out = new ArrayList<>();
        try {
            File[] files = recordDir().listFiles();
            if (files == null) return out;
            for (File f : files) {
                if (f != null && f.isFile() && f.getName().startsWith("_arena_")) out.add(f);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 旧版单文件记录（{@code _arena.json}）—— 只有在场没有任何进行中的挑战时才敢清理。 */
    public static File legacyRecordFile() {
        return new File(recordDir(), "_arena.json");
    }

    /** 供调试：把一份记录转成可读文本。 */
    public static String describe(Map<String, Object> rec) {
        if (rec == null) return "(null)";
        return Json.write(rec);
    }

    // ==================================================================
    // 启动自清理（批次 10，原脚本 cleanupStaleBossContent 的开机路径）
    // ==================================================================

    /**
     * 启动自清理：残留场地 + 挑战本体 / 虚仆 / 剑影 / 挑战道具 / 护法箭矢。
     *
     * <p>安全闸（照抄原脚本 13405-13411 行的口径）：<b>场上还有活着的挑战本体时
     * 什么都不清</b> —— 服务器 reload 会丢内存战局但场地和本体还在原地，
     * 那是「重载残留的活战局」，清掉等于把正在挑战的玩家脚下地板拆了。</p>
     *
     * <p>这条路径只服务<b>旧版本在主世界建场</b>留下的记录（专用世界 tangran_space 的场地
     * 常驻、每场结束就地重置，不写记录）。原来的「按标记清场」入口（挑战区域清除器道具）
     * 已随独立维度化删除，新挑战开场不再走清场。</p>
     *
     * @return 清掉的场地方块 + 残留实体总数
     */
    public static int cleanupStaleStartup() {
        int total = 0;
        try {
            if (hasLiveBossAnywhere()) {
                BossLog.warn("启动自清理：场上仍有活着的挑战本体 → 保留场地与血条，本次不清理"
                        + "（避免打断进行中的挑战）");
                return 0;
            }
            // 1) 场地：一场一份记录，逐个还原（石英场 / 屏障 / 装饰）
            for (File f : listRecords()) {
                try {
                    Map<String, Object> rec = readRecord(f);
                    if (rec != null) {
                        total += clearFromRecord(rec);
                        BossLog.warn("自清理：清除残留挑战场地（" + f.getName() + "）");
                    }
                } catch (Throwable ignored) {
                    // 单份失败不影响其它
                }
                try {
                    f.delete();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            // 旧版单文件记录：全场没挑战才走到这，一并清
            try {
                File legacy = legacyRecordFile();
                if (legacy.exists()) {
                    Map<String, Object> rec = readRecord(legacy);
                    if (rec != null) total += clearFromRecord(rec);
                    legacy.delete();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            if (total > 0) {
                BossLog.warn("自清理：共清除残留场地方块 " + total + " 个");
            }
        } catch (Throwable t) {
            BossLog.warn("启动自清理失败: " + t);
        }
        total += sweepMarkedEntities();
        return total;
    }

    /** 全世界扫一遍：清掉带我们标记 / 名字的残留实体。 */
    private static int sweepMarkedEntities() {
        int n = 0;
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    try {
                        if (e instanceof Player) continue;
                        if (!e.isValid()) continue;
                        // 注意：绝不能把 isDecorative 放进删除条件——它对全服任意世界的
                        // 展示框/画都返回 true，而本方法每次插件启用都会跑一遍（经
                        // cleanupStaleStartup），放进来等于「每次开服删光全服展示框与画」。
                        // 装饰实体的保留纪律由 clearRegion 的按坐标保护（:317/:387）负责。
                        if (isBossBody(e) || carriesOurTag(e)) {
                            e.remove();
                            n++;
                        }
                    } catch (Throwable ignored) {
                        // 单个失败不影响其它
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return n;
    }

    /** 带插件标记的残留实体（战斗实体 / 挑战道具掉落物 / 护法箭矢 / 二阶段道具掉落）。 */
    private static boolean carriesOurTag(Entity e) {
        try {
            if (hasEntityTag(e)) return true;
            if (e.hasMetadata("Tr_xu_challenge")) return true;
            if (e instanceof org.bukkit.entity.AbstractArrow aa
                    && aa.getPersistentDataContainer().has(
                            BossPhaseTwoItems.ARROW_KEY, org.bukkit.persistence.PersistentDataType.STRING)) {
                return true;
            }
            if (e instanceof org.bukkit.entity.Item item) {
                org.bukkit.inventory.ItemStack st = item.getItemStack();
                if (st != null && st.hasItemMeta() && st.getItemMeta() != null
                        && st.getItemMeta().getPersistentDataContainer().has(
                                BossPhaseTwoItems.ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }
}
