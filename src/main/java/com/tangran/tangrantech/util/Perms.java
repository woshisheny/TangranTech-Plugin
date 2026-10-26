package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.util.Locale;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 权限层：<b>有 LuckPerms 就 hook 它，没有就回落 Bukkit 原生判定</b>。
 *
 * <h2>两类节点</h2>
 * <table border="1">
 *   <caption>节点表</caption>
 *   <tr><th>节点</th><th>作用</th><th>谁发放</th></tr>
 *   <tr><td>{@value #NODE_ADMIN}</td><td>管理员：指令、管理员物品、场地保护放行</td><td>服主在权限组里配</td></tr>
 *   <tr><td>{@value #NODE_COMPILER}</td><td>终刻合成台编译器（中章管理员物品）</td><td>服主</td></tr>
 *   <tr><td>{@value #NODE_ARENA_BYPASS}</td><td>挑战世界场地保护放行（管理员进场地不被拦）</td><td>服主</td></tr>
 *   <tr><td>{@value #NODE_IMPORT}</td><td>{@code /tr import} 数据导入</td><td>服主</td></tr>
 *   <tr><td>{@value #NODE_STORAGE}</td><td>储物空间准入（开辟石使用时自动发放）</td><td>插件发放，可被回收</td></tr>
 *   <tr><td>{@code tangrantech.yanhuo.&lt;id&gt;}</td><td>已掌握某种异火（获得时自动发放）</td><td>插件发放</td></tr>
 *   <tr><td>{@code tangrantech.danfang.&lt;id&gt;}</td><td>已学会某种丹方（学会时自动发放）</td><td>插件发放</td></tr>
 * </table>
 *
 * <h2>判定优先级</h2>
 * <p>「玩法资格」类（储物空间 / 异火 / 丹方）一律<b>存档为主、权限为辅</b>：
 * 存档里有就算有（旧档不会因为没节点而失去资格），权限里有也算有
 * （管理员可以直接用 {@code /lp user <玩家> permission set ...} 发放资格）。
 * 「管理侧」只认权限节点或 OP。</p>
 *
 * <h2>为什么用 {@link Bukkit#getServicesManager()}</h2>
 * <p>LuckPerms 在 Bukkit 平台把 {@link LuckPerms} 注册进 ServicesManager；
 * 服务不存在说明没装 / 还没启用，此时全部走 {@link Player#hasPermission(String)}。
 * 本类对 LuckPerms 的引用都是 {@code compileOnly}，缺失时不会 NoClassDefFound ——
 * 因为入口处先判 {@code Bukkit.getPluginManager().getPlugin("LuckPerms") != null}。</p>
 */
public final class Perms {

    private Perms() {}

    public static final String NODE_ADMIN = "tangrantech.admin";
    public static final String NODE_COMPILER = "tangrantech.admin.compiler";
    public static final String NODE_ARENA_BYPASS = "tangrantech.admin.arena";
    public static final String NODE_IMPORT = "tangrantech.command.import";
    public static final String NODE_STORAGE = "tangrantech.storage.unlocked";
    public static final String NODE_YANHUO_PREFIX = "tangrantech.yanhuo.";
    public static final String NODE_DANFANG_PREFIX = "tangrantech.danfang.";

    /** LuckPerms API 句柄；null = 未安装 / 未就绪（全部回落 Bukkit）。 */
    private static LuckPerms api;

    /** 是否已 hook 到 LuckPerms。 */
    public static boolean hooked() {
        return api != null;
    }

    /** 插件启动时调用一次；没有 LuckPerms 时只记一条日志。 */
    public static void init() {
        api = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                TT.log("[权限] 未检测到 LuckPerms，权限判定回落 Bukkit（hasPermission / isOp）");
                return;
            }
            RegisteredServiceProvider<LuckPerms> reg = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (reg == null || reg.getProvider() == null) {
                TT.log("[权限] LuckPerms 已安装但 API 未就绪，本次回落 Bukkit 判定");
                return;
            }
            api = reg.getProvider();
            TT.log("[权限] 已 hook LuckPerms（节点判定走其缓存数据，含组继承与上下文）");
        } catch (Throwable t) {
            api = null;
            TT.log("[权限] LuckPerms hook 失败，回落 Bukkit 判定: " + t);
        }
    }

    // ==================================================================
    // 判定
    // ==================================================================

    /** 是否拥有某节点（LuckPerms 走缓存数据，含继承与上下文）。 */
    public static boolean has(Player player, String node) {
        if (player == null || node == null) return false;
        if (api != null) {
            try {
                PlayerAdapter<Player> adapter = api.getPlayerAdapter(Player.class);
                CachedPermissionData data = adapter.getPermissionData(player);
                return data.checkPermission(node).asBoolean();
            } catch (Throwable ignored) {
                // 缓存未加载等情形 → 下面回落
            }
        }
        try {
            return player.hasPermission(node);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 任一节点成立即通过。 */
    public static boolean hasAny(Player player, String... nodes) {
        if (nodes == null) return false;
        for (String n : nodes) {
            if (has(player, n)) return true;
        }
        return false;
    }

    /** 是否在某个权限组里（LuckPerms 的组节点约定 {@code group.<组名>}）。 */
    public static boolean inGroup(Player player, String group) {
        return group != null && has(player, "group." + group.toLowerCase(Locale.ROOT));
    }

    /** 管理侧统一判定：OP 或 {@value #NODE_ADMIN}（或其任意子节点）。 */
    public static boolean admin(Player player) {
        if (player == null) return false;
        try {
            if (player.isOp()) return true;
        } catch (Throwable ignored) {
            // 忽略
        }
        return has(player, NODE_ADMIN) || has(player, NODE_COMPILER) || has(player, NODE_IMPORT);
    }

    /** 终刻合成台编译器（管理员物品）的准入。 */
    public static boolean compiler(Player player) {
        if (player == null) return false;
        try {
            if (player.isOp()) return true;
        } catch (Throwable ignored) {
            // 忽略
        }
        return has(player, NODE_COMPILER) || has(player, NODE_ADMIN);
    }

    /** 挑战世界场地保护的放行（管理员/维护用）。 */
    public static boolean arenaBypass(Player player) {
        if (player == null) return false;
        try {
            if (player.isOp()) return true;
        } catch (Throwable ignored) {
            // 忽略
        }
        return has(player, NODE_ARENA_BYPASS) || has(player, NODE_ADMIN);
    }

    // ==================================================================
    // 发放 / 回收（只对「玩法资格」用）
    // ==================================================================

    /**
     * 给在线玩家发放（或回收）一个布尔节点。
     *
     * <p>LuckPerms 侧走 {@code UserManager#modifyUser}：它在后台线程读-改-写并落盘，
     * 不阻塞主线程；失败只记日志（资格本身还有存档兜底，不会因为权限写失败而丢东西）。
     * 没装 LuckPerms 时什么也不做 —— 此时资格只存在存档里，判定照样成立。</p>
     *
     * @param value true = 授予，false = 移除
     */
    public static void grant(Player player, String node, boolean value) {
        if (player == null || node == null) return;
        if (api == null) return;
        try {
            api.getUserManager().modifyUser(player.getUniqueId(), (User user) -> {
                if (value) {
                    user.data().add(Node.builder(node).value(true).build());
                } else {
                    user.data().remove(Node.builder(node).value(true).build());
                }
            }).exceptionally(t -> {
                TT.log("[权限] 写入节点 " + node + "（" + player.getName() + "）失败: " + t);
                return null;
            });
        } catch (Throwable t) {
            TT.log("[权限] 写入节点 " + node + " 失败: " + t);
        }
    }

    /** 把物品/异火 id 规范成合法的权限键（小写 + 只留 [a-z0-9_.-]）。 */
    public static String nodeKey(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                    ? c : '_');
        }
        return sb.toString();
    }

    /** 异火资格节点。 */
    public static String yanhuoNode(String id) {
        return NODE_YANHUO_PREFIX + nodeKey(id);
    }

    /** 丹方资格节点。 */
    public static String danfangNode(String id) {
        return NODE_DANFANG_PREFIX + nodeKey(id);
    }

    /** 供 {@code /tr} 指令展示的一行状态。 */
    public static String status() {
        return api == null ? "未 hook（回落 Bukkit hasPermission / isOp）" : "已 hook LuckPerms";
    }
}
