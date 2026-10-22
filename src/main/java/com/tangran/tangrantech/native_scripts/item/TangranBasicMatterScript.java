package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * 「糖然牌基础物质获取」的原生实现（对应 {@code scripts/糖然牌基础物质获取.js}）。
 *
 * <p>行为与原脚本逐条一致：
 * <ul>
 *   <li>右键持有该物品 → 打开 54 格菜单，标题 {@code §b§l糖然牌基础物质获取}；</li>
 *   <li>上下边框（0-8 / 45-53）填充绿色玻璃板；</li>
 *   <li>80% 概率在内容区（9-44）随机一格放入「糖然牌基础物质」；</li>
 *   <li>点击该物品 → 给玩家掉落一个 {@code TR_SUGAR} 并关闭菜单。</li>
 * </ul>
 */
public final class TangranBasicMatterScript implements NativeLifecycleScript {

    private static final String MAIN_TITLE = "§b§l糖然牌基础物质获取";
    private static final String ITEM_NAME = "§b§l糖然牌基础物质";
    /** 菜单格数（内容区 9-44 + 上下边框 0-8 / 45-53）。 */
    private static final int MENU_SIZE = 54;
    private static final String REWARD_ID = "TR_SUGAR";
    private static final Random RANDOM = new Random();

    private static final int[] BORDER_SLOTS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        45, 46, 47, 48, 49, 50, 51, 52, 53
    };

    /** 当前打开着本菜单的玩家。 */
    private static final Set<UUID> OPEN_PLAYERS = ConcurrentHashMap.newKeySet();

    /**
     * 玩家 → 本次菜单里「糖」所在的格子（{@code -1} 表示这一轮没给糖）。
     *
     * <p>菜单每次打开时糖出现在内容区随机一格，原来靠「点击物品的显示名 == ITEM_NAME」
     * 来认它是糖 —— 但显示名能被铁砧伪造：玩家把任意物品改名后，在自己背包里点它
     * 就能白拿奖励。这里改成记下糖的格子、点击时按格子判身份（格子不可伪造），
     * 与 Minecraft GUI 的通行写法一致。</p>
     */
    private static final Map<UUID, Integer> SUGAR_SLOT = new ConcurrentHashMap<>();

    /** 单例监听器（与原脚本挂在 RSC 插件对象上的写法对应）。 */
    private static volatile Listener listener;

    @Override
    public Object invoke(String fn, Object... args) {
        switch (fn) {
            case "onUse":
                if (args.length > 0 && args[0] instanceof org.bukkit.event.player.PlayerInteractEvent e) {
                    openMain(e.getPlayer());
                }
                return false;
            case "onButtonGroupClick":
                if (args.length > 0 && args[0] instanceof Player p) {
                    openMain(p);
                }
                return true;
            default:
                return null;
        }
    }

    /** 打开主菜单。 */
    public static void openMain(@Nullable Player player) {
        if (player == null) return;
        try {
            Inventory inv = buildMainMenu(player);
            player.openInventory(inv);
            OPEN_PLAYERS.add(player.getUniqueId());
            ensureListener();
        } catch (Throwable t) {
            player.sendMessage("§c无法打开基础物质菜单，请联系管理。");
            TT.log("基础物质菜单打开失败: " + t);
        }
    }

    /**
     * 取菜单监听器单例（只建实例，不自主注册）。
     *
     * <p>注册统一由 {@code listeners() → NativeLifecycle} 在启动链完成 ——
     * 曾经的懒注册是「listeners() 从未被调用」时代的兜底，框架接线修复后
     * 若还在这里 registerEvents，一次点击会被两个 MenuHandler 各处理一遍。</p>
     */
    private static Listener ensureListener() {
        if (listener == null) {
            synchronized (TangranBasicMatterScript.class) {
                if (listener == null) {
                    listener = new MenuHandler();
                }
            }
        }
        return listener;
    }

    private static Inventory buildMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, MAIN_TITLE);
        ItemStack pane = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        for (int s : BORDER_SLOTS) inv.setItem(s, pane.clone());
        // 80% 概率在内容区（9-44）随机一格给出糖；记下这一格，点击时按格子认身份
        int slot = -1;
        if (RANDOM.nextDouble() < 0.8) {
            slot = 9 + RANDOM.nextInt(36);
            inv.setItem(slot, sugarItem());
        }
        if (p != null) SUGAR_SLOT.put(p.getUniqueId(), slot);
        return inv;
    }

    private static ItemStack sugarItem() {
        ItemStack item = new ItemStack(Material.SUGAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ITEM_NAME);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void giveReward(Player p) {
        SlimefunItem sfItem = SlimefunItem.getById(REWARD_ID);
        if (sfItem == null) {
            p.sendMessage("§c物品id无效，请联系管理");
            return;
        }
        ItemStack reward = sfItem.getItem().clone();
        p.getWorld().dropItemNaturally(p.getLocation(), reward);
        p.sendMessage("§f有点粘手的糖果?");
    }

    @Override
    public Iterable<Listener> listeners() {
        // 必须与 ensureListener() 共用同一个实例：框架修复前本方法无人调用（靠懒注册兜底），
        // 修复后若返回新实例，一次点击会被两个 MenuHandler 各处理一遍（奖励发两份）。
        return List.of(ensureListener());
    }

    /** 合并处理菜单点击与关闭（原脚本用同一个 listener 注册两个事件）。 */
    private static final class MenuHandler implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                if (!(event.getWhoClicked() instanceof Player p)) return;
                if (!OPEN_PLAYERS.contains(p.getUniqueId())) return;
                if (!MAIN_TITLE.equals(title(event))) return;
                int slot = event.getRawSlot();
                // 只处理「点在我们自己的顶层菜单里」的点击（rawSlot 落在 0..53），
                // 点到玩家自己背包时放行，让他能正常整理。
                // 原来这里不判点击来源、只比显示名：玩家把任意物品在铁砧上改成 ITEM_NAME 后，
                // 在自己背包里点它就能白拿奖励。
                //
                // 注意：千万别写成 getClickedInventory() != getTopInventory()。Bukkit 的
                // Inventory 只是底层容器（NMS IInventory）的<b>包装对象</b>，两次调用不保证
                // 拿到同一个引用 —— 那样守卫会恒真，连点菜单里的糖都会被吞掉。
                if (slot < 0 || slot >= MENU_SIZE) return;
                event.setCancelled(true);
                if (slot != SUGAR_SLOT.getOrDefault(p.getUniqueId(), -1)) return;
                giveReward(p);
                p.closeInventory();
            } catch (Throwable t) {
                TT.log("基础物质菜单点击错误: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                if (!(event.getPlayer() instanceof Player p)) return;
                OPEN_PLAYERS.remove(p.getUniqueId());
                SUGAR_SLOT.remove(p.getUniqueId());
            } catch (Throwable t) {
                TT.log("关闭菜单处理错误: " + t);
            }
        }
    }

    /** 统一取视图标题（走 Views 以规避 1.21 起 InventoryView 由抽象类改接口的问题）。 */
    private static String title(InventoryClickEvent event) {
        return com.tangran.tangrantech.util.Views.title(event.getView());
    }
}
