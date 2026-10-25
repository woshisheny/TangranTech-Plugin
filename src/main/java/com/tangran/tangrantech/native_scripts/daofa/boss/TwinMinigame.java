package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentBridge;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;

/**
 * 双子经营小游戏（原脚本 7668-11020 行的核心玩法环）：
 * <b>天虚召唤虚仆 → 打死虚仆得虚币 → 挖/伐/收场上资源入账本 → 换出破法弓解除双子护体</b>。
 *
 * <h2>玩法闭环（原脚本类注释照抄）</h2>
 * <ol>
 *   <li>天虚周期性召唤【虚仆】（僵尸/骷髅/蜘蛛/苦力怕/女巫/凋灵骷髅等，四档权重 45/35/15/5）；</li>
 *   <li>打死虚仆 → 按档位区间记「虚币」进账本（不进背包）；</li>
 *   <li>场上资源（无限矿石单方块 / 橡树 / 土链）挖掉即入账本、随即自动补回；</li>
 *   <li>挑战道具每 10 秒刷一种、存在 30 秒、拾取即生效不进背包（盾/清负面/饱和/回满/杀戮光环）；</li>
 *   <li>拿到破法弓 → 解除双子护体（否则双子无敌，箭和近战都被原版无敌吃掉）。</li>
 * </ol>
 *
 * <h2>批次 6c 待做（本类先如实降级）</h2>
 * <ul>
 *   <li><b>天实商店 GUI</b>（四分类兑换）—— 右键天实只给提示；</li>
 *   <li><b>增益购买与属性应用</b>（攻击力/护甲值/吸血/四重祝福）—— 账本已有存储位；</li>
 *   <li><b>水瓶链 / 中章装备补发</b>。</li>
 * </ul>
 * 为让双子阶段<b>可通关</b>，破法弓随开局直接发放（原脚本要从商店换购 —— 偏差已记录）。
 */
public final class TwinMinigame {

    private TwinMinigame() {}

    // ==================================================================
    // 常量（原脚本 7677-7860）
    // ==================================================================

    /** 虚仆 metadata 键（原 {@code XU_META}）。 */
    public static final String SERVANT_META = "Tr_xu_servant";

    /** 每 10 秒召唤一只虚仆。 */
    private static final int SERVANT_INTERVAL_SEC = 10;

    /** 虚仆移速（格/秒 ≈ 15）。 */
    private static final double SERVANT_SPEED = 0.35d;

    /** 场上同时最多 8 只虚仆。 */
    private static final int SERVANT_MAX = 8;

    /** 以天虚为圆心的落点半径。 */
    private static final double SERVANT_RING = 3.2d;

    /** 虚仆名字前缀。 */
    private static final String SERVANT_PREFIX = "§8[§f虚仆§8] ";

    /** 无限矿石单方块的候选（不含深板岩变种）。 */
    private static final String[] ORE_TYPES = {
            "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE",
            "REDSTONE_ORE", "EMERALD_ORE", "DIAMOND_ORE", "LAPIS_ORE"};

    /** 土链：只有泥土、沙子、粘土、灵魂沙，依次排开。 */
    private static final String[] SOIL_KINDS = {"DIRT", "SAND", "CLAY", "SOUL_SAND"};

    /** 橡树树干高度。 */
    private static final int TREE_HEIGHT = 4;

    /** 挑战道具：每 10 秒刷一种、存在 30 秒、效果 30 秒。 */
    private static final int CH_INTERVAL_SEC = 10;
    private static final int CH_LIFE_SEC = 30;
    private static final int CH_BUFF_SEC = 30;

    /** 熟悉操作倒计时（秒）——结束前不刷虚仆。 */
    private static final int FAMILIAR_SEC = 30;

    // ==================================================================
    // 单局状态
    // ==================================================================

    /** 一局的经营状态（原脚本 {@code state.xuGame}）。 */
    public static final class Game {
        public BossState state;
        public int tick;
        public BukkitTask task;
        public World world;
        public BossArena.Region region;
        /** 无限矿石单方块（挖掉换一种）。 */
        public int[] oreBlock;
        /** 橡树（原木键 + 全部键，收场时整棵清掉）。 */
        public final List<Tree> trees = new ArrayList<>();
        /** 土链。 */
        public final List<Soil> soils = new ArrayList<>();
        /** 挑战道具（原脚本 {@code xuChallengeItems}）。 */
        public final List<ChallengeItem> challengeItems = new ArrayList<>();
        /** 熟悉倒计时结束前不刷虚仆。 */
        public boolean spawnReady;
        /** 破法弓已到手（双子护体解除）。 */
        public boolean bowUnlocked;
        public BukkitTask countdownTask;
    }

    /** 一棵橡树（原脚本 {@code xuSpawnTree} 的返回）。 */
    public static final class Tree {
        public final Map<String, String> logKeys;
        public final Map<String, String> allKeys;

        public Tree(Map<String, String> logKeys, Map<String, String> allKeys) {
            this.logKeys = logKeys;
            this.allKeys = allKeys;
        }
    }

    /** 一格土壤。 */
    public static final class Soil {
        public final int x;
        public final int y;
        public final int z;
        public final String mat;

        public Soil(int x, int y, int z, String mat) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mat = mat;
        }
    }

    /** 一个挑战道具。 */
    public static final class ChallengeItem {
        public final org.bukkit.entity.Item entity;
        public final long born;
        public final String kind;

        public ChallengeItem(org.bukkit.entity.Item entity, long born, String kind) {
            this.entity = entity;
            this.born = born;
            this.kind = kind;
        }
    }

    /** 战局键 → 单局状态。 */
    private static final Map<String, Game> GAMES = new ConcurrentHashMap<>();

    /** 经营是否进行中（原脚本 {@code xuStageActive}；装备侧据此拦截掉落）。 */
    public static boolean active(Player player) {
        try {
            if (player == null) return false;
            BossState st = BossFight.bySummoner(player);
            if (st == null) {
                st = BossFight.byName(player.getName());
            }
            return st != null && GAMES.containsKey(st.key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前局（没有则 null）。 */
    public static Game gameOf(BossState state) {
        return (state == null) ? null : GAMES.get(state.key);
    }

    // ==================================================================
    // 启停（BossTwin 的 XuGameHook 调）
    // ==================================================================

    /** 启动经营（原脚本 {@code startXuGame}）：30 秒熟悉倒计时 + 资源铺场 + 每秒主循环。 */
    public static void start(Player player, BossState state) {
        try {
            stop(state);
            Game g = new Game();
            g.state = state;
            g.world = state.arena.twoWorld;
            g.region = state.arena.twoRegion;
            GAMES.put(state.key, g);
            state.minigame.world = g.world;
            state.minigame.region = g.region;
            Plugin plugin = TT.plugin();
            if (plugin == null) return;

            // 破法弓随开局发放（原脚本从天实商店换购 —— 商店属批次 6c，偏差已记录）
            try {
                givePofabow(player);
            } catch (Throwable t) {
                BossLog.warn("破法弓发放失败: " + t);
            }
            // 双子挑战阶段：不给饱和效果（原脚本注释「按需求删除」）
            BossPhaseTwo.clearSaturation(player);

            // 资源铺场：4 棵橡树（2×2）+ 一排土壤 + 1 格无限矿石，全排在天实左手边那侧
            try {
                if (g.world != null && g.region != null) {
                    float[] anchor = anchorFrame(state);
                    for (int[] spot : treeSpots(g.region, anchor)) {
                        Tree tr = spawnTree(g.world, g.region, spot[0], spot[1]);
                        if (tr != null) g.trees.add(tr);
                    }
                    int[] soilStart = soilStart(g.region, anchor);
                    g.soils.addAll(spawnSoils(g.world, g.region, soilStart[0], soilStart[1]));
                    spawnOreBlock(state, g);
                }
            } catch (Throwable t) {
                BossLog.warn("双子资源铺场失败: " + t);
            }

            g.task = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> tick(player, state), 60L, 20L);
            state.minigame.task = g.task;

            try {
                player.sendMessage("§d[天虚] §f想碰到我们？先拿到 §d破法弓 §f再说。");
                player.sendMessage("§e[旁白] §f挑战期间，天虚每10秒会召唤虚仆，虚仆有各自的特性哦");
                player.sendMessage("§d[天实] §f请收集场上物资。");
                player.sendMessage("§e[旁白] §f部分物资受工具是否开启熔炼模式影响哦。");
            } catch (Throwable ignored) {
                // 忽略
            }
            // 30 秒熟悉操作倒计时（结束前不刷虚仆）
            final int[] left = {FAMILIAR_SEC};
            g.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (BossFight.byKey(state.key) != state || !player.isOnline()) {
                        BossState.cancelTask(g.countdownTask);
                        g.countdownTask = null;
                        return;
                    }
                    int v = left[0];
                    if (v == 30 || v == 25 || v == 20 || v == 15 || v == 10 || v <= 5) {
                        player.sendMessage("§e[旁白] §f熟悉操作：§a" + v + "§f 秒");
                        player.playSound(player.getLocation(),
                                v <= 5 ? "block.note_block.hat" : "block.note_block.bell",
                                0.8f, v <= 5 ? 1.6f : 1.2f);
                    }
                    left[0]--;
                    if (left[0] < 0) {
                        BossState.cancelTask(g.countdownTask);
                        g.countdownTask = null;
                        g.spawnReady = true;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, 20L);
            state.minigame.running();
        } catch (Throwable t) {
            BossLog.warn("双子经营启动失败: " + t);
        }
    }

    /** 停经营（原脚本 {@code stopXuGame} 的经营部分）：清资源方块 + 虚仆 + 挑战道具 + 增益。 */
    public static void stop(BossState state) {
        try {
            if (state == null) return;
            Game g = GAMES.remove(state.key);
            if (g != null) {
                BossState.cancelTask(g.task);
                g.task = null;
                BossState.cancelTask(g.countdownTask);
                g.countdownTask = null;
                state.minigame.task = null;
                if (g.world != null) {
                    // 无限矿石
                    if (g.oreBlock != null) {
                        setBlock(g.world, g.oreBlock[0], g.oreBlock[1], g.oreBlock[2], "AIR");
                        g.oreBlock = null;
                    }
                    // 橡树（含树叶）
                    for (Tree tr : g.trees) {
                        for (String key : tr.allKeys.keySet()) {
                            String[] p = key.split(",");
                            setBlock(g.world, Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                    Integer.parseInt(p[2]), "AIR");
                        }
                    }
                    g.trees.clear();
                    // 土链
                    for (Soil so : g.soils) {
                        setBlock(g.world, so.x, so.y, so.z, "AIR");
                    }
                    g.soils.clear();
                }
            }
            // 虚仆
            for (Entity e : state.minigame.servants) {
                BossState.removeEntity(e);
            }
            state.minigame.servants.clear();
            // running() 口径复位（装备侧的「经营中」判定读它）
            state.minigame.region = null;
            state.minigame.world = null;
            // 挑战道具
            if (g != null) {
                for (ChallengeItem ci : g.challengeItems) {
                    BossState.removeEntity(ci.entity);
                }
                g.challengeItems.clear();
            }
            // 挑战增益
            Player pl = (state.playerName == null) ? null : Bukkit.getPlayerExact(state.playerName);
            if (pl != null) {
                clearBuffs(pl);
                // 天实商店还开着就关掉：账本是跨局保留的，收场后仍开着 GUI 继续点
                // 就能在阶段外消费（含破法弓兑换）
                TwinShop.closeFor(pl);
            }
            // 离线玩家的增益条目也要摘：BUFFS 按 UUID 记，只清在线者会留下离线条目
            try {
                if (state.summonerUuid != null) BUFFS.remove(UUID.fromString(state.summonerUuid));
            } catch (Throwable ignored) {
                // 忽略
            }
            // 储水炼药锅登记随局清空（与「重启丢失」同一口径，见 TwinShop.clearWaterSource）
            TwinShop.clearWaterSource();
        } catch (Throwable t) {
            BossLog.warn("双子经营停止失败: " + t);
        }
    }

    /** 供卸载使用：清掉全部局的资源记录。 */
    public static void shutdown() {
        for (String key : new ArrayList<>(GAMES.keySet())) {
            BossState st = BossFight.byKey(key);
            stop(st);
        }
        GAMES.clear();
        SUPPRESS.clear();
        BUFFS.clear();
    }

    // ==================================================================
    // 每秒主循环（原脚本 xuGameTick）
    // ==================================================================

    private static void tick(Player player, BossState state) {
        try {
            Game g = GAMES.get(state.key);
            if (g == null) return;
            if (BossFight.byKey(state.key) != state) {
                stop(state);
                return;
            }
            g.tick++;
            // ★ 双子护体解除：拿到破法弓后解除双本体的无敌（原脚本每秒确认一次）。
            //   不解除的话「护体」没破时箭和近战都会被原版无敌吃掉，表现为打不动双子。
            try {
                if (!g.bowUnlocked) {
                    Player p = (player != null && player.isOnline()) ? player : playerOf(state);
                    if (p != null && hasPofabow(p)) {
                        g.bowUnlocked = true;
                        try {
                            BossFx.sendLine(p, "§c[糖然]§b§l什么？他们俩的护体……");
                            BossFx.sendLine(p, "§c[天虚]§f§l那就来吧，看看你能不能碰到我们。");
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
                if (g.bowUnlocked) {
                    Entity a = slotOf(state).body;
                    Entity b = slotOf(state).body2;
                    if (a != null && a.isValid() && a.isInvulnerable()) a.setInvulnerable(false);
                    if (b != null && b.isValid() && b.isInvulnerable()) b.setInvulnerable(false);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 无限矿石单方块：没有就刷；被爆炸等弄没了就补（挖掉那颗由破坏事件秒刷）
            try {
                if (g.world != null) {
                    if (g.oreBlock == null) {
                        spawnOreBlock(state, g);
                    } else {
                        Block cur = g.world.getBlockAt(g.oreBlock[0], g.oreBlock[1], g.oreBlock[2]);
                        String tn = cur.getType().name();
                        if ("AIR".equals(tn) || "CAVE_AIR".equals(tn)) {
                            setBlock(g.world, g.oreBlock[0], g.oreBlock[1], g.oreBlock[2], rollOreMat());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 土链兜底补回（挖完立刻补的那次由破坏事件做；这里补被别的插件留空的）
            try {
                for (Soil so : g.soils) {
                    String tn = g.world.getBlockAt(so.x, so.y, so.z).getType().name();
                    if ("AIR".equals(tn) || "CAVE_AIR".equals(tn)) {
                        setBlock(g.world, so.x, so.y, so.z, so.mat);
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 天虚还在 + 熟悉期结束 → 每 10 秒放一只虚仆
            Entity vex = slotOf(state).body;
            if (g.spawnReady && alive(vex)
                    && g.tick % Math.max(1, SERVANT_INTERVAL_SEC) == 0
                    && servantAlive(state) < SERVANT_MAX) {
                spawnServant(state, vex);
            }
            // 虚仆：每秒重新锁定玩家 + 清火 + 每 5 秒放小技能
            servantSkillTick(state, player, g);
            // 挑战道具：每 10 秒一种、存在 30 秒、拾取给效果
            challengeItemsTick(state, player, g);
            // 守护之盾/清明之水：持续效果维护
            challengeBuffTick(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 虚仆（原脚本 9990-10207）
    // ==================================================================

    /** 一档虚仆定义。 */
    private record Tier(int weight, int coinLo, int coinHi, MobDef[] mobs) {}

    private record MobDef(String type, String name, String skill, boolean fireResistant) {}

    private static final Tier[] TIERS = {
            new Tier(60, 5, 10, new MobDef[]{
                    new MobDef("ZOMBIE", "行尸", "slow", false),
                    new MobDef("SKELETON", "白骨", "shoot", true),
                    new MobDef("CREEPER", "裂身", "boom", false),
                    new MobDef("SPIDER", "噬影", "blind", false)}),
            new Tier(20, 20, 40, new MobDef[]{
                    new MobDef("WITHER_SKELETON", "焦骨", "wither", true),
                    new MobDef("BLAZE", "焚身", "blaze", true),
                    new MobDef("BREEZE", "旋风", "wind", false),
                    new MobDef("GUARDIAN", "潮守", "laser", false)}),
            new Tier(15, 80, 160, new MobDef[]{
                    new MobDef("ELDER_GUARDIAN", "古守", "fatigue", false),
                    new MobDef("PILLAGER", "劫掠者", "charge", false),
                    new MobDef("RAVAGER", "劫掠兽", "ram", false),
                    new MobDef("ENDERMAN", "影主", "teleport", false)}),
            new Tier(5, 250, 500, new MobDef[]{
                    new MobDef("WARDEN", "监守", "sonic", false),
                    new MobDef("WITHER", "凋主", "withershot", false)}),
    };

    /** 刷怪权重（原脚本 {@code XU_SERVANT_PICK}：45/35/15/5）。 */
    private static final int[] PICK_WEIGHTS = {45, 35, 15, 5};

    /** 各档显示名的渐变色（原脚本 {@code XU_SERVANT_GRAD}）。 */
    private static final String[][] GRADS = {
            {"a7f3d0", "059669"}, {"93c5fd", "1d4ed8"},
            {"fcd34d", "b45309"}, {"fca5a5", "7f1d1d"}};

    private static int rollTierIndex() {
        int total = 0;
        for (int w : PICK_WEIGHTS) total += w;
        double roll = Math.random() * total;
        int acc = 0;
        for (int t = 0; t < PICK_WEIGHTS.length; t++) {
            acc += PICK_WEIGHTS[t];
            if (roll <= acc) return t;
        }
        return 0;
    }

    /** 虚仆显示名（§x 渐变）。 */
    private static String servantName(int tierIdx, String mobName) {
        String[] g = GRADS[Math.max(0, Math.min(GRADS.length - 1, tierIdx))];
        return SERVANT_PREFIX + gradient(mobName, g[0], g[1]);
    }

    /** 文本渐变（原脚本 {@code xuGradient}：逐字 §x 真彩色）。 */
    private static String gradient(String text, String fromHex, String toHex) {
        try {
            if (text == null || text.isEmpty()) return String.valueOf(text);
            int fr = Integer.parseInt(fromHex.substring(0, 2), 16);
            int fg = Integer.parseInt(fromHex.substring(2, 4), 16);
            int fb = Integer.parseInt(fromHex.substring(4, 6), 16);
            int tr = Integer.parseInt(toHex.substring(0, 2), 16);
            int tg = Integer.parseInt(toHex.substring(2, 4), 16);
            int tb = Integer.parseInt(toHex.substring(4, 6), 16);
            int n = Math.max(1, text.length() - 1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                double t = i / (double) n;
                out.append("§x")
                        .append(hexPair(fr + (int) ((tr - fr) * t)))
                        .append(hexPair(fg + (int) ((tg - fg) * t)))
                        .append(hexPair(fb + (int) ((tb - fb) * t)))
                        .append(text.charAt(i));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return String.valueOf(text);
        }
    }

    private static String hexPair(int v) {
        int c = Math.max(0, Math.min(255, v));
        return String.format("%02x", c);
    }

    /** 场上还活着的虚仆数。 */
    private static int servantAlive(BossState state) {
        int n = 0;
        for (Entity e : state.minigame.servants) {
            if (alive(e)) n++;
        }
        return n;
    }

    /** 这个实体是不是本插件的虚仆。 */
    public static boolean isServant(Entity e) {
        try {
            return e != null && e.isValid() && e.hasMetadata(SERVANT_META);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 召唤一只虚仆（原脚本 {@code xuSpawnServant}）。 */
    private static void spawnServant(BossState state, Entity vex) {
        try {
            World world = vex.getWorld();
            Location base = vex.getLocation();
            double ang = Math.random() * Math.PI * 2;
            double r = 1.2 + Math.random() * SERVANT_RING;
            Location loc = new Location(world,
                    base.getX() + Math.cos(ang) * r,
                    base.getY() + 1.0d,
                    base.getZ() + Math.sin(ang) * r);
            int tierIdx = rollTierIndex();
            Tier tier = TIERS[tierIdx];
            MobDef mob = tier.mobs()[(int) (Math.random() * tier.mobs().length)];
            Entity ent;
            try {
                ent = world.spawnEntity(loc, EntityType.valueOf(mob.type()));
            } catch (Throwable t) {
                return;
            }
            ent.setCustomName(servantName(tierIdx, mob.name()));
            ent.setCustomNameVisible(true);
            // persistent(false) + PDC 归属：崩溃/重启后不留名为虚仆的残留怪
            // （metadata 活不过重启，isBossBody/cleanupStaleStartup 靠 PDC 标记仍能认出）
            ent.setPersistent(false);
            BossArena.tagEntity(ent);
            ent.setMetadata(SERVANT_META, new FixedMetadataValue(TT.plugin, tierIdx + 1));
            ent.setMetadata("Tr_xu_skill", new FixedMetadataValue(TT.plugin, mob.skill()));
            ent.setMetadata("Tr_xu_coin", new FixedMetadataValue(TT.plugin,
                    tier.coinLo() + "," + tier.coinHi()));
            if (ent instanceof LivingEntity le) {
                le.setRemoveWhenFarAway(false);
                le.setCanPickupItems(false);
                le.setFireTicks(0);
                // 永久防火，避免阳光烧死（原脚本：直接给 30 分钟防火）
                le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                        20 * 60 * 30, 0, false, false, false));
                // 移速 ≈ 15 格/秒 + 立刻咬人
                try {
                    var spd = le.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
                    if (spd != null) spd.setBaseValue(SERVANT_SPEED);
                    var follow = le.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
                    if (follow != null) follow.setBaseValue(48.0d);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (ent instanceof Mob m) {
                Player target = playerOf(state);
                if (target != null) {
                    m.setTarget(target);
                }
            }
            state.minigame.servants.add(ent);
            try {
                world.spawnParticle(Particle.END_ROD, loc, 14, 0.3, 0.5, 0.3, 0.02);
                world.playSound(loc, "entity.vex.ambient", 0.7f, 1.4f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 虚仆每秒维护（原脚本 {@code xuServantSkillTick}）。 */
    private static void servantSkillTick(BossState state, Player player, Game g) {
        try {
            List<Entity> keep = new ArrayList<>();
            for (Entity ent : state.minigame.servants) {
                if (!alive(ent)) continue;
                keep.add(ent);
                ent.setFireTicks(0);
                Player p = (player != null && player.isOnline()) ? player : playerOf(state);
                if (p != null && ent instanceof Mob m) {
                    m.setTarget(p);
                }
                String skill = metaString(ent, "Tr_xu_skill");
                if (skill == null || skill.isEmpty()) continue;
                if (g.tick % 5 == 0 && p != null) {
                    servantSkill(ent, p, skill, state);
                }
            }
            state.minigame.servants.clear();
            state.minigame.servants.addAll(keep);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 虚仆小技能（原脚本 {@code xuServantSkill}，每 5 秒一次）。 */
    private static void servantSkill(Entity ent, Player player, String skill, BossState state) {
        try {
            World world = ent.getWorld();
            Location eye = (ent instanceof LivingEntity le) ? le.getEyeLocation() : ent.getLocation();
            Vector toP = player.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            Vector dir = (toP.lengthSquared() > 0.0001d) ? toP.normalize() : new Vector(0, 0, 1);
            switch (skill) {
                case "shoot" -> {
                    try {
                        Projectile ar = world.spawn(eye.clone().add(dir.clone().multiply(0.8)),
                                org.bukkit.entity.Arrow.class);
                        if (ar instanceof org.bukkit.entity.Arrow a) {
                            a.setShooter((org.bukkit.projectiles.ProjectileSource) ent);
                            a.setVelocity(dir.clone().multiply(2.2));
                            a.setDamage(2.0);
                        }
                        world.playSound(eye, "entity.arrow.shoot", 0.7f, 1.2f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "blaze", "withershot" -> {
                    try {
                        LargeFireball fb = world.spawn(eye.clone().add(dir.clone().multiply(0.9)),
                                LargeFireball.class);
                        fb.setDirection(dir);
                        fb.setYield(0.0f);
                        fb.setIsIncendiary(false);
                        world.spawnParticle(skill.equals("blaze") ? Particle.FLAME : Particle.SOUL_FIRE_FLAME,
                                eye, 20, 0.3, 0.3, 0.3, 0.05);
                        world.playSound(eye, "entity.blaze.shoot", 0.8f, 1.0f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "laser", "sonic" -> {
                    double dmg = skill.equals("sonic") ? 3.0 : 2.0;
                    boolean hitP = false;
                    for (int s = 1; s <= 18; s++) {
                        Location p2 = eye.clone().add(dir.clone().multiply(s));
                        try {
                            world.spawnParticle(skill.equals("sonic") ? Particle.SONIC_BOOM : Particle.END_ROD,
                                    p2, skill.equals("sonic") ? 1 : 2, 0.02, 0.02, 0.02, 0.0);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        try {
                            if (player.getLocation().add(0, 1, 0).distanceSquared(p2) < 2.2) {
                                hitP = true;
                                break;
                            }
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    if (hitP) {
                        player.damage(dmg);
                        if (skill.equals("sonic")) regenSuppress(player, 10000);
                    } else {
                        world.playSound(eye, skill.equals("sonic")
                                ? "entity.warden.sonic_charge" : "entity.guardian.attack", 0.8f, 1.0f);
                    }
                }
                case "wind" -> {
                    try {
                        if (player.getLocation().distanceSquared(ent.getLocation()) < 49) {
                            Vector v = player.getVelocity();
                            v.setY(1.0);
                            player.setVelocity(v);
                            world.spawnParticle(Particle.CLOUD, player.getLocation(), 24, 0.4, 0.2, 0.4, 0.05);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "charge", "ram" -> {
                    try {
                        double step = skill.equals("ram") ? 3.0 : 2.0;
                        ent.teleport(ent.getLocation().clone().add(dir.clone().multiply(step)));
                        world.spawnParticle(Particle.CLOUD, ent.getLocation(), 14, 0.3, 0.2, 0.3, 0.03);
                        world.playSound(ent.getLocation(), "entity.ravager.step", 0.8f, 1.1f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "teleport" -> {
                    try {
                        Location behind = player.getLocation().clone()
                                .add(player.getLocation().getDirection().normalize().multiply(-1.8));
                        ent.teleport(behind);
                        world.spawnParticle(Particle.PORTAL, behind, 30, 0.3, 0.6, 0.3, 0.2);
                        world.playSound(behind, "entity.enderman.teleport", 0.8f, 1.2f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                default -> {
                    // slow / blind / wither / fatigue / boom：命中时由事件层附效（见 KatarinaListener 同级规则）
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 虚仆死亡：只记「虚币」进账本（按档位区间），不进背包。 */
    public static void servantCoinReward(Player killer, Entity dead) {
        try {
            if (killer == null || dead == null) return;
            // 杀戮光环路径已经直接结算过 → 别再给一次
            if (dead.hasMetadata("Tr_xu_aura_paid")) return;
            int lo = 1;
            int hi = 3;
            String coin = metaString(dead, "Tr_xu_coin");
            if (coin != null && coin.contains(",")) {
                String[] parts = coin.split(",");
                try {
                    lo = Integer.parseInt(parts[0].trim());
                    hi = Integer.parseInt(parts[1].trim());
                } catch (Throwable ignored) {
                    lo = 1;
                    hi = 3;
                }
            }
            int n = lo + (int) Math.floor(Math.random() * Math.max(1, hi - lo + 1));
            TwinLedger.add(killer, "coin", n);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 虚仆死亡事件（事件层调）：清掉落与经验，虚币走账本。 */
    public static boolean onServantDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        try {
            Entity dead = event.getEntity();
            if (!isServant(dead)) return false;
            event.getDrops().clear();
            event.setDroppedExp(0);
            Player killer = (dead instanceof LivingEntity le) ? le.getKiller() : null;
            if (killer == null) {
                // 杀戮光环秒杀（伤害事件被取消，getKiller 可能为空）→ 按记录的击杀者算
                String uid = metaString(dead, "Tr_xu_aura_killer");
                if (uid != null) {
                    try {
                        Player pl = Bukkit.getPlayer(UUID.fromString(uid));
                        if (pl != null) killer = pl;
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
            servantCoinReward(killer, dead);
            Location loc = dead.getLocation();
            try {
                loc.getWorld().spawnParticle(Particle.SCULK_SOUL,
                        loc.clone().add(0, 0.6, 0), 18, 0.3, 0.4, 0.3, 0.01);
            } catch (Throwable ignored) {
                // 忽略
            }
            // 从在场列表摘掉（下一拍 skillTick 也会自清，这里顺手）
            BossState st = BossFight.byEntity(dead);
            if (st != null) {
                st.minigame.servants.remove(dead);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 杀戮光环：命中即秒杀虚仆（事件层 HIGHEST 调；视为玩家击杀照常给虚币）。 */
    public static boolean auraKill(EntityDamageByEntityEvent event) {
        try {
            Entity victim = event.getEntity();
            if (!isServant(victim)) return false;
            if (!(event.getDamager() instanceof Player damager)) return false;
            if (!buffActive(damager, "aura")) return false;
            event.setCancelled(true);
            auraKillServant(damager, victim);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 虚仆打玩家（事件层 HIGHEST 调，原脚本 12926-12966 行）：
     * <b>固定 1 点伤害</b>（不分虚仆类型，监守也只 1 点）+ 命中附带各类型小特性。
     *
     * @return true = 这次伤害已由本方法接管（调整过数值）
     */
    public static boolean servantHit(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return false;
            BossState st = BossFight.bySummoner(victim);
            if (st == null || !st.guardians.isTwinStage()) return false;
            Entity damager = event.getDamager();
            if (damager == null || !isServant(damager)) return false;
            // 守护之盾：30 秒内免疫一切伤害
            if (buffActive(victim, "shield")) {
                event.setCancelled(true);
                return true;
            }
            String skill = metaString(damager, "Tr_xu_skill");
            if ("boom".equals(skill)) {
                // 裂身自爆：固定扣 5 血 + 10 秒回血压制
                event.setDamage(5.0d);
                regenSuppress(victim, 10000);
                try {
                    victim.sendMessage("§c[虚仆] §7裂身自爆：-5 生命，10 秒内无法自然回血。");
                } catch (Throwable ignored) {
                    // 忽略
                }
                return true;
            }
            event.setDamage(1.0d);
            // 命中附带小特性
            try {
                switch (skill == null ? "" : skill) {
                    case "slow" -> victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOWNESS, 60, 0));
                    case "blind" -> victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.BLINDNESS, 60, 0));
                    case "wither" -> victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.WITHER, 60, 0));
                    case "fatigue" -> victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.MINING_FATIGUE, 100, 0));
                    case "sonic" -> regenSuppress(victim, 10000);
                    default -> {
                        // 无特性
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            // 无敌帧压到 2：成群虚仆围殴时不至于完全打不动玩家
            Plugin plugin = TT.plugin();
            if (plugin != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        victim.setNoDamageTicks(2);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                });
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 杀戮光环击杀虚仆：先按档位给钱 + 标记已结算（死亡事件兜底不再给），下一拍送它上路。
     */
    private static void auraKillServant(Player player, Entity s) {
        try {
            if (player == null || s == null || !alive(s)) return;
            if (!s.hasMetadata("Tr_xu_aura_paid")) {
                servantCoinReward(player, s);
                s.setMetadata("Tr_xu_aura_paid", new FixedMetadataValue(TT.plugin, "1"));
            }
            s.setMetadata("Tr_xu_aura_killer",
                    new FixedMetadataValue(TT.plugin, player.getUniqueId().toString()));
            Entity target = s;
            Plugin plugin = TT.plugin();
            if (plugin != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (target == null) return;
                        if (target.isValid() && !target.isDead()
                                && target instanceof LivingEntity le) {
                            le.setHealth(0.0);
                        }
                        if (target.isValid() && !target.isDead()
                                && target instanceof LivingEntity le) {
                            le.damage(1000.0d, player);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                });
            } else {
                if (s instanceof LivingEntity le) {
                    le.setHealth(0.0);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 资源铺场与回收（原脚本 xuSpawnOreBlock / xuSpawnTree / xuSpawnSoils / xuOnBlockBreakTrack）
    // ==================================================================

    private static String rollOreMat() {
        return ORE_TYPES[(int) (Math.random() * ORE_TYPES.length)];
    }

    private static boolean setBlock(World world, int x, int y, int z, String matName) {
        try {
            Material mat = Material.matchMaterial(matName);
            if (mat == null) return false;
            world.getBlockAt(x, y, z).setType(mat, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 资源区锚点：天实「左手边第 10 格」。
     *
     * @return {x, z, lx, lz}（天实左手方向的单位向量）
     */
    private static float[] anchorFrame(BossState state) {
        Entity allay = slotOf(state).body2;
        Location loc = (allay != null && allay.isValid()) ? allay.getLocation() : null;
        BossArena.Region r = state.arena.twoRegion;
        if (loc == null && r == null) return new float[]{0, 0, 0, 1};
        if (loc == null) {
            loc = new Location(state.arena.twoWorld,
                    r.minX() + (r.maxX() - r.minX()) / 2.0d + 0.5d, r.minY(),
                    r.minZ() + (r.maxZ() - r.minZ()) / 2.0d + 0.5d);
        }
        double yaw = loc.getYaw() * Math.PI / 180.0d;
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double lx = fz;
        double lz = -fx;
        return new float[]{(float) (loc.getX() + lx * 10), (float) (loc.getZ() + lz * 10),
                (float) lx, (float) lz};
    }

    private static int[] clampToRegion(BossArena.Region r, double x, double z) {
        int cx = (int) Math.max(r.minX() + 1, Math.min(r.maxX() - 1, Math.round(x)));
        int cz = (int) Math.max(r.minZ() + 1, Math.min(r.maxZ() - 1, Math.round(z)));
        return new int[]{cx, cz};
    }

    private static int[][] treeSpots(BossArena.Region r, float[] anchor) {
        int inset = 5;
        if (anchor == null || (anchor[2] == 0 && anchor[3] == 1 && anchor[0] == 0 && anchor[1] == 0)) {
            return new int[][]{
                    {r.minX() + inset, r.minZ() + inset},
                    {r.maxX() - inset, r.minZ() + inset},
                    {r.minX() + inset, r.maxZ() - inset},
                    {r.maxX() - inset, r.maxZ() - inset}};
        }
        int bx = Math.round(anchor[0]);
        int bz = Math.round(anchor[1]);
        int lx = Math.round(anchor[2]);
        int lz = Math.round(anchor[3]);
        return new int[][]{
                clampToRegion(r, bx, bz),
                clampToRegion(r, bx + lx * 2, bz + lz * 2),
                clampToRegion(r, bx + lx * 4, bz + lz * 4),
                clampToRegion(r, bx + lx * 2 + lx * 2, bz + lz * 4)};
    }

    private static int[] soilStart(BossArena.Region r, float[] anchor) {
        if (anchor == null || (anchor[2] == 0 && anchor[3] == 1 && anchor[0] == 0 && anchor[1] == 0)) {
            return new int[]{r.minX() + 4, r.maxZ() - 5};
        }
        return clampToRegion(r, anchor[0] + anchor[2] * 5, anchor[1] + anchor[3] * 5);
    }

    /** 橡木树：树干 4 格 + 双层叶 + 顶叶；砍光后原地重刷。 */
    private static Tree spawnTree(World world, BossArena.Region r, int x, int z) {
        try {
            int y = r.minY() + 1;
            Map<String, String> keys = new java.util.LinkedHashMap<>();
            Map<String, String> allKeys = new java.util.LinkedHashMap<>();
            for (int i = 0; i < TREE_HEIGHT; i++) {
                if (setBlock(world, x, y + i, z, "OAK_LOG")) {
                    keys.put(key(x, y + i, z), "OAK_LOG");
                    allKeys.put(key(x, y + i, z), "OAK_LOG");
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (setBlock(world, x + dx, y + TREE_HEIGHT - 1, z + dz, "OAK_LEAVES")) {
                        allKeys.put(key(x + dx, y + TREE_HEIGHT - 1, z + dz), "OAK_LEAVES");
                    }
                    if (setBlock(world, x + dx, y + TREE_HEIGHT, z + dz, "OAK_LEAVES")) {
                        allKeys.put(key(x + dx, y + TREE_HEIGHT, z + dz), "OAK_LEAVES");
                    }
                }
            }
            if (setBlock(world, x, y + TREE_HEIGHT + 1, z, "OAK_LEAVES")) {
                allKeys.put(key(x, y + TREE_HEIGHT + 1, z), "OAK_LEAVES");
            }
            return new Tree(keys, allKeys);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 土壤：一排各种土（并列），挖掉后立刻补回。 */
    private static List<Soil> spawnSoils(World world, BossArena.Region r, int startX, int startZ) {
        List<Soil> out = new ArrayList<>();
        try {
            int y = r.minY() + 1;
            // 土链的排布方向：默认沿 X；锚点给了方向就沿它
            int lx = 1;
            int lz = 0;
            for (int i = 0; i < SOIL_KINDS.length; i++) {
                int[] p = clampToRegion(r, startX + lx * i, startZ + lz * i);
                setBlock(world, p[0], y, p[1], SOIL_KINDS[i]);
                out.add(new Soil(p[0], y, p[1], SOIL_KINDS[i]));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 无限矿石单方块：土链旁错开 2 格，贴着土链排。 */
    private static void spawnOreBlock(BossState state, Game g) {
        try {
            World world = g.world;
            BossArena.Region r = g.region;
            if (world == null || r == null) return;
            int baseX = r.minX() + 4;
            int baseZ = r.maxZ() - 5;
            int[] p = clampToRegion(r, Math.round(baseX + 2), Math.round(baseZ));
            int y = r.minY() + 1;
            String mat = rollOreMat();
            setBlock(world, p[0], y, p[1], mat);
            g.oreBlock = new int[]{p[0], y, p[1]};
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 资源被挖掉（事件层 {@code BlockBreakEvent} 调，原脚本 {@code xuOnBlockBreakTrack}）。
     *
     * <p>三条线：入账（账本，不占背包）→ 破坏不掉落（防止自动入包，全部只走账本）→
     * 按资源类型立刻补回。</p>
     */
    public static void onBlockBreak(Player player, Block block, org.bukkit.event.block.BlockBreakEvent event) {
        try {
            BossState st = BossFight.bySummoner(player);
            Game g = (st != null) ? GAMES.get(st.key) : null;
            if (g == null) return;
            // 区域闸：资源入账与补种只认本局经营区域内的方块。没有这道闸，
            // 经营期间挖服务器任意矿石/原木/作物都会入账（ledgerFromBlock 只按材质
            // 匹配，等于全服挖矿刷货币），场外破坏还会被无差别吞掉掉落。
            if (g.world == null || g.region == null
                    || !g.world.equals(block.getWorld())
                    || !g.region.contains(block.getX(), block.getY(), block.getZ())) {
                return;
            }
            // 资源入账
            ledgerFromBlock(player, block);
            // 双子经营期间：破坏方块一律没有掉落物
            try {
                event.setDropItems(false);
            } catch (Throwable ignored) {
                // 忽略
            }
            String k = key(block.getX(), block.getY(), block.getZ());
            // 无限矿石单方块：挖掉 → 1 拍后原地换一种矿石
            if (g.oreBlock != null && g.oreBlock[0] == block.getX()
                    && g.oreBlock[1] == block.getY() && g.oreBlock[2] == block.getZ()) {
                respawnLater(g.world != null ? g.world : block.getWorld(),
                        block.getX(), block.getY(), block.getZ(), rollOreMat());
                return;
            }
            // 土壤：挖掉立刻补回
            for (Soil s : g.soils) {
                if (s.x == block.getX() && s.y == block.getY() && s.z == block.getZ()) {
                    respawnLater(block.getWorld(), s.x, s.y, s.z, s.mat);
                    return;
                }
            }
            // 橡树：原木破坏一块就立刻原地刷新（树叶不参与判定）
            for (Tree tr : g.trees) {
                if (tr.logKeys.containsKey(k)) {
                    respawnLater(g.world != null ? g.world : block.getWorld(),
                            block.getX(), block.getY(), block.getZ(), "OAK_LOG");
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void respawnLater(World world, int x, int y, int z, String mat) {
        try {
            Plugin plugin = TT.plugin();
            if (plugin == null) return;
            // 静态审查修正：+1 拍期间可能已收场——restoreArena 清场在先、
            // 迟到的 setBlock 在后会把资源方块"复活"残留原地。落方块前先确认局还活着
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    for (Game g : GAMES.values()) {
                        if (g.world != null && g.world.equals(world)) {
                            setBlock(world, x, y, z, mat);
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 1L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 资源入账（原脚本 xuLedgerFromBlock / xuFortuneMult）
    // ==================================================================

    /** 矿石 → 矿物精华（按稀有度）。 */
    private static final Map<String, Integer> ORE_ESSENCE = Map.of(
            "COAL_ORE", 1, "COPPER_ORE", 2, "IRON_ORE", 3, "LAPIS_ORE", 4,
            "REDSTONE_ORE", 5, "GOLD_ORE", 6, "DIAMOND_ORE", 8, "EMERALD_ORE", 10);

    /** 原木 → 木之精华（本局只刷橡树/丛林原木，其余同样记 1）。 */
    private static boolean isLog(String mat) {
        return mat.endsWith("_LOG") || mat.endsWith("_WOOD")
                || mat.startsWith("STRIPPED_");
    }

    /** 土类 → 对应点数字段。 */
    private static String soilField(String mat) {
        return switch (mat) {
            case "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "PODZOL",
                    "MYCELIUM", "DIRT_PATH", "FARMLAND" -> "dirt";
            case "CLAY" -> "terracotta";
            case "SOUL_SAND", "SOUL_SOIL" -> "soul";
            default -> null;
        };
    }

    /** 农作物 → 对应点数字段。 */
    private static String cropField(String mat) {
        return switch (mat) {
            case "CARROTS", "CARROT" -> "carrot";
            case "MELON", "MELON_SLICE" -> "melon";
            case "SUGAR_CANE" -> "cane";
            case "NETHER_WART" -> "wart";
            case "COCOA", "COCOA_BEANS" -> "cocoa";
            default -> null;
        };
    }

    /** 挖方块 → 记账（原脚本 {@code xuLedgerFromBlock}）。 */
    private static void ledgerFromBlock(Player player, Block block) {
        try {
            if (player == null || block == null) return;
            String t = block.getType().name();
            // 矿石 → 矿物精华（按稀有度）
            Integer ore = ORE_ESSENCE.get(t);
            if (ore != null) {
                TwinLedger.add(player, "mineral", ore);
                return;
            }
            // 原木 → 木之精华
            if (isLog(t)) {
                TwinLedger.add(player, "wood", 1);
                return;
            }
            // 沙子：工具的自动熔炼开着 → 玻璃点，否则沙子点
            if (t.equals("SAND") || t.equals("RED_SAND")) {
                boolean smelt = EquipmentBridge.twinSmeltActive(player);
                TwinLedger.add(player, smelt ? "glass" : "sand", 1);
                return;
            }
            // 土类 → 对应点数
            String soil = soilField(t);
            if (soil != null) {
                TwinLedger.add(player, soil, 1);
                return;
            }
            // 农作物 → 对应点数（自制时运：时运等级越高入账倍率越高）
            String crop = cropField(t);
            if (crop != null) {
                int lvl = fortuneLevel(player);
                int mult = (lvl <= 0) ? 1 : 1 + (int) Math.floor(Math.random() * (lvl + 1));
                TwinLedger.add(player, crop, mult);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 手持工具的时运等级。 */
    private static int fortuneLevel(Player player) {
        try {
            ItemStack it = player.getInventory().getItemInMainHand();
            if (it == null) return 0;
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return 0;
            return meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.FORTUNE);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ==================================================================
    // 挑战道具（原脚本 9698-9975）
    // ==================================================================

    private record ChallengeDef(String kind, Material icon, String name, List<String> lore) {}

    private static final List<ChallengeDef> CH_ITEMS = List.of(
            new ChallengeDef("shield", Material.SHIELD, "§b§l守护之盾",
                    List.of("§7拾取后：§f30 秒无敌§7（伤害全部无效）")),
            new ChallengeDef("clear", Material.POTION, "§b§l清明之水",
                    List.of("§7拾取后：§f30 秒持续清除所有负面效果")),
            new ChallengeDef("saturation", Material.GOLDEN_CARROT, "§6§l饱和药水",
                    List.of("§7拾取后：§f30 秒饱和效果")),
            new ChallengeDef("nectar", Material.EXPERIENCE_BOTTLE, "§d§l最终阶生命甘霖",
                    List.of("§7拾取后：§f立刻回满全部生命")),
            new ChallengeDef("aura", Material.NETHER_STAR, "§c§l杀戮光环",
                    List.of("§7拾取后：先清场所有虚仆", "§7再获得 §f30 秒秒杀虚仆§7 的能力")));

    private static final Map<UUID, Map<String, Long>> BUFFS = new ConcurrentHashMap<>();

    /** 挑战增益是否生效（原脚本 {@code xuBuffActive}；守护之盾的事件层规则也读它）。 */
    public static boolean buffActive(Player player, String key) {
        try {
            Map<String, Long> m = BUFFS.get(player.getUniqueId());
            Long until = (m == null) ? null : m.get(key);
            return until != null && until > System.currentTimeMillis();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void buffUntil(Player player, String key, long ms) {
        try {
            BUFFS.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(key, System.currentTimeMillis() + ms);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 清掉一位玩家的全部挑战增益（收场时）。 */
    public static void clearBuffs(Player player) {
        try {
            if (player != null) BUFFS.remove(player.getUniqueId());
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 挑战道具每秒维护（原脚本 {@code xuChallengeItemsTick}）。 */
    private static void challengeItemsTick(BossState state, Player player, Game g) {
        try {
            long now = System.currentTimeMillis();
            List<ChallengeItem> keep = new ArrayList<>();
            for (ChallengeItem rec : g.challengeItems) {
                if (!alive(rec.entity)) continue;
                if (now - rec.born > CH_LIFE_SEC * 1000L) {
                    BossState.removeEntity(rec.entity);
                    // 同步从二阶段地面掉落表摘除：这里不摘的话，过期实体引用会一直
                    // 留到收场才清，三阶段决斗每秒扫 drops 时还会对它做 ItemMeta 克隆
                    state.phaseTwo.drops.remove(rec.entity);
                    continue;
                }
                keep.add(rec);
            }
            g.challengeItems.clear();
            g.challengeItems.addAll(keep);
            if (g.tick % CH_INTERVAL_SEC == 0) {
                spawnChallengeItem(state, player, g);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 刷一个挑战道具（每种场上同时只允许 1 个）。 */
    private static void spawnChallengeItem(BossState state, Player player, Game g) {
        try {
            if (g.world == null || g.region == null) return;
            List<ChallengeDef> pool = new ArrayList<>();
            for (ChallengeDef def : CH_ITEMS) {
                boolean onField = false;
                for (ChallengeItem ci : g.challengeItems) {
                    if (ci.kind.equals(def.kind())) {
                        onField = true;
                        break;
                    }
                }
                if (!onField) pool.add(def);
            }
            if (pool.isEmpty()) return;
            ChallengeDef def = pool.get((int) (Math.random() * pool.size()));
            int[] p = clampToRegion(g.region,
                    g.region.minX() + 2 + Math.random() * Math.max(1, g.region.maxX() - g.region.minX() - 4),
                    g.region.minZ() + 2 + Math.random() * Math.max(1, g.region.maxZ() - g.region.minZ() - 4));
            Location loc = new Location(g.world, p[0] + 0.5, g.region.minY() + 1.2, p[1] + 0.5);
            ItemStack it = new ItemStack(def.icon(), 1);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(BossFx.legacy(def.name()));
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String line : def.lore()) lore.add(BossFx.legacy(line));
                lore.add(BossFx.legacy("§8拾取即生效，不占背包"));
                meta.lore(lore);
                BossPhaseTwoItems.addGlint(meta);
                it.setItemMeta(meta);
            }
            org.bukkit.entity.Item ent = g.world.dropItem(loc, it);
            ent.setMetadata("Tr_xu_challenge", new FixedMetadataValue(TT.plugin, def.kind()));
            ent.setPickupDelay(0);
            ent.setGlowing(true);
            g.challengeItems.add(new ChallengeItem(ent, System.currentTimeMillis(), def.kind()));
            // 挂进二阶段地面掉落列表：清场（失败/通关）时一并收掉
            state.phaseTwo.drops.add(ent);
            try {
                g.world.spawnParticle(Particle.END_ROD, loc, 24, 0.4, 0.4, 0.4, 0.03);
                g.world.playSound(loc, "block.beacon.activate", 0.7f, 1.6f);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.sendMessage("§e[旁白] §f石英层上出现了 §f" + def.name() + "§f！");
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 拾取挑战道具（事件层 {@code EntityPickupItemEvent} 调）：不进背包，直接给效果。 */
    public static boolean onChallengePickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        try {
            org.bukkit.entity.Item ent = event.getItem();
            if (ent == null) return false;
            String kind = metaString(ent, "Tr_xu_challenge");
            if (kind == null) return false;
            if (!(event.getEntity() instanceof Player player)) return false;
            event.setCancelled(true);
            // 只有召唤者本人（且经营仍在进行）能捡：路人捡走等于把本局道具偷走，
            // buffUntil 还会把增益错发给局外人
            BossState st = BossFight.bySummoner(player);
            if (st == null || GAMES.get(st.key) == null) {
                try {
                    player.sendMessage("§7这个道具不属于你，无法拾取。");
                } catch (Throwable ignored) {
                    // 忽略
                }
                return true;
            }
            BossState.removeEntity(ent);
            Game g = GAMES.get(st.key);
            g.challengeItems.removeIf(ci -> ci.entity == ent);
            st.phaseTwo.drops.remove(ent);
            applyChallengeItem(player, st, kind);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 挑战道具效果（原脚本 {@code xuApplyChallengeItem}；同类刷新为 30 秒，不叠加）。 */
    private static void applyChallengeItem(Player player, BossState state, String kind) {
        try {
            long ms = CH_BUFF_SEC * 1000L;
            switch (kind) {
                case "shield" -> {
                    buffUntil(player, "shield", ms);
                    player.sendMessage("§b[守护之盾] §f30 秒内免疫一切伤害！");
                    player.playSound(player.getLocation(), "block.anvil.land", 0.6f, 1.6f);
                }
                case "clear" -> {
                    buffUntil(player, "clear", ms);
                    clearBadEffects(player);
                    player.sendMessage("§b[清明之水] §f30 秒内持续清除负面效果！");
                    player.playSound(player.getLocation(), "item.bottle.fill_dragonbreath", 0.8f, 1.4f);
                }
                case "saturation" -> {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION,
                            CH_BUFF_SEC * 20, 4, false, false, true));
                    player.sendMessage("§6[饱和药水] §f获得 30 秒饱和！");
                }
                case "nectar" -> {
                    player.setHealth(player.getMaxHealth());
                    regenBaseline(player);   // 脚本回血同步基线，不会被「回血压制」压回去
                    player.sendMessage("§d[最终阶生命甘霖] §f生命值已回满！");
                    player.playSound(player.getLocation(), "entity.player.levelup", 0.8f, 1.4f);
                }
                case "aura" -> {
                    // 先清场所有虚仆（算玩家击杀 → 照常给虚币）
                    int killed = 0;
                    if (state != null) {
                        for (Entity s : new ArrayList<>(state.minigame.servants)) {
                            if (alive(s)) {
                                auraKillServant(player, s);
                                killed++;
                            }
                        }
                        state.minigame.servants.clear();
                    }
                    buffUntil(player, "aura", ms);
                    player.sendMessage("§c[杀戮光环] §f清场 " + killed
                            + " 只虚仆，并获得 30 秒秒杀虚仆能力！");
                    player.playSound(player.getLocation(), "entity.wither.spawn", 0.8f, 1.4f);
                }
                default -> {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 持续效果维护（清明之水：生效期间每秒清一遍负面）。 */
    private static void challengeBuffTick(Player player) {
        try {
            if (player == null || !player.isOnline() || player.isDead()) return;
            if (buffActive(player, "clear")) {
                clearBadEffects(player);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 负面效果判定：Paper 1.21.x 的 {@code PotionEffectType} 没有 {@code isBad()}，
     * 按名字兜底（原脚本 {@code XU_BAD_EFFECT_NAMES} 的完整名单，含挖掘疲劳等新老名字）。
     */
    private static final java.util.Set<String> BAD_EFFECTS = java.util.Set.of(
            "SLOWNESS", "SLOW",
            "MINING_FATIGUE", "SLOW_DIGGING",
            "BLINDNESS", "WITHER", "POISON", "WEAKNESS", "HUNGER",
            "NAUSEA", "CONFUSION", "LEVITATION", "UNLUCK", "DARKNESS",
            "INSTANT_DAMAGE", "HARM", "INFESTED", "WEAVING", "OOZING",
            "WIND_CHARGED", "BAD_OMEN");

    /** 清掉全部负面效果（按名字兜底判定）。 */
    private static void clearBadEffects(Player player) {
        try {
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                try {
                    if (BAD_EFFECTS.contains(effect.getType().getName())) {
                        player.removePotionEffect(effect.getType());
                    }
                } catch (Throwable ignored) {
                    // 单个失败不影响其它
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 回血压制（原脚本 9537-9588：把自然回的血量压回去，不是饥饿）
    // ==================================================================

    private static final Map<UUID, long[]> SUPPRESS = new ConcurrentHashMap<>();

    /** 压制自然回血（裂身自爆 / 监守者声波用）。 */
    public static void regenSuppress(Player player, long ms) {
        try {
            if (player == null) return;
            long until = System.currentTimeMillis() + ms;
            long[] rec = SUPPRESS.get(player.getUniqueId());
            if (rec != null && rec[0] > until) {
                rec[0] = until;   // 刷新为新的时长，不叠加
            } else {
                SUPPRESS.put(player.getUniqueId(), new long[]{until, (long) player.getHealth()});
            }
            ensureSuppressTask();
            try {
                player.sendMessage("§c[虚仆] §7你被压制：10 秒内无法自然回血。");
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 脚本强制回血（生命甘霖等）：同步压制基线，避免被误压回去。 */
    public static void regenBaseline(Player player) {
        try {
            if (player == null) return;
            long[] rec = SUPPRESS.get(player.getUniqueId());
            if (rec != null) rec[1] = (long) player.getHealth();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static volatile boolean suppressTaskStarted;

    private static void ensureSuppressTask() {
        if (suppressTaskStarted) return;
        suppressTaskStarted = true;
        Plugin plugin = TT.plugin();
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, long[]> e : SUPPRESS.entrySet()) {
                    Player pl = Bukkit.getPlayer(e.getKey());
                    long[] rec = e.getValue();
                    if (pl == null || !pl.isOnline() || now > rec[0]) {
                        SUPPRESS.remove(e.getKey());
                        continue;
                    }
                    // 自然回血：血量涨了就把超出部分压回去（setHealth 的回血会同步基线）
                    if (pl.getHealth() > rec[1] + 0.01) {
                        pl.setHealth(rec[1]);
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }, 10L, 10L);
    }

    // ==================================================================
    // 破法弓（批次 6c 商店的替代发放；双子护体解除）
    // ==================================================================

    /** 发一把破法弓（原脚本 {@code giveTangranPofabow} 的发放部分；商店属批次 6c）。 */
    public static int givePofabow(Player player) {
        try {
            if (player == null || !player.isOnline()) return 0;
            if (hasPofabow(player)) return 0;
            ItemStack bow = Pofabow.make(player);
            var left = player.getInventory().addItem(bow);
            if (!left.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), bow);
            }
            player.sendMessage("§d[天实] §f破法弓已随开局发放（商店里也可用点数再换一把）。");
            return 1;
        } catch (Throwable t) {
            BossLog.warn("破法弓发放异常: " + t);
            return 0;
        }
    }

    /** 玩家包里有没有破法弓（原脚本 {@code xuPlayerHasPofabow}：剥掉颜色码后比名字）。 */
    public static boolean hasPofabow(Player player) {
        try {
            for (ItemStack it : player.getInventory().getContents()) {
                if (EquipmentBridge.isPofabow(it)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 立即解除双子护体（原脚本 {@code xuUnlockTwinBow}）。
     *
     * <p>主循环每秒也会自检，这里是商店兑换破法弓时的<b>即时入口</b>；
     * 幂等：已解除就只补一次无敌摘除。</p>
     */
    public static void unlockTwinBow(Player player) {
        try {
            BossState st = BossFight.bySummoner(player);
            if (st == null) st = BossFight.byName(player.getName());
            Game g = gameOf(st);
            if (g != null) {
                g.bowUnlocked = true;
            }
            if (st != null) {
                Entity a = slotOf(st).body;
                Entity b = slotOf(st).body2;
                if (a != null && a.isValid() && a.isInvulnerable()) a.setInvulnerable(false);
                if (b != null && b.isValid() && b.isInvulnerable()) b.setInvulnerable(false);
            }
            BossFx.sendLine(player, "§c[糖然]§b§l什么？他们俩的护体……");
            BossFx.sendLine(player, "§c[天虚]§f§l那就来吧，看看你能不能碰到我们。");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 这格方块是双子经营资源吗（原脚本 {@code xuIsResourceBlock}）。
     *
     * <p>批次 8 的防爆/防火规则用它判断「不许被炸掉」的方块。</p>
     */
    public static boolean isResourceBlock(org.bukkit.block.Block block) {
        try {
            if (block == null) return false;
            for (Game g : GAMES.values()) {
                if (g.world != null && !g.world.equals(block.getWorld())) continue;
                int bx = block.getX();
                int by = block.getY();
                int bz = block.getZ();
                int[] ore = g.oreBlock;
                if (ore != null && ore.length >= 3 && ore[0] == bx && ore[1] == by && ore[2] == bz) {
                    return true;
                }
                for (Soil s : g.soils) {
                    if (s.x == bx && s.y == by && s.z == bz) return true;
                }
                for (Tree t : g.trees) {
                    if (t.allKeys != null && t.allKeys.containsKey(bx + "," + by + "," + bz)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 这个实体是场上的挑战道具（掉落物）吗（原脚本 {@code xuIsChallengeItemEntity}）。 */
    public static boolean isChallengeItem(Entity entity) {
        try {
            return entity != null && entity.hasMetadata("Tr_xu_challenge");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 小工具 */

    private static BossState.GuardianSlot slotOf(BossState state) {
        return state.guardians.twin;
    }

    private static Player playerOf(BossState state) {
        return (state.playerName == null) ? null : Bukkit.getPlayerExact(state.playerName);
    }

    private static String metaString(Entity e, String key) {
        try {
            if (!e.hasMetadata(key)) return null;
            var mv = e.getMetadata(key);
            if (mv == null || mv.isEmpty()) return null;
            return mv.get(0).asString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean alive(Entity e) {
        try {
            return e != null && e.isValid() && !e.isDead();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
