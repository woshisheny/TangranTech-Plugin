package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * BOSS战 共用的视觉、判定与伤害小工具。
 *
 * <p>这些函数在原脚本里是<b>平铺的顶层 function</b>，被 zqx125 / Katarina / 双子 / 糖然决斗
 * 等所有阶段共用。原生化后集中到这里，避免每个护法类各抄一份
 * （「抄一份」的代价见本类注释里标了 ★ 的两处）。</p>
 *
 * <h2>两个必须原样保留的细节</h2>
 * <ol>
 *   <li><b>{@link #toBlockInt} 处理「负零」</b>。GraalJS 把 JS 数字传给 Java 的 {@code int}
 *       参数时，<b>负零</b>会被判成「有损转换」直接抛
 *       {@code Cannot convert '-0.0' (java.lang.Double) to Java type 'int'}。
 *       而 {@code Math.round(-0.3)}、坐标加减、{@code Math.max(负数, -0)} 都很容易产出 -0 ——
 *       一旦传进 {@code getBlockAt(x, y, z)} 就抛异常，被外层 {@code try/catch} 吞掉后
 *       <b>整波红石块直接没有</b>。Java 里不会有这个转换问题，但保留同样的
 *       「四舍五入 + 把 -0 归成正零」语义，便于与原脚本逐行对照。</li>
 *   <li><b>箭矢命中判定用「相对位移 + 玩家碰撞箱扫掠」</b>，不是「在胸口放半径 0.8 的球采样」。
 *       原脚本注释记录了为什么改：球心固定在胸口、玩家高速位移时不准，
 *       而且贴脸（箭在同一 tick 内掠过）会<b>漏判</b> ——
 *       表现就是「贴脸举盾格挡，箭被原版弹回却不掉血」。
 *       现在把双方位移都换算成「相对玩家」的位移，再对玩家碰撞箱
 *       （宽 0.6 / 高 1.8，外扩箭矢半宽 0.30）做一次扫掠。</li>
 * </ol>
 */
public final class BossFx {

    private BossFx() {}

    /** 箭矢半宽 0.25 + 余量 0.05（原脚本 {@code PHASE_TWO_ARROW_HIT_PAD}）。 */
    private static final double ARROW_HIT_PAD = 0.30d;

    /** 以「玩家脚底」为原点的判定箱（每次新建：便宜，且避免共享可变状态）。 */
    private static BoundingBox playerHitBox() {
        return new BoundingBox(-0.3d, 0.0d, -0.3d, 0.3d, 1.8d, 0.3d).expand(ARROW_HIT_PAD);
    }

    // ==================================================================
    // 坐标 / 视觉
    // ==================================================================

    /** 四舍五入取整，并把 -0 归成正零。 */
    public static int toBlockInt(double v) {
        long n = Math.round(v);
        return (n == 0L) ? 0 : (int) n;
    }

    /** 单点 DUST 粒子（所有阵法/粒子线的画法）。 */
    public static void dust(World world, double x, double y, double z, Color color, float size) {
        try {
            if (world == null) return;
            world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0d, 0.0d, 0.0d, 0.0d,
                    new Particle.DustOptions(color, size));
        } catch (Throwable ignored) {
            // 粒子失败不影响玩法
        }
    }

    /** 灵魂火苗（护法登场用）。 */
    public static void soulFlame(World world, Location at, int count,
                                 double spread, double speed) {
        try {
            if (world == null || at == null) return;
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                    at.getX(), at.getY() + 0.5d, at.getZ(), count,
                    spread, spread, spread, speed);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 直线逼近目标点。
     *
     * @param pos    「当前位置」，会被<b>原地修改</b>为推进后的位置（照抄原脚本的写法）
     * @param target 目标点
     * @param speed  本帧推进距离（格）
     * @return 是否已抵达
     */
    public static boolean advanceTowards(double[] pos, Location target, double speed) {
        if (pos == null || target == null || pos.length < 3) return false;
        double dx = target.getX() - pos[0];
        double dy = target.getY() - pos[1];
        double dz = target.getZ() - pos[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= speed) {
            pos[0] = target.getX();
            pos[1] = target.getY();
            pos[2] = target.getZ();
            return true;
        }
        pos[0] += dx / dist * speed;
        pos[1] += dy / dist * speed;
        pos[2] += dz / dist * speed;
        return false;
    }

    /** 红色粒子六芒星 + 外圆，{@code angle} 为整体旋转角。 */
    public static void drawSixStarCircle(World world, Location center, double radius, double angle) {
        try {
            if (world == null || center == null) return;
            Color red = Color.RED;
            double baseY = center.getY() + 0.1d;
            double cx = center.getX();
            double cz = center.getZ();

            // 外圆
            int circleSteps = 48;
            for (int i = 0; i < circleSteps; i++) {
                double a = angle + i * Math.PI * 2.0d / circleSteps;
                dust(world, cx + Math.cos(a) * (radius + 0.6d), baseY,
                        cz + Math.sin(a) * (radius + 0.6d), red, 1.3f);
            }

            // 两个交叠的正三角形 = 六芒星（每边插值成粒子线）
            int seg = 8;
            for (int t = 0; t < 2; t++) {
                double offset = t * Math.PI / 3.0d;
                double[] vx = new double[3];
                double[] vz = new double[3];
                for (int v = 0; v < 3; v++) {
                    double ang = angle + offset + v * Math.PI * 2.0d / 3.0d;
                    vx[v] = cx + Math.cos(ang) * radius;
                    vz[v] = cz + Math.sin(ang) * radius;
                }
                for (int e = 0; e < 3; e++) {
                    int nxt = (e + 1) % 3;
                    for (int s = 0; s < seg; s++) {
                        double tt = (double) s / seg;
                        dust(world,
                                vx[e] + (vx[nxt] - vx[e]) * tt, baseY,
                                vz[e] + (vz[nxt] - vz[e]) * tt, red, 1.3f);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 台词 / 伤害
    // ==================================================================

    /**
     * 把一段 {@code §} 文本转成 Adventure {@code Component}。
     *
     * <p>项目里有三处各写了一份同名私有方法，这里收成一处：
     * 1.21 起 {@code ItemMeta#displayName} / {@code #lore} 只接受 {@code Component}，
     * 而内容里的名字/lore 全是 {@code §} 字面量，不转就编译不过。</p>
     */
    public static net.kyori.adventure.text.Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s == null ? "" : s);
    }

    /** {@link #legacy} 的列表版。 */
    public static List<net.kyori.adventure.text.Component> legacyList(List<String> list) {
        if (list == null || list.isEmpty()) return List.of();
        List<net.kyori.adventure.text.Component> out = new ArrayList<>(list.size());
        for (String s : list) out.add(legacy(s));
        return out;
    }

    /** 糖然的一句台词（带交易音效，玩家能听出「是谁在说话」）。 */
    public static void sendLine(Player player, String text) {
        try {
            if (player == null) return;
            player.sendMessage(text);
            player.playSound(player.getLocation(), "entity.villager.trade", 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 二阶段的「直伤」：直接扣血，不走原版伤害事件。
     *
     * <p>为什么不用 {@code player.damage(n)}：原脚本要的是「固定扣 N 滴血」，
     * 而原版伤害会受护甲、抗性、附魔、无敌帧影响，还要额外处理
     * 「掉出场地就判负」那类 listener。</p>
     */
    public static boolean directDamage(Player player, double amount) {
        try {
            if (player == null || !player.isOnline() || player.isDead()) return false;
            double next = player.getHealth() - amount;
            if (next <= 0.0d) {
                player.setHealth(0.0d);
                return true;
            }
            player.setHealth(next);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 受击反馈：抖一下 + 痛呼 + 刷新客户端血条。 */
    public static void hurtFeedback(Player player) {
        try {
            if (player == null) return;
            player.playHurtAnimation(0.0f);
            player.playSound(player.getLocation(), "entity.player.hurt", 1.0f, 1.0f);
            player.sendHealthUpdate();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 场地
    // ==================================================================

    /**
     * 这个位置是不是还在二阶段场地里（含边缘余量）。
     *
     * <p>余量是刻意的：原脚本给四边各留 1~2 格、上方留 2 格，
     * 这样「箭矢刚飞出边缘」「玩家站在边缘」都还算在场内，不会因为 1 格的抖动
     * 把箭/机制判成失效。判定失败（世界未加载等）一律 {@code false}。</p>
     */
    public static boolean inTwoArena(BossState state, Location loc) {
        try {
            BossArena.Region r = (state == null) ? null : state.arena.twoRegion;
            if (r == null || loc == null) return false;
            World w = state.arena.twoWorld;
            if (w != null && !w.getName().equals(loc.getWorld().getName())) return false;
            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            return x >= r.minX() - 1 && x <= r.maxX() + 2
                    && y >= r.minY() - 3 && y <= r.maxY() + 2
                    && z >= r.minZ() - 1 && z <= r.maxZ() + 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 场内随机一点（默认钉在地面上一层；{@code includeAir} 时整片空中随机）。 */
    public static Location randomArenaPoint(BossState state, boolean includeAir) {
        try {
            BossArena.Region r = (state == null) ? null : state.arena.twoRegion;
            World world = (state == null) ? null : state.arena.twoWorld;
            if (r == null || world == null) return null;
            double x = r.minX() + 1.0d + Math.random() * (r.maxX() - r.minX() - 1.0d);
            double z = r.minZ() + 1.0d + Math.random() * (r.maxZ() - r.minZ() - 1.0d);
            double y = r.minY() + 1.0d;
            if (includeAir) {
                y = r.minY() + 1.0d
                        + Math.random() * Math.max(0.5d, r.maxY() - r.minY() - 2.0d);
            }
            return new Location(world, x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 血条
    // ==================================================================

    /**
     * 这个位置能不能看到糖然的血条。
     *
     * <p>有二阶段场地时按<b>区域范围</b>判（区域内全员可见，方便观战）；
     * 没有场地时退化成「离中心 20 格内」。</p>
     */
    public static boolean barVisibleTo(BossState state, Location center, Location loc) {
        try {
            if (center == null || loc == null) return false;
            if (loc.getWorld() == null || center.getWorld() == null) return false;
            if (!loc.getWorld().getName().equals(center.getWorld().getName())) return false;
            BossArena.Region region = (state == null) ? null : state.arena.twoRegion;
            if (region != null) {
                return loc.getBlockX() >= region.minX() && loc.getBlockX() <= region.maxX()
                        && loc.getBlockY() >= region.minY() && loc.getBlockY() <= region.maxY()
                        && loc.getBlockZ() >= region.minZ() && loc.getBlockZ() <= region.maxZ();
            }
            return loc.distanceSquared(center) <= 400.0d;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 按可见性刷新血条上的玩家（糖然主血条 + 护法血条 + 双子血条）。
     *
     * <p><b>差量更新</b>：本方法被多个秒级任务高频调用（血量刷新 / 甘露刷新 / 追踪任务），
     * 原实现每次对「全服在线玩家 × 全部血条」无差别 add/remove——这两个调用每个都是
     * 一次发往客户端的包，随在线人数线性放大。现在先算一次期望可见集，再对每条血条
     * 与其实际玩家列表做差：变化的才发包。以 {@code bar.getPlayers()}（本地列表，
     * 不发包）为基准，外部直接 addPlayer 的玩家也能被正确接管。</p>
     */
    public static void updateBarPlayers(BossState state, Location center) {
        try {
            if (state == null) return;
            java.util.List<org.bukkit.entity.Player> want = new java.util.ArrayList<>();
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p == null) continue;
                try {
                    if (barVisibleTo(state, center, p.getLocation())) want.add(p);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            diffBar(state.bar, want);
            for (BossState.GuardianSlot slot : state.guardians.all()) {
                diffBar(slot.bar, want);
                diffBar(slot.bar2, want);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 把血条上的玩家集差量刷成 {@code want}：只对变化的玩家 add/remove。 */
    private static void diffBar(org.bukkit.boss.BossBar bar, java.util.List<org.bukkit.entity.Player> want) {
        if (bar == null) return;
        try {
            java.util.List<org.bukkit.entity.Player> have = bar.getPlayers();
            if (have != null && !have.isEmpty()) {
                for (org.bukkit.entity.Player p : have) {
                    if (p == null || want.contains(p)) continue;
                    try {
                        bar.removePlayer(p);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
            for (org.bukkit.entity.Player p : want) {
                if (have != null && have.contains(p)) continue;
                try {
                    bar.addPlayer(p);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 糖然二阶段主血条标题。 */
    public static String phaseTwoBarTitle(double hp) {
        int n = (int) Math.max(0, Math.min(BossState.PHASE_TWO_MAX_HP, Math.round(hp)));
        return "§d§l糖然二阶段 §f[§d" + n + "§f/§a"
                + (int) BossState.PHASE_TWO_MAX_HP + "§f]";
    }

    // ==================================================================
    // 箭矢命中判定（★ 见类注释第 2 条）
    // ==================================================================

    /**
     * 箭矢这一 tick 的位移是否扫到玩家。
     *
     * @param from     箭矢上一 tick 位置
     * @param nx/ny/nz 箭矢当前位置
     * @param playerNow 玩家当前位置
     * @param lastPos   玩家上一 tick 位置（没有则退化为「只看箭自己动」）
     */
    public static boolean segmentsHitPlayer(Location from, double nx, double ny, double nz,
                                            Location playerNow, Location lastPos) {
        try {
            if (playerNow == null) return false;
            double px = playerNow.getX();
            double py = playerNow.getY();
            double pz = playerNow.getZ();

            double sx;
            double sy;
            double sz;
            double dx;
            double dy;
            double dz;
            if (lastPos != null) {
                // 双方都在动：都换算成「相对玩家上一 tick 位置」的位移
                sx = from.getX() - lastPos.getX();
                sy = from.getY() - lastPos.getY();
                sz = from.getZ() - lastPos.getZ();
                dx = (nx - from.getX()) - (px - lastPos.getX());
                dy = (ny - from.getY()) - (py - lastPos.getY());
                dz = (nz - from.getZ()) - (pz - lastPos.getZ());
            } else {
                sx = from.getX() - px;
                sy = from.getY() - py;
                sz = from.getZ() - pz;
                dx = nx - from.getX();
                dy = ny - from.getY();
                dz = nz - from.getZ();
            }

            BoundingBox box = playerHitBox();
            if (box.contains(sx, sy, sz)) return true;
            if (box.contains(sx + dx, sy + dy, sz + dz)) return true;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.000001d) return false;
            Vector dir = new Vector(dx / len, dy / len, dz / len);
            return box.rayTrace(new Vector(sx, sy, sz), dir, len) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
