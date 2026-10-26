package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 「粗糖水获取」的原生实现（对应 2026-10-24 重写后的 {@code scripts/粗糖水获取.js}）。
 *
 * <p>玩法：把「糖渣」({@code TR_TZ}) 与「万用瓶」({@code TR_BOTTLE}) 丢进水里，
 * 按 4 糖渣 + 1 万用瓶 = 1 瓶粗糖水 立即结算，产物在第一个万用瓶的位置掉落。</p>
 *
 * <h2>为什么是「实扫世界」而不是「累加计数」</h2>
 * <p>旧实现（以及我们上一版移植）在 {@code PlayerDropItemEvent} 里按玩家累加
 * {@code tzCount/bottleCount}，并保留丢出瞬间的物品实体引用。这条链路有两个致命问题：</p>
 * <ol>
 *   <li><b>物品实体会被合并</b>：连续丢同类物品时原版会把它们并进一个实体、删掉被并的那个，
 *       我们记录的 Entry 指向的实体失效 → 消耗时全部跳过 → 计数够但「一件也扣不掉」，
 *       表现为<b>材料充足却永远合成不出来</b>；</li>
 *   <li>计数与真实世界脱节：掉进水里飘走/被别人捡走/被水冲走的物品仍然留在计数里。</li>
 * </ol>
 * <p>重写后的脚本改为：丢出后延时 1 刻（等实体登记/合并完成），<b>实扫</b>落点周围
 * {@link #SCAN_RADIUS} 格内的掉落物，现算现扣；能凑几份就合几份。这样合并、飘移、
 * 分多次丢都不影响判定。</p>
 *
 * <h2>材料必须在水中</h2>
 * <p>按需求只统计<b>泡在液体里</b>的掉落物（{@link #inWater}）：糖渣和万用瓶都进了水
 * 才会结算，丢在岸上不算。原脚本判的是「玩家附近有水」，玩家站在岸上/高处往水里丢时
 * 会被误拒，这里改为判物品自身位置。</p>
 */
public final class RawSugarWaterScript implements NativeLifecycleScript {

    private static final String TZ_ID = "TR_TZ";
    private static final String BOTTLE_ID = "TR_BOTTLE";
    private static final String CTS_ID = "TR_CTS";

    /** 兑换比：4 个糖渣 + 1 个万用瓶 → 1 瓶粗糖水。 */
    private static final int TZ_PER_BOTTLE = 4;
    /** 统计掉落物的搜索半径（格），以最后丢出的物品的落点为中心。 */
    private static final double SCAN_RADIUS = 4.0d;
    /** 单次触发最多合成的份数（防异常情况下死循环）。 */
    private static final int CRAFT_GUARD = 64;
    /**
     * 入水判定重试时刻（刻）：丢出瞬间物品还在玩家手边/半空，等它落进水里再结算。
     * 每一刻都重扫一遍世界，先到的先结算；结算会消耗材料，后面的重试自然不再出货。
     */
    private static final long[] WATER_PROBE_DELAYS = {1L, 4L, 8L, 16L};

    /**
     * 玩家 → 最近一次「已排好探测」的丢出位置。
     *
     * <p>连续快速丢出（按住 Ctrl+Q 或连点）会产生大量 {@code PlayerDropItemEvent}，
     * 若每件都排 4 次世界扫描就是无谓开销。落点仍在探测半径内的后续丢出会被
     * 已排队的重试一并扫到，因此直接跳过、不重复排队。</p>
     */
    private static final java.util.Map<java.util.UUID, Location> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /**
     * 玩家丢出物品的入口。
     *
     * <p>只认糖渣/万用瓶；真正的入水判定与水中的批量结算延后到
     * {@link #WATER_PROBE_DELAYS} 里的时刻执行。</p>
     */
    public static void handleDrop(Player player, Item dropped) {
        if (dropped == null || !player.isOnline()) return;
        ItemStack item = dropped.getItemStack();
        if (item == null) return;
        if (!isTrItem(item, TZ_ID) && !isTrItem(item, BOTTLE_ID)) return;

        Location fallback = dropped.getLocation().clone();
        java.util.UUID uuid = player.getUniqueId();
        Location previous = PENDING.put(uuid, fallback);
        if (previous != null && previous.getWorld() == fallback.getWorld()
                && previous.distanceSquared(fallback) <= SCAN_RADIUS * SCAN_RADIUS) {
            return;     // 附近的这次丢出会被已排队的探测扫到
        }

        for (int i = 0; i < WATER_PROBE_DELAYS.length; i++) {
            long delay = WATER_PROBE_DELAYS[i];
            boolean last = i == WATER_PROBE_DELAYS.length - 1;
            NativePluginHelper.runLater(() -> {
                try {
                    if (last) PENDING.remove(uuid);
                    if (!player.isOnline()) return;
                    // 实体被合并/捡走时退回丢出瞬间的位置判断，避免整条链路空转
                    Location center = dropped.isValid() ? dropped.getLocation() : fallback;
                    if (!inWater(center)) return;
                    tryGenerate(player, center);
                } catch (Throwable t) {
                    TT.log("粗糖水获取处理出错: " + t);
                }
            }, delay);
        }
    }

    /**
     * 扫描 {@code center} 周围水中的糖渣/万用瓶，按 4:1 结算并掉落产物。
     *
     * <p>循环结算到凑不出下一份为止：材料多时一次丢出即可全部合成
     * （对齐原脚本 {@code Tr_tryGenerate} 的 {@code crafted += pairs} 语义）。</p>
     *
     * @return 是否有产出
     */
    public static boolean tryGenerate(Player player, Location center) {
        if (player == null || center == null) return false;
        World world = center.getWorld();
        if (world == null) return false;
        SlimefunItem cts = SlimefunItem.getById(CTS_ID);
        if (cts == null) return false;

        int crafted = 0;
        for (int guard = 0; guard < CRAFT_GUARD; guard++) {
            List<Item> tz = new ArrayList<>();
            List<Item> bottle = new ArrayList<>();
            scanDrops(center, tz, bottle);

            int pairs = Math.min(totalAmount(tz) / TZ_PER_BOTTLE, totalAmount(bottle));
            if (pairs <= 0) break;

            List<Consumed> tzUsed = planConsumption(tz, pairs * TZ_PER_BOTTLE);
            List<Consumed> bottleUsed = planConsumption(bottle, pairs);
            if (tzUsed == null || bottleUsed == null) break;

            Location spawnLoc = bottleUsed.get(0).entity.getLocation();
            ItemStack reward = cts.getItem().clone();
            reward.setAmount(pairs);
            world.dropItemNaturally(spawnLoc, reward);

            applyConsumption(tzUsed);
            applyConsumption(bottleUsed);
            crafted += pairs;
        }

        if (crafted <= 0) return false;
        playFishingSound(player, center);
        return true;
    }

    /** 实扫：只收「泡在液体里」的掉落物——糖渣与万用瓶都进了水才参与结算。 */
    private static void scanDrops(Location center, List<Item> tz, List<Item> bottle) {
        World world = center.getWorld();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(center, SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
            if (!(entity instanceof Item item)) continue;
            if (!item.isValid() || item.isDead()) continue;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir()) continue;
            if (!inWater(item.getLocation())) continue;
            if (isTrItem(stack, TZ_ID)) tz.add(item);
            else if (isTrItem(stack, BOTTLE_ID)) bottle.add(item);
        }
    }

    /**
     * 该位置是否泡在液体里。
     *
     * <p>先看物品所在格；物品在水面上下浮动时可能刚好越过水块上沿，再补看下方一格。</p>
     */
    public static boolean inWater(Location loc) {
        if (loc == null) return false;
        World world = loc.getWorld();
        if (world == null) return false;
        Block block = loc.getBlock();
        if (block.isLiquid()) return true;
        return block.getRelative(0, -1, 0).isLiquid();
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

    private static int totalAmount(List<Item> items) {
        int sum = 0;
        for (Item item : items) {
            if (item == null || !item.isValid()) continue;
            ItemStack stack = item.getItemStack();
            if (stack != null) sum += stack.getAmount();
        }
        return sum;
    }

    /** 规划消耗：数量不够则整体返回 null，不做部分消耗。 */
    private static List<Consumed> planConsumption(List<Item> entities, int required) {
        List<Consumed> out = new ArrayList<>();
        int remain = required;
        for (Item entity : entities) {
            if (remain <= 0) break;
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

    /** 播放入水音效。 */
    private static void playFishingSound(Player player, Location loc) {
        try {
            World world = loc.getWorld();
            if (world != null) {
                world.playSound(loc, "entity.fishing_bobber.splash", 1.0f, 1.0f);
                return;
            }
        } catch (Throwable ignored) {
            // 音效资源缺失时静默
        }
        try {
            player.playSound(player.getLocation(), "entity.fishing_bobber.splash", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 静默
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new DropListener());
    }

    /** 只监听丢出事件：入水判定与结算都在延时任务里做，不需要移动/切物品事件。 */
    private static final class DropListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            try {
                handleDrop(event.getPlayer(), event.getItemDrop());
            } catch (Throwable t) {
                TT.log("粗糖水获取处理出错: " + t);
            }
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
}
