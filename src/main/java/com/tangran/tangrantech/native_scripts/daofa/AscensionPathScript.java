package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 「道法/升维之路」的原生实现（对应 {@code scripts/道法/升维之路.js}）。
 *
 * <p><b>入口是物品组按钮，不是物品</b>：{@code groups.yml} 里 {@code Tr_65} 声明了
 * {@code type: button} + {@code actions: ["script 道法/升维之路"]}，
 * 由 {@link com.tangran.tangrantech.groups.TTButtonGroup} 在点击时回调本类的
 * {@code onButtonGroupClick}。（同时也保留 {@code onUse}，以兼容把它挂到物品上的情况。）</p>
 *
 * <p>界面是一张 54 格的「升维阶梯」：9 个阶段各占一格（自下而上），每格正下方铺无名字金块
 * 当作台阶实体；右上角第 8 格是「下一步」，点击时检查当前阶段对应的任务是否已完成
 * （读 {@code addon_configs/糖然科技/任务/<玩家名>.json} 的 {@code completed} 数组），
 * 完成则把 {@code ascensionStage} 推进 1 并在阶梯上盖一枚玩家头颅。</p>
 */
public final class AscensionPathScript implements NativeLifecycleScript {

    /** 任务存档目录（与「道法/任务」共用同一份文件）。 */
    private static final String TASK_DIR = "addon_configs/糖然科技/任务";

    /** 界面标题（含 {@code {#RRGGBB}} 自定义色与 {@code &} 传统色）。 */
    private static final String GUI_TITLE = "{#1e3a8a}&l升{#0ea5e9}&l维{#67e8f9}&l之{#e0f7fa}&l路";

    private static final int NEXT_SLOT = 8;
    private static final int CLOSE_SLOT = 49;
    private static final int MENU_SIZE = 54;

    /** 自定义十六进制颜色占位：{@code {#RRGGBB}}。 */
    private static final Pattern HEX_TOKEN = Pattern.compile("\\{#([0-9a-fA-F]{6})\\}");

    /** 一个升维阶段：对应一个任务 id、阶梯名与任务显示名。 */
    private record Stage(String taskId, String name, String taskName) {}

    private static final Stage[] STAGES = {
            new Stage("first_element", "升维阶梯·其一", "初识元素"),
            new Stage("open_storage", "升维阶梯·其二", "开辟储物空间"),
            new Stage("merit_limitless", "升维阶梯·其三", "功德无量"),
            new Stage("nine_flames", "升维阶梯·其四", "掌握九种异火"),
            new Stage("pill_emperor", "升维阶梯·其五", "成就丹帝"),
            new Stage("sugar_emperor", "升维阶梯·其六", "成就糖帝"),
            new Stage("crystal_tycoon", "升维阶梯·其七", "神晶大亨"),
            new Stage("final_hour", "升维阶梯·其八", "终焉之刻?"),
            new Stage("sugar_heart", "升维阶梯·其九", "获取糖心"),
    };

    /** 阶梯的视觉位置与材质。 */
    private record StageVisual(int slot, String material, String name) {}

    private static final StageVisual[] STAGE_VISUALS = {
            new StageVisual(53, "SMOOTH_QUARTZ_STAIRS", "升维阶梯·其一"),
            new StageVisual(43, "SMOOTH_QUARTZ_STAIRS", "升维阶梯·其二"),
            new StageVisual(33, "SMOOTH_QUARTZ_STAIRS", "升维阶梯·其三"),
            new StageVisual(23, "SMOOTH_QUARTZ_STAIRS", "升维阶梯·其四"),
            new StageVisual(13, "SMOOTH_QUARTZ_STAIRS", "升维阶梯·其五"),
            new StageVisual(12, "SMOOTH_QUARTZ", "升维阶梯·其六"),
            new StageVisual(11, "SMOOTH_QUARTZ", "升维阶梯·其七"),
            new StageVisual(10, "SMOOTH_QUARTZ", "升维阶梯·其八"),
            new StageVisual(0, "FIREWORK_ROCKET", "升维阶梯·其九"),
    };

    /** 当前打开着的本界面实例。 */
    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();

    @Override
    public Object invoke(String fn, Object... args) {
        switch (fn) {
            case "onUse":
                if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                    Action action = event.getAction();
                    if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                        openAscensionMenu(event.getPlayer());
                    }
                }
                return Boolean.FALSE;
            case "onButtonGroupClick":
                // 原 RSC 传入 (player, slot, clickedItem, clickAction, mode)；这里只用 player
                if (args.length > 0 && args[0] instanceof Player player) {
                    openAscensionMenu(player);
                    return Boolean.TRUE;
                }
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 文本 / 物品构建
    // ------------------------------------------------------------------

    /**
     * 把 {@code {#RRGGBB}} 转成原版十六进制色码 {@code §x§R§R§G§G§B§B}，
     * 再把 {@code &} 转成 {@code §}。
     */
    static String toLegacy(String text) {
        if (text == null) return "";
        Matcher m = HEX_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (int i = 0; i < hex.length(); i++) {
                rep.append('§').append(hex.charAt(i));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    /** 带名字的染料玻璃板之类的简单物品。 */
    private static ItemStack named(String name, String materialName) {
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 无名字金块（台阶实体）。 */
    private static ItemStack unnamedGoldBlock() {
        ItemStack it = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy("§0"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static net.kyori.adventure.text.Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    private static File taskFile(String playerName) {
        File dir = new File(TT.plugin.getDataFolder(), TASK_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            TT.log("任务存档目录创建失败: " + dir.getAbsolutePath());
        }
        return new File(dir, playerName + ".json");
    }

    /** 读任务存档；缺失或损坏时返回「未完成任何任务、阶段 0」的默认值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTaskData(String playerName) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("completed", new ArrayList<Object>());
        def.put("ascensionStage", 0);
        try {
            File file = taskFile(playerName);
            if (!file.exists()) return def;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> data = Json.parseObject(text);
            if (!(data.get("completed") instanceof List)) data.put("completed", new ArrayList<Object>());
            if (data.get("ascensionStage") == null) data.put("ascensionStage", 0);
            return data;
        } catch (Throwable t) {
            return def;
        }
    }

    private static void saveTaskData(String playerName, Map<String, Object> data) {
        try {
            File file = taskFile(playerName);
            Files.write(file.toPath(),
                    Collections.singletonList(Json.writePretty(data)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            TT.log("任务存档写入失败 " + playerName + ": " + e);
        }
    }

    /** 某任务是否已完成。 */
    private static boolean hasCompleted(Map<String, Object> data, String taskId) {
        Object c = data.get("completed");
        if (!(c instanceof List<?> list)) return false;
        for (Object o : list) {
            if (taskId.equals(String.valueOf(o))) return true;
        }
        return false;
    }

    /** 当前已达成的阶段序号（0 起）。 */
    private static int getStage(String playerName) {
        Object v = loadTaskData(playerName).get("ascensionStage");
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    /** 写入阶段序号（保留文件里的其它字段）。 */
    private static void setStage(String playerName, int stage) {
        Map<String, Object> data = loadTaskData(playerName);
        data.put("ascensionStage", stage);
        saveTaskData(playerName, data);
    }

    // ------------------------------------------------------------------
    // 界面搭建
    // ------------------------------------------------------------------

    /** 玩家头颅：升维之证。 */
    private static ItemStack buildPlayerHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                skull.setOwningPlayer(player);
            } catch (Throwable ignored) {
                // 设置皮肤失败不影响其余
            }
        }
        if (meta != null) {
            meta.displayName(legacy("§d§l" + player.getName() + " §7的升维之证"));
            if (getStage(player.getName()) >= STAGE_VISUALS.length) {
                // 走到第九阶（获取糖心）→ 头颅显示「您已升维！」
                meta.lore(List.of(legacy("§a§l您已升维！")));
            } else {
                meta.lore(List.of(legacy("§7你已完成当前阶段，")));
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    /** 单个阶梯的展示物。 */
    private static ItemStack buildStageVisual(int index) {
        if (index < 0 || index >= STAGE_VISUALS.length) return null;
        StageVisual v = STAGE_VISUALS[index];
        Material mat = Material.matchMaterial(v.material());
        if (mat == null) return null;

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        meta.displayName(legacy("§f§l" + v.name()));
        if (index < STAGES.length) {
            meta.lore(List.of(legacy("§7对应任务：§e" + STAGES[index].taskName())));
        } else {
            meta.lore(List.of(legacy("§7后续阶段待开启")));
        }

        if ("FIREWORK_ROCKET".equals(v.material())) {
            // 先隐藏附加信息提示
            try {
                meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            } catch (Throwable ignored) {
                // 该标记不存在时跳过
            }
            it.setItemMeta(meta);
            // 再彻底移除烟花组件，避免客户端仍显示「飞行时间: 1」。
            // 原脚本走 CraftItemStack + net.minecraft DataComponents 的 NMS 路径，
            // 那要求编译期能访问服务端内部类；Paper 1.21 提供了等价的公开 API，
            // 因此这里改用 ItemStack#unsetData。
            try {
                it.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.FIREWORKS);
            } catch (Throwable ignored) {
                // 组件不存在或 API 不可用时忽略（HIDE_ADDITIONAL_TOOLTIP 已兜底）
            }
            return it;
        }

        it.setItemMeta(meta);
        return it;
    }

    /** 构建整张界面。 */
    private static Inventory buildAscensionMenu(Player player) {
        String playerName = player.getName();
        int stage = getStage(playerName);

        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy(toLegacy(GUI_TITLE)));

        // 所有阶梯正下方铺无名字金块
        Set<Integer> gold = new HashSet<>();
        for (StageVisual v : STAGE_VISUALS) {
            int below = v.slot() + 9;
            while (below <= MENU_SIZE - 1) {
                gold.add(below);
                below += 9;
            }
        }
        for (int slot : gold) {
            inv.setItem(slot, unnamedGoldBlock());
        }

        // 九个阶梯
        for (int i = 0; i < STAGE_VISUALS.length; i++) {
            ItemStack visual = buildStageVisual(i);
            if (visual != null) inv.setItem(STAGE_VISUALS[i].slot(), visual);
        }

        // 「下一步」
        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = next.getItemMeta();
        if (nextMeta != null) {
            nextMeta.displayName(legacy("§a§l下一步"));
            if (stage < STAGES.length) {
                nextMeta.lore(List.of(legacy("§7检测当前阶段任务：§e" + STAGES[stage].taskName())));
            } else {
                nextMeta.lore(List.of(legacy("§7所有阶段已完成")));
            }
            next.setItemMeta(nextMeta);
        }
        inv.setItem(NEXT_SLOT, next);

        // 已完成阶段上方的玩家头颅
        if (stage > 0) {
            if (stage >= STAGE_VISUALS.length) {
                // 阶段九完成：烟花位置直接替换成玩家头颅
                inv.setItem(STAGE_VISUALS[STAGE_VISUALS.length - 1].slot(), buildPlayerHead(player));
            } else {
                int completedIndex = Math.min(stage - 1, STAGE_VISUALS.length - 1);
                int aboveSlot = STAGE_VISUALS[completedIndex].slot() - 9;
                if (aboveSlot >= 0) inv.setItem(aboveSlot, buildPlayerHead(player));
            }
        }

        inv.setItem(CLOSE_SLOT, named("§c§l关闭菜单", "BARRIER"));
        return inv;
    }

    /** 打开界面。 */
    private static void openAscensionMenu(Player player) {
        try {
            Inventory inv = buildAscensionMenu(player);
            ACTIVE.add(inv);
            player.playSound(player.getLocation(), "block.amethyst_block.chime", 0.8f, 1.4f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("升维之路打开界面失败: " + t);
        }
    }

    /** 原地刷新：把新界面的 54 格内容覆盖到旧界面上（保持窗口不重开）。 */
    private static void refreshAscensionMenu(Inventory inv, Player player) {
        Inventory fresh = buildAscensionMenu(player);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, fresh.getItem(i));
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new MenuListener());
    }

    /** 菜单交互。 */
    private static final class MenuListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;

                Inventory clicked = event.getClickedInventory();
                int slot = event.getRawSlot();

                // 点到自己背包：只拦 shift 快速移动，允许正常整理背包
                if (clicked != top) {
                    if (event.isShiftClick()) event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);

                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                    return;
                }
                if (slot != NEXT_SLOT) return;

                String playerName = player.getName();
                int stage = getStage(playerName);

                if (stage >= STAGES.length) {
                    player.sendMessage("§a你已经走完了当前所有升维阶段。");
                    return;
                }

                Stage cur = STAGES[stage];
                if (!hasCompleted(loadTaskData(playerName), cur.taskId())) {
                    player.sendMessage("§c当前阶段需要先完成：§e" + cur.taskName());
                    play(player, "entity.villager.no", 1.0f, 1.0f);
                    return;
                }

                int newStage = stage + 1;
                setStage(playerName, newStage);
                refreshAscensionMenu(top, player);
                player.updateInventory();

                if (newStage >= STAGES.length) {
                    player.sendMessage("§a恭喜！你已经完成当前所有升维之路。");
                    play(player, "ui.toast.challenge_complete", 1.0f, 1.0f);
                } else {
                    player.sendMessage("§a你已踏入下一阶段：§e" + STAGES[newStage].taskName());
                    play(player, "entity.player.levelup", 0.8f, 1.2f);
                }
            } catch (Throwable t) {
                TT.log("升维之路点击处理出错: " + t);
            }
        }

        /** 关闭时把该界面从活跃集合里摘掉。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                ACTIVE.remove(event.getInventory());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 禁止拖拽物品。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top != null && ACTIVE.contains(top)) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static void play(Player player, String key, float volume, float pitch) {
            try {
                player.playSound(player.getLocation(), key, volume, pitch);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        ACTIVE.clear();
    }
}
