package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「糖然的种植园」：糖然牌基础物质（{@code TR_SUGAR}）的获取方式。
 *
 * <h2>界面（4 行 36 格，对照截图）</h2>
 * <pre>
 *  第 0 行  0  1  2  3  4  5  6  7  8      ← 背景板（长出第二/第三根甘蔗的位置）
 *  第 1 行  9 10 11 12 13 14 15 16 17      ← 背景板（长出第二根甘蔗的位置）
 *  第 2 行 18 19 20 21 22 23 24 25 26      ← 种子甘蔗（泥土上方一格，玩家永远动不了）
 *  第 3 行 27 28 29 30 31 32 33 34 35      ← 泥土 / 水桶
 * </pre>
 * 有泥土（也就能种甘蔗）的列是 {@code 0,2,3,5,6,8}；水桶在 {@code 1,4,7} 三列的最下一行。
 *
 * <h2>生长</h2>
 * <p>每列最多 3 根：种子甘蔗（第 2 行）→ 第 1 行 → 第 0 行。某一列还没长满时，
 * 随机取 15~30 秒计时，到点就在「当前最上面那根甘蔗的上方」补一根；补完若上方还有空位，
 * 会重新取一次 15~30 秒继续长（对应需求里的「17 → 9 → 0」逐格向上）。</p>
 *
 * <h2>交互</h2>
 * <ul>
 *   <li>点击水桶：所有正在计时的列各减 1 秒（不足 1 秒直接长成）——即「加速甘蔗生长」；</li>
 *   <li>点击长成的甘蔗（第 0/1 行）：该列高度减 1、给玩家 1 个糖然牌基础物质，并重置该列计时；</li>
 *   <li>种子甘蔗（第 2 行）、泥土、背景板：一律不可操作。</li>
 * </ul>
 *
 * <p><b>计时只在界面开着时推进</b>（关掉界面即暂停，避免后台空转）：每个玩家一个 1 秒任务，开界面时启动、关界面或切走时自动取消。状态按玩家保存在内存里，重开能看到残留进度；服务器重启后重置。</p>
 */
public final class SugarPlantation {

    /** 4 行 36 格。 */
    private static final int SIZE = 36;
    private static final String TITLE = "§a糖然的种植园";

    /** 有泥土、能种甘蔗的列。 */
    private static final int[] CANE_COLUMNS = {0, 2, 3, 5, 6, 8};
    /** 水桶所在列（都在最下一行）。 */
    private static final int[] BUCKET_COLUMNS = {1, 4, 7};

    /** 每列最多几根甘蔗：种子 + 上面两格。 */
    private static final int MAX_CANES = 3;
    /** 生长计时区间（秒）。 */
    private static final int MIN_SECONDS = 15;
    private static final int MAX_SECONDS = 30;

    /** 收获产物：糖然牌基础物质（TR_SUGAR，也就是原来 54 格菜单发的那颗糖）。 */
    private static final String REWARD_ID = "TR_SUGAR";

    private static final int ROW_TOP = 0;
    private static final int ROW_MID = 9;
    private static final int ROW_SEED = 18;
    private static final int ROW_SOIL = 27;

    private static final Random RANDOM = new Random();

    /** 玩家 → 他的种植园状态。 */
    private static final Map<UUID, Plot> PLOTS = new ConcurrentHashMap<>();

    private static volatile Listener listener;

    private SugarPlantation() {}

    // ==================================================================
    // 打开 / 渲染
    // ==================================================================

    /** 打开（或切回）该玩家的种植园。 */
    public static void open(Player player) {
        if (player == null) return;
        try {
            Plot plot = PLOTS.computeIfAbsent(player.getUniqueId(), k -> new Plot());
            Holder holder = new Holder(player.getUniqueId());
            plot.holder = holder;
            Inventory inv = Bukkit.createInventory(holder, SIZE, TITLE);
            holder.inventory = inv;
            render(inv, plot);
            player.openInventory(inv);
            // 只在界面开着的时候计时：打开时起一个「每玩家一个」的 1 秒任务，关界面即取消
            startPlotTask(player, plot);
        } catch (Throwable t) {
            player.sendMessage("§c无法打开糖然的种植园，请联系管理。");
            TT.log("种植园打开失败: " + t);
        }
    }

    /** 按状态铺界面：先整屏铺背景，再盖泥土/水桶，最后画甘蔗。 */
    private static void render(Inventory inv, Plot plot) {
        ItemStack pane = plain(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, pane.clone());

        for (int c : CANE_COLUMNS) inv.setItem(ROW_SOIL + c, plain(Material.DIRT));
        for (int c : BUCKET_COLUMNS) inv.setItem(ROW_SOIL + c, bucketItem());

        for (int i = 0; i < CANE_COLUMNS.length; i++) {
            int c = CANE_COLUMNS[i];
            inv.setItem(ROW_SEED + c, seedCaneItem());
            for (int k = 1; k < plot.canes[i]; k++) {
                inv.setItem((k == 1 ? ROW_MID : ROW_TOP) + c, grownCaneItem());
            }
        }
    }

    // ==================================================================
    // 生长计时（仅在界面打开期间推进）
    // ==================================================================

    /** 为该玩家起一个每秒一次的生长任务（开界面时调用；重复打开会先取消旧的）。 */
    private static void startPlotTask(Player player, Plot plot) {
        plot.cancelTask();
        try {
            plot.task = Bukkit.getScheduler().runTaskTimer(TT.plugin,
                    () -> tickPlot(player, plot), 20L, 20L);
        } catch (Throwable t) {
            TT.log("种植园计时启动失败: " + t);
        }
    }

    /** 每秒推进一次该玩家的种植园；界面已关闭/切走就自动收工。 */
    private static void tickPlot(Player player, Plot plot) {
        try {
            if (!player.isOnline()) {
                plot.cancelTask();
                return;
            }
            Inventory top = Views.top(player.getOpenInventory());
            if (top == null || top.getHolder() != plot.holder) {
                plot.cancelTask();     // 关界面即停：计时跟着暂停，省掉后台空转
                return;
            }
            boolean changed = false;
            for (int i = 0; i < CANE_COLUMNS.length; i++) {
                changed |= tickColumn(plot, i);
            }
            if (changed) render(top, plot);
        } catch (Throwable t) {
            TT.log("种植园计时出错: " + t);
            plot.cancelTask();
        }
    }

    /** 单列推进：没在计时的取 15~30 秒，正在计时的减 1 秒，到点补一根。返回是否长出了新甘蔗。 */
    private static boolean tickColumn(Plot plot, int i) {
        if (plot.canes[i] >= MAX_CANES) {
            plot.pending[i] = -1;
            return false;
        }
        if (plot.pending[i] < 0) {
            plot.pending[i] = MIN_SECONDS + RANDOM.nextInt(MAX_SECONDS - MIN_SECONDS + 1);
            return false;
        }
        plot.pending[i]--;
        if (plot.pending[i] > 0) return false;
        plot.canes[i]++;
        plot.pending[i] = -1;      // 上方还有空位的话，下一次 tick 重新取随机时长
        return true;
    }

    /** 水桶加速：所有正在计时的列各减 1 秒；减到 0 立即长成。 */
    private static void accelerate(Player player, Plot plot) {
        boolean grew = false;
        for (int i = 0; i < CANE_COLUMNS.length; i++) {
            if (plot.pending[i] < 0) continue;
            plot.pending[i]--;
            if (plot.pending[i] <= 0) {
                if (plot.canes[i] < MAX_CANES) {
                    plot.canes[i]++;
                    grew = true;
                }
                plot.pending[i] = -1;
            }
        }
        playSound(player, "entity.generic.splash");
        if (grew) playSound(player, "block.sweet_berry_bush.pick_berries");
    }

    /** 收获：该列高度减 1 并给 1 个糖然牌基础物质，重置该列计时。 */
    private static void harvest(Player player, Plot plot, int index) {
        if (plot.canes[index] <= 1) return;      // 只剩种子甘蔗，收不了
        plot.canes[index]--;
        plot.pending[index] = -1;
        giveReward(player);
        playSound(player, "block.sweet_berry_bush.pick_berries");
    }

    /** 发一个糖然牌基础物质：背包优先，放不下才掉在脚下。 */
    private static void giveReward(Player player) {
        SlimefunItem item = SlimefunItem.getById(REWARD_ID);
        if (item == null) {
            player.sendMessage("§c物品id无效，请联系管理");
            return;
        }
        ItemStack reward = item.getItem().clone();
        reward.setAmount(1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(reward);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        player.sendMessage("§a+1 §b§l糖然牌基础物质");
    }

    private static void playSound(Player player, String key) {
        try {
            player.getWorld().playSound(player.getLocation(), key, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 音效资源缺失时静默
        }
    }

    // ==================================================================
    // 物品
    // ==================================================================

    private static ItemStack plain(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§r");
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack bucketItem() {
        return named(Material.WATER_BUCKET, "§a点击加速甘蔗生长",
                List.of("§7快干活！！"));
    }

    private static ItemStack seedCaneItem() {
        return named(Material.SUGAR_CANE, "§2甘蔗根",
                List.of("§7拔了我吃什么？？"));
    }

    private static ItemStack grownCaneItem() {
        return named(Material.SUGAR_CANE, "§a点击获得糖然牌基础物质",
                List.of("§7点击收获 §f1 §7个糖然牌基础物质"));
    }

    private static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(new ArrayList<>(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ==================================================================
    // 点击
    // ==================================================================

    /** 本种植园的监听器（由 {@link TangranBasicMatterScript#listeners()} 一并注册）。 */
    public static Listener listener() {
        if (listener == null) {
            synchronized (SugarPlantation.class) {
                if (listener == null) listener = new ClickHandler();
            }
        }
        return listener;
    }

    /** 菜单容器标记：点击时靠它认「这是种植园」，不依赖任何全局状态。 */
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ClickHandler implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                if (!(event.getWhoClicked() instanceof Player player)) return;
                if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
                if (!holder.owner.equals(player.getUniqueId())) return;

                // shift 点击一律拦下：否则玩家自己背包里的物品会被塞进种植园
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                    return;
                }
                int slot = event.getRawSlot();
                if (slot < 0 || slot >= SIZE) return;     // 点自己背包：放行，允许整理
                event.setCancelled(true);

                Plot plot = PLOTS.get(player.getUniqueId());
                if (plot == null) return;

                if (isBucketSlot(slot)) {
                    accelerate(player, plot);
                    render(event.getView().getTopInventory(), plot);
                    return;
                }
                int caneIndex = grownCaneIndexAt(slot, plot);
                if (caneIndex >= 0) {
                    harvest(player, plot, caneIndex);
                    render(event.getView().getTopInventory(), plot);
                }
                // 其余（种子甘蔗 / 泥土 / 背景板）已取消，什么都不做
            } catch (Throwable t) {
                TT.log("种植园点击错误: " + t);
            }
        }
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
                Plot plot = PLOTS.get(holder.owner);
                if (plot != null) plot.cancelTask();   // 关界面 → 停止计时
            } catch (Throwable t) {
                TT.log("种植园关闭处理错误: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
                for (int slot : event.getRawSlots()) {
                    if (slot >= 0 && slot < SIZE) {
                        event.setCancelled(true);   // 不允许把物品拖进种植园
                        return;
                    }
                }
            } catch (Throwable t) {
                TT.log("种植园拖拽处理错误: " + t);
            }
        }
    }

    private static boolean isBucketSlot(int slot) {
        if (slot < ROW_SOIL || slot >= ROW_SOIL + 9) return false;
        for (int c : BUCKET_COLUMNS) {
            if (slot == ROW_SOIL + c) return true;
        }
        return false;
    }

    /** 该格若是「已长成的甘蔗」返回对应列下标，否则 -1。 */
    private static int grownCaneIndexAt(int slot, Plot plot) {
        for (int i = 0; i < CANE_COLUMNS.length; i++) {
            int c = CANE_COLUMNS[i];
            if (slot == ROW_MID + c && plot.canes[i] >= 2) return i;
            if (slot == ROW_TOP + c && plot.canes[i] >= 3) return i;
        }
        return -1;
    }

    /** 单个玩家的种植园状态。 */
    private static final class Plot {
        /** 各列甘蔗根数（至少 1 = 种子甘蔗）。 */
        final int[] canes = new int[CANE_COLUMNS.length];
        /** 各列剩余生长秒数；-1 表示当前没有计时（已长满或还没开始）。 */
        final int[] pending = new int[CANE_COLUMNS.length];
        Holder holder;
        /** 界面打开期间的每秒任务；关界面即取消。 */
        BukkitTask task;

        void cancelTask() {
            if (task != null) {
                try {
                    task.cancel();
                } catch (Throwable ignored) {
                    // 已结束
                }
                task = null;
            }
        }

        Plot() {
            java.util.Arrays.fill(canes, 1);
            java.util.Arrays.fill(pending, -1);
        }
    }
}
