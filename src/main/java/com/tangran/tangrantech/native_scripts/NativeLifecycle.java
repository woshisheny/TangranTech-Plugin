package com.tangran.tangrantech.native_scripts;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * 生命周期注册器：把脚本的监听器注册到 Bukkit，并保证「同一脚本只注册一次」。
 *
 * <p>为什么需要幂等：同一脚本名可能被多条 yml 条目引用（例如同一行为挂在多个物品上），
 * 若每次引用都 registerEvents，监听逻辑会重复执行 N 次（重复扣物品、重复发消息等）。</p>
 */
public final class NativeLifecycle {

    private NativeLifecycle() {}

    /** 已注册过的脚本类型（按实现类的类型去重）。 */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    /** 幂等注册：已注册过则直接返回。 */
    public static void registerLifecycleOnce(NativeLifecycleScript script, Plugin plugin) {
        if (script == null || plugin == null) return;
        if (!REGISTERED.add(key(script))) return;
        register(script, plugin);
    }

    /** 强制注册（忽略幂等，仅供重载/测试使用）。 */
    public static void register(NativeLifecycleScript script, Plugin plugin) {
        if (script == null || plugin == null) return;
        for (Listener l : nullSafe(script.listeners())) {
            if (l != null) Bukkit.getPluginManager().registerEvents(l, plugin);
        }
    }

    private static Iterable<Listener> nullSafe(Iterable<Listener> it) {
        return it == null ? Collections.emptyList() : it;
    }

    private static String key(NativeScript script) {
        return script.getClass().getName();
    }

    /** 清空注册记录（插件重载 / 单元测试用）。 */
    public static void reset() {
        REGISTERED.clear();
    }
}
