package com.tangran.tangrantech.native_scripts;

import org.bukkit.event.Listener;

/**
 * 需要在加载期注册监听器的脚本标记接口。
 *
 * <p>原 JS 脚本在求值阶段自行 {@code Bukkit.getPluginManager().registerEvents(...)}，
 * 原生化后由 {@link NativeLifecycle} 统一、幂等地完成注册。</p>
 */
public interface NativeLifecycleScript extends NativeScript {

    /** 返回本脚本需要注册的全部监听器实例。 */
    Iterable<Listener> listeners();
}
