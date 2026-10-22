package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 「太虚枢机获取」的原生实现（对应 {@code scripts/太虚枢机获取.js}）。
 *
 * <p>右键手持 {@code TR_MGXX}（某个道具）：消耗一个，在玩家视线前方 2 格生成一个
 * {@link ItemDisplay} 并让它持续跟随视线；前 100 刻每 2 刻换一次展示物品、
 * 每 4 刻播放一次音高递增的 pling；之后进入 revealing 阶段展示真实结果 40 刻，再清除实体。</p>
 *
 * <p>滚动阶段展示的物品并非最终结果的预览，而是独立的「奖池展示」取样
 * （{@link #poolDisplayItem()}：60% 随机原版物 / 15% 太虚枢机 / 其余空占位），
 * 与原脚本 {@code Tr_getPoolDisplayItem()} 一致。</p>
 *
 * <p>真实结果概率（{@link #roll()}）：60% 随机原版物品 / 15% {@code TR_TXSJ}（太虚枢机）/ 25% 空。
 * 保底：连续 15 次未中太虚枢机，则下一次强制产出并清零计数。</p>
 */
public final class TaixuHubScript implements NativeLifecycleScript {

    /** 触发道具 id。 */
    private static final String ITEM_ID = "TR_MGXX";
    /** 太虚枢机 id。 */
    private static final String REWARD_ID = "TR_TXSJ";

    /** 抽奖冷却（ms），期间再右键只提示剩余时间。 */
    private static final long COOLDOWN_MS = 5000L;
    /** 防连点：两次交互最小间隔（ms）。 */
    private static final long CLICK_THROTTLE_MS = 150L;

    /** 滚动阶段时长（刻）。 */
    private static final int ROLL_TICKS = 100;
    /** 结果展示阶段时长（刻）。 */
    private static final int REVEAL_TICKS = 40;
    /** 展示实体换物品的间隔（刻）。 */
    private static final int SWAP_EVERY = 2;
    /** 播放 pling 音效的间隔（刻）。 */
    private static final int SOUND_EVERY = 4;
    /** 保底阈值：连续未中太虚枢机达到该次数即强制出货。 */
    private static final int PITY_THRESHOLD = 14;

    /** 展示实体缩放。 */
    private static final float DISPLAY_SCALE = 0.7f;
    /** 展示实体距玩家眼睛的距离（格）。 */
    private static final double DISPLAY_DISTANCE = 2.0;
    /** 展示实体传送插值时长（刻）。 */
    private static final int DISPLAY_TELEPORT_DURATION = 2;

    private static final Random RANDOM = new Random();

    /** 玩家 UUID → 进行中的抽奖状态。 */
    private static final Map<UUID, Roll> ROLLS = new ConcurrentHashMap<>();
    /** 玩家 UUID → 冷却截止时间戳（ms）。 */
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次交互时间（ms），用于防连点。 */
    private static final Map<UUID, Long> LAST_CLICK = new ConcurrentHashMap<>();
    /** 玩家 UUID → 保底计数（连续未中太虚枢机的次数）。 */
    private static final Map<UUID, Integer> PITY = new ConcurrentHashMap<>();

    /** 「未中奖」占位展示物。 */
    private static final ItemStack EMPTY_ITEM =
            new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1);

    /** 原版物品池，首次使用时构建一次。 */
    private static volatile List<Material> vanillaPool;

    /**
     * 创造模式专属 / 生存不可得的物品名黑名单（不进奖池）。
     *
     * <p>Bukkit 没有「仅创造」判定 API（{@link Material#getCreativeCategory()} 对生存
     * 物品同样非空），只能按名单过滤；所有刷怪蛋另按 {@code _SPAWN_EGG} 后缀统一排除。
     * 这些物品进了奖池等于免费发创造物品（基岩/屏障/命令方块/刷怪笼），会直接冲垮经济。</p>
     */
    private static final Set<String> CREATIVE_ONLY = Set.of(
            "BEDROCK",
            "BARRIER",
            "END_PORTAL_FRAME",
            "SPAWNER",
            "TRIAL_SPAWNER",
            "VAULT",
            "OMINOUS_VAULT",
            "CREAKING_HEART",
            "COMMAND_BLOCK",
            "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART",
            "STRUCTURE_BLOCK",
            "STRUCTURE_VOID",
            "JIGSAW",
            "DEBUG_STICK",
            "KNOWLEDGE_BOOK",
            "DRAGON_EGG",
            "DRAGON_HEAD",
            "PIGLIN_HEAD",
            "LIGHT",
            "REINFORCED_DEEPSLATE",
            "PETRIFIED_OAK_SLAB",
            "BUDDING_AMETHYST");

    /** 滚动阶段 pling 音效：原脚本枚举名。 */
    private static final String[] AMETHYST_SOUNDS = {
            "block.budding_amethyst.break",
            "block.amethyst_block.break",
            "block.amethyst_cluster.break",
    };
    private static final String[] GHAST_SOUNDS = {"entity.ghast.ambient", "entity.ghast.scream"};
    private static final String[] DRAGON_SOUNDS = {
            "entity.ender_dragon.ambient", "entity.ender_dragon.growl",
    };
    private static final String[] VILLAGER_NO_SOUNDS = {
            "entity.villager.no", "entity.villager.ambient",
    };

    @Override
    public Object invoke(String fn, Object... args) {
        if ("onUse".equals(fn) && args.length > 0
                && args[0] instanceof PlayerInteractEvent event) {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();
            if (item == null) {
                item = player.getInventory().getItemInMainHand();
            }
            if (isMgxx(item)) {
                // 原脚本在 PlayerInteractEvent 监听里对 RIGHT_CLICK_BLOCK 做了取消，
                // 避免右键同时触发方块本身；入口收敛到 onUse 后这段必须跟着搬过来。
                if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    try {
                        event.setCancelled(true);
                    } catch (Throwable ignored) {
                        // 事件不可取消时忽略
                    }
                }
                handleInteract(player);
            }
            return Boolean.FALSE;
        }
        return null;
    }

    /** 右键状态机：节流 → rolling 时提示冷却 → revealing 时先清场 → 开抽。 */
    public static void handleInteract(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();

        if (now - LAST_CLICK.getOrDefault(id, 0L) < CLICK_THROTTLE_MS) return;
        LAST_CLICK.put(id, now);

        Roll current = ROLLS.get(id);
        if (current != null && "rolling".equals(current.state)) {
            long until = COOLDOWN_UNTIL.getOrDefault(id, 0L);
            double remain = Math.max(0, until - now) / 1000.0;
            player.sendMessage("§c§l冷却中... §7§l(剩余 §e§l"
                    + String.format("%.1f", remain) + "§7§l 秒)");
            playSound(player, "entity.enderman.teleport", 1.0f, 1.0f);
            return;
        }
        if (current != null && "revealing".equals(current.state)) {
            clearRoll(player);
        }
        startRoll(player);
    }

    /** 开抽：扣道具 → 定结果 → 生成展示实体 → 启动 tick。 */
    private static boolean startRoll(Player player) {
        UUID id = player.getUniqueId();
        // 保底计数必须在 clearRoll 之前读：清场会把 PITY 一并 remove。
        // 原脚本的 Tr_clearDisplay 只删 followTask/display/state/cooldownUntil，
        // 不动 pityCount；这里若先清再读，保底计数每局都被打成 0，永远触发不了。
        int pityBefore = PITY.getOrDefault(id, 0);

        if (!consumeOne(player, ITEM_ID)) return false;

        clearRoll(player);

        RollResult result;
        if (pityBefore >= PITY_THRESHOLD) {
            result = new RollResult("txsj", stackOf(REWARD_ID), true);
            PITY.put(id, 0);
        } else {
            result = roll();
            if ("txsj".equals(result.type())) PITY.put(id, 0);
            else PITY.put(id, pityBefore + 1);
        }

        Roll roll = new Roll();
        roll.state = "rolling";
        roll.result = result;
        roll.display = spawnDisplay(player);
        ROLLS.put(id, roll);
        COOLDOWN_UNTIL.put(id, System.currentTimeMillis() + COOLDOWN_MS);
        roll.task = Bukkit.getScheduler().runTaskTimer(
                NativePluginHelper.javaPlugin(), () -> tickRoll(player, roll), 0L, 1L);
        return true;
    }

    /** 每次 tick：跟随视线 → 换物品 / 播音效 → 到点结算 → 展示结束清除。 */
    private static void tickRoll(Player player, Roll roll) {
        try {
            if (!player.isOnline()) {
                clearRoll(player);
                return;
            }
            if (!"rolling".equals(roll.state) && !"revealing".equals(roll.state)) {
                clearRoll(player);
                return;
            }

            ItemDisplay display = roll.display;
            if (display != null) {
                try {
                    display.teleport(frontOf(player));
                } catch (Throwable t) {
                    clearRoll(player);
                    return;
                }
            }

            if ("rolling".equals(roll.state)) {
                roll.elapsed++;
                if (display != null && roll.elapsed % SWAP_EVERY == 0) {
                    try {
                        display.setItemStack(poolDisplayItem());
                    } catch (Throwable ignored) {
                        // 展示实体已失效
                    }
                }
                if (roll.elapsed % SOUND_EVERY == 0) {
                    float pitch = (float) Math.min(2.0, 0.5 + (roll.elapsed / 100.0) * 1.5);
                    playSound(player, "block.note_block.pling", 1.0f, pitch);
                }
                if (roll.elapsed >= ROLL_TICKS) {
                    roll.state = "revealing";
                    roll.revealLeft = REVEAL_TICKS;
                    reveal(player, roll);
                }
            } else {
                roll.revealLeft--;
                if (roll.revealLeft <= 0) clearRoll(player);
            }
        } catch (Throwable t) {
            TT.log("太虚枢机滚动任务出错: " + t);
        }
    }

    /** 结算：中太虚枢机 → 发奖 + 大奖提示；随机原版物 → 发奖 + 普通提示；空 → 提示「遗憾」。 */
    private static void reveal(Player player, Roll roll) {
        if (!player.isOnline()) return;

        RollResult result = roll.result;
        ItemStack stack = result.stack();
        /** 最终钉在展示实体上的物品；三个分支各自赋值，取不到物品时保持 null（展示保留滚动时的样子）。 */
        ItemStack showStack = null;

        if ("vanilla".equals(result.type())) {
            if (stack == null) {
                player.sendMessage("§c无法生成原版物品,请联系管理");
            } else {
                giveOrDrop(player, stack);
                sendVanillaWin(player, stack);
                playFirstSound(player, AMETHYST_SOUNDS, 1.0f, 1.0f);
                showStack = stack;
            }
        } else if ("txsj".equals(result.type())) {
            if (stack == null) {
                player.sendMessage("§c无法生成太虚枢机物品,请联系管理");
            } else {
                giveOrDrop(player, stack);
                if (result.pity()) {
                    player.sendMessage("§d§lTOT§f§l,§e§l神明§f§l对§x§2§9§2§4§2§5§l非酋§f§l的怜悯");
                    playFirstSound(player, GHAST_SOUNDS, 1.0f, 1.0f);
                } else {
                    player.sendMessage("§4§l大奖 §f§l获取§x§7§c§7§c§8§5§l太"
                            + "§x§3§a§3§a§4§2§l虚§x§c§f§e§e§f§f§l枢"
                            + "§x§9§e§d§4§f§0§l机 §f§lx1");
                    playFirstSound(player, DRAGON_SOUNDS, 1.0f, 1.0f);
                }
                showStack = stack;
            }
        } else {
            player.sendMessage("§7§l遗憾！ §f§l获取 x1");
            playFirstSound(player, VILLAGER_NO_SOUNDS, 1.0f, 1.0f);
            showStack = EMPTY_ITEM;
        }

        if (roll.display != null && showStack != null) {
            try {
                roll.display.setItemStack(showStack);
            } catch (Throwable ignored) {
                // 展示实体已失效
            }
        }
    }

    /** 滚动阶段的展示取样：60% 随机原版物 / 15% 太虚枢机 / 其余空占位。 */
    private static ItemStack poolDisplayItem() {
        double r = RANDOM.nextDouble();
        if (r < 0.6) {
            ItemStack stack = randomVanillaItem();
            if (stack != null) return stack;
        }
        if (r < 0.75) {
            try {
                ItemStack txsj = stackOf(REWARD_ID);
                if (txsj != null) return txsj;
            } catch (Throwable ignored) {
                // 未注册时降级到占位
            }
        }
        return EMPTY_ITEM;
    }

    /** 真实结果：60% 随机原版物 / 15% 太虚枢机 / 25% 空。 */
    private static RollResult roll() {
        double r = RANDOM.nextDouble();
        if (r < 0.6) {
            return new RollResult("vanilla", randomVanillaItem(), false);
        }
        if (r < 0.75) {
            ItemStack stack = null;
            try {
                stack = stackOf(REWARD_ID);
            } catch (Throwable ignored) {
                // 保持 null，由调用方提示
            }
            return new RollResult("txsj", stack, false);
        }
        return new RollResult("none", null, false);
    }

    /**
     * 播放一个原版音效（按原脚本的命名空间字符串）。
     *
     * <p>{@link Player#playSound(Location, String, float, float)} 的字符串重载在 1.21 仍存在，
     * 因此可以逐字保留原脚本里的音效名；失败时降级到 world 级播放。</p>
     */
    private static void playSound(Player player, String key, float volume, float pitch) {
        if (player == null || key == null || key.isEmpty()) return;
        try {
            player.playSound(player.getLocation(), key, volume, pitch);
        } catch (Throwable t) {
            try {
                World world = player.getWorld();
                world.playSound(player.getLocation(), key, volume, pitch);
            } catch (Throwable ignored) {
                // 音效放不出来不影响抽奖主流程
            }
        }
    }

    /** 依次尝试音效名，播放第一个成功的（等价原脚本 {@code Tr_playFirstSound}）。 */
    private static void playFirstSound(Player player, String[] keys, float volume, float pitch) {
        if (player == null || keys == null) return;
        for (String key : keys) {
            try {
                player.playSound(player.getLocation(), key, volume, pitch);
            } catch (Throwable t) {
                try {
                    player.getWorld().playSound(player.getLocation(), key, volume, pitch);
                } catch (Throwable ignored) {
                    continue;
                }
            }
            return;
        }
    }

    /** 原版物品中奖提示：优先用物品的显示名，失败则退回材质名。 */
    private static void sendVanillaWin(Player player, ItemStack stack) {
        try {
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand();
            net.kyori.adventure.text.TextComponent msg = ser.deserialize("&f&l获取 ")
                    .append(stack.displayName()
                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(ser.deserialize("&f&l x1"));
            player.sendMessage(msg);
            return;
        } catch (Throwable ignored) {
            // 退回纯文本提示
        }
        try {
            player.sendMessage("§f§l获取 §a§l" + stack.getType().name() + " §f§lx1");
        } catch (Throwable ignored) {
            // 放弃
        }
    }

    /** 取注册物品的一份拷贝；未注册返回 null。 */
    private static ItemStack stackOf(String sfid) {
        SlimefunItem item = SlimefunItem.getById(sfid);
        return item == null ? null : item.getItem().clone();
    }

    /** 随机原版物品。 */
    private static ItemStack randomVanillaItem() {
        List<Material> pool = vanillaPool();
        if (pool.isEmpty()) return null;
        return new ItemStack(pool.get(RANDOM.nextInt(pool.size())), 1);
    }

    /**
     * 全部可作为物品的材质（首次构建一次）。
     *
     * <p>原脚本对没有 {@code isItem()} 的老版本做了名字黑名单兜底；本项目目标 1.21，
     * 直接走 {@link Material#isItem()} 即可。同时要排除创造模式专属/生存不可得的物品
     * （见 {@link #CREATIVE_ONLY}），否则奖池能刷出基岩、命令方块这类破坏经济的物品。</p>
     */
    private static List<Material> vanillaPool() {
        List<Material> cached = vanillaPool;
        if (cached != null) return cached;
        List<Material> tmp = new ArrayList<>();
        for (Material m : Material.values()) {
            try {
                if (!m.isItem()) continue;
                if (m.name().endsWith("_SPAWN_EGG")) continue;
                if (CREATIVE_ONLY.contains(m.name())) continue;
                tmp.add(m);
            } catch (Throwable ignored) {
                // 已移除的材质跳过
            }
        }
        vanillaPool = tmp;
        return tmp;
    }

    /** 判断物品是否为触发道具。 */
    public static boolean isMgxx(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String id = TT.sfIdOf(item);
        if (id != null) return ITEM_ID.equalsIgnoreCase(id);
        ItemStack template = stackOf(ITEM_ID);
        return template != null && template.isSimilar(item);
    }

    /** 消耗一个触发道具（优先从非手持槽位扣）。 */
    private static boolean consumeOne(Player player, String sfid) {
        PlayerInventory inv = player.getInventory();
        int held = inv.getHeldItemSlot();

        for (int i = 0; i < 36; i++) {
            if (i == held) continue;
            ItemStack stack = inv.getItem(i);
            if (!isMgxx(stack)) continue;
            takeOne(inv, i, stack);
            return true;
        }

        ItemStack hand = inv.getItemInMainHand();
        if (isMgxx(hand)) {
            if (hand.getAmount() > 1) {
                hand.setAmount(hand.getAmount() - 1);
                inv.setItemInMainHand(hand);
            } else {
                inv.setItemInMainHand(null);
            }
            return true;
        }
        return false;
    }

    /** 扣掉指定槽位的一个物品。 */
    private static void takeOne(PlayerInventory inv, int slot, ItemStack stack) {
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        } else {
            inv.setItem(slot, null);
        }
    }

    /** 发奖：优先进背包，满了就地掉落。 */
    private static void giveOrDrop(Player player, ItemStack stack) {
        try {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            if (leftovers != null && !leftovers.isEmpty()) {
                World world = player.getWorld();
                Location loc = player.getLocation();
                for (ItemStack leftover : leftovers.values()) {
                    world.dropItemNaturally(loc, leftover);
                }
            }
        } catch (Throwable t) {
            try {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            } catch (Throwable ignored) {
                // 放弃
            }
        }
    }

    /** 玩家视线前方的落点。 */
    private static Location frontOf(Player player) {
        Location eye = player.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(DISPLAY_DISTANCE));
    }

    /** 在玩家视线前方生成展示实体。 */
    private static ItemDisplay spawnDisplay(Player player) {
        try {
            ItemDisplay display = player.getWorld().spawn(frontOf(player), ItemDisplay.class);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTeleportDuration(DISPLAY_TELEPORT_DURATION);
            try {
                display.setGlowing(true);
            } catch (Throwable ignored) {
                // 部分服务端不支持发光
            }
            display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                    new Quaternionf()));
            display.setItemStack(poolDisplayItem());
            return display;
        } catch (Throwable t) {
            TT.log("太虚枢机展示实体生成失败: " + t);
            return null;
        }
    }

    /**
     * 清掉某玩家的展示实体与跟随任务。
     *
     * <p>注意：这里会一并清掉 {@link #PITY}。原脚本的 {@code Tr_clearDisplay} 不删
     * {@code pityCount}，但那是全局对象、跨局保留；本实现的 {@link #startRoll} 已在清场
     * <em>之前</em>把计数取出来并在之后重新写回，因此行为等价。</p>
     */
    private static void clearRoll(Player player) {
        UUID id = player.getUniqueId();
        Roll roll = ROLLS.remove(id);
        if (roll != null && roll.task != null) {
            try {
                roll.task.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
        }
        if (roll != null && roll.display != null && roll.display.isValid()) {
            try {
                roll.display.remove();
            } catch (Throwable ignored) {
                // 已移除
            }
        }
        COOLDOWN_UNTIL.remove(id);
        LAST_CLICK.remove(id);
        PITY.remove(id);
    }

    @Override
    public Iterable<Listener> listeners() {
        // 右键入口走 ScriptItem → invoke("onUse") 这一条通道即可；
        // 不再自注册 PlayerInteractEvent（原脚本两条通道都存在，靠 150ms 节流把
        // 第二次调用吃掉，属于「碰巧不出错」。这里改成单一入口，不再依赖节流兜底）。
        return Collections.singletonList(new QuitListener());
    }

    /** 玩家维度的一次抽奖状态。 */
    private static final class Roll {
        String state = "rolling";
        RollResult result;
        ItemDisplay display;
        /** 跟随视线的定时器句柄，清场时取消。 */
        BukkitTask task;
        int elapsed;
        int revealLeft = -1;
    }

    /** 抽奖结果：类型 / 物品 / 是否触发保底。 */
    private record RollResult(String type, ItemStack stack, boolean pity) {}

    /** 玩家下线时清掉遗留实体与任务。 */
    private static final class QuitListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            clearRoll(event.getPlayer());
        }
    }
}
