package com.tangran.tangrantech.native_scripts;

import org.bukkit.plugin.Plugin;

/**
 * 原生脚本接口（对齐 Komutech 的 {@code NativeScript}）。
 *
 * <p>原 RSC 版中，每个 {@code scripts/*.js} 在加载期被求值一次，脚本自行注册 Bukkit 监听，
 * 并由 RSC 在特定时机回调约定函数（如 {@code onUse}、{@code tick}）。
 * 原生化后每个脚本对应一个本接口的实现类（单例），由 {@link NativeScriptRegistry} 按脚本名索引。</p>
 */
public interface NativeScript {

    /**
     * 注册本脚本所需的运行期监听器。由 {@code Setup#loadAll} 在内容注册完毕后统一调用一次，
     * 对应原 JS 脚本「加载期注册监听」的行为。
     */
    default void registerListeners(Plugin plugin) {}

    /**
     * 本脚本的机器是否需要 Slimefun 的 {@code BlockMenu}。
     *
     * <p>默认 {@code true}：绝大多数 {@code machines.yml} 机器都用 {@code menus.yml} 定义的菜单，
     * 取不到菜单就没什么可干的，跳过这一拍更省。</p>
     *
     * <p><b>必须返回 {@code false} 的例外</b>：「糖然牌堆叠机」<b>刻意没有 menus.yml 条目</b>
     * —— 它的界面是脚本自建的（存在方块 BlockData 里），tick 逻辑也完全不碰菜单。
     * 如果按默认规则等菜单，一旦该方块没被 Slimefun 建出 BlockMenu，
     * 这台机器就会<b>彻底不工作</b>（玩家表现为「放下去没反应」）。</p>
     */
    default boolean needsMenu() {
        return true;
    }

    /**
     * 调用脚本函数。
     *
     * @param fn   函数名（如 {@code onUse}、{@code tick}）
     * @param args 参数（与原 JS 回调签名一致）
     * @return 脚本返回值；null 表示无返回值
     */
    Object invoke(String fn, Object... args);
}
