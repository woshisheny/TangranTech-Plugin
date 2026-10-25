package com.tangran.tangrantech.machines.superblock;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTSuperMultiBlockMachine;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * 超多方块管理器 —— 按 RSC {@code CustomSuperMultiBlockMachine + SuperMultiBlockManager +
 * SuperMultiBlockListener} 的可观察行为原样复原（糖然的四台超多方块机器均无 JS 脚本，
 * 走的全是 RSC 内建逻辑，因此无需脚本钩子）。
 *
 * <p>本类补齐旧实现缺失的五块能力：</p>
 * <ol>
 *   <li><b>方向感知</b>：放置核心时记录玩家朝向（RSC {@code HorizonDirection}），结构按
 *       4 个水平方向旋转匹配——旧实现只有固定朝向，转个方向结构就永远不成形；</li>
 *   <li><b>实例生命周期</b>：{@code coreStorage / monitoring / firstTicks} 三张表 +
 *       重叠检测（结构覆盖另一台的结构时拒绝搭建并广播提示）；</li>
 *   <li><b>dirty 驱动的逐格检测</b>：方块增删/爆炸/活塞/物理等事件把格子标记为脏，
 *       只重检该格（旧实现每 tick 全结构扫描，浑天仪约六百格 × 每刻 × 每台，开销不可接受；
 *       另每 500 tick 全量自检一次对齐 RSC 的自愈）；</li>
 *   <li><b>成形/破坏广播</b>（{@code defaultNotice}）：搭建完成 / 被破坏 / 阻碍搭建，
 *       对 10 格内玩家广播，文案沿用 RSC 原文；</li>
 *   <li><b>投影层</b>（{@code displayProjectiles + allowSwitchDisplayLayer}）：未成形时为每个
 *       结构格生成 BLOCK_DISPLAY 投影（缺失格发光），核心右键切层（潜行=向下），成形后移除。
 *       显示层下标经 {@code StorageCacheUtils} 持久化，重启不丢；重启后按 PDC 收养旧投影。</li>
 * </ol>
 *
 * <p>交互语义（对齐 RSC {@code CustomSuperMultiBlockMachine.onInteract}）：
 * 右键部件/核心 → 成形时打开核心菜单（{@code openMenuWhenClickedParts}）；
 * 未成形时菜单被挡（{@code noMenuWhenNotFormed}），核心右键改为切换投影层；
 * 潜行 + 手持方块放行，让玩家能贴着结构放置。</p>
 */
public final class SuperBlocks implements Listener {

    private SuperBlocks() {}

    // ==================================================================
    // 方向（对齐 RSC HorizonDirection + SuperMultiBlockDefinition.getRotatedMap）
    // ==================================================================

    public enum Dir {
        NORTH, EAST, SOUTH, WEST;

        /** yml 基准朝向（NORTH）下的偏移旋到本朝向：EAST(-z,y,x) / SOUTH(-x,y,-z) / WEST(z,y,-x)。 */
        public int rx(int x, int y, int z) {
            return switch (this) {
                case NORTH -> x;
                case EAST -> -z;
                case SOUTH -> -x;
                case WEST -> z;
            };
        }

        public int rz(int x, int y, int z) {
            return switch (this) {
                case NORTH -> z;
                case EAST -> x;
                case SOUTH -> -z;
                case WEST -> -x;
            };
        }

        /** 对齐 RSC {@code HorizonDirection.getDirectionFromYaw}：0°=北(-Z)，90°=东(+X)。 */
        public static Dir fromYaw(float yaw) {
            float n = yaw % 360;
            if (n < 0) n += 360;
            if (n >= 315 || n < 45) return NORTH;
            if (n >= 45 && n < 135) return EAST;
            if (n >= 135 && n < 225) return SOUTH;
            return WEST;
        }
    }

    /** 结构格相对核心的偏移（不可变，作 Map 键）。 */
    record Off(int x, int y, int z) {}

    // ==================================================================
    // 实例
    // ==================================================================

    static final class Inst {
        final TTSuperMultiBlockMachine machine;
        final Location core;
        final Dir dir;
        /** 旋转后的结构格：偏移 → 期望物品（核心本身不在其中，对齐 RSC blockParts）。 */
        final Map<Off, TTSuperMultiBlockMachine.Expect> cells;
        final Set<Location> cellLocs;
        /** 各显示层的绝对 Y（升序）。 */
        final int[] layerYs;
        final Set<Location> correct = new CopyOnWriteArraySet<>();
        volatile boolean formed;
        /** 当前显示层下标；{@link #DISPLAY_ALL} 表示全部层可见。 */
        volatile int layerIndex;
        final Map<Location, Display> projectiles = new ConcurrentHashMap<>();
        int tickCount;
        int rescanCount;

        Inst(TTSuperMultiBlockMachine machine, Location core, Dir dir, Map<Off, TTSuperMultiBlockMachine.Expect> cells) {
            this.machine = machine;
            this.core = core;
            this.dir = dir;
            this.cells = cells;
            Set<Location> locs = new CopyOnWriteArraySet<>();
            Set<Integer> ys = new java.util.TreeSet<>();
            World w = core.getWorld();
            for (Off o : cells.keySet()) {
                Location l = new Location(w, core.getX() + o.x(), core.getY() + o.y(), core.getZ() + o.z());
                locs.add(l);
                ys.add(l.getBlockY());
            }
            this.cellLocs = locs;
            int[] arr = new int[ys.size()];
            int i = 0;
            for (int y : ys) arr[i++] = y;
            this.layerYs = arr;
        }

        boolean isFormedNow() {
            return correct.size() >= cellLocs.size() && correct.containsAll(cellLocs);
        }

        boolean isLayerFormed(int y) {
            for (Location l : cellLocs) {
                if (l.getBlockY() != y) continue;
                if (!correct.contains(l)) return false;
            }
            return true;
        }
    }

    // ==================================================================
    // 全局状态（对齐 RSC coreStorage / monitoringLocations / firstTicks / dirtyLocations）
    // ==================================================================

    private static final Map<Location, Inst> CORE = new ConcurrentHashMap<>();
    private static final Map<Location, Inst> MONITOR = new ConcurrentHashMap<>();
    private static final Set<Location> FIRST_TICKS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Dirty> DIRTY = new ConcurrentLinkedQueue<>();

    private record Dirty(Location loc, boolean autoSwitchLayer) {}

    /** 「全部层可见」（对齐 RSC DISPLAY_ALL = -999）。 */
    private static final int DISPLAY_ALL = -999;
    private static final float DISPLAY_SCALE = 0.8f;
    private static NamespacedKey DISPLAY_KEY;
    private static volatile boolean registered;

    // ==================================================================
    // 注册与入口
    // ==================================================================

    /** 注册监听与脏队列消费（幂等；由 loader 在机器注册完后调用）。 */
    public static synchronized void registerOnce() {
        if (registered) return;
        registered = true;
        DISPLAY_KEY = new NamespacedKey(TT.plugin, "superblock_display");
        Bukkit.getPluginManager().registerEvents(new SuperBlocks(), TT.plugin);
        // 脏队列逐刻清空（RSC 用异步任务，我们同步处理——单格检查开销极小，同步更简单安全）
        Bukkit.getScheduler().runTaskTimer(TT.plugin, SuperBlocks::drainDirty, 1L, 1L);
    }

    /** 核心被放置：记录玩家朝向（持久化，重启不丢）。 */
    public static void onCorePlaced(Location core, Dir dir) {
        try {
            StorageCacheUtils.setData(core, "TTHorizonDirection", dir.name());
        } catch (Throwable ignored) {
            // 存储不可用时退化为默认朝向
        }
    }

    /** 机器 tick 入口：首刻建档 + 周期自愈（对齐 RSC tick 的自愈逻辑并加强）。 */
    public static void onMachineTick(TTSuperMultiBlockMachine machine, Block core) {
        Location loc = core.getLocation();
        Inst inst = CORE.get(loc);
        if (inst == null) {
            // 对齐 RSC：firstTicks 成功添加才尝试启动；启动失败（结构重叠）不再自动重试，
            // 拆掉重放才会重来（destroy 会从 FIRST_TICKS 移除）
            if (FIRST_TICKS.add(loc)) tryStart(machine, loc);
            return;
        }
        inst.tickCount++;
        if (!inst.formed) {
            // 未成形：每 40 tick 全量重扫自愈，但仅限附近有玩家时——全世界未成形的机器
            // 都自检会造成无意义的扫描开销（性能问题：所有核心都在周期重扫）。
            // 逐格 dirty 检测只覆盖「建档后发生的方块事件」，建档前就摆好的方块
            // （先搭外壳、最后放核心的常见流程）只能靠建档时的初始扫描——
            // 那次扫描一旦读到陈旧/异常数据，这些格子永远不会被事件重检，机器将永远「未成形」。
            if (inst.tickCount % 40 == 0 && playerNearby(loc, 48)) fullRescan(inst);
        } else if (inst.tickCount % 500 == 0 && playerNearby(loc, 48)) {
            for (Location l : inst.cellLocs) DIRTY.add(new Dirty(l, false));
        }
    }

    /** 附近是否有玩家（纯坐标运算，机器 tick 在异步线程也可安全调用）。 */
    private static boolean playerNearby(Location loc, double radius) {
        double r2 = radius * radius;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            double dx = p.getLocation().getX() - loc.getX();
            double dy = p.getLocation().getY() - loc.getY();
            double dz = p.getLocation().getZ() - loc.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 缓存的成形状态：管理器无实例时视为未成形（生产暂停，直到首刻建档完成）。 */
    public static boolean isFormed(Location core) {
        Inst inst = CORE.get(core);
        return inst != null && inst.formed;
    }

    /** 核心被破坏（机器的 BlockBreakHandler 调用）：销毁实例与投影。 */
    public static void destroyAt(Location core) {
        Inst inst = CORE.remove(core);
        if (inst != null) destroy(inst);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    private static void tryStart(TTSuperMultiBlockMachine machine, Location core) {
        Dir dir;
        try {
            String s = StorageCacheUtils.getData(core, "TTHorizonDirection");
            dir = (s == null || s.isEmpty()) ? Dir.NORTH : Dir.valueOf(s);
        } catch (Throwable t) {
            dir = Dir.NORTH;
        }

        Inst inst = buildInst(machine, core, dir);
        if (inst == null) return;

        // 注册 + 初始扫描必须在主线程：机器 tick 在异步线程触发本方法，异步读方块
        // 有陈旧/异常风险（见 runSync 注释），而初始扫描是「建档前摆好的方块」的唯一判定。
        runSync(() -> {
            if (CORE.containsKey(core)) return;   // 已被同刻的其他路径建档

            // 重叠检测：任一结构格已被别的实例占用 → 拒绝（对齐 RSC startSuperMultiBlock）
            for (Location l : inst.cellLocs) {
                if (MONITOR.containsKey(l)) {
                    if (machine.flags().defaultNotice()) {
                        notice(core, p -> p.sendMessage("§c附近存在其他多方块阻碍，无法搭建该多方块，请拆除后重试。"));
                    }
                    return;
                }
            }

            for (Location l : inst.cellLocs) MONITOR.put(l, inst);
            CORE.put(core, inst);

            // 建档即逐格缓存（对齐 RSC generateCache）
            for (Location l : inst.cellLocs) {
                if (cellMatches(inst, l)) inst.correct.add(l);
            }
            inst.formed = inst.isFormedNow();

            if (inst.formed) {
                noticeFormed(inst);
            } else {
                if (machine.flags().defaultNotice()) {
                    String click = machine.flags().noMenuWhenNotFormed() ? "右键" : "左键";
                    notice(core, p -> p.sendMessage("§a你已放置 " + machine.getItemName()
                            + ". §a" + click + " 或 Shift+" + click + "以切换投影层."));
                }
                if (machine.flags().displayProjectiles()) {
                    spawnProjectiles(inst);
                } else {
                    removeProjectiles(inst);
                }
            }
        });
    }

    /** 从机器的结构/mapping/核心偏移构建旋转后的实例（坐标公式与旧 isFormed 一致）。 */
    private static Inst buildInst(TTSuperMultiBlockMachine machine, Location core, Dir dir) {
        List<List<List<String>>> structure = machine.getStructure();
        Map<String, TTSuperMultiBlockMachine.Expect> mapping = machine.getMapping();
        int[] coreOffset = machine.getCoreOffset();
        if (structure == null || structure.isEmpty() || mapping == null || coreOffset == null) return null;

        Map<Off, TTSuperMultiBlockMachine.Expect> cells = new java.util.LinkedHashMap<>();
        for (int l = 0; l < structure.size(); l++) {
            List<List<String>> rows = structure.get(l);
            if (rows == null) continue;
            for (int r = 0; r < rows.size(); r++) {
                List<String> cols = rows.get(r);
                if (cols == null) continue;
                for (int c = 0; c < cols.size(); c++) {
                    // 核心本身不进结构格（对齐 RSC blockParts.remove(corePos)）
                    if (l == coreOffset[0] && r == coreOffset[1] && c == coreOffset[2]) continue;
                    TTSuperMultiBlockMachine.Expect expect = mapping.get(cols.get(c));
                    if (expect == null || expect.material().isAir()) continue;   // "__" 通配
                    int dx = c - coreOffset[2];
                    int dy = coreOffset[0] - l;
                    int dz = r - coreOffset[1];
                    cells.put(new Off(dir.rx(dx, dy, dz), dy, dir.rz(dx, dy, dz)), expect);
                }
            }
        }
        if (cells.isEmpty()) return null;
        return new Inst(machine, core, dir, cells);
    }

    private static void destroy(Inst inst) {
        for (Location l : inst.cellLocs) {
            if (MONITOR.get(l) == inst) MONITOR.remove(l);
        }
        CORE.remove(inst.core);
        FIRST_TICKS.remove(inst.core);
        removeProjectiles(inst);
    }

    // ==================================================================
    // 逐格检测（对齐 RSC processDirty）
    // ==================================================================

    private static void drainDirty() {
        Dirty d;
        while ((d = DIRTY.poll()) != null) {
            try {
                processDirty(d);
            } catch (Throwable t) {
                TT.log("[超多方块] 脏格子处理出错: " + t);
            }
        }
    }

    private static void processDirty(Dirty d) {
        Location loc = d.loc();
        Inst inst = MONITOR.get(loc);
        if (inst == null) return;

        boolean before = inst.formed;
        boolean ok = cellMatches(inst, loc);
        if (ok) {
            inst.correct.add(loc);
        } else {
            inst.correct.remove(loc);
        }
        Display proj = inst.projectiles.get(loc);
        if (proj != null && proj.isValid()) proj.setGlowing(!ok);

        boolean now = inst.isFormedNow();

        // 逐层搭建的自动换层（对齐 RSC autoSwitchLayer：当前层搭完 → 自动切到下一个未成形层）
        if (!now && d.autoSwitchLayer() && inst.layerIndex != DISPLAY_ALL
                && inst.layerIndex >= 0 && inst.layerIndex < inst.layerYs.length) {
            int layerY = inst.layerYs[inst.layerIndex];
            if (inst.isLayerFormed(layerY) && switchToUnformedLayer(inst)
                    && inst.machine.flags().defaultNotice()) {
                notice(loc, p -> p.sendMessage("§c已搭建完成 y=" + layerY));
            }
        }

        if (before && !now) {
            // 结构被破坏：广播 + 销毁实例；下一刻 firstTick 会重新建档（投影重现、缺失格发光）
            inst.formed = false;
            if (inst.machine.flags().defaultNotice()) {
                notice(loc, p -> p.sendMessage("§c" + inst.machine.getItemName() + "§c已被破坏!"));
            }
            destroy(inst);
        } else if (!before && now) {
            inst.formed = true;
            noticeFormed(inst);
            if (inst.machine.flags().displayProjectiles()) removeProjectiles(inst);
        }
    }

    /** 切到第一个未成形层；成功返回 true（对齐 RSC switchToUnformedLayer）。 */
    private static boolean switchToUnformedLayer(Inst inst) {
        for (int i = 0; i < inst.layerYs.length; i++) {
            if (!inst.isLayerFormed(inst.layerYs[i])) {
                setLayer(inst, i);
                return true;
            }
        }
        return false;
    }

    /**
     * 未成形实例的全量重扫自愈（主线程执行）。
     *
     * <p>修正「建档时初始扫描读到陈旧数据、之后又没有事件再触发重检」造成的永久未成形；
     * 逐层投影的发光状态同步刷新。成形/破坏的状态迁移与 {@link #processDirty} 同一套语义。</p>
     */
    private static void fullRescan(Inst inst) {
        runSync(() -> {
            if (CORE.get(inst.core) != inst) return;   // 实例已被重建/销毁
            for (Location l : inst.cellLocs) {
                boolean ok = cellMatches(inst, l);
                if (ok) inst.correct.add(l);
                else inst.correct.remove(l);
                Display p = inst.projectiles.get(l);
                if (p != null && p.isValid()) p.setGlowing(!ok);
            }
            boolean now = inst.isFormedNow();
            boolean before = inst.formed;
            if (now == before) {
                if (!now && ++inst.rescanCount % 5 == 0) {
                    TT.log("[超多方块] " + inst.machine.getId() + " @"
                            + inst.core.getBlockX() + "," + inst.core.getBlockY() + "," + inst.core.getBlockZ()
                            + " 仍未成形: " + inst.correct.size() + "/" + inst.cellLocs.size()
                            + " 格就位（发光方块为缺失格）");
                }
                return;
            }
            inst.formed = now;
            if (now) {
                noticeFormed(inst);
                if (inst.machine.flags().displayProjectiles()) removeProjectiles(inst);
            } else {
                if (inst.machine.flags().defaultNotice()) {
                    notice(inst.core, p -> p.sendMessage("§c" + inst.machine.getItemName() + "§c已被破坏!"));
                }
                destroy(inst);
            }
        });
    }

    /** 单格比对：与 {@link TTSuperMultiBlockMachine} 的匹配规则一致（粘液按 id、原版按材质）。 */
    private static boolean cellMatches(Inst inst, Location loc) {
        Off key = new Off(
                loc.getBlockX() - inst.core.getBlockX(),
                loc.getBlockY() - inst.core.getBlockY(),
                loc.getBlockZ() - inst.core.getBlockZ());
        TTSuperMultiBlockMachine.Expect expect = inst.cells.get(key);
        if (expect == null) return false;
        return TTSuperMultiBlockMachine.matchesBlock(loc.getBlock(), expect);
    }

    // ==================================================================
    // 交互（对齐 RSC CustomSuperMultiBlockMachine.onInteract + SuperMultiBlockListener）
    // ==================================================================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // 右键方块时本事件对手/副手各发一次——不过滤会连续切两层（实测：一次点击跳两层）
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Inst inst = MONITOR.get(b.getLocation());
        boolean clickedCore = false;
        if (inst == null) {
            inst = CORE.get(b.getLocation());
            clickedCore = inst != null;
        }
        if (inst == null) return;

        try {
            if (!Slimefun.getProtectionManager().hasPermission(p, b, Interaction.INTERACT_BLOCK)) return;
        } catch (Throwable ignored) {
            // 保护插件缺失时放行
        }

        var tp = p.getInventory().getItemInMainHand().getType();
        boolean holdingBlock = tp.isBlock() && !tp.isAir();
        if (holdingBlock && p.isSneaking()) return;   // 玩家要放方块，放行

        TTSuperMultiBlockMachine.SuperFlags flags = inst.machine.flags();
        boolean formed = inst.formed;

        // 未成形 + 点核心 + 允许换层：右键（noMenuWhenNotFormed 时）切换投影层；潜行=向下
        if (flags.allowSwitchDisplayLayer() && !formed && clickedCore) {
            boolean right = e.getAction().isRightClick();
            if (!holdingBlock && ((flags.noMenuWhenNotFormed() && right)
                    || (!flags.noMenuWhenNotFormed() && !right))) {
                e.setCancelled(true);
                switchLayer(inst, p, p.isSneaking());
                return;
            }
        }

        if (!flags.openMenuWhenClickedParts() && !clickedCore) return;

        // 未成形挡菜单：RSC 用反射替换 BlockMenu.open 实现，这里直接取消事件（行为等价：点了没反应）
        if (flags.noMenuWhenNotFormed() && !formed) {
            if (clickedCore) e.setCancelled(true);
            return;
        }

        // 成形：打开核心菜单（部件点击也开核心菜单）
        e.setCancelled(true);
        BlockMenu menu = StorageCacheUtils.getMenu(inst.core);
        if (menu != null) menu.open(p);
    }

    /** 切换投影层（对齐 RSC switchLayer：单层 ↔ 全部层循环，潜行=向下）。 */
    private static void switchLayer(Inst inst, Player p, boolean down) {
        if (inst.layerYs.length <= 1) return;
        int cur = inst.layerIndex;
        if (cur == DISPLAY_ALL) {
            setLayer(inst, down ? inst.layerYs.length - 1 : 0);
            int idx = inst.layerIndex;
            notice(inst.core, pl -> pl.sendMessage("§a已切换多方块显示层为 y=" + inst.layerYs[idx]
                    + " (第" + (idx + 1) + "/" + inst.layerYs.length + "层)"));
        } else if ((cur == inst.layerYs.length - 1 && !down) || (cur == 0 && down)) {
            setLayer(inst, DISPLAY_ALL);
            notice(inst.core, pl -> pl.sendMessage("§a已切换多方块显示层为所有层"));
        } else {
            setLayer(inst, cur + (down ? -1 : 1));
            int idx = inst.layerIndex;
            notice(inst.core, pl -> pl.sendMessage("§a已切换多方块显示层为 y=" + inst.layerYs[idx]
                    + " (第" + (idx + 1) + "/" + inst.layerYs.length + "层)"));
        }
        try {
            StorageCacheUtils.setData(inst.core, "TTLayerIndex", String.valueOf(inst.layerIndex));
        } catch (Throwable ignored) {
        }
    }

    private static void setLayer(Inst inst, int index) {
        inst.layerIndex = index;
        if (!inst.machine.flags().displayProjectiles()) return;
        for (Map.Entry<Location, Display> en : inst.projectiles.entrySet()) {
            Display d = en.getValue();
            if (d == null || !d.isValid()) continue;
            boolean visible = index == DISPLAY_ALL
                    || (index >= 0 && index < inst.layerYs.length
                        && en.getKey().getBlockY() == inst.layerYs[index]);
            d.setTransformation(visible ? defaultTransformation() : invisibleTransformation());
        }
    }

    // ==================================================================
    // 投影（对齐 RSC addProjectiles / removeProjectiles / checkProjectiles）
    // ==================================================================

    private static Transformation defaultTransformation() {
        float offset = (1.0f - DISPLAY_SCALE) / 2f;
        return new Transformation(new Vector3f(offset, offset, offset),
                new AxisAngle4f(0, 0, 0, 0), new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                new AxisAngle4f(0, 0, 0, 0));
    }

    private static Transformation invisibleTransformation() {
        return new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0),
                new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0));
    }

    private static void spawnProjectiles(Inst inst) {
        // 生成/收养实体必须主线程（机器 tick 在异步线程触发本方法）
        runSync(() -> spawnProjectilesSync(inst));
    }

    private static void spawnProjectilesSync(Inst inst) {
        World w = inst.core.getWorld();
        for (Location l : inst.cellLocs) {
            TTSuperMultiBlockMachine.Expect expect = inst.cells.get(new Off(
                    l.getBlockX() - inst.core.getBlockX(),
                    l.getBlockY() - inst.core.getBlockY(),
                    l.getBlockZ() - inst.core.getBlockZ()));
            if (expect == null || expect.sfId() != null || !expect.material().isBlock()) continue;   // 非原版方块不给投影，对齐 RSC

            // 重启后世界里的旧投影还活着——按 PDC 收养，避免重复生成（对齐 RSC addProjectile 收养分支）
            Display existing = findAdoptable(w, l);
            if (existing != null) {
                inst.projectiles.put(l, existing);
                continue;
            }

            BlockDisplay d = (BlockDisplay) w.spawnEntity(l, EntityType.BLOCK_DISPLAY);
            d.setBlock(expect.material().createBlockData());
            d.setTransformation(defaultTransformation());
            d.getPersistentDataContainer().set(DISPLAY_KEY, PersistentDataType.BOOLEAN, true);
            d.setGlowing(!inst.correct.contains(l));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setCustomNameVisible(false);
            inst.projectiles.put(l, d);
        }
        // 初始只显示核心所在层（对齐 RSC addProjectiles 末尾）
        int coreIdx = 0;
        for (int i = 0; i < inst.layerYs.length; i++) {
            if (inst.layerYs[i] == inst.core.getBlockY()) { coreIdx = i; break; }
        }
        setLayer(inst, coreIdx);
        try {
            StorageCacheUtils.setData(inst.core, "TTLayerIndex", String.valueOf(coreIdx));
        } catch (Throwable ignored) {
        }
    }

    private static Display findAdoptable(World w, Location l) {
        for (Entity e : w.getNearbyEntities(l, 0.1, 0.1, 0.1)) {
            if ((e.getType() == EntityType.BLOCK_DISPLAY || e.getType() == EntityType.ITEM_DISPLAY)
                    && e.getPersistentDataContainer().has(DISPLAY_KEY, PersistentDataType.BOOLEAN)) {
                return (Display) e;
            }
        }
        return null;
    }

    private static void removeProjectiles(Inst inst) {
        // 移除实体必须主线程（tryStart 在异步机器 tick 里也会走到这里）
        runSync(() -> {
            for (Map.Entry<Location, Display> en : inst.projectiles.entrySet()) {
                Display d = en.getValue();
                if (d != null && !d.isDead() && d.isValid()) d.remove();
            }
            inst.projectiles.clear();
        });
    }

    // ==================================================================
    // 世界事件 → 脏标记（事件清单对齐 RSC SuperMultiBlockListener）
    // ==================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) DIRTY.add(new Dirty(b.getLocation(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) DIRTY.add(new Dirty(b.getLocation(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTNTPrime(TNTPrimeEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), true)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) DIRTY.add(new Dirty(b.getLocation(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) DIRTY.add(new Dirty(b.getLocation(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent e) { DIRTY.add(new Dirty(e.getBlock().getLocation(), false)); }

    // ==================================================================
    // 杂项
    // ==================================================================

    private static void noticeFormed(Inst inst) {
        if (!inst.machine.flags().defaultNotice()) return;
        notice(inst.core, p -> p.sendMessage("§a已搭建完成 " + inst.machine.getItemName()));
    }

    /**
     * 主线程执行：已在主线程则内联，否则调度到下一刻。
     *
     * <p>必须包住所有实体/玩家操作——Slimefun 的 TickerTask 在<b>异步线程</b>跑机器 tick
     * （实测栈：ForkJoinPool worker 触发 AsyncCatcher「failed main thread check」），
     * getNearbyPlayers / spawnEntity / getNearbyEntities 从异步调用会被拦截且投影生成中断。
     * RSC 原版正是用 {@code Asynchronized.runTask} 包住全部实体操作，此处对齐。</p>
     */
    private static void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(TT.plugin, r);
        }
    }

    private static void notice(Location loc, Consumer<Player> msg) {
        runSync(() -> {
            try {
                loc.getWorld().getNearbyPlayers(loc, 10).forEach(msg);
            } catch (Throwable ignored) {
            }
        });
    }
}
