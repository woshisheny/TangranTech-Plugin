package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.ToolState;
import com.tangran.tangrantech.native_scripts.daofa.TwinTools;

/**
 * 「破法弓」—— {@code scripts/道法/装备.js} 的第三部分（约 1040 行）。
 *
 * <p>自带附魔：力量 20 / 冲击 III / 无限 / 无法破坏（火矢由菜单开关）。</p>
 *
 * <h2>为什么不能走原版拉弓</h2>
 * <p>RSC 对<b>带 script 的物品</b>会无条件取消右键事件 —— 原版拉弓在服务端根本不成立。
 * 所以破法弓自己实现了两种射击模式：</p>
 * <ul>
 *   <li><b>普通模式</b>：本监听器（{@code HIGHEST}，最后跑）把 RSC 的取消<b>撤销</b>，
 *       让原版 {@code ServerPlayerGameMode} 继续执行 {@code item.use} = 正常拉弓，
 *       拉弓动作、满蓄力判定、箭矢轨迹、伤害、无限附魔全按原版走；</li>
 *   <li><b>特殊箭模式</b>：右键立即射出（无需箭），20 格/秒直线、3 秒自毁、
 *       命中造成<b>真伤</b>（8% 最大生命 + 满蓄力伤害，走 {@code setHealth} 绕过护甲）。</li>
 * </ul>
 *
 * <h2>9 项功能开关</h2>
 * <p>菜单里一排 9 个开关：功能 / 特殊箭 / 火矢 / 药水箭 / 治愈箭 / 传送箭 / 冰箭 / 爆炸箭 /
 * 无限附魔。状态存在 {@code 糖b工具/<玩家>.json} 的 {@code Tr_pfg} 键下（见 {@link ToolState}）。
 * 药水箭点右键进二级菜单，可多选（分页展示全部 {@link PotionEffectType}）。</p>
 *
 * <h2>几个必须保留的细节</h2>
 * <ol>
 *   <li><b>末影人预结算</b>：末影人被弹射物命中会瞬移躲开，所以箭靠近到 1.5 格就先结算，
 *       再收掉这支箭。特殊箭与普通箭共用同一套判定，差别只在伤害口径。</li>
 *   <li><b>命中后清无敌帧</b>：真伤走 {@code setHealth} 后原版仍会判「刚被打过」，
 *       把接下来 1 秒内的箭全吞掉 —— 表现为「之后的箭打不动它」。所以必须
 *       {@code setNoDamageTicks(0)}。</li>
 *   <li><b>治愈箭要在伤害环节就作废原版伤害</b>并就地回血，否则会先掉血再回血
 *       （还会留下无敌帧）。用一个已回血记录表避免命中环节重复回血（变成 20%）。</li>
 *   <li><b>传送箭的载具处理</b>：玩家坐在载具上时直接 teleport 会被乘客位置每刻拉回去，
 *       所以要先下具、再把载具按回原位；挂乘客要放到下一拍做（Leaf 有异步实体追踪器，
 *       同拍挂乘客的包可能已被 flush 掉）。</li>
 *   <li><b>识别「我们的箭」有三条判据</b>：特殊箭表 → 普通箭表 → 箭上的标记 →
 *       射手手持破法弓。1.21.11 弓的射出链路被重写过，{@code EntityShootBowEvent}
 *       不一定还会触发，登记表可能是空的，所以兜底判据不能省。</li>
 * </ol>
 */
public final class Pofabow {

    private Pofabow() {}

    /** 破法弓的粘液 id（注册表里会大写化，比较时忽略大小写）。 */
    static final String BOW_ID = "Tr_pfg";
    private static final String BOW_NAME = "破法弓";
    /** 箭上打的标记键。 */
    private static final String ARROW_TAG = "Tr_pfg_arrow";

    /** 特殊箭速度（20 格/秒 = 1 格/刻）。 */
    private static final double SPEED = 1.0;
    /** 特殊箭寿命（ms）。 */
    private static final long SPECIAL_LIFE_MS = 3000L;
    /** 普通箭登记表的过期时间（ms）。 */
    private static final long NORMAL_LIFE_MS = 60_000L;
    /** 药水箭命中后的效果持续时长（刻）——10 秒。 */
    private static final int POTION_TICKS = 20 * 10;
    /**
     * 末影人预结算距离（格）：箭进入这个半径就先结算，绕开原版
     * 「被弹射物命中就瞬移」的规避（见类注释「必须保留的细节」第 1 条）。
     */
    private static final double ENDERMAN_PRE_HIT_RANGE = 1.5d;
    /**
     * 发射瞬间沿视线锁定末影人的最大距离（格）。
     *
     * <p>取 64 是为了覆盖特殊箭的完整射程（寿命 3 秒 × 1 格/刻 = 60 格），
     * 免得远距离瞄准的末影人锁不上、回退到「打不到」的原版表现。</p>
     */
    private static final double ENDERMAN_LOCK_RANGE = 64.0d;

    private static final String TITLE_MAIN = "§d§l破法弓 · 功能菜单";
    private static final String TITLE_MAIN_KEY = "破法弓 · 功能菜单";
    private static final String TITLE_POTION_KEY = "破法弓 · 药水箭类型";

    // ==================================================================
    // 状态
    // ==================================================================

    /** 破法弓的 9 个开关 + 已选药水效果。 */
    public static final class State {
        public boolean on = true;
        public boolean special;
        public boolean flame;
        public boolean infinity = true;
        public boolean potion;
        public List<String> potions = new ArrayList<>();
        public boolean heal;
        public boolean tp;
        public boolean freeze;
        public boolean boom;
    }

    public static State stateOf(Player player) {
        State s = new State();
        try {
            Map<String, Object> raw = TwinTools.rawState(player, BOW_ID);
            s.on = !ToolState.isFalse(raw, "on");
            s.special = ToolState.isTrue(raw, "special");
            s.flame = ToolState.isTrue(raw, "flame");
            s.infinity = !ToolState.isFalse(raw, "infinity");
            s.potion = ToolState.isTrue(raw, "potion");
            s.potions = new ArrayList<>(ToolState.strList(raw, "potions"));
            s.heal = ToolState.isTrue(raw, "heal");
            s.tp = ToolState.isTrue(raw, "tp");
            s.freeze = ToolState.isTrue(raw, "freeze");
            s.boom = ToolState.isTrue(raw, "boom");
        } catch (Throwable ignored) {
            // 用默认值
        }
        return s;
    }

    public static void save(Player player, State st) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("on", st.on);
            value.put("special", st.special);
            value.put("flame", st.flame);
            value.put("infinity", st.infinity);
            value.put("potion", st.potion);
            value.put("potions", new ArrayList<>(st.potions));
            value.put("heal", st.heal);
            value.put("tp", st.tp);
            value.put("freeze", st.freeze);
            value.put("boom", st.boom);
            ToolState.put(player.getName(), BOW_ID, value);
        } catch (Throwable t) {
            TT.log("[装备/破法弓] 状态保存失败: " + t);
        }
    }

    // ==================================================================
    // 物品与附魔
    // ==================================================================

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    /**
     * 去掉各种颜色码。
     *
     * <p>渐变名会把色码插在字与字之间，不剥掉的话 {@code indexOf("破法弓")} 永远匹配不到，
     * 破法弓识别就会失败 → 所有功能失效。</p>
     */
    public static boolean isBow(ItemStack item) {
        try {
            if (item == null || item.getType() != Material.BOW) return false;
            // 只认粘液 id：显示名可被铁砧伪造，普通弓改个名就能冒充破法弓
            String sf = TT.sfIdOf(item);
            return sf != null && sf.equalsIgnoreCase(BOW_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean enchAtLeast(ItemMeta meta, Enchantment ench, int minLevel) {
        try {
            int cur = 0;
            try {
                cur = meta.getEnchantLevel(ench);
            } catch (Throwable ignored) {
                cur = 0;
            }
            if (cur >= minLevel) return false;
            meta.addEnchant(ench, minLevel, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean stripRscGlow(ItemMeta meta) {
        boolean changed = false;
        try {
            if (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA)) {
                meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return changed;
    }

    /**
     * 补破法弓的附魔（只保底，不封顶）。
     *
     * @param flame    true 时额外加火矢，否则摘掉
     * @param infinity true 时保底「无限 I」，false 时摘掉
     */
    public static ItemStack applyEnchant(ItemStack item, boolean flame, boolean infinity) {
        try {
            if (item == null || item.getType() != Material.BOW) return item;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            boolean changed = false;

            if (!meta.isUnbreakable()) {
                meta.setUnbreakable(true);
                changed = true;
            }
            changed |= enchAtLeast(meta, Enchantment.POWER, 20);
            changed |= enchAtLeast(meta, Enchantment.PUNCH, 3);

            if (!infinity) {
                try {
                    if (meta.hasEnchant(Enchantment.INFINITY)) {
                        meta.removeEnchant(Enchantment.INFINITY);
                        changed = true;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            } else {
                changed |= enchAtLeast(meta, Enchantment.INFINITY, 1);
            }

            try {
                if (flame) {
                    int fv = 0;
                    try {
                        fv = meta.getEnchantLevel(Enchantment.FLAME);
                    } catch (Throwable ignored) {
                        fv = 0;
                    }
                    if (fv < 1) {
                        meta.addEnchant(Enchantment.FLAME, 1, true);
                        changed = true;
                    }
                } else if (meta.hasEnchant(Enchantment.FLAME)) {
                    meta.removeEnchant(Enchantment.FLAME);
                    changed = true;
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            changed |= stripRscGlow(meta);
            if (changed) item.setItemMeta(meta);
        } catch (Throwable t) {
            TT.log("[装备/破法弓] 补附魔失败: " + t);
        }
        return item;
    }

    /**
     * 造一把破法弓（优先用注册的物品，取不到退化成普通弓）。
     *
     * <p>没有玩家上下文时用默认档：无火矢、有无限。要按玩家开关定型的走
     * {@link #make(Player)}。</p>
     */
    public static ItemStack make() {
        return make(null);
    }

    /**
     * 造一把破法弓，并按玩家当前的开关状态把附魔<b>一次性定型</b>。
     *
     * <p><b>为什么改成发放时定型</b>：原脚本（以及照抄它的第一版原生实现）是
     * 「发放时只给一把普通弓，等玩家右键时再按状态补一遍附魔」——于是弓刚到手时附魔
     * 恒为默认档（无火矢、有无限），跟玩家菜单里的实际开关对不上；弓被交易/丢给
     * 别的玩家后，附魔还停留在旧主人的状态。改成发放当下就按归属玩家的状态构造
     * （{@link #applyEnchant} 本来就是幂等的保底），拿到手即是最终形态。</p>
     *
     * <p>右键时那次 {@link #applyEnchant} 同步<b>不删</b>，它兜的是另外两种情况：
     * 老档里本来就没附过魔的弓，以及玩家在弓躺在背包里时改过开关。
     * 菜单里切火矢/无限同理，只改主手那一把（见 {@code handleMenuClick}）。</p>
     *
     * @param player 归属玩家；为 null 时按默认状态构造
     */
    public static ItemStack make(Player player) {
        ItemStack bow = LuxianSword.slimefunItem(BOW_ID);
        if (bow == null) bow = new ItemStack(Material.BOW);
        State st = (player == null) ? new State() : stateOf(player);
        return applyEnchant(bow, st.flame, st.infinity);
    }

    // ==================================================================
    // 伤害口径
    // ==================================================================

    private static int powerLevel(Player player) {
        try {
            ItemMeta meta = player.getInventory().getItemInMainHand().getItemMeta();
            if (meta != null) {
                int lv = meta.getEnchantLevel(Enchantment.POWER);
                if (lv > 0) return lv;
            }
        } catch (Throwable ignored) {
            // 落到兜底
        }
        return 0;
    }

    /** 箭的基础伤害（原版口径）：2.0 + 力量 × 0.5 + 0.5。 */
    private static double arrowBaseDamage(Player player) {
        int power = powerLevel(player);
        if (power <= 0) power = 20;   // 兜底：破法弓自带力量 20
        return 2.0 + 0.5 * power + 0.5;
    }

    /** 满蓄力伤害 = 满蓄力箭速 3.0 × 箭基础伤害（力量 20 → 37.5）。 */
    private static double baseDamage(Player player) {
        return 3.0 * arrowBaseDamage(player);
    }

    private static int punchLevel(Player player) {
        try {
            ItemMeta meta = player.getInventory().getItemInMainHand().getItemMeta();
            if (meta != null) return meta.getEnchantLevel(Enchantment.PUNCH);
        } catch (Throwable ignored) {
            // 忽略
        }
        return 0;
    }

    // ==================================================================
    // 箭的追踪
    // ==================================================================

    /** 一支由破法弓射出的箭。{@code healed} 与 {@code arrowBase} 会随命中被写入。 */
    static final class ArrowRec {
        final Arrow arrow;
        final long born = System.currentTimeMillis();
        final double base;
        final int punch;
        final double arrowBase;
        boolean healed;
        /**
         * 发射瞬间沿视线锁定的末影人（可能为 null）。
         *
         * <p>原来是「每刻对每支箭做一次 {@code getNearbyEntities} 盒查询」来找末影人
         * —— 飞行中的箭越多，每刻的盒查询次数越多（N 支箭 = 20N 次/秒），每次都要扫一遍
         * 所在区块的实体表。这里改成发射时沿视线锁一只，之后每刻只对它做 {@code O(1)}
         * 的距离比较，飞行途中不再有盒查询。</p>
         *
         * <p>副作用（已确认可接受）：飞行途中才走进弹道的末影人不再被预结算，
         * 交给原版 {@code ProjectileHitEvent} 正常处理。</p>
         */
        java.util.UUID target;

        ArrowRec(Arrow arrow, double base, int punch, double arrowBase) {
            this.arrow = arrow;
            this.base = base;
            this.punch = punch;
            this.arrowBase = arrowBase;
        }
    }

    /** 特殊箭。 */
    private static final List<ArrowRec> SPECIAL = new ArrayList<>();
    /** 原版拉弓射出的破法弓箭。 */
    private static final List<ArrowRec> NORMAL = new ArrayList<>();
    /** 已在伤害环节回过血的箭（避免命中环节再回一次）。 */
    private static final List<Arrow> HEALED = new ArrayList<>();
    /** 药水菜单的页码。 */
    private static final Map<String, Integer> POTION_PAGE = new ConcurrentHashMap<>();

    private static Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("TangranTech");
    }

    private static ArrowRec findSpecial(Arrow arrow) {
        for (ArrowRec rec : SPECIAL) {
            if (rec.arrow.equals(arrow)) return rec;
        }
        return null;
    }

    private static ArrowRec findNormal(Arrow arrow) {
        for (ArrowRec rec : NORMAL) {
            if (rec.arrow.equals(arrow)) return rec;
        }
        return null;
    }

    private static boolean isHealed(Arrow arrow) {
        for (Arrow a : HEALED) {
            if (a.equals(arrow)) return true;
        }
        return false;
    }

    private static void dropNormal(ArrowRec rec) {
        NORMAL.remove(rec);
    }

    private static boolean hasTag(Arrow arrow) {
        try {
            return arrow.hasMetadata(ARROW_TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这支箭是不是破法弓射的。
     *
     * <p>判据依次是：特殊箭表 → 普通箭表 → 箭上的标记 → 射手手持（或武器栏是）破法弓。
     * 最后一条不能省 —— 1.21.11 的弓射出链路被重写，登记表可能是空的。</p>
     */
    public static boolean isOurArrow(Arrow arrow) {
        try {
            if (arrow == null) return false;
            if (findSpecial(arrow) != null || findNormal(arrow) != null) return true;
            if (hasTag(arrow)) return true;

            if (!(arrow.getShooter() instanceof Player shooter)) return false;
            boolean mine = false;
            try {
                ItemStack weapon = arrow.getWeapon();
                if (weapon != null && isBow(weapon)) mine = true;
            } catch (Throwable ignored) {
                // 忽略
            }
            if (!mine && isBow(shooter.getInventory().getItemInMainHand())) mine = true;
            if (!mine && isBow(shooter.getInventory().getItemInOffHand())) mine = true;

            // 认定是我们的箭就打个标记：之后玩家换手/换物品也还能认出来
            if (mine) tagArrow(arrow);
            return mine;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void tagArrow(Arrow arrow) {
        try {
            Plugin p = plugin();
            if (p != null) arrow.setMetadata(ARROW_TAG, new FixedMetadataValue(p, "1"));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 破法弓箭矢对双子护法的伤害口径（双子按 BossBar 计数器掉血，够不到命中判定也照样结算）。
     *
     * <p>特殊箭 = 真伤口径（目标最大生命 8% + 满蓄力伤害）；普通箭 = 箭速 × 箭基础伤害。
     * 双子护法上限 300，与 BOSS战 的 {@code TWIN_MAX_HP} 一致。</p>
     */
    public static double twinArrowDamage(Arrow arrow, Player player) {
        try {
            double twinMax = 300;
            ArrowRec rec = findSpecial(arrow);
            if (rec != null) return Math.max(1, Math.round(twinMax * 0.08 + rec.base));

            double speed = 0;
            try {
                speed = arrow.getVelocity().length();
            } catch (Throwable ignored) {
                speed = 0;
            }
            if (speed <= 0) speed = 3.0;   // 拿不到速度就按满蓄力算
            ArrowRec norm = findNormal(arrow);
            double base = (norm != null && norm.arrowBase > 0) ? norm.arrowBase : arrowBaseDamage(player);
            return Math.max(1, Math.ceil(speed * base));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    // ==================================================================
    // 发射
    // ==================================================================

    /** 生成一支箭。{@code special=true} 走特殊箭（无重力 + 记录到追踪表）。 */
    static boolean spawnArrow(Player player, boolean special) {
        try {
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection().clone().normalize();
            Arrow arrow = player.getWorld().spawn(eye.add(dir.clone().multiply(0.6)), Arrow.class);
            if (arrow == null) return false;

            try {
                arrow.setShooter(player);
            } catch (Throwable ignored) {
                // 忽略
            }
            tagArrow(arrow);
            try {
                arrow.setDamage(0.0);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                arrow.setGravity(!special);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                arrow.setVelocity(dir.clone().multiply(SPEED));
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (ToolState.isTrue(TwinTools.rawState(player, BOW_ID), "flame")) {
                    arrow.setFireTicks(200);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            } catch (Throwable ignored) {
                // 忽略
            }

            if (special) {
                ArrowRec rec = new ArrowRec(arrow, baseDamage(player), punchLevel(player),
                        arrowBaseDamage(player));
                // 发射时沿视线锁一只末影人；之后每刻只对它做距离比较，不再每刻扫实体盒
                rec.target = lockEnderman(player, eye, dir);
                SPECIAL.add(rec);
            }
            try {
                player.playSound(player.getLocation(),
                        special ? "item.trident.throw" : "entity.arrow.shoot", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable t) {
            TT.log("[装备/破法弓] 发射失败: " + t);
            return false;
        }
    }

    // ==================================================================
    // 命中结算
    // ==================================================================

    /** 特殊箭真伤：8% 目标最大生命 + 满蓄力伤害（走 setHealth = 真实伤害）。 */
    private static void trueDamage(LivingEntity target, double base) {
        try {
            // 无敌目标不吃真伤（setHealth 绕过不了这层保护）
            try {
                if (target.isInvulnerable()) return;
            } catch (Throwable ignored) {
                // 继续
            }
            double dmg = target.getMaxHealth() * 0.08 + base;
            double next = target.getHealth() - dmg;
            if (next <= 0) target.setHealth(0.0);
            else target.setHealth(next);

            // 关键：清掉伤害无敌帧，否则原版会判定这只怪「刚被打过」，
            // 接下来 1 秒内的箭（含普通箭）都会被吞掉
            try {
                target.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                target.setLastDamage(dmg);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                target.getWorld().spawnParticle(org.bukkit.Particle.CRIT,
                        target.getLocation().add(0, 1, 0), 18, 0.3, 0.3, 0.3, 0.05);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                target.getWorld().playSound(target.getLocation(), "item.trident.hit", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static boolean healTarget(LivingEntity target) {
        try {
            double max = target.getMaxHealth();
            double hp = Math.min(max, target.getHealth() + max * 0.1);
            target.setHealth(hp);
            try {
                target.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                        target.getLocation().add(0, 1.2, 0), 12, 0.3, 0.3, 0.3, 0.0);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                target.getWorld().playSound(target.getLocation(), "block.beacon.activate", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 应用药水箭 / 治愈箭 / 传送箭 / 冰箭 / 爆炸箭，并按需补真伤。 */
    private static void applyFeatures(Player player, LivingEntity target, boolean isSpecial, ArrowRec info) {
        State st = stateOf(player);
        String hitSound = null;

        // 1) 药水箭：命中后把所选效果全部作用到目标身上，持续 10 秒
        if (st.on && st.potion && !st.potions.isEmpty()) {
            for (String raw : st.potions) {
                try {
                    PotionEffectType type = resolveEffectType(raw);
                    if (type != null) {
                        try {
                            target.addPotionEffect(new PotionEffect(type, POTION_TICKS, 0, false, true, true));
                        } catch (Throwable ignored) {
                            try {
                                target.addPotionEffect(new PotionEffect(type, POTION_TICKS, 0));
                            } catch (Throwable ignored2) {
                                // 放弃这个效果
                            }
                        }
                    } else {
                        TT.log("[装备/破法弓] 药水箭：解析不到效果 " + raw);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            hitSound = "entity.splash_potion.break";
            try {
                target.getWorld().spawnParticle(org.bukkit.Particle.ENTITY_EFFECT,
                        target.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.1);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // 2) 治愈箭：不掉血，回 10% 最大生命
        boolean healed = false;
        if (st.on && st.heal) {
            healed = true;
            if (info == null || !info.healed) healTarget(target);
            hitSound = "block.beacon.activate";
        }

        // 3) 传送箭：与目标交换位置（玩家坐载具时，目标占玩家原位置并坐上载具）
        if (st.on && st.tp) {
            try {
                Location pl = player.getLocation().clone();
                Location tl = target.getLocation().clone();
                Entity vehicle = null;
                try {
                    vehicle = player.getVehicle();
                } catch (Throwable ignored) {
                    // 忽略
                }
                Location vl = null;
                if (vehicle != null) {
                    try {
                        vl = vehicle.getLocation().clone();
                    } catch (Throwable ignored) {
                        vl = null;
                    }
                }

                // 1) 先下具：挂在载具上时 teleport 会被乘客位置每刻拉回去
                if (vehicle != null) {
                    boolean okLeave;
                    try {
                        okLeave = player.leaveVehicle();
                    } catch (Throwable ignored) {
                        okLeave = false;
                    }
                    if (!okLeave) {
                        try {
                            vehicle.removePassenger(player);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
                // 2) 玩家 → 生物原来站的位置
                try {
                    player.teleport(tl);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 3) 生物 → 玩家原来的位置（坐载具时就是载具位置）
                try {
                    target.teleport(vl != null ? vl : pl);
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    target.setVelocity(new Vector(0, 0, 0));
                } catch (Throwable ignored) {
                    // 忽略
                }
                // 4) 玩家的 teleport 有时会把坐骑一起带走 → 把载具按回原位
                if (vehicle != null && vl != null) {
                    try {
                        if (vehicle.isValid() && vehicle.getLocation().distanceSquared(vl) > 1.0) {
                            vehicle.teleport(vl);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                // 5) 挂乘客放到下一拍做（Leaf 有异步实体追踪器，同拍挂的包可能已被 flush）
                if (vehicle != null) {
                    Entity vehRef = vehicle;
                    LivingEntity mobRef = target;
                    Plugin p = plugin();
                    if (p != null) {
                        Bukkit.getScheduler().runTaskLater(p, () -> {
                            try {
                                if (!vehRef.isValid() || !mobRef.isValid()) return;
                                vehRef.addPassenger(mobRef);
                                mobRef.teleport(vehRef.getLocation());
                                mobRef.setVelocity(new Vector(0, 0, 0));
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }, 1L);
                        // 6) 两拍后核对：没挂上就补挂
                        Bukkit.getScheduler().runTaskLater(p, () -> {
                            try {
                                if (!vehRef.isValid() || !mobRef.isValid()) return;
                                Entity mv = mobRef.getVehicle();
                                boolean riding = mv != null
                                        && mv.getUniqueId().equals(vehRef.getUniqueId());
                                if (!riding) vehRef.addPassenger(mobRef);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }, 2L);
                    }
                }
                hitSound = "entity.enderman.teleport";
                target.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, tl.add(0, 1, 0),
                        40, 0.4, 0.6, 0.4, 0.2);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // 4) 冰箭：禁锢 1 秒
        if (st.on && st.freeze) {
            try {
                LivingEntity mob = target;
                if (mob instanceof Mob m) m.setAI(false);
                mob.setVelocity(new Vector(0, 0, 0));
                try {
                    mob.setFreezeTicks(140);
                } catch (Throwable ignored) {
                    // 忽略
                }
                target.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE,
                        mob.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
                hitSound = "block.glass.break";
                Plugin p = plugin();
                if (p != null) {
                    Bukkit.getScheduler().runTaskLater(p, () -> {
                        try {
                            if (mob.isValid() && mob instanceof Mob m) m.setAI(true);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }, 20L);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // 5) 爆炸箭：按冲击等级 Y 轴击飞（上限 8 格）
        if (st.on && st.boom) {
            try {
                int punch = (info != null) ? info.punch : punchLevel(player);
                int height = Math.min(8, Math.max(0, punch));
                if (height > 0) {
                    Vector v = target.getVelocity();
                    target.setVelocity(new Vector(v.getX(), 0.42 * Math.sqrt(height), v.getZ()));
                }
                hitSound = "entity.generic.explode";
                target.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION,
                        target.getLocation(), 1, 0, 0, 0, 0);
                target.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                        target.getLocation(), 30, 0.4, 0.4, 0.4, 0.06);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // 特殊箭：真伤（治愈箭开着就不打伤害）
        if (isSpecial && !healed) {
            trueDamage(target, info != null ? info.base : baseDamage(player));
            hitSound = "item.trident.hit";
        }

        if (hitSound != null) {
            try {
                target.getWorld().playSound(target.getLocation(), hitSound, 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ==================================================================
    // 药水效果解析
    // ==================================================================

    /**
     * 把配置里的效果名解析成 {@link PotionEffectType}。
     *
     * <p>不同服务端的名字可能是 {@code "nausea"} / {@code "NAUSEA"} /
     * {@code "minecraft:nausea"}，{@code getByName} 只认其中一种，
     * 所以多试几种写法，最后扫全表做大小写不敏感匹配。</p>
     */
    private static PotionEffectType resolveEffectType(String raw) {
        try {
            if (raw == null) return null;
            String s = String.valueOf(raw);
            String tail = s.contains(":") ? s.substring(s.lastIndexOf(':') + 1) : s;
            String[] tries = {s, s.toUpperCase(Locale.ROOT), s.toLowerCase(Locale.ROOT),
                    tail, tail.toUpperCase(Locale.ROOT), tail.toLowerCase(Locale.ROOT)};
            for (String t : tries) {
                try {
                    PotionEffectType found = PotionEffectType.getByName(t);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                    // 换下一种写法
                }
            }
            for (PotionEffectType type : PotionEffectType.values()) {
                String n = type.getName();
                String nt = n.contains(":") ? n.substring(n.lastIndexOf(':') + 1) : n;
                if (nt.equalsIgnoreCase(tail)) return type;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 效果名 → 中文名（覆盖 1.21.11 全部效果，另附旧接口别名兜底）。 */
    private static final Map<String, String> POTION_CN = potionCn();

    private static Map<String, String> potionCn() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("speed", "迅捷");
        m.put("slowness", "缓慢");
        m.put("haste", "急迫");
        m.put("mining_fatigue", "挖掘疲劳");
        m.put("strength", "力量");
        m.put("instant_health", "瞬间治疗");
        m.put("instant_damage", "瞬间伤害");
        m.put("jump_boost", "跳跃提升");
        m.put("nausea", "反胃");
        m.put("regeneration", "生命恢复");
        m.put("resistance", "抗性提升");
        m.put("fire_resistance", "抗火");
        m.put("water_breathing", "水下呼吸");
        m.put("invisibility", "隐身");
        m.put("blindness", "失明");
        m.put("night_vision", "夜视");
        m.put("hunger", "饥饿");
        m.put("weakness", "虚弱");
        m.put("poison", "中毒");
        m.put("wither", "凋零");
        m.put("health_boost", "生命提升");
        m.put("absorption", "伤害吸收");
        m.put("saturation", "饱和");
        m.put("glowing", "发光");
        m.put("levitation", "飘浮");
        m.put("luck", "幸运");
        m.put("unluck", "霉运");
        m.put("slow_falling", "缓降");
        m.put("conduit_power", "潮涌能量");
        m.put("dolphins_grace", "海豚的恩惠");
        m.put("bad_omen", "不祥之兆");
        m.put("hero_of_the_village", "村庄英雄");
        m.put("darkness", "黑暗");
        m.put("trial_omen", "试炼之兆");
        m.put("raid_omen", "袭击之兆");
        m.put("wind_charged", "蓄风");
        m.put("weaving", "盘丝");
        m.put("oozing", "渗浆");
        m.put("infested", "寄生");
        // 旧接口别名
        m.put("slow", "缓慢");
        m.put("fast_digging", "急迫");
        m.put("slow_digging", "挖掘疲劳");
        m.put("increase_damage", "力量");
        m.put("jump", "跳跃提升");
        m.put("confusion", "反胃");
        m.put("damage_resistance", "抗性提升");
        m.put("harm", "瞬间伤害");
        m.put("heal", "瞬间治疗");
        return Map.copyOf(m);
    }

    private static String potionCn(String raw) {
        String key = String.valueOf(raw).toLowerCase(Locale.ROOT);
        if (key.contains(":")) key = key.substring(key.lastIndexOf(':') + 1);
        String cn = POTION_CN.get(key);
        return cn != null ? cn : String.valueOf(raw);
    }

    /** 全部可选效果名（过滤掉 1.21.11 已经没有的「鹦鹉螺之息」）。 */
    private static List<String> potionList() {
        List<String> out = new ArrayList<>();
        try {
            for (PotionEffectType type : PotionEffectType.values()) {
                if (type == null) continue;
                String n = type.getName();
                if (n.toLowerCase(Locale.ROOT).contains("nautilus")) continue;
                out.add(n);
            }
        } catch (Throwable ignored) {
            // 空列表
        }
        return out;
    }

    // ==================================================================
    // 每刻任务
    // ==================================================================

    private static void tick() {
        try {
            long now = System.currentTimeMillis();

            // 普通箭：清尾 + 末影人预结算
            if (!NORMAL.isEmpty()) {
                List<ArrowRec> keep = new ArrayList<>(NORMAL.size());
                for (ArrowRec rec : NORMAL) {
                    boolean ok;
                    try {
                        ok = rec.arrow != null && rec.arrow.isValid();
                    } catch (Throwable ignored) {
                        ok = false;
                    }
                    if (!ok || (now - rec.born) > NORMAL_LIFE_MS) continue;
                    try {
                        Arrow arrow = rec.arrow;
                        LivingEntity pre = endermanOf(rec, arrow.getLocation());
                        if (pre != null) {
                            endermanPreHit(pre, arrow, rec, false);
                            continue;   // 已结算并收箭
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    keep.add(rec);
                }
                NORMAL.clear();
                NORMAL.addAll(keep);
            }

            // 已回血记录清尾
            if (!HEALED.isEmpty()) {
                List<Arrow> keep = new ArrayList<>(HEALED.size());
                for (Arrow a : HEALED) {
                    boolean ok;
                    try {
                        ok = a != null && a.isValid();
                    } catch (Throwable ignored) {
                        ok = false;
                    }
                    if (ok) keep.add(a);
                }
                HEALED.clear();
                HEALED.addAll(keep);
            }

            // 特殊箭：寿命 + 轨迹 + 末影人预结算
            List<ArrowRec> keep = new ArrayList<>(SPECIAL.size());
            for (ArrowRec rec : SPECIAL) {
                Arrow a = rec.arrow;
                boolean ok;
                try {
                    ok = a != null && a.isValid();
                } catch (Throwable ignored) {
                    ok = false;
                }
                if (!ok || (now - rec.born) > SPECIAL_LIFE_MS) {
                    try {
                        if (a != null && a.isValid()) a.remove();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    continue;
                }
                try {
                    Location loc = a.getLocation();
                    LivingEntity pre = endermanOf(rec, loc);
                    if (pre != null) {
                        endermanPreHit(pre, a, rec, true);
                        continue;   // 已结算并收箭
                    }
                    try {
                        loc.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                                loc, 3, 0.03, 0.03, 0.03, 0.0);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                keep.add(rec);
            }
            SPECIAL.clear();
            SPECIAL.addAll(keep);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 半径内找一只活的末影人。
     *
     * <p>用 {@code getNearbyEntitiesByType} 只取末影人 —— 与 LaowuSummonerScript 里的
     * 猫查找是同一个套路。原来是先用 {@code getNearbyEntities} 把半径内<b>全部</b>实体
     * 取回来再逐个 {@code getType() == ENDERMAN} 判一遍，等于每刻为箭周围每件无关实体
     * （掉落物、盔甲架、其他生物…）都白付一次遍历和装箱开销。语义完全等价。</p>
     */
    private static LivingEntity findEndermanNear(Location loc, double radius) {
        try {
            for (org.bukkit.entity.Enderman e : loc.getWorld()
                    .getNearbyEntitiesByType(org.bukkit.entity.Enderman.class, loc, radius, radius, radius)) {
                try {
                    if (e.isValid() && !e.isDead()) return e;
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 发射瞬间沿视线锁定一只末影人，之后每刻只做 {@code O(1)} 的距离比较。
     *
     * <p><b>为什么改</b>：原来是「每刻对每支箭做一次 {@code getNearbyEntities} 盒查询」
     * 来找末影人 —— 飞行中的箭越多，每刻盒查询次数越多（N 支箭 = 20N 次/秒），
     * 每次都要扫一遍所在区块的实体表。这里改成发射时沿视线射一次线，
     * 锁住玩家实际瞄准的那一只，飞行途中不再有盒查询。</p>
     *
     * <p><b>副作用（可接受）</b>：飞行途中才走进弹道的末影人不再被预结算，
     * 交给原版 {@code ProjectileHitEvent} 正常处理。</p>
     */
    private static java.util.UUID lockEnderman(Player player, Location from, Vector dir) {
        try {
            org.bukkit.util.RayTraceResult hit = player.getWorld().rayTraceEntities(
                    from, dir, ENDERMAN_LOCK_RANGE, ENDERMAN_PRE_HIT_RANGE,
                    e -> e instanceof org.bukkit.entity.Enderman);
            if (hit != null && hit.getHitEntity() instanceof org.bukkit.entity.Enderman em) {
                return em.getUniqueId();
            }
        } catch (Throwable ignored) {
            // 服务端不支持带过滤的射线时，退回一次盒查询
            try {
                LivingEntity near = findEndermanNear(from, ENDERMAN_LOCK_RANGE);
                if (near != null) return near.getUniqueId();
            } catch (Throwable ignored2) {
                // 放弃
            }
        }
        return null;
    }

    /**
     * 取「发射时已锁定、且此刻已进入预结算范围」的末影人；没有则返回 null。
     *
     * <p>这是原来每刻 {@code findEndermanNear} 的等价替代：判定口径仍是
     * 「箭当前位置与末影人距离 ≤ {@link #ENDERMAN_PRE_HIT_RANGE}」，
     * 只是不再做实体盒查询 —— 只对锁定的那一只做距离比较。</p>
     */
    private static LivingEntity endermanOf(ArrowRec rec, Location arrowLoc) {
        if (rec == null) return null;
        try {
            if (rec.target != null) {
                org.bukkit.entity.Entity e = Bukkit.getEntity(rec.target);
                if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                    Location el = le.getLocation();
                    if (el.getWorld() != null && el.getWorld() == arrowLoc.getWorld()
                            && el.distance(arrowLoc) <= ENDERMAN_PRE_HIT_RANGE) {
                        return le;      // 常见路径：锁定成功，O(1) 距离比较
                    }
                    return null;
                }
                rec.target = null;      // 目标没了就解锁，交给下面的兜底
            }
            // 没锁定（发射时视线上没有末影人，或目标已失效）：退回一次盒查询。
            // 这是原实现的行为，保留它以免「飞行途中才有末影人靠近」的情况被漏掉。
            return findEndermanNear(arrowLoc, ENDERMAN_PRE_HIT_RANGE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 末影人预结算：靠近到 1.5 格就先结算，再收掉这支箭。
     *
     * <p>特殊箭 = 真伤；普通箭 = 原版口径（命中时的箭速 × 箭基础伤害），走 {@code damage()}
     * 让护甲与事件照常生效。</p>
     */
    private static void endermanPreHit(LivingEntity target, Arrow arrow, ArrowRec rec, boolean isSpecial) {
        try {
            if (!(arrow.getShooter() instanceof Player shooter)) return;
            if (isSpecial) {
                State st = stateOf(shooter);
                if (!(st.on && st.heal)) {
                    trueDamage(target, rec != null ? rec.base : baseDamage(shooter));
                }
            } else {
                double speed = 0;
                try {
                    speed = arrow.getVelocity().length();
                } catch (Throwable ignored) {
                    speed = 0;
                }
                double base = (rec != null && rec.arrowBase > 0) ? rec.arrowBase : arrowBaseDamage(shooter);
                double dmg = Math.max(1.0, Math.ceil(speed * base));
                // 关键：末影人在 EnderMan.hurt() 里，只要 DamageSource 带 IS_PROJECTILE 标签
                // （箭、三叉戟、雪球…）就会随机瞬移并 return false —— 即免疫伤害。
                // 所以这里<b>不能</b>拿 arrow 当伤害源（那样预结算出来的伤害同样被吞掉，
                // 箭还被 remove，等于白射一箭）；必须以「玩家」为源，末影人才会正常掉血。
                try {
                    target.damage(dmg, shooter);
                } catch (Throwable ignored) {
                    try {
                        target.damage(dmg);
                    } catch (Throwable ignored2) {
                        // 放弃
                    }
                }
            }
            if (rec != null && isHealed(arrow)) rec.healed = true;
            applyFeatures(shooter, target, false, rec);
            try {
                arrow.remove();
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static void startTask(Plugin plugin) {
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, Pofabow::tick, 1L, 1L);
        } catch (Throwable t) {
            TT.log("[装备/破法弓] 每刻任务启动失败: " + t);
        }
    }

    // ==================================================================
    // 菜单
    // ==================================================================

    private static final int MENU_CLOSE_SLOT = 22;

    static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    static final class PotionHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static ItemStack button(String matName, String name, List<String> lore) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String l : lore) lines.add(legacy(l));
                meta.lore(lines);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static void refreshMain(Inventory inv, Player player) {
        try {
            State st = stateOf(player);
            ItemStack glass = button("GRAY_STAINED_GLASS_PANE", "§0", null);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);

            // 槽 9~17：功能 / 特殊箭 / 火矢 / 药水箭 / 治愈箭 / 传送箭 / 冰箭 / 爆炸箭 / 无限附魔
            inv.setItem(9, button(st.on ? "LIME_DYE" : "GRAY_DYE",
                    "§e功能：" + (st.on ? "§a开启" : "§c关闭"), List.of("§7点击切换")));
            inv.setItem(10, button(st.special ? "SPECTRAL_ARROW" : "ARROW",
                    "§b特殊箭：" + (st.special ? "§a开启" : "§c关闭"),
                    List.of("§7开启：手持右键无需箭，20格/s直线箭",
                            "§7关闭：普通弓（原版拉弓，需要背包有箭；有无限则消耗1支即可）")));
            inv.setItem(11, button(st.flame ? "BLAZE_POWDER" : "GUNPOWDER",
                    "§6火矢：" + (st.flame ? "§a开启" : "§c关闭"), List.of("§7开启后箭带火焰")));
            inv.setItem(12, button(st.potion ? "LIME_DYE" : "GRAY_DYE",
                    "§d药水箭：" + (st.potion ? "§a开启" : "§c关闭"),
                    List.of("§7左键：切换开关",
                            "§7右键：选择药水类型（已选 §f" + st.potions.size() + " §7种）",
                            "§8已选：" + (st.potions.isEmpty() ? "无"
                                    : potionCn(st.potions.get(0)) + " 等" + st.potions.size() + "种"))));
            inv.setItem(13, button("GOLDEN_APPLE",
                    "§a治愈箭：" + (st.heal ? "§a开启" : "§c关闭"),
                    List.of("§7命中不回伤害，回目标10%最大生命")));
            inv.setItem(14, button("ENDER_PEARL",
                    "§5传送箭：" + (st.tp ? "§a开启" : "§c关闭"),
                    List.of("§7与命中目标交换位置（玩家坐载具时目标占玩家的位置并坐上载具）")));
            inv.setItem(15, button("SNOWBALL",
                    "§b冰箭：" + (st.freeze ? "§a开启" : "§c关闭"), List.of("§7禁锢命中目标 1 秒")));
            inv.setItem(16, button("TNT",
                    "§c爆炸箭：" + (st.boom ? "§a开启" : "§c关闭"),
                    List.of("§7按冲击等级把目标向上击飞（上限8格）")));
            inv.setItem(17, button(st.infinity ? "LIME_DYE" : "GRAY_DYE",
                    "§b无限附魔：" + (st.infinity ? "§a开启" : "§c关闭"),
                    List.of("§7开启：弓保底\"无限 I\"（普通拉弓只需 1 支箭）",
                            "§7关闭：摘掉无限附魔")));
            inv.setItem(MENU_CLOSE_SLOT, button("BARRIER", "§c§l关闭菜单", List.of("§7点击关闭")));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static boolean openMenu(Player player) {
        try {
            Inventory inv = Bukkit.createInventory(new MenuHolder(), 27, legacy(TITLE_MAIN));
            refreshMain(inv, player);
            player.openInventory(inv);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openPotionMenu(Player player, int page) {
        try {
            List<String> list = potionList();
            int per = 45;
            int maxPage = Math.max(0, (list.size() + per - 1) / per - 1);
            int p = Math.max(0, Math.min(page, maxPage));
            POTION_PAGE.put(player.getUniqueId().toString(), p);

            State st = stateOf(player);
            Inventory inv = Bukkit.createInventory(new PotionHolder(), 54,
                    legacy("§d§l破法弓 · 药水箭类型 (第" + (p + 1) + "页)"));
            ItemStack glass = button("GRAY_STAINED_GLASS_PANE", "§0", null);
            for (int i = 0; i < 54; i++) inv.setItem(i, glass);

            for (int k = 0; k < per; k++) {
                int idx = p * per + k;
                if (idx >= list.size()) break;
                String name = list.get(idx);
                boolean chosen = false;
                for (String s : st.potions) {
                    if (String.valueOf(s).equals(name)) {
                        chosen = true;
                        break;
                    }
                }
                String cn = potionCn(name);
                inv.setItem(k, button(chosen ? "LIME_DYE" : "GRAY_DYE",
                        (chosen ? "§a" : "§f") + cn,
                        List.of(chosen ? "§7已选择，点击取消" : "§7点击选择",
                                "§8效果：" + cn, "§8原版名：" + name)));
            }
            inv.setItem(45, button("ARROW", "§e上一页", null));
            inv.setItem(49, button("BARRIER", "§c§l返回", null));
            inv.setItem(53, button("ARROW", "§e下一页", null));
            player.openInventory(inv);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void handleMenuClick(InventoryClickEvent event, Inventory top) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean isMain = isTitle(event.getView(), TITLE_MAIN_KEY);
        boolean isPotion = isTitle(event.getView(), TITLE_POTION_KEY);
        if (!isMain && !isPotion) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();

        if (isPotion) {
            int page = POTION_PAGE.getOrDefault(player.getUniqueId().toString(), 0);
            if (slot == 45) {
                openPotionMenu(player, page - 1);
                return;
            }
            if (slot == 53) {
                openPotionMenu(player, page + 1);
                return;
            }
            if (slot == 49) {
                openMenu(player);
                return;
            }
            if (slot < 0 || slot >= 45) return;

            List<String> list = potionList();
            int idx = page * 45 + slot;
            if (idx >= list.size()) return;

            State st = stateOf(player);
            String name = list.get(idx);
            List<String> out = new ArrayList<>(st.potions.size());
            boolean found = false;
            for (String s : st.potions) {
                if (String.valueOf(s).equals(name)) {
                    found = true;
                    continue;
                }
                out.add(s);
            }
            if (!found) out.add(name);
            st.potions = out;
            save(player, st);
            openPotionMenu(player, page);
            return;
        }

        // 主菜单
        if (slot == MENU_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        State st = stateOf(player);
        boolean modifyEnchant = false;
        if (slot == 9) st.on = !st.on;
        else if (slot == 10) st.special = !st.special;
        else if (slot == 11) {
            st.flame = !st.flame;
            modifyEnchant = true;
        } else if (slot == 12) {
            if (event.isRightClick()) {
                save(player, st);
                openPotionMenu(player, 0);
                return;
            }
            st.potion = !st.potion;
        } else if (slot == 13) st.heal = !st.heal;
        else if (slot == 14) st.tp = !st.tp;
        else if (slot == 15) st.freeze = !st.freeze;
        else if (slot == 16) st.boom = !st.boom;
        else if (slot == 17) {
            st.infinity = !st.infinity;
            modifyEnchant = true;
        } else return;

        save(player, st);
        if (modifyEnchant) {
            try {
                applyEnchant(player.getInventory().getItemInMainHand(), st.flame, st.infinity);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        refreshMain(top, player);
        try {
            player.playSound(player.getLocation(), "ui.button.click", 0.8f, 1.2f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 认菜单靠标题。
     *
     * <p>标题必须从 {@link org.bukkit.inventory.InventoryView} 取 —— 1.21 起
     * {@code Inventory} 已经没有 {@code getTitle()} 了。</p>
     */
    private static boolean isTitle(org.bukkit.inventory.InventoryView view, String key) {
        try {
            return view.getTitle().contains(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 事件
    // ==================================================================

    private static void onInteract(PlayerInteractEvent event) {
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                    && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) return;

            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!isBow(tool)) return;

            // shift + 左键 → 功能菜单
            if (player.isSneaking()
                    && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
                event.setCancelled(true);
                openMenu(player);
                return;
            }
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

            State st = stateOf(player);
            if (st.on && st.special) {
                // 特殊箭模式：立即射出（无需箭），禁止原版拉弓
                event.setCancelled(true);
                spawnArrow(player, true);
                return;
            }
            // 普通模式：交还原版弓 —— 在本监听器（HIGHEST，最后）把 RSC 的取消撤销，
            // 让原版 ServerPlayerGameMode 继续执行 item.use = 正常拉弓
            applyEnchant(tool, st.flame, st.infinity);
            event.setCancelled(false);
            event.setUseItemInHand(Event.Result.DEFAULT);
            event.setUseInteractedBlock(Event.Result.DEFAULT);
        } catch (Throwable t) {
            TT.log("[装备/破法弓] 右键处理异常: " + t);
        }
    }

    /** 原版拉弓成功（普通模式）：登记这支箭，用于命中时附加功能。 */
    private static void onShootBow(EntityShootBowEvent event) {
        try {
            if (!(event.getEntity() instanceof Player shooter)) return;
            ItemStack bow = event.getBow();
            if (!isBow(bow)) bow = shooter.getInventory().getItemInMainHand();
            if (!isBow(bow)) return;
            if (!(event.getProjectile() instanceof Arrow arrow)) return;
            ArrowRec rec = new ArrowRec(arrow, baseDamage(shooter), punchLevel(shooter),
                    arrowBaseDamage(shooter));
            try {
                Vector v = arrow.getVelocity();
                if (v.lengthSquared() > 0.0d) {
                    rec.target = lockEnderman(shooter, arrow.getLocation(), v.clone().normalize());
                }
            } catch (Throwable ignored) {
                // 拿不到速度就不锁定，交给原版命中事件
            }
            NORMAL.add(rec);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 伤害事件：治愈箭就地回血并作废原版伤害；特殊箭的真伤由脚本自己结算。 */
    private static void onDamage(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof Arrow arrow)) return;
            if (!(arrow.getShooter() instanceof Player shooter)) return;
            if (!isOurArrow(arrow)) return;
            if (!(event.getEntity() instanceof LivingEntity target)) return;

            // 双子护法本体不吃实体伤害（BOSS战已 setCancelled），这里把伤害改成扣计数器
            if (BossBattleBridge.twinArrowHit(target, shooter, arrow, twinArrowDamage(arrow, shooter))) {
                event.setCancelled(true);
                return;
            }

            ArrowRec rec = findSpecial(arrow);
            ArrowRec norm = findNormal(arrow);
            State st = stateOf(shooter);
            boolean healOn = st.on && st.heal;
            if (healOn) {
                event.setCancelled(true);
                healTarget(target);
                HEALED.add(arrow);
                if (rec != null) rec.healed = true;
                if (norm != null) norm.healed = true;
                return;
            }
            if (rec != null) {
                // 特殊箭：真伤自己结算，作废原版这一下，避免留下 20 刻无敌帧
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void onHit(ProjectileHitEvent event) {
        try {
            if (!(event.getEntity() instanceof Arrow arrow)) return;
            Entity target = event.getHitEntity();
            if (!(target instanceof LivingEntity living)) return;

            // 打到「双子头顶展示头颅」的盔甲架时，改打本体（盔甲架无敌，打它等于没打）
            Entity real = BossBattleBridge.twinFromDisplay(target);
            if (real instanceof LivingEntity realLiving && realLiving.isValid()) living = realLiving;

            if (!(arrow.getShooter() instanceof Player shooter)) return;
            ArrowRec rec = findSpecial(arrow);
            ArrowRec norm = findNormal(arrow);
            if (rec == null && norm == null && !isOurArrow(arrow)) return;

            // 双子护法：本体不吃实体伤害 —— 伤害结算到 BossBar 计数器上
            if (BossBattleBridge.twinArrowHit(living, shooter, arrow, twinArrowDamage(arrow, shooter))) {
                return;
            }

            if (isHealed(arrow)) {
                if (rec != null) rec.healed = true;
                if (norm != null) norm.healed = true;
            }
            applyFeatures(shooter, living, rec != null, rec != null ? rec : norm);

            if (rec != null) {
                SPECIAL.remove(rec);
                try {
                    arrow.remove();
                } catch (Throwable ignored) {
                    // 忽略
                }
            } else {
                dropNormal(norm);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 破法弓的事件监听。 */
    static final class ListenerImpl implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent event) {
            Pofabow.onInteract(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onShoot(EntityShootBowEvent event) {
            onShootBow(event);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageByEntityEvent event) {
            Pofabow.onDamage(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onHit(ProjectileHitEvent event) {
            Pofabow.onHit(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = event.getView().getTopInventory();
                if (top == null) return;
                if (!isTitle(event.getView(), TITLE_MAIN_KEY)
                        && !isTitle(event.getView(), TITLE_POTION_KEY)) return;
                handleMenuClick(event, top);
            } catch (Throwable t) {
                TT.log("[装备/破法弓] 菜单点击异常: " + t);
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        for (ArrowRec rec : new ArrayList<>(SPECIAL)) {
            try {
                if (rec.arrow != null && rec.arrow.isValid()) rec.arrow.remove();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        SPECIAL.clear();
        NORMAL.clear();
        HEALED.clear();
        POTION_PAGE.clear();
    }
}
