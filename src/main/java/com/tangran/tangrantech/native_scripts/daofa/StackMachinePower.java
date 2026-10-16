package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNet;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineNet;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRegistry;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineStore;

/**
 * 「糖然牌堆叠机」的能量层（原脚本阶段 5 的电力部分 + 阶段 6）。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的 {@code pdEnergyNetAt} / {@code pdEnergyNetAtCached} /
 * {@code pdFillEnergyComponents} / {@code pdPushEnergy} / {@code pdComponent*} /
 * {@code pdNetBuf*} / {@code pdBus*} / {@code pdSelfChargeUp} / {@code pdMachinePowerCost} /
 * {@code pdDrawEnergyFromNet}。</p>
 *
 * <h2>三个必须分清的「电量」概念</h2>
 * <table border="1">
 *   <caption>电量概念</caption>
 *   <tr><th>名字</th><th>存放位置</th><th>用途</th></tr>
 *   <tr><td><b>本机电量</b>（{@code trpddj_energy}）</td><td>本机 BlockData</td>
 *       <td>机器真正「自己的电」。上限 {@link #SELF_CAPACITY} = 1,000,000 J。
 *           写成脚本常量而<b>不是</b> machines.yml 的配置，是因为后续要加「模块强化」。</td></tr>
 *   <tr><td><b>发电机缓存</b>（{@code trpddj_charge}）</td><td>本机 BlockData</td>
 *       <td>发电机发出的、一时送不出去的电（受该发电机 yml 的 capacity 限制）。</td></tr>
 *   <tr><td><b>网络缓冲</b>（{@code trpddj_netbuf}）</td><td>本机 BlockData</td>
 *       <td>「网络侧中转窗口」：把本机挂进粘液能源网络的<b>电容表</b>，
 *           让原版 {@code ENERGY_CONNECTOR} / 调节器传过来的余电先落到这里，
 *           每刻再搬进本机电量。见 {@link #netBufEnsure}。</td></tr>
 * </table>
 *
 * <h2>为什么需要「网络缓冲」这个假组件</h2>
 * <p>用户口径：「不要局限在粘液电容，要支持原版的 {@code ENERGY_CONNECTOR} 传输电量」。</p>
 * <p>javap 实证原版粘液的能源网络（{@code EnergyNet.tick}）流程是：
 * {@code pool = tickAllGenerators() + tickAllCapacitors()}（<b>只读</b>电容电量）
 * → 先满足 consumers（{@code setCharge}）→ 剩下的 {@code storeRemainingEnergy()} 按顺序写回 capacitors。</p>
 * <p>堆叠机不是 {@code EnergyNetComponent} ⇒ 它<b>既不会被充电、也没法把电交给网络</b>
 * （哪怕旁边就是 {@code ENERGY_CONNECTOR}）。做法：给每台堆叠机挂一个假的
 * {@code EnergyNetComponent}（电容），用 {@link Proxy} 生成，反射塞进
 * {@code EnergyNet.capacitors}（{@code getCapacitors()} 返回的是 unmodifiableMap，不能 put，
 * 只能反射拿原表）。</p>
 *
 * <h2>一个必须守住的「不出错」条件</h2>
 * <p>假组件在「坐标的数据容器不存在 / 未加载 / 待删除」时必须<b>报 0 并拒写</b>。
 * 原因是粘液的 {@code storeRemainingEnergy} 会跳过「加载不了」的坐标，
 * 但 {@code tickAllCapacitors} <b>照样</b>会把端口报的电量算进 pool ⇒
 * 同一份电每刻重复计价（<b>凭空变电</b>）。见 {@link #netBufLive}。</p>
 */
public final class StackMachinePower {

    private StackMachinePower() {}

    // ==================================================================
    // 机器 id（与 {@code machines.yml} 的顶层键一致）
    // ==================================================================

    private static final String MACHINE_ID = "TR_TRPDDJ";

    // ==================================================================
    // BlockData 键
    // ==================================================================

    /** 发电机缓存（发出的、暂时送不出去的电）。 */
    public static final String DATA_CHARGE = "trpddj_charge";
    /** 本机电量。 */
    public static final String DATA_ENERGY = "trpddj_energy";
    /** 网络侧中转窗口。 */
    public static final String DATA_NETBUF = "trpddj_netbuf";

    // ==================================================================
    // 可调参数（按原脚本注释：留脚本常量，方便以后用配置界面模块强化）
    // ==================================================================

    /** 本机储能上限（J）。<b>刻意不写进 machines.yml</b> —— 后续要加模块强化。 */
    public static long SELF_CAPACITY = 1_000_000L;
    /** 耗电倍率（模块以后能加）。 */
    public static double SELF_COST_MULT = 1.0;
    /** ≥0 时强制覆盖「每台每粘液刻耗电」（调试 / 模块用；-1 = 按机器自身 yml）。 */
    public static double SELF_COST_OVERRIDE = -1.0;
    /** {@code true} = 电量不足就停机；{@code false} = 只记账不拦（调试用）。 */
    public static boolean SELF_REQUIRE_POWER = true;
    /** 网络缓冲上限（J）：够一拍的余电进来，又不会把网络的电容份额全占了。 */
    public static long NETBUF_CAP = 100_000L;

    // ==================================================================
    // 缓存
    // ==================================================================

    /** 坐标键 → 网络（找到过缓存 100 拍，没找到缓存 20 拍）。 */
    private static final Map<String, EnetCache> ENET_CACHE = new ConcurrentHashMap<>();

    /** 坐标键 → 电力总线邻居表（BFS 不便宜，缓存 100 拍）。 */
    private static final Map<String, Bus> BUS_CACHE = new ConcurrentHashMap<>();

    /** 坐标键 → 网络缓冲的代理实例与其所在的那张电容表。 */
    private static final Map<String, NetBufEntry> NETBUF = new ConcurrentHashMap<>();

    /** 机器 id（大写） → 每台每粘液刻耗电。 */
    private static final Map<String, Long> COST_CACHE = new ConcurrentHashMap<>();

    /** 网络缓存条目。 */
    private static final class EnetCache {
        Object net;
        int at;
        /** 是否已算过（区分「缓存了一个 null」与「没缓存」）。 */
        boolean filled;
    }

    /** 电力总线邻居表。 */
    public static final class Bus {
        /** 全部相邻堆叠机。 */
        public final List<Location> list = new ArrayList<>();
        /** 不是发电机的（优先收电）。 */
        public final List<Location> noGen = new ArrayList<>();
        /** 发电机模式的（优先借电）。 */
        public final List<Location> gens = new ArrayList<>();
        int at;
    }

    /** 网络缓冲条目。 */
    private static final class NetBufEntry {
        EnergyNetComponent proxy;
        Map<Location, EnergyNetComponent> map;
    }

    /** 供调试 / 卸载使用。 */
    public static void shutdown() {
        for (String key : new ArrayList<>(NETBUF.keySet())) {
            NetBufEntry e = NETBUF.get(key);
            if (e != null && e.map != null) {
                Location loc = StackMachineNet.parseLoc(keyToLoc(key));
                if (loc != null) {
                    try {
                        e.map.remove(loc);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        }
        NETBUF.clear();
        ENET_CACHE.clear();
        BUS_CACHE.clear();
        COST_CACHE.clear();
    }

    /** 坐标键（{@code world|X|Y|Z}）→ 逗号形式，供解析回 Location。 */
    private static String keyToLoc(String key) {
        try {
            return key == null ? "" : key.replace('|', ',');
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================================================================
    // 本机储能
    // ==================================================================

    /** 本机储能上限。 */
    public static long selfCapacity() {
        return Math.max(0L, SELF_CAPACITY);
    }

    /** 本机电量（J）。 */
    public static long selfEnergyOf(Location loc) {
        try {
            String v = StackMachineStore.localGet(loc, DATA_ENERGY, "0");
            long n = Long.parseLong(v == null ? "0" : v.trim());
            return Math.max(0L, n);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 写本机电量（自动按上限截断）。 */
    public static void setSelfEnergy(Location loc, long n) {
        try {
            long cap = selfCapacity();
            long v = Math.max(0L, n);
            if (cap > 0L && v > cap) v = cap;
            StackMachineStore.localSet(loc, DATA_ENERGY, String.valueOf(v));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 发电机缓存（J）。 */
    public static long localChargeOf(Location loc) {
        try {
            String v = StackMachineStore.localGet(loc, DATA_CHARGE, "0");
            long n = Long.parseLong(v == null ? "0" : v.trim());
            return Math.max(0L, n);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 写发电机缓存。 */
    public static void setLocalCharge(Location loc, long n) {
        try {
            StackMachineStore.localSet(loc, DATA_CHARGE,
                    String.valueOf(Math.max(0L, n)));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 每台每粘液刻耗电
    // ==================================================================

    /**
     * 「每台每粘液刻耗电」。
     *
     * <p>先看覆盖值，再从机器物品反射常见字段名 —— 不同附属给「耗电」起了不同名字
     * （能量工艺 {@code energyPerCraft}、模板机 {@code consumption}…）。
     * <b>读不到就返回 0</b>：那台机器本来就不耗电，不会平白无故扣电。</p>
     */
    public static long machinePowerCost(String machineId) {
        try {
            if (machineId == null) return 0L;
            if (SELF_COST_OVERRIDE >= 0.0) {
                return (long) Math.max(0L, Math.floor(SELF_COST_OVERRIDE));
            }
            String ck = machineId.toUpperCase(Locale.ROOT);
            Long cached = COST_CACHE.get(ck);
            if (cached != null) return cached;

            long cost = 0L;
            SlimefunItem item = TT.sfItem(machineId);
            if (item != null) {
                for (String name : new String[]{"energyPerCraft", "consumption", "energyConsumption",
                        "perTickCost", "powerConsumption", "energyCost", "cost"}) {
                    Object v = fieldOf(item, name);
                    if (!(v instanceof Number n)) continue;
                    if (n.doubleValue() > 0.0) {
                        cost = n.longValue();
                        break;
                    }
                }
            }
            cost = (long) Math.floor(Math.max(0L, cost) * Math.max(0.0, SELF_COST_MULT));
            COST_CACHE.put(ck, cost);
            return cost;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ==================================================================
    // Slimefun 能源网络
    // ==================================================================

    /**
     * 本机连着的能源网络：<b>先问自己，再 6 邻域 BFS</b>。
     *
     * <p>网络是正交连接的，最多铺 {@link StackMachineNet#BFS_LIMIT} 个方块位。
     * 注意这里 BFS 的邻居<b>不</b>限定是堆叠机 —— 与
     * {@link StackMachineNet#networkRoot}（物品网络）的规则不同，别混。</p>
     */
    public static EnergyNet energyNetAt(Location loc) {
        try {
            if (loc == null) return null;
            EnergyNet net = null;
            try {
                net = EnergyNet.getNetworkFromLocation(loc);
            } catch (Throwable ignored) {
                net = null;
            }
            if (net != null) return net;

            World w = loc.getWorld();
            if (w == null) return null;
            Location start = new Location(w, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Deque<Location> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(start);
            seen.add(StackMachineNet.fmtLoc(start));
            int head = 0;
            List<Location> snapshot = new ArrayList<>();
            snapshot.add(start);
            while (head < snapshot.size() && head < StackMachineNet.BFS_LIMIT) {
                Location cur = snapshot.get(head++);
                for (int[] d : FACES) {
                    Location nb = new Location(cur.getWorld(), cur.getBlockX() + d[0],
                            cur.getBlockY() + d[1], cur.getBlockZ() + d[2]);
                    String kk = StackMachineNet.fmtLoc(nb);
                    if (seen.contains(kk)) continue;
                    seen.add(kk);
                    EnergyNet n2 = null;
                    try {
                        n2 = EnergyNet.getNetworkFromLocation(nb);
                    } catch (Throwable ignored) {
                        n2 = null;
                    }
                    if (n2 != null) return n2;
                    if (snapshot.size() < StackMachineNet.BFS_LIMIT) snapshot.add(nb);
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 查找能源网络失败: " + t);
        }
        return null;
    }

    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** 带缓存的网络查找：找到过缓存 100 拍；没找到缓存 20 拍（玩家可能刚接上电缆）。 */
    public static EnergyNet energyNetAtCached(Location loc) {
        try {
            String k = StackMachineStore.locKey(loc);
            if (k == null) return null;
            int now = nowTick();
            EnetCache c = ENET_CACHE.get(k);
            if (c != null && c.filled) {
                int ttl = (c.net != null) ? 100 : 20;
                if ((now - c.at) < ttl) return (EnergyNet) c.net;
            }
            EnergyNet net = energyNetAt(loc);
            EnetCache fresh = new EnetCache();
            fresh.net = net;
            fresh.at = now;
            fresh.filled = true;
            ENET_CACHE.put(k, fresh);
            return net;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 当前服务器 tick（拿不到就用自增计数器，仅用于 TTL 比较）。 */
    public static int nowTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable ignored) {
            return ++TICK_FALLBACK;
        }
    }

    private static int TICK_FALLBACK = 0;

    // ==================================================================
    // 能源组件的电量读写
    // ==================================================================

    /** 组件当前电量（先 long 版、再 int 版）。 */
    public static long componentCharge(Location cloc, EnergyNetComponent comp) {
        try {
            if (comp == null || cloc == null) return 0L;
            try {
                return Math.max(0L, comp.getChargeLong(cloc));
            } catch (Throwable ignored) {
                // 退 int 版
            }
            return Math.max(0L, comp.getCharge(cloc));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 从组件里扣电。
     *
     * <p>返回<b>实际扣到</b>的量：扣完再读一次核对 —— 避免「方法调了但没扣掉」时
     * 调用方以为电已经拿到（那会导致凭空发电）。读不回来（有些实现把电量存在别处）
     * 时按请求量算。</p>
     */
    public static long componentTake(Location cloc, EnergyNetComponent comp, long n) {
        try {
            long want = Math.max(0L, n);
            if (comp == null || cloc == null || want <= 0L) return 0L;
            long before = componentCharge(cloc, comp);
            if (before <= 0L) return 0L;
            if (want > before) want = before;
            boolean ok = false;
            try {
                comp.removeCharge(cloc, want);
                ok = true;
            } catch (Throwable ignored) {
                // 退 int 版
            }
            if (!ok) {
                try {
                    comp.removeCharge(cloc, (int) want);
                    ok = true;
                } catch (Throwable ignored) {
                    // 无计可施
                }
            }
            if (!ok) return 0L;
            long after = componentCharge(cloc, comp);
            if (after < before) return before - after;
            return want;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 往组件里充电。返回<b>实际充进去</b>的量。 */
    public static long componentGive(Location cloc, EnergyNetComponent comp, long n) {
        try {
            long want = Math.max(0L, n);
            if (comp == null || cloc == null || want <= 0L) return 0L;
            long before = componentCharge(cloc, comp);
            boolean ok = false;
            try {
                comp.addCharge(cloc, want);
                ok = true;
            } catch (Throwable ignored) {
                // 退 int 版
            }
            if (!ok) {
                try {
                    comp.addCharge(cloc, (int) want);
                    ok = true;
                } catch (Throwable ignored) {
                    // 无计可施
                }
            }
            if (!ok) return 0L;
            long after = componentCharge(cloc, comp);
            if (after > before) return after - before;
            return want;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 往网络里塞电：<b>先把电容（储能）充满，再给消费者充电</b>。
     *
     * <p>储能优先，是因为网络自己的 tick 会把电容里的电再分给消费者；
     * 直接塞给消费者会让「有电池的服务器」电全部泄走、没有缓冲。</p>
     *
     * @return 没塞进去的余量
     */
    public static long pushEnergy(Location loc, EnergyNet net, long amount) {
        long left = Math.max(0L, amount);
        try {
            if (net == null || left <= 0L) return left;
            Map<Location, EnergyNetComponent> caps = null;
            Map<Location, EnergyNetComponent> cons = null;
            try {
                caps = net.getCapacitors();
            } catch (Throwable ignored) {
                caps = null;
            }
            try {
                cons = net.getConsumers();
            } catch (Throwable ignored) {
                cons = null;
            }
            left = fillEnergyComponents(caps, left);
            if (left > 0L) left = fillEnergyComponents(cons, left);
        } catch (Throwable t) {
            TT.log("[堆叠机] 网络充电失败: " + t);
        }
        return left;
    }

    /** 给一张「坐标 → 组件」表充电，返回没充进去的余量。 */
    public static long fillEnergyComponents(Map<Location, EnergyNetComponent> map, long left) {
        try {
            if (map == null || left <= 0L) return left;
            for (Map.Entry<Location, EnergyNetComponent> e : map.entrySet()) {
                if (left <= 0L) break;
                Location cloc = e.getKey();
                EnergyNetComponent comp = e.getValue();
                if (cloc == null || comp == null) continue;
                long cap = 0L;
                try {
                    cap = comp.getCapacityLong();
                } catch (Throwable ignored) {
                    cap = 0L;
                }
                if (cap <= 0L) {
                    try {
                        cap = comp.getCapacity();
                    } catch (Throwable ignored) {
                        cap = 0L;
                    }
                }
                if (cap <= 0L) continue;
                long cur = componentCharge(cloc, comp);
                long room = cap - cur;
                if (room <= 0L) continue;
                long add = Math.min(room, left);
                left -= componentGive(cloc, comp, add);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return left;
    }

    /**
     * 从网络的<b>电容</b>里抽电。
     *
     * <p>储能是电池；<b>不去抽别的消费者</b> —— 那等于从别人的机器嘴里抢电。</p>
     */
    public static long drawEnergyFromNet(Location loc, long need) {
        long got = 0L;
        try {
            long want = Math.max(0L, need);
            if (want <= 0L) return 0L;
            EnergyNet net = energyNetAtCached(loc);
            if (net == null) return 0L;
            Map<Location, EnergyNetComponent> caps = null;
            try {
                caps = net.getCapacitors();
            } catch (Throwable ignored) {
                caps = null;
            }
            if (caps == null) return 0L;
            String selfKey = StackMachineStore.locKey(loc);
            for (Map.Entry<Location, EnergyNetComponent> e : caps.entrySet()) {
                if (got >= want) break;
                Location cloc = e.getKey();
                EnergyNetComponent comp = e.getValue();
                if (cloc == null || comp == null) continue;
                // 跳过本机自己挂的「网络电力接口」：它是中转站，已经在 netBufDrain 里搬过了
                if (selfKey != null && selfKey.equals(StackMachineStore.locKey(cloc))) continue;
                got += componentTake(cloc, comp, want - got);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 从网络抽电失败: " + t);
        }
        return got;
    }

    // ==================================================================
    // 网络缓冲（挂在能源网络电容表里的假组件）
    // ==================================================================

    /** 缓冲上限（同时受本机储能上限约束）。 */
    public static long netBufCap() {
        try {
            return Math.max(1L, Math.min(Math.max(1L, SELF_CAPACITY), Math.max(1L, NETBUF_CAP)));
        } catch (Throwable ignored) {
            return 100_000L;
        }
    }

    /** 缓冲当前值。 */
    public static long netBufOf(Location loc) {
        try {
            String v = StackMachineStore.localGet(loc, DATA_NETBUF, "0");
            long n = Long.parseLong(v == null ? "0" : v.trim());
            return Math.max(0L, n);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 写缓冲（按上限截断）。 */
    public static void setNetBuf(Location loc, long n) {
        try {
            long cap = netBufCap();
            long v = Math.max(0L, n);
            if (v > cap) v = cap;
            StackMachineStore.localSet(loc, DATA_NETBUF, String.valueOf(v));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 端口是否「活着」：坐标的数据容器存在 + 已加载 + 没被标成待删除。
     *
     * <p><b>不活着时必须报 0 / 拒写</b>：粘液的 {@code storeRemainingEnergy} 会跳过
     * 「加载不了」的坐标，但 {@code tickAllCapacitors} 照样会把端口报的电量算进 pool
     * ⇒ 同一份电每刻重复计价（凭空变电）。</p>
     */
    /** 方法缓存（性能优化：原先每 tick 每台机器两次 getMethod 重查）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Method> DC_METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean netBufLive(Location loc) {
        try {
            if (loc == null) return false;
            Object dc = com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                    .getDataContainer(loc);
            if (dc == null) return false;
            return !dcFlagFalse(dc, "isDataLoaded") && !dcFlagTrue(dc, "isPendingRemove");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 调布尔方法：返回 TRUE=true；返回其它/方法不存在=false？——用三态区分。 */
    private static boolean dcFlagFalse(Object dc, String name) {
        try {
            String key = dc.getClass().getName() + "#" + name;
            Method m = DC_METHOD_CACHE.get(key);
            if (m == null) {
                try {
                    m = dc.getClass().getMethod(name);
                    DC_METHOD_CACHE.put(key, m);
                } catch (Throwable notFound) {
                    DC_METHOD_CACHE.put(key, MISSING_METHOD);
                    return false;   // 方法不存在：视同不拦截
                }
            }
            if (m == MISSING_METHOD) return false;
            return Boolean.FALSE.equals(m.invoke(dc));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean dcFlagTrue(Object dc, String name) {
        try {
            String key = dc.getClass().getName() + "#" + name;
            Method m = DC_METHOD_CACHE.get(key);
            if (m == null) {
                try {
                    m = dc.getClass().getMethod(name);
                    DC_METHOD_CACHE.put(key, m);
                } catch (Throwable notFound) {
                    DC_METHOD_CACHE.put(key, MISSING_METHOD);
                    return false;
                }
            }
            if (m == MISSING_METHOD) return false;
            return Boolean.TRUE.equals(m.invoke(dc));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 方法缺失的哨兵（区分「查过没有」与「还没查」）。 */
    private static final Method MISSING_METHOD;
    static {
        Method m = null;
        try {
            m = Object.class.getDeclaredMethod("hashCode");
        } catch (Throwable ignored) {
            // 不会发生
        }
        MISSING_METHOD = m;
    }

    /** 假组件的调用处理器（只建一次；实例每台机器一个，与原脚本一致）。 */
    private static InvocationHandler netBufHandler;
    private static boolean netBufHandlerReady = false;

    /**
     * 造一个假组件实例（电量读写全走 {@code trpddj_netbuf}）。
     *
     * <p>原脚本用 GraalJS 的 {@code Java.extend(EnergyNetComponent, impl)} 生成类，
     * 再 {@code new cls()} <b>每台机器一个实例</b>。Java 里等价物是 {@link Proxy}：
     * 处理器只建一次（无状态，全靠 {@code args[0]} 的 Location 区分机器），
     * 实例仍<b>每台机器一个</b> —— 保持与原脚本完全一致的语义，
     * 免得哪天 Slimefun 改用对象身份做 key。</p>
     */
    private static EnergyNetComponent newNetBufProxy() {
        InvocationHandler handler = netBufHandlerOf();
        if (handler == null) return null;
        try {
            return (EnergyNetComponent) Proxy.newProxyInstance(
                    StackMachinePower.class.getClassLoader(),
                    new Class<?>[]{EnergyNetComponent.class}, handler);
        } catch (Throwable t) {
            TT.log("[堆叠机] 创建网络缓冲代理失败: " + t);
            return null;
        }
    }

    /** 取（惰性构造）代理处理器。 */
    private static InvocationHandler netBufHandlerOf() {
        if (netBufHandlerReady) return netBufHandler;
        synchronized (StackMachinePower.class) {
            if (netBufHandlerReady) return netBufHandler;
            try {
                netBufHandler = (proxy, method, args) -> {
                    String name = method.getName();
                    try {
                        switch (name) {
                            case "getEnergyComponentType":
                                return EnergyNetComponentType.CAPACITOR;
                            case "getCapacity":
                                return (int) Math.min(Integer.MAX_VALUE, netBufCap());
                            case "getCapacityLong":
                                return netBufCap();
                            case "isChargeable":
                                return Boolean.TRUE;
                            case "getCharge":
                                return (int) locationChargeOf(args);
                            case "getChargeLong":
                                return locationChargeOf(args);
                            case "setCharge": {
                                Location l = locArg(args);
                                if (l != null && netBufLive(l)) setNetBuf(l, numArg(args));
                                return null;
                            }
                            case "addCharge": {
                                Location l = locArg(args);
                                if (l != null && netBufLive(l)) {
                                    setNetBuf(l, netBufOf(l) + numArg(args));
                                }
                                return null;
                            }
                            case "removeCharge": {
                                Location l = locArg(args);
                                if (l != null && netBufLive(l)) {
                                    setNetBuf(l, netBufOf(l) - numArg(args));
                                }
                                return null;
                            }
                            case "toString":
                                return "TangranStackMachineNetBuf";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == (args == null || args.length == 0 ? null : args[0]);
                            default:
                                return null;
                        }
                    } catch (Throwable t) {
                        TT.log("[堆叠机] 网络缓冲代理调用失败(" + name + "): " + t);
                        return defaultValue(method.getReturnType());
                    }
                };
            } catch (Throwable t) {
                TT.log("[堆叠机] 生成网络缓冲代理处理器失败: " + t);
                netBufHandler = null;
            }
            netBufHandlerReady = true;
            return netBufHandler;
        }
    }

    /** 代理的「读电量」：端口不活着就报 0。 */
    private static long locationChargeOf(Object[] args) {
        Location l = locArg(args);
        if (l == null) return 0L;
        return netBufLive(l) ? netBufOf(l) : 0L;
    }

    private static Location locArg(Object[] args) {
        if (args == null || args.length == 0) return null;
        return (args[0] instanceof Location l) ? l : null;
    }

    private static long numArg(Object[] args) {
        if (args == null || args.length < 2) return 0L;
        return (args[1] instanceof Number n) ? n.longValue() : 0L;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == char.class) return (char) 0;
        return null;
    }

    /**
     * 反射拿 {@code EnergyNet} 的私有 {@code capacitors} 原表。
     *
     * <p>{@code getCapacitors()} 返回的是 unmodifiableMap，<b>不能 put</b>，
     * 所以只能反射拿原表往里面塞。</p>
     *
     * <p>性能优化：Field 按类缓存（原先每 tick 每台机器都 {@code getDeclaredField +
     * setAccessible} 重查一遍）。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> CAP_FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static Map<Location, EnergyNetComponent> netCapMap(EnergyNet net) {
        try {
            if (net == null) return null;
            Class<?> cls = net.getClass();
            for (int d = 0; d < 6 && cls != null && cls != Object.class; d++) {
                String key = cls.getName() + "#capacitors";
                Field f = CAP_FIELD_CACHE.get(key);
                if (f == null) {
                    try {
                        f = cls.getDeclaredField("capacitors");
                        f.setAccessible(true);
                        CAP_FIELD_CACHE.put(key, f);
                    } catch (Throwable ignored) {
                        cls = cls.getSuperclass();
                        continue;
                    }
                }
                Object m = f.get(net);
                if (m instanceof Map<?, ?> map) {
                    return (Map<Location, EnergyNetComponent>) map;
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 把本机作为「电容」挂到网络里（幂等；网络重建 / 被清掉时会重新挂）。
     *
     * <p>必须在「有数据容器且已加载」的坐标挂（见 {@link #netBufLive}：
     * 不然会凭空变电）。</p>
     */
    public static EnergyNetComponent netBufEnsure(Location loc, EnergyNet net) {
        try {
            if (loc == null || net == null) return null;
            String key = StackMachineStore.locKey(loc);
            if (key == null) return null;
            if (!netBufLive(loc)) return null;
            Map<Location, EnergyNetComponent> map = netCapMap(net);
            if (map == null) return null;

            NetBufEntry ent = NETBUF.get(key);
            if (ent != null && ent.proxy != null && ent.map == map) {
                try {
                    if (map.get(loc) == ent.proxy) return ent.proxy;
                } catch (Throwable ignored) {
                    // 表变了 → 重新挂
                }
            }
            EnergyNetComponent proxy = (ent != null && ent.proxy != null)
                    ? ent.proxy : newNetBufProxy();
            if (proxy == null) return null;
            try {
                map.put(loc, proxy);
            } catch (Throwable t) {
                return null;
            }
            NetBufEntry fresh = new NetBufEntry();
            fresh.proxy = proxy;
            fresh.map = map;
            NETBUF.put(key, fresh);
            return proxy;
        } catch (Throwable t) {
            TT.log("[堆叠机] 挂载网络接口失败: " + t);
            return null;
        }
    }

    /** 拆机器 / 换机器：把挂上去的「电容」摘掉（不然网络里会留个指向空坐标的残留）。 */
    public static void netBufDrop(Location loc) {
        try {
            String key = StackMachineStore.locKey(loc);
            NetBufEntry ent = (key != null) ? NETBUF.get(key) : null;
            if (ent != null) {
                try {
                    if (ent.map != null && loc != null) ent.map.remove(loc);
                } catch (Throwable ignored) {
                    // 忽略
                }
                NETBUF.remove(key);
            }
            setNetBuf(loc, 0L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 网络侧缓冲 → 本机私有缓存（每粘液刻都搬，最多搬到本机储能上限）。 */
    public static long netBufDrain(Location loc) {
        long moved = 0L;
        try {
            long buf = netBufOf(loc);
            if (buf <= 0L) return 0L;
            long cap = selfCapacity();
            long cur = selfEnergyOf(loc);
            long room = cap - cur;
            if (room <= 0L) return 0L;
            moved = Math.min(room, buf);
            setSelfEnergy(loc, cur + moved);
            setNetBuf(loc, buf - moved);
        } catch (Throwable ignored) {
            // 忽略
        }
        return moved;
    }

    // ==================================================================
    // 堆叠机之间的「电力总线」
    // ==================================================================

    /**
     * 相邻堆叠机邻居表（缓存 100 拍）。
     *
     * <p>用户实测场景：发电机模式的堆叠机<b>直接贴着</b>耗电的堆叠机、中间没有粘液电容
     * ⇒ 能源网络里没有「电容」可抽，电只能留在发电机自己的缓存里
     * ⇒ 耗电那台永远 0（「堆叠机器存不进去电」）。
     * 所以让相邻（可连成串）的堆叠机直接互相搬电。</p>
     */
    public static Bus busParts(Location loc, int limit, boolean force) {
        Bus parts = new Bus();
        try {
            if (loc == null) return parts;
            String ck = StackMachineStore.locKey(loc);
            int now = nowTick();
            if (!force && ck != null) {
                Bus cached = BUS_CACHE.get(ck);
                if (cached != null && (now - cached.at) < 100) return cached;
            }
            int lim = Math.max(1, limit);
            List<Location> out = new ArrayList<>();
            Deque<Location> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(loc);
            if (ck != null) seen.add(ck);
            int steps = 0;
            while (!queue.isEmpty() && steps < lim) {
                Location cur = queue.poll();
                steps++;
                World w = cur.getWorld();
                if (w == null) continue;
                for (int[] f : FACES) {
                    Location nl = new Location(w, cur.getBlockX() + f[0],
                            cur.getBlockY() + f[1], cur.getBlockZ() + f[2]);
                    String k = StackMachineStore.locKey(nl);
                    if (k == null || seen.contains(k)) continue;
                    // 只跟「也是堆叠机」的方块互通，并且穿过去继续找（多台串起来也算一条总线）
                    boolean ours;
                    try {
                        ours = me.mrCookieSlime.Slimefun.api.BlockStorage.check(nl, MACHINE_ID);
                    } catch (Throwable t) {
                        ours = false;
                    }
                    if (!ours) continue;
                    seen.add(k);
                    out.add(nl);
                    queue.add(nl);
                }
            }
            // 分两类：发电机之间互相塞电只会来回弹（电卡在发电机中间）
            // ⇒ 送电优先给「不是发电机」的邻居
            for (Location nl : out) {
                String t = StackMachineStore.getType(nl);
                if (StackMachineRegistry.TYPE_GEN.equals(t)) {
                    parts.gens.add(nl);
                } else {
                    parts.noGen.add(nl);
                }
            }
            parts.list.addAll(out);
            parts.at = now;
            if (ck != null) BUS_CACHE.put(ck, parts);
        } catch (Throwable ignored) {
            // 忽略
        }
        return parts;
    }

    /**
     * 把电塞给旁边的堆叠机。
     *
     * @return 没塞进去的余量
     */
    public static long busPush(Location loc, long amount) {
        long left = Math.max(0L, amount);
        try {
            if (left <= 0L) return left;
            long cap = selfCapacity();
            Bus parts = busParts(loc, StackMachineNet.BFS_LIMIT, false);
            List<Location> order = new ArrayList<>(parts.noGen);
            order.addAll(parts.gens);
            for (Location nloc : order) {
                if (left <= 0L) break;
                long cur = selfEnergyOf(nloc);
                long room = cap - cur;
                if (room <= 0L) continue;
                long add = Math.min(room, left);
                setSelfEnergy(nloc, cur + add);
                left -= add;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return left;
    }

    /** 从旁边的堆叠机缓存里「借电」（先借发电机那台 —— 它暂时送不出去）。返回借到的量。 */
    public static long busPull(Location loc, long need) {
        long got = 0L;
        try {
            long want = Math.max(0L, need);
            if (want <= 0L) return 0L;
            Bus parts = busParts(loc, StackMachineNet.BFS_LIMIT, false);
            List<Location> order = new ArrayList<>(parts.gens);
            order.addAll(parts.noGen);
            for (Location nloc : order) {
                if (got >= want) break;
                long cur = selfEnergyOf(nloc);
                if (cur <= 0L) continue;
                long take = Math.min(cur, want - got);
                // 写回邻居（同一个写通缓存，两边立刻看得到）
                setSelfEnergy(nloc, cur - take);
                got += take;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return got;
    }

    /**
     * 每粘液刻「吸电」。
     *
     * <ol>
     *   <li>把本机挂进能源网络当「电容接口」（原版 {@code ENERGY_CONNECTOR} / 调节器
     *       传过来的余电会写进 {@code trpddj_netbuf}）；</li>
     *   <li>缓冲 → 私有缓存；</li>
     *   <li>网络电容里还有电就直接抽（跳过自己那个接口，它是中转站）；</li>
     *   <li>都没有再从旁边的堆叠机缓存里借（电力总线）。</li>
     * </ol>
     *
     * <p><b>必须「不管这拍有没有活干」都调</b>：原脚本曾把它放在「有配方能跑」之后，
     * 结果没配方能跑（等材料 / 等模板）时永远不吸电，本机电量看着永远是 0
     * （用户口径：「堆叠机器现在存不进去电」）。</p>
     *
     * @return 吸完后的本机电量
     */
    public static long selfChargeUp(Location loc) {
        try {
            long cap = selfCapacity();
            EnergyNet net = null;
            try {
                net = energyNetAtCached(loc);
            } catch (Throwable ignored) {
                net = null;
            }
            if (net != null) {
                try {
                    netBufEnsure(loc, net);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            try {
                netBufDrain(loc);
            } catch (Throwable ignored) {
                // 忽略
            }
            long cur = selfEnergyOf(loc);
            if (cap <= 0L || cur >= cap) return cur;
            long want = cap - cur;
            long got = drawEnergyFromNet(loc, want);
            if (got < want) {
                try {
                    got += busPull(loc, want - got);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (got > 0L) {
                cur = Math.min(cap, cur + got);
                setSelfEnergy(loc, cur);
            }
            return cur;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ==================================================================
    // 反射读私有字段（电力 / 发电机规格都要用）
    // ==================================================================

    /** 逐层 {@code getDeclaredField} + {@code setAccessible}；读不到返回 null（最多 8 层）。 */
    public static Object fieldOf(Object obj, String name) {
        try {
            if (obj == null || name == null) return null;
            Class<?> c = obj.getClass();
            for (int d = 0; d < 8 && c != null && c != Object.class; d++) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Throwable ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }
}
