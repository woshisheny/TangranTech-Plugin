package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineStore;

/**
 * 「糖然牌堆叠机」的网络层（阶段 4 的产出归属）。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的 {@code pdNetworkUtil} / {@code pdNetworkRoot} /
 * {@code pdFmtLoc} / {@code pdParseLoc} / {@code pdNetAmount} / {@code pdPushNet}。</p>
 *
 * <h2>规则（沿用「堆叠自动赛跑机」的实战经验）</h2>
 * <ol>
 *   <li><b>只有网络里「已经有」这种产物时，产物才进网络。</b>
 *       判定是「拿 1 个去问 {@code root.getAmount(probe)}」，大于 0 才算有 ——
 *       这样不会把网络变成「什么都往里塞」的垃圾桶。</li>
 *   <li><b>整块交给网络，不做分轮塞。</b> javap 实证
 *       {@code NetworkRoot.addItemStack0(loc, stack)} 会遍历网络里所有存储、
 *       把传进去的这个 {@code ItemStack} 一点点收下（收不完的留在那个 stack 里），
 *       所以一次调用就能收下「网络还装得下」的全部数量。
 *       <br><b>这是原脚本修过的一个真实缺陷</b>：以前写成 {@code for (round < 8) chunk = min(64, …)}
 *       ⇒ 一次周期最多只能进网 512 个（玩家实测：叠 97695 台自动赛跑机 → 并行 195390，
 *       结果一次只进网 512）。</li>
 *   <li><b>数量必须交叉校验，宁可少报。</b> 收下的量优先用网络自己
 *       {@code getAmount} 的前后差；再用「传进去那叠的剩余量」对一遍，
 *       两边都有数时取<b>较小</b>的 —— 剩下的会去输出槽，
 *       <b>绝不凭空消失</b>。</li>
 * </ol>
 *
 * <p>NetworksExpansion 编译期没有 jar，全部走反射；失败一次就记住不可用，
 * 不装该插件时「网络」这条路径整体退化为「全部进输出槽」，机器照常工作。</p>
 */
public final class StackMachineNet {

    private StackMachineNet() {}

    /** 本机注册 id（与 {@code machines.yml} 的顶层键一致）。 */
    private static final String MACHINE_ID = "TR_TRPDDJ";

    /** 找网络时的 BFS 步数上限（原脚本 {@code PD_NET_BFS_LIMIT}）。 */
    public static final int BFS_LIMIT = 64;

    /** 缓存的锚点坐标串最短长度（比这短就一定是脏数据）。 */
    private static final int MIN_ANCHOR_LEN = 3;

    // ------------------------------------------------------------------
    // 反射缓存
    // ------------------------------------------------------------------

    private static volatile boolean utilProbed = false;
    private static Class<?> networkUtilClass;
    private static Method findRootMethod;

    private static volatile boolean rootProbed = false;
    private static Method rootGetAmountMethod;
    private static Method rootAddItemMethod;

    /** 供调试 / 卸载使用。 */
    public static void shutdown() {
        utilProbed = false;
        networkUtilClass = null;
        findRootMethod = null;
        rootProbed = false;
        rootGetAmountMethod = null;
        rootAddItemMethod = null;
    }

    /** 定位并缓存 {@code NetworkUtil#findRoot}；不可用返回 null。 */
    private static Method findRoot() {
        if (!utilProbed) {
            synchronized (StackMachineNet.class) {
                if (!utilProbed) {
                    try {
                        networkUtilClass = Class.forName(
                                "com.ytdd9527.networksexpansion.implementation.machines.cellnet.support.NetworkUtil");
                        findRootMethod = singleArgMethod(networkUtilClass, "findRoot",
                                org.bukkit.Location.class);
                    } catch (Throwable ignored) {
                        networkUtilClass = null;
                        findRootMethod = null;
                    }
                    utilProbed = true;
                }
            }
        }
        return findRootMethod;
    }

    /** 定位并缓存 {@code NetworkRoot} 上的取量 / 投物方法。 */
    private static void probeRoot() {
        if (rootProbed) return;
        synchronized (StackMachineNet.class) {
            if (rootProbed) return;
            rootProbed = true;
            // NetworkRoot 的实例类在拿到第一个 root 时才知道 —— 这里先记「没探到」，
            // 由 ensureRootMethods(root) 按第一个真实实例补探
        }
    }

    /** 按第一个拿到的 root 实例补探方法（幂等）。 */
    private static void ensureRootMethods(Object root) {
        if (rootGetAmountMethod != null && rootAddItemMethod != null) return;
        probeRoot();
        synchronized (StackMachineNet.class) {
            if (root == null) return;
            if (rootGetAmountMethod == null) {
                rootGetAmountMethod = singleArgMethod(root.getClass(), "getAmount", ItemStack.class);
            }
            if (rootAddItemMethod == null) {
                rootAddItemMethod = twoArgMethod(root.getClass(), "addItemStack0",
                        Location.class, ItemStack.class);
                if (rootAddItemMethod == null) {
                    rootAddItemMethod = twoArgMethod(root.getClass(), "addItemStack",
                            Location.class, ItemStack.class);
                }
            }
        }
    }

    /** 按「单参 + 参数类型兼容」找方法（逐层向上）。 */
    private static Method singleArgMethod(Class<?> cls, String name, Class<?> argType) {
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isAssignableFrom(argType)) continue;
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 按「两参 + 参数类型兼容」找方法（逐层向上）。 */
    private static Method twoArgMethod(Class<?> cls, String name, Class<?> a, Class<?> b) {
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 2) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (!p[0].isAssignableFrom(a) || !p[1].isAssignableFrom(b)) continue;
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 找网络
    // ------------------------------------------------------------------

    /**
     * 本机连着的网络根。
     *
     * <p>策略：<b>先试上次记下的锚点</b>（省掉一次 BFS），失败才做 6 邻域 BFS；
     * BFS 只穿过「<b>也是堆叠机</b>」的方块继续找 —— 多台堆叠机串在一起时，
     * 后面的机器也能借前面的机器进网。</p>
     *
     * @return 网络根对象；没找到返回 null
     */
    public static Object networkRoot(Location loc) {
        try {
            if (loc == null) return null;
            Method findRoot = findRoot();
            if (findRoot == null) return null;

            String cached = StackMachineStore.localGet(loc, StackMachineStore.DATA_NET, "");
            if (cached != null && cached.length() > MIN_ANCHOR_LEN) {
                Location anchor = parseLoc(cached);
                if (anchor != null) {
                    Object r0 = invokeFindRoot(findRoot, anchor);
                    if (r0 != null) return r0;
                    StackMachineStore.localSet(loc, StackMachineStore.DATA_NET, "");
                }
            }

            Deque<Location> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(loc);
            seen.add(fmtLoc(loc));
            String selfId = MACHINE_ID;
            int steps = 0;
            while (!queue.isEmpty() && steps < BFS_LIMIT) {
                Location cur = queue.poll();
                steps++;
                Object root = invokeFindRoot(findRoot, cur);
                if (root != null) {
                    StackMachineStore.localSet(loc, StackMachineStore.DATA_NET, fmtLoc(cur));
                    return root;
                }
                World w = cur.getWorld();
                if (w == null) continue;
                for (int[] f : FACES) {
                    Location nl = new Location(w, cur.getBlockX() + f[0],
                            cur.getBlockY() + f[1], cur.getBlockZ() + f[2]);
                    String k = fmtLoc(nl);
                    if (k.isEmpty() || seen.contains(k)) continue;
                    // 只穿过「同类堆叠机」继续找网：多台相连时后面的机器也能进网
                    boolean ours;
                    try {
                        ours = me.mrCookieSlime.Slimefun.api.BlockStorage.check(nl, selfId);
                    } catch (Throwable t) {
                        ours = false;
                    }
                    if (!ours) continue;
                    seen.add(k);
                    queue.add(nl);
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 查找网络失败: " + t);
        }
        return null;
    }

    private static Object invokeFindRoot(Method findRoot, Location at) {
        try {
            return findRoot.invoke(null, at);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    // ------------------------------------------------------------------
    // 坐标串（与缓存键不同：这里用逗号，写进 BlockData 供跨次查找锚点）
    // ------------------------------------------------------------------

    /** {@code 世界,X,Y,Z}。 */
    public static String fmtLoc(Location loc) {
        try {
            if (loc == null || loc.getWorld() == null) return "";
            return loc.getWorld().getName() + "," + loc.getBlockX()
                    + "," + loc.getBlockY() + "," + loc.getBlockZ();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** {@code 世界,X,Y,Z} → Location；非法 / 世界不存在返回 null。 */
    public static Location parseLoc(String str) {
        try {
            if (str == null) return null;
            String[] p = str.split(",");
            if (p.length < 4) return null;
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w, Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 取量 / 投物
    // ------------------------------------------------------------------

    /** 网络里有这件东西多少个（0 = 没有）。 */
    public static long netAmount(Object root, ItemStack stack) {
        try {
            if (root == null || stack == null) return 0L;
            ensureRootMethods(root);
            if (rootGetAmountMethod == null) return 0L;
            ItemStack probe = stack.clone();
            probe.setAmount(1);
            Object v = rootGetAmountMethod.invoke(root, probe);
            return (v instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 把一叠塞进网络。
     *
     * @return <b>真实被收下</b>的数量（网络前后差 + 传入叠剩余量交叉校验，取较小值）
     */
    public static long pushNet(Location loc, Object root, ItemStack stack, long amount) {
        long accepted = 0L;
        try {
            if (loc == null || root == null || stack == null) return 0L;
            long want = Math.max(0L, amount);
            if (want <= 0L) return 0L;
            ensureRootMethods(root);
            if (rootAddItemMethod == null) return 0L;

            ItemStack probe = stack.clone();
            probe.setAmount(1);
            long before = netAmount(root, probe);

            ItemStack st = stack.clone();
            st.setAmount((int) Math.min(Integer.MAX_VALUE, want));
            try {
                rootAddItemMethod.invoke(root, loc, st);
            } catch (Throwable t) {
                return 0L;
            }
            long after = netAmount(root, probe);
            long gotNet = after - before;
            long gotAmt = gotNet;
            try {
                gotAmt = want - st.getAmount();
            } catch (Throwable ignored) {
                gotAmt = gotNet;
            }
            accepted = gotNet > 0L ? gotNet : gotAmt;
            // 交叉校验：两边都有数时取小的 —— 宁可少报，让剩下的去输出槽，绝不凭空消失
            if (gotAmt > 0L && gotAmt < accepted) accepted = gotAmt;
            if (accepted < 0L) accepted = 0L;
            if (accepted > want) accepted = want;
        } catch (Throwable t) {
            TT.log("[堆叠机] 入网失败: " + t);
        }
        return accepted;
    }
}
