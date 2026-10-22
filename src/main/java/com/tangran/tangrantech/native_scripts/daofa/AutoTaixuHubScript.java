package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ScriptMachineItem;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 「道法/自动太虚枢机」的原生实现（对应 {@code scripts/道法/自动太虚枢机.js}）。
 *
 * <p>为「命轨织机」{@code TR_MGZJ} 提供自动化的「捕捞」玩法：第 5 格（槽位 4）放一枚
 * {@code TR_MGXX}（命轨星屑），机器每隔 1 秒推进一次进度条（5 格），满 5 格后消耗一枚输入，
 * 并按 30% 概率产出 4 个 {@code TR_TXSJ}（太虚枢机），否则产出 4 个随机原版物品，
 * 结果堆叠进 9~44 号输出槽。</p>
 *
 * <p>与原脚本的差异（改进而非改行为）：</p>
 * <ul>
 *   <li>原脚本靠 RSC 的 {@code onPlace}/{@code onBreak} 钩子为每台机器起一个
 *       {@code runTaskTimer(0, 20)} 任务。这种做法的副作用是：<b>服务器重启后，
 *       已经放置好的机器再也不会恢复进度</b>（任务丢了，且没有任何补挂逻辑）。</li>
 *   <li>本实现改走 {@link ScriptMachineItem} 每刻回调的 {@code tick}，用一个按坐标维护的
 *       计数器把节奏压到「第 1、21、41…刻各处理一次」，与原来 {@code delay=0, period=20}
 *       的触发时刻完全一致，同时天然免疫重启、也天然随机器被破坏/区块卸载而停止。</li>
 *   <li>仍额外挂一个 {@link BlockBreakEvent} 监听，只为清理状态表，避免长跑服内存缓慢增长。</li>
 * </ul>
 */
public final class AutoTaixuHubScript implements NativeLifecycleScript {

    /** 机器内部逻辑用的常量（与原脚本一致；机器 id 仅作注释参考，实际由绑定关系决定）。 */
    private static final int INPUT_SLOT = 4;
    private static final String INPUT_ID = "TR_MGXX";
    private static final String OUTPUT_ID = "TR_TXSJ";
    private static final int PROGRESS_SLOT = 49;

    /** 输出槽区间：9 ~ 44（闭区间）。 */
    private static final int OUTPUT_SLOT_FROM = 9;
    private static final int OUTPUT_SLOT_TO = 44;

    /** 进度上限（格）。 */
    private static final int PROGRESS_MAX = 5;
    /** 处理周期（刻），与原脚本 runTaskTimer(0, 20) 一致。 */
    private static final int PERIOD = 20;

    /** 产出太虚枢机的概率；其余情况产随机原版物。 */
    private static final double TXSJ_CHANCE = 0.3;
    /** 每次产出的数量（两种产出都是 4）。 */
    private static final int OUTPUT_AMOUNT = 4;

    /** 进度条上的格子数。 */
    private static final int BAR_LENGTH = 5;

    private static final String[] FISHING_SOUNDS = {
            "entity.fishing_bobber.throw",
            "entity.fishing_bobber.splash",
            "entity.fishing_bobber.retrieve",
    };

    private static final Random RANDOM = new Random();

    /** 位置 key → 已过去的刻数（用于把 tick 压成 20 刻一次）。 */
    private static final ConcurrentHashMap<String, Integer> AGE = new ConcurrentHashMap<>();
    /** 位置 key → 当前进度（0..5）。 */
    private static final ConcurrentHashMap<String, Integer> PROGRESS = new ConcurrentHashMap<>();

    /** 可作为物品的原版材质池，首次使用时构建。 */
    private static volatile List<Material> allItems;

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"tick".equals(fn) || args.length == 0) return null;
        if (!(args[0] instanceof ScriptMachineItem.TickInfo info)) return null;

        try {
            Location loc = info.block().getLocation();
            String key = locKey(loc);

            int age = AGE.merge(key, 1, Integer::sum);
            // 原脚本是 runTaskTimer(delay=0, period=20)：首次在下一刻执行，此后每 20 刻一次。
            // 计数器从 1 开始，因此「模 20 余 1」正好对齐原触发时刻。
            if (age % PERIOD != 1) return null;

            step(key, loc, info.menu());
        } catch (Throwable t) {
            TT.log("自动太虚枢机 tick 出错: " + t);
        }
        return null;
    }

    /** 一次推进：无进度 → 看输入决定是否起步；有进度 → 加一格，满则结算。 */
    private static void step(String key, Location loc, BlockMenu menu) {
        if (menu == null) return;

        int progress = PROGRESS.getOrDefault(key, 0);
        ItemStack input = menu.getItemInSlot(INPUT_SLOT);

        if (progress == 0) {
            if (isInput(input)) {
                PROGRESS.put(key, 1);
                updateProgress(menu, 1);
                playFishingSound(loc);
            } else {
                updateProgress(menu, 0);
            }
            return;
        }

        progress++;
        if (progress >= PROGRESS_MAX) {
            ItemStack out = randomOutputStack();
            // 先塞输出、成功才扣输入：addToOutput 在输出槽满时返回 false，
            // 原顺序（先 consumeInput）会把命轨星屑吞掉且产出蒸发。
            if (out != null && addToOutput(menu, out)) {
                consumeInput(menu);
                PROGRESS.put(key, 0);
                updateProgress(menu, 0);
                try {
                    loc.getWorld().playSound(loc.clone().add(0.5, 0.5, 0.5),
                            "entity.experience_orb.pickup", 1.0f, 1.5f);
                } catch (Throwable ignored) {
                    // 音效失败忽略
                }
            } else {
                // 输出满（或产出异常）：不扣输入、保持满进度，等玩家取货后下一拍自然重试
                PROGRESS.put(key, PROGRESS_MAX);
                updateProgress(menu, BAR_LENGTH);
            }
        } else {
            PROGRESS.put(key, progress);
            updateProgress(menu, progress);
            playFishingSound(loc);
        }
    }

    /** 位置 key（世界名 + 整数坐标）。 */
    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ':' + loc.getBlockX() + ':'
                + loc.getBlockY() + ':' + loc.getBlockZ();
    }

    /** 随机挑一个钓鱼浮标音效播放。 */
    private static void playFishingSound(Location loc) {
        try {
            String sound = FISHING_SOUNDS[RANDOM.nextInt(FISHING_SOUNDS.length)];
            loc.getWorld().playSound(loc.clone().add(0.5, 0.5, 0.5), sound, 0.8f, 1.0f);
        } catch (Throwable ignored) {
            // 音效失败忽略
        }
    }

    /** 按 id 取粘液物品的拷贝；未注册返回 null。 */
    private static ItemStack sfStack(String id) {
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf == null) sf = SlimefunItem.getById(id.toUpperCase(Locale.ROOT));
            if (sf == null) sf = SlimefunItem.getById(id.toLowerCase(Locale.ROOT));
            return sf == null ? null : sf.getItem().clone();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 物品是否为命轨星屑。 */
    private static boolean isInput(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        try {
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null) return false;
            return INPUT_ID.equalsIgnoreCase(sf.getId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 刷新第 49 格的进度条（屏障 + 名称 + 5 格条形 lore）。 */
    private static void updateProgress(BlockMenu menu, int progress) {
        try {
            ItemStack it = new ItemStack(Material.BARRIER);
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return;

            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection();
            meta.displayName(ser.deserialize("§b捕捞中..."));

            StringBuilder bars = new StringBuilder();
            for (int i = 0; i < BAR_LENGTH; i++) {
                bars.append(i < progress ? "§a▮" : "§7▮");
            }
            meta.lore(Collections.singletonList(ser.deserialize(bars.toString())));
            it.setItemMeta(meta);

            menu.replaceExistingItem(PROGRESS_SLOT, it);
        } catch (Throwable ignored) {
            // 进度条刷新失败不影响主流程
        }
    }

    /**
     * 把产出塞进输出槽：先并入同类堆叠，再找第一个空槽；全部放下返回 true。
     *
     * <p>部分合并不会丢数量：同类堆叠装不下的部分继续找后续槽位/空槽，放不下才整体
     * 返回 false（由调用方决定不扣输入）。原实现一次合并后直接 return，同类堆叠
     * 剩余空间装不下整个堆叠时（如 63 + 4）多出的几个会静默蒸发。</p>
     */
    private static boolean addToOutput(BlockMenu menu, ItemStack stack) {
        try {
            if (stack == null) return false;
            ItemStack remain = stack.clone();

            for (int slot = OUTPUT_SLOT_FROM; slot <= OUTPUT_SLOT_TO && remain.getAmount() > 0; slot++) {
                ItemStack old = menu.getItemInSlot(slot);
                if (old == null || !old.isSimilar(remain)) continue;
                int space = old.getMaxStackSize() - old.getAmount();
                if (space <= 0) continue;
                int add = Math.min(remain.getAmount(), space);
                old.setAmount(old.getAmount() + add);
                remain.setAmount(remain.getAmount() - add);
                menu.replaceExistingItem(slot, old);
            }
            for (int slot = OUTPUT_SLOT_FROM; slot <= OUTPUT_SLOT_TO && remain.getAmount() > 0; slot++) {
                if (menu.getItemInSlot(slot) != null) continue;
                int put = Math.min(remain.getAmount(), remain.getMaxStackSize());
                ItemStack placing = remain.clone();
                placing.setAmount(put);
                menu.replaceExistingItem(slot, placing);
                remain.setAmount(remain.getAmount() - put);
            }
            return remain.getAmount() <= 0;
        } catch (Throwable ignored) {
            // 输出满/槽位异常
            return false;
        }
    }

    /** 消耗一枚命轨星屑。 */
    private static void consumeInput(BlockMenu menu) {
        try {
            ItemStack input = menu.getItemInSlot(INPUT_SLOT);
            if (!isInput(input)) return;
            if (input.getAmount() <= 1) {
                menu.replaceExistingItem(INPUT_SLOT, null);
            } else {
                input.setAmount(input.getAmount() - 1);
                menu.replaceExistingItem(INPUT_SLOT, input);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 随机产出：30% 太虚枢机 ×4，否则随机原版物品 ×4。 */
    private static ItemStack randomOutputStack() {
        try {
            if (RANDOM.nextDouble() < TXSJ_CHANCE) {
                ItemStack stack = sfStack(OUTPUT_ID);
                if (stack != null) stack.setAmount(OUTPUT_AMOUNT);
                return stack;
            }
            List<Material> all = allItems();
            if (all.isEmpty()) return null;
            ItemStack stack = new ItemStack(all.get(RANDOM.nextInt(all.size())));
            stack.setAmount(OUTPUT_AMOUNT);
            return stack;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 全部可作为物品的材质（首次构建一次）。 */
    private static List<Material> allItems() {
        List<Material> cached = allItems;
        if (cached != null) return cached;
        List<Material> tmp = new ArrayList<>();
        for (Material m : Material.values()) {
            try {
                if (m.isItem() && m != Material.AIR) tmp.add(m);
            } catch (Throwable ignored) {
                // 已移除的材质跳过
            }
        }
        allItems = tmp;
        return tmp;
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new BreakListener());
    }

    /** 机器被破坏时清掉该坐标的状态，避免长跑服状态表无限增长。 */
    private static final class BreakListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onBreak(BlockBreakEvent event) {
            try {
                World world = event.getBlock().getWorld();
                Location loc = event.getBlock().getLocation();
                String key = world.getName() + ':' + loc.getBlockX() + ':'
                        + loc.getBlockY() + ':' + loc.getBlockZ();
                AGE.remove(key);
                PROGRESS.remove(key);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        AGE.clear();
        PROGRESS.clear();
    }
}
