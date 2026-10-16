package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.ShieldMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;

/**
 * 二阶段的三件挑战道具：<b>反击之盾</b> / <b>生命甘露</b> / <b>加强生命甘露</b>。
 *
 * <h2>为什么要单独一个类</h2>
 * <p>这三件东西同时被<b>四个地方</b>读写：zqx125 的箭矢结算（举盾挡箭）、
 * Katarina 的护盾与加强甘露、双子的挑战道具、加入时的残留清理。
 * 原脚本把它们做成顶层函数，任何一处改名/改判定都不会报错，只会静默失效。</p>
 *
 * <h2>★ 一个必须保留的判定顺序</h2>
 * <p>{@link #kindOf} 的顺序是<b>先按显示名、再按 PDC 标签</b>，而且名字判定的顺序是
 * 「加强生命甘露 → 反击之盾 → 生命甘露」：</p>
 * <ul>
 *   <li><b>「加强生命甘露」必须排在「生命甘露」前面</b> —— 前者的名字<b>包含</b>后者，
 *       顺序一反就会把加强版认成普通版，回血从 8 变成 3。</li>
 *   <li><b>先名字后标签</b>是因为这两件道具的名字是玩家可见的「§d§l生命甘露」，
 *       而 PDC 是纯内部标记。原脚本优先名字是为了兼容「用命名牌/铁砧改过名的老道具」。</li>
 * </ul>
 */
public final class BossPhaseTwoItems {

    private BossPhaseTwoItems() {}

    /** 反击之盾。 */
    public static final String SHIELD = "counter_shield";
    /** 生命甘露（回 3 血）。 */
    public static final String NECTAR = "life_nectar";
    /** 加强生命甘露（回 8 血，只存在 20 秒）。 */
    public static final String NECTAR_PLUS = "life_nectar_plus";

    /**
     * 道具 PDC 标签键。
     *
     * <p>key 部分<b>必须全小写</b>：{@code NamespacedKey} 的构造器会校验，
     * 大写直接抛异常（本项目已在 {@code RealmScript} 上踩过一次，见转化报告第四节第 9 条）。</p>
     */
    public static final NamespacedKey ITEM_KEY =
            new NamespacedKey(TT.plugin, "tangran_phase2_item");

    /** 护法箭矢的归属标签键（存玩家名，用于排查与反查）。 */
    public static final NamespacedKey ARROW_KEY =
            new NamespacedKey(TT.plugin, "tangran_guardian_arrow");

    /** 磁吸半径（格）。 */
    private static final double MAGNET_RADIUS = 3.0d;
    /** 每 4 tick 最多挪多少格。 */
    private static final double MAGNET_STEP = 0.6d;

    // ==================================================================
    // 造道具
    // ==================================================================

    /**
     * 「发光」：让道具在场地里好找。
     *
     * <p>优先用 1.20.5+ 的 {@code setEnchantmentGlintOverride(true)} ——
     * 纯光效，<b>不占附魔格也不显示附魔行</b>；老服务端没有这个 API 时退回
     * 「隐藏的垃圾附魔」写法（原脚本注释里记录的降级路径）。</p>
     */
    public static boolean addGlint(ItemMeta meta) {
        try {
            if (meta == null) return false;
            try {
                meta.setEnchantmentGlintOverride(true);
                return true;
            } catch (Throwable noApi) {
                // 老服务端：走隐藏附魔
            }
            // 1.21 已移除 Enchantment.LUCK（旧的「万能附魔」键），只有 LUCK_OF_THE_SEA
            Enchantment junk;
            try {
                junk = Enchantment.LUCK_OF_THE_SEA;
            } catch (Throwable ignored) {
                return false;
            }
            meta.addEnchant(junk, 1, true);
            try {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setTag(ItemMeta meta, String tag) {
        try {
            meta.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.STRING, tag);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String getTag(ItemStack stack) {
        try {
            if (stack == null || stack.getType() == Material.AIR) return null;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return null;
            return meta.getPersistentDataContainer().get(ITEM_KEY, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 统一的道具构造：名字 + lore + 标签 + 光效。 */
    public static ItemStack build(Material material, String name, List<String> lore, String tag) {
        try {
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            meta.displayName(BossFx.legacy(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(BossFx.legacyList(lore));
            }
            setTag(meta, tag);
            addGlint(meta);
            item.setItemMeta(meta);
            return item;
        } catch (Throwable t) {
            BossLog.warn("造二阶段道具失败: " + t);
            return new ItemStack(material, 1);
        }
    }

    /** 反击之盾（浅蓝底 + 五条旗帜纹样）。 */
    public static ItemStack createCounterShield() {
        ItemStack item = build(Material.SHIELD, "§6§l反击之盾",
                List.of("§f孩子们我想你了，man!", "§f看我肘击它!"), SHIELD);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof ShieldMeta shield) {
                shield.setBaseColor(DyeColor.LIGHT_BLUE);
                // 1.21 起类名从 BannerPattern 改成了 Pattern（org.bukkit.block.banner.Pattern）
                List<org.bukkit.block.banner.Pattern> patterns = new ArrayList<>();
                patterns.add(new org.bukkit.block.banner.Pattern(
                        DyeColor.BROWN, PatternType.STRIPE_BOTTOM));
                patterns.add(new org.bukkit.block.banner.Pattern(
                        DyeColor.YELLOW, PatternType.TRIANGLE_BOTTOM));
                patterns.add(new org.bukkit.block.banner.Pattern(
                        DyeColor.BROWN, PatternType.RHOMBUS));
                patterns.add(new org.bukkit.block.banner.Pattern(
                        DyeColor.LIGHT_BLUE, PatternType.HALF_HORIZONTAL));
                patterns.add(new org.bukkit.block.banner.Pattern(
                        DyeColor.BROWN, PatternType.CIRCLE));
                shield.setPatterns(patterns);
                item.setItemMeta(meta);
            }
        } catch (Throwable t) {
            BossLog.warn("反击之盾纹样设置失败: " + t);
        }
        return item;
    }

    /**
     * 生命甘露（回 3 血）。
     *
     * <p>材质用<b>虚弱药水</b>而不是普通水瓶：右键由脚本接管，玩家不会真的喝出虚弱。</p>
     */
    public static ItemStack createLifeNectar() {
        ItemStack item = build(Material.POTION, "§d§l生命甘露",
                List.of("§f奇怪的颜色?", "§f一次回复3滴血量！", "§f右键服用。"), NECTAR);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta potion) {
                potion.setBasePotionType(PotionType.WEAKNESS);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return item;
    }

    /** 加强生命甘露（回 8 血，Katarina 阶段专属，只存在 20 秒）。 */
    public static ItemStack createKatarinaNectar() {
        ItemStack item = build(Material.POTION, "§d§l加强生命甘露",
                List.of("§f奇怪的颜色?", "§f一次回复8滴血量！", "§f右键服用。"), NECTAR_PLUS);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta potion) {
                potion.setBasePotionType(PotionType.WEAKNESS);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return item;
    }

    // ==================================================================
    // 识别 / 消耗
    // ==================================================================

    /** 这件物品是哪一种挑战道具；不是则 {@code null}（判定顺序见类注释）。 */
    public static String kindOf(ItemStack stack) {
        try {
            if (stack == null) return null;
            Material type = stack.getType();
            if (type == null || type == Material.AIR) return null;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return null;
            String name = "";
            try {
                name = String.valueOf(meta.displayName());
            } catch (Throwable ignored) {
                name = "";
            }
            // ★ 顺序不能反：前两个名字都包含「生命甘露」
            if (name.contains("加强生命甘露")) return NECTAR_PLUS;
            if (name.contains("反击之盾")) return SHIELD;
            if (name.contains("生命甘露")) return NECTAR;
            String tag = getTag(stack);
            if (tag != null) return tag;
            // 拿着盾/药水却认不出来：写一次日志便于排查（不刷屏，只在真的认不出时）
            if (type == Material.SHIELD || type == Material.POTION) {
                BossLog.warn("二阶段道具无法识别: type=" + type + " name=" + name);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 玩家身上（含副手）有没有这种道具。 */
    public static boolean has(Player player, String tag) {
        try {
            if (player == null || tag == null) return false;
            for (ItemStack it : player.getInventory().getContents()) {
                if (tag.equals(kindOf(it))) return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 消耗一件这种道具。 */
    public static boolean consumeOne(Player player, String tag) {
        try {
            if (player == null || tag == null) return false;
            for (ItemStack it : player.getInventory().getContents()) {
                if (!tag.equals(kindOf(it))) continue;
                ItemStack one = it.clone();
                one.setAmount(1);
                player.getInventory().removeItem(one);
                try {
                    player.updateInventory();
                } catch (Throwable ignored) {
                    // 忽略
                }
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 玩家此刻正举着的反击之盾（没有则 {@code null}）。 */
    public static ItemStack raisedShield(Player player) {
        try {
            if (player == null || !player.isHandRaised()) return null;
            ItemStack main = player.getInventory().getItemInMainHand();
            if (SHIELD.equals(kindOf(main))) return main;
            ItemStack off = player.getInventory().getItemInOffHand();
            if (SHIELD.equals(kindOf(off))) return off;
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 此刻是否算「举盾格挡中」。
     *
     * <p>除了「手里正举着盾」，还认 {@code phaseTwoShieldRaiseGrace > 0} ——
     * 那是原脚本给的一个宽限窗口：玩家按下右键到盾真正举起来之间有几帧延迟，
     * 没有这个宽限就会「明明举了盾还是被箭射中」。</p>
     */
    public static boolean isBlocking(BossState state, Player player) {
        try {
            if (raisedShield(player) != null) return true;
            return state != null && state.phaseTwo.shieldRaiseGrace > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 清掉「不在挑战中的玩家」身上残留的挑战道具。
     *
     * <p>在加入事件里调：玩家可能在上一次挑战中途退出，包里还留着盾/甘露。
     * <b>只清盾与普通甘露</b>，不碰加强甘露（那是 Katarina 阶段的，由该阶段自己管）。</p>
     */
    public static int cleanResidual(Player player) {
        try {
            if (player == null || !player.isOnline()) return 0;
            int removed = 0;
            for (ItemStack it : player.getInventory().getContents()) {
                String kind = kindOf(it);
                if (!SHIELD.equals(kind) && !NECTAR.equals(kind)) continue;
                player.getInventory().remove(it);
                removed++;
            }
            if (removed > 0) {
                try {
                    player.updateInventory();
                } catch (Throwable ignored) {
                    // 忽略
                }
                BossLog.warn("已清理残留挑战道具 x" + removed + "：" + player.getName());
            }
            return removed;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ==================================================================
    // 掉落物磁吸
    // ==================================================================

    /**
     * 丢一个本局道具（原脚本 {@code spawnPhaseTwoDrop}）。
     *
     * <p>{@code forceGroundLayer} 时把 Y <b>钉回石英块上面那一层</b>（防飞天），
     * 并在原高度明显偏高时写一条日志（原脚本记录过这个偏移问题）。
     * 掉落物统一：无重力、永不久置、发光（隔墙可见）、延迟 10 刻可捡、怪物不能捡。</p>
     *
     * <p>Katarina 的加强甘露（{@code BossKatarina}）与 zqx125 的道具刷新共用本方法 ——
     * 两者的差别只在「刷什么、刷在哪、什么时候刷」。</p>
     */
    public static org.bukkit.entity.Item spawnDrop(BossState state, ItemStack stack,
                                                   Location loc, boolean forceGroundLayer) {
        try {
            if (stack == null || loc == null) return null;
            if (forceGroundLayer) {
                BossArena.Region r = (state == null) ? null : state.arena.twoRegion;
                if (r != null) {
                    double rawY = loc.getY();
                    loc = new Location(loc.getWorld(), loc.getX(), r.minY() + 1.1d, loc.getZ());
                    if (rawY > r.minY() + 2.0d) {
                        BossLog.warn("道具原刷新高度偏高，已钉回地面层: y=" + rawY
                                + " → " + loc.getY());
                    }
                }
            }
            org.bukkit.entity.Item item = loc.getWorld().dropItem(loc, stack);
            item.setGravity(false);
            item.setUnlimitedLifetime(true);
            item.setPersistent(true);
            item.setPickupDelay(10);
            item.setCanMobPickup(false);
            // ★ 掉落物实体也发光（隔墙可见），配合示踪粒子，玩家一眼就能找到道具
            item.setGlowing(true);
            state.phaseTwo.drops.add(item);
            return item;
        } catch (Throwable t) {
            BossLog.warn("二阶段道具刷新失败: " + t);
            return null;
        }
    }

    /**
     * 掉落物还活着吗。
     *
     * <p>四条「算没了」的路径都来自原脚本注释里的实测反馈：
     * 被玩家捡走 / 到时间自己消失 / <b>被清理插件（垃圾桶、清掉落物）扫走</b> /
     * 被传出场地。任一条成立，光柱与示踪粒子就要立刻停。</p>
     */
    public static boolean dropAlive(BossState state, org.bukkit.entity.Item item) {        try {
            if (item == null) return false;
            if (!item.isValid()) return false;
            if (item.isDead()) return false;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType() == Material.AIR) return false;
            BossArena.Region r = (state == null) ? null : state.arena.twoRegion;
            if (r != null) {
                Location loc = item.getLocation();
                if (loc == null) return false;
                int x = BossFx.toBlockInt(Math.floor(loc.getX()));
                int y = BossFx.toBlockInt(Math.floor(loc.getY()));
                int z = BossFx.toBlockInt(Math.floor(loc.getZ()));
                if (x < r.minX() - 2 || x > r.maxX() + 2) return false;
                if (z < r.minZ() - 2 || z > r.maxZ() + 2) return false;
                if (y < r.minY() - 4 || y > r.maxY() + 4) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把本局掉落物吸向本局玩家。
     *
     * <p><b>只吸本局那名玩家</b>（原脚本注释：别的玩家靠近不会被吸，免得被抢）。
     * 每 4 tick 挪 {@link #MAGNET_STEP} 格，并把 pickupDelay 压到 0 ⇒ 靠近就自动捡起。</p>
     */
    public static void magnetize(BossState state, Player owner, List<org.bukkit.entity.Item> drops) {
        try {
            if (owner == null || !owner.isOnline() || drops == null || drops.isEmpty()) return;
            Location at = owner.getLocation();
            for (org.bukkit.entity.Item item : drops) {
                if (!dropAlive(state, item)) continue;
                Location il = item.getLocation();
                if (il == null || il.getWorld() == null) continue;
                if (!il.getWorld().getName().equals(at.getWorld().getName())) continue;
                double dx = at.getX() - il.getX();
                double dy = (at.getY() + 0.6d) - il.getY();
                double dz = at.getZ() - il.getZ();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > MAGNET_RADIUS || d < 0.0001d) continue;
                double k = Math.min(MAGNET_STEP, d) / d;
                item.setVelocity(new org.bukkit.util.Vector(dx * k, dy * k, dz * k));
                try {
                    item.setPickupDelay(0);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 剔除已消失的掉落物，返回剔除数量。 */
    public static int pruneDrops(BossState state, List<org.bukkit.entity.Item> drops) {
        try {
            if (drops == null || drops.isEmpty()) return 0;
            int before = drops.size();
            drops.removeIf(item -> !dropAlive(state, item));
            return before - drops.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 从玩家身上读 PDC 标签（供外部排查用）。 */
    public static String tagOf(ItemStack stack) {
        return getTag(stack);
    }

    /** 给物品写 PDC 标签（外部构造同款道具时用）。 */
    public static void tagOf(ItemStack stack, String tag) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;
            setTag(meta, tag);
            stack.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 右键入口
    // ==================================================================

    /** 加强生命甘露一次回多少血（原脚本 {@code KATA_NECTAR_HEAL}）。 */
    public static final int NECTAR_PLUS_HEAL = 8;

    /** 普通生命甘露一次回多少血。 */
    public static final int NECTAR_HEAL = 3;

    /** 举盾宽限窗口（刻）：原脚本给 100 刻 = 5 秒。 */
    private static final int SHIELD_RAISE_GRACE = 100;

    /**
     * 二阶段道具右键统一入口（原脚本 {@code handlePhaseTwoItemInteract}）。
     *
     * <h2>★ 为什么不能再用 {@code event.isCancelled()} 当「这次右键已处理过」的判据</h2>
     * <p>原脚本注释记录了一个真实 bug：二阶段的「防收纳」监听器（同为 {@code LOWEST}、
     * 注册更早）会先把「右键方块」取消掉 —— 玩家拿着生命甘露点方块时，
     * 入口看到 {@code isCancelled()} 就直接 return，表现就是
     * <b>「右键甘露毫无反应、也不回血」</b>（玩家反馈的）。</p>
     *
     * <p>去重换成两条与实际取消状态无关的口径：</p>
     * <ol>
     *   <li><b>主手优先</b>（和原版一致：主手是我们的道具就用主手，否则才看副手）；</li>
     *   <li>同一次右键会派发<b>两只手</b>的事件 —— 按「同一个服务端 tick + 同一种道具」去重，
     *       一次右键只喝一瓶 / 只举一次盾（人类点不出同 tick 的两次右键，不会误挡真实操作）。</li>
     * </ol>
     *
     * @return 是否真的处理了这次右键（处理了调用方就应当取消事件）
     */
    public static boolean handleInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        try {
            if (event == null) return false;
            if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                    && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
                return false;
            }
            Player player = event.getPlayer();
            if (player == null) return false;
            BossState state = BossFight.bySummoner(player);
            // 只在二阶段战斗中进行（phaseTwoStarted 由阶段二门面置位）
            if (state == null || !state.tangran.phaseTwoStarted) return false;

            ItemStack main = player.getInventory().getItemInMainHand();
            ItemStack off = player.getInventory().getItemInOffHand();
            String kind = kindOf(main);
            if (kind == null) kind = kindOf(off);
            if (kind == null) kind = kindOf(event.getItem());
            if (kind == null) return false;

            // 同 tick 同道具 = 同一次右键的另一只手，跳过
            int tk;
            try {
                tk = Bukkit.getCurrentTick();
            } catch (Throwable ignored) {
                tk = -1;
            }
            if (tk >= 0) {
                if (state.phaseTwo.useTick == tk && String.valueOf(state.phaseTwo.useKind).equals(kind)) {
                    return false;
                }
                state.phaseTwo.useTick = tk;
                state.phaseTwo.useKind = kind;
            }

            if (SHIELD.equals(kind)) {
                // 举盾：即使原版因「禁止方块交互」没能起手，也给 5 秒的格挡判定
                state.phaseTwo.shieldRaiseGrace = SHIELD_RAISE_GRACE;
                return true;
            }
            if (NECTAR_PLUS.equals(kind)) {
                if (!consumeOne(player, NECTAR_PLUS)) return false;
                double max = player.getMaxHealth();
                double hp = Math.min(max, player.getHealth() + NECTAR_PLUS_HEAL);
                player.setHealth(hp);
                state.tangran.lastHealth = hp;      // 让禁疗逻辑接受这次回血
                try {
                    player.playSound(player.getLocation(), "entity.generic.drink", 1.0f, 1.1f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                event.setCancelled(true);
                return true;
            }
            if (!NECTAR.equals(kind)) return false;
            if (drinkLifeNectar(player, state)) {
                event.setCancelled(true);
                return true;
            }
            return false;
        } catch (Throwable t) {
            BossLog.warn("二阶段道具右键处理失败: " + t);
            return false;
        }
    }

    /** 喝一瓶生命甘露（回 3 血 + 心形粒子）。 */
    private static boolean drinkLifeNectar(Player player, BossState state) {
        try {
            if (!consumeOne(player, NECTAR)) return false;
            double max = player.getMaxHealth();
            double hp = Math.min(max, player.getHealth() + NECTAR_HEAL);
            player.setHealth(hp);
            state.tangran.lastHealth = hp;          // 让禁疗逻辑接受这次回血
            try {
                player.playSound(player.getLocation(), "entity.generic.drink", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                        player.getLocation().add(0, 2.0d, 0), 3, 0.3d, 0.2d, 0.3d, 0.0d);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        // 无状态
    }

    /** 未使用的容器引用占位：保留 {@link PersistentDataContainer} 的类型说明。 */
    @SuppressWarnings("unused")
    private static final Class<?> PDC = PersistentDataContainer.class;
}
