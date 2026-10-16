package com.tangran.tangrantech.native_scripts;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import com.tangran.tangrantech.script.ScriptEval;

/**
 * 把 {@link ScriptEval} 的函数调用转发给 Java 单例 {@link NativeScript}。
 *
 * <p>{@code init} 触发生命周期注册（{@link NativeLifecycle#registerLifecycleOnce}）；
 * 其余函数名原样转发到 {@code script.invoke}。</p>
 */
public final class NativeScriptEval extends ScriptEval {

    private final String name;
    private final NativeScript script;

    public NativeScriptEval(String name, NativeScript script) {
        this.name = name;
        this.script = script;
    }

    /** 脚本名（标识用）。 */
    public String name() { return name; }

    @Override
    public String key() { return "java"; }

    @Override
    protected void contextInit() {}

    @Override
    public void addThing(String var1, Object var2) {}

    @Override
    @Nullable
    public Object evalFunction(String fn, Object... args) {
        if ("init".equals(fn)) {
            if (script instanceof NativeLifecycleScript lifecycle) {
                Plugin plugin = NativePluginHelper.getPlugin();
                // 幂等入口：同一脚本实例可能被多条 yml 引用而多次 doInit，
                // 若每次都真正注册，监听器会重复注册
                NativeLifecycle.registerLifecycleOnce(lifecycle, plugin);
            }
            return null;
        }
        return script.invoke(fn, args);
    }

    @Override
    public void close() {}
}
