package com.tangran.tangrantech.script;

import org.jetbrains.annotations.Nullable;

/**
 * 脚本求值抽象层：机器 / 菜单 / 物品通过它调用行为脚本。
 *
 * <p>对应原 RSC 的 {@code ScriptEval}，以及 Komutech 重构版
 * {@code tech.komutech.script.ScriptEval}。糖然科技走「纯 Java 全量重写」路线，
 * 因此实际生效的实现只有 {@code NativeScriptEval}：按脚本名从
 * {@code NativeScriptRegistry} 取 Java 单例并转发调用。
 */
public abstract class ScriptEval {

    protected ScriptEval() {}

    /** 实现标识（原生路径返回 "java"）。 */
    public abstract String key();

    protected void contextInit() {}

    /** 向脚本上下文注入变量；Java 路径下为空实现。 */
    public abstract void addThing(String name, Object value);

    public final void doInit() {
        evalFunction("init");
    }

    /** 调用脚本函数；返回 null 表示脚本无此函数或无需返回值。 */
    @Nullable
    public abstract Object evalFunction(String name, Object... args);

    public abstract void close();
}
