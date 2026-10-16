package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.tangran.tangrantech.native_scripts.SlimefunBridge;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 「初章祭坛」的原生实现（对应 {@code scripts/初章祭坛.js}）。
 *
 * <p>这是纯**视觉**脚本，不参与机器逻辑本身。玩法：玩家右键初章祭坛（多方块机器
 * {@code TR_CZJT}）附近任意方块时，为它挂一个「观察者」——每 5 刻轮询一次机器的
 * 运行状态。一旦机器开始合成，就播放一段仪式动画：</p>
 * <ul>
 *   <li>在同层半径 4 内的每个发射器上方 1 格掉落一份「配料」展示物（无重力、不可拾取、速度归零）；</li>
 *   <li>在核心正上方 5 格处生成产物的 {@link ItemDisplay}（多个产物按 0.4 格横向错开）；</li>
 *   <li>每 20 刻对 10 格内玩家播 {@code entity.enderman.teleport} + {@code entity.allay.hurt}；</li>
 *   <li>每 2 刻从每个配料位置到核心画一条抛物线附魔粒子；</li>
 *   <li>机器停转（或兜底计时到）时统一清除上述实体，并播一次 {@code block.lava.pop}。</li>
 * </ul>
 *
 * <p>与原脚本的两处必要适配（已在方法内注明）：</p>
 * <ol>
 *   <li><b>机器状态查询</b>：原脚本反射调用 RSC 自己的 {@code SuperMultiBlockMachine} 的
 *       {@code getTicker()/getCurrentOperation()}。本插件里对应实现是
 *       {@link com.tangran.tangrantech.machines.TTSuperMultiBlockMachine}，它继承
 *       {@link AContainer}，因此改走 Slimefun 标准通道
 *       {@code getMachineProcessor().getOperation(...)}，拿到的是 {@link CraftingOperation}。</li>
 *   <li><b>RSC 发射器展示物清理</b>：原脚本调用
 *       {@code SuperMultiBlockManager.getProjectiles()} 清掉 RSC 在发射器上生成的展示实体。
 *       独立插件没有这个 API，且本插件的机器从不生成此类实体，因此该方法退化为空实现——
 *       若照搬「删掉半径内所有发射器上的 ItemDisplay」，会误删玩家自己放的展示实体。</li>
 * </ol>
 */
public final class FirstChapterAltarScript implements NativeLifecycleScript {

    /** 核心方块 id（初章祭坛）。只对这一台机器生效，与原脚本一致。 */
    private static final String CORE_ID = "TR_CZJT";

    /** 右键非核心方块时，向外搜索核心的半径。 */
    private static final int CORE_FIND_RADIUS = 8;
    /** 围绕核心搜索发射器的半径（仅同层）。 */
    private static final int DISPENSER_RADIUS = 4;

    /** 配料掉落的时间间隔（刻），同时也是音效间隔。 */
    private static final int INTERVAL = 20;
    /** 观察者轮询周期（刻）。 */
    private static final int WATCH_PERIOD = 5;
    /** 粒子绘制周期（刻）。 */
    private static final int PARTICLE_PERIOD = 2;
    /** 音效可及半径（格）。 */
    private static final double SOUND_RADIUS = 10.0;

    /** 抛物线采样段数。 */
    private static final int PARABOLA_STEPS = 16;
    /** 抛物线拱高（格）。 */
    private static final double PARABOLA_HEIGHT = 2.5;

    /** 结果展示实体距核心的相对高度（格）。 */
    private static final double CENTER_HEIGHT = 5.0;
    /** 多个产物之间的横向间距（格）。 */
    private static final double RESULT_SPACING = 0.4;

    /** 兜底计时在配方时长之外额外等待的刻数。 */
    private static final int FALLBACK_EXTRA_TICKS = 15;

    /** 展示物的拾取延迟，取最大值表示「永不拾取」。 */
    private static final int NEVER_PICKUP = 32767;

    /** 附魔粒子：1.20.5+ 叫 ENCHANT，更早叫 ENCHANTMENT_TABLE，运行期探测。 */
    private static final Particle ENCHANT_PARTICLE = resolveEnchantParticle();

    /** 核心位置 key → 观察者任务。 */
    private static final Map<String, BukkitTask> WATCHERS = new ConcurrentHashMap<>();
    /** 核心位置 key → 进行中的动画。 */
    private static final Map<String, Animation> ANIMATIONS = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        // 原脚本只靠监听器驱动，没有对外回调函数
        return null;
    }

    /** 运行期探测附魔粒子常量名。 */
    private static Particle resolveEnchantParticle() {
        for (String name : new String[] {"ENCHANT", "ENCHANTMENT_TABLE"}) {
            try {
                return Particle.valueOf(name);
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 结构探测
    // ------------------------------------------------------------------

    /** 该位置是否为本脚本关心的机器核心。 */
    public static boolean isCoreBlock(Location loc) {
        return loc != null && SlimefunBridge.isBlock(loc.getBlock(), CORE_ID);
    }

    /** 以 center 为中心搜核心方块，返回第一个命中的位置；找不到返回 null。 */
    private static Location findCoreNear(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return null;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    try {
                        Location loc = new Location(world, cx + dx, cy + dy, cz + dz);
                        if (isCoreBlock(loc)) return loc;
                    } catch (Throwable ignored) {
                        // 越界坐标跳过
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找出核心同层的全部发射器。
     *
     * <p>遍历顺序（dx 外层、dz 内层）与原脚本一致，保证「第 i 份配料 → 第 i 个发射器」
     * 的对应关系稳定可复现。</p>
     */
    private static List<Location> findDispensers(World world, Location coreLoc, int radius) {
        List<Location> result = new ArrayList<>();
        int cy = coreLoc.getBlockY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                try {
                    Block b = world.getBlockAt(
                            coreLoc.getBlockX() + dx, cy, coreLoc.getBlockZ() + dz);
                    if (b.getType() == Material.DISPENSER) {
                        result.add(b.getLocation());
                    }
                } catch (Throwable ignored) {
                    // 未加载区块跳过
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 机器状态
    // ------------------------------------------------------------------

    /**
     * 取核心机器当前正在进行的合成操作；空闲或不是容器机器时返回 null。
     *
     * <p>原脚本先试 {@code getTicker().getCurrentOperation(loc)}，再退回
     * {@code getMachineProcessor().getOperation(...)}。本插件里机器就是 Slimefun 标准容器，
     * 直接走后者即可。</p>
     */
    private static CraftingOperation operationAt(Location coreLoc) {
        try {
            SlimefunItem sf = SlimefunItem.getById(CORE_ID);
            if (!(sf instanceof AContainer container)) return null;
            return container.getMachineProcessor().getOperation(coreLoc.getBlock());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 实体 / 粒子 / 音效
    // ------------------------------------------------------------------

    /** 生成产物的展示实体（无重力、无敌、不发光）。 */
    private static ItemDisplay spawnProjection(World world, Location loc, ItemStack stack) {
        if (stack == null) return null;
        try {
            ItemDisplay display = world.spawn(loc, ItemDisplay.class, e -> {
                e.setItemStack(stack.clone());
                e.setGravity(false);
                e.setInvulnerable(true);
                e.setGlowing(false);
                try {
                    e.setTeleportDuration(1);
                } catch (Throwable ignored) {
                    // 旧服务端无此属性
                }
            });
            return display;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 掉落一份「配料」展示物：速度归零、无重力、永不可拾取。 */
    private static Item spawnMaterialDrop(World world, Location loc, ItemStack stack) {
        if (stack == null) return null;
        try {
            Item item = world.dropItem(loc, stack.clone());
            item.setVelocity(new Vector(0, 0, 0));
            item.setGravity(false);
            item.setPickupDelay(NEVER_PICKUP);
            return item;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 from 到 to 画一条抛物线附魔粒子（拱高 {@link #PARABOLA_HEIGHT}）。 */
    private static void drawParabola(World world, Location from, Location to) {
        if (ENCHANT_PARTICLE == null) return;
        try {
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double dz = to.getZ() - from.getZ();
            for (int i = 0; i <= PARABOLA_STEPS; i++) {
                double t = (double) i / PARABOLA_STEPS;
                double x = from.getX() + dx * t;
                double z = from.getZ() + dz * t;
                double y = from.getY() + dy * t
                        + PARABOLA_HEIGHT * Math.sin(Math.PI * t);
                world.spawnParticle(ENCHANT_PARTICLE, new Location(world, x, y, z), 2);
            }
        } catch (Throwable ignored) {
            // 粒子失败不影响主流程
        }
    }

    /** 对半径内所有玩家播放音效。 */
    private static void playSoundNearby(World world, Location loc,
                                        String sound, float volume, float pitch, double radius) {
        try {
            for (Player p : world.getPlayers()) {
                try {
                    if (p.getLocation().distance(loc) <= radius) {
                        p.playSound(loc, sound, volume, pitch);
                    }
                } catch (Throwable ignored) {
                    // 跨世界或坐标异常，跳过该玩家
                }
            }
        } catch (Throwable ignored) {
            // 无玩家在线
        }
    }

    /** 方块位置 key。 */
    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ';' + loc.getBlockX() + ';'
                + loc.getBlockY() + ';' + loc.getBlockZ();
    }

    // ------------------------------------------------------------------
    // 动画
    // ------------------------------------------------------------------

    /** 一段仪式动画的可取消句柄。 */
    private static final class Animation {
        final World world;
        final Location coreLoc;
        final Location centerLoc;
        final List<Entity> spawned = new ArrayList<>();
        final List<Location> active = new ArrayList<>();
        BukkitTask soundTask;
        BukkitTask particleTask;
        BukkitTask fallbackTask;
        volatile boolean cancelled;
        boolean finished;

        Animation(World world, Location coreLoc, Location centerLoc) {
            this.world = world;
            this.coreLoc = coreLoc;
            this.centerLoc = centerLoc;
        }

        /** 收尾：停掉全部任务、清除全部实体、播收束音效。幂等。 */
        synchronized void finish() {
            if (finished) return;
            finished = true;
            cancelled = true;
            cancel(soundTask);
            cancel(particleTask);
            cancel(fallbackTask);
            for (Entity e : spawned) {
                if (e != null && !e.isDead()) {
                    try {
                        e.remove();
                    } catch (Throwable ignored) {
                        // 已移除
                    }
                }
            }
            spawned.clear();
            active.clear();
            playSoundNearby(world, coreLoc, "block.lava.pop", 1.0f, 1.0f, SOUND_RADIUS);
        }

        private static void cancel(BukkitTask task) {
            if (task == null) return;
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
    }

    /** 启动一段动画。 */
    private static Animation startAnimation(Location coreLoc, CraftingOperation op) {
        World world = coreLoc.getWorld();
        Animation anim = new Animation(world, coreLoc,
                coreLoc.clone().add(0.5, CENTER_HEIGHT, 0.5));

        ItemStack[] ingredients = safeArray(op == null ? null : op.getIngredients());
        ItemStack[] results = safeArray(op == null ? null : op.getResults());

        List<Location> dispenserLocs = findDispensers(world, coreLoc, DISPENSER_RADIUS);
        int count = Math.min(ingredients.length, dispenserLocs.size());

        // 兜底时长：优先用配方总时长（刻），拿不到就按配料数量推。
        // 原脚本这里对 getTotalTicks() 又乘了一次 interval，那是因为 RSC 返回的
        // 是「秒」；Slimefun 的 CraftingOperation 返回的就是刻，重复乘 20 会把兜底
        // 拖到配方时长的 20 倍，故此处不再缩放。
        int totalTicks = 0;
        try {
            if (op != null) totalTicks = op.getTotalTicks();
        } catch (Throwable ignored) {
            // 用推算值
        }
        if (totalTicks <= 0) {
            totalTicks = Math.max(count, 1) * INTERVAL;
        }
        final int fallbackDelay = totalTicks + FALLBACK_EXTRA_TICKS;

        // 配料：第 i 份延迟 i*20 刻掉在对应发射器上方 1 格
        for (int i = 0; i < count; i++) {
            final int index = i;
            NativePluginHelper.runLater(() -> {
                if (anim.cancelled) return;
                try {
                    Location dLoc = dispenserLocs.get(index);
                    Location matLoc = new Location(world,
                            dLoc.getX() + 0.5, dLoc.getY() + 1.0, dLoc.getZ() + 0.5);
                    Item item = spawnMaterialDrop(world, matLoc, ingredients[index]);
                    if (item != null) {
                        anim.spawned.add(item);
                        anim.active.add(matLoc);
                    }
                } catch (Throwable ignored) {
                    // 单份配料失败不影响其余
                }
            }, (long) Math.floor(index * (double) INTERVAL));
        }

        // 产物展示：核心上方，多个产物按 0.4 格横向居中错开
        NativePluginHelper.runLater(() -> {
            if (anim.cancelled) return;
            try {
                for (int i = 0; i < results.length; i++) {
                    double off = results.length > 1
                            ? (i - (results.length - 1) / 2.0) * RESULT_SPACING
                            : 0;
                    ItemDisplay item = spawnProjection(
                            world, anim.centerLoc.clone().add(off, 0, 0), results[i]);
                    if (item != null) anim.spawned.add(item);
                }
            } catch (Throwable ignored) {
                // 展示失败不影响主流程
            }
        }, 0L);

        // 循环音效
        anim.soundTask = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> {
                    if (anim.cancelled) return;
                    playSoundNearby(world, coreLoc, "entity.enderman.teleport",
                            1.0f, 1.0f, SOUND_RADIUS);
                    playSoundNearby(world, coreLoc, "entity.allay.hurt",
                            1.0f, 1.0f, SOUND_RADIUS);
                }, 0L, INTERVAL);

        // 抛物线粒子
        anim.particleTask = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> {
                    if (anim.cancelled) return;
                    for (Location from : new ArrayList<>(anim.active)) {
                        try {
                            drawParabola(world, from, anim.centerLoc);
                        } catch (Throwable ignored) {
                            // 单条曲线失败跳过
                        }
                    }
                }, 0L, PARTICLE_PERIOD);

        // 兜底：机器停转时由观察者收尾，这里只防「观察者先被清掉」的漏网
        anim.fallbackTask = NativePluginHelper.runLater(anim::finish, fallbackDelay);

        return anim;
    }

    /** 结束并移除某核心的动画。 */
    private static void finishAnimation(String key) {
        Animation anim = ANIMATIONS.remove(key);
        if (anim != null) {
            try {
                anim.finish();
            } catch (Throwable ignored) {
                // 收尾失败不阻断
            }
        }
    }

    /** 为某核心挂一个观察者（已存在则直接返回）。 */
    private static void ensureWatcher(Location coreLoc) {
        String key = locKey(coreLoc);
        if (WATCHERS.containsKey(key)) return;

        World world = coreLoc.getWorld();

        // 原脚本在每个观察者启动时做一次 RSC 展示物清理。
        // 本插件不存在 RSC 生成的发射器展示实体，且机器自身不会生成，
        // 因此这里不做任何删除（详见类注释第 2 条）。

        final boolean[] wasActive = {false};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> {
                    try {
                        Block block = coreLoc.getBlock();

                        // 区块卸载或核心被拆 → 收尾并自我注销
                        if (!block.getChunk().isLoaded() || !isCoreBlock(coreLoc)) {
                            finishAnimation(key);
                            cancelWatcher(key);
                            return;
                        }

                        CraftingOperation op = operationAt(coreLoc);
                        boolean activeNow = op != null;

                        if (activeNow && !wasActive[0]) {
                            int progress = 0;
                            try {
                                int total = op.getTotalTicks();
                                int current = op.getProgress();
                                if (total > 0) {
                                    progress = (int) Math.floor(current / (double) total * 100);
                                }
                            } catch (Throwable ignored) {
                                // 保持 0，视作刚起步
                            }
                            // 只在刚开始（进度 ≤10%）时起动画，避免中途右键补挂出突兀动画
                            if (progress <= 10) {
                                finishAnimation(key);
                                ANIMATIONS.put(key, startAnimation(coreLoc, op));
                            }
                        } else if (!activeNow && wasActive[0]) {
                            finishAnimation(key);
                        }
                        wasActive[0] = activeNow;
                    } catch (Throwable ignored) {
                        // 观察者本身绝不抛异常
                    }
                }, 0L, WATCH_PERIOD);

        WATCHERS.put(key, task);
    }

    /** 取消并移除某核心的观察者。 */
    private static void cancelWatcher(String key) {
        BukkitTask task = WATCHERS.remove(key);
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {
            // 已结束
        }
    }

    /** 安全取数组：null → 空数组，并剔除空元素。 */
    private static ItemStack[] safeArray(ItemStack[] src) {
        if (src == null || src.length == 0) return new ItemStack[0];
        List<ItemStack> list = new ArrayList<>(src.length);
        for (ItemStack s : src) {
            if (s != null && !s.getType().isAir()) list.add(s);
        }
        return list.toArray(new ItemStack[0]);
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new InteractListener());
    }

    /**
     * 右键派发：在 MONITOR 优先级上旁听（不取消事件、不干扰机器自身的交互处理）。
     */
    private static final class InteractListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInteract(PlayerInteractEvent event) {
            try {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
                Block block = event.getClickedBlock();
                if (block == null) return;

                Location coreLoc;
                if (isCoreBlock(block.getLocation())) {
                    coreLoc = block.getLocation();
                } else {
                    coreLoc = findCoreNear(block.getLocation(), CORE_FIND_RADIUS);
                }
                if (coreLoc != null) ensureWatcher(coreLoc);
            } catch (Throwable ignored) {
                // 视觉脚本绝不因异常影响玩家交互
            }
        }
    }

    /** 供调试/卸载使用：停掉全部观察者与动画。 */
    public static void shutdown() {
        for (String key : new ArrayList<>(WATCHERS.keySet())) {
            cancelWatcher(key);
        }
        for (String key : new ArrayList<>(ANIMATIONS.keySet())) {
            finishAnimation(key);
        }
    }
}
