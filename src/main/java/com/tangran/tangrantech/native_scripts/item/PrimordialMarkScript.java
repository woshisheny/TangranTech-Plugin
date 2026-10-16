package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * 「原初刻印」的原生实现（对应 {@code scripts/原初刻印.js}）。
 *
 * <p>玩法：右键手持 {@code TR_YCKY}（原初刻印）消耗一个，随机抽取 9 种元素任务之一，
 * 全服广播 + 标题提示，限时 60 秒（1200 刻）完成。完成条件各不相同：</p>
 *
 * <table border="1">
 *   <caption>任务表</caption>
 *   <tr><th>#</th><th>条件</th><th>奖励 id</th></tr>
 *   <tr><td>1</td><td>挖任意原版矿石</td><td>TR_FJ 金·锋寂</td></tr>
 *   <tr><td>2</td><td>砍任意原版原木</td><td>TR_SL 木·森罗</td></tr>
 *   <tr><td>3</td><td>跳进水中</td><td>TR_YL 水·渊澜</td></tr>
 *   <tr><td>4</td><td>跳进岩浆</td><td>TR_JM 火·烬灭</td></tr>
 *   <tr><td>5</td><td>挖任意土类方块</td><td>TR_CH 土·尘寰</td></tr>
 *   <tr><td>6</td><td>站位光照 ≥15</td><td>TR_YC 光·曜辰</td></tr>
 *   <tr><td>7</td><td>站位光照 ≤0</td><td>TR_YY 暗·幽湮</td></tr>
 *   <tr><td>8</td><td>遭到雷击</td><td>TR_TE 雷·霆殛</td></tr>
 *   <tr><td>9</td><td>挖任意冰类方块</td><td>TR_XS 冰·雪殇</td></tr>
 * </table>
 *
 * <p>超时、死亡、下线都会清掉进行中的任务。</p>
 *
 * <p>与 1.21 的两处适配：</p>
 * <ol>
 *   <li>原脚本用 {@code Particle.REDSTONE} 画元素色粉尘；1.20.5 起该常量更名为
 *       {@code DUST}，这里做运行期探测以兼容。</li>
 *   <li>原脚本用 {@code player.sendTitle(legacy)}；这里改走 Adventure 的
 *       {@code showTitle}，避免使用已废弃 API，颜色语义不变。</li>
 * </ol>
 */
public final class PrimordialMarkScript implements NativeLifecycleScript {

    /** 触发道具 id。 */
    private static final String ITEM_ID = "TR_YCKY";
    /** 任务时限（刻）= 60 秒。 */
    private static final int TASK_DURATION_TICKS = 1200;

    /** 元素色粉尘的大小。 */
    private static final float DUST_SIZE = 1.2f;
    /** 元素爆发：粉尘粒子数与散布半径。 */
    private static final int BURST_DUST_COUNT = 80;
    private static final double BURST_SPREAD = 1.5;
    /** 元素爆发：主粒子数与散布。 */
    private static final int BURST_MAIN_COUNT = 20;
    private static final int BURST_MAIN_COUNT_LARGE = 25;
    private static final double BURST_MAIN_SPREAD = 0.5;

    private static final Random RANDOM = new Random();

    /** 玩家 UUID → 进行中的任务状态。 */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** 一种元素任务：奖励物品 id / 显示名 / 主题色。 */
    private record Element(String id, String name, Color color) {}

    /** 任务号（1..9）→ 元素。下标 0 空置，便于与任务号直接对应。 */
    private static final Element[] ELEMENTS = new Element[10];

    /** 任务号（1..9）→ 任务说明（含 {@code &} 颜色码）。 */
    private static final String[] TASK_TEXT = new String[10];

    static {
        ELEMENTS[1] = new Element("TR_FJ", "金 · 锋寂", Color.fromRGB(255, 215, 0));
        ELEMENTS[2] = new Element("TR_SL", "木 · 森罗", Color.fromRGB(34, 139, 34));
        ELEMENTS[3] = new Element("TR_YL", "水 · 渊澜", Color.fromRGB(30, 144, 255));
        ELEMENTS[4] = new Element("TR_JM", "火 · 烬灭", Color.fromRGB(255, 69, 0));
        ELEMENTS[5] = new Element("TR_CH", "土 · 尘寰", Color.fromRGB(160, 82, 45));
        ELEMENTS[6] = new Element("TR_YC", "光 · 曜辰", Color.fromRGB(255, 255, 153));
        ELEMENTS[7] = new Element("TR_YY", "暗 · 幽湮", Color.fromRGB(75, 0, 130));
        ELEMENTS[8] = new Element("TR_TE", "雷 · 霆殛", Color.fromRGB(0, 191, 255));
        ELEMENTS[9] = new Element("TR_XS", "冰 · 雪殇", Color.fromRGB(173, 216, 230));

        TASK_TEXT[1] = "挖掘任意原版矿石，获取 &e金·锋寂";
        TASK_TEXT[2] = "砍伐任意原版原木，获取 &a木·森罗";
        TASK_TEXT[3] = "跳进水中，获取 &b水·渊澜";
        TASK_TEXT[4] = "跳进岩浆，获取 &c火·烬灭";
        TASK_TEXT[5] = "挖掘任意土类方块，获取 &6土·尘寰";
        TASK_TEXT[6] = "站在光照 15 的位置，获取 &e光·曜辰";
        TASK_TEXT[7] = "站在光照 0 的位置，获取 &8暗·幽湮";
        TASK_TEXT[8] = "遭到雷击，获取 &b雷·霆殛";
        TASK_TEXT[9] = "挖掘任意冰类方块，获取 &f冰·雪殇";
    }

    /** 元素色粉尘粒子：1.20.5+ 为 DUST，更早为 REDSTONE。 */
    private static final Particle DUST_PARTICLE = resolveDustParticle();

    // ------------------------------------------------------------------
    // 方块分类表
    // ------------------------------------------------------------------

    /** 任务 1：原版矿石（含深层变体与下界矿）。 */
    private static final List<String> ORES = List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE",
            "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
            "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
            "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
            "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE", "ANCIENT_DEBRIS");

    /** 任务 2：原版原木/木头（含去皮与菌柄）。 */
    private static final List<String> LOGS = List.of(
            "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG",
            "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "CRIMSON_STEM", "WARPED_STEM",
            "OAK_WOOD", "SPRUCE_WOOD", "BIRCH_WOOD", "JUNGLE_WOOD", "ACACIA_WOOD",
            "DARK_OAK_WOOD", "MANGROVE_WOOD", "CHERRY_WOOD",
            "CRIMSON_HYPHAE", "WARPED_HYPHAE",
            "STRIPPED_OAK_LOG", "STRIPPED_SPRUCE_LOG", "STRIPPED_BIRCH_LOG",
            "STRIPPED_JUNGLE_LOG", "STRIPPED_ACACIA_LOG", "STRIPPED_DARK_OAK_LOG",
            "STRIPPED_MANGROVE_LOG", "STRIPPED_CHERRY_LOG",
            "STRIPPED_CRIMSON_STEM", "STRIPPED_WARPED_STEM",
            "STRIPPED_OAK_WOOD", "STRIPPED_SPRUCE_WOOD", "STRIPPED_BIRCH_WOOD",
            "STRIPPED_JUNGLE_WOOD", "STRIPPED_ACACIA_WOOD", "STRIPPED_DARK_OAK_WOOD",
            "STRIPPED_MANGROVE_WOOD", "STRIPPED_CHERRY_WOOD",
            "STRIPPED_CRIMSON_HYPHAE", "STRIPPED_WARPED_HYPHAE");

    /** 任务 5：土类方块。 */
    private static final List<String> DIRTS = List.of(
            "DIRT", "GRASS_BLOCK", "PODZOL", "COARSE_DIRT", "ROOTED_DIRT",
            "MYCELIUM", "MUD", "MOSS_BLOCK");

    /** 任务 9：冰类方块。 */
    private static final List<String> ICES = List.of(
            "ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE");

    @Override
    public Object invoke(String fn, Object... args) {
        // 原脚本只靠监听器驱动，没有对外回调函数
        return null;
    }

    /** 运行期探测粉尘粒子常量名。 */
    private static Particle resolveDustParticle() {
        for (String name : new String[] {"DUST", "REDSTONE"}) {
            try {
                return Particle.valueOf(name);
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 把 {@code &} 颜色码转成 {@code §}。 */
    private static String colorize(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    /**
     * 把 {@link Color} 转成原版支持的自定义十六进制格式 {@code &x&r&r&g&g&b&b}。
     * 转换失败退回白色。
     */
    private static String colorToHex(Color color) {
        try {
            String hex = String.format("%02x%02x%02x",
                    color.getRed(), color.getGreen(), color.getBlue());
            StringBuilder sb = new StringBuilder("&x");
            for (int i = 0; i < hex.length(); i++) {
                sb.append('&').append(hex.charAt(i));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "&x&f&f&f&f&f&f";
        }
    }

    /**
     * 判断物品是否为原初刻印。
     *
     * <p><b>刻意只认粘液 id，绝不用显示名兜底</b>：原脚本的注释明确记录过这个坑——
     * 早期版本加过「名字里含『原初刻印』就放行」的兜底，导致铁砧把任意物品改名即可触发任务，
     * 连本脚本产出的奖励「原初刻印的馈赠」都会被误判。此处保持只认 id。</p>
     */
    public static boolean isYcky(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        try {
            String id = TT.sfIdOf(item);
            return id != null && ITEM_ID.equalsIgnoreCase(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从主手扣掉一个原初刻印。 */
    private static void consumeOne(Player player) {
        try {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType().isAir()) return;
            if (item.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                item.setAmount(item.getAmount() - 1);
            }
        } catch (Throwable ignored) {
            // 扣除失败不阻断任务下发
        }
    }

    /** 发奖：优先进背包，满了就地掉落。 */
    private static void giveReward(Player player, String id) {
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf == null) {
                player.sendMessage(colorize("&c&l任务完成，但找不到物品 " + id + "，请联系管理"));
                return;
            }
            ItemStack stack = sf.getItem().clone();
            Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
            if (left != null && !left.isEmpty()) {
                for (ItemStack rest : left.values()) {
                    try {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    } catch (Throwable ignored) {
                        // 单件掉落失败不影响其余
                    }
                }
            }
            player.sendMessage(colorize("&a&l[原初刻印] 任务完成！获得 &e&l" + id));
            player.playSound(player.getLocation(), "entity.player.levelup", 1.0f, 1.2f);
        } catch (Throwable ignored) {
            // 发奖失败不抛给玩家
        }
    }

    /** 三连递增音高的 pling（0 / 4 / 8 刻）。 */
    private static void playTaskSound(Player player) {
        try {
            player.playSound(player.getLocation(), "block.note_block.pling", 1.0f, 1.0f);
            NativePluginHelper.runLater(() -> {
                try {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(),
                                "block.note_block.pling", 1.0f, 1.5f);
                    }
                } catch (Throwable ignored) {
                    // 玩家已下线
                }
            }, 4L);
            NativePluginHelper.runLater(() -> {
                try {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(),
                                "block.note_block.pling", 1.0f, 2.0f);
                    }
                } catch (Throwable ignored) {
                    // 玩家已下线
                }
            }, 8L);
        } catch (Throwable ignored) {
            // 音效失败不阻断
        }
    }

    /** 在玩家眼周撒一圈元素色粉尘，再按任务号补一种主题粒子。 */
    private static void spawnElementBurst(Player player, Color color, int taskNum) {
        try {
            World world = player.getWorld();
            Location eye = player.getEyeLocation();

            if (DUST_PARTICLE != null) {
                Particle.DustOptions dust = new Particle.DustOptions(color, DUST_SIZE);
                for (int i = 0; i < BURST_DUST_COUNT; i++) {
                    try {
                        Location loc = eye.clone().add(
                                (RANDOM.nextDouble() * 2 - 1) * BURST_SPREAD,
                                (RANDOM.nextDouble() * 2 - 1) * BURST_SPREAD,
                                (RANDOM.nextDouble() * 2 - 1) * BURST_SPREAD);
                        world.spawnParticle(DUST_PARTICLE, loc, 0, 0, 0, 0, 0, dust);
                    } catch (Throwable ignored) {
                        // 单颗粒子失败跳过
                    }
                }
            }

            try {
                switch (taskNum) {
                    case 1 -> world.spawnParticle(Particle.CRIT, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.2);
                    case 2 -> world.spawnParticle(Particle.HAPPY_VILLAGER, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.2);
                    case 3 -> world.spawnParticle(Particle.SPLASH, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.2);
                    case 4 -> world.spawnParticle(Particle.FLAME, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.1);
                    case 5 -> world.spawnParticle(Particle.CLOUD, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.1);
                    case 6 -> world.spawnParticle(Particle.END_ROD, eye, BURST_MAIN_COUNT_LARGE,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.1);
                    case 7 -> world.spawnParticle(Particle.ASH, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.1);
                    case 8 -> world.spawnParticle(Particle.ELECTRIC_SPARK, eye, BURST_MAIN_COUNT_LARGE,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.2);
                    case 9 -> world.spawnParticle(Particle.SNOWFLAKE, eye, BURST_MAIN_COUNT,
                            BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, BURST_MAIN_SPREAD, 0.1);
                    default -> { }
                }
            } catch (Throwable ignored) {
                // 主题粒子失败不影响主流程
            }
        } catch (Throwable ignored) {
            // 世界/坐标异常
        }
    }

    /** 全服广播 + 玩家标题。 */
    private static void broadcastTask(Player player, int taskNum, Element element) {
        try {
            String hex = colorToHex(element.color);
            String msg = colorize(
                    "&d&l[原初刻印] &f" + player.getName() + " 触发了任务："
                            + hex + "&l" + element.name
                            + " &r&7| &f" + TASK_TEXT[taskNum]
                            + " &7&l(限时60秒完成)");
            for (Player online : player.getServer().getOnlinePlayers()) {
                try {
                    online.sendMessage(msg);
                } catch (Throwable ignored) {
                    // 单个玩家发送失败跳过
                }
            }

            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand();
            player.showTitle(net.kyori.adventure.title.Title.title(
                    ser.deserialize(hex + "&l" + element.name),
                    ser.deserialize("&f&l(限时60秒完成)"),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(2500),
                            java.time.Duration.ofMillis(500))));
        } catch (Throwable ignored) {
            // 广播失败不影响任务本身
        }
    }

    /** 清掉某玩家进行中的任务（取消计时器）。 */
    private static void clearState(UUID id) {
        try {
            State st = STATES.remove(id);
            if (st != null && st.timerTask != null) {
                try {
                    st.timerTask.cancel();
                } catch (Throwable ignored) {
                    // 已结束
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 下发一个新任务：扣道具 → 抽号 → 挂超时 → 广播 → 音效/粒子。 */
    private static void startNewTask(Player player) {
        try {
            UUID id = player.getUniqueId();
            clearState(id);
            consumeOne(player);

            final int taskNum = RANDOM.nextInt(9) + 1;
            Element element = ELEMENTS[taskNum];

            BukkitTask timer = NativePluginHelper.runLater(() -> {
                try {
                    State cur = STATES.get(id);
                    if (cur == null) return;
                    // 期间若已换过任务，这次超时作废
                    if (cur.task != taskNum) return;
                    clearState(id);
                    player.sendMessage(colorize("&7&l[原初刻印] 任务超时，原初刻印沉寂了..."));
                } catch (Throwable ignored) {
                    // 超时处理不抛异常
                }
            }, TASK_DURATION_TICKS);

            STATES.put(id, new State(taskNum, timer));
            broadcastTask(player, taskNum, element);
            playTaskSound(player);
            spawnElementBurst(player, element.color, taskNum);
        } catch (Throwable ignored) {
            // 下发失败不抛给玩家
        }
    }

    // ------------------------------------------------------------------
    // 事件处理
    // ------------------------------------------------------------------

    /** 右键派发：已有任务则提示「已更新任务」，随后重新抽取。 */
    private static void handleInteract(Player player, ItemStack item) {
        try {
            if (!isYcky(item)) return;
            UUID id = player.getUniqueId();
            if (STATES.containsKey(id)) {
                player.sendMessage(colorize("&e&l[原初刻印] 已更新任务"));
                clearState(id);
            }
            startNewTask(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 破坏方块：任务 1 / 2 / 5 / 9 的完成判定。 */
    private static void handleBlockBreak(BlockBreakEvent event) {
        try {
            if (event.isCancelled()) return;
            Player player = event.getPlayer();
            UUID id = player.getUniqueId();
            State st = STATES.get(id);
            if (st == null) return;

            String type = event.getBlock().getType().name();
            if (st.task == 1 && ORES.contains(type)) {
                clearState(id);
                giveReward(player, ELEMENTS[1].id());
            } else if (st.task == 2 && LOGS.contains(type)) {
                clearState(id);
                giveReward(player, ELEMENTS[2].id());
            } else if (st.task == 5 && DIRTS.contains(type)) {
                clearState(id);
                giveReward(player, ELEMENTS[5].id());
            } else if (st.task == 9 && ICES.contains(type)) {
                clearState(id);
                giveReward(player, ELEMENTS[9].id());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 移动：任务 3 / 4 / 6 / 7 的完成判定（踩水/踩岩浆/光照）。 */
    private static void handleMove(PlayerMoveEvent event) {
        try {
            Player player = event.getPlayer();
            UUID id = player.getUniqueId();
            State st = STATES.get(id);
            if (st == null) return;

            // 注意：原脚本用「玩家所在方块」判定，而非脚下 —— 玩家在水中时
            // getLocation().getBlock() 就是水方块本身。
            Block at = player.getLocation().getBlock();
            Material type = at.getType();

            if (st.task == 3 && type == Material.WATER) {
                clearState(id);
                giveReward(player, ELEMENTS[3].id());
                return;
            }
            if (st.task == 4 && type == Material.LAVA) {
                clearState(id);
                giveReward(player, ELEMENTS[4].id());
                return;
            }
            if (st.task == 6) {
                try {
                    if (at.getLightLevel() >= 15) {
                        clearState(id);
                        giveReward(player, ELEMENTS[6].id());
                        return;
                    }
                } catch (Throwable ignored) {
                    // 光照读取失败跳过
                }
            }
            if (st.task == 7) {
                try {
                    if (at.getLightLevel() <= 0) {
                        clearState(id);
                        giveReward(player, ELEMENTS[7].id());
                        return;
                    }
                } catch (Throwable ignored) {
                    // 光照读取失败跳过
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 受伤：任务 8 的完成判定（遭雷击）。 */
    private static void handleDamage(EntityDamageEvent event) {
        try {
            if (event.isCancelled()) return;
            if (event.getCause() != EntityDamageEvent.DamageCause.LIGHTNING) return;
            if (!(event.getEntity() instanceof Player player)) return;

            UUID id = player.getUniqueId();
            State st = STATES.get(id);
            if (st == null || st.task != 8) return;

            clearState(id);
            giveReward(player, ELEMENTS[8].id());
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 玩家 UUID → 进行中的任务。 */
    private static final class State {
        final int task;
        final BukkitTask timerTask;

        State(int task, BukkitTask timerTask) {
            this.task = task;
            this.timerTask = timerTask;
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        return Collections.singletonList(new EventListener());
    }

    /** 全部事件入口。 */
    public static final class EventListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            try {
                // 只认主手，避免副手再触发一次
                if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
                Player player = event.getPlayer();
                ItemStack item = event.getItem() != null
                        ? event.getItem()
                        : player.getInventory().getItemInMainHand();
                handleInteract(player, item);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onBlockBreak(BlockBreakEvent event) {
            handleBlockBreak(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onMove(PlayerMoveEvent event) {
            handleMove(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDamage(EntityDamageEvent event) {
            handleDamage(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(PlayerDeathEvent event) {
            try {
                clearState(event.getEntity().getUniqueId());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onQuit(PlayerQuitEvent event) {
            try {
                clearState(event.getPlayer().getUniqueId());
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (UUID id : STATES.keySet()) {
            clearState(id);
        }
    }
}
