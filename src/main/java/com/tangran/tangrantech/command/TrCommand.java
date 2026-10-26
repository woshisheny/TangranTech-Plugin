package com.tangran.tangrantech.command;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.util.Perms;
import com.tangran.tangrantech.util.Store;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /tr} —— 糖然科技管理指令。
 *
 * <h2>{@code /tr import}</h2>
 * <p>把 <b>RSC 版</b>的玩家数据（{@code plugins/RykenSlimeCustomizer/addon_configs/糖然科技/**}）
 * 导进本插件现在的 SQLite 文档库。默认只<b>预览</b>：扫描、统计、列出样例，一个字节都不写；
 * 确认无误后加 {@code --apply} 才会落库。</p>
 *
 * <pre>
 * /tr import                     预览 RSC 数据（默认源目录）
 * /tr import --apply             真正写入
 * /tr import --source &lt;目录&gt;     指定源目录（例如把 RSC 数据拷到别处）
 * /tr import --only 境界,储物空间  只导这些命名空间
 * /tr import --overwrite         库里已有同键时用 RSC 数据覆盖（默认跳过，保护现有进度）
 * </pre>
 *
 * <p>权限：{@code tangrantech.command.import} 或 OP，控制台永远可用。</p>
 */
public final class TrCommand implements CommandExecutor, TabCompleter {

    /** RSC 插件名（数据目录 {@code plugins/RykenSlimeCustomizer}）。 */
    private static final String RSC_PLUGIN = "RykenSlimeCustomizer";

    /** RSC 数据在插件目录下的相对路径。 */
    private static final String RSC_DATA = "addon_configs/糖然科技";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!checkPerm(sender)) return true;
        if (args.length == 0 || !"import".equalsIgnoreCase(args[0])) {
            usage(sender, label);
            return true;
        }
        return importData(sender, label, args);
    }

    private boolean checkPerm(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;   // 控制台
        if (Perms.has(player, Perms.NODE_IMPORT)) return true;
        try {
            if (player.isOp()) return true;
        } catch (Throwable ignored) {
            // 忽略
        }
        sender.sendMessage("§c需要权限 §ftangrantech.command.import §c（或 OP）才能执行数据导入。");
        return false;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage("§6§l糖然科技 §7数据管理");
        sender.sendMessage("§f/" + label + " import §7— 从 RSC 导入玩家数据（默认只预览）");
        sender.sendMessage("§8  --apply §7真正写入  §8--source <目录> §7指定源目录");
        sender.sendMessage("§8  --only <命名空间,...> §7只导指定命名空间  §8--overwrite §7覆盖已有同名文档");
        sender.sendMessage("§7存储后端：§f" + (Store.available()
                ? "SQLite（" + Store.databaseFile().getName() + "）"
                : "文件（SQLite 不可用，已降级）"));
        Map<String, Integer> ns = Store.namespaces();
        if (ns.isEmpty()) {
            sender.sendMessage("§7库内暂无玩家数据。");
        } else {
            sender.sendMessage("§7库内数据：" + describeNamespaces(ns));
        }
        sender.sendMessage("§7权限层：§f" + Perms.status());
    }

    private boolean importData(CommandSender sender, String label, String[] args) {
        boolean apply = false;
        boolean overwrite = false;
        File source = defaultSource();
        Set<String> only = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a.toLowerCase(Locale.ROOT)) {
                case "--apply" -> apply = true;
                case "--overwrite" -> overwrite = true;
                case "--dry-run" -> apply = false;
                case "--source" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage("§c--source 后面要跟目录路径。");
                        return true;
                    }
                    source = new File(args[++i]);
                }
                case "--only" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage("§c--only 后面要跟命名空间（逗号分隔，可多个，如 境界,储物空间）。");
                        return true;
                    }
                    for (String part : args[++i].split("[,，]")) {
                        String ns = part.trim();
                        if (!ns.isEmpty()) only.add(ns);
                    }
                }
                default -> {
                    sender.sendMessage("§c未知参数：" + a);
                    usage(sender, label);
                    return true;
                }
            }
        }

        if (!Store.available()) {
            sender.sendMessage("§cSQLite 后端不可用（见启动日志「[存储]」），导入无处可写。"
                    + "请先修好数据库再试。");
            return true;
        }
        if (source == null || !source.isDirectory()) {
            sender.sendMessage("§c源目录不存在：" + (source == null ? "?" : source.getAbsolutePath()));
            sender.sendMessage("§7若 RSC 数据不在默认位置，用 §f--source <目录> §7指定。");
            return true;
        }

        final File src = source;
        final boolean doApply = apply;
        final boolean doOverwrite = overwrite;
        final Set<String> onlyNs = only;

        sender.sendMessage("§6[导入] §f源目录：§7" + src.getAbsolutePath());
        sender.sendMessage("§6[导入] §f模式：" + (doApply ? "§c写入（--apply）" : "§a预览（不会写入）")
                + (doOverwrite ? " §7+ 覆盖已有" : ""));
        if (!onlyNs.isEmpty()) sender.sendMessage("§6[导入] §f只导：" + String.join(", ", onlyNs));

        // 扫描要读成百上千个文件，放到异步线程；结果回主线程发消息
        Bukkit.getScheduler().runTaskAsynchronously(TT.plugin(), () -> {
            Store.ImportReport rep;
            try {
                rep = Store.importLegacy(src, onlyNs, doOverwrite, !doApply);
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(TT.plugin(), () ->
                        sender.sendMessage("§c[导入] 失败：" + t));
                return;
            }
            Bukkit.getScheduler().runTask(TT.plugin(), () -> report(sender, rep, doApply));
        });
        return true;
    }

    private void report(CommandSender sender, Store.ImportReport rep, boolean applied) {
        if (rep.total() == 0) {
            sender.sendMessage("§7[导入] 源目录里没有可导入的玩家数据（*.json）。");
            return;
        }
        sender.sendMessage("§6[导入] §f" + rep.summary());
        if (!rep.perNs().isEmpty()) {
            List<String> parts = new ArrayList<>();
            int shown = 0;
            for (Map.Entry<String, Integer> e : rep.perNs().entrySet()) {
                parts.add(e.getKey() + " ×" + e.getValue());
                if (++shown >= 15) break;
            }
            sender.sendMessage("§7[导入] 命名空间：" + String.join(" §8| §7", parts));
        }
        if (!rep.samples().isEmpty()) {
            sender.sendMessage("§7[导入] 样例：" + String.join(", ", rep.samples()));
        }
        if (rep.failed() > 0) {
            sender.sendMessage("§c[导入] " + rep.failed() + " 份文件解析/写入失败，已跳过（原文件未动）。");
        }
        if (applied) {
            sender.sendMessage("§a[导入] 已写入 SQLite。当前库内："
                    + describeNamespaces(Store.namespaces()));
        } else {
            sender.sendMessage("§e[导入] 这是预览。确认无误后执行 §f/tr import --apply §e真正写入。");
        }
    }

    private static String describeNamespaces(Map<String, Integer> ns) {
        if (ns == null || ns.isEmpty()) return "（空）";
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> e : ns.entrySet()) {
            parts.add(e.getKey() + " ×" + e.getValue());
            if (++shown >= 12) break;
        }
        return String.join(" §8| §7", parts);
    }

    /** 默认源目录：{@code plugins/RykenSlimeCustomizer/addon_configs/糖然科技}。 */
    private static File defaultSource() {
        File plugins = TT.plugin() == null ? null : TT.plugin().getDataFolder().getParentFile();
        return plugins == null ? null : new File(plugins, RSC_PLUGIN + "/" + RSC_DATA);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("import".startsWith(args[0].toLowerCase(Locale.ROOT))) out.add("import");
            return out;
        }
        if (args.length >= 2 && "import".equalsIgnoreCase(args[0])) {
            String cur = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (String opt : new String[]{"--apply", "--overwrite", "--source", "--only"}) {
                if (opt.startsWith(cur) && !java.util.Arrays.asList(args).contains(opt)) out.add(opt);
            }
        }
        return out;
    }
}
