package com.tangran.tangrantech.load;

import com.tangran.tangrantech.native_scripts.NativeScript;
import com.tangran.tangrantech.native_scripts.NativeScriptEval;
import com.tangran.tangrantech.native_scripts.NativeScriptRegistry;
import com.tangran.tangrantech.script.ScriptEval;
import org.jetbrains.annotations.Nullable;

/**
 * 按脚本名取 Java 单例并包成 {@link ScriptEval}。
 *
 * <p>对应原 RSC 的 {@code ScriptEvalLoader.load}，但去掉了它那层 .js 文件逻辑——
 * 原实现只在拼 registry key 时用到文件，内容从未被读取。糖然科技走纯 Java 路线，
 * 因此这里直接查 {@link NativeScriptRegistry}。</p>
 */
public final class ScriptLoader {

    private ScriptLoader() {}

    /** 加载脚本；找不到时返回 null（调用方按「无脚本」处理）。 */
    @Nullable
    public static ScriptEval load(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        NativeScript script = NativeScriptRegistry.get(name);
        if (script == null) return null;
        return new NativeScriptEval(name, script);
    }
}
