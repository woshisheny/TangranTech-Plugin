package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「宝库模拟器」的原生实现（对应 {@code scripts/宝库模拟器.js}）。
 *
 * <p>机器放下后启动一个 20 刻周期的任务：维护一个计数器，每累计 10 次产出一次
 * 4 个「沉重核心」保底，其余各次从原版试炼密室宝库战利品表
 * （{@code chests/trial_chambers/reward}、{@code .../reward_ominous}）里随机抽一件，
 * 放入 8 个输出槽之一（优先堆叠同类）。</p>
 *
 * <p>原脚本用反射（{@code server.getClass().getMethod('getLootTable', ...)}）来绕开
 * 编译期绑定；这里直接调 {@link org.bukkit.Server#getLootTable}，API 等价且编译期可校验。</p>
 */
public final class TreasureSimulatorScript implements NativeLifecycleScript {

    private static final String MACHINE_ID = "Tr_bkmnq";

    private static final int[] OUTPUT_SLOTS = {11, 15, 20, 24, 29, 33, 38, 42};
    /** 保底产出数量。 */
    private static final int AMOUNT = 4;
    /** 保底周期。 */
    private static final int GUARANTEE_EVERY = 10;

    /** 原版宝库战利品表：普通宝库 + 不祥宝库。 */
    private static final String[] LOOT_TABLE_KEYS = {
        "chests/trial_chambers/reward",
        "chests/trial_chambers/reward_ominous"
    };

    private static final Random RANDOM = new Random();

    /** 机器坐标 → 运行中的周期任务。 */
    private static final Map<String, BukkitTask> TASKS = new HashMap<>();
    /** 机器坐标 → 累计次数。 */
    private static final Map<String, Integer> COUNTER = new HashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }

    /** 启动机器的周期任务（对应原脚本的 onPlace）。 */
    public static synchronized void startMachine(Location loc) {
        String key = locKey(loc);
        if (TASKS.containsKey(key)) return;
        Location frozen = loc.clone();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> tryWork(frozen), 0L, 20L);
        TASKS.put(key, task);
    }

    /** 停止机器的周期任务（对应原脚本的 onBreak）。 */
    public static synchronized void stopMachine(Location loc) {
        String key = locKey(loc);
        BukkitTask task = TASKS.remove(key);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
        COUNTER.remove(key);
    }

    /** 一个周期的产出逻辑。 */
    private static void tryWork(Location loc) {
        try {
            BlockMenu menu = StorageCacheUtils.getMenu(loc);
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

    /** 放置 / 破坏：对应原脚本的 onPlace / onBreak。 */
    private static final class MachineListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            try {
                Block b = event.getBlock();
                String id = me.mrCookieSlime.Slimefun.api.BlockStorage.checkID(b);
                if (id != null && MACHINE_ID.equalsIgnoreCase(id)) {
                    startMachine(b.getLocation());
                }
            } catch (Throwable t) {
                TT.log("宝库模拟器放置处理出错: " + t);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            try {
                Block b = event.getBlock();
                String id = me.mrCookieSlime.Slimefun.api.BlockStorage.checkID(b);
                if (id != null && MACHINE_ID.equalsIgnoreCase(id)) {
                    stopMachine(b.getLocation());
                }
            } catch (Throwable t) {
                TT.log("宝库模拟器破坏处理出错: " + t);
            }
        }
    }
}
