package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ScriptMachineItem;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

/**
 * 「宝库模拟器」的原生实现（对应 {@code scripts/宝库模拟器.js}）。
 *
 * <p>机器每 20 刻（1 秒）推进一次：维护一个计数器，每累计 10 次产出一次
 * 4 个「沉重核心」保底，其余各次从原版试炼密室宝库战利品表
 * （{@code chests/trial_chambers/reward}、{@code .../reward_ominous}）里随机抽一件，
 * 放入 8 个输出槽之一（优先堆叠同类）。</p>
 *
 * <p>原脚本用反射（{@code server.getClass().getMethod('getLootTable', ...)}）来绕开
 * 编译期绑定；这里直接调 {@link org.bukkit.Server#getLootTable}，API 等价且编译期可校验。</p>
 *
 * <p><b>驱动方式（对齐「自动太虚枢机」，修「重启停工」）</b>：原脚本在 {@code onPlace}
 * 里为每台机器起一个 {@code runTaskTimer(0, 20)} 任务，服务器重启后任务全部丢失、
 * 已放置的机器再也不会产出，且原脚本没有任何补挂逻辑。本实现改走
 * {@link ScriptMachineItem} 的每刻 {@code tick} 回调，用一个按坐标的计数器把节奏压到
 * 「第 1、21、41… 刻各处理一次」——与原 {@code delay=0, period=20} 的触发时刻一致，
 * 同时天然免疫重启；代价是区块不在模拟距离内时机器暂停（与原版熔炉等机器一致，
 * 原任务版反而会在无人时继续产出）。另挂一个 {@link BlockBreakEvent} 监听，
 * 只为清理计数器状态，避免长跑服内存缓慢增长。</p>
 */
public final class TreasureSimulatorScript implements NativeLifecycleScript {

    private static final String MACHINE_ID = "Tr_bkmnq";

    private static final int[] OUTPUT_SLOTS = {11, 15, 20, 24, 29, 33, 38, 42};
    /** 保底产出数量。 */
    private static final int AMOUNT = 4;
    /** 保底周期。 */
    private static final int GUARANTEE_EVERY = 10;
    /** 处理周期（刻），与原脚本 runTaskTimer(0, 20) 一致。 */
    private static final int PERIOD = 20;

    /** 原版宝库战利品表：普通宝库 + 不祥宝库。 */
    private static final String[] LOOT_TABLE_KEYS = {
        "chests/trial_chambers/reward",
        "chests/trial_chambers/reward_ominous"
    };

    private static final Random RANDOM = new Random();

    /** 机器坐标 → 已过去的刻数（把每刻 tick 压成 20 刻一次）。 */
    private static final Map<String, Integer> TICKS = new ConcurrentHashMap<>();
    /** 机器坐标 → 累计产出次数（到 10 出保底）。 */
    private static final Map<String, Integer> COUNTER = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"tick".equals(fn) || args.length == 0) return null;
        if (!(args[0] instanceof ScriptMachineItem.TickInfo info)) return null;

        try {
            Location loc = info.block().getLocation();
            String key = locKey(loc);

            int age = TICKS.merge(key, 1, Integer::sum);
            // 原脚本是 runTaskTimer(delay=0, period=20)：首次在下一刻执行，此后每 20 刻一次。
            // 计数器从 1 开始，因此「模 20 余 1」正好对齐原触发时刻（放置后的第一刻即首次产出）。
            if (age % PERIOD != 1) return null;

            tryWork(loc, info.menu());
        } catch (Throwable t) {
            TT.log("宝库模拟器 tick 出错: " + t);
        }
        return null;
    }

    /** 一个周期的产出逻辑。 */
    private static void tryWork(Location loc, BlockMenu menu) {
        try {
            if (menu == null) return;

            String key = locKey(loc);
            int counter = COUNTER.getOrDefault(key, 0) + 1;

            ItemStack stack = (counter >= GUARANTEE_EVERY)
                    ? heavyCore()
                    : rollVanillaVaultLoot(loc);
            if (stack == null) return;

            if (!addToOutput(menu, stack)) return;

            COUNTER.put(key, counter >= GUARANTEE_EVERY ? 0 : counter);
        } catch (Throwable t) {
            TT.log("宝库模拟器工作出错: " + t);
        }
    }

    /** 沉重核心保底产出。 */
    private static ItemStack heavyCore() {
        ItemStack stack = new ItemStack(Material.HEAVY_CORE);
        stack.setAmount(AMOUNT);
        return stack;
    }

    /**
     * 从原版宝库战利品表随机抽一件。
     *
     * <p>随机起点 + 顺序轮询，保证两张表被均匀使用；任一张表解析失败就换下一张。</p>
     */
    private static ItemStack rollVanillaVaultLoot(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        int start = RANDOM.nextInt(LOOT_TABLE_KEYS.length);
        for (int i = 0; i < LOOT_TABLE_KEYS.length; i++) {
            int idx = (start + i) % LOOT_TABLE_KEYS.length;
            try {
                LootTable table = Bukkit.getServer().getLootTable(
                        NamespacedKey.minecraft(LOOT_TABLE_KEYS[idx]));
                if (table == null) continue;

                LootContext context = new LootContext.Builder(loc.clone()).build();
                if (context == null) continue;

                java.util.Collection<ItemStack> loot =
                        table.populateLoot(new Random(), context);
                if (loot == null || loot.isEmpty()) continue;

                List<ItemStack> items = new ArrayList<>(loot);
                return items.get(RANDOM.nextInt(items.size())).clone();
            } catch (Throwable ignored) {
                // 换下一张表
            }
        }
        return null;
    }

    /** 放入输出槽：优先堆叠到同类，其次占空槽；都放不下返回 false。 */
    private static boolean addToOutput(BlockMenu menu, ItemStack stack) {
        if (stack == null) return false;

        for (int slot : OUTPUT_SLOTS) {
            ItemStack old = menu.getItemInSlot(slot);
            if (old != null && !old.getType().isAir()
                    && old.isSimilar(stack)
                    && old.getAmount() + stack.getAmount() <= old.getMaxStackSize()) {
                old.setAmount(old.getAmount() + stack.getAmount());
                menu.replaceExistingItem(slot, old);
                return true;
            }
        }

        for (int slot : OUTPUT_SLOTS) {
            ItemStack old = menu.getItemInSlot(slot);
            if (old == null || old.getType().isAir()) {
                menu.replaceExistingItem(slot, stack.clone());
                return true;
            }
        }
        return false;
    }

    /** 坐标键：world:x:y:z。 */
    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ':' + loc.getBlockX() + ':' + loc.getBlockY() + ':' + loc.getBlockZ();
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new MachineListener());
    }

    /** 破坏：清理计数器（tick 随方块消失自动停止，这里只收内存）。 */
    private static final class MachineListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            try {
                Block b = event.getBlock();
                String id = me.mrCookieSlime.Slimefun.api.BlockStorage.checkID(b);
                if (id != null && MACHINE_ID.equalsIgnoreCase(id)) {
                    String key = locKey(b.getLocation());
                    TICKS.remove(key);
                    COUNTER.remove(key);
                }
            } catch (Throwable t) {
                TT.log("宝库模拟器破坏处理出错: " + t);
            }
        }
    }
}
