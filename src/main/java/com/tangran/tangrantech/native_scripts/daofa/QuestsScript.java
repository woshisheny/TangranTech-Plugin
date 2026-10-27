package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentBridge;

/**
 * 「道法/任务」的原生实现（对应 {@code scripts/道法/任务.js}）。中章任务的接取与结算界面。
 *
 * <p>界面布局模仿异火选择：外围彩色玻璃、中间 4×7 任务格、底部灰色控制栏。
 * 第九个任务（终焉之刻）同时是 BOSS 挑战的入口——它有三副面孔：
 * 未提交材料时是「未完成」的书；提交过后变「开始挑战中章BOSS！」的下界合金剑；
 * 通关阶段 1 后变「读取存档」的望远镜（可重复挑战任意阶段）；
 * 通关阶段 3（糖然本体）后直接显示「已完成」。</p>
 *
 * <p><b>任务表</b>（顺序即解锁顺序，必须前一任务完成才能接下一个）：</p>
 * <ol>
 *   <li>初识元素 —— 上交 512 个九色琉璃珠</li>
 *   <li>开辟储物空间 —— 拥有储物空间存档即可（不消耗）</li>
 *   <li>功德无量 —— 上交 204800 功德</li>
 *   <li>掌握九种异火 —— 拥有全部 9 种异火（不消耗）</li>
 *   <li>成就丹帝 —— 丹方权限 exp ≥ 2000（不消耗）</li>
 *   <li>成就糖帝 —— 境界 realm ≥ 5（不消耗）</li>
 *   <li>神晶大亨 —— 上交 256 个糖神神晶</li>
 *   <li>终焉之刻 —— 上交沌/萃/材各 128，换取 BOSS 挑战权限</li>
 *   <li>获取糖心 —— 上交 1 个糖心，奖励「中章装备全套」</li>
 * </ol>
 *
 * <p><b>与 BOSS战 / 装备 的耦合</b>统一走 {@link BossBattleBridge} 与
 * {@link EquipmentBridge}（原 JS 版是 {@code globalThis} 全局函数直连 + 缺函数时
 * {@code eval} 现场补加载）。进度存档 {@code addon_configs/糖然科技/BOSS战/<玩家名>.json}
 * 的 {@code cleared} 数组是直接读的，因此即使 BOSS战 尚未原生化，
 * 「通关三阶段后任务八显示已完成」也能正确工作。</p>
 *
 * <p>另有一个防漏机制：菜单里的物品（「阶段 N」「关闭菜单」「读取存档」「任务其…」等）
 * 一旦被玩家顺走就会一直留在背包里，因此上线时与打开菜单时都会清扫一遍
 * （{@link #cleanLeakedMenuItems}）。这是原脚本为解决实际反馈的刷物/残留问题加的。
 * <b>认物方式已从「按显示名匹配」改为 PDC 标记</b>（{@link #MENU_ITEM_KEY}）：
 * 早年按名字匹配时，玩家任何被铁砧改名为「关闭菜单」「阶段 N」的私人物品，
 * 都会在登录/开菜单时被 {@code inv.remove} 当菜单残留误删 —— 显示名可伪造，
 * 不能用来判物品身份（项目硬约定③）。</p>
 */
public final class QuestsScript implements NativeLifecycleScript {

    private static final String GUI_TITLE = Colors.c("{#c084fc}&l中{#a855f7}&l章{#8b5cf6}&l任{#6366f1}&l务");
    private static final int CLOSE_SLOT = 49;
    private static final int MENU_SIZE = 54;

    /**
     * 菜单物品的 PDC 标记。
     *
     * <p>防漏清理（{@link #cleanLeakedMenuItems}）靠它认「这是我们菜单里的物品」。
     * 早年用显示名子串匹配，会把玩家改过名的私人物品当残留误删，故改为 PDC。</p>
     */
    private static final NamespacedKey MENU_ITEM_KEY =
            new NamespacedKey(TT.plugin, "tangran_quest_menu_item");

    private static final int[] PRODUCT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };
    private static final int[] BOSS_STAGE_SLOTS = {10, 12, 14, 16};
    private static final int[] GUARDIAN_SLOTS = {10, 12, 14, 16};
    private static final int[] OUTER_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44};

    private static final String[] COLOR_GLASS = {
            "RED_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE",
            "LIGHT_BLUE_STAINED_GLASS_PANE", "BLUE_STAINED_GLASS_PANE", "PURPLE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE", "PINK_STAINED_GLASS_PANE",
    };

    /** 玩家持有的问天玉碟物品 id（功德 lore 刷新用）。 */
    private static final String CARD_ID = "TR_WTYD";

    private static final java.util.Random RANDOM = new java.util.Random();

    /** 一个任务的定义。 */
    private record TaskDef(String id, String name, List<String> desc,
                           Predicate<Player> check,
                           Predicate<Player> consume,
                           java.util.function.Function<Player, String> failMessage,
                           Predicate<Player> reward) {}

    private static final List<TaskDef> TASKS = buildTasks();

    private static List<TaskDef> buildTasks() {
        List<TaskDef> list = new ArrayList<>();

        list.add(new TaskDef("first_element", "§d§l任务其一·初识元素",
                List.of("§7上交 §b512 个九色琉璃珠"),
                p -> countItem(p, "TR_JSLLZ") >= 512,
                p -> takeItem(p, "TR_JSLLZ", 512),
                p -> "缺少九色琉璃珠 x" + Math.max(0, 512 - countItem(p, "TR_JSLLZ")),
                null));

        list.add(new TaskDef("open_storage", "§d§l任务其二·开辟储物空间",
                List.of("§7使用 §b储物空间开辟石", "§7开辟属于你的储物空间。"),
                p -> AddonPaths.docExists(new File(AddonPaths.configDir(AddonPaths.DIR_STORAGE),
                        PlayerKeys.key(p.getName()) + ".json")),
                null,
                p -> "您还未开辟储物空间",
                null));

        list.add(new TaskDef("merit_limitless", "§d§l任务其三·功德无量",
                List.of("§7上交 §6204800 功德", "§7在灵圃种植作物，",
                        "§7再通过问天玉碟兑换成功德。"),
                p -> gongde(p.getName()) >= 204800,
                p -> consumeGongde(p, 204800),
                p -> "缺少功德 x" + Math.max(0, 204800 - gongde(p.getName())),
                null));

        list.add(new TaskDef("nine_flames", "§d§l任务其四·掌握九种异火",
                List.of("§7在浮生当铺购买并掌握", "§7九种异火。"),
                p -> hasAllYanhuo(p.getName()),
                null,
                p -> "缺少异火 x" + Math.max(0, 9 - ownedYanhuo(p.getName()).size()),
                null));

        list.add(new TaskDef("pill_emperor", "§d§l任务其五·成就丹帝",
                List.of("§7在太虚琉璃炉炼制不同品质丹药，",
                        "§7提升熟练度，最终成就丹帝。"),
                p -> danfangExp(p.getName()) >= 2000,
                null,
                p -> "您还未成就丹帝",
                null));

        list.add(new TaskDef("sugar_emperor", "§d§l任务其六·成就糖帝",
                List.of("§7服用丹药突破境界，", "§7最终成就糖帝。"),
                p -> realmLevel(p.getName()) >= 5,
                null,
                p -> "您还未成就糖帝",
                null));

        list.add(new TaskDef("crystal_tycoon", "§d§l任务其七·神晶大亨",
                List.of("§7上交 §d256 个糖神神晶"),
                p -> countItem(p, "TR_TSSJ") >= 256,
                p -> takeItem(p, "TR_TSSJ", 256),
                p -> "缺少糖神神晶 x" + Math.max(0, 256 - countItem(p, "TR_TSSJ")),
                null));

        list.add(new TaskDef("final_hour", "§d§l任务其八·终焉之刻?",
                List.of("§7提交 §d128 个沌", "§7提交 §a128 个萃",
                        "§7提交 §e128 个材", "§7获得挑战中章BOSS的权限"),
                null, null, QuestsScript::finalHourMissing, null));

        list.add(new TaskDef("sugar_heart", "§d§l任务其九·获取糖心",
                List.of("§7上交 §d1 个糖心",
                        "§7领取中章装备全套（四件工具 + 破法弓 + 戮仙剑）"),
                p -> countItem(p, "Tr_tangxin") >= 1,
                p -> takeItem(p, "Tr_tangxin", 1),
                p -> "缺少糖心 x" + Math.max(0, 1 - countItem(p, "Tr_tangxin")),
                QuestsScript::giveMidChapterKit));

        return Collections.unmodifiableList(list);
    }

    // ------------------------------------------------------------------
    // 菜单状态
    // ------------------------------------------------------------------

    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();
    /** 菜单 Inventory → 类型（{@code task} / {@code boss_stage} / {@code boss_guardian}）。 */
    private static final Map<Inventory, String> MENU_TYPE = new ConcurrentHashMap<>();
    /** 护法菜单的槽位 → 护法 id。 */
    private static final Map<Inventory, Map<Integer, BossBattleBridge.Guardian>> GUARDIAN_MAP =
            new ConcurrentHashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        Action a = event.getAction();
                        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
                            // 任务书若绑定成物品，右键只该开菜单：取消原版交互防止双发
                            // （当前 yml 只从指南按钮组进入，这里是为将来的物品绑定兜底）
                            try {
                                event.setCancelled(true);
                            } catch (Throwable ignored) {
                                // 事件不可取消时忽略
                            }
                            openTaskMenu(event.getPlayer());
                        }
                    }
                    return Boolean.FALSE;
                }
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        openTaskMenu(player);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[任务] invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private static String sfId(ItemStack stack) {
        return TT.sfIdOf(stack);
    }

    /**
     * 给菜单物品打 PDC 标记，供 {@link #isOurMenuItem} 识别。
     *
     * <p>打不上标记不影响物品正常使用，只是防漏清理会漏掉它（可接受）——
     * 绝不能反过来用显示名兜底，那会误删玩家私人物品。</p>
     */
    private static void tagMenuItem(ItemStack stack) {
        try {
            if (stack == null) return;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;
            meta.getPersistentDataContainer().set(MENU_ITEM_KEY, PersistentDataType.STRING, "menu");
            stack.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 见 javadoc：允许静默失败
        }
    }

    private static ItemStack glass(String name, String material) {
        Material mat = Material.matchMaterial(material);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            it.setItemMeta(meta);
        }
        tagMenuItem(it);
        return it;
    }

    private static ItemStack randomGlass() {
        return glass("§0", COLOR_GLASS[RANDOM.nextInt(COLOR_GLASS.length)]);
    }

    /** 造一个带名字与 lore 的物品。 */
    private static ItemStack named(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (!loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(line.isEmpty() ? Component.empty() : legacy(line));
                }
                meta.lore(lore);
            }
            it.setItemMeta(meta);
        }
        tagMenuItem(it);
        return it;
    }

    // ------------------------------------------------------------------
    // 物品计数（含副手）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 背包快照：同一刻内只扫一次背包
    // ------------------------------------------------------------------

    /** 缓存所属的游戏刻。 */
    private static volatile long snapTick = Long.MIN_VALUE;

    /** 玩家 → 该刻的背包快照；跨刻自动作废。 */
    private static final Map<UUID, InvSnap> SNAP_CACHE = new ConcurrentHashMap<>();

    /**
     * 取玩家的背包快照 —— 同一刻内多次调用共用同一份数据。
     *
     * <p><b>为什么需要它</b>：任务的条件检查（{@code check}）、缺料提示
     * （{@code failMessage}）与扣除（{@code consume}）各自都要把背包从头扫一遍，
     * 一次提交下来同一个背包被扫三四遍；打开/刷新任务菜单时更是 9 个任务各扫两三遍
     * （37 次 {@code getItem} × 20+ 遍）。改成「一次扫描 → 之后所有判断与扣除
     * 都基于快照」后，一次菜单打开只有 1 次全扫。</p>
     *
     * <p><b>为什么可以整刻缓存</b>：任务的一次提交（检查 → 提示 → 扣除）全部在
     * 同一个 {@code InventoryClickEvent} 里同步跑完，中间不会有别的插件改动背包；
     * 跨刻自动作废，不会读到过期数据。</p>
     */
    private static InvSnap snapOf(Player player) {
        try {
            long t = Bukkit.getCurrentTick();
            if (t != snapTick) {
                snapTick = t;
                SNAP_CACHE.clear();
            }
            if (player == null) return InvSnap.EMPTY;
            return SNAP_CACHE.computeIfAbsent(player.getUniqueId(), k -> InvSnap.of(player));
        } catch (Throwable ignored) {
            return InvSnap.EMPTY;
        }
    }

    /** 统计背包（含副手）里该粘液物品的总数（基于快照，不再重复扫背包）。 */
    private static int countItem(Player player, String sfId) {
        return snapOf(player).count(sfId);
    }

    /**
     * 从背包（含副手）足额扣除 amount 个；<b>不足则一个都不动</b>。
     *
     * <p>修正：原来循环走完无条件 {@code return true}，少扣（或中途异常只扣了一部分）
     * 也会被当成成功放行，玩家白拿任务奖励。</p>
     */
    private static boolean takeItem(Player player, String sfId, int amount) {
        InvSnap snap = snapOf(player);
        if (snap.count(sfId) < amount) return false;
        return snap.take(sfId, amount);
    }

    /**
     * 背包（含副手）的一次性快照：37 个格子的「粘液 id + 数量」。
     *
     * <p>一次 {@code of()} 只扫一遍背包；之后的 {@link #count(String)} 与
     * {@link #take(String, int)} 全部基于这份快照完成，
     * 扣除只在最后把真正动过的格子写回背包。</p>
     */
    private static final class InvSnap {

        /** 空快照（玩家为 null 等异常场合使用）。 */
        static final InvSnap EMPTY = new InvSnap(null, new String[0], new int[0]);

        private final PlayerInventory inv;
        /** 下标 0-35 对应背包槽 0-35；下标 36 对应副手槽 40。 */
        private final String[] ids;
        private final int[] amounts;

        private InvSnap(PlayerInventory inv, String[] ids, int[] amounts) {
            this.inv = inv;
            this.ids = ids;
            this.amounts = amounts;
        }

        /** 扫一遍背包（含副手）建立快照。 */
        static InvSnap of(Player player) {
            if (player == null) return EMPTY;
            String[] ids = new String[37];
            int[] amounts = new int[37];
            try {
                PlayerInventory inv = player.getInventory();
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack == null || stack.getType().isAir()) continue;
                    ids[i] = sfId(stack);
                    amounts[i] = stack.getAmount();
                }
                ItemStack off = inv.getItem(40);
                if (off != null && !off.getType().isAir()) {
                    ids[36] = sfId(off);
                    amounts[36] = off.getAmount();
                }
                return new InvSnap(inv, ids, amounts);
            } catch (Throwable ignored) {
                return EMPTY;
            }
        }

        /** 该粘液物品在背包里的总数。 */
        int count(String want) {
            if (want == null) return 0;
            int n = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != null && ids[i].equalsIgnoreCase(want)) n += amounts[i];
            }
            return n;
        }

        /**
         * 从快照里扣掉 amount 个并写回背包。
         *
         * @return 足额扣除才返回 true；不足时返回 false 且<b>背包完全没动</b>
         */
        boolean take(String want, int amount) {
            if (inv == null || want == null || amount <= 0) return false;
            boolean[] touched = new boolean[ids.length];
            int remain = amount;
            for (int i = 0; i < ids.length && remain > 0; i++) {
                if (ids[i] == null || !ids[i].equalsIgnoreCase(want)) continue;
                int t = Math.min(amounts[i], remain);
                amounts[i] -= t;
                remain -= t;
                touched[i] = true;
            }
            if (remain > 0) return false;
            // 只把动过的格子写回背包，避免整包回写
            for (int i = 0; i < ids.length; i++) {
                if (!touched[i]) continue;
                int slot = (i == 36) ? 40 : i;
                try {
                    if (amounts[i] <= 0) {
                        inv.setItem(slot, null);
                    } else {
                        ItemStack cur = inv.getItem(slot);
                        if (cur != null) cur.setAmount(amounts[i]);
                    }
                } catch (Throwable ignored) {
                    // 单个格子写回失败不影响其它格子
                }
            }
            return true;
        }
    }

    /**
     * 一次性扣除多种材料（每种各 {@code amount} 个）：要么全扣，要么一个都不动。
     *
     * <p>做法：先把每种都数一遍，任一种不够就直接返回 false（此时背包还没动过）；
     * 够的话再逐个扣，扣除过程中任何一步返回 false 或抛异常，就把前面已经扣掉的
     * 数量按原样还回去。</p>
     *
     * <p><b>为什么需要它</b>：原来写成 {@code !take(a) || !take(b) || !take(c)} 的
     * 短路链 —— b 一旦失败，a 的 128 个已经被扣走且不会还，玩家白白损失材料，
     * 还只收到一句「提交失败」。</p>
     *
     * @return 全部扣除成功才返回 true
     */
    private static boolean takeAll(Player player, String[][] need, int amount) {
        if (player == null || need == null || amount <= 0) return false;
        // 1) 整体预检：任一种不够就不动背包
        for (String[] n : need) {
            if (countItem(player, n[0]) < amount) return false;
        }
        // 2) 逐个扣除，失败或异常则回滚已扣的
        int[] taken = new int[need.length];
        try {
            for (int i = 0; i < need.length; i++) {
                if (!takeItem(player, need[i][0], amount)) {
                    giveBack(player, need, taken);
                    return false;
                }
                taken[i] = amount;
            }
            return true;
        } catch (Throwable t) {
            giveBack(player, need, taken);
            return false;
        }
    }

    /** 把 {@code taken} 里记录的数量按原样还给玩家（背包满了就掉地上）。 */
    private static void giveBack(Player player, String[][] need, int[] taken) {
        for (int i = 0; i < need.length; i++) {
            if (taken[i] <= 0) continue;
            try {
                ItemStack stack = LuxianSword.slimefunItem(need[i][0]);
                if (stack == null) continue;
                stack.setAmount(taken[i]);
                var left = player.getInventory().addItem(stack);
                for (ItemStack rest : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            } catch (Throwable ignored) {
                // 还回去失败也不能打断主流程
            }
        }
    }

    // ------------------------------------------------------------------
    // 各类存档读取
    // ------------------------------------------------------------------

    private static Map<String, Object> readPlayerDir(String dirName, String playerName) {
        File f = new File(AddonPaths.configDir(dirName), PlayerKeys.key(playerName) + ".json");
        Map<String, Object> data = AddonPaths.readJson(f);
        return data == null ? new LinkedHashMap<>() : data;
    }

    private static double gongde(String playerName) {
        return Json.dbl(readPlayerDir("天道货币", playerName), "gongde", 0);
    }

    /** 扣除功德并同步刷新背包里问天玉碟的功德 lore。 */
    private static boolean consumeGongde(Player player, double amount) {
        try {
            String name = player.getName();
            Map<String, Object> data = readPlayerDir("天道货币", name);
            double have = Json.dbl(data, "gongde", 0);
            if (have < amount) return false;
            data.put("gongde", have - amount);
            data.putIfAbsent("daoyuan", 0);
            AddonPaths.writeJson(new File(AddonPaths.configDir("天道货币"), PlayerKeys.key(name) + ".json"), data);
            updateGongdeLore(player, have - amount);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把背包里所有问天玉碟的功德行（lore 下标 4）刷成最新值。 */
    private static void updateGongdeLore(Player player, double value) {
        try {
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack == null || stack.getType().isAir()) continue;
                String id = sfId(stack);
                if (id == null || !CARD_ID.equalsIgnoreCase(id)) continue;
                ItemMeta meta = stack.getItemMeta();
                if (meta == null) continue;

                List<Component> lore = new ArrayList<>();
                List<Component> existing = meta.lore();
                if (existing != null) lore.addAll(existing);
                while (lore.size() < 5) lore.add(legacy("§7"));
                lore.set(4, legacy("§f[§e功德余额§f]§6 " + num(value) + "功德"));
                meta.lore(lore);
                stack.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static List<String> ownedYanhuo(String playerName) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(readPlayerDir(AddonPaths.DIR_YANHUO, playerName), "owned")) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static boolean hasAllYanhuo(String playerName) {
        List<String> owned = ownedYanhuo(playerName);
        for (int i = 1; i <= 9; i++) {
            String need = String.format("TR_YH%02d", i);
            boolean found = false;
            for (String o : owned) {
                if (o.equalsIgnoreCase(need)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static int danfangExp(String playerName) {
        return Json.i(readPlayerDir(AddonPaths.DIR_DANFANG, playerName), "exp", 0);
    }

    /** 境界等级；未开辟返回 -1（与原脚本一致）。 */
    private static int realmLevel(String playerName) {
        Map<String, Object> data = readPlayerDir(AddonPaths.DIR_REALM, playerName);
        if (data.isEmpty() || !Json.bool(data, "opened", false)) return -1;
        return data.get("realm") == null ? -1 : Json.i(data, "realm", -1);
    }

    // ------------------------------------------------------------------
    // 任务存档
    // ------------------------------------------------------------------

    private static File taskFile(String playerName) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_TASKS), PlayerKeys.key(playerName) + ".json");
    }

    private static Map<String, Object> loadTaskData(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(taskFile(playerName));
        if (data == null) data = new LinkedHashMap<>();
        if (!(data.get("completed") instanceof List)) data.put("completed", new ArrayList<>());
        return data;
    }

    private static void saveTaskData(String playerName, Map<String, Object> data) {
        AddonPaths.writeJson(taskFile(playerName), data);
    }

    private static boolean hasCompleted(Map<String, Object> data, String taskId) {
        for (Object o : Json.arr(data, "completed")) {
            if (taskId.equals(String.valueOf(o))) return true;
        }
        return false;
    }

    private static int taskIndex(String taskId) {
        for (int i = 0; i < TASKS.size(); i++) {
            if (TASKS.get(i).id().equals(taskId)) return i;
        }
        return -1;
    }

    /** 前面第一个未完成的任务；全完成或 index == 0 时返回 null。 */
    private static TaskDef missingPrevious(Map<String, Object> data, int index) {
        for (int i = 0; i < index && i < TASKS.size(); i++) {
            if (!hasCompleted(data, TASKS.get(i).id())) return TASKS.get(i);
        }
        return null;
    }

    /**
     * 标记任务完成。
     *
     * <p>唯一入口就是带 {@code data} 的这个版本：调用方为了算前置任务本来就得读一次
     * 存档（handleTask / handleFinalHour），传进来即可，不必同一次交互里读两遍。</p>
     *
     * @param data 已经读好的存档（非 null）
     */
    private static boolean completeTask(String playerName, String taskId, Map<String, Object> data) {
        if (hasCompleted(data, taskId)) return false;
        int index = taskIndex(taskId);
        if (index > 0) {
            for (int i = 0; i < index; i++) {
                if (!hasCompleted(data, TASKS.get(i).id())) return false;
            }
        }
        Json.arr(data, "completed").add(taskId);
        saveTaskData(playerName, data);
        return true;
    }

    private static boolean isTaskDone(Player player, TaskDef task) {
        try {
            return task.check() != null && task.check().test(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 任务九的奖励
    // ------------------------------------------------------------------

    /** 任务八的缺料描述。 */
    private static String finalHourMissing(Player player) {
        List<String> miss = new ArrayList<>();
        String[][] need = {{"Tr_dun", "沌"}, {"Tr_cui", "萃"}, {"Tr_cai", "材"}};
        for (String[] n : need) {
            int have = countItem(player, n[0]);
            if (have < 128) miss.add(n[1] + " x" + (128 - have));
        }
        return String.join("、", miss);
    }

    /**
     * 发放「中章装备全套」：四件工具 + 破法弓 + 戮仙剑。
     *
     * <p>六件全部就位才算成功（发放函数返回 0 表示本来就有，同样算成功）。
     * 原脚本注释记录过一个曾经的漏项：破法弓那一支最初忘了调，导致「只给工具不给弓」。</p>
     */
    private static boolean giveMidChapterKit(Player player) {
        try {
            int given = 0;
            given += EquipmentBridge.giveTwinTools(player);
            given += BossBattleBridge.givePofabow(player);
            if (!EquipmentBridge.hasLuxianSword(player) && EquipmentBridge.giveLuxianSword(player)) {
                given++;
            }
            // 保险：再走一遍 BOSS战 的「中章装备全套」（幂等，缺的会补、已有的不动）
            given += BossBattleBridge.giveMidChapterKit(player, "任务九奖励");

            List<String> miss = midChapterKitMissing(player);
            if (miss.isEmpty()) {
                player.sendMessage("§a已发放中章装备全套（四件工具 + 破法弓 + 戮仙剑）。");
                return true;
            }
            if (given > 0) {
                player.sendMessage("§a已发放中章装备全套（本次 " + given
                        + " 件）。§e背包满的会掉在脚下，记得捡：" + String.join("、", miss));
                warn("中章装备发放：" + player.getName() + " 补发 " + given
                        + " 件，背包里暂时看不到：" + String.join("、", miss));
                return true;
            }
            warn("中章装备发放不完整（" + player.getName() + "，一件都没发出去，仍缺："
                    + String.join("、", miss) + "）");
        } catch (Throwable t) {
            warn("中章装备发放异常: " + t);
        }
        return false;
    }

    /**
     * 「中章装备全套」还缺哪几件。
     *
     * <p>认法一律走粘液物品 id（{@link #sfId}）：最可靠，不受渐变名影响、也不能被铁砧伪造。
     * 早年还有一条「按去色显示名兜底」的分支（弓含「破法弓」、剑含「戮仙剑」），
     * 已删除：渐变名本来就会让纯字符串匹配不可靠，而这条兜底真正能命中的反而是
     * 玩家改过名的私人物品 —— 把私人物品当成套装部件，奖励就漏发了。</p>
     */
    private static List<String> midChapterKitMissing(Player player) {
        List<String> miss = new ArrayList<>();
        try {
            boolean[] got = new boolean[EquipmentBridge.TWIN_TOOL_IDS.length];
            boolean bow = false;
            boolean sword = false;

            for (ItemStack st : player.getInventory().getContents()) {
                if (st == null || st.getType().isAir()) continue;
                String id = sfId(st);
                if (id == null) id = "";

                if (!bow && id.equalsIgnoreCase(EquipmentBridge.BOW_ID)) {
                    bow = true;
                }
                if (!sword && id.equalsIgnoreCase(EquipmentBridge.SWORD_ID)) {
                    sword = true;
                }
                for (int t = 0; t < EquipmentBridge.TWIN_TOOL_IDS.length; t++) {
                    if (got[t]) continue;
                    if (id.equalsIgnoreCase(EquipmentBridge.TWIN_TOOL_IDS[t])) {
                        got[t] = true;
                        continue;
                    }
                    // 兜底：交给装备侧按「是不是双工具」判定（名字渐变时只能靠这个）
                    if (EquipmentBridge.isTwinTool(st, EquipmentBridge.TWIN_TOOL_IDS[t])) {
                        got[t] = true;
                    }
                }
            }
            for (int k = 0; k < got.length; k++) {
                if (!got[k]) miss.add(EquipmentBridge.TWIN_TOOL_NAMES[k]);
            }
            if (!bow) miss.add("破法弓");
            if (!sword) miss.add("戮仙剑");
        } catch (Throwable ignored) {
            // 忽略
        }
        return miss;
    }

    private static void warn(String msg) {
        try {
            TT.log("[糖然科技/任务] " + msg);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    /** 单个任务格的展示物。 */
    private static ItemStack buildTaskItem(TaskDef task, Player player, boolean completed,
                                           boolean locked, TaskDef missingTask,
                                           Map<String, Object> taskData) {
        // 任务八：提交后先变下界合金剑；通关阶段 1 后变「读取存档」；通关三阶段后显示已完成
        if ("final_hour".equals(task.id())) {
            BossBattleBridge.Progress progress = BossBattleBridge.readProgress(player.getName());
            if (progress.phase3Cleared()) {
                List<String> lore = new ArrayList<>(task.desc());
                lore.add("");
                lore.add("§a✔ 已完成");
                return named(Material.ENCHANTED_BOOK, task.name(), lore);
            }
            if (!locked && Boolean.TRUE.equals(taskData.get("bossReady"))) {
                if (progress.phase1Cleared()) {
                    return named(Material.SPYGLASS, "§d§l读取存档", List.of(
                            "§7点击读取阶段存档。", "§7已通关阶段可重复挑战。"));
                }
                return named(Material.NETHERITE_SWORD, "§c§l开始挑战中章BOSS！",
                        List.of("§7挑战难度较高，请做好准备。"));
            }
            List<String> lore = new ArrayList<>(task.desc());
            lore.add("");
            if (locked) {
                lore.add("§c前置任务未完成");
                if (missingTask != null) lore.add("§7请先完成：" + missingTask.name());
            } else {
                lore.add("§c未完成");
            }
            return named(Material.BOOK, task.name(), lore);
        }

        Material mat = completed ? Material.ENCHANTED_BOOK : Material.BOOK;
        List<String> lore = new ArrayList<>(task.desc());
        lore.add("");
        if (completed) {
            lore.add("§a✔ 已完成");
        } else if (locked) {
            lore.add("§c前置任务未完成");
            if (missingTask != null) lore.add("§7请先完成：" + missingTask.name());
        } else if (isTaskDone(player, task)) {
            lore.add("§e点击完成任务");
        } else {
            lore.add("§c未完成");
        }
        return named(mat, task.name(), lore);
    }

    private static Inventory buildTaskMenu(Player player) {
        String playerName = player.getName();
        Map<String, Object> data = loadTaskData(playerName);

        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy(GUI_TITLE));
        for (int slot : OUTER_SLOTS) inv.setItem(slot, randomGlass());

        for (int i = 0; i < TASKS.size() && i < PRODUCT_SLOTS.length; i++) {
            TaskDef task = TASKS.get(i);
            boolean completed = hasCompleted(data, task.id());
            TaskDef missing = missingPrevious(data, i);
            boolean locked = !completed && missing != null;
            inv.setItem(PRODUCT_SLOTS[i],
                    buildTaskItem(task, player, completed, locked, missing, data));
        }

        inv.setItem(40, named(Material.ENDER_EYE, "§b§l必要说明", List.of(
                "§71.进入任务八boss战的时候一定要阅读聊天栏的boss机制",
                "§72.如果服务器中加入了清理掉落物插件，boss战中的必要道具会被清扫走，"
                        + "比如护法zqx125阶段的反击之盾，这时候可以去垃圾桶看看哦",
                "§73.如果挑战期间服务器出现重启等情况，玩家的背包会停留在挑战阶段，"
                        + "这时候只需要再次开启一次挑战并输入/suicide自杀就能拿回背包中的物品"
                        + "(输入/sf guide领取一本新的粘液科技书)")));

        for (int s = 45; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(CLOSE_SLOT, glass("§c§l关闭菜单", "BARRIER"));
        return inv;
    }

    private static void openTaskMenu(Player player) {
        try {
            cleanLeakedMenuItems(player);
            Inventory inv = buildTaskMenu(player);
            ACTIVE.add(inv);
            MENU_TYPE.put(inv, "task");
            player.playSound(player.getLocation(), "block.enchantment_table.use", 0.8f, 1.4f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[任务] 打开任务菜单失败: " + t);
        }
    }

    private static void refresh(Inventory inv, Inventory fresh) {
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, fresh.getItem(i));
    }

    /** BOSS 阶段选择界面。 */
    private static Inventory buildBossStageMenu(Player player) {
        BossBattleBridge.Progress progress = BossBattleBridge.readProgress(player.getName());
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy("§d§l选择挑战阶段"));
        for (int slot : OUTER_SLOTS) inv.setItem(slot, randomGlass());
        for (int s = 45; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(CLOSE_SLOT, glass("§c§l关闭菜单", "BARRIER"));

        for (int phase = 1; phase <= 4; phase++) {
            int slot = BOSS_STAGE_SLOTS[phase - 1];
            if (!progress.unlocked(phase)) {
                inv.setItem(slot, named(Material.BARRIER, "§c此阶段您尚未解锁", List.of()));
                continue;
            }
            Material mat = switch (phase) {
                case 1 -> Material.LEATHER_BOOTS;
                case 2 -> Material.IRON_SWORD;
                // 阶段 3：糖然下场（三阶段）
                case 3 -> Material.FLINT_AND_STEEL;
                // 阶段 4：挑战彻底结束的过场
                default -> Material.REDSTONE;
            };
            List<String> lore = new ArrayList<>();
            if (phase == 2) {
                lore.add("§7点击选择护法阶段（读档到指定护法）。");
            } else if (phase == 3) {
                lore.add("§7点击挑战 §c糖然本体§7。");
            } else if (phase == 4) {
                lore.add("§7跟糖bee说再见。");
            } else {
                lore.add("§7点击挑战该阶段。");
            }
            if (progress.has(phase)) lore.add("§a已通关（可重复挑战）");
            inv.setItem(slot, named(mat, "§a§l阶段 " + phase, lore));
        }
        return inv;
    }

    private static void openBossStageMenu(Player player) {
        try {
            Inventory inv = buildBossStageMenu(player);
            ACTIVE.add(inv);
            MENU_TYPE.put(inv, "boss_stage");
            player.playSound(player.getLocation(), "block.enchantment_table.use", 0.8f, 1.4f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[任务] 打开阶段菜单失败: " + t);
        }
    }

    /**
     * 护法选择界面（阶段 2 的读档）。
     *
     * <p>四位护法全部开放（不再有「未击败」屏障）：统一护法正版头颅 + 可点击。
     * 名单由 {@link BossBattleBridge#guardians()} 提供；拿不到名单时退回单个 {@code zqx125}，
     * 与原脚本的兜底一致。</p>
     */
    private static void openGuardianMenu(Player player) {
        try {
            Inventory inv = Bukkit.createInventory(null, MENU_SIZE, legacy("§d§l选择护法阶段"));
            for (int slot : OUTER_SLOTS) inv.setItem(slot, randomGlass());
            for (int s = 45; s < MENU_SIZE; s++) {
                inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
            }
            inv.setItem(CLOSE_SLOT, glass("§c§l关闭菜单", "BARRIER"));

            List<BossBattleBridge.Guardian> roster = BossBattleBridge.guardians();
            if (roster.isEmpty()) {
                roster = List.of(new BossBattleBridge.Guardian(
                        "zqx125", "zqx125", null, List.of(), false));
            }

            Map<Integer, BossBattleBridge.Guardian> slotMap = new LinkedHashMap<>();
            int shown = 0;
            for (; shown < roster.size() && shown < GUARDIAN_SLOTS.length; shown++) {
                BossBattleBridge.Guardian g = roster.get(shown);
                boolean beaten = BossBattleBridge.hasBeatenGuardian(player, g.id());

                List<String> lore = new ArrayList<>();
                lore.add("§7点击从该护法开始挑战。");
                if (g.members() != null && !g.members().isEmpty()) {
                    for (BossBattleBridge.Guardian.Member m : g.members()) {
                        lore.add("§f[" + m.title() + "] §e" + m.id());
                    }
                }
                lore.add(beaten ? "§a已击败（可重复挑战）" : "§e当前进度（尚未通关）");
                if (g.unimplemented()) lore.add("§7内容尚未实装。");

                String iconName = "§a§l护法 " + g.name();
                ItemStack icon = null;

                if (g.iconMaterial() != null && !g.iconMaterial().isBlank()) {
                    Material mat = Material.matchMaterial(g.iconMaterial());
                    if (mat != null) icon = named(mat, iconName, lore);
                } else {
                    icon = BossBattleBridge.makeGuardianHead(g.id(), iconName, lore);
                    if (icon == null || icon.getType() != Material.PLAYER_HEAD) {
                        // 兜底：拿不到头颅时用普通头颅，但名字 / lore 仍需正确
                        icon = named(Material.PLAYER_HEAD, iconName, lore);
                    } else {
                        // 头颅产自 BossBattleBridge，不过 named() 工厂，手动补标记
                        tagMenuItem(icon);
                    }
                }
                inv.setItem(GUARDIAN_SLOTS[shown], icon);
                slotMap.put(GUARDIAN_SLOTS[shown], g);
            }
            // 名单外的护法槽位（护法四已删）：用灰色玻璃补齐，避免留空槽
            for (int fs = shown; fs < GUARDIAN_SLOTS.length; fs++) {
                inv.setItem(GUARDIAN_SLOTS[fs], glass("§0", "GRAY_STAINED_GLASS_PANE"));
            }

            ACTIVE.add(inv);
            MENU_TYPE.put(inv, "boss_guardian");
            GUARDIAN_MAP.put(inv, slotMap);
            player.playSound(player.getLocation(), "block.enchantment_table.use", 0.8f, 1.2f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[任务] 打开护法菜单失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 菜单物品防漏
    // ------------------------------------------------------------------

    /** 去掉 {@code §} 与 {@code &} 颜色码。 */
    private static String stripColors(String s) {
        return Colors.strip(s).replaceAll("&.", "");
    }

    /**
     * 是不是我们菜单里的物品 —— 只认 {@link #MENU_ITEM_KEY} PDC 标记，不看显示名。
     *
     * <p>被顺走的菜单物品（「阶段 N / 关闭菜单 / 读取存档 / 任务其…」等）会留在背包里，
     * 靠这个方法识别并清理。早年按显示名子串匹配，误删过玩家改过名的私人物品
     * （铁砧可伪造名字，显示名不能用来判物品身份）；现在只认 PDC。
     * 代价：本次修复前就已经漏进背包的旧菜单物品认不出来、会一直留着
     * （纯外观垃圾且不会复发）—— 比误删玩家物品可接受得多。</p>
     */
    private static boolean isOurMenuItem(ItemStack stack) {
        try {
            if (stack == null || stack.getType().isAir()) return false;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return false;
            return meta.getPersistentDataContainer().has(MENU_ITEM_KEY, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清掉已经漏进背包的菜单物品（登录时 + 打开菜单时各清一次）。 */
    private static void cleanLeakedMenuItems(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            PlayerInventory inv = player.getInventory();
            int removed = 0;
            for (ItemStack st : inv.getContents()) {
                if (st == null || !isOurMenuItem(st)) continue;
                try {
                    inv.remove(st);
                    removed++;
                } catch (Throwable ignored) {
                    // 单件失败跳过
                }
            }
            if (removed > 0) {
                player.updateInventory();
                warn("清理了 " + removed + " 件漏进背包的菜单物品：" + player.getName());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new TaskListener());
    }

    private static final class TaskListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                // 兜底 1：点到的是我们菜单里的物品 → 只取消这次移动，不 return（按钮逻辑还要跑）
                if (isOurMenuItem(event.getCurrentItem())) event.setCancelled(true);

                // 兜底 2：标题是我们菜单时，shift 点击一律禁止移动
                try {
                    String title = stripColors(Views.title(event.getView()));
                    if (event.isShiftClick()
                            && (title.contains("选择挑战阶段") || title.contains("中章任务"))) {
                        event.setCancelled(true);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }

                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                String type = MENU_TYPE.get(top);
                if (type == null) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;

                org.bukkit.inventory.Inventory clicked = event.getClickedInventory();
                int slot = event.getRawSlot();

                if (clicked != top) {
                    if (event.isShiftClick()) event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);

                if ("boss_guardian".equals(type)) {
                    handleGuardian(player, top, slot);
                    return;
                }
                if ("boss_stage".equals(type)) {
                    handleStage(player, slot);
                    return;
                }
                handleTask(player, top, slot);
            } catch (Throwable t) {
                TT.log("[任务] 点击处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                Inventory inv = event.getInventory();
                ACTIVE.remove(inv);
                MENU_TYPE.remove(inv);
                GUARDIAN_MAP.remove(inv);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top != null && ACTIVE.contains(top)) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 登录时清掉漏进背包的菜单物品（阶段 1 / 阶段 2 …）。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            cleanLeakedMenuItems(event.getPlayer());
        }

        // ---- 护法选择 ----

        private static void handleGuardian(Player player, Inventory top, int slot) {
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            Map<Integer, BossBattleBridge.Guardian> map = GUARDIAN_MAP.get(top);
            if (map == null) return;
            BossBattleBridge.Guardian picked = map.get(slot);
            if (picked == null) return;

            if (picked.unimplemented()) {
                player.sendMessage("§c该护法的内容尚未实装。");
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            player.closeInventory();

            if (BossBattleBridge.isActive(player)) {
                player.sendMessage("§c你已经在挑战中章BOSS了！");
                return;
            }
            boolean started = BossBattleBridge.startPhaseFromGuardian(player, picked.id());
            if (!started) {
                // 记一条日志：说明这次读档选的护法没被接受，会退回从 zqx125 开始
                BossBattleBridge.log("读档护法未被接受（" + picked.id() + "），退回 zqx125 开始");
                if (!BossBattleBridge.startPhase(player, 2)) {
                    player.sendMessage("§cBOSS战脚本未加载或未实现。");
                }
            }
        }

        // ---- 阶段选择 ----

        private static void handleStage(Player player, int slot) {
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            int stageIndex = -1;
            for (int i = 0; i < BOSS_STAGE_SLOTS.length; i++) {
                if (BOSS_STAGE_SLOTS[i] == slot) {
                    stageIndex = i;
                    break;
                }
            }
            if (stageIndex < 0) return;
            int phase = stageIndex + 1;

            BossBattleBridge.Progress progress = BossBattleBridge.readProgress(player.getName());
            if (!progress.unlocked(phase)) {
                player.sendMessage("§c此阶段您尚未解锁。");
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }

            // 阶段 2：先进「选择护法阶段」界面（读档到指定护法）
            if (phase == 2) {
                player.closeInventory();
                openGuardianMenu(player);
                return;
            }
            player.closeInventory();
            if (!BossBattleBridge.startPhase(player, phase)) {
                player.sendMessage("§cBOSS战脚本未加载或未实现阶段挑战。");
            }
        }

        // ---- 任务格 ----

        private static void handleTask(Player player, Inventory top, int slot) {
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            int idx = -1;
            for (int i = 0; i < PRODUCT_SLOTS.length; i++) {
                if (PRODUCT_SLOTS[i] == slot) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0 || idx >= TASKS.size()) return;

            TaskDef task = TASKS.get(idx);
            String playerName = player.getName();
            Map<String, Object> data = loadTaskData(playerName);
            TaskDef missingPrev = missingPrevious(data, idx);

            if ("final_hour".equals(task.id())) {
                handleFinalHour(player, top, task, data, missingPrev);
                return;
            }

            if (hasCompleted(data, task.id())) {
                player.sendMessage("§a该任务已经完成过了。");
                return;
            }
            if (missingPrev != null) {
                player.sendMessage("§c请先完成前置任务：" + missingPrev.name());
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            if (!isTaskDone(player, task)) {
                String fail = (task.failMessage() == null)
                        ? "未满足任务条件"
                        : task.failMessage().apply(player);
                player.sendMessage("§c任务尚未达成: " + fail);
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            if (task.consume() != null && !task.consume().test(player)) {
                player.sendMessage("§c上交失败，请确认材料数量是否足够。");
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            if (completeTask(playerName, task.id(), data)) {
                player.sendMessage("§a任务完成！" + Colors.strip(task.name()));
                player.playSound(player.getLocation(), "entity.player.levelup", 0.8f, 1.2f);
                if (task.reward() != null && !task.reward().test(player)) {
                    player.sendMessage("§c奖励发放失败，请找管理员补发。");
                }
                refresh(top, buildTaskMenu(player));
                player.updateInventory();
            }
        }

        /** 任务八：提交材料后不标记完成，只解锁 BOSS 挑战。 */
        private static void handleFinalHour(Player player, Inventory top, TaskDef task,
                                           Map<String, Object> data, TaskDef missingPrev) {
            if (missingPrev != null) {
                player.sendMessage("§c请先完成前置任务：" + missingPrev.name());
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }

            if (Boolean.TRUE.equals(data.get("bossReady"))) {
                if (BossBattleBridge.isActive(player)) {
                    player.sendMessage("§c你已经在挑战中章BOSS了！");
                    return;
                }
                BossBattleBridge.Progress progress =
                        BossBattleBridge.readProgress(player.getName());
                if (progress.phase1Cleared()) {
                    openBossStageMenu(player);
                    return;
                }
                if (!BossBattleBridge.start(player)) {
                    player.sendMessage("§cBOSS战脚本未加载或未实现。");
                }
                return;
            }

            List<String> missing = new ArrayList<>();
            String[][] need = {{"Tr_dun", "沌"}, {"Tr_cui", "萃"}, {"Tr_cai", "材"}};
            for (String[] n : need) {
                int have = countItem(player, n[0]);
                if (have < 128) missing.add(n[1] + " x" + (128 - have));
            }
            if (!missing.isEmpty()) {
                player.sendMessage("§c任务尚未达成: " + String.join("、", missing));
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }

            // 三种材料一次性结算：要么全扣、要么一个都不扣（回滚见 takeAll 的说明）。
            // 原来这里是 !take(沌) || !take(萃) || !take(材) 的短路链 —— 一旦「萃」
            // 失败（比如扣到一半抛异常），「沌」的 128 个已经被扣走且不会还回来，
            // 玩家白丢材料，只收到一句「提交失败」。
            if (!takeAll(player, need, 128)) {
                player.sendMessage("§c提交失败，请确认材料数量是否足够。");
                player.playSound(player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
                return;
            }

            data.put("bossReady", Boolean.TRUE);
            saveTaskData(player.getName(), data);
            player.sendMessage("§a提交成功！你已获得挑战中章BOSS的权限。");
            player.playSound(player.getLocation(), "entity.player.levelup", 0.8f, 1.2f);
            refresh(top, buildTaskMenu(player));
            player.updateInventory();
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        ACTIVE.clear();
        MENU_TYPE.clear();
        GUARDIAN_MAP.clear();
    }
}
