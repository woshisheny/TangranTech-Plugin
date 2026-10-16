package com.tangran.tangrantech.native_scripts;

/**
 * 空实现：占位用，任何函数调用都返回 null。
 *
 * <p>用于「yml 里写了 script: 但对应行为尚未原生化」的条目，保证注册流程不中断
 * （物品仍然正常注册、可合成、可显示），行为待后续分批补齐。
 */
public final class NoOpNativeScript implements NativeScript {

    @Override
    public Object invoke(String fn, Object... args) {
        return null;
    }
}
