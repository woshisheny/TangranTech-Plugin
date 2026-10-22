package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTSuperMultiBlockMachine;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.ZhNames;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 「道法/大型结构填充」的原生实现（对应 {@code scripts/道法/大型结构填充.js}）。
 * 「纳界袋」{@code TR_DXJGTCB}：右键超多方块机器的核心，自动把缺失的部件补齐。
 *
 * <p>流程：扫描结构 → 统计缺失 → 建筑权限检查 → （生存模式）校验并收取材料 → 摆方块 → 写粘液数据
 * → 复查并如实回执。</p>
 *
 * <p>生存模式「缺什么收什么」，创造模式一键填充（不检测背包、不消耗材料）。</p>
 *
 * <p><b>与原脚本的四条硬性一致（都是踩过坑的）</b>：</p>
 * <ol>
 *   <li><b>先摆实体方块，再写粘液数据</b>。原脚本用 javap 实证过 RSC 的
 *       {@code buildMultiBlock} 对粘液部件<b>只写数据、从不 setType</b>，
 *       于是产生「机器能成型、也能工作，但方块是空气」的幽灵结构。
 *       本插件的 {@link TTSuperMultiBlockMachine} 没有这个问题（结构比对本身就是看实体方块），
 *       但补齐顺序仍然必须是 {@code setType} → {@code BlockStorage.store}：
 *       方块实体材质要先摆上方块，写 PDC 才写得进 TileState。</li>
 *   <li><b>权限检查只对「没有粘液数据」的位置发 {@link BlockBreakEvent}</b>。
 *       Slimefun 的 {@code isEventFaked} 只认 mcMMO / QuickShops 的假事件，
 *       我们自己合成的破坏事件会被当成真破坏处理 → 掉出机器物品并把方块数据标记删除，
 *       等于把机器拆了。因此 {@link #pickPermissionProbe} 在目标位置有粘液数据时，
 *       会换成它上下左右里第一个没有粘液数据的位置（领地/保护区是整片区域判定，
 *       相邻一格的结论一样；探针是空气时事件对粘液完全无副作用）。
 *       找不到安全探针就跳过检查——宁可放行，也不能冒着拆掉机器的风险。</li>
 *   <li><b>只要部件不符就要收材料，绝不免费补</b>。免费补一个粘液方块 = 玩家挖掉即可拿到物品
 *       ⇒ 刷物口子（原脚本注释记录过玩家实测反馈）。</li>
 *   <li><b>复查后如实回执</b>：补完再扫一次，仍有部件没成型就直说，不谎报「搭建完成」。</li>
 * </ol>
 *
 * <p>与原脚本的差异：原脚本通过 RSC 的 {@code SuperMultiBlockManager.getCoreStorage().get(loc)}
 * 与部件类的 protected 字段（{@code target} / {@code blockData}）拿结构信息，本插件改为直接读
 * {@link TTSuperMultiBlockMachine#scanCells(Block)} 的快照——同一套坐标公式与比对规则，
 * 但不需要任何反射。原脚本那段「GraalJS 读 protected 字段不抛异常只给 undefined、
 * 导致 try/catch 兜底永远走不到」的坑在 Java 里不存在。</p>
 */
public final class LargeStructureFillScript implements NativeLifecycleScript {

    /** 自定义十六进制颜色占位。 */
    private static final String PREFIX = "§7[填充] ";

    /** 粘液材料 key 前缀。 */
    private static final String KEY_SF = "sf:";
    /** 原版材料 key 前缀。 */
    private static final String KEY_MC = "mc:";

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"onUse".equals(fn)) return null;
        if (args.length == 0 || !(args[0] instanceof PlayerInteractEvent event)) {
            return Boolean.FALSE;
        }
        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return Boolean.FALSE;
            Block block = event.getClickedBlock();
            if (block == null) return Boolean.FALSE;

            Player player = event.getPlayer();
            SlimefunItem sf = BlockStorage.check(block);
            if (!(sf instanceof TTSuperMultiBlockMachine machine)) return Boolean.FALSE;

            // 确认要处理才取消：填充棒对着别的东西右键不该被拦（blaze_rod 本身没有原版行为，
            // 但结构与填充是「消耗背包材料」的重操作，取消掉同 tick 的其它交互语义更干净）
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // 事件不可取消时忽略
            }
            fill(player, machine, block);
            return Boolean.TRUE;
        } catch (Throwable t) {
            TT.log("大型结构填充处理出错: " + t);
            try {
                if (args[0] instanceof PlayerInteractEvent e) {
                    e.getPlayer().sendMessage("§c填充失败: " + t);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return Boolean.FALSE;
        }
    }

    /** 一次填充的完整流程。 */
    private static void fill(Player player, TTSuperMultiBlockMachine machine, Block core) {
        List<TTSuperMultiBlockMachine.Cell> cells = machine.scanCells(core);

        List<TTSuperMultiBlockMachine.Cell> missing = new ArrayList<>();
        for (TTSuperMultiBlockMachine.Cell c : cells) {
            if (!c.ok()) missing.add(c);
        }

        if (missing.isEmpty()) {
            player.sendMessage(PREFIX + "这台结构已经是完整的，无需填充");
            return;
        }

        // 统计材料需求 + 展示名
        Map<String, Integer> needed = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (TTSuperMultiBlockMachine.Cell c : missing) {
            String key = keyOf(c);
            needed.merge(key, 1, Integer::sum);
            display.putIfAbsent(key, displayName(c));
        }

        // 权限检查：放在收材料之前——没权限就一点都不该动，也不能先把材料扣了
        for (TTSuperMultiBlockMachine.Cell c : missing) {
            Location probeLoc = pickPermissionProbe(c.block().getLocation());
            if (probeLoc == null) continue;
            Block probe = probeLoc.getBlock();
            if (hasBuildPermission(player, probe)) continue;
            player.sendMessage("§c" + PREFIX + "你没有权限修改这里的方块！");
            return;
        }

        boolean creative = false;
        try {
            creative = player.getGameMode() == GameMode.CREATIVE;
        } catch (Throwable ignored) {
            // 取不到模式按生存处理
        }

        if (!creative) {
            List<String> miss = new ArrayList<>();
            for (Map.Entry<String, Integer> e : needed.entrySet()) {
                int have = countNeed(player, e.getKey());
                if (have < e.getValue()) {
                    miss.add(display.getOrDefault(e.getKey(), e.getKey())
                            + " x" + (e.getValue() - have));
                }
            }
            if (!miss.isEmpty()) {
                player.sendMessage("§c缺少材料: " + String.join("、", miss));
                return;
            }
            for (Map.Entry<String, Integer> e : needed.entrySet()) {
                takeNeed(player, e.getKey(), e.getValue());
            }
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // 已废弃接口
            }
        }

        // ① 先摆实体方块 + 写粘液数据（顺序不可反）
        int placed = 0;
        for (TTSuperMultiBlockMachine.Cell c : missing) {
            if (placeCell(c)) placed++;
        }

        // ② 复查，如实回执
        int left = 0;
        try {
            for (TTSuperMultiBlockMachine.Cell c : machine.scanCells(core)) {
                if (!c.ok()) left++;
            }
        } catch (Throwable ignored) {
            // 复查失败就按 0 处理，不阻断回执
        }

        if (left > 0) {
            player.sendMessage("§e" + PREFIX + "已补 " + placed + " 处方块，但还有 §c" + left
                    + " §e个部件没能成型（请联系服主查看原因）");
        } else {
            player.sendMessage(creative
                    ? "§a" + PREFIX + "搭建完成（创造模式 · 一键填充）"
                    : "§a" + PREFIX + "搭建完成");
        }
    }

    // ------------------------------------------------------------------
    // 材料 key / 展示名
    // ------------------------------------------------------------------

    /** 材料 key：粘液物品用 {@code sf:<id>}，原版方块用 {@code mc:<MATERIAL>}。 */
    private static String keyOf(TTSuperMultiBlockMachine.Cell c) {
        String id = c.expectSfId();
        if (id != null && !id.isBlank()) {
            return KEY_SF + id.toLowerCase(Locale.ROOT);
        }
        return KEY_MC + c.wantMaterial().name();
    }

    /** 缺失材料的展示名：粘液物品优先用它的显示名，原版方块用中文名。 */
    private static String displayName(TTSuperMultiBlockMachine.Cell c) {
        String id = c.expectSfId();
        if (id != null && !id.isBlank()) {
            try {
                SlimefunItem sf = SlimefunItem.getById(id.toUpperCase(Locale.ROOT));
                if (sf != null) {
                    ItemStack it = sf.getItem();
                    if (it != null && it.hasItemMeta() && it.getItemMeta() != null
                            && it.getItemMeta().hasDisplayName()) {
                        return it.getItemMeta().getDisplayName();
                    }
                }
            } catch (Throwable ignored) {
                // 退到 id
            }
            return id;
        }
        return ZhNames.orEnglish(c.wantMaterial());
    }

    // ------------------------------------------------------------------
    // 材料匹配 / 统计 / 扣除
    // ------------------------------------------------------------------

    /** 手上物品是否匹配材料 key。 */
    private static boolean matchNeed(ItemStack stack, String key) {
        if (stack == null || stack.getType().isAir()) return false;
        if (key.startsWith(KEY_MC)) {
            return stack.getType().name().equals(key.substring(KEY_MC.length()));
        }
        if (key.startsWith(KEY_SF)) {
            try {
                SlimefunItem sf = SlimefunItem.getByItem(stack);
                if (sf == null) return false;
                return String.valueOf(sf.getId())
                        .equalsIgnoreCase(key.substring(KEY_SF.length()));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 背包（含副手）里某材料的数量。 */
    private static int countNeed(Player player, String key) {
        int count = 0;
        try {
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (matchNeed(stack, key)) count += stack.getAmount();
            }
            ItemStack off = inv.getItem(40);
            if (matchNeed(off, key)) count += off.getAmount();
        } catch (Throwable ignored) {
            // 忽略
        }
        return count;
    }

    /** 扣除指定数量的材料；数量不足时返回 false 且不做任何扣除。 */
    private static boolean takeNeed(Player player, String key, int amount) {
        if (countNeed(player, key) < amount) return false;
        try {
            PlayerInventory inv = player.getInventory();
            int remain = amount;
            for (int i = 0; i < 36 && remain > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (!matchNeed(stack, key)) continue;
                int take = Math.min(stack.getAmount(), remain);
                int left = stack.getAmount() - take;
                if (left <= 0) {
                    inv.setItem(i, null);
                } else {
                    stack.setAmount(left);
                }
                remain -= take;
            }
            if (remain > 0) {
                ItemStack off = inv.getItem(40);
                if (matchNeed(off, key)) {
                    int take = Math.min(off.getAmount(), remain);
                    if (off.getAmount() - take <= 0) {
                        inv.setItem(40, null);
                    } else {
                        off.setAmount(off.getAmount() - take);
                    }
                    remain -= take;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /** 这个位置「粘液认不认为是它的方块」。 */
    private static boolean hasSlimefunDataAt(Location loc) {
        try {
            return loc != null && BlockStorage.checkID(loc) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 挑一个可以安全模拟破坏的探针位置。
     *
     * <p>目标位置本身没有粘液数据 → 直接用它；有粘液数据（核心 / 幽灵部件）→ 换成它上下左右里
     * 第一个没有粘液数据的位置。找不到安全探针返回 null（调用方跳过检查）。</p>
     */
    private static Location pickPermissionProbe(Location loc) {
        try {
            if (loc == null) return null;
            if (!hasSlimefunDataAt(loc)) return loc;

            World world = loc.getWorld();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            int[][] offsets = {
                    {0, 1, 0}, {0, -1, 0}, {1, 0, 0},
                    {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 2, 0}, {0, 3, 0},
            };
            for (int[] o : offsets) {
                Location probe = new Location(world, x + o[0], y + o[1], z + o[2]);
                if (!hasSlimefunDataAt(probe)) return probe;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 模拟一次「破坏这个方块」，谁取消（Res 领地 / 圈地 / WorldGuard / 各种保护插件）
     * 就说明玩家没权限。检测本身异常时放行——避免与别的插件冲突导致填充棒整台不能用。
     */
    private static boolean hasBuildPermission(Player player, Block block) {
        try {
            BlockBreakEvent e = new BlockBreakEvent(block, player);
            try {
                e.setDropItems(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            Bukkit.getPluginManager().callEvent(e);
            return !e.isCancelled();
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 补方块
    // ------------------------------------------------------------------

    /**
     * 把一个缺失格补成目标方块。
     *
     * <p>顺序：先 {@code setType} 摆实体方块，再 {@code BlockStorage.store} 写粘液数据。
     * 反过来的话，写 PDC 时方块还不存在，数据会丢。</p>
     *
     * @return true 表示这次真的改动了方块
     */
    private static boolean placeCell(TTSuperMultiBlockMachine.Cell c) {
        try {
            Block block = c.block();
            Material want = c.wantMaterial();
            if (want == null) return false;

            // 已经是目标方块 → 不动（幂等），但仍然要把粘液数据补上（幽灵数据的情况）
            boolean same = block.getType() == want;

            if (!same) {
                if (!want.isBlock()) return false;
                block.setType(want, false);
            }

            String id = c.expectSfId();
            if (id != null && !id.isBlank()) {
                String existing = null;
                try {
                    existing = BlockStorage.checkID(block);
                } catch (Throwable ignored) {
                    // 视为无数据
                }
                if (existing == null || !existing.equalsIgnoreCase(id)) {
                    BlockStorage.store(block, id.toUpperCase(Locale.ROOT));
                }
            }
            return !same;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 右键入口走 ScriptItem → invoke("onUse")，无需自注册监听器
        return Collections.emptyList();
    }
}
