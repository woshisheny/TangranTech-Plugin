package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Perms;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import com.tangran.tangrantech.native_scripts.daofa.FudiPool;
import com.tangran.tangrantech.native_scripts.daofa.LingpuPlot;
import com.tangran.tangrantech.native_scripts.daofa.StorageSpaceUnlockScript;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossChallengeState;

/**
 * 「道法/储物空间」的原生实现（对应 {@code scripts/道法/储物空间.js}，2063 行）。
 *
 * <p>这是一个多人共用的<b>按玩家存档</b>的大型 GUI 系统，一共五套界面：</p>
 * <ol>
 *   <li><b>主界面</b> —— 分类入口（原版 / 粘液科技）+ 洞天福地入口；</li>
 *   <li><b>分类储物</b> —— 每页 45 格，前 45 格展示「物品类型 → 累计数量」，后 9 格翻页/取出/自动归位；</li>
 *   <li><b>洞天福地</b> —— 二级菜单，通向福地生珍与灵圃；</li>
 *   <li><b>福地生珍</b> —— 每轮刷 6~15 件随机产出，可单件领取、一键领取、放弃、用刷新券跳冷却；</li>
 *   <li><b>灵圃 / 灵圃果实</b> —— 种田小游戏（详见 {@link LingpuPlot}）与果实仓库。</li>
 * </ol>
 *
 * <p><b>准入</b>：必须先由「储物空间开辟石」写出
 * {@code addon_configs/糖然科技/储物空间/<玩家名>.json}（见 {@code StorageSpaceUnlockScript}），
 * 否则点物品/按钮只会得到一句提示。</p>
 *
 * <h2>入口</h2>
 * <p>两个，与原脚本一致：纳界袋物品（{@code TR_NJD}，材质 {@code BUNDLE}）的 {@code onUse}，
 * 以及 {@code groups.yml} 里绑定本脚本的按钮组（{@code Tr_60}）。</p>
 *
 * <h2>会话管理：改用 InventoryHolder，不再维护「已打开背包」集合</h2>
 * <p>原脚本用两个全局容器（{@code activeInventories} 记录已打开的背包、{@code INV_DATA} 记录
 * 每个背包的菜单上下文），并在 {@code InventoryCloseEvent} 里清理。原生化后改成给每个菜单挂一个
 * {@link MenuHolder}，事件里直接读 {@code getTopInventory().getHolder()}：</p>
 * <ul>
 *   <li>不再需要「这个背包是不是我们的」集合查询，也就不会再出现「关屏/掉线后集合没清干净」的问题；</li>
 *   <li>{@code InventoryCloseEvent} 可以完全不注册 —— 原脚本在关闭时也只是清这两个容器，
 *       而真正的数据在每次操作后都已经落盘（本类同样如此）。</li>
 * </ul>
 *
 * <h2>与原脚本的三处刻意差异</h2>
 * <ol>
 *   <li>{@code UI_STATE}（玩家名 → 当前分类/页码）原脚本<b>只写不读</b>，是死变量，不再搬。</li>
 *   <li>{@code skullItem()}（用 Base64 纹理建头）原脚本<b>定义了但一次都没调用</b>，
 *       且带一段 NMS 反射（{@code org.bukkit.craftbukkit.<version>.inventory.CraftMetaSkull}）
 *       在 1.21 会直接失败。属死代码，不再搬。</li>
 *   <li>{@code VANILLA_COMMON_IDS} / {@code VANILLA_RARE_IDS} 两张表也从未被读取，同属死代码，
 *       见 {@link FudiPool} 类注释。</li>
 * </ol>
 *
 * <h2>一处沿用原脚本的已知风险（未改动，需你决策）</h2>
 * <p>原脚本对「背包侧点击」只拦了 shift：</p>
 * <pre>
 * if (clickedInv !== topInv) {
 *     if (event.isShiftClick() &amp;&amp; nonStorage) { event.setCancelled(true); return; }
 *     return;
 * }
 * </pre>
 * <p>而 {@code nonStorage} <b>不包含分类储物界面</b>（因为该界面要支持「手持物品点击存入」）。
 * 于是在<b>分类储物界面里 shift 点击背包物品</b>不会被拦下，物品会被原版逻辑塞进顶部的展示格，
 * 关闭界面后随之消失。这是原脚本就存在的物品丢失口子。本实现按「行为等价」要求<b>原样保留</b>，
 * 未擅自加 {@code setCancelled(true)} —— 若你确认要堵，只需在 {@code handleClick} 里把
 * {@code nonStorage} 的判断去掉（即对分类界面也拦 shift）即可，一行改动。</p>
 *
 * <h2>存档</h2>
 * <p>{@code addon_configs/糖然科技/储物空间/<玩家名>.json}，字段名与嵌套结构沿用原脚本
 * （{@code vanilla} / {@code slimefun} / {@code fudi} / {@code lingpu.plots} /
 * {@code lingpu.fruits}），老档可直接读取。</p>
 */
public final class StorageSpaceScript implements NativeLifecycleScript {

    // ------------------------------------------------------------------
    // 界面标题
    // ------------------------------------------------------------------

    private static final String TITLE_MAIN = "{#e8f6ff}&l储{#b8e3ff}&l物{#7fc9e8}&l空{#4aa3c7}&l间{#d4f1f9}&l";
    private static final String TITLE_DONGTIAN = "{#e8f6ff}&l洞{#b8e3ff}&l天{#7fc9e8}&l福{#4aa3c7}&l地{#d4f1f9}&l";
    private static final String TITLE_FUDI = "{#e8f6ff}&l福{#b8e3ff}&l地{#7fc9e8}&l生{#4aa3c7}&l珍{#d4f1f9}&l";
    private static final String TITLE_LINGPU = "{#87dea5}&l灵{#8dde87}&l圃";
    private static final String TITLE_FRUIT = "§b灵植果实储物空间";

    /** 分类界面标题与主界面一致（原脚本如此，方便识别为同一系统）。 */
    private static final String TITLE_CAT = TITLE_MAIN;

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

    private static final int MENU_SIZE = 54;
    /** 每页储物格数（前 5 行）。 */
    private static final int ITEMS_PER_PAGE = 45;
    private static final int MAX_STACK = 64;

    private static final int NAV_PREV_SLOT = 45;
    private static final int NAV_NEXT_SLOT = 53;
    private static final int BACK_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int CLOSE_SLOT = 50;
    private static final int AUTO_DEPOSIT_SLOT = 46;

    /** 主界面：洞天福地入口。 */
    private static final int FUDI_MAIN_SLOT = 0;

    /** 洞天福地界面：福地生珍入口。 */
    private static final int FUDI_CATEGORY_SLOT = 0;
    /** 洞天福地界面：灵圃入口。 */
    private static final int LINGPU_BUTTON_SLOT = 1;
    /** 洞天福地界面：返回主菜单。 */
    private static final int DONGTIAN_BACK_SLOT = 49;

    /** 福地生珍界面槽位。 */
    private static final int FUDI_CLAIM_ALL_SLOT = 45;
    private static final int FUDI_BACK_SLOT = 46;
    private static final int ABANDON_SLOT = 47;
    private static final int FUDI_REFRESH_SLOT = 48;

    /** 灵圃界面槽位。 */
    private static final int LINGPU_HARVEST_ALL_SLOT = 45;
    private static final int LINGPU_BACK_SLOT = 46;
    private static final int LINGPU_FRUIT_SLOT = 47;
    private static final int LINGPU_PREV_SLOT = 48;
    private static final int LINGPU_NEXT_SLOT = 52;
    private static final int LINGPU_INFO_SLOT = 53;

    /** 果实仓库界面槽位。 */
    private static final int FRUIT_BACK_SLOT = 45;
    private static final int FRUIT_PREV_SLOT = 48;
    private static final int FRUIT_NEXT_SLOT = 53;

    /** 福地生珍冷却时长（ms）。 */
    private static final long FUDI_COOLDOWN_MS = 60_000L;
    /** 刷新券物品 id。 */
    private static final String ITEM_FUDI_REFRESH_TICKET = "TR_FDSZSXQ";
    /** 聚灵散物品 id（催熟，每个减 10 秒）。 */
    private static final String ITEM_JLS = "TR_JLS";
    /** 纳界袋物品 id。 */
    private static final String ITEM_NJD = "TR_NJD";
    /** 灵圃自动收获任务的执行周期（刻）。 */
    private static final long AUTO_HARVEST_PERIOD_TICKS = 40L;

    // ------------------------------------------------------------------
    // 分类定义
    // ------------------------------------------------------------------

    private record CatDef(String id, String name, String lore, String icon, int slot) {}

    private static final List<CatDef> CATEGORIES = List.of(
            new CatDef("vanilla", "§a原版物品", "§7存放原版物品", "GRASS_BLOCK", 20),
            new CatDef("slimefun", "§b粘液科技物品", "§7存放粘液科技物品", "SLIME_BALL", 24));

    // ------------------------------------------------------------------
    // 菜单会话
    // ------------------------------------------------------------------

    private static final String TYPE_MAIN = "main";
    private static final String TYPE_CAT = "cat";
    private static final String TYPE_DONGTIAN = "dongtian";
    private static final String TYPE_FUDI = "fudi";
    private static final String TYPE_LINGPU = "lingpu";
    private static final String TYPE_FRUIT = "lingpu_fruit";

    /**
     * 菜单上下文。
     *
     * <p>挂在 {@code Inventory} 上的「我是谁」标记：事件里通过
     * {@code getTopInventory().getHolder()} 就能还原菜单类型与页码，不必再维护
     * 「背包 → 上下文」的外部映射表。</p>
     */
    private static final class MenuHolder implements InventoryHolder {
        final String type;
        /** 仅分类界面使用。 */
        String cat;
        /** 分类 / 灵圃 / 果实仓库界面使用。 */
        int page;
        Inventory inv;

        MenuHolder(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** 有鸿蒙神圃的玩家名（原脚本挂在 {@code globalThis.LINGPU_AUTO_PLAYERS} 上）。 */
    private static final Set<String> AUTO_PLAYERS = ConcurrentHashMap.newKeySet();
    /** 自动收获任务是否已完成首次在线玩家扫描。 */
    private static volatile boolean autoInited = false;

    // ==================================================================
    // 入口 / 生命周期
    // ==================================================================

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                        return onUse(event);
                    }
                    return Boolean.FALSE;
                }
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        return openFromButton(player);
                    }
                    return Boolean.FALSE;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[储物空间] invoke(" + fn + ") 异常: " + t);
            return Boolean.FALSE;
        }
    }

    /**
     * 右键入口（纳界袋）。
     *
     * <p>顺序与原脚本一致：先把纳界袋里的东西倒回背包，再判「是否已开辟」，最后开主界面。</p>
     */
    private static Object onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return Boolean.FALSE;
        }
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return Boolean.FALSE;

        ItemStack used = event.getItem();
        if (used == null) used = player.getInventory().getItemInMainHand();
        returnNjdContents(player, used);

        if (!hasAccess(player)) {
            player.sendMessage(PREFIX + "§c你还没有开辟储物空间！请先使用储物空间开辟石。");
            return Boolean.FALSE;
        }
        openMain(player);
        return Boolean.FALSE;
    }

    /** 按钮组入口。 */
    private static Object openFromButton(Player player) {
        if (!player.isOnline()) return Boolean.FALSE;
        if (!hasAccess(player)) {
            player.sendMessage("§c你还没有开辟储物空间！请先使用储物空间开辟石。");
            return Boolean.FALSE;
        }
        openMain(player);
        return Boolean.TRUE;
    }

    /**
     * 储物空间准入：<b>权限节点或存档里的 unlocked 任一成立即放行</b>。
     *
     * <p>节点 {@code tangrantech.storage.unlocked} 由开辟石在使用成功时发放，
     * 管理员因此可以用权限组批量发放/回收资格；而旧档（只有 JSON 里的 unlocked、
     * 没有节点）依然能进 —— 权限插件缺失时 {@link Perms#has} 回落 Bukkit hasPermission，
     * 也不会把老玩家挡在门外。</p>
     */
    private static boolean hasAccess(Player player) {
        if (player == null) return false;
        if (Perms.has(player, Perms.NODE_STORAGE)) return true;
        return AddonPaths.docExists(storageFile(player.getName()));
    }

    @Override
    public void registerListeners(Plugin plugin) {
        if (plugin == null) return;
        // 灵圃「鸿蒙神圃」的自动收获：每 40 刻（2 秒）跑一次，与原脚本
        // runTaskTimer(PLUGIN, runnable, 0, 40) 一致。任务挂在插件上，插件卸载时由 Bukkit 取消。
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, StorageSpaceScript::autoHarvestTick, 0L,
                    AUTO_HARVEST_PERIOD_TICKS);
        } catch (Throwable t) {
            TT.log("[储物空间] 灵圃自动收获任务启动失败: " + t);
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 只注册界面事件。右键入口走 ScriptItem → invoke("onUse") 这一条通道，
        // 刻意不再自注册 PlayerInteractEvent（两条通道会让一次右键触发两遍）。
        return Collections.singletonList(new MenuListener());
    }

    // ==================================================================
    // 存档读写
    // ==================================================================

    /** 界面消息前缀（带渐变色的「储物空间」标签）。 */
    private static final String PREFIX =
            "§f[§x§B§8§E§3§F§F储§x§7§F§C§9§E§8物§x§4§A§A§3§C§7空§x§D§4§F§1§F§9间§f] ";

    private static File storageFile(String playerName) {
        return new File(AddonPaths.configDir(AddonPaths.DIR_STORAGE), PlayerKeys.key(playerName) + ".json");
    }

    /**
     * 读存档；文件不存在返回 null（调用方据此提示「尚未开辟」）。
     *
     * <p>与原脚本一样，读到之后强制补齐 {@code vanilla} / {@code slimefun} / {@code fudi}
     * 三个子对象，这样后续写代码不用到处判空。</p>
     */
    private static Map<String, Object> load(String playerName) {
        File file = storageFile(playerName);
        if (!AddonPaths.docExists(file)) return null;
        Map<String, Object> data = AddonPaths.readJson(file);
        if (data == null) {
            // 文件损坏：原脚本会回落成一个带 unlocked 标记的空档。照做，避免此处直接判定「未开辟」。
            data = new LinkedHashMap<>();
            data.put("unlocked", Boolean.TRUE);
        }
        child(data, "vanilla");
        child(data, "slimefun");
        child(data, "fudi");
        return data;
    }

    private static void save(String playerName, Map<String, Object> data) {
        AddonPaths.writeJson(storageFile(playerName), data);
    }

    /** 取（并按需创建）一个可写的子对象。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    /** 取（并按需创建）一个可写的子列表。 */
    @SuppressWarnings("unchecked")
    private static List<Object> childList(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v instanceof List) return (List<Object>) v;
        List<Object> created = new ArrayList<>();
        parent.put(key, created);
        return created;
    }

    // ---- 分类容器（物品桶 → 数量） ----
    //
    // 存档形状（向后兼容）：
    //   白板物品 / 旧档  → "STONE": 128               （只有 id + 数量，物品按 id 现造）
    //   带数据物品       → "STONE#a1b2c3d4": {"count": 5, "nbt": "<base64 字节>"}
    //
    // 为什么要分桶：旧实现只按「材质 / 粘液 id」记数量，附魔、显示名、lore、损伤、
    // 容器内容、PDC 全都会在存取之间被抹掉（存进去一把锋利 V 的剑，取出来是白板剑）。
    // 现在把「与模板不一致」的物品按完整字节算指纹，单独成桶；取用时反序列化还原。

    /** 桶键分隔符：{@code id#指纹}。 */
    private static final char BUCKET_SEP = '#';

    /** 键里的分类 id（去掉 {@code #指纹}）。 */
    private static String idOfKey(String key) {
        if (key == null) return "";
        int i = key.indexOf(BUCKET_SEP);
        return i <= 0 ? key : key.substring(0, i);
    }

    /** 是不是「带完整物品数据」的桶键。 */
    private static boolean hasNbt(String key) {
        return key != null && key.indexOf(BUCKET_SEP) > 0;
    }

    /** 物品的完整字节（数量归一成 1，保证「同一物品不同堆叠数」是同一个桶）。 */
    private static byte[] bytesOf(ItemStack item) {
        if (item == null) return null;
        try {
            ItemStack copy = item.clone();
            copy.setAmount(1);
            return copy.serializeAsBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 字节指纹（SHA-1 前 12 位十六进制）—— 只要物品数据有一处不同就不会同桶。 */
    private static String fingerprint(byte[] raw) {
        try {
            byte[] dig = java.security.MessageDigest.getInstance("SHA-1").digest(raw);
            StringBuilder sb = new StringBuilder(12);
            // byte → 有符号，必须先 & 0xff 再格式化，否则 -1 会格式成 8 个 f
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", dig[i] & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(java.util.Arrays.hashCode(raw));
        }
    }

    /**
     * 物品该进哪个桶：与「按 id 现造的同款白板物品」字节一致 → 用 id（与旧档合并）；
     * 否则用 {@code id#指纹} 单独成桶。
     */
    private static String bucketKey(ItemStack item, String cat, String id) {
        if (item == null || id == null) return id;
        byte[] raw = bytesOf(item);
        if (raw == null) return id;
        byte[] clean = bytesOf(buildCleanStack(cat, id));
        if (clean != null && java.util.Arrays.equals(raw, clean)) return id;
        return id + BUCKET_SEP + fingerprint(raw);
    }

    /** 读一个桶的数量（兼容老格式的数字）。 */
    private static long countOf(Object entry) {
        if (entry instanceof Map<?, ?> m) return asLong(m.get("count"));
        return asLong(entry);
    }

    private static long count(Map<String, Object> data, String cat, String key) {
        return countOf(child(data, cat).get(key));
    }

    private static void setCount(Map<String, Object> data, String cat, String key, long n) {
        Map<String, Object> m = child(data, cat);
        Object old = m.get(key);
        if (n <= 0) {
            m.remove(key);
            return;
        }
        if (old instanceof Map<?, ?> om) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : om.entrySet()) {
                bucket.put(String.valueOf(e.getKey()), e.getValue());
            }
            bucket.put("count", n);
            m.put(key, bucket);
        } else {
            m.put(key, n);
        }
    }

    /** 存入：自动选桶；带数据的物品顺带把完整字节写进桶里（只写一次）。 */
    private static void addStored(Map<String, Object> data, ItemStack item, long amount) {
        if (item == null || amount <= 0) return;
        ItemCat info = classify(item);
        String key = bucketKey(item, info.cat(), info.id());
        Map<String, Object> m = child(data, info.cat());
        if (!hasNbt(key)) {
            m.put(key, countOf(m.get(key)) + amount);
            return;
        }
        Map<String, Object> bucket = new LinkedHashMap<>();
        Object old = m.get(key);
        if (old instanceof Map<?, ?> om) {
            for (Map.Entry<?, ?> e : om.entrySet()) {
                bucket.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (!bucket.containsKey("nbt")) {
            byte[] raw = bytesOf(item);
            if (raw != null) {
                bucket.put("nbt", java.util.Base64.getEncoder().encodeToString(raw));
            }
        }
        bucket.put("count", countOf(bucket) + amount);
        m.put(key, bucket);
    }

    /** 取一件原型物品：带数据的桶反序列化（失败退化白板）；白板桶按 id 现造。 */
    private static ItemStack protoOf(String cat, String key, Object entry) {
        if (entry instanceof Map<?, ?> m) {
            Object nbt = m.get("nbt");
            if (nbt != null) {
                try {
                    ItemStack it = ItemStack.deserializeBytes(
                            java.util.Base64.getDecoder().decode(String.valueOf(nbt)));
                    if (it != null && !it.getType().isAir()) {
                        it.setAmount(1);
                        return it;
                    }
                } catch (Throwable t) {
                    TT.log("[储物空间] 还原物品失败（" + key + "），已按白板处理: " + t);
                }
            }
        }
        return buildCleanStack(cat, idOfKey(key));
    }

    /** 有库存的物品桶，按字典序（与原脚本 {@code keys.sort()} 一致）。 */
    private static List<String> sortedKeys(Map<String, Object> data, String cat) {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, Object> e : child(data, cat).entrySet()) {
            if (countOf(e.getValue()) > 0) keys.add(e.getKey());
        }
        return new ArrayList<>(keys);
    }

    // ---- 福地生珍 ----

    private static Map<String, Object> fudi(Map<String, Object> data) {
        return child(data, "fudi");
    }

    private static List<Object> fudiCurrent(Map<String, Object> data) {
        return childList(fudi(data), "current");
    }

    private static Map<String, Object> fudiClaimed(Map<String, Object> data) {
        return child(fudi(data), "claimed");
    }

    private static Map<String, Object> fudiAbandoned(Map<String, Object> data) {
        return child(fudi(data), "abandoned");
    }

    private static long fudiNextRefresh(Map<String, Object> data) {
        return asLong(fudi(data).get("nextRefreshTime"));
    }

    /** 产出记录的唯一键。 */
    private static String fudiKey(Object product) {
        if (!(product instanceof Map<?, ?> m)) return "?|?";
        return m.get("cat") + "|" + m.get("id");
    }

    /** 本轮产出是否已全部处理完（领取或放弃）。空列表视为「已处理完」（与原脚本一致）。 */
    private static boolean fudiAllClaimed(Map<String, Object> data) {
        Map<String, Object> claimed = fudiClaimed(data);
        Map<String, Object> abandoned = fudiAbandoned(data);
        for (Object p : fudiCurrent(data)) {
            String key = fudiKey(p);
            if (!isTruthy(claimed.get(key)) && !isTruthy(abandoned.get(key))) return false;
        }
        return true;
    }

    // ---- 灵圃 ----

    private static Map<String, Object> lingpu(Map<String, Object> data) {
        return child(data, "lingpu");
    }

    private static Map<String, Object> plots(Map<String, Object> data) {
        return child(lingpu(data), "plots");
    }

    private static Map<String, Object> fruits(Map<String, Object> data) {
        return child(lingpu(data), "fruits");
    }

    private static LingpuPlot plotAt(Map<String, Object> data, int index) {
        return LingpuPlot.fromJson(plots(data).get(String.valueOf(index)));
    }

    private static void putPlot(Map<String, Object> data, int index, LingpuPlot plot) {
        plots(data).put(String.valueOf(index), plot.toJson());
    }

    private static void removePlot(Map<String, Object> data, int index) {
        plots(data).remove(String.valueOf(index));
    }

    // ==================================================================
    // 物品工具
    // ==================================================================

    /** 物品身份：{@code cat} 为 {@code vanilla}／{@code slimefun}，{@code id} 为材质名或粘液 id。 */
    private record ItemCat(String cat, String id) {}

    /** 判定一个物品属于哪个分类（原脚本 {@code getItemCategory}）。 */
    private static ItemCat classify(ItemStack stack) {
        try {
            SlimefunItem sf = SlimefunItem.getByItem(stack);
            if (sf != null && sf.getId() != null && !sf.getId().isBlank()) {
                return new ItemCat("slimefun", sf.getId());
            }
        } catch (Throwable ignored) {
            // 退到原版分类
        }
        return new ItemCat("vanilla", stack.getType().name());
    }

    /** 取物品的粘液 id；非粘液物品返回 null。 */
    private static String slimefunIdOf(ItemStack stack) {
        try {
            SlimefunItem sf = SlimefunItem.getByItem(stack);
            return (sf == null) ? null : sf.getId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按 id 取粘液物品的一份拷贝（原大小写 / 全大写 / 全小写轮试）。 */
    private static ItemStack slimefunItem(String id) {
        if (id == null || id.isBlank()) return null;
        for (String candidate : new String[]{id, id.toUpperCase(Locale.ROOT), id.toLowerCase(Locale.ROOT)}) {
            try {
                SlimefunItem sf = SlimefunItem.getById(candidate);
                if (sf != null && sf.getItem() != null) {
                    // 必须 clone：sf.getItem() 返回的是注册模板，直接改会污染所有同类物品
                    return sf.getItem().clone();
                }
            } catch (Throwable ignored) {
                // 换下一种写法
            }
        }
        return null;
    }

    /** 按「分类 + id」造一件干净物品（无自定义名/lore）。 */
    private static ItemStack buildCleanStack(String cat, String id) {
        if ("slimefun".equals(cat)) return slimefunItem(id);
        try {
            Material mat = Material.matchMaterial(id);
            return mat == null ? null : new ItemStack(mat);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取物品自带的显示名（legacy 带色）；没有则返回 fallback。 */
    private static String legacyNameOf(ItemStack stack, String fallback) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String s = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
            // 退到 fallback
        }
        return fallback;
    }

    /** 「分类 + id」对应的显示名。 */
    private static String displayNameOf(String cat, String id) {
        ItemStack stack = buildCleanStack(cat, id);
        return stack == null ? id : legacyNameOf(stack, id);
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    /** 给物品改名 + 写 lore（legacy {@code §} 码会自动转成组件）。 */
    private static ItemStack named(ItemStack item, String name, List<String> loreLines) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreLines.size());
                for (String line : loreLines) lore.add(legacy(line));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 「材质名 + 名字」的普通按钮。 */
    private static ItemStack glass(String name, String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        return named(new ItemStack(mat), name, null);
    }

    /** 用粘液物品当底板的按钮；id 不存在时回落成末影箱。 */
    private static ItemStack slimefunItemButton(String sfId, String name, List<String> loreLines) {
        ItemStack item = slimefunItem(sfId);
        if (item == null) item = new ItemStack(Material.ENDER_CHEST);
        return named(item, Colors.c(name), Colors.c(loreLines));
    }

    /** 空储物格占位。 */
    private static ItemStack placeholder() {
        return named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "§8§o[空储物格]", null);
    }

    /** 「自动归位」按钮。 */
    private static ItemStack autoDepositButton() {
        ItemStack base = slimefunItem("RADIANT_BACKPACK");
        if (base == null) base = new ItemStack(Material.ENDER_CHEST);
        return named(base, "§d§l[自动归位]", List.of(
                "§7左键：将背包中已存储过的物品各存入64个",
                "§7右键：将背包中已存储过的物品全部存入",
                "§7数量不足时只存入背包拥有的数量"));
    }

    /** 分类界面里一格「储物展示」。 */
    /** 分类界面里一格「储物展示」；带数据的桶直接用存下来的物品当图标。 */
    private static ItemStack storageDisplayItem(String cat, String key, Object entry, long amount) {
        ItemStack stack = protoOf(cat, key, entry);
        if (stack == null) stack = new ItemStack(Material.STONE);
        stack.setAmount(1);
        // 名字优先取物品自己的（带 NBT 的桶才有自定义名），否则用模板名
        String name = legacyNameOf(stack, displayNameOf(cat, idOfKey(key)));
        return named(stack, name, List.of(
                "§7库存数量: §f" + amount,
                "§e左键取出 1 个",
                "§e右键取出 64 个",
                "§eShift+左键填满背包",
                "§7手持物品点击本格可存入"));
    }

    // ==================================================================
    // 打开界面
    // ==================================================================

    /** 二阶段挑战期间禁止打开储物空间（防止把挑战中发放的装备藏进去）。 */
    private static boolean sealed(Player player) {
        try {
            return BossChallengeState.isPhaseTwoLocked(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void openMenu(Player player, Inventory inv) {
        if (sealed(player)) {
            player.sendMessage("§c糖然二阶段挑战期间，无法打开储物空间！");
            return;
        }
        player.openInventory(inv);
    }

    private static void openMain(Player player) {
        MenuHolder holder = new MenuHolder(TYPE_MAIN);
        Inventory inv = buildMainMenu(holder);
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openCategory(Player player, String playerName, String cat, int page) {
        MenuHolder holder = new MenuHolder(TYPE_CAT);
        holder.cat = cat;
        holder.page = page;
        Inventory inv = buildCategoryMenu(playerName, holder);
        if (inv == null) return;
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openDongtian(Player player) {
        MenuHolder holder = new MenuHolder(TYPE_DONGTIAN);
        Inventory inv = buildDongtianMenu(holder);
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openFudi(Player player, String playerName) {
        Map<String, Object> data = load(playerName);
        if (data == null) return;
        // 先落盘：buildFudiMenu 里可能会刷新一轮产出，那次刷新自己也 save 一遍，
        // 这里的 save 负责把「fudi 子对象已建好」这件事固化下来（原脚本同样先 save 再 build）。
        save(playerName, data);
        MenuHolder holder = new MenuHolder(TYPE_FUDI);
        Inventory inv = buildFudiMenu(playerName, holder);
        if (inv == null) return;
        holder.inv = inv;
        openMenu(player, inv);
    }

    private static void openLingpu(Player player, String playerName, int page) {
        MenuHolder holder = new MenuHolder(TYPE_LINGPU);
        holder.page = page;
        Inventory inv = buildLingpuMenu(playerName, holder);
        if (inv == null) return;
        holder.inv = inv;
        openMenu(player, inv);
        startLingpuRefresh(player, holder);
    }

    /**
     * 灵圃界面的实时刷新任务（玩家名 → 任务）。
     *
     * <p>打开界面期间每 20 刻（1 秒）原地重画一次格子，倒计时与事件进度实时可见；
     * 玩家下线 / 关界面 / 切到别的菜单时任务自动停。写档走 Store 的写后缓存，
     * 每秒一次只是内存标记，落库仍按 5 分钟周期，无性能压力。</p>
     */
    private static final Map<String, org.bukkit.scheduler.BukkitTask> LINGPU_REFRESH =
            new ConcurrentHashMap<>();

    /** 打开灵圃界面时启动（替换同名旧任务，幂等）。 */
    private static void startLingpuRefresh(Player player, MenuHolder holder) {
        stopLingpuRefresh(player.getName());
        try {
            LINGPU_REFRESH.put(player.getName(), org.bukkit.Bukkit.getScheduler().runTaskTimer(
                    TT.plugin, () -> {
                        try {
                            if (!player.isOnline()) {
                                stopLingpuRefresh(player.getName());
                                return;
                            }
                            Inventory top = player.getOpenInventory().getTopInventory();
                            if (holderOf(top) != holder) {
                                // 已切到别的菜单或关掉了
                                stopLingpuRefresh(player.getName());
                                return;
                            }
                            refreshLingpuMenu(top, player.getName(), holder.page);
                            player.updateInventory();
                        } catch (Throwable t) {
                            TT.log("[储物空间] 灵圃实时刷新异常（任务已停）: " + t);
                            stopLingpuRefresh(player.getName());
                        }
                    }, 20L, 20L));
        } catch (Throwable t) {
            TT.log("[储物空间] 灵圃实时刷新任务启动失败: " + t);
        }
    }

    /** 停掉某玩家的实时刷新任务。 */
    private static void stopLingpuRefresh(String name) {
        org.bukkit.scheduler.BukkitTask task = LINGPU_REFRESH.remove(name);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 已取消/已运行完都无所谓
            }
        }
    }

    private static void openFruitStorage(Player player, String playerName, int page) {
        MenuHolder holder = new MenuHolder(TYPE_FRUIT);
        holder.page = page;
        Inventory inv = buildFruitMenu(playerName, holder);
        if (inv == null) return;
        holder.inv = inv;
        openMenu(player, inv);
    }

    // ==================================================================
    // 界面构建
    // ==================================================================

    private static Inventory buildMainMenu(MenuHolder holder) {
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(Colors.c(TITLE_MAIN)));
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, glass("§0", "BLACK_STAINED_GLASS_PANE"));

        inv.setItem(FUDI_MAIN_SLOT, slimefunItemButton("TR_洞天福地", "§d§l洞天福地", List.of("§e点击进入")));

        for (CatDef cat : CATEGORIES) {
            Material mat = Material.matchMaterial(cat.icon());
            // 储物空间已是「桶」模型：带 NBT 的物品独立成桶完整保存，无需再警告
            List<String> lore = new ArrayList<>();
            lore.add(cat.lore());
            lore.add("§e点击进入");
            inv.setItem(cat.slot(), named(new ItemStack(mat == null ? Material.STONE : mat), cat.name(), lore));
        }
        return inv;
    }

    /** 分类储物界面；未开辟存档时返回 null。页码会被裁剪到合法范围并写回 holder。 */
    private static Inventory buildCategoryMenu(String playerName, MenuHolder holder) {
        Map<String, Object> data = load(playerName);
        if (data == null) return null;

        String cat = holder.cat;
        List<String> keys = sortedKeys(data, cat);
        int pageCount = Math.max(1, (keys.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        int page = Math.max(1, Math.min(holder.page, pageCount));
        holder.page = page;

        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(Colors.c(TITLE_CAT)));

        int start = (page - 1) * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx < keys.size()) {
                String key = keys.get(idx);
                inv.setItem(i, storageDisplayItem(cat, key, child(data, cat).get(key),
                        count(data, cat, key)));
            } else {
                inv.setItem(i, placeholder());
            }
        }

        for (int s = ITEMS_PER_PAGE; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }

        if (page > 1) inv.setItem(NAV_PREV_SLOT, glass("§c§l◀ 上一页", "ARROW"));
        else inv.setItem(NAV_PREV_SLOT, glass("§8§l◀ 返回分类", "ARROW"));

        if (page < pageCount) inv.setItem(NAV_NEXT_SLOT, glass("§a§l下一页 ▶", "ARROW"));
        else inv.setItem(NAV_NEXT_SLOT, glass("§8§l没有下一页", "BARRIER"));

        inv.setItem(BACK_SLOT, glass("§e§l返回分类", "BARRIER"));
        inv.setItem(INFO_SLOT, named(new ItemStack(Material.BOOK), "§b§l储物信息", List.of(
                "§7当前分类: " + cat,
                "§7第 " + page + " / " + pageCount + " 页",
                "§7物品数: " + keys.size())));
        inv.setItem(CLOSE_SLOT, glass("§c§l关闭", "BARRIER"));
        inv.setItem(AUTO_DEPOSIT_SLOT, autoDepositButton());
        return inv;
    }

    private static Inventory buildDongtianMenu(MenuHolder holder) {
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(Colors.c(TITLE_DONGTIAN)));
        // 前 45 格刻意留空（原脚本注释：不放任何占位玻璃）
        for (int i = 0; i < ITEMS_PER_PAGE; i++) inv.setItem(i, null);
        for (int s = ITEMS_PER_PAGE; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(FUDI_CATEGORY_SLOT, slimefunItemButton("TR_福地生珍", "§d§l福地生珍",
                List.of("§7进入福地生珍领取菜单", "§e点击进入")));
        inv.setItem(LINGPU_BUTTON_SLOT, slimefunItemButton("TR_灵圃", "{#87dea5}&l灵{#8dde87}&l圃",
                List.of("§7进入灵圃种植菜单", "§e点击进入")));
        inv.setItem(DONGTIAN_BACK_SLOT, glass("§e§l返回主菜单", "BARRIER"));
        return inv;
    }

    /**
     * 福地生珍界面。
     *
     * <p>进入时若「一轮都没刷」或「已全部处理且冷却走完」，就先刷新一轮并落盘。</p>
     */
    private static Inventory buildFudiMenu(String playerName, MenuHolder holder) {
        Map<String, Object> data = load(playerName);
        if (data == null) return null;

        long now = System.currentTimeMillis();
        List<Object> current = fudiCurrent(data);
        if (current.isEmpty() || (fudiAllClaimed(data) && now >= fudiNextRefresh(data))) {
            refreshFudiSelection(data);
            save(playerName, data);
            current = fudiCurrent(data);
        }

        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(Colors.c(TITLE_FUDI)));
        Map<String, Object> claimed = fudiClaimed(data);
        Map<String, Object> abandoned = fudiAbandoned(data);

        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            if (i >= current.size()) {
                inv.setItem(i, null);
                continue;
            }
            Object raw = current.get(i);
            if (!(raw instanceof Map<?, ?> p)) {
                inv.setItem(i, null);
                continue;
            }
            String key = fudiKey(p);
            if (isTruthy(abandoned.get(key))) {
                inv.setItem(i, named(new ItemStack(Material.BARRIER), "§7[已放弃奖励]", null));
                continue;
            }
            if (isTruthy(claimed.get(key))) {
                inv.setItem(i, named(new ItemStack(Material.BARRIER), "§7<已领取！>", null));
                continue;
            }
            String cat = str(p.get("cat"), "vanilla");
            String id = str(p.get("id"), "");
            ItemStack stack = buildCleanStack(cat, id);
            if (stack == null) stack = new ItemStack(Material.STONE);
            stack.setAmount(Math.max(1, (int) asLong(p.get("amount"))));

            List<String> lore = new ArrayList<>();
            lore.add("§e左键领取");
            Object rarity = p.get("rarity");
            if (rarity != null) lore.add("§7珍稀度: " + rarity);

            String name = str(p.get("name"), null);
            inv.setItem(i, named(stack, name != null ? name : displayNameOf(cat, id), lore));
        }

        for (int s = ITEMS_PER_PAGE; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(FUDI_CLAIM_ALL_SLOT, glass("§a§l[一键领取全部]", "CHEST"));
        inv.setItem(FUDI_BACK_SLOT, glass("§e§l返回洞天福地", "BARRIER"));
        inv.setItem(ABANDON_SLOT, glass("§c§l放弃领取本次奖励", "BARRIER"));
        inv.setItem(FUDI_REFRESH_SLOT, named(new ItemStack(Material.PAPER),
                "§b§l使用福地生珍刷新券清除冷却", List.of(
                        "§7点击消耗1张刷新券，立即刷新福地生珍",
                        "§7仅在进入冷却时可以使用")));
        return inv;
    }

    /**
     * 灵圃界面。
     *
     * <p>构建前先做两件维护：给鸿蒙神圃推进倒计时并自动收获、给普通灵圃推进事件。
     * 这保证玩家一打开界面看到的进度就是最新的。</p>
     */
    private static Inventory buildLingpuMenu(String playerName, MenuHolder holder) {
        Map<String, Object> data = load(playerName);
        if (data == null) return null;

        // 鸿蒙神圃：推进倒计时，成熟即自动收获
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (String key : new ArrayList<>(plots(data).keySet())) {
            LingpuPlot plot = LingpuPlot.fromJson(plots(data).get(key));
            if (plot == null || !plot.planted() || !plot.ultimate()) continue;
            long last = plot.lastUpdate > 0 ? plot.lastUpdate : now;
            plot.remainingMs = Math.max(0L, plot.remainingMs - (now - last));
            plot.lastUpdate = now;
            if (plot.mature() && harvest(data, plot)) changed = true;
            putPlot(data, parseInt(key, -1), plot);
        }
        if (changed) save(playerName, data);

        // 普通灵圃：推进事件与倒计时
        boolean eventChanged = false;
        for (String key : new ArrayList<>(plots(data).keySet())) {
            LingpuPlot plot = LingpuPlot.fromJson(plots(data).get(key));
            if (plot == null || !plot.planted() || plot.ultimate()) continue;
            plot.tick();
            putPlot(data, parseInt(key, -1), plot);
            eventChanged = true;
        }
        if (eventChanged) save(playerName, data);

        int pageCount = LingpuPlot.pageCount(plots(data).keySet());
        int page = Math.max(0, Math.min(holder.page, pageCount - 1));
        holder.page = page;

        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(Colors.c(TITLE_LINGPU)));
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            LingpuPlot plot = plotAt(data, page * ITEMS_PER_PAGE + i);
            inv.setItem(i, plot == null
                    ? glass("§7[灵圃未解锁，请将灵圃拖在上面点击]", "GRAY_STAINED_GLASS_PANE")
                    : buildPlotItem(plot));
        }
        for (int s = ITEMS_PER_PAGE; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(LINGPU_HARVEST_ALL_SLOT, glass("§a§l[一键收获成熟灵植]", "DIAMOND_HOE"));
        inv.setItem(LINGPU_BACK_SLOT, glass("§e§l返回洞天福地", "BARRIER"));
        inv.setItem(LINGPU_FRUIT_SLOT, glass("§b§l灵植果实储物空间", "CHEST"));
        if (page > 0) inv.setItem(LINGPU_PREV_SLOT, glass("§c§l上一页", "ARROW"));
        if (page < pageCount - 1) inv.setItem(LINGPU_NEXT_SLOT, glass("§a§l下一页", "ARROW"));
        inv.setItem(LINGPU_INFO_SLOT, named(new ItemStack(Material.PAPER), "§b§l灵植事件介绍", List.of(
                "§7浇水：水桶点击，不消耗",
                "§7翻耕：任意锄头点击，耐久-1~10",
                "§7施肥：骨粉点击，消耗骨粉",
                "§7事件未完成前，成熟时间不会开始计时")));
        return inv;
    }

    /** 果实仓库界面。 */
    private static Inventory buildFruitMenu(String playerName, MenuHolder holder) {
        Map<String, Object> data = load(playerName);
        if (data == null) return null;

        Map<String, Object> fruits = fruits(data);
        List<String> keys = new ArrayList<>(new TreeSet<>(fruits.keySet()));
        int pageCount = Math.max(1, (keys.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        int page = Math.max(0, Math.min(holder.page, pageCount - 1));
        holder.page = page;

        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, legacy(TITLE_FRUIT));
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = page * ITEMS_PER_PAGE + i;
            if (idx >= keys.size()) {
                inv.setItem(i, null);
                continue;
            }
            String fid = keys.get(idx);
            ItemStack stack = buildCleanStack("slimefun", fid);
            if (stack == null) {
                inv.setItem(i, null);
                continue;
            }
            stack.setAmount(1);
            inv.setItem(i, named(stack, null, List.of(
                    "§7数量: §f" + asLong(fruits.get(fid)),
                    "§e左键取出1个",
                    "§e右键取出64个",
                    "§e蹲下右键取出至填满背包")));
        }
        for (int s = ITEMS_PER_PAGE; s < MENU_SIZE; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(FRUIT_BACK_SLOT, glass("§e§l返回灵圃", "BARRIER"));
        if (page > 0) inv.setItem(FRUIT_PREV_SLOT, glass("§c§l上一页", "ARROW"));
        if (page < pageCount - 1) inv.setItem(FRUIT_NEXT_SLOT, glass("§a§l下一页", "ARROW"));
        return inv;
    }

    /** 一块地的图标与进度描述。 */
    private static ItemStack buildPlotItem(LingpuPlot plot) {
        LingpuPlot.Soil soil = plot.soil();
        ItemStack base = buildCleanStack("slimefun", plot.soilId);
        if (base == null) base = new ItemStack(Material.DIRT);
        base.setAmount(1);

        List<String> lore = new ArrayList<>();
        if (plot.planted()) {
            String fruitId = LingpuPlot.seedToFruit(plot.seedId);
            lore.add("§7目前种植: §a" + displayNameOf("slimefun", plot.seedId));
            long remainSec = plot.remainingSeconds();
            if (plot.mature() && plot.eventsDone()) {
                lore.add("§a已成熟！点击收获");
            } else if (plot.mature()) {
                lore.add("§6已到成熟时间！还需完成事件才能收获");
                lore.add("§7目前需要: §e" + plot.pendingEventsText());
            } else {
                lore.add(plot.paused
                        ? "§7成熟还需: §b" + remainSec + "秒 §7(事件暂停)"
                        : "§7成熟还需: §b" + remainSec + "秒");
                if (!plot.eventsDone()) lore.add("§7目前需要: §e" + plot.pendingEventsText());
            }
            lore.add("§7收获: §f" + displayNameOf("slimefun", fruitId));
            lore.add("§7可用聚灵散催熟（每个减少10秒）");
        } else {
            lore.add("§7未种植");
            lore.add("§7将灵植种子拖到此处种植");
        }
        lore.add("§7右键直接取下灵圃（种植进度不会保留）");
        return named(base, soil == null ? String.valueOf(plot.soilId) : soil.name(), lore);
    }

    // ==================================================================
    // 点击分发
    // ==================================================================

    private static MenuHolder holderOf(Inventory inv) {
        if (inv == null) return null;
        InventoryHolder h = inv.getHolder();
        return (h instanceof MenuHolder mh) ? mh : null;
    }

    private static void handleClick(InventoryClickEvent event, Inventory top, MenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String playerName = player.getName();
        int slot = event.getRawSlot();
        Inventory clicked = event.getClickedInventory();

        boolean nonStorage = TYPE_MAIN.equals(holder.type) || TYPE_DONGTIAN.equals(holder.type)
                || TYPE_FUDI.equals(holder.type) || TYPE_LINGPU.equals(holder.type)
                || TYPE_FRUIT.equals(holder.type);

        // 点击的不是顶部菜单（自己的背包，或 GUI 外的空白）：只处理「非存储型界面的 shift 塞入」。
        // 注意：此处刻意不拦分类界面的 shift —— 见类注释「已知风险」。
        if (clicked != top) {
            if (event.isShiftClick() && nonStorage) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        try {
            player.playSound(player.getLocation(), "ui.button.click", 0.8f, 1.0f);
        } catch (Throwable ignored) {
            // 音效失败不影响功能
        }

        switch (holder.type) {
            case TYPE_MAIN -> handleMainClick(player, slot);
            case TYPE_CAT -> handleCategoryClick(player, event, top, holder, slot);
            case TYPE_DONGTIAN -> handleDongtianClick(player, slot);
            case TYPE_FUDI -> handleFudiClick(player, event, top, holder);
            case TYPE_LINGPU -> handleLingpuClick(player, event, top, holder, slot);
            case TYPE_FRUIT -> handleFruitClick(player, event, top, holder, slot);
            default -> {
                // 未知类型：已取消事件，什么都不做
            }
        }
    }

    // ---- 主界面 ----

    private static void handleMainClick(Player player, int slot) {
        if (slot == FUDI_MAIN_SLOT) {
            openDongtian(player);
            return;
        }
        for (CatDef cat : CATEGORIES) {
            if (slot == cat.slot()) {
                openCategory(player, player.getName(), cat.id(), 1);
                return;
            }
        }
    }

    // ---- 分类储物 ----

    private static void handleCategoryClick(Player player, InventoryClickEvent event,
                                            Inventory top, MenuHolder holder, int slot) {
        String playerName = player.getName();
        String cat = holder.cat;
        Map<String, Object> data = load(playerName);
        if (data == null) {
            player.sendMessage("§c尚未开辟储物空间！");
            return;
        }

        if (slot == BACK_SLOT) {
            openMain(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == NAV_PREV_SLOT) {
            if (holder.page > 1) openCategory(player, playerName, cat, holder.page - 1);
            else openMain(player);
            return;
        }
        if (slot == NAV_NEXT_SLOT) {
            List<String> keys = sortedKeys(data, cat);
            int pageCount = Math.max(1, (keys.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
            if (holder.page < pageCount) openCategory(player, playerName, cat, holder.page + 1);
            return;
        }
        if (slot == AUTO_DEPOSIT_SLOT) {
            ClickType click = event.getClick();
            boolean leftClick = (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT);
            performAutoDeposit(player, data, leftClick);
            save(playerName, data);
            openCategory(player, playerName, cat, holder.page);
            player.updateInventory();
            return;
        }
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return;

        // 光标上有东西 → 存入
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            if (depositCursorItem(player, data, cat)) {
                save(playerName, data);
                openCategory(player, playerName, cat, holder.page);
                player.updateInventory();
            }
            return;
        }

        // 空手 → 取出
        List<String> keys = sortedKeys(data, cat);
        int idx = (holder.page - 1) * ITEMS_PER_PAGE + slot;
        if (idx >= keys.size()) return;
        String id = keys.get(idx);
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT) {
            withdrawItem(player, data, cat, id, FILL_AMOUNT);
        } else if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            withdrawItem(player, data, cat, id, MAX_STACK);
        } else {
            withdrawItem(player, data, cat, id, 1);
        }
        save(playerName, data);
        openCategory(player, playerName, cat, holder.page);
        player.updateInventory();
    }

    // ---- 洞天福地 ----

    private static void handleDongtianClick(Player player, int slot) {
        if (slot == FUDI_CATEGORY_SLOT) {
            openFudi(player, player.getName());
            return;
        }
        if (slot == LINGPU_BUTTON_SLOT) {
            openLingpu(player, player.getName(), 0);
            return;
        }
        if (slot == DONGTIAN_BACK_SLOT) {
            openMain(player);
        }
    }

    // ---- 福地生珍 ----

    private static void handleFudiClick(Player player, InventoryClickEvent event,
                                        Inventory top, MenuHolder holder) {
        String playerName = player.getName();
        int slot = event.getRawSlot();
        Map<String, Object> data = load(playerName);
        if (data == null) {
            player.sendMessage("§c尚未开辟储物空间！");
            return;
        }

        if (slot == FUDI_BACK_SLOT) {
            openDongtian(player);
            return;
        }

        if (slot == FUDI_REFRESH_SLOT) {
            long now = System.currentTimeMillis();
            if (fudiNextRefresh(data) <= now) {
                player.sendMessage("§c还未进入冷却！");
                playSound(player, "entity.wither.shoot", 1.0f, 1.0f);
                return;
            }
            if (countInInventory(player, ITEM_FUDI_REFRESH_TICKET) < 1) {
                player.sendMessage("§c背包中没有福地生珍刷新券！");
                playSound(player, "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            takeFromInventory(player, ITEM_FUDI_REFRESH_TICKET, 1);
            refreshFudiSelection(data);
            save(playerName, data);
            refreshFudiMenu(top, playerName);
            player.updateInventory();
            player.sendMessage("§a已消耗刷新券，福地生珍已刷新！");
            playSound(player, "entity.ender_dragon.growl", 1.0f, 1.0f);
            return;
        }

        if (slot == ABANDON_SLOT) {
            long now = System.currentTimeMillis();
            Map<String, Object> claimed = fudiClaimed(data);
            Map<String, Object> abandoned = fudiAbandoned(data);
            for (Object p : fudiCurrent(data)) {
                String key = fudiKey(p);
                if (!isTruthy(claimed.get(key))) abandoned.put(key, Boolean.TRUE);
            }
            // 已因「领取完」而冷却中的，放弃不会把冷却重置得更早
            if (fudiAllClaimed(data) && fudiNextRefresh(data) > now) {
                player.sendMessage("§c已在冷却中，放弃不会重置冷却时间。");
            } else {
                fudi(data).put("nextRefreshTime", now + FUDI_COOLDOWN_MS);
                player.sendMessage("§c已放弃本次奖励，进入冷却。");
            }
            save(playerName, data);
            refreshFudiMenu(top, playerName);
            player.updateInventory();
            return;
        }

        if (slot == FUDI_CLAIM_ALL_SLOT) {
            long now = System.currentTimeMillis();
            if (fudiAllClaimed(data)) {
                if (fudiNextRefresh(data) > now) {
                    long wait = (fudiNextRefresh(data) - now + 999L) / 1000L;
                    player.sendMessage("§c冷却中，还需等待 §b" + wait + " §c秒才能再次领取。");
                    return;
                }
                refreshFudiSelection(data);
                save(playerName, data);
                player.sendMessage("§a福地生珍已刷新！");
                openFudi(player, playerName);
                player.updateInventory();
                return;
            }
            int claimedCount = claimAllFudi(player, data);
            if (claimedCount > 0) {
                maybeSetFudiCooldown(data);
                save(playerName, data);
                player.sendMessage("§a已一键领取 §b" + claimedCount + " §a个生成物！");
            } else {
                player.sendMessage("§7没有可领取的生成物。");
            }
            openFudi(player, playerName);
            player.updateInventory();
            return;
        }

        if (slot >= 0 && slot < ITEMS_PER_PAGE && slot < fudiCurrent(data).size()) {
            ClickType click = event.getClick();
            if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT) return;

            Object p = fudiCurrent(data).get(slot);
            String key = fudiKey(p);
            if (isTruthy(fudiAbandoned(data).get(key))) {
                player.sendMessage("§7已放弃！");
                playSound(player, "entity.villager.no", 1.0f, 1.0f);
                return;
            }
            if (claimFudiItem(player, data, p)) {
                maybeSetFudiCooldown(data);
                save(playerName, data);
                player.sendMessage("§a领取成功！");
            } else {
                player.sendMessage("§7该生成物已领取过了。");
            }
            openFudi(player, playerName);
            player.updateInventory();
        }
    }

    // ---- 灵圃 ----

    private static void handleLingpuClick(Player player, InventoryClickEvent event,
                                          Inventory top, MenuHolder holder, int slot) {
        String playerName = player.getName();
        // 先原地刷新一次进度（原脚本同样如此），再取档 —— 顺序反了的话，
        // 后面点击保存的会是刷新推进之前的旧倒计时，进度会被回拨。
        refreshLingpuMenu(top, playerName, holder.page);
        Map<String, Object> data = load(playerName);
        if (data == null) {
            player.sendMessage("§c尚未开辟储物空间！");
            return;
        }

        if (slot == LINGPU_BACK_SLOT) {
            openDongtian(player);
            return;
        }
        if (slot == LINGPU_FRUIT_SLOT) {
            openFruitStorage(player, playerName, 0);
            return;
        }
        if (slot == LINGPU_HARVEST_ALL_SLOT) {
            int harvested = 0;
            int startIdx = holder.page * ITEMS_PER_PAGE;
            for (int i = 0; i < ITEMS_PER_PAGE; i++) {
                LingpuPlot plot = plotAt(data, startIdx + i);
                if (plot != null && plot.planted() && plot.harvestable() && harvest(data, plot)) {
                    putPlot(data, startIdx + i, plot);
                    harvested++;
                }
            }
            if (harvested > 0) {
                save(playerName, data);
                player.sendMessage("§a收获成功！共收获 §b" + harvested + " §a株灵植。");
                playSound(player, "entity.player.levelup", 0.8f, 1.2f);
            } else {
                player.sendMessage("§7当前页没有可收获的成熟灵植。");
                playSound(player, "block.note_block.pling", 0.6f, 0.8f);
            }
            openLingpu(player, playerName, holder.page);
            player.updateInventory();
            return;
        }
        if (slot == LINGPU_PREV_SLOT && holder.page > 0) {
            openLingpu(player, playerName, holder.page - 1);
            return;
        }
        if (slot == LINGPU_NEXT_SLOT && holder.page < LingpuPlot.pageCount(plots(data).keySet()) - 1) {
            openLingpu(player, playerName, holder.page + 1);
            return;
        }
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return;

        int index = holder.page * ITEMS_PER_PAGE + slot;
        LingpuPlot plot = plotAt(data, index);
        ItemStack cursor = player.getOpenInventory().getCursor();

        // ---- 空手 ----
        if (cursor == null || cursor.getType().isAir()) {
            ClickType click = event.getClick();

            // 右键（含蹲下右键）：直接取下灵圃，返还灵圃与已种下的种子。
            // 返还的是按 id 现造的干净物品（buildCleanStack），不携带任何灵植数据等附加 NBT；
            // 种植进度（农事事件 / 倒计时）随取下一并丢弃，重新放回即从零开始。
            if (plot != null && (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)) {
                giveOrDrop(player, buildCleanStack("slimefun", plot.soilId));
                if (plot.planted()) giveOrDrop(player, buildCleanStack("slimefun", plot.seedId));
                removePlot(data, index);
                save(playerName, data);
                top.setItem(slot, glass("§7[灵圃未解锁，请将灵圃拖在上面点击]", "GRAY_STAINED_GLASS_PANE"));
                playSound(player, "entity.item.pickup", 0.8f, 1.0f);
                player.updateInventory();
                return;
            }
            // 其余空手点击（左键）：收获成熟灵植
            if (plot != null && plot.planted() && plot.harvestable() && harvest(data, plot)) {
                putPlot(data, index, plot);
                save(playerName, data);
                player.sendMessage("§a收获成功！果实已存入灵植果实储物空间。");
                playSound(player, "entity.player.levelup", 0.8f, 1.2f);
                openLingpu(player, playerName, holder.page);
                player.updateInventory();
            }
            return;
        }

        // ---- 光标上有东西 ----

        // 农事事件材料是原版物品（水桶 / 锄头 / 骨粉），lingpuItemId（= slimefunIdOf）
        // 对它们返回 null —— 必须在 id 判空之前处理，否则「灵植事件介绍」里的
        // 浇水 / 翻耕 / 施肥三类操作永远走不到（实测 bug：事件无法按介绍复现）。
        if (plot != null && plot.planted()) {
            Material type = cursor.getType();
            String evType = null;
            if (type == Material.WATER_BUCKET) evType = LingpuPlot.EVENT_WATER;
            else if (type.name().contains("HOE")) evType = LingpuPlot.EVENT_HOE;
            else if (type == Material.BONE_MEAL) evType = LingpuPlot.EVENT_BONE;
            if (evType != null) {
                handleFarmEvent(player, playerName, data, plot, index, slot, top, cursor, evType);
                return;
            }
        }

        String cursorId = lingpuItemId(cursor);
        if (cursorId == null) return;

        // 放置灵圃：原地更新不重开菜单，方便连续放置
        if (LingpuPlot.knownSoil(cursorId)) {
            if (plot == null) {
                LingpuPlot fresh = new LingpuPlot();
                fresh.soilId = cursorId;
                fresh.lastUpdate = System.currentTimeMillis();
                putPlot(data, index, fresh);
                consumeOneFromCursor(player, cursor);
                save(playerName, data);
                top.setItem(slot, buildPlotItem(fresh));
                playSound(player, "block.note_block.pling", 0.8f, 1.3f);
                player.updateInventory();
            }
            return;
        }

        // 种下种子
        if (plot != null && !plot.planted() && cursorId.contains("_SEED")) {
            if (plot.ultimate()) {
                plot.plantUltimateSeed(cursorId);
                // 加入自动收获名单（原脚本写进 globalThis.LINGPU_AUTO_PLAYERS）
                AUTO_PLAYERS.add(playerName);
            } else {
                plot.plantNormalSeed(cursorId);
            }
            putPlot(data, index, plot);
            consumeOneFromCursor(player, cursor);
            save(playerName, data);
            top.setItem(slot, buildPlotItem(plot));
            playSound(player, "block.note_block.pling", 0.8f, 1.6f);
            player.updateInventory();
            return;
        }

        // 聚灵散催熟
        if (ITEM_JLS.equals(cursorId) && plot != null && plot.planted()) {
            if (plot.speedUp(10_000L)) {
                putPlot(data, index, plot);
                consumeOneFromCursor(player, cursor);
                save(playerName, data);
                top.setItem(slot, buildPlotItem(plot));
                playSound(player, "block.note_block.pling", 0.8f, 1.8f);
                player.updateInventory();
            }
            return;
        }
    }

    /**
     * 处理一次农事事件（浇水 / 翻耕 / 施肥）。
     *
     * <p>三种事件的收尾差异，逐字对应原脚本（即「灵植事件介绍」承诺的行为）：</p>
     * <ul>
     *   <li>浇水 —— 水桶点击，<b>不消耗</b>；</li>
     *   <li>翻耕 —— 任意锄头点击，耐久 -1~10（不消耗物品）；</li>
     *   <li>施肥 —— 骨粉点击，<b>消耗 1 份</b>。</li>
     * </ul>
     * <p>没有对应待办时 {@link LingpuPlot#consumeEvent} 返回 false，这里直接返回，
     * 不扣玩家手上的工具 / 材料。</p>
     */
    private static void handleFarmEvent(Player player, String playerName, Map<String, Object> data,
                                        LingpuPlot plot, int index, int slot, Inventory top,
                                        ItemStack cursor, String evType) {
        if (!plot.consumeEvent(evType)) return;

        if (LingpuPlot.EVENT_WATER.equals(evType)) {
            playSound(player, "entity.player.splash", 0.8f, 1.2f);
        } else if (LingpuPlot.EVENT_HOE.equals(evType)) {
            damageHoe(cursor);
            playSound(player, "block.grass.break", 0.8f, 1.0f);
        } else {
            consumeOneFromCursor(player, cursor);
            playSound(player, "entity.experience_orb.pickup", 0.8f, 1.4f);
        }
        putPlot(data, index, plot);
        save(playerName, data);
        // 原地刷新，不重开菜单 —— 这样光标上的工具/材料还在，可以连续操作
        top.setItem(slot, buildPlotItem(plot));
        player.updateInventory();
    }

    // ---- 果实仓库 ----

    private static void handleFruitClick(Player player, InventoryClickEvent event,
                                         Inventory top, MenuHolder holder, int slot) {
        String playerName = player.getName();
        Map<String, Object> data = load(playerName);
        if (data == null) {
            player.sendMessage("§c尚未开辟储物空间！");
            return;
        }
        Map<String, Object> fruits = fruits(data);

        if (slot == FRUIT_BACK_SLOT) {
            openLingpu(player, playerName, 0);
            return;
        }
        if (slot == FRUIT_PREV_SLOT && holder.page > 0) {
            openFruitStorage(player, playerName, holder.page - 1);
            return;
        }
        if (slot == FRUIT_NEXT_SLOT) {
            List<String> keys = new ArrayList<>(new TreeSet<>(fruits.keySet()));
            int pageCount = Math.max(1, (keys.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
            if (holder.page < pageCount - 1) openFruitStorage(player, playerName, holder.page + 1);
            return;
        }
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return;

        List<String> keys = new ArrayList<>(new TreeSet<>(fruits.keySet()));
        int idx = holder.page * ITEMS_PER_PAGE + slot;
        if (idx >= keys.size()) return;

        String fid = keys.get(idx);
        long have = asLong(fruits.get(fid));
        if (have <= 0) return;

        ItemStack proto = buildCleanStack("slimefun", fid);
        int maxStack = proto == null ? MAX_STACK : Math.max(1, proto.getMaxStackSize());

        ClickType click = event.getClick();
        long take;
        if (click == ClickType.SHIFT_RIGHT) {
            take = Math.min(have, inventorySpaceFor(player, proto, maxStack));
        } else if (click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT) {
            take = Math.min(MAX_STACK, have);
        } else {
            take = Math.min(1, have);
        }
        if (take <= 0) {
            player.sendMessage("§c背包已满，无法取出！");
            return;
        }

        ItemStack give = buildCleanStack("slimefun", fid);
        if (give == null) return;
        give.setAmount((int) Math.min(Integer.MAX_VALUE, take));
        giveOrDrop(player, give);
        fruits.put(fid, have - take);
        if (have - take <= 0) fruits.remove(fid);
        save(playerName, data);
        playSound(player, "entity.item.pickup", 0.8f, 1.2f);
        openFruitStorage(player, playerName, holder.page);
        player.updateInventory();
    }

    /** 原地刷新福地生珍界面。 */
    private static void refreshFudiMenu(Inventory inv, String playerName) {
        MenuHolder holder = holderOf(inv);
        if (holder == null) return;
        Inventory fresh = buildFudiMenu(playerName, holder);
        if (fresh == null) return;
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, fresh.getItem(i));
    }

    /** 原地刷新灵圃界面（只更新前 45 格与存档，不动功能栏，避免光标被顶掉）。 */
    private static void refreshLingpuMenu(Inventory inv, String playerName, int page) {
        Map<String, Object> data = load(playerName);
        if (data == null) return;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int index = Math.max(0, page) * ITEMS_PER_PAGE + i;
            LingpuPlot plot = plotAt(data, index);
            if (plot == null) {
                inv.setItem(i, glass("§7[灵圃未解锁，请将灵圃拖在上面点击]", "GRAY_STAINED_GLASS_PANE"));
                continue;
            }
            long now = System.currentTimeMillis();
            if (plot.planted() && plot.ultimate()) {
                long last = plot.lastUpdate > 0 ? plot.lastUpdate : now;
                plot.remainingMs = Math.max(0L, plot.remainingMs - (now - last));
                plot.lastUpdate = now;
                if (plot.mature()) harvest(data, plot);
                putPlot(data, index, plot);
            } else if (plot.planted()) {
                plot.tick();
                putPlot(data, index, plot);
            }
            inv.setItem(i, buildPlotItem(plot));
        }
        save(playerName, data);
    }

    // ==================================================================
    // 存取逻辑
    // ==================================================================

    /** {@code withdrawItem} 的特殊数量：尽量填满背包。 */
    private static final int FILL_AMOUNT = -1;

    /** 把光标上的物品存入分类容器（按物品桶存，完整数据一起留下）。 */
    private static boolean depositCursorItem(Player player, Map<String, Object> data, String cat) {
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (cursor == null || cursor.getType().isAir()) return false;

        ItemCat info = classify(cursor);
        if (!cat.equals(info.cat())) {
            player.sendMessage("§c这个物品不属于当前分类！");
            return false;
        }
        addStored(data, cursor, cursor.getAmount());
        player.getOpenInventory().setCursor(null);
        playSound(player, "entity.item.pickup", 1.0f, 1.2f);
        return true;
    }

    /**
     * 从分类容器取出物品。
     *
     * @param amount 取出数量；{@link #FILL_AMOUNT} 表示「尽量填满背包」
     */
    private static void withdrawItem(Player player, Map<String, Object> data,
                                    String cat, String key, int amount) {
        long have = count(data, cat, key);
        if (have <= 0) return;

        // 带数据的桶（附魔/改名/损伤/容器内容…）按存下来的完整物品还原
        ItemStack proto = protoOf(cat, key, child(data, cat).get(key));
        if (proto == null) return;
        int maxStack = Math.max(1, proto.getMaxStackSize());

        long take;
        if (amount == FILL_AMOUNT) {
            take = Math.min(have, inventorySpaceFor(player, proto, maxStack));
        } else {
            take = Math.min(have, amount);
        }
        if (take <= 0) {
            player.sendMessage("§c背包已满，无法取出！");
            return;
        }

        ItemStack give = protoOf(cat, key, child(data, cat).get(key));
        if (give == null) return;
        give.setAmount((int) Math.min(Integer.MAX_VALUE, take));

        // 背包塞不下的部分从取出量里扣掉（原脚本的做法：addItem 返回的残留量要减回去）
        var leftover = player.getInventory().addItem(give);
        long leftoverAmount = 0L;
        for (ItemStack rest : leftover.values()) {
            if (rest != null) leftoverAmount += rest.getAmount();
        }
        long actuallyGiven = take - leftoverAmount;
        if (actuallyGiven <= 0) {
            // 一件都没塞进去：把残留物还给玩家脚下，避免凭空消失
            for (ItemStack rest : leftover.values()) {
                if (rest != null) dropAt(player, rest);
            }
            player.sendMessage("§c背包已满，无法取出！");
            return;
        }

        setCount(data, cat, key, have - actuallyGiven);
        playSound(player, "entity.item.pickup", 1.0f, 1.0f);
    }

    /**
     * 自动归位：把背包里「已存储过」的物品收进储物空间。
     *
     * @param leftClick true → 每种最多存 64；false → 有多少存多少
     */
    private static void performAutoDeposit(Player player, Map<String, Object> data, boolean leftClick) {
        PlayerInventory inv = player.getInventory();

        // 已存储过的物品桶 → 本次最多还能收多少
        Map<String, Long> wanted = new LinkedHashMap<>();
        for (String cat : new String[]{"vanilla", "slimefun"}) {
            for (String key : child(data, cat).keySet()) {
                if (count(data, cat, key) <= 0) continue;
                wanted.put(cat + "|" + key, leftClick ? 64L : 999_999L);
            }
        }

        long deposited = 0L;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            ItemCat info = classify(item);
            // 先按完整数据算桶键：带 NBT 的物品不会和白板同 id 的桶混在一起
            String bucket = bucketKey(item, info.cat(), info.id());
            String mapKey = info.cat() + "|" + bucket;
            Long remain = wanted.get(mapKey);
            if (remain == null || remain <= 0) continue;

            long take = Math.min(item.getAmount(), remain);
            if (take <= 0) continue;

            addStored(data, item, take);
            int newAmount = (int) (item.getAmount() - take);
            if (newAmount <= 0) inv.setItem(slot, null);
            else item.setAmount(newAmount);

            wanted.put(mapKey, remain - take);
            deposited += take;
        }

        if (deposited > 0) {
            playSound(player, "entity.item.pickup", 1.0f, 1.2f);
            player.sendMessage("§a自动归位完成，共存入 §b" + deposited + " §a个物品。");
        } else {
            player.sendMessage("§7背包中没有可归位的已存储物品。");
        }
    }

    // ==================================================================
    // 福地生珍：抽取与领取
    // ==================================================================

    /**
     * 刷新一轮福地生珍产出。
     *
     * <p>规则（逐字保留原脚本）：必出 5 件<b>互不相同</b>的常见产出；再额外加 1~10 件，
     * 每件的档位是「50% 稀有 / 35% 史诗 / 15% 传说」。两段都用「去重计数」的循环，
     * 且都带次数上限，避免池子抽空时死循环。</p>
     */
    private static void refreshFudiSelection(Map<String, Object> data) {
        List<Object> selected = new ArrayList<>();
        Set<String> used = new HashSet<>();

        int commonAdded = 0;
        for (int guard = 0; commonAdded < 5 && guard < 100; guard++) {
            if (addUniqueProduct(selected, used, FudiPool.product(FudiPool.COMMON), FudiPool.COMMON)) {
                commonAdded++;
            }
        }

        int extra = 1 + ThreadLocalRandom.current().nextInt(10);
        int extraAdded = 0;
        for (int guard = 0; extraAdded < extra && guard < 200; guard++) {
            double r = ThreadLocalRandom.current().nextDouble();
            String rarity = r < 0.5 ? FudiPool.RARE
                    : (r < 0.85 ? FudiPool.EPIC : FudiPool.LEGENDARY);
            if (addUniqueProduct(selected, used, FudiPool.product(rarity), rarity)) extraAdded++;
        }

        // 整块替换 fudi（原脚本就是 data.fudi = {...}，claimed/abandoned 一并清空）
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("current", selected);
        fresh.put("claimed", new LinkedHashMap<String, Object>());
        fresh.put("abandoned", new LinkedHashMap<String, Object>());
        fresh.put("nextRefreshTime", 0);
        data.put("fudi", fresh);
    }

    private static boolean addUniqueProduct(List<Object> selected, Set<String> used,
                                            FudiPool.Product p, String rarity) {
        String key = p.cat() + "|" + p.id();
        if (!used.add(key)) return false;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("cat", p.cat());
        rec.put("id", p.id());
        rec.put("name", p.name());
        rec.put("amount", FudiPool.amountFor(rarity));
        rec.put("rarity", rarity);
        selected.add(rec);
        return true;
    }

    /** 领取一件产出。 */
    private static boolean claimFudiItem(Player player, Map<String, Object> data, Object product) {
        if (!(product instanceof Map<?, ?> p)) return false;
        String key = fudiKey(p);
        if (isTruthy(fudiClaimed(data).get(key))) return false;

        String cat = str(p.get("cat"), "vanilla");
        String id = str(p.get("id"), "");
        ItemStack stack = buildCleanStack(cat, id);
        if (stack == null) return false;
        stack.setAmount(Math.max(1, (int) asLong(p.get("amount"))));
        giveOrDrop(player, stack);
        fudiClaimed(data).put(key, Boolean.TRUE);
        return true;
    }

    /** 一键领取全部未处理的产出，返回实际领取件数。 */
    private static int claimAllFudi(Player player, Map<String, Object> data) {
        int claimed = 0;
        Map<String, Object> abandoned = fudiAbandoned(data);
        for (Object p : new ArrayList<>(fudiCurrent(data))) {
            String key = fudiKey(p);
            if (isTruthy(abandoned.get(key))) continue;
            if (claimFudiItem(player, data, p)) claimed++;
        }
        return claimed;
    }

    /** 本轮产出全部处理完后进入 60 秒冷却。 */
    private static void maybeSetFudiCooldown(Map<String, Object> data) {
        if (fudiAllClaimed(data) && fudiNextRefresh(data) == 0L) {
            fudi(data).put("nextRefreshTime", System.currentTimeMillis() + FUDI_COOLDOWN_MS);
        }
    }

    // ==================================================================
    // 灵圃：收获与自动收获
    // ==================================================================

    /**
     * 收获一块地。
     *
     * <p>鸿蒙神圃收获后<b>不</b>清空种子，而是把倒计时重置为基准时长直接进入下一轮
     * （这是它「永动」的机制）；普通灵圃收获后清空种子与事件。</p>
     *
     * @return 是否真的收到东西
     */
    private static boolean harvest(Map<String, Object> data, LingpuPlot plot) {
        if (!plot.planted() || !plot.harvestable()) return false;
        LingpuPlot.Soil soil = plot.soil();
        if (soil == null) return false;

        int span = soil.max() - soil.min() + 1;
        int amount = soil.min() + (span <= 1 ? 0 : ThreadLocalRandom.current().nextInt(span));
        String fruitId = LingpuPlot.seedToFruit(plot.seedId);
        if (fruitId == null) return false;

        Map<String, Object> fruits = fruits(data);
        fruits.put(fruitId, asLong(fruits.get(fruitId)) + amount);

        if (soil.ultimate()) {
            plot.remainingMs = soil.duration();
            plot.lastUpdate = System.currentTimeMillis();
            plot.paused = false;
            return true;
        }
        plot.seedId = null;
        plot.water = 0;
        plot.hoe = 0;
        plot.bone = 0;
        plot.remainingMs = 0;
        plot.paused = false;
        return true;
    }

    /** 自动收获任务的每周期逻辑（原脚本 {@code runTaskTimer(..., 0, 40)} 的 runnable）。 */
    private static void autoHarvestTick() {
        try {
            if (!autoInited) {
                autoInited = true;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (hasUltimatePlot(p.getName())) AUTO_PLAYERS.add(p.getName());
                }
            }
            for (String name : new ArrayList<>(AUTO_PLAYERS)) {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || !p.isOnline()) {
                    AUTO_PLAYERS.remove(name);
                    continue;
                }
                if (!processAutoHarvest(p)) AUTO_PLAYERS.remove(name);
            }
        } catch (Throwable ignored) {
            // 定时任务不能把异常抛回调度器
        }
    }

    /**
     * 推进某玩家的鸿蒙神圃并自动收获。
     *
     * @return 该玩家是否仍有鸿蒙神圃（false 时调用方把它移出名单）
     */
    private static boolean processAutoHarvest(Player player) {
        String playerName = player.getName();
        Map<String, Object> data = load(playerName);
        if (data == null) return false;

        boolean has = false;
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (String key : new ArrayList<>(plots(data).keySet())) {
            int index = parseInt(key, -1);
            if (index < 0) continue;
            LingpuPlot plot = LingpuPlot.fromJson(plots(data).get(key));
            if (plot == null || !plot.planted() || !plot.ultimate()) continue;
            has = true;
            long last = plot.lastUpdate > 0 ? plot.lastUpdate : now;
            plot.remainingMs = Math.max(0L, plot.remainingMs - (now - last));
            plot.lastUpdate = now;
            if (plot.mature() && harvest(data, plot)) changed = true;
            putPlot(data, index, plot);
        }
        // 只有「真的收了一轮」才落盘 —— 与原脚本一致：平时的倒计时变化留在内存里，
        // 避免每 2 秒给每个玩家写一次文件。
        if (changed) save(playerName, data);
        return has;
    }

    private static boolean hasUltimatePlot(String playerName) {
        Map<String, Object> data = load(playerName);
        if (data == null) return false;
        for (Object raw : plots(data).values()) {
            LingpuPlot plot = LingpuPlot.fromJson(raw);
            if (plot != null && plot.planted() && plot.ultimate()) return true;
        }
        return false;
    }

    // ==================================================================
    // 背包 / 物品辅助
    // ==================================================================

    /** 背包还能容纳多少个「与 proto 同类」的物品（前 36 格）。 */
    private static long inventorySpaceFor(Player player, ItemStack proto, int maxStack) {
        long space = 0L;
        ItemStack[] contents = player.getInventory().getContents();
        int limit = Math.min(36, contents.length);
        for (int i = 0; i < limit; i++) {
            ItemStack slotItem = contents[i];
            if (slotItem == null || slotItem.getType().isAir()) {
                space += maxStack;
            } else if (proto != null && slotItem.isSimilar(proto)) {
                space += Math.max(0, maxStack - slotItem.getAmount());
            }
        }
        return space;
    }

    /** 给玩家物品，装不下的掉在脚下。 */
    private static void giveOrDrop(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack rest : leftover.values()) {
            if (rest != null) dropAt(player, rest);
        }
    }

    private static void dropAt(Player player, ItemStack stack) {
        try {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        } catch (Throwable ignored) {
            // 丢不出来也没别的办法
        }
    }

    /** 光标物品减一；减到 0 就清空光标。 */
    private static void consumeOneFromCursor(Player player, ItemStack cursor) {
        if (cursor.getAmount() <= 1) player.getOpenInventory().setCursor(null);
        else cursor.setAmount(cursor.getAmount() - 1);
    }

    /** 翻耕用掉的锄头耐久 -1~10（原脚本用 Damageable 改 meta）。 */
    private static void damageHoe(ItemStack cursor) {
        try {
            ItemMeta meta = cursor.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.Damageable dmg) {
                dmg.setDamage(dmg.getDamage() + 1 + ThreadLocalRandom.current().nextInt(10));
                cursor.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 非耐久物品忽略
        }
    }

    /** 统计背包里某个粘液 id 的总数（前 36 格）。 */
    private static int countInInventory(Player player, String sfId) {
        int total = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (sfId.equals(slimefunIdOf(stack))) total += stack.getAmount();
        }
        return total;
    }

    /** 从背包扣掉指定数量的粘液物品。 */
    private static void takeFromInventory(Player player, String sfId, int amount) {
        PlayerInventory inv = player.getInventory();
        int left = amount;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (!sfId.equals(slimefunIdOf(stack))) continue;
            int take = Math.min(stack.getAmount(), left);
            int remain = stack.getAmount() - take;
            if (remain <= 0) inv.setItem(i, null);
            else stack.setAmount(remain);
            left -= take;
        }
    }

    /**
     * 灵圃界面里「光标物品」的 id。
     *
     * <p>只认粘液 id：原脚本 {@code getLingpuItemId} 的「显示名反推」兜底已移除——
     * 铁砧能把普通物品改成「青木灵圃」「灵蕴草种子」等名字，假灵圃/假种子会被种进玩家地块。</p>
     */
    private static String lingpuItemId(ItemStack stack) {
        return slimefunIdOf(stack);
    }

    /**
     * 纳界袋退回：把袋子里的东西倒回背包，倒不下的掉脚下。
     *
     * <p>只认粘液 id {@code TR_NJD}：显示名分支（含「纳界袋」及纳/界/袋宽松匹配）可被铁砧
     * 伪造，普通 Bundle 改个名就会被倒空，已移除。</p>
     */
    private static void returnNjdContents(Player player, ItemStack item) {
        if (!isNjdItem(item)) return;
        try {
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof BundleMeta bundle)) return;
            List<ItemStack> items = bundle.getItems();
            if (items == null || items.isEmpty()) return;

            for (ItemStack stack : new ArrayList<>(items)) {
                if (stack == null) continue;
                var leftover = player.getInventory().addItem(stack);
                for (ItemStack rest : leftover.values()) {
                    if (rest != null) dropAt(player, rest);
                }
            }
            bundle.setItems(new ArrayList<>());
            item.setItemMeta(bundle);
            player.updateInventory();
            player.sendMessage("§a纳界袋中的物品已退回背包，装不下的已掉落在脚下。");
        } catch (Throwable ignored) {
            // 读不出内容就什么都不做
        }
    }

    private static boolean isNjdItem(ItemStack stack) {
        if (stack == null || stack.getType() != Material.BUNDLE) return false;
        try {
            SlimefunItem sf = SlimefunItem.getByItem(stack);
            if (sf != null && ITEM_NJD.equalsIgnoreCase(String.valueOf(sf.getId()))) return true;
        } catch (Throwable ignored) {
            // 判不出来就当不是
        }
        return false;
    }

    private static void playSound(Player player, String key, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), key, volume, pitch);
        } catch (Throwable ignored) {
            // 音效失败不影响功能
        }
    }

    // ==================================================================
    // 类型转换
    // ==================================================================

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof Boolean b) return b ? 1L : 0L;
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isBlank() && !"false".equalsIgnoreCase(s.trim());
        return true;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 供调试/卸载使用：清掉自动收获名单、实时刷新任务与首次扫描标记。 */
    public static void shutdown() {
        AUTO_PLAYERS.clear();
        for (String name : new ArrayList<>(LINGPU_REFRESH.keySet())) {
            stopLingpuRefresh(name);
        }
        autoInited = false;
    }

    // ==================================================================
    // 界面事件
    // ==================================================================

    /**
     * 储物空间全部界面的点击 / 拖拽处理。
     *
     * <p>与其它 GUI 脚本不同，本类需要<b>自己注册</b>界面事件：原脚本在加载期用
     * {@code registerEvent} 挂了三个（Click / Close / Drag），而菜单是脚本自己
     * {@code createInventory} 出来的，不走 Slimefun 的物品菜单。</p>
     *
     * <p>Close 事件不注册 —— 原脚本在关闭时只是清两个缓存容器，本实现用
     * {@link MenuHolder} 承载上下文后没有需要清的东西，数据在每次操作后都已落盘。</p>
     */
    private static final class MenuListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = event.getView().getTopInventory();
                MenuHolder holder = holderOf(top);
                if (holder == null) return;
                handleClick(event, top, holder);
            } catch (Throwable t) {
                TT.log("[储物空间] 点击处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                if (holderOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
