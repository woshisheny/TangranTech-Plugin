package com.tangran.tangrantech.native_scripts;

import com.tangran.tangrantech.TT;
import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 原生脚本取插件实例的入口。
 *
 * <p>原 JS 脚本里直接引用宿主注入的全局 {@code plugin}（即 RSC 自己的 JavaPlugin），
 * 用 {@code plugin.getDataFolder()} 拼存档路径、用 {@code plugin} 作为
 * {@code Bukkit.getScheduler().runTask(plugin, ...)} 的属主。
 * 原生化后统一改走这里，返回本插件实例，语义等价。</p>
 */
public final class NativePluginHelper {

    private NativePluginHelper() {}

    /** 当前插件实例（等价原脚本里的全局 {@code plugin}）。 */
    public static Plugin getPlugin() {
        return TT.plugin;
    }

    /** 插件数据目录（等价原脚本里的 {@code plugin.getDataFolder()}）。 */
    public static File getDataFolder() {
        Plugin p = getPlugin();
        return p != null ? p.getDataFolder() : new File("plugins/TangranTech");
    }

    /** 在主线程执行任务（等价原脚本的 {@code Bukkit.getScheduler().runTask(plugin, r)}）。 */
    public static void runOnMain(Runnable task) {
        Plugin p = getPlugin();
        if (p == null) { task.run(); return; }
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(p, task);
    }

    /**
     * 延迟在主线程执行任务。
     *
     * @return 任务句柄，便于取消；插件实例不可用时返回 null（任务已就地执行）
     */
    public static org.bukkit.scheduler.BukkitTask runLater(Runnable task, long delayTicks) {
        Plugin p = getPlugin();
        if (p == null) {
            task.run();
            return null;
        }
        return Bukkit.getScheduler().runTaskLater(p, task, delayTicks);
    }

    /** 以 tick 为单位的定时异步调度器所属插件（与 Komutech 的异步调度器同构）。 */
    public static JavaPlugin javaPlugin() {
        return TT.plugin;
    }
}
