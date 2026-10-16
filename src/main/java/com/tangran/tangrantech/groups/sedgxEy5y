package com.tangran.tangrantech.groups;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeScript;
import com.tangran.tangrantech.native_scripts.NativeScriptRegistry;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.FlexItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 带点击动作的物品组「按钮」（对应 RSC {@code groups.yml} 里 {@code type: button} 且写了
 * {@code actions:} 的条目）。
 *
 * <p><b>为什么必须实现它</b>：本项目的 8 个大脚本（异火选择、境界、浮生当铺、终刻合成台、
 * 储物空间、任务、升维之路，以及糖然牌基础物质获取）都<b>只</b>通过物品组按钮触发——
 * 它们在 yml 里是</p>
 * <pre>
 * Tr_65:
 *   type: button
 *   parent: TangranTech
 *   actions:
 *   - "script 道法/升维之路"
 * </pre>
 * <p>没有这个类，这些脚本即使原生化完毕也点不到，等于白移植。</p>
 *
 * <p>支持的 action 类型（与原脚本一致）：</p>
 * <ul>
 *   <li>{@code script <脚本名>} —— 转发到原生脚本的 {@code onButtonGroupClick}
 *       （与原 RSC 的 {@code evalFunction("onButtonGroupClick", p, slot, clickedItem, clickAction, mode)} 同参）；</li>
 *   <li>{@code console <命令>} —— 以控制台身份执行，支持 {@code %player%} 占位；</li>
 *   <li>{@code link <网址>} —— 给玩家发一条可点击链接；</li>
 *   <li>{@code open_itemgroup <ns:key[:page]>} —— 打开指定物品组（按键名在本插件已注册的组里查）。</li>
 * </ul>
 *
 * <p><b>与原 RSC 的差异（位置不同、功能等价）</b>：RSC 把按钮组做成嵌在父组页面里的条目，
 * 那是靠它自己完全接管了 Slimefun 指南的渲染（{@code RSCItemGroupLegacy.openPage}）实现的。
 * 本插件不做这套重写，改为标准 Slimefun 的 {@link FlexItemGroup}——按钮会出现在指南的物品组
 * 列表里（按其 {@code tier} 排序），点击即执行 {@code actions}。用户可观察的功能一致，
 * 仅入口在指南中的位置略有不同。</p>
 */
public class TTButtonGroup extends FlexItemGroup {

    /** 该按钮的点击动作（原样保留 yml 里的字符串）。 */
    private final List<String> actions;

    public TTButtonGroup(NamespacedKey key, ItemStack item, int tier, List<String> actions) {
        super(key, item, tier);
        this.actions = (actions == null) ? List.of() : List.copyOf(actions);
    }

    /** 该按钮绑定的动作。 */
    public List<String> getActions() { return actions; }

    @Override
    public boolean isVisible(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        // 指南修复：功能按钮不再作为独立组出现在主界面——改由 TTNestedGroup 的
        // 自绘父页面按 tier 画出并分发点击（与 RSC「按钮嵌在父组页面里」一致）
        return false;
    }

    @Override
    public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        runActions(p, profile, mode, getKey().getKey(), actions);
    }

    /**
     * 顺序执行一组动作（静态入口：自绘父页面直接分发，不经组实例）。
     *
     * @param key 按钮名（仅用于日志定位）
     */
    public static void runActions(Player p, PlayerProfile profile, SlimefunGuideMode mode,
                                  String key, List<String> actions) {
        if (actions == null) return;
        for (String action : actions) {
            try {
                runAction(p, profile, mode, action);
            } catch (Throwable t) {
                TT.log("物品组按钮动作执行失败 [" + key + "] " + action + " -> " + t);
            }
        }
    }

    /** 执行一条动作。 */
    private static void runAction(Player p, PlayerProfile profile, SlimefunGuideMode mode, String action) {
        if (action == null || action.isBlank()) return;

        // 原 RSC 的格式约定：动作串按空格切成两段（类型 + 内容）。
        // 对 console 类型，RSC 会用 replace 把整段（含前缀）替回去以保留命令里的空格，
        // 这里等价处理。
        String trimmed = action.trim();
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            TT.log("物品组按钮动作格式不正确（应为「类型 内容」）: " + trimmed);
            return;
        }
        String type = trimmed.substring(0, sp).toLowerCase(Locale.ROOT);
        String content = trimmed.substring(sp + 1).trim();

        switch (type) {
            case "script" -> runScript(p, mode, content);
            case "console" -> runConsole(p, content);
            case "link" -> p.sendMessage("§e单击此处打开链接: §7" + content);
            case "open_itemgroup" -> openItemGroup(p, profile, mode, content);
            default -> TT.log("物品组按钮遇到未知动作类型: " + type);
        }
    }

    /** {@code script <脚本名>}：转发给原生脚本的 {@code onButtonGroupClick}。 */
    private static void runScript(Player p, SlimefunGuideMode mode, String scriptName) {
        NativeScript script = NativeScriptRegistry.get(scriptName);
        if (script == null) {
            TT.log("物品组按钮引用的脚本尚未原生化，已跳过: " + scriptName);
            return;
        }
        // 原 RSC 传入 (player, slot, clickedItem, clickAction, mode)。
        // 本实现没有 slot 与 ClickAction 的上下文（FlexItemGroup 的 open 里拿不到），
        // 因此传 -1 与 null；本项目用到的脚本都不依赖这两个参数。
        script.invoke("onButtonGroupClick", p, -1, null, null, mode);
    }

    /** {@code console <命令>}：以控制台身份执行，支持 {@code %player%}。 */
    private static void runConsole(Player p, String command) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    command.replace("%player%", p.getName()));
        } catch (Throwable t) {
            TT.log("物品组按钮执行命令失败: " + command + " -> " + t);
        }
    }

    /** {@code open_itemgroup <ns:key[:page]>}：打开指定物品组。 */
    private static void openItemGroup(Player p, PlayerProfile profile, SlimefunGuideMode mode, String content) {
        String[] parts = content.split(":");
        if (parts.length < 2) {
            TT.log("物品组按钮的 open_itemgroup 缺少 NamespacedKey: " + content);
            return;
        }
        // 本插件注册的组统一以「小写组名」作为 key 的 key 部分，因此按键名回查即可。
        ItemGroup target = TT.groups.get(parts[1].toLowerCase(Locale.ROOT));
        if (target == null) {
            TT.log("物品组按钮指向了未注册的物品组: " + content);
            return;
        }
        int page = 1;
        if (parts.length > 2) {
            try {
                page = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ignored) {
                // 用默认页码
            }
        }
        SlimefunGuide.openItemGroup(profile, target, mode, page);
    }
}
