package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.plugin.Plugin;
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
    private static final String REWARD_ID = "TR_SUGAR";
    private static final Random RANDOM = new Random();

    private static final int[] BORDER_SLOTS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        45, 46, 47, 48, 49, 50, 51, 52, 53
    };

    /** 当前打开着本菜单的玩家。 */
    private static final Set<UUID> OPEN_PLAYERS = ConcurrentHashMap.newKeySet();

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
            Inventory inv = buildMainMenu();
            player.openInventory(inv);
            OPEN_PLAYERS.add(player.getUniqueId());
            ensureListener();
        } catch (Throwable t) {
            player.sendMessage("§c无法打开基础物质菜单，请联系管理。");
            TT.log("基础物质菜单打开失败: " + t);
        }
    }

    private static void ensureListener() {
        if (listener == null) {
            synchronized (TangranBasicMatterScript.class) {
                if (listener == null) {
                    Listener l = new MenuHandler();
                    Plugin p = TT.plugin;
                    if (p != null) Bukkit.getPluginManager().registerEvents(l, p);
                    listener = l;
                }
            }
        }
    }

    private static Inventory buildMainMenu() {
        Inventory inv = Bukkit.createInventory(null, 54, MAIN_TITLE);
        ItemStack pane = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        for (int s : BORDER_SLOTS) inv.setItem(s, pane.clone());
        // 80% 概率在内容区（9-44）随机一格给出糖
        if (RANDOM.nextDouble() < 0.8) {
            int slot = 9 + RANDOM.nextInt(36);
            inv.setItem(slot, sugarItem());
        }
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
        List<Listener> out = new ArrayList<>();
        out.add(new MenuHandler());
        return out;
    }

    /** 合并处理菜单点击与关闭（原脚本用同一个 listener 注册两个事件）。 */
    private static final class MenuHandler implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                if (!(event.getWhoClicked() instanceof Player p)) return;
                if (!OPEN_PLAYERS.contains(p.getUniqueId())) return;
                if (!MAIN_TITLE.equals(title(event))) return;
                event.setCancelled(true);
                ItemStack item = event.getCurrentItem();
                if (item == null || item.getType() == Material.AIR) return;
                ItemMeta meta = item.getItemMeta();
                String dn = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : "";
                if (ITEM_NAME.equals(dn)) {
                    giveReward(p);
                    p.closeInventory();
                }
            } catch (Throwable t) {
                TT.log("基础物质菜单点击错误: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                if (!(event.getPlayer() instanceof Player p)) return;
                OPEN_PLAYERS.remove(p.getUniqueId());
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
