package com.tangran.tangrantech.groups;

import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.guide.SurvivalSlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 糖然科技的根组「糖然科技」——自绘父组页面，<b>逐行照抄</b> RSC
 * {@code RSCItemGroupLegacy#setup}（实机截图 + 源码双重对照）。
 *
 * <h2>RSC 页面结构（源码实证）</h2>
 * <ol>
 *   <li>{@code createHeader} —— Slimefun 标准头部（第 1 行：返回/设置/搜索）；</li>
 *   <li>{@code slot 1} —— 返回按钮（无历史→主菜单；有历史→goBack；Shift+左键→主菜单）；</li>
 *   <li><b>内容区 slot 9-44（4 行 × 9 = 36 格/页）</b>：全部子项按 {@code getDisplayTier}
 *       升序排序后<b>顺序填充</b>（tier 只是排序键，不是槽位！73 个条目 = 3 页）；</li>
 *   <li>{@code slot 46} = 上一页、{@code slot 52} = 下一页（固定槽位，永远画）；</li>
 *   <li>功能按钮（type: button + actions）点击执行动作、装饰按钮纯展示，
 *       子分类点击经 {@code SlimefunGuide.openItemGroup} 打开。</li>
 * </ol>
 *
 * <p>此前用「tier=槽位、54 格/页」的假设渲染——与 RSC 截图前两行巧合吻合、之后错位，
 * 且 54 格全满导致翻页按钮无处放。以源码为准全部重写。</p>
 */
public class TTNestedGroup extends NestedItemGroup {

    /** 内容页容量：slot 9-44 = 36 格/页（RSC 常量口径）。 */
    private static final int PER_PAGE = 36;
    /** 内容区起始槽位。 */
    private static final int CONTENT_START = 9;
    /** 上一页 / 下一页的固定槽位（RSC 照抄）。 */
    private static final int PREV_SLOT = 46;
    private static final int NEXT_SLOT = 52;

    @FunctionalInterface
    public interface Click {
        void run(Player p, PlayerProfile profile, SlimefunGuideMode mode);
    }

    /** 页面条目：展示物 + 点击行为（null = 纯装饰）+ 排序 tier。 */
    private record Entry(ItemStack display, Click onClick, int tier) {}

    private final String title;
    private final List<Entry> entries = new ArrayList<>();

    public TTNestedGroup(NamespacedKey key, ItemStack display, int tier, String title) {
        super(key, display, tier);
        this.title = title;
    }

    /** 追加一个页面条目（GroupLoader 按 yml 顺序调用；tier 为排序键）。 */
    public void addEntry(int tier, ItemStack display, Click onClick) {
        entries.add(new Entry(display, onClick, tier));
    }

    /** 主界面可见（Flex 组由 fork 指南按本判定收集）。 */
    @Override
    public boolean isVisible(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        return !entries.isEmpty();
    }

    @Override
    public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        openPage(p, profile, mode, 1);
    }

    private void openPage(Player p, PlayerProfile profile, SlimefunGuideMode mode, int page) {
        // 按 tier 升序（RSC：sorted(comparingInt(getDisplayTier))）
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Integer.compare(a.tier(), b.tier()));
        int validCount = sorted.size();
        int pages = Math.max(1, (validCount + PER_PAGE - 1) / PER_PAGE);
        final int pg = Math.max(1, Math.min(pages, page));

        try {
            profile.getGuideHistory().add(this, pg);
        } catch (Throwable ignored) {
            // 历史记录失败不影响打开
        }

        ChestMenu menu = new ChestMenu(title);
        try {
            menu.setEmptySlotsClickable(false);
        } catch (Throwable ignored) {
            // 老版本无此方法则跳过
        }
        try {
            // Slimefun 标准头部（第 1 行：返回/设置/搜索）
            var guide = Slimefun.getRegistry().getSlimefunGuide(mode);
            if (guide instanceof SurvivalSlimefunGuide survival) {
                survival.createHeader(p, profile, menu);
            }
        } catch (Throwable ignored) {
            // 头部失败不影响内容
        }

        // slot 1：返回按钮（RSC 双语义：无历史→主菜单；有历史→goBack；Shift+左键→主菜单）
        menu.addItem(1, ChestMenuUtils.getBackButton(p), (pl, s, item, action) -> {
            var history = profile.getGuideHistory();
            try {
                if (history.size() <= 1 || action.isShiftClicked()) {
                    SlimefunGuide.openMainMenu(profile, mode, history.getMainMenuPage());
                    return false;
                }
                history.goBack((SurvivalSlimefunGuide) Slimefun.getRegistry().getSlimefunGuide(mode));
            } catch (Throwable err) {
                SlimefunGuide.openMainMenu(profile, mode, 1);
            }
            return false;
        });

        // 内容区 slot 9-44：按 tier 排序后顺序填充
        int start = (pg - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= validCount) break;
            Entry e = sorted.get(idx);
            int slot = CONTENT_START + i;
            menu.addItem(slot, e.display());
            Click click = e.onClick();
            if (click != null) {
                menu.addMenuClickHandler(slot, (pl, s2, item, action) -> {
                    click.run(pl, profile, mode);
                    return false;
                });
            } else {
                // 装饰条目：ChestMenu 默认点击会把物品拿走，必须挂空处理器
                menu.addMenuClickHandler(slot, ChestMenuUtils.getEmptyClickHandler());
            }
        }

        // 上一页 / 下一页（RSC 固定槽位 46 / 52，永远画；越界点击原地重开）
        menu.addItem(PREV_SLOT, ChestMenuUtils.getPreviousButton(p, pg, pages),
                (pl, s, item, action) -> {
                    if (pg > 1) openPage(pl, profile, mode, pg - 1);
                    return false;
                });
        menu.addItem(NEXT_SLOT, ChestMenuUtils.getNextButton(p, pg, pages),
                (pl, s, item, action) -> {
                    if (pg < pages) openPage(pl, profile, mode, pg + 1);
                    return false;
                });

        menu.open(p);
    }

    /** 构造兜底展示物品（根组展示解析失败时用）。 */
    public static ItemStack fallbackDisplay(String name) {
        ItemStack it = new ItemStack(Material.CHEST);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&d&l" + name));
            it.setItemMeta(meta);
        }
        return it;
    }

    @Override
    public String toString() {
        return "TTNestedGroup(" + getKey().getKey() + ", entries=" + entries.size() + ")";
    }
}
