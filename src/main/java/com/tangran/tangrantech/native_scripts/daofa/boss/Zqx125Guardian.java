package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 第一位护法 <b>zqx125</b>（封号「天殛」，100 血，本体 = 无敌骷髅）。
 *
 * <h2>这位护法的设计（照抄原脚本，含注释里的踩坑记录）</h2>
 * <ul>
 *   <li><b>本体只是「演出道具」</b>：完全无敌、不可被推动、{@code setAware(false)} 让原版 AI
 *       完全不参与 —— 移动/瞄准/攻击全部由脚本接管。真实血量在 BossBar 上，
 *       本体血量拉到 1024 只是「免得被顺手几刀秒掉」的伪装。</li>
 *   <li><b>贴地追击</b>：每刻朝玩家挪 0.25 格（5 格/秒），贴到 2.2 格就停 ——
 *       「不要把玩家顶穿」。用 teleport 而不是速度，免得被物理/摩擦吞掉。</li>
 *   <li><b>随血量下降的五个档位</b>：箭更密更快、雷击波更密更大、铁砧更多，
 *       每进一档还触发一次「狂暴」（360° 无死角直线箭群，持续 2 秒）+ 补一瓶生命甘露。</li>
 *   <li><b>举盾挡箭</b>：箭命中时若玩家举着「反击之盾」→ 摧毁箭矢并给护法 10 点伤害；
 *       否则玩家固定扣 1 滴血。</li>
 * </ul>
 *
 * <h2>★ 三处必须保留的实现细节</h2>
 * <ol>
 *   <li><b>箭矢命中判定走「相对位移扫掠」</b>（{@link BossFx#segmentsHitPlayer}），
 *       不是「在胸口放半径 0.8 的球采样」。原脚本注释记录了球判的缺陷：
 *       球心固定在胸口、玩家高速位移时不准，而且贴脸（箭在同一 tick 内掠过）会漏判 ——
 *       表现就是「贴脸举盾格挡，箭被原版弹回却不掉血」。</li>
 *   <li><b>箭矢 {@code setPierceLevel(1)}</b>：穿透 1 级让箭穿过玩家时不会被原版立刻销毁，
 *       脚本才有机会在<b>后一 tick</b> 判定命中/格挡。</li>
 *   <li><b>红石块落雷必须「踩在这一格上」才扣血</b>，而且 Y 判定覆盖<b>整个挑战区域</b>
 *       （地面层 → 最上方屏障）：站在地面或飞到屏障顶都要吃这一下，
 *       而屏障之外观战的玩家不受影响。</li>
 * </ol>
 *
 * <h2>定时任务为什么还是「一个机制一个任务」</h2>
 * <p>原脚本为每个机制单独 {@code runTaskTimer}，多玩家并行时任务数线性增长。
 * 代码评审建议合并成一个全局主循环。但那要等<b>战局主循环</b>（阶段二的门面）就位才有意义 ——
 * 现在合并只会把五个机制塞进一个还不存在的调度器里。
 * 本类先把每个机制的<b>逻辑</b>完整实现并各自收得干净（{@link #cleanup()} 幂等），
 * 合并到主循环是阶段二门面那一批的事（见转化报告第 9.4 节批次 4）。</p>
 */
public final class Zqx125Guardian implements BossGuardian {

    // ==================================================================
    // 档位表（照抄原脚本 3398-3423 行）
    // ==================================================================

    /** 箭矢阶段：随血条下降，间隔更短 / 箭更多 / 更快。 */
    private record ArrowStage(int from, int interval, int count, double speed,
                              int spacing, int track, double trackSpeed) {}

    private static final List<ArrowStage> ARROW_STAGES = List.of(
            new ArrowStage(81, 40, 1, 0.25d, 3, 0, 0.0d),
            new ArrowStage(61, 30, 2, 0.40d, 3, 0, 0.0d),
            new ArrowStage(41, 20, 3, 0.60d, 3, 0, 0.0d),
            new ArrowStage(21, 10, 3, 0.75d, 3, 1, 0.50d),
            new ArrowStage(0, 5, 2, 0.75d, 3, 1, 0.75d));

    /** 红石雷击阶段：波更密 / 群更多 / 每群更大 / 撒得更广。 */
    private record StrikeStage(int from, int interval, int strike, int life,
                               int groups, int count, int cluster) {}

    private static final List<StrikeStage> STRIKE_STAGES = List.of(
            new StrikeStage(81, 60, 40, 100, 2, 5, 4),
            new StrikeStage(61, 50, 34, 92, 3, 6, 5),
            new StrikeStage(41, 40, 28, 84, 4, 7, 6),
            new StrikeStage(21, 30, 22, 76, 5, 8, 7),
            new StrikeStage(0, 20, 16, 68, 6, 9, 8));

    /** 天降铁砧阶段。 */
    private record AnvilStage(int from, int interval, int count) {}

    private static final List<AnvilStage> ANVIL_STAGES = List.of(
            new AnvilStage(81, 10, 2),
            new AnvilStage(61, 8, 3),
            new AnvilStage(41, 6, 4),
            new AnvilStage(21, 4, 5),
            new AnvilStage(0, 2, 6));

    /** 同时在空中的铁砧上限（防止服务器被实体拖垮）。 */
    private static final int ANVIL_MAX_ALIVE = 120;

    /** 生命甘露档位：每降到一档补一瓶，20/100 是最后一瓶（共 5 瓶）。 */
    private static final int[] NECTAR_TIERS = {80, 60, 40, 20};

    /** 贴地追击速度（格/刻）。 */
    private static final double GUARDIAN_SPEED = 0.25d;

    /** 贴到这么近就停住，不要把玩家顶穿。 */
    private static final double GUARDIAN_STOP = 2.2d;

    /** 狂暴：360° 无死角直线箭群，持续 2 秒。 */
    private static final int BERSERK_TICKS = 40;
    private static final int BERSERK_INTERVAL = 10;
    private static final double BERSERK_SPEED = 0.6d;
    private static final int BERSERK_DIRS = 16;
    private static final int[] BERSERK_PITCHES = {0, -22, 22};

    /** 箭矢半宽余量（命中判定的判定箱外扩，见 {@link BossFx}）。 */
    private static final int MAX_STRIKE_GROUPS = 64;

    // ==================================================================
    // 本局状态
    // ==================================================================

    private final BossState state;
    private final String key;
    private BossState.GuardianSlot slot;

    /** 本局玩家（读档直开时可能为 {@code null}）。 */
    private Player player;

    private BukkitTask arrowTask;
    private BukkitTask strikeTask;
    private BukkitTask anvilTask;
    private BukkitTask itemTask;
    private BukkitTask dropFxTask;
    private BukkitTask berserkTask;

    private int arrowTick;
    private int strikeTick;
    private int anvilTick;
    private int berserkTick;

    /** 玩家上一 tick 位置（箭矢命中判定的「相对位移」要用）。 */
    private Location playerLast;

    /** 是否已退场（{@link #defeat} 与 {@link #cleanup} 都靠它保证幂等）。 */
    private boolean defeated;

    public Zqx125Guardian(BossState state) {
        this.state = state;
        this.key = (state == null) ? null : state.key;
    }

    @Override
    public String id() {
        return BossState.GID_ZQX125;
    }

    @Override
    public String title() {
        return "天殛";
    }

    @Override
    public double maxHp() {
        return BossState.GUARDIAN_MAX_HP;
    }

    // ==================================================================
    // 召唤
    // ==================================================================

    @Override
    public void spawn(Player player, Location target) {
        this.player = player;
        this.slot = state.guardians.zqx125;
        try {
            if (target == null || target.getWorld() == null) {
                BossLog.warn("zqx125 召唤失败：没有目标位置");
                return;
            }
            World world = target.getWorld();
            slot.maxHp = BossState.GUARDIAN_MAX_HP;
            slot.hp = BossState.GUARDIAN_MAX_HP;
            slot.damageable = false;               // 完全无敌，血量只在 BossBar 上
            slot.defeated = false;

            // 糖然台词 + 登场特效
            if (player != null) {
                BossFx.sendLine(player, "§c[糖然]§b§l出来吧！" + id() + "护法！");
            }
            try {
                world.playSound(target, "entity.skeleton.ambient", 1.0f, 0.6f);
            } catch (Throwable ignored) {
                // 忽略
            }
            BossFx.soulFlame(world, target, 60, 0.8d, 0.02d);

            Skeleton skeleton = world.spawn(target, Skeleton.class);
            // 本体只是演出道具：无敌、不烧、不可推动、AI 全关
            skeleton.setInvulnerable(true);
            skeleton.setShouldBurnInDay(false);
            skeleton.setCustomName("§c§l[天殛]§e§l" + id());
            skeleton.setCustomNameVisible(true);
            skeleton.setPersistent(true);
            skeleton.setRemoveWhenFarAway(false);
            skeleton.setCanPickupItems(false);
            // ★ 原脚本这里写的是 skeleton.setAI(false)，而 setAI 在 1.21 已改名 setAware，
            //   那一行一直被 try/catch 吞掉 ⇒ 护法的原版 AI 从未真正被关过。
            //   本实现用 setAware(false) 落实原脚本注释里写明的意图（「移动/攻击全部由脚本接管」）。
            skeleton.setAware(false);
            skeleton.setSilent(false);
            skeleton.setCollidable(false);
            try {
                var maxHealth = skeleton.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(1024.0d);
                    skeleton.setHealth(1024.0d);
                }
            } catch (Throwable t) {
                BossLog.warn("zqx125 本体血量设置失败: " + t);
            }

            // 装备（掉落率全部归零：本体是演出道具，不该掉装备）
            try {
                var equip = skeleton.getEquipment();
                if (equip != null) {
                    ItemStack head = BossGuardianSkin.head(id(), null, null);
                    equip.setHelmet(head);
                    equip.setChestplate(trimmed(Material.DIAMOND_CHESTPLATE));
                    equip.setLeggings(trimmed(Material.DIAMOND_LEGGINGS));
                    equip.setBoots(trimmed(Material.DIAMOND_BOOTS));
                    equip.setItemInMainHand(new ItemStack(Material.BOW, 1));
                    equip.setHelmetDropChance(0.0f);
                    equip.setChestplateDropChance(0.0f);
                    equip.setLeggingsDropChance(0.0f);
                    equip.setBootsDropChance(0.0f);
                    equip.setItemInMainHandDropChance(0.0f);
                }
            } catch (Throwable t) {
                BossLog.warn("zqx125 装备设置失败: " + t);
            }
            slot.body = skeleton;

            // BossBar：[天殛]zqx125 [n/100]
            try {
                BossState.removeBar(slot.bar);
                org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                        guardianBarTitle(), org.bukkit.boss.BarColor.RED,
                        org.bukkit.boss.BarStyle.SOLID);
                bar.setProgress(1.0d);
                bar.setVisible(true);
                slot.bar = bar;
            } catch (Throwable t) {
                BossLog.warn("zqx125 血条创建失败: " + t);
            }
            Location center = arenaCenterOr(target);
            BossFx.updateBarPlayers(state, center);
            updateBarTitle();

            // 收回糖然双手姿势（召唤时是张开的）
            try {
                Entity stand = state.stand;
                if (stand instanceof org.bukkit.entity.ArmorStand as && as.isValid()) {
                    as.setRightArmPose(org.bukkit.util.EulerAngle.ZERO);
                    as.setLeftArmPose(org.bukkit.util.EulerAngle.ZERO);
                }
            } catch (Throwable ignored) {
                // 糖然本体可能已经不在了
            }

            // 机制：射箭 / 红石雷击 / 天降铁砧 / 道具刷新 / 道具光柱
            //
            // ★ 契约：这五个任务的第一个自检就是 `state.phaseTwo.combatActive`，
            //   而本类<b>不</b>置位它 —— 原脚本里置位的是阶段二入口
            //   （`startPhaseTwoCombat` / `startGuardianQuickStart`，都在建场地之前）。
            //   所以「阶段二门面」那一批（批次 4）必须先置 `combatActive = true` 再召唤护法，
            //   否则这五个任务会在第一拍就全部自停（表现为「护法召出来了但什么都不干」）。
            startArrowTask();
            startStrikeTask();
            startAnvilTask();
            startItemTask();
            startDropFxTask();
        } catch (Throwable t) {
            BossLog.warn("召唤 zqx125 护法失败: " + t);
        }
    }

    /** 钻石甲 + 武装纹理（原脚本 {@code createGuardianTrimmedArmor}）。 */
    private static ItemStack trimmed(Material material) {
        try {
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            // 1.21 起没有 TrimArmorMeta：武装纹理是 ArmorMeta 上的 setTrim(ArmorTrim)
            if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armor) {
                armor.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(
                        org.bukkit.inventory.meta.trim.TrimMaterial.QUARTZ,
                        org.bukkit.inventory.meta.trim.TrimPattern.SILENCE));
            }
            item.setItemMeta(meta);
            return item;
        } catch (Throwable t) {
            return new ItemStack(material, 1);
        }
    }

    private Location arenaCenterOr(Location fallback) {
        try {
            Location c = state.arena.twoCenter;
            if (c != null && c.getWorld() != null) return c;
        } catch (Throwable ignored) {
            // 忽略
        }
        return fallback;
    }

    // ==================================================================
    // 血条
    // ==================================================================

    /** 护法血条标题：{@code [天殛]zqx125 [n/100]}（封号红 + 名字黄，血量紫/上限绿）。 */
    private String guardianBarTitle() {
        return "§c§l[天殛]§e§l" + id() + " §f[§d" + (int) Math.round(slot.hp)
                + "§f/§a" + (int) Math.round(slot.maxHp) + "§f]";
    }

    private void updateBarTitle() {
        try {
            if (slot.bar == null) return;
            slot.bar.setTitle(guardianBarTitle());
            double p = (slot.maxHp > 0) ? slot.hp / slot.maxHp : 0.0d;
            slot.bar.setProgress(Math.max(0.0d, Math.min(1.0d, p)));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 伤害（计数器类）
    // ==================================================================

    @Override
    public boolean canBeDamaged(Entity victim) {
        try {
            if (slot == null || !slot.damageable) return false;
            return slot.body != null && slot.body.equals(victim);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 计数器伤害（原脚本 {@code setTangranBossGuardianHp} / {@code damageTangranBossGuardian}）。
     *
     * <p>扣血顺序：<b>先夹到 [0, maxHp]</b>（原脚本就是这么写的，超量扣不会扣成负数），
     * 再把血量同步到本体（本体有 1024 血，同步过去是「可见地掉血」），
     * 最后更新血条。归零就走 {@link #defeat}。</p>
     */
    @Override
    public boolean damage(Player damager, double amount) {
        try {
            if (slot == null) return false;
            if (defeated) return true;
            double next = slot.hp - Math.max(0.0d, amount);
            slot.hp = Math.max(0.0d, Math.min(slot.maxHp, Math.round(next)));

            // 本体是真实生物：把扣血同步到本体血量，保证盾/技能都能生效
            try {
                if (slot.body != null && slot.body.isValid()) {
                    ((org.bukkit.entity.LivingEntity) slot.body).setHealth(slot.hp);
                }
            } catch (Throwable ignored) {
                // 本体血量同步失败不影响血条口径
            }
            updateBarTitle();
            Location center = arenaCenterOr(null);
            if (center != null) BossFx.updateBarPlayers(state, center);

            if (slot.hp <= 0.0d) {
                defeat(damager != null ? damager : player);
            } else {
                checkNectarStage();
            }
            return true;
        } catch (Throwable t) {
            BossLog.warn("zqx125 扣血失败: " + t);
            return false;
        }
    }

    // ==================================================================
    // 退场
    // ==================================================================

    @Override
    public void defeat(Player player) {
        if (state == null || defeated) return;
        defeated = true;
        slot.defeated = true;
        this.player = (player != null) ? player : this.player;

        // 记录「已击败该护法」：读档菜单据此开放
        try {
            BossProgress.markGuardianDefeated(state.playerName, id());
        } catch (Throwable ignored) {
            // 忽略
        }

        // 停机制 + 清场
        stopTasks();
        clearArrows();
        clearStrikeGroups();
        clearAnvils();
        clearDrops();
        BossState.removeEntity(slot.body);
        slot.body = null;
        BossState.removeBar(slot.bar);
        slot.bar = null;
        slot.hp = 0.0d;

        // 糖然血条：zqx125 退场固定 80/100
        state.tangran.hp = 80.0d;
        try {
            if (state.bar != null) {
                state.bar.setTitle(BossFx.phaseTwoBarTitle(state.tangran.hp));
                state.bar.setProgress(0.8d);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        Location center = arenaCenterOr(null);
        if (center != null) BossFx.updateBarPlayers(state, center);

        try {
            Player p = this.player;
            if (p != null && p.isOnline()) {
                p.getWorld().playSound(p.getLocation(), "entity.skeleton.death", 1.0f, 0.7f);
                BossFx.sendLine(p, "§c[糖然]§b§l不！");
                scheduleLine(40, "§c[糖然]§b§l你竟然打败了我的zqx125护法");
                scheduleLine(80, "§c[糖然]§b§l你会后悔的！");
                scheduleLine(120, "§c[糖然]§b§l来不及为zqx125哀悼，接下来登场的是Katarina护法！");
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 下一位护法。Katarina 的登场阵法（蓝色五角星 + 顶点光柱）由阶段二门面的
        // NextArrival 钩子接入（批次 5 注册实现）；台词播完（0/40/80/120）后 200 刻触发，
        // 与原脚本 defeatPhaseTwoGuardian 的 scheduleAction(state, 200, ...) 对齐。
        state.guardians.pendingId = BossState.GID_KATARINA;
        try {
            Player p = this.player;
            if (p != null && p.isOnline()) {
                org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
                if (plugin != null) {
                    state.cutscene.dialogueTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (BossFight.byKey(state.key) != state) return;   // 已收场：不登场
                        BossPhaseTwo.arriveNext(state, p, BossState.GID_KATARINA);
                    }, 200L));
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        BossLog.warn("zqx125 护法已退场，200 刻后呼叫下一位护法登场序列");
    }

    private void scheduleLine(long delay, String text) {
        try {
            if (player == null) return;
            Player p = player;
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            state.cutscene.dialogueTasks.add(
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> BossFx.sendLine(p, text), delay));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public void cleanup() {
        defeated = true;
        stopTasks();
        clearArrows();
        clearStrikeGroups();
        clearAnvils();
        clearDrops();
        if (slot != null) {
            BossState.removeEntity(slot.body);
            slot.body = null;
            BossState.removeBar(slot.bar);
            slot.bar = null;
        }
    }

    private void stopTasks() {
        BossState.cancelTask(arrowTask);
        arrowTask = null;
        BossState.cancelTask(strikeTask);
        strikeTask = null;
        BossState.cancelTask(anvilTask);
        anvilTask = null;
        BossState.cancelTask(itemTask);
        itemTask = null;
        BossState.cancelTask(dropFxTask);
        dropFxTask = null;
        BossState.cancelTask(berserkTask);
        berserkTask = null;
    }

    // ==================================================================
    // 档位查询
    // ==================================================================

    private static ArrowStage arrowStage(double hp) {
        double v = (hp <= 0) ? BossState.GUARDIAN_MAX_HP : hp;
        for (ArrowStage s : ARROW_STAGES) {
            if (v >= s.from()) return s;
        }
        return ARROW_STAGES.get(ARROW_STAGES.size() - 1);
    }

    private static StrikeStage strikeStage(double hp) {
        double v = (hp <= 0) ? BossState.GUARDIAN_MAX_HP : hp;
        for (StrikeStage s : STRIKE_STAGES) {
            if (v >= s.from()) return s;
        }
        return STRIKE_STAGES.get(STRIKE_STAGES.size() - 1);
    }

    private static AnvilStage anvilStage(double hp) {
        double v = (hp <= 0) ? BossState.GUARDIAN_MAX_HP : hp;
        for (AnvilStage s : ANVIL_STAGES) {
            if (v >= s.from()) return s;
        }
        return ANVIL_STAGES.get(ANVIL_STAGES.size() - 1);
    }

    // ==================================================================
    // 机制一：射箭
    // ==================================================================

    private void startArrowTask() {
        try {
            clearArrows();
            arrowTick = 0;
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            arrowTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        BossState.cancelTask(arrowTask);
                        arrowTask = null;
                        return;
                    }
                    Entity body = slot.body;
                    boolean alive = body != null && body.isValid();
                    updateArrows();
                    // 本体没了也自停：否则退场后这个任务会一直空转到战局结束
                    if (!alive) {
                        BossState.cancelTask(arrowTask);
                        arrowTask = null;
                        return;
                    }
                    Player p = this.player;
                    if (p == null || !p.isOnline() || p.isDead()) return;
                    moveTowards((Skeleton) body, p);

                    ArrowStage stage = arrowStage(slot.hp);
                    arrowTick++;
                    if (arrowTick < stage.interval()) return;
                    arrowTick = 0;
                    fireVolley((Skeleton) body, p, stage);
                } catch (Throwable t) {
                    BossLog.warn("zqx125 射箭异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("zqx125 射箭机制启动失败: " + t);
        }
    }

    /** 这一局还是当前战局吗（原脚本每个定时器开头的守卫）。 */
    private boolean isMine() {
        try {
            return state != null && BossFight.byKey(key) == state;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 贴地追击玩家：AI 关闭 + 直接位移，不用速度（免得被物理/摩擦吞掉）。 */
    private void moveTowards(Skeleton skeleton, Player p) {
        try {
            Location sl = skeleton.getLocation();
            Location pl = p.getLocation();
            double dx = pl.getX() - sl.getX();
            double dz = pl.getZ() - sl.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            float yaw = (len < 0.001d)
                    ? sl.getYaw() : (float) (Math.atan2(-dx, dz) * 180.0d / Math.PI);
            skeleton.setRotation(yaw, 0.0f);
            if (len <= GUARDIAN_STOP) return;
            double step = Math.min(GUARDIAN_SPEED, len - GUARDIAN_STOP);
            if (step <= 0) return;
            double nx = sl.getX() + dx / len * step;
            double nz = sl.getZ() + dz / len * step;
            double ny = sl.getY();
            BossArena.Region region = state.arena.twoRegion;
            if (region != null) {
                ny = Math.max(ny, region.minY() + 1.0d);
                nx = Math.max(region.minX() + 1.5d, Math.min(region.maxX() - 0.5d, nx));
                nz = Math.max(region.minZ() + 1.5d, Math.min(region.maxZ() - 0.5d, nz));
            }
            Location dest = new Location(sl.getWorld(), nx, ny, nz);
            dest.setYaw(yaw);
            dest.setPitch(0.0f);
            skeleton.teleport(dest);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 射一支箭。{@code offset} 是横向偏移（齐射用），{@code trackSpeed > 0} 表示追踪箭。 */
    private Entity fireArrow(ArrowStage stage, double offset, double trackSpeed, Vector overrideDir) {
        try {
            Skeleton skeleton = (Skeleton) slot.body;
            if (skeleton == null || !skeleton.isValid()) return null;
            World world = skeleton.getWorld();
            Location eye = skeleton.getEyeLocation();
            Location origin = new Location(world, eye.getX(), eye.getY() - 0.25d, eye.getZ());

            Vector dir;
            if (overrideDir != null) {
                dir = overrideDir.clone();
            } else {
                Player p = this.player;
                if (p == null) return null;
                Location target = p.getLocation().clone().add(0, 1.0d, 0);
                dir = target.toVector().subtract(origin.toVector());
            }
            if (dir.lengthSquared() < 0.0001d) return null;
            dir.normalize();

            if (Math.abs(offset) > 0.001d) {
                Vector right = new Vector(-dir.getZ(), 0.0d, dir.getX());
                if (right.lengthSquared() > 0.0001d) {
                    origin.add(right.normalize().multiply(offset));
                }
            }

            // 原版物理飞行（玩家可预判轨迹）
            org.bukkit.entity.Arrow arrow =
                    world.spawnArrow(origin, dir, (float) stage.speed(), 0.0f);
            if (arrow == null) return null;
            arrow.setDamage(1.0d);
            arrow.setGravity(false);                 // 无重力 → 直线
            arrow.setCritical(false);
            try {
                arrow.setKnockbackStrength(0);       // 1.21 已标记待删除，但仍是唯一入口
            } catch (Throwable ignored) {
                // 忽略
            }
            // 穿透 1：箭穿过玩家时不会被原版立刻销毁，脚本才有机会在后一 tick 判定命中/格挡
            arrow.setPierceLevel(1);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            arrow.setShooter(skeleton, false);
            try {
                arrow.getPersistentDataContainer().set(
                        BossPhaseTwoItems.ARROW_KEY, PersistentDataType.STRING,
                        String.valueOf(state.playerName));
            } catch (Throwable ignored) {
                // 忽略
            }

            BossState.ArrowEntry entry = new BossState.ArrowEntry(arrow, arrowTick);
            entry.resolved = false;
            // 追踪箭：前 2 秒（40 拍）改速度重新瞄准玩家当前位置
            entry.homing = (trackSpeed > 0.0d) ? 40 : 0;
            entry.homingSpeed = trackSpeed;
            Location ol = arrow.getLocation();
            entry.lastX = ol.getX();
            entry.lastY = ol.getY();
            entry.lastZ = ol.getZ();
            entry.hasLast = true;
            state.guardians.arrows.add(entry);
            return arrow;
        } catch (Throwable t) {
            BossLog.warn("zqx125 射箭失败: " + t);
            return null;
        }
    }

    /** 一轮齐射：{@code count} 支平行箭 + {@code track} 支追踪箭。 */
    private void fireVolley(Skeleton skeleton, Player p, ArrowStage stage) {
        try {
            int n = Math.max(1, stage.count());
            int spacing = stage.spacing();
            for (int i = 0; i < n; i++) {
                double offset = (i - (n - 1) / 2.0d) * spacing;
                fireArrow(stage, offset, 0.0d, null);
            }
            for (int t = 0; t < stage.track(); t++) {
                fireArrow(stage, 0.0d, stage.trackSpeed(), null);
            }
            try {
                skeleton.getWorld().playSound(skeleton.getLocation(),
                        "entity.arrow.shoot", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable t) {
            BossLog.warn("zqx125 齐射失败: " + t);
        }
    }

    private void clearArrows() {
        try {
            for (BossState.ArrowEntry a : state.guardians.arrows) {
                if (a != null) BossState.removeEntity(a.entity);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        state.guardians.arrows.clear();
    }

    /**
     * 每刻更新全部箭矢：追踪、命中判定、出界剔除。
     *
     * <p>命中判定把「箭矢上一拍位置 → 当前位置」与「玩家上一拍位置 → 当前位置」
     * 都换算成<b>相对玩家</b>的位移，再对玩家碰撞箱做一次扫掠
     * （见 {@link BossFx#segmentsHitPlayer}）。命中即 {@code resolved = true} 并当场结算，
     * 下一拍统一 remove —— 这样「伤害事件」与「轮询」两条路都不会二次扣血。</p>
     *
     * <p>★ {@code lastX/Y/Z} 是必需的：没有它，本拍的起点与终点都是「当前位置」，
     * 位移长度为 0 ⇒ 扫掠永远不命中（表现就是「箭明明穿过玩家却不掉血」）。</p>
     */
    private void updateArrows() {
        try {
            Player p = this.player;
            boolean canHit = p != null && p.isOnline() && !p.isDead();
            Location nowLoc = canHit ? p.getLocation() : null;

            List<BossState.ArrowEntry> keep = new ArrayList<>();
            for (BossState.ArrowEntry a : state.guardians.arrows) {
                Entity e = (a == null) ? null : a.entity;
                if (e == null || !e.isValid()) continue;
                if (a.resolved) {
                    // 已由命中判定结算：下一拍才移除，避免同一支箭被伤害事件再触发一次
                    BossState.removeEntity(e);
                    continue;
                }

                Location loc = e.getLocation();

                // 追踪箭：前 2 秒改速度重新瞄准玩家当前位置（其余箭矢走原版物理）
                if (a.homing > 0) {
                    a.homing--;
                    try {
                        if (nowLoc != null) {
                            Location tgt = nowLoc.clone().add(0, 1.0d, 0);
                            double vx = tgt.getX() - loc.getX();
                            double vy = tgt.getY() - loc.getY();
                            double vz = tgt.getZ() - loc.getZ();
                            double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                            if (vl > 0.0001d) {
                                double sp = (a.homingSpeed > 0.0d) ? a.homingSpeed
                                        : arrowStage(slot.hp).speed();
                                e.setVelocity(new Vector(vx / vl * sp, vy / vl * sp, vz / vl * sp));
                            }
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }

                // 命中判定：拿「上一拍 → 当前」的真实位移扫玩家
                Location from = new Location(loc.getWorld(), a.lastX, a.lastY, a.lastZ);
                if (canHit && a.hasLast
                        && BossFx.segmentsHitPlayer(from, loc.getX(), loc.getY(), loc.getZ(),
                                nowLoc, playerLast)) {
                    a.resolved = true;
                    resolveArrowHit(e);
                    continue;
                }
                a.lastX = loc.getX();
                a.lastY = loc.getY();
                a.lastZ = loc.getZ();
                a.hasLast = true;

                // 插在方块上（含场地边缘）或飞出场地 → 消失
                // isInBlock() 在 AbstractArrow 上，Entity 上没有 —— 直接转型
                boolean inBlock;
                try {
                    inBlock = (e instanceof org.bukkit.entity.AbstractArrow ar) && ar.isInBlock();
                } catch (Throwable ignored) {
                    inBlock = false;
                }
                if (inBlock || !BossFx.inTwoArena(state, loc)) {
                    BossState.removeEntity(e);
                    continue;
                }
                keep.add(a);
            }

            if (nowLoc != null) playerLast = nowLoc.clone();
            state.guardians.arrows.clear();
            state.guardians.arrows.addAll(keep);
        } catch (Throwable t) {
            BossLog.warn("二阶段箭矢更新异常: " + t);
        }
    }

    /**
     * 一箭的结算：举盾 → 摧毁箭矢 + zqx125 扣 10；否则 → 玩家固定扣 1 滴血。
     */
    /**
     * 事件入口：zqx125 的箭矢命中玩家（批次 8 并表，原脚本 13071-13094 行）。
     *
     * <p>与每拍扫掠（{@code arrowSweep}）走同一个 {@link #resolveArrowHit} 结算：
     * 取消原版伤害、标记箭矢已结算、举盾则毁箭扣他 10 点，否则玩家固定扣 1 点。</p>
     *
     * @return true = 本规则已接管（事件层不要再按常规结算）
     */
    public static boolean onArrowDamage(
            org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof org.bukkit.entity.Arrow arrow)) return false;
            BossState st = BossFight.byArrow(arrow);
            if (st == null) return false;
            if (!(event.getEntity() instanceof Player victim)) return false;
            if (st.playerName == null || !st.playerName.equals(victim.getName())) return false;
            if (st.guardians.zqx125.defeated) return false;
            event.setCancelled(true);
            for (BossState.ArrowEntry entry : st.guardians.arrows) {
                if (entry.entity != null && entry.entity.getUniqueId().equals(arrow.getUniqueId())) {
                    entry.resolved = true;
                    break;
                }
            }
            if (st.guardians.active instanceof Zqx125Guardian zq) {
                zq.resolveArrowHit(arrow);
            } else {
                // 护法实例不在（读档续战等边缘）：退回「固定 1 点」的保底口径
                BossState.removeEntity(arrow);
                BossFx.directDamage(victim, 1.0d);
                BossFx.hurtFeedback(victim);
            }
            return true;
        } catch (Throwable t) {
            BossLog.warn("zqx125 箭矢命中事件结算失败: " + t);
            return false;
        }
    }

    private void resolveArrowHit(Entity arrowEntity) {
        try {
            if (state == null) return;
            Player p = this.player;
            if (p == null) return;
            if (BossPhaseTwoItems.isBlocking(state, p)
                    && BossPhaseTwoItems.has(p, BossPhaseTwoItems.SHIELD)) {
                BossState.removeEntity(arrowEntity);
                BossPhaseTwoItems.consumeOne(p, BossPhaseTwoItems.SHIELD);
                state.phaseTwo.shieldActive = false;
                state.phaseTwo.shieldTimer = 0;
                state.phaseTwo.shieldRaiseGrace = 0;
                try {
                    p.playSound(p.getLocation(), "item.shield.block", 1.0f, 1.0f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                p.sendMessage("§6反击之盾挡下了这一箭，zqx125 受到 §c10§6 点伤害！");
                damage(p, 10.0d);
                return;
            }
            BossState.removeEntity(arrowEntity);
            BossFx.directDamage(p, 1.0d);
            BossFx.hurtFeedback(p);
        } catch (Throwable t) {
            BossLog.warn("护法箭矢结算失败: " + t);
        }
    }

    // ==================================================================
    // 机制二：红石雷击
    // ==================================================================

    private void startStrikeTask() {
        try {
            clearStrikeGroups();
            strikeTick = 0;
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            strikeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        BossState.cancelTask(strikeTask);
                        strikeTask = null;
                        return;
                    }
                    StrikeStage stage = strikeStage(slot.hp);
                    strikeTick++;
                    if (strikeTick % stage.interval() == 0) createStrikeGroup(stage);

                    List<BossState.StrikeGroup> keep = new ArrayList<>();
                    for (BossState.StrikeGroup g : state.phaseTwo.strikeGroups) {
                        g.age++;
                        if (!g.struck && g.age >= g.strikeDelay) {
                            g.struck = true;
                            strikeGroup(g);
                            restoreStrikeGroup(g);       // 落雷结束立刻复原成石英块
                            continue;
                        }
                        if (g.age >= g.life) restoreStrikeGroup(g);
                        else keep.add(g);
                    }
                    state.phaseTwo.strikeGroups.clear();
                    state.phaseTwo.strikeGroups.addAll(keep);
                } catch (Throwable t) {
                    BossLog.warn("二阶段红石雷击异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("红石雷击启动失败: " + t);
        }
    }

    /**
     * 生成一波雷击群。
     *
     * <p>约 45% 的群<b>贴着四边</b>生成（保证边界/角落也被照顾），其余整片地板均匀随机。
     * 群内一半贴邻（连成片）、一半外扩（间隔散点）。只把<b>石英块</b>换成红石块 ——
     * 场地边缘或已被占用的格不动。</p>
     */
    private void createStrikeGroup(StrikeStage stage) {
        try {
            BossArena.Region region = state.arena.twoRegion;
            World world = state.arena.twoWorld;
            if (region == null || world == null) return;
            if (state.phaseTwo.strikeGroups.size() >= MAX_STRIKE_GROUPS) return;

            int y = region.minY();
            int minX = region.minX() + 1;
            int maxX = region.maxX() - 1;
            int minZ = region.minZ() + 1;
            int maxZ = region.maxZ() - 1;
            int groups = Math.max(1, stage.groups());

            for (int gi = 0; gi < groups; gi++) {
                int ccx;
                int ccz;
                if (Math.random() < 0.45d) {
                    int edge = (int) Math.floor(Math.random() * 4);
                    double t = Math.random();
                    int inset = (Math.random() < 0.5d) ? 1 : 3;
                    if (edge == 0) {
                        ccx = minX + inset;
                        ccz = BossFx.toBlockInt(minZ + t * (maxZ - minZ));
                    } else if (edge == 1) {
                        ccx = maxX - inset;
                        ccz = BossFx.toBlockInt(minZ + t * (maxZ - minZ));
                    } else if (edge == 2) {
                        ccz = minZ + inset;
                        ccx = BossFx.toBlockInt(minX + t * (maxX - minX));
                    } else {
                        ccz = maxZ - inset;
                        ccx = BossFx.toBlockInt(minX + t * (maxX - minX));
                    }
                } else {
                    ccx = BossFx.toBlockInt(minX + Math.random() * (maxX - minX));
                    ccz = BossFx.toBlockInt(minZ + Math.random() * (maxZ - minZ));
                }

                BossState.StrikeGroup group = new BossState.StrikeGroup();
                group.strikeDelay = stage.strike();
                group.life = stage.life();

                java.util.Set<String> seen = new java.util.HashSet<>();
                int tries = 0;
                int maxTries = stage.count() * 24;
                int spread = Math.max(1, stage.cluster());
                while (group.blocks.size() < stage.count() && tries < maxTries) {
                    tries++;
                    int step = (Math.random() < 0.5d) ? 1 : spread;
                    int ox = BossFx.toBlockInt((Math.random() * 2 - 1) * step);
                    int oz = BossFx.toBlockInt((Math.random() * 2 - 1) * step);
                    int x = BossFx.toBlockInt(
                            Math.max(minX, Math.min(maxX, ccx + ox)));
                    int z = BossFx.toBlockInt(
                            Math.max(minZ, Math.min(maxZ, ccz + oz)));
                    String tag = x + ":" + z;
                    if (!seen.add(tag)) continue;
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.QUARTZ_BLOCK) continue;
                    block.setType(Material.REDSTONE_BLOCK, false);
                    group.blocks.add(new int[]{x, y, z});
                }
                if (!group.blocks.isEmpty()) {
                    state.phaseTwo.strikeGroups.add(group);
                }
            }
        } catch (Throwable t) {
            BossLog.warn("二阶段红石块生成异常: " + t);
        }
    }

    /**
     * 落雷：无焰雷电 + 「必须真的踩在这一格红石块上」才扣 1 滴血。
     *
     * <p>Y 判定覆盖<b>整个挑战区域</b>（地面层 → 最上方屏障）：站在地面或飞到屏障顶
     * 都要吃这一下，而屏障之外（外面观战的玩家）不受影响。</p>
     */
    private void strikeGroup(BossState.StrikeGroup group) {
        try {
            World world = state.arena.twoWorld;
            if (world == null) return;
            BossArena.Region region = state.arena.twoRegion;
            Player p = this.player;
            boolean hitPlayer = false;

            for (int[] b : group.blocks) {
                Location loc = new Location(world, b[0] + 0.5d, b[1] + 1.0d, b[2] + 0.5d);
                try {
                    world.strikeLightningEffect(loc);
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (hitPlayer) continue;
                try {
                    if (p != null && p.isOnline() && !p.isDead()) {
                        Location pl = p.getLocation();
                        int px = BossFx.toBlockInt(Math.floor(pl.getX()));
                        int pz = BossFx.toBlockInt(Math.floor(pl.getZ()));
                        double dy = pl.getY() - (b[1] + 1);
                        double topY = (region != null) ? (region.maxY() + 0.4d) : (b[1] + 40.0d);
                        boolean onBlock = (px == b[0]) && (pz == b[2]);
                        if (onBlock && dy >= -0.6d && pl.getY() <= topY) {
                            hitPlayer = true;
                            BossFx.directDamage(p, 1.0d);
                            BossFx.hurtFeedback(p);
                            try {
                                world.playSound(pl, "entity.lightning_bolt.impact", 1.0f, 1.2f);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (region != null) {
                try {
                    world.playSound(new Location(world,
                            region.minX() + 1, region.minY() + 1, region.minZ() + 1),
                            "entity.lightning_bolt.thunder", 0.35f, 1.4f);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void restoreStrikeGroup(BossState.StrikeGroup group) {
        try {
            World world = state.arena.twoWorld;
            if (world == null) return;
            for (int[] b : group.blocks) {
                org.bukkit.block.Block block = world.getBlockAt(b[0], b[1], b[2]);
                if (block.getType() == Material.REDSTONE_BLOCK) {
                    block.setType(Material.QUARTZ_BLOCK, false);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void clearStrikeGroups() {
        try {
            for (BossState.StrikeGroup g : state.phaseTwo.strikeGroups) {
                restoreStrikeGroup(g);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        state.phaseTwo.strikeGroups.clear();
    }

    // ==================================================================
    // 机制三：天降铁砧
    // ==================================================================

    private void startAnvilTask() {
        try {
            clearAnvils();
            anvilTick = 0;
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            anvilTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        BossState.cancelTask(anvilTask);
                        anvilTask = null;
                        return;
                    }
                    updateAnvils();
                    AnvilStage stage = anvilStage(slot.hp);
                    anvilTick++;
                    if (anvilTick < stage.interval()) return;
                    anvilTick = 0;
                    int n = Math.max(1, stage.count());
                    for (int i = 0; i < n; i++) {
                        if (state.phaseTwo.anvils.size() >= ANVIL_MAX_ALIVE) break;
                        spawnAnvil();
                    }
                } catch (Throwable t) {
                    BossLog.warn("天降铁砧异常: " + t);
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("天降铁砧启动失败: " + t);
        }
    }

    /**
     * 生成一块铁砧。
     *
     * <p>10% 砸向玩家（按当前速度<b>预判落点</b> + 0~3.5 格随机偏移），
     * 其余 90% 全场地随机 —— 以散为主。原版伤害关掉，只走脚本固定 1 点。</p>
     */
    private void spawnAnvil() {
        try {
            BossArena.Region region = state.arena.twoRegion;
            World world = state.arena.twoWorld;
            if (region == null || world == null) return;

            double x;
            double z;
            Player p = this.player;
            boolean aimed = p != null && p.isOnline() && Math.random() < 0.10d;
            if (aimed) {
                Location pl = p.getLocation();
                double vx = 0.0d;
                double vz = 0.0d;
                try {
                    Vector v = p.getVelocity();
                    vx = v.getX();
                    vz = v.getZ();
                } catch (Throwable ignored) {
                    // 忽略
                }
                double leadX = Math.max(-6.0d, Math.min(6.0d, vx * 30.0d));
                double leadZ = Math.max(-6.0d, Math.min(6.0d, vz * 30.0d));
                double offAngle = Math.random() * Math.PI * 2;
                double offR = Math.random() * 3.5d;
                x = pl.getX() + leadX + Math.cos(offAngle) * offR;
                z = pl.getZ() + leadZ + Math.sin(offAngle) * offR;
            } else {
                x = region.minX() + 1.5d + Math.random() * (region.maxX() - region.minX() - 2.0d);
                z = region.minZ() + 1.5d + Math.random() * (region.maxZ() - region.minZ() - 2.0d);
            }
            x = Math.max(region.minX() + 1.2d, Math.min(region.maxX() - 1.2d, x));
            z = Math.max(region.minZ() + 1.2d, Math.min(region.maxZ() - 1.2d, z));
            // 从场地顶部（屏障层下方）落下
            double y = region.maxY() - 1.2d;
            Location loc = new Location(world, x, y, z);

            FallingBlock falling = world.spawnFallingBlock(loc, Material.ANVIL.createBlockData());
            if (falling == null) return;
            falling.setDropItem(false);
            falling.setCancelDrop(true);      // 不落成方块
            falling.setHurtEntities(false);   // 原版伤害关掉，只走脚本 1 点
            falling.setVelocity(new Vector(0, -0.6d, 0));

            BossState.Anvil anvil = new BossState.Anvil();
            anvil.entity = falling;
            anvil.hitPlayer = false;
            state.phaseTwo.anvils.add(anvil);
        } catch (Throwable t) {
            BossLog.warn("天降铁砧生成失败: " + t);
        }
    }

    private void updateAnvils() {
        try {
            BossArena.Region region = state.arena.twoRegion;
            Player p = this.player;
            boolean canHit = p != null && p.isOnline() && !p.isDead();
            Location pl = canHit ? p.getLocation() : null;

            List<BossState.Anvil> keep = new ArrayList<>();
            for (BossState.Anvil a : state.phaseTwo.anvils) {
                FallingBlock e = (a == null) ? null : a.entity;
                if (e == null || !e.isValid()) continue;
                Location loc = e.getLocation();
                // 砸到石英块层 → 直接消失（不留方块、不掉落）
                if (region != null && loc.getY() <= region.minY() + 1.35d) {
                    BossState.removeEntity(e);
                    continue;
                }
                // 命中玩家 → 固定 1 滴血（同一块铁砧只结算一次）
                if (canHit && !a.hitPlayer && pl != null) {
                    double dx = loc.getX() - pl.getX();
                    double dy = loc.getY() - (pl.getY() + 1.0d);
                    double dz = loc.getZ() - pl.getZ();
                    if ((dx * dx + dz * dz) <= 1.7d && Math.abs(dy) <= 1.6d) {
                        a.hitPlayer = true;
                        BossFx.directDamage(p, 1.0d);
                        BossFx.hurtFeedback(p);
                    }
                }
                keep.add(a);
            }
            state.phaseTwo.anvils.clear();
            state.phaseTwo.anvils.addAll(keep);
        } catch (Throwable t) {
            BossLog.warn("天降铁砧更新异常: " + t);
        }
    }

    private void clearAnvils() {
        try {
            for (BossState.Anvil a : state.phaseTwo.anvils) {
                if (a != null) BossState.removeEntity(a.entity);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        state.phaseTwo.anvils.clear();
    }

    // ==================================================================
    // 机制四：道具刷新（反击之盾 + 生命甘露）
    // ==================================================================

    private void startItemTask() {
        try {
            clearDrops();
            state.phaseTwo.shieldActive = false;
            state.phaseTwo.shieldTimer = 0;
            state.phaseTwo.nectarTierIndex = -1;
            // 战斗开始：第一瓶生命甘露
            spawnLifeNectar();

            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            itemTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        BossState.cancelTask(itemTask);
                        itemTask = null;
                        return;
                    }
                    Player p = this.player;
                    if (p == null || !p.isOnline()) return;

                    // 举盾宽限窗衰减（按下右键到盾真正举起来之间有几帧延迟）
                    if (state.phaseTwo.shieldRaiseGrace > 0) {
                        state.phaseTwo.shieldRaiseGrace =
                                Math.max(0, state.phaseTwo.shieldRaiseGrace - 20);
                    }

                    // 反击之盾：每 5 秒一轮，场上/身上还有盾就停止刷新
                    if (!defeated) {
                        if (state.phaseTwo.shieldActive) {
                            boolean alive = state.phaseTwo.shieldEntity != null
                                    && state.phaseTwo.shieldEntity.isValid();
                            if (!alive && !BossPhaseTwoItems.has(p, BossPhaseTwoItems.SHIELD)) {
                                state.phaseTwo.shieldActive = false;
                                state.phaseTwo.shieldTimer = 0;
                            }
                        } else {
                            state.phaseTwo.shieldTimer++;
                            if (state.phaseTwo.shieldTimer >= 5) {
                                state.phaseTwo.shieldTimer = 0;
                                spawnCounterShield(p);
                            }
                        }
                    }
                } catch (Throwable t) {
                    BossLog.warn("二阶段道具刷新异常: " + t);
                }
            }, 20L, 20L);
        } catch (Throwable t) {
            BossLog.warn("道具刷新启动失败: " + t);
        }
    }

    /** 生命甘露只刷在石英块上面那一层（不往空中刷）。 */
    private Item spawnLifeNectar() {
        Location loc = BossFx.randomArenaPoint(state, false);
        if (loc == null) return null;
        return spawnDrop(BossPhaseTwoItems.createLifeNectar(), loc, true);
    }

    private Item spawnCounterShield(Player p) {
        Location loc = BossFx.randomArenaPoint(state, true);
        if (loc == null) return null;
        Item entity = spawnDrop(BossPhaseTwoItems.createCounterShield(), loc, false);
        if (entity == null) return null;
        state.phaseTwo.shieldEntity = entity;
        state.phaseTwo.shieldActive = true;
        try {
            p.playSound(p.getLocation(), "block.beacon.activate", 0.7f, 1.7f);
        } catch (Throwable ignored) {
            // 忽略
        }
        p.sendMessage("§e一个反击之盾已在场地中刷新，右键举起它格挡 zqx125 的箭矢！");
        return entity;
    }

    /**
     * 丢一个本局道具。
     *
     * <p>与 Katarina 共用 {@link BossPhaseTwoItems#spawnDrop}（原脚本两边各写了一份
     * 一模一样的 {@code spawnPhaseTwoDrop}，这里合并）。</p>
     */
    private Item spawnDrop(ItemStack stack, Location loc, boolean forceGroundLayer) {
        return BossPhaseTwoItems.spawnDrop(state, stack, loc, forceGroundLayer);
    }

    /**
     * 进入下一档位：补一瓶生命甘露 + 触发狂暴 + 糖然台词。
     *
     * <p>档位表 {@link #NECTAR_TIERS} 是<b>降序</b>的（80/60/40/20），
     * 所以用 {@code while} 一次跨过多个档位（例如一刀从 90 砍到 30，
     * 要连补三瓶并连播三次台词，不能只补一瓶）。</p>
     */
    private void checkNectarStage() {
        try {
            if (state == null || defeated) return;
            if (!state.phaseTwo.combatActive) return;
            if (!BossState.GID_ZQX125.equals(state.guardians.activeId)) return;
            double hp = slot.hp;
            if (hp <= 0.0d) return;                 // 清空血条那一刀不再补瓶，直接走退场

            int idx = state.phaseTwo.nectarTierIndex;
            while ((idx + 1) < NECTAR_TIERS.length && hp <= NECTAR_TIERS[idx + 1]) {
                idx++;
                spawnLifeNectar();
                int stageNo = idx + 2;              // 100 档 = 第 1 阶段，80 档 = 第 2 阶段 …
                Player p = this.player;
                if (p != null && p.isOnline()) {
                    BossFx.sendLine(p, "§c[糖然]§b§l" + id() + " 护法进入第 " + stageNo
                            + " 阶段，挑战难度上升！");
                }
                // 进入新阶段的前 2 秒：狂暴，360° 无死角直线箭群
                startBerserk();
                // 血条剩 20（最后一档）：护法亲自说话
                if (idx == NECTAR_TIERS.length - 1) {
                    if (p != null && p.isOnline()) {
                        p.sendMessage("§c§l[天殛]§e§l" + id() + "§f§l: §c过家家游戏到此结束！");
                        try {
                            p.playSound(p.getLocation(), "entity.ender_dragon.growl", 1.0f, 0.6f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
            }
            state.phaseTwo.nectarTierIndex = idx;
        } catch (Throwable t) {
            BossLog.warn("甘露档位检查失败: " + t);
        }
    }

    // ==================================================================
    // 机制五：狂暴
    // ==================================================================

    /** 360° 无死角直线箭群，持续 2 秒。 */
    private void startBerserk() {
        try {
            if (state == null || defeated) return;
            BossState.cancelTask(berserkTask);
            berserkTask = null;
            berserkTick = 0;
            Skeleton skeleton = (Skeleton) slot.body;
            if (skeleton != null && skeleton.isValid()) {
                try {
                    skeleton.getWorld().playSound(skeleton.getLocation(),
                            "entity.ender_dragon.growl", 1.0f, 0.7f);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            berserkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || BossFight.byKey(key) != state) {
                        BossState.cancelTask(berserkTask);
                        berserkTask = null;
                        return;
                    }
                    Skeleton body = (Skeleton) slot.body;
                    boolean ok = body != null && body.isValid() && !defeated;
                    if (!ok) {
                        BossState.cancelTask(berserkTask);
                        berserkTask = null;
                        return;
                    }
                    if (berserkTick % BERSERK_INTERVAL == 0) {
                        fireBerserkVolley(body);
                    }
                    berserkTick++;
                    if (berserkTick > BERSERK_TICKS) {
                        BossState.cancelTask(berserkTask);
                        berserkTask = null;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 1L);
        } catch (Throwable t) {
            BossLog.warn("狂暴箭群启动失败: " + t);
        }
    }

    private void fireBerserkVolley(Skeleton skeleton) {
        try {
            ArrowStage stage = new ArrowStage(0, 0, 0, BERSERK_SPEED, 0, 0, 0.0d);
            double baseYaw = Math.random() * 360.0d;
            for (int pi = 0; pi < BERSERK_PITCHES.length; pi++) {
                double pitchRad = BERSERK_PITCHES[pi] * Math.PI / 180.0d;
                double vy = Math.sin(pitchRad);
                double horiz = Math.cos(pitchRad);
                for (int i = 0; i < BERSERK_DIRS; i++) {
                    double yawRad = (baseYaw + i * (360.0d / BERSERK_DIRS)) * Math.PI / 180.0d;
                    Vector dir = new Vector(
                            -Math.sin(yawRad) * horiz, vy, Math.cos(yawRad) * horiz);
                    fireArrow(stage, 0.0d, 0.0d, dir);
                }
            }
            try {
                skeleton.getWorld().playSound(skeleton.getLocation(),
                        "entity.arrow.shoot", 1.0f, 0.8f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable t) {
            BossLog.warn("狂暴箭群发射失败: " + t);
        }
    }

    // ==================================================================
    // 机制六：道具示踪 + 磁吸
    // ==================================================================

    private void startDropFxTask() {
        try {
            org.bukkit.plugin.Plugin plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return;
            dropFxTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (!isMine() || defeated || !state.phaseTwo.combatActive) {
                        BossState.cancelTask(dropFxTask);
                        dropFxTask = null;
                        return;
                    }
                    markDrops();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 4L, 4L);
        } catch (Throwable t) {
            BossLog.warn("二阶段道具示踪启动失败: " + t);
        }
    }

    /**
     * 道具标记：先 prune（失效的连同特效一起消失），再把存活的道具吸向本局玩家。
     *
     * <p>原脚本这里还有一整套「信标光柱 + 顶部旋转呼吸环 + 每 5 秒一次向外示踪 ping」的
     * 粒子表现（玩家反馈「道具太难找」后加的）。本类先落实<b>功能性</b>的部分
     * （发光 + 磁吸 + prune），纯表现的光柱/呼吸环/ping 留到表现层批次一起做 ——
     * 现在每件道具已经 {@code setGlowing(true)}，隔墙可见。</p>
     */
    private void markDrops() {
        try {
            BossPhaseTwoItems.pruneDrops(state, state.phaseTwo.drops);
            if (state.phaseTwo.drops.isEmpty()) return;
            Player p = this.player;
            if (p == null || !p.isOnline()) return;
            BossPhaseTwoItems.magnetize(state, p, state.phaseTwo.drops);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void clearDrops() {
        try {
            for (Entity e : state.phaseTwo.drops) {
                BossState.removeEntity(e);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        state.phaseTwo.drops.clear();
        state.phaseTwo.shieldEntity = null;
        state.phaseTwo.shieldActive = false;
    }
}
