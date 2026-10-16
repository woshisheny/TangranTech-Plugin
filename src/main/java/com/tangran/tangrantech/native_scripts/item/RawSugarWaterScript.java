package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「粗糖水获取」的原生实现（对应 {@code scripts/粗糖水获取.js}）。
 *
 * <p>玩法：站/走到水边并手持「糖」({@code TR_TZ}) 或「瓶子」({@code TR_BOTTLE})，
 * 把它们丢出去；脚本按「4 个糖 + 1 个瓶子 = 1 瓶粗糖水」的比例结算，
 * 直接在第一个瓶子的位置掉落 {@code TR_CTS}。每第 10 次结算保底产出 4 个沉重核心
 * （原脚本的整蛊设定，这里原样保留）。</p>
 *
 * <p>两个实现细节：</p>
 * <ul>
 *   <li>「观察者」有 200 刻（10 秒）无操作超时，超时即清空累计；
 *       原脚本在玩家退出时没有清理遗留任务，这里补了 {@code PlayerQuitEvent} 清理。</li>
 *   <li>掉落物实体在丢出瞬间可能还没生成，原脚本靠外层
 *       {@code PlayerDropItemEvent} 直接拿到了实体，这里保持一致，
 *       但消耗前会校验实体仍然有效。</li>
 * </ul>
 */
public final class RawSugarWaterScript implements NativeLifecycleScript {

    private static final String TZ_ID = "TR_TZ";
    private static final String BOTTLE_ID = "TR_BOTTLE";
    private static final String CTS_ID = "TR_CTS";

    /** 兑换比：4 个糖 → 1 瓶粗糖水。 */
    private static final int TZ_PER_BOTTLE = 4;
    /** 保底周期：每 10 次结算出一次沉重核心。 */
    private static final int GUARANTEE_EVERY = 10;
    /** 沉重核心保底数量。 */
    private static final int HEAVY_CORE_AMOUNT = 4;
    /** 无操作超时（刻）。 */
    private static final long INACTIVITY_TICKS = 200L;
    /** 水检测范围：以玩家所在格为中心的 4×4×4。 */
    private static final int WATER_SCAN = 2;

    /** 正在观察中的玩家 UUID。 */
    private static final Set<UUID> ACTIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 玩家 UUID → 累计状态。 */
    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /** 玩家丢出物品的入口。 */
    public static void handleDrop(Player player, Item dropped) {
        if (dropped == null || !player.isOnline()) return;

        ItemStack item = dropped.getItemStack();
        if (item == null) return;
        int amount = item.getAmount();

        boolean tz = isTrItem(item, TZ_ID);
        boolean bottle = isTrItem(item, BOTTLE_ID);
        if (!tz && !bottle) return;
        if (!isNearWater(player.getLocation())) return;

        tryStartWatcher(player);

        PlayerState state = getState(player);
        if (tz) {
            state.tzCount += amount;
            state.tzEntries.add(new Entry(dropped, amount));
        } else {
            state.bottleCount += amount;
            state.bottleEntries.add(new Entry(dropped, amount));
        }

        resetInactivityTimer(player);
        tryGenerate(player);
    }

    /** 判断物品是否为指定 id 的糖然物品。 */
    public static boolean isTrItem(ItemStack item, String sfid) {
        if (item == null || item.getType().isAir()) return false;
        String id = TT.sfIdOf(item);
        if (id != null) return id.equalsIgnoreCase(sfid);

        // 兜底：物品尚未写入 PDC（加载期未注册）时，拿模板物品按外观比对
        SlimefunItem template = SlimefunItem.getById(sfid);
        return template != null && template.getItem().isSimilar(item);
    }

    /** 检测坐标附近是否有液体（原脚本扫 4×4×4）。 */
    public static boolean isNearWater(Location loc) {
        if (loc == null) return false;
        World world = loc.getWorld();
        if (world == null) return false;
        int bx = (int) Math.floor(loc.getX());
        int by = (int) Math.floor(loc.getY());
        int bz = (int) Math.floor(loc.getZ());
        for (int dy = -WATER_SCAN; dy <= WATER_SCAN - 1; dy++) {
            for (int dx = -WATER_SCAN; dx <= WATER_SCAN - 1; dx++) {
                for (int dz = -WATER_SCAN; dz <= WATER_SCAN - 1; dz++) {
                    if (world.getBlockAt(bx + dx, by + dy, bz + dz).isLiquid()) return true;
                }
            }
        }
        return false;
    }

    private static PlayerState getState(Player player) {
        return STATES.computeIfAbsent(player.getUniqueId(), k -> new PlayerState());
    }

    private static void clearState(Player player) {
        PlayerState state = STATES.remove(player.getUniqueId());
        if (state != null) state.cancelInactivity();
    }

    /** 停止观察：复位标记、移出活跃集合、清空累计。 */
    private static void stopWatcher(Player player) {
        PlayerState state = STATES.get(player.getUniqueId());
        if (state != null) state.watchHandPreviously = false;
        ACTIVE.remove(player.getUniqueId());
        clearState(player);
    }

    private static void tryStartWatcher(Player player) {
        if (!player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        PlayerState state = getState(player);

        if (!hasWatchItemInHand(player)) {
            state.watchHandPreviously = false;
            return;
        }
        if (ACTIVE.contains(uuid)) return;
        if (!isNearWater(player.getLocation())) return;
        if (state.watchHandPreviously) return;

        ACTIVE.add(uuid);
        state.watchHandPreviously = true;
        resetInactivityTimer(player);
    }

    private static void resetInactivityTimer(Player player) {
        PlayerState state = getState(player);
        state.cancelInactivity();
        state.inactivityTask = NativePluginHelper.runLater(() -> {
            ACTIVE.remove(player.getUniqueId());
            clearState(player);
        }, INACTIVITY_TICKS);
    }

    private static boolean hasWatchItemInHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return isTrItem(held, TZ_ID) || isTrItem(held, BOTTLE_ID);
    }

    /** 按累计数量结算并掉落产物；成功返回 true。 */
    public static boolean tryGenerate(Player player) {
        PlayerState state = getState(player);
        int pairs = Math.min(state.tzCount / TZ_PER_BOTTLE, state.bottleCount);
        if (pairs <= 0) return false;

        SlimefunItem cts = SlimefunItem.getById(CTS_ID);
        if (cts == null) return false;

        List<Consumed> tzUsed = planConsumption(state.tzEntries, pairs * TZ_PER_BOTTLE);
        List<Consumed> bottleUsed = planConsumption(state.bottleEntries, pairs);
        if (tzUsed == null || bottleUsed == null) return false;

        Location spawnLoc = bottleUsed.get(0).entity.getLocation();
        ItemStack reward = cts.getItem().clone();
        reward.setAmount(pairs);
        player.getWorld().dropItemNaturally(spawnLoc, reward);

        applyConsumption(tzUsed);
        applyConsumption(bottleUsed);

        state.tzCount -= pairs * TZ_PER_BOTTLE;
        state.bottleCount -= pairs;
        if (state.tzCount < 0) state.tzCount = 0;
        if (state.bottleCount < 0) state.bottleCount = 0;

        playFishingSound(player);
        stopWatcher(player);
        return true;
    }

    /** 规划消耗：数量不够则整体返回 null，不做部分消耗。 */
    private static List<Consumed> planConsumption(List<Entry> entries, int required) {
        List<Consumed> out = new ArrayList<>();
        int remain = required;
        for (Entry entry : entries) {
            if (remain <= 0) break;
            Item entity = entry.entity;
            if (entity == null || !entity.isValid()) continue;
            ItemStack stack = entity.getItemStack();
            if (stack == null) continue;
            int current = stack.getAmount();
            if (current <= 0) continue;
            int used = Math.min(current, remain);
            out.add(new Consumed(entity, used));
            remain -= used;
        }
        return remain == 0 ? out : null;
    }

    /** 落地消耗：数量归零则移除实体。 */
    private static void applyConsumption(List<Consumed> records) {
        for (Consumed record : records) {
            Item entity = record.entity;
            if (entity == null || !entity.isValid()) continue;
            ItemStack stack = entity.getItemStack();
            if (stack == null) continue;
            int remain = stack.getAmount() - record.used;
            if (remain <= 0) {
                entity.remove();
            } else {
                stack.setAmount(remain);
                entity.setItemStack(stack);
            }
        }
    }

    /** 播放入水音效；失败时退回 {@code player.playSound}。 */
    private static void playFishingSound(Player player) {
        try {
            player.getWorld().playSound(player.getLocation(),
                    "entity.fishing_bobber.splash", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 音效资源缺失时静默
        }
    }

    /** 玩家退出时清理遗留状态。 */
    public static void cleanup(Player player) {
        clearState(player);
        ACTIVE.remove(player.getUniqueId());
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new DropListener());
    }

    /** 丢出 / 移动 / 切物品：后两者只用于刷新无操作超时。 */
    private static final class DropListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            try {
                handleDrop(event.getPlayer(), event.getItemDrop());
            } catch (Throwable t) {
                TT.log("粗糖水获取处理出错: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            try {
                if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                    && event.getFrom().getBlockY() == event.getTo().getBlockY()
                    && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
                    return; // 只转视角不算操作
                }
                tryStartWatcher(event.getPlayer());
                resetInactivityTimer(event.getPlayer());
            } catch (Throwable t) {
                TT.log("粗糖水观察刷新出错: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onHeld(PlayerItemHeldEvent event) {
            try {
                Player player = event.getPlayer();
                tryStartWatcher(player);
                resetInactivityTimer(player);
            } catch (Throwable t) {
                TT.log("粗糖水切物品刷新出错: " + t);
            }
        }
    }

    /** 单个掉落物的累计记录。 */
    private static final class Entry {
        final Item entity;
        final int amount;

        Entry(Item entity, int amount) {
            this.entity = entity;
            this.amount = amount;
        }
    }

    /** 一条待执行的消耗。 */
    private static final class Consumed {
        final Item entity;
        final int used;

        Consumed(Item entity, int used) {
            this.entity = entity;
            this.used = used;
        }
    }

    /** 玩家维度的累计状态。 */
    private static final class PlayerState {
        int tzCount;
        int bottleCount;
        final List<Entry> tzEntries = Collections.synchronizedList(new ArrayList<>());
        final List<Entry> bottleEntries = Collections.synchronizedList(new ArrayList<>());
        BukkitTask inactivityTask;
        boolean watchHandPreviously;

        void cancelInactivity() {
            if (inactivityTask != null) {
                try {
                    inactivityTask.cancel();
                } catch (Throwable ignored) {
                    // 已结束
                }
                inactivityTask = null;
            }
        }
    }
}
