package com.tangran.tangrantech.native_scripts.daofa.boss;

import org.bukkit.Bukkit;

/**
 * BOSS战 的统一日志出口。
 *
 * <p>对应原脚本的 {@code logBossError(msg)} 与 {@code globalThis.tangranBossLog(msg)}
 * —— 后者被 {@code 任务.js} 等其它脚本调用，用来往同一份日志里写行。</p>
 *
 * <p>原脚本用 {@code Bukkit.getLogger().warning('[糖然科技] ' + msg)}（warning 级），
 * 这里保持一致：BOSS战 的自我诊断信息在服务端日志里是 {@code WARN} 而不是 {@code INFO}，
 * 便于在「重启后翻日志找问题」时一眼看到。不要为了「好看」把它降成 info。</p>
 */
public final class BossLog {

    private BossLog() {}

    /** 前缀（与原脚本一致）。 */
    public static final String PREFIX = "[糖然科技] ";

    /** 写一条日志。 */
    public static void warn(String msg) {
        try {
            Bukkit.getLogger().warning(PREFIX + msg);
        } catch (Throwable ignored) {
            // 日志都写不出来就算了，绝不让它影响主流程
        }
    }
}
