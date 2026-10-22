package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ScriptMachineItem;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「道法/神晶获取」的原生实现（对应 {@code scripts/道法/神晶获取.js}）。
 * 甘霖神龛（{@code TR_GLSK}）的「凝聚神晶」玩法。
 *
 * <p>菜单第 0~3 格是四种神晶（下品 10 / 中品 50 / 上品 250 / 糖神 1250 糖之力每秒），
 * 第 4 格开始凝聚、第 5 格结束凝聚、第 49 格关闭；产出堆进 9~44 号槽。</p>
 *
 * <p>点「开始凝聚」后：把玩家传送到神龛上方并用一个隐形 {@link ArmorStand} 当坐骑让他「骑」上去，
 * 之后每秒扣一次对应数量的糖之力（经 {@link RealmScript#editRealm} 写
 * {@code addon_configs/糖然科技/境界/<玩家名>.json}），累计 3000 进度（= 3 秒）产出一枚神晶。</p>
 *
 * <p>终止条件：玩家潜行 / 下线 / 死亡 / 传送 / 下车 / 神龛被挖，任一满足即停。
 * 与原脚本一致，<b>糖之力不足时不会中断，只是不推进</b>。</p>
 *
 * <p>与原脚本的差异（修掉一个「重启后失效」的坑）：</p>
 * <ul>
 *   <li>原脚本靠 {@code onPlace} 记录机器坐标（{@code GL_LOCS}），事件里再遍历它与
 *       {@code StorageCacheUtils.getMenu(loc).toInventory()} 逐个比对来认菜单。
 *       这份坐标表是纯内存的 —— <b>服务器重启后已放置的神龛菜单会完全失灵</b>。</li>
 *   <li>本实现改为在 {@code tick} 钩子里维护「机器菜单 Inventory → 坐标」索引：
 *       每刻都会被回调，因此天然自愈；事件处理也从「遍历所有机器」降为一次哈希查找。</li>
 * </ul>
 *
 * <p>另注：原脚本在 {@code EntityDismountEvent} 里判断「下车的载具是不是我们的盔甲架」——
 * 这个事件名是对的（盔甲架不是 {@code Vehicle}，用 {@code VehicleExitEvent} 是抓不到的）。</p>
 */
public final class DivineCrystalScript implements NativeLifecycleScript {

    /** 输出槽区间：9 ~ 44（闭区间）。 */
    private static final int OUTPUT_FROM = 9;
    private static final int OUTPUT_TO = 44;
    /** 菜单判定「点到自己背包」的槽位下界。 */
    private static final int PLAYER_INV_FROM = 54;

    private static final int START_SLOT = 4;
    private static final int STOP_SLOT = 5;
    private static final int CLOSE_SLOT = 49;

    /** 每次扣除的糖之力折算成的进度。 */
    private static final int PROGRESS_PER_TICK = 1000;
    /** 出一枚神晶所需进度（3 次扣除 = 3 秒）。 */
    private static final int PROGRESS_PER_OUTPUT = 3000;
    /** 扣除周期（刻）= 1 秒。 */
    private static final long PERIOD = 20L;

    /** 玩家被传送到的落点（相对神龛方块）。 */
    private static final double RIDE_X = 0.5;
    private static final double RIDE_Y = 1.2;
    private static final double RIDE_Z = 0.5;
    /** 盔甲架生成点（相对神龛方块）。 */
    private static final double STAND_X = 0.5;
    private static final double STAND_Y = 0.8;
    private static final double STAND_Z = 0.5;
    /** 产出特效粒子点（相对神龛方块）。 */
    private static final double FX_Y = 1.5;

    /** 一种神晶：物品 id / 显示名 / 每秒糖之力消耗 / 无物品时的兜底图标材质。 */
    private record Crystal(String id, String name, int cost, String icon) {}

    private static final Crystal[] TYPES = {
            new Crystal("Tr_xpsj", "下品神晶", 10, "QUARTZ"),
            new Crystal("Tr_zpsj", "中品神晶", 50, "PRISMARINE_SHARD"),
            new Crystal("Tr_spsj", "上品神晶", 250, "DIAMOND"),
            new Crystal("Tr_tssj", "糖神神晶", 1250, "NETHER_STAR"),
    };

    /** 神晶 id（小写）→ 是否为合法神晶，用于判断光标上拿的是不是神晶。 */
    private static final Set<String> CRYSTAL_IDS = Set.of(
            "tr_xpsj", "tr_zpsj", "tr_spsj", "tr_tssj");

    /** 坐标 key → 神龛位置。 */
    private static final Map<String, Location> LOCATIONS = new ConcurrentHashMap<>();
    /** 菜单 Inventory → 坐标 key（由 tick 维护，事件期只做一次查表）。 */
    private static final Map<Inventory, String> INV_INDEX = new ConcurrentHashMap<>();
    /** 坐标 key → 运行中的凝聚状态。 */
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    /** 坐标 key → 已选中的神晶序号。 */
    private static final Map<String, Integer> SELECTED = new ConcurrentHashMap<>();
    /** 菜单 Inventory → 已选中的神晶序号（原脚本的 GL_INV_SELECTED）。 */
    private static final Map<Inventory, Integer> INV_SELECTED = new ConcurrentHashMap<>();
    /** 已做过一次菜单初始化的坐标（重启后清空，会自动重新初始化）。 */
    private static final Set<String> INITIALIZED = ConcurrentHashMap.newKeySet();

    @Override
    public Object invoke(String fn, Object... args) {
        if (!"tick".equals(fn) || args.length == 0) return null;
        if (!(args[0] instanceof ScriptMachineItem.TickInfo info)) return null;

        try {
            Location loc = info.block().getLocation();
            String key = locKey(loc);
            LOCATIONS.put(key, loc.clone());

            BlockMenu menu = info.menu();
            if (menu != null) {
                Inventory inv = menu.toInventory();
                INV_INDEX.put(inv, key);
                // 首次见到该机器（含服务器重启后的第一次 tick）时铺一次菜单
                if (INITIALIZED.add(key)) {
                    setupMenu(menu, key);
                }
            }
        } catch (Throwable t) {
            TT.log("神晶获取 tick 出错: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /**
     * 扣除糖之力。
     *
     * <p>返回 false 表示「这次不推进」——原因可能是没开辟境界、或糖之力不足。
     * 与原脚本一致：不足时不会中断凝聚，只是原地等待。</p>
     *
     * <p>文件访问统一走 {@link RealmScript#editRealm}（load → edit → save 同一临界区）。
     * 历史上本类自带一份 {@code realmFile/loadRealm/saveRealm}，与 {@code RealmScript}
     * 拼出同一路径各写各的，存在丢失更新风险，现已删除。</p>
     */
    private static boolean consumeSugar(Player player, int amount) {
        try {
            boolean[] ok = {false};
            if (!RealmScript.editRealm(player, data -> {
                double sugar = Json.dbl(data, "sugarPower", 0);
                if (sugar >= amount) {
                    data.put("sugarPower", sugar - amount);
                    ok[0] = true;
                }
            })) {
                return false;
            }
            return ok[0];
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    private static ItemStack glass(String name, String materialName) {
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            it.setItemMeta(meta);
        }
        return it;
    }

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

    private static net.kyori.adventure.text.Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s);
    }

    /** 某一种神晶的菜单图标（带「已选择」状态）。 */
    private static ItemStack typeStack(Crystal type, boolean selected) {
        ItemStack it = sfStack(type.id());
        if (it == null) {
            Material mat = Material.matchMaterial(type.icon());
            it = new ItemStack(mat == null ? Material.STONE : mat);
        }
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy("§b" + type.name()));
            meta.lore(List.of(
                    legacy("§7每秒消耗 §e" + type.cost() + " 糖之力"),
                    legacy("§7每3秒凝聚 1 个 " + type.name()),
                    legacy(selected ? "§a√ 已选择" : "§e点击选择")));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 铺一次菜单（按当前选中状态）。 */
    private static void setupMenu(BlockMenu menu, String key) {
        try {
            int selected = SELECTED.getOrDefault(key, -1);
            for (int i = 0; i < TYPES.length; i++) {
                menu.replaceExistingItem(i, typeStack(TYPES[i], selected == i));
            }
            menu.replaceExistingItem(START_SLOT, glass("§a§l开始凝聚", "LIME_STAINED_GLASS_PANE"));
            menu.replaceExistingItem(STOP_SLOT, glass("§c§l结束凝聚", "RED_STAINED_GLASS_PANE"));
            for (int s = 45; s < 54; s++) {
                menu.replaceExistingItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
            }
            menu.replaceExistingItem(CLOSE_SLOT, glass("§c关闭", "BARRIER"));
        } catch (Throwable ignored) {
            // 菜单已失效
        }
    }

    /** 把一枚神晶塞进输出槽：先并入同 id 堆叠，再找第一个空槽。 */
    private static void outputToMenu(BlockMenu menu, String sfid) {
        try {
            ItemStack stack = sfStack(sfid);
            if (stack == null) return;
            stack.setAmount(1);

            for (int slot = OUTPUT_FROM; slot <= OUTPUT_TO; slot++) {
                ItemStack old = menu.getItemInSlot(slot);
                if (old == null) continue;
                try {
                    SlimefunItem oldSf = SlimefunItem.getByItem(old);
                    if (oldSf != null
                            && String.valueOf(oldSf.getId()).equalsIgnoreCase(sfid)
                            && old.getAmount() < old.getMaxStackSize()) {
                        old.setAmount(old.getAmount() + 1);
                        menu.replaceExistingItem(slot, old);
                        return;
                    }
                } catch (Throwable ignored) {
                    // 单槽判断失败继续
                }
            }
            for (int slot = OUTPUT_FROM; slot <= OUTPUT_TO; slot++) {
                if (menu.getItemInSlot(slot) == null) {
                    menu.replaceExistingItem(slot, stack.clone());
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 输出满
        }
    }

    // ------------------------------------------------------------------
    // 凝聚流程
    // ------------------------------------------------------------------

    /** 坐标 key。 */
    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ':' + loc.getBlockX() + ':'
                + loc.getBlockY() + ':' + loc.getBlockZ();
    }

    /** 结束某坐标的凝聚：取消任务、移除盔甲架、清掉状态。 */
    private static void stopState(String key) {
        try {
            State state = STATES.remove(key);
            if (state != null) {
                if (state.task != null) {
                    try {
                        state.task.cancel();
                    } catch (Throwable ignored) {
                        // 已结束
                    }
                }
                if (state.stand != null && state.stand.isValid()) {
                    try {
                        state.stand.remove();
                    } catch (Throwable ignored) {
                        // 已移除
                    }
                }
            }
            SELECTED.remove(key);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 结束某玩家正在进行的全部凝聚。 */
    private static void stopAllForPlayer(String playerName) {
        for (Map.Entry<String, State> e : STATES.entrySet()) {
            State st = e.getValue();
            if (st != null && playerName.equals(st.playerName)) {
                stopState(e.getKey());
            }
        }
    }

    /** 开始凝聚。 */
    private static void startProcess(Player player, Location loc, int typeIndex) {
        try {
            String key = locKey(loc);
            int selected = SELECTED.getOrDefault(key, typeIndex);
            stopState(key);
            SELECTED.put(key, selected);

            State state = new State(selected, player.getName());
            STATES.put(key, state);

            try {
                player.teleport(loc.clone().add(RIDE_X, RIDE_Y, RIDE_Z));
            } catch (Throwable ignored) {
                // 传送失败仍继续（骑乘本身会带过去）
            }

            try {
                ArmorStand stand = loc.getWorld().spawn(
                        loc.clone().add(STAND_X, STAND_Y, STAND_Z), ArmorStand.class);
                stand.setGravity(false);
                stand.setVisible(false);
                stand.setInvulnerable(true);
                stand.setMarker(true);
                stand.addPassenger(player);
                state.stand = stand;
            } catch (Throwable ignored) {
                // 盔甲架生成失败：任务仍在，但首次 tick 就会因「不在骑乘」而自停
            }

            state.task = Bukkit.getScheduler().runTaskTimer(
                    com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(),
                    () -> tickProcess(key, loc, state), 0L, PERIOD);
        } catch (Throwable t) {
            TT.log("神晶获取开始凝聚失败: " + t);
        }
    }

    /** 凝聚的每秒推进。 */
    private static void tickProcess(String key, Location loc, State state) {
        try {
            Player p = Bukkit.getPlayerExact(state.playerName);
            boolean stillRiding = state.stand != null && state.stand.isValid()
                    && state.stand.getPassengers().contains(p);
            if (p == null || !p.isOnline() || p.isSneaking() || !stillRiding) {
                stopState(key);
                return;
            }

            if (state.type < 0 || state.type >= TYPES.length) {
                stopState(key);
                return;
            }
            Crystal type = TYPES[state.type];

            // 糖之力不足时原地等待（不中断），与原脚本一致
            if (!consumeSugar(p, type.cost())) return;

            state.progress += PROGRESS_PER_TICK;

            if (state.progress >= PROGRESS_PER_OUTPUT) {
                state.progress -= PROGRESS_PER_OUTPUT;
                BlockMenu menu = com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                        .getMenu(loc);
                if (menu != null) outputToMenu(menu, type.id());
                p.sendMessage("§a您已凝聚出" + type.name() + "x1");
                try {
                    p.playSound(p.getLocation(), "block.beacon.activate", 1.0f, 1.0f);
                    p.playSound(p.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.5f);
                    loc.getWorld().spawnParticle(Particle.CLOUD,
                            loc.clone().add(0.5, FX_Y, 0.5), 8, 0.4, 0.4, 0.4, 0.01);
                } catch (Throwable ignored) {
                    // 音效/粒子失败不影响产出
                }
            }
        } catch (Throwable ignored) {
            // 凝聚任务不抛异常
        }
    }

    /** 一次凝聚的状态。 */
    private static final class State {
        final int type;
        final String playerName;
        ArmorStand stand;
        BukkitTask task;
        int progress;

        State(int type, String playerName) {
            this.type = type;
            this.playerName = playerName;
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new CrystalListener());
    }

    /** 神龛相关的全部事件。 */
    private static final class CrystalListener implements Listener {

        /** 挖掉神龛：立刻结束凝聚并撤掉索引。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onBreak(BlockBreakEvent event) {
            try {
                Location loc = event.getBlock().getLocation();
                String key = locKey(loc);
                if (!LOCATIONS.containsKey(key)) return;
                stopState(key);
                LOCATIONS.remove(key);
                INITIALIZED.remove(key);
                // 先把属于该坐标的菜单 Inventory 收集出来，再统一摘除索引
                // （不能在遍历 INV_INDEX 的同时用 get 反查，那样会先把自己清空）
                java.util.List<Inventory> owned = new java.util.ArrayList<>();
                for (Map.Entry<Inventory, String> e : INV_INDEX.entrySet()) {
                    if (key.equals(e.getValue())) owned.add(e.getKey());
                }
                for (Inventory inv : owned) {
                    INV_INDEX.remove(inv);
                    INV_SELECTED.remove(inv);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 玩家下车（松蹲/被推开）：结束凝聚。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onDismount(EntityDismountEvent event) {
            try {
                Entity dismounted = event.getDismounted();
                for (Map.Entry<String, State> e : STATES.entrySet()) {
                    State st = e.getValue();
                    if (st != null && st.stand == dismounted) {
                        stopState(e.getKey());
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(PlayerDeathEvent event) {
            try {
                stopAllForPlayer(event.getEntity().getName());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onTeleport(PlayerTeleportEvent event) {
            try {
                stopAllForPlayer(event.getPlayer().getName());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onQuit(PlayerQuitEvent event) {
            try {
                stopAllForPlayer(event.getPlayer().getName());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 打开菜单时刷新选中状态。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onOpen(InventoryOpenEvent event) {
            try {
                String key = INV_INDEX.get(event.getInventory());
                if (key == null) return;
                Location loc = LOCATIONS.get(key);
                if (loc == null) return;
                BlockMenu menu = com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                        .getMenu(loc);
                if (menu != null) setupMenu(menu, key);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 菜单点击。 */
        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null) return;
                String key = INV_INDEX.get(top);
                if (key == null) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;

                Location loc = LOCATIONS.get(key);
                if (loc == null) return;
                int slot = event.getRawSlot();

                // 点到自己背包：允许正常整理，只拦 shift 快速移动
                if (slot >= PLAYER_INV_FROM) {
                    if (event.isShiftClick()) event.setCancelled(true);
                    return;
                }

                // 输出槽：只放行「取出」类操作；任何形式的塞入一律拦。
                // 原实现只看光标上有没有非神晶物品，数字键交换（HOTBAR_SWAP）、
                // F 键卸副手（SWAP_OFFHAND）、SWAP_WITH_CURSOR 时光标是空的，
                // 判断被绕过，任意物品都能换进输出槽 —— 改为按动作白名单放行。
                if (slot >= OUTPUT_FROM && slot <= OUTPUT_TO) {
                    switch (event.getAction()) {
                        case PICKUP_ALL:
                        case PICKUP_SOME:
                        case PICKUP_HALF:
                        case PICKUP_ONE:
                        case MOVE_TO_OTHER_INVENTORY:
                        case DROP_ALL_SLOT:
                        case DROP_ONE_SLOT:
                        case COLLECT_TO_CURSOR:
                            return; // 取出 / 移走 / 掉落：放行
                        case PLACE_ALL:
                        case PLACE_SOME:
                        case PLACE_ONE: {
                            // 光标放置：保持原设计，只允许神晶
                            ItemStack cursor = event.getView().getCursor();
                            if (cursor != null && !cursor.getType().isAir()) {
                                String cursorId = "";
                                try {
                                    SlimefunItem cursorSf = SlimefunItem.getByItem(cursor);
                                    if (cursorSf != null) {
                                        cursorId = String.valueOf(cursorSf.getId()).toLowerCase(Locale.ROOT);
                                    }
                                } catch (Throwable ignored) {
                                    // 按非神晶处理
                                }
                                if (CRYSTAL_IDS.contains(cursorId)) return;
                            }
                            event.setCancelled(true);
                            return;
                        }
                        default:
                            // HOTBAR_SWAP / HOTBAR_MOVE_AND_READD / SWAP_WITH_CURSOR /
                            // F 键 SWAP_OFFHAND / CLONE_STACK / bundle 系 / UNKNOWN：全拦
                            event.setCancelled(true);
                            return;
                    }
                }

                event.setCancelled(true);

                if (slot >= 0 && slot < TYPES.length) {
                    SELECTED.put(key, slot);
                    INV_SELECTED.put(top, slot);
                    BlockMenu menu = com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                            .getMenu(loc);
                    if (menu != null) setupMenu(menu, key);
                    return;
                }

                if (slot == START_SLOT) {
                    Integer selected = SELECTED.get(key);
                    if (selected == null) selected = INV_SELECTED.get(top);
                    if (selected == null) {
                        player.sendMessage("§c请先选择神晶种类！");
                        return;
                    }
                    player.closeInventory();
                    startProcess(player, loc, selected);
                    return;
                }

                if (slot == STOP_SLOT) {
                    stopState(key);
                    stopAllForPlayer(player.getName());
                    player.closeInventory();
                    player.sendMessage("§c已结束凝聚！");
                    return;
                }

                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (String key : List.copyOf(STATES.keySet())) {
            stopState(key);
        }
        LOCATIONS.clear();
        INV_INDEX.clear();
        SELECTED.clear();
        INV_SELECTED.clear();
        INITIALIZED.clear();
    }
}
