package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetProvider;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNet;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.AbstractEnergyProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineFuel;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineNet;
import com.tangran.tangrantech.native_scripts.daofa.StackMachinePower;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRecipe;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineScript;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineWork;

/**
 * 「糖然牌堆叠机」的发电机层（原脚本阶段 5）。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的 {@code pdGenSpecOf} / {@code pdRunGeneratorCycle} /
 * {@code pdPickGenFuel} / {@code pdPickGenFish} / {@code pdProviderOutput} /
 * {@code pdGenPseudoRecipes} / {@code pdEmitGeneratorOutput} / {@code pdFish*}。</p>
 *
 * <h2>支持的三类发电机（用户点名的）</h2>
 * <ol>
 *   <li><b>燃料型</b>（{@code generators.yml} 的 {@code TR_CJHFYD}）——
 *       继承粘液 {@link AbstractEnergyProvider} 的家族：烧燃料发电 + 有副产物。
 *       本项目的 {@code TTGenerator extends AGenerator extends AbstractEnergyProvider}，
 *       所以它天然落在这里。</li>
 *   <li><b>生物型</b>（MagicExpansion）—— 读它类里的 {@code FISH_POWER_MAP}（鱼类型 → 基础发电），
 *       再乘鱼物品 PDC 里的<b>重量</b>与<b>重量档位倍率</b>。</li>
 *   <li><b>通用 provider 型</b>—— 任何 {@link EnergyNetProvider}（太阳能 / 位置型…）：
 *       每粘液刻直接问它自己要产量（{@code getGeneratedOutputLong} 优先，退 {@code getGeneratedOutput}）。</li>
 * </ol>
 *
 * <h2>为什么发电机要「每粘液刻」跑</h2>
 * <p>配方机器是「攒够 {@code ticks} 才出一份」，而发电机<b>每一刻都在发电</b>。
 * 原脚本因此在 {@code tick} 里为发电机单开一条分支（见 {@code StackMachineScript} 的 tick 入口）。</p>
 *
 * <h2>电去哪了（顺序不能变）</h2>
 * <pre>
 *   ① 本机缓存里存的电，先还给能源网络
 *   ② 本周期发电
 *   ③ 推电：先网络 → 再「塞给旁边的堆叠机」（电力总线）→ 最后余下才进本机缓存（受 capacity 限制）
 * </pre>
 * <p>第 ③ 步中间那环是用户实测加的：发电机<b>直接贴着</b>耗电的堆叠机、中间没有粘液电容时，
 * 能源网络里没有「电容」可抽，电只能留在发电机自己缓存里 ⇒ 耗电那台永远 0
 * （「堆叠机器存不进去电」）。</p>
 */
public final class StackMachineGenerator {

    private StackMachineGenerator() {}

    // ==================================================================
    // 类型
    // ==================================================================

    public static final String KIND_FUEL = "fuel";
    public static final String KIND_FISH = "fish";
    public static final String KIND_PROVIDER = "provider";

    /** 一种燃料。 */
    public static final class Fuel {
        public final ItemStack stack;
        public final ItemStack out;
        public final int ticks;

        Fuel(ItemStack stack, ItemStack out, int ticks) {
            this.stack = stack;
            this.out = out;
            this.ticks = ticks;
        }
    }

    /** 发电机规格。 */
    public static final class GenSpec {
        public final String kind;
        /** 燃料型：每粘液刻产量（单台）。 */
        public long production;
        public long capacity;
        public final List<Fuel> fuels = new ArrayList<>();
        /** 生物型：鱼类型 → 基础发电（保持 Map 顺序）。 */
        public final Map<String, Long> power = new LinkedHashMap<>();
        /** provider 型：机器本体。 */
        public SlimefunItem item;

        GenSpec(String kind) {
            this.kind = kind;
        }
    }

    /** 一次发电机周期的结果。 */
    public static final class GenResult {
        public boolean ok;
        public String reason = "";
        /** 本周期总发电量（单台产量 × 并行）。 */
        public long gen;
        /** 成功送出去的（网络 + 总线）。 */
        public long pushed;
        /** 进入本机缓存的。 */
        public long stored;
        /** 副产物实际落下的数量。 */
        public long made;
        /** 单台产量。 */
        public long unit;
        /** 通过电力总线送出去的。 */
        public long bus;
    }

    /** 挑中的燃料。 */
    public static final class FuelPick {
        public Fuel fuel;
        public int per;
        public long need;
        public int index;
    }

    /** 挑中的模板鱼。 */
    public static final class FishPick {
        public long unit;
        public ItemStack stack;
    }

    // ==================================================================
    // 缓存
    // ==================================================================

    private static final Map<String, GenSpec> SPEC_CACHE = new ConcurrentHashMap<>();
    /** 已探过但确实没有规格的机器 id（避免每次 tick 反复反射）。 */
    private static final Set<String> SPEC_MISS = ConcurrentHashMap.newKeySet();

    /** 鱼类型名 → 该鱼的物品（建一次）。 */
    private static final Map<String, ItemStack> FISH_ITEM_MAP = new ConcurrentHashMap<>();
    private static volatile boolean fishMapBuilt = false;

    private static volatile boolean fishProbed = false;
    private static boolean fishReady = false;
    private static Object fishTypeKey;
    private static Object fishWeightKey;
    private static Object fishRarityKey;
    private static Method rarityMultiplier;

    /** 供调试 / 卸载使用。 */
    public static void shutdown() {
        SPEC_CACHE.clear();
        SPEC_MISS.clear();
        FISH_ITEM_MAP.clear();
        fishMapBuilt = false;
    }

    // ==================================================================
    // 规格解析
    // ==================================================================

    /**
     * 机器 id → 发电机规格（缓存）。
     *
     * <p>判定顺序<b>不能变</b>：先 {@link AbstractEnergyProvider}（燃料型），
     * 再 {@code FISH_POWER_MAP}（生物型），最后才落到通用 {@link EnergyNetProvider}。
     * 因为子类同时满足多个条件时，越具体的越应该胜出。</p>
     */
    public static GenSpec specOf(String machineId) {
        try {
            if (machineId == null) return null;
            String ck = machineId.toUpperCase(Locale.ROOT);
            GenSpec cached = SPEC_CACHE.get(ck);
            if (cached != null) return cached;
            if (SPEC_MISS.contains(ck)) return null;

            GenSpec spec = buildSpec(machineId);
            if (spec == null) {
                SPEC_MISS.add(ck);
                return null;
            }
            SPEC_CACHE.put(ck, spec);
            return spec;
        } catch (Throwable t) {
            TT.log("[堆叠机] 解析发电机规格失败(" + machineId + "): " + t);
            return null;
        }
    }

    private static GenSpec buildSpec(String machineId) {
        SlimefunItem item = TT.sfItem(machineId);
        if (item == null) return null;

        // ① 燃料型：RSC generators.yml 的 CustomGenerator 家族（本项目 = TTGenerator）
        if (item instanceof AbstractEnergyProvider aep) {
            GenSpec spec = new GenSpec(KIND_FUEL);
            try {
                spec.production = Math.max(0L, aep.getEnergyProduction());
            } catch (Throwable ignored) {
                spec.production = 0L;
            }
            try {
                spec.capacity = Math.max(0L, aep.getCapacity());
            } catch (Throwable ignored) {
                spec.capacity = 0L;
            }
            try {
                Set<MachineFuel> set = aep.getFuelTypes();
                if (set != null) {
                    for (MachineFuel f : set) {
                        if (f == null) continue;
                        ItemStack in0 = f.getInput();
                        if (in0 == null) continue;
                        ItemStack out0;
                        try {
                            out0 = f.getOutput();
                        } catch (Throwable ignored) {
                            out0 = null;
                        }
                        int tk = f.getTicks();
                        if (tk <= 0) tk = 2;
                        spec.fuels.add(new Fuel(in0, out0, tk));
                    }
                }
            } catch (Throwable ignored) {
                // 燃料表读不到 → 退化成「不发电」
            }
            return spec;
        }

        // ② 生物型：MagicExpansion 的 FISH_POWER_MAP
        Object pm = StackMachinePower.fieldOf(item, "FISH_POWER_MAP");
        if (isMapLike(pm)) {
            GenSpec spec = new GenSpec(KIND_FISH);
            Object cap = StackMachinePower.fieldOf(item, "Capacity");
            if (cap instanceof Number n) spec.capacity = Math.max(0L, n.longValue());
            fillPower(spec, pm);
            return spec;
        }

        // ③ 通用 provider 型：任何 EnergyNetProvider（太阳能 / 位置型…）
        if (item instanceof EnergyNetProvider ep) {
            GenSpec spec = new GenSpec(KIND_PROVIDER);
            spec.item = item;
            try {
                // 注意：instanceof 不改写变量类型，必须用模式变量调 getCapacity()
                spec.capacity = Math.max(0L, ep.getCapacity());
            } catch (Throwable ignored) {
                spec.capacity = 0L;
            }
            return spec;
        }
        return null;
    }

    /** 判断是不是「像 Map 一样」的东西（普通 Map 或 fastutil 之类的键值容器）。 */
    private static boolean isMapLike(Object o) {
        if (o == null) return false;
        if (o instanceof Map<?, ?>) return true;
        try {
            return o.getClass().getMethod("get", Object.class) != null
                    && o.getClass().getMethod("keySet") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把「鱼类型 → 基础发电」灌进规格（普通 Map 与反射两条路）。 */
    private static void fillPower(GenSpec spec, Object pm) {
        try {
            if (pm instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() == null) continue;
                    long v = (e.getValue() instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
                    spec.power.put(String.valueOf(e.getKey()), v);
                }
                return;
            }
            Method keySet = pm.getClass().getMethod("keySet");
            Method get = pm.getClass().getMethod("get", Object.class);
            Object ks = keySet.invoke(pm);
            for (Object k : StackMachineRecipe.toList(ks)) {
                if (k == null) continue;
                Object v = get.invoke(pm, k);
                long n = (v instanceof Number nn) ? Math.max(0L, nn.longValue()) : 0L;
                spec.power.put(String.valueOf(k), n);
            }
        } catch (Throwable ignored) {
            // 读不到就当作「没有可用的鱼」
        }
    }

    /** 生物发电机支持的鱼类型名（保持原 Map 顺序）。 */
    public static List<String> genFishNames(GenSpec spec) {
        List<String> names = new ArrayList<>();
        try {
            if (spec == null) return names;
            names.addAll(spec.power.keySet());
        } catch (Throwable ignored) {
            // 忽略
        }
        return names;
    }

    // ==================================================================
    // 伪配方（让配方子菜单 / 纸标签 / 进度条共用同一套 UI）
    // ==================================================================

    /**
     * 发电机没有 {@code MachineRecipe} 列表 ⇒ 用「燃料 / 模板鱼」合成伪配方。
     *
     * <ul>
     *   <li>燃料型：每种燃料一条（图标 = 燃料、产出 = 副产物、耗时 = 燃烧粘液刻）；</li>
     *   <li>生物型：每种可用的鱼一条（模板名 = 鱼名，不消耗）；</li>
     *   <li>provider 型：一条「环境发电」。</li>
     * </ul>
     */
    public static List<StackMachineRecipe.Recipe> pseudoRecipes(SlimefunItem sfItem) {
        List<StackMachineRecipe.Recipe> out = new ArrayList<>();
        try {
            if (sfItem == null) return out;
            GenSpec spec = specOf(sfItem.getId());
            if (spec == null) return out;
            if (KIND_FUEL.equals(spec.kind)) {
                for (int i = 0; i < spec.fuels.size(); i++) {
                    Fuel f = spec.fuels.get(i);
                    int per = Math.max(1, f.stack.getAmount());
                    StackMachineRecipe.Recipe r = new StackMachineRecipe.Recipe();
                    r.inputs.add(new StackMachineRecipe.Input(f.stack.clone(), per, per));
                    if (f.out != null) {
                        r.outputs.add(new StackMachineRecipe.Output(
                                f.out.clone(), Math.max(1, f.out.getAmount()), 100.0));
                    }
                    r.ticks = Math.max(1, f.ticks);
                    r.display = f.stack.clone();
                    r.genFuelIndex = i;
                    out.add(r);
                }
            } else if (KIND_FISH.equals(spec.kind)) {
                for (String name : genFishNames(spec)) {
                    StackMachineRecipe.Recipe r = new StackMachineRecipe.Recipe();
                    r.ticks = 1;
                    r.templateName = name + "（鱼，带重量的那种）";
                    r.display = fishDisplayStack(name);
                    r.genFishType = name;
                    out.add(r);
                }
            } else if (KIND_PROVIDER.equals(spec.kind)) {
                StackMachineRecipe.Recipe r = new StackMachineRecipe.Recipe();
                r.ticks = 1;
                r.genProvider = true;
                out.add(r);
            }
        } catch (Throwable t) {
            TT.log("[叠堆机] 发电机伪配方失败: " + t);
        }
        return out;
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    /**
     * 发电机：每粘液刻跑一次。
     *
     * @param count      本机堆叠的台数
     * @param selected   玩家固定的燃料 / 鱼下标；null = 自动
     */
    public static GenResult runCycle(Location loc, String type, String machineId, long count,
                                     Inventory inv, Integer selected) {
        GenResult res = new GenResult();
        try {
            GenSpec spec = specOf(machineId);
            if (spec == null) {
                res.reason = "nospec";
                return res;
            }
            // 配方子菜单里固定了「第 k 种燃料 / 鱼」就只认那一条（越界或不够 → 不工作，不偷偷换别的）
            Integer fixed = selected;
            StackMachineRecipe.Recipe pseudo = null;
            try {
                List<StackMachineRecipe.Recipe> list = StackMachineRecipe.listOf(machineId);
                if (fixed != null && fixed >= 0 && fixed < list.size()) pseudo = list.get(fixed);
            } catch (Throwable ignored) {
                pseudo = null;
            }
            long parallel = Math.max(1L, (long) Math.floor(count * StackMachineWork.DEFAULT_EFFICIENCY));
            if (fixed != null && pseudo == null) {
                res.reason = "fixed-oob";
                return res;
            }
            StackMachineScript.TickState st = StackMachineScript.tickStateOf(loc);
            if (st == null) {
                res.reason = "nostate";
                return res;
            }
            EnergyNet net = StackMachinePower.energyNetAtCached(loc);
            long buffered = StackMachinePower.localChargeOf(loc);

            // ① 本机缓存先吐出去（网络有空位就还给它）
            if (buffered > 0L && net != null) {
                long left0 = StackMachinePower.pushEnergy(loc, net, buffered);
                long moved = buffered - left0;
                if (moved > 0L) {
                    buffered = left0;
                    StackMachinePower.setLocalCharge(loc, buffered);
                    res.pushed += moved;
                }
            }

            // ② 本周期每台发多少 J
            long unit = 0L;
            st.genWorking = false;
            if (KIND_FUEL.equals(spec.kind)) {
                if (st.genBurn > 0L) {
                    st.genBurn = st.genBurn - 1L;
                    unit = Math.max(0L, spec.production);
                    st.genWorking = true;
                } else {
                    FuelPick pick = pickGenFuel(spec, inv, parallel, fixed);
                    if (pick != null) {
                        StackMachineRecipe.takeInput(inv, pick.fuel.stack, pick.need, true);
                        st.genBurn = Math.max(0L, pick.fuel.ticks - 1L);
                        unit = Math.max(0L, spec.production);
                        st.genWorking = true;
                        st.genBurnTotal = pick.fuel.ticks;
                        if (pick.fuel.out != null) {
                            ItemStack ob = pick.fuel.out.clone();
                            int oa = Math.max(1, ob.getAmount());
                            ob.setAmount((int) Math.min(Integer.MAX_VALUE, (long) oa * parallel));
                            res.made += emitGeneratorOutput(loc, type, inv, ob);
                        }
                    }
                }
            } else if (KIND_FISH.equals(spec.kind)) {
                String wantType = (pseudo != null && pseudo.genFishType != null)
                        ? pseudo.genFishType : null;
                FishPick fish = pickGenFish(spec, inv, loc, wantType);
                if (fish != null) {
                    unit = fish.unit;
                    st.genWorking = true;
                }
            } else if (KIND_PROVIDER.equals(spec.kind)) {
                long out = providerOutput(spec, loc);
                if (out > 0L) {
                    unit = out;
                    st.genWorking = true;
                }
            }
            long gen = Math.max(0L, (long) Math.floor(unit * (double) parallel));
            res.unit = unit;
            res.gen = gen;

            // ③ 推电：先网络，再「塞给旁边的堆叠机」，最后余下才进本机缓存（受 capacity 限制）
            if (gen > 0L) {
                long left = gen;
                if (net != null) left = StackMachinePower.pushEnergy(loc, net, left);
                res.pushed += (gen - left);
                if (left > 0L) {
                    long beforeBus = left;
                    try {
                        left = StackMachinePower.busPush(loc, left);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    res.bus += (beforeBus - left);
                    res.pushed += (beforeBus - left);
                }
                if (left > 0L) {
                    long cap = Math.max(0L, spec.capacity);
                    long keep = buffered + left;
                    if (cap > 0L && keep > cap) keep = cap;
                    if (keep > buffered) {
                        res.stored = keep - buffered;
                        StackMachinePower.setLocalCharge(loc, keep);
                    }
                }
            }

            // ④ 状态（机器信息卡 / 进度条用）
            st.genUnit = Math.max(0L, unit);
            st.genParallel = parallel;
            st.genRate = gen;
            st.genRateOut = res.pushed;
            st.genStorage = StackMachinePower.localChargeOf(loc);
            st.genCap = Math.max(0L, spec.capacity);
            st.genFull = (st.genCap > 0L && st.genStorage >= st.genCap);
            res.ok = true;
        } catch (Throwable t) {
            res.reason = "error:" + t;
            TT.log("[堆叠机] 发电机周期异常: " + t);
        }
        return res;
    }

    // ==================================================================
    // 燃料 / 鱼 / provider 的挑法
    // ==================================================================

    /**
     * 挑一种燃料：输入槽里有「每份数量 × 并行」就算可用。
     * <b>固定了配方（selected）就只认那一条</b> —— 不够就不工作，不偷偷换别的。
     */
    public static FuelPick pickGenFuel(GenSpec spec, Inventory inv, long parallel, Integer selected) {
        try {
            if (spec == null || spec.fuels.isEmpty() || inv == null) return null;
            Integer idx = null;
            if (selected != null) {
                int si = selected;
                if (si >= 0 && si < spec.fuels.size()) {
                    idx = si;
                } else {
                    return null;
                }
            }
            int n = (idx != null) ? 1 : spec.fuels.size();
            for (int k = 0; k < n; k++) {
                int i = (idx != null) ? idx : k;
                Fuel f = spec.fuels.get(i);
                if (f == null) continue;
                int per = Math.max(1, f.stack.getAmount());
                long need = (long) per * parallel;
                if (StackMachineRecipe.countInput(inv, f.stack, need) >= need) {
                    FuelPick pick = new FuelPick();
                    pick.fuel = f;
                    pick.per = per;
                    pick.need = need;
                    pick.index = i;
                    return pick;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 挑「模板鱼」。
     *
     * <p>优先配置界面的模板槽（19~26，<b>只当模具不消耗</b> —— 和 RSC 模板机器一个口径），
     * 其次父界面输入槽（原版生物发电机就是放 13 号输入槽）。</p>
     */
    public static FishPick pickGenFish(GenSpec spec, Inventory inv, Location loc, String wantType) {
        try {
            if (spec == null || spec.power.isEmpty()) return null;
            List<ItemStack> cands = new ArrayList<>();
            try {
                Inventory cinv = StackMachineScript.configInvFor(loc, true);
                if (cinv != null) {
                    for (int slot : StackMachineRecipe.CFG_TEMPLATE_SLOTS) {
                        ItemStack it = cinv.getItem(slot);
                        if (it != null && !it.getType().isAir()) cands.add(it);
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            if (inv != null) {
                for (int slot : StackMachineRecipe.INPUT_SLOTS) {
                    ItemStack it = inv.getItem(slot);
                    if (it != null && !it.getType().isAir()) cands.add(it);
                }
            }
            for (ItemStack cand : cands) {
                if (wantType != null && !wantType.isEmpty()) {
                    String tn = fishTypeOf(cand);
                    // 固定了鱼种 → 只认这一种
                    if (tn == null || !tn.equals(wantType)) continue;
                }
                long unit = fishPowerOf(spec, cand);
                if (unit > 0L) {
                    FishPick pick = new FishPick();
                    pick.unit = unit;
                    pick.stack = cand;
                    return pick;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 通用 provider：问它自己要「每粘液刻多少 J」（长整型版优先，失败退 int 版）。 */
    public static long providerOutput(GenSpec spec, Location loc) {
        try {
            if (spec == null || spec.item == null) return 0L;
            EnergyNetProvider ep = (EnergyNetProvider) spec.item;
            Object data = null;
            try {
                data = com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                        .getDataContainer(loc);
            } catch (Throwable ignored) {
                data = null;
            }
            if (data instanceof com.xzavier0722.mc.plugin.slimefun4.storage.controller.ASlimefunDataContainer dc) {
                try {
                    return Math.max(0L, ep.getGeneratedOutputLong(loc, dc));
                } catch (Throwable ignored) {
                    // 退 int 版
                }
                try {
                    return Math.max(0L, ep.getGeneratedOutput(loc, dc));
                } catch (Throwable ignored) {
                    return 0L;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return 0L;
    }

    /**
     * 发电机产出的副产物：网络已有这种物品 → 全额入网；否则进输出槽（图标 / 容量按布局）。
     */
    public static long emitGeneratorOutput(Location loc, String type, Inventory inv, ItemStack stack) {
        long made = 0L;
        try {
            if (stack == null || stack.getType().isAir()) return 0L;
            long total = Math.max(0L, stack.getAmount());
            if (total <= 0L) return 0L;
            long remain = total;
            Object root = StackMachineNet.networkRoot(loc);
            if (root != null && StackMachineNet.netAmount(root, stack) > 0L) {
                long sent = StackMachineNet.pushNet(loc, root, stack, remain);
                remain -= sent;
                made += sent;
            }
            if (remain > 0L && inv != null) {
                int[] slots = StackMachineScript.outputSlotsOf(type);
                long room = (long) StackMachineRecipe.emptySlotCount(inv, slots)
                        * StackMachineRecipe.MAX_STACK
                        + StackMachineRecipe.slotRoomFor(inv, stack, slots);
                long allocN = Math.min(remain, room);
                if (allocN > 0L) {
                    made += (allocN - StackMachineRecipe.putOutput(inv, stack, allocN, slots));
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return made;
    }

    // ==================================================================
    // 鱼（MagicExpansion 可选依赖）
    // ==================================================================

    /**
     * 探测 MagicExpansion 的鱼类 API。
     *
     * <p>编译期没有它的 jar，全部走反射；失败一次就记住不可用 ——
     * 不装该插件时「生物发电机」这条路径整体退化为「不发电」，其它两类照常工作。</p>
     */
    private static boolean fishApi() {
        if (fishProbed) return fishReady;
        synchronized (StackMachineGenerator.class) {
            if (fishProbed) return fishReady;
            try {
                Class<?> keys = Class.forName("io.Yomicer.magicExpansion.items.misc.fish.FishKeys");
                fishTypeKey = keys.getField("FISH_TYPE").get(null);
                fishWeightKey = keys.getField("FISH_WEIGHT").get(null);
                fishRarityKey = keys.getField("FISH_WEIGHT_RARITY").get(null);
                Class<?> rarity = Class.forName(
                        "io.Yomicer.magicExpansion.items.misc.fish.Fish$WeightRarity");
                rarityMultiplier = rarity.getMethod("getMultiplierByName", String.class);
                fishReady = fishTypeKey != null && fishWeightKey != null && fishRarityKey != null;
            } catch (Throwable ignored) {
                fishReady = false;
            }
            fishProbed = true;
        }
        return fishReady;
    }

    /** 读一个物品的 PDC {@code FISH_TYPE}（生物发电机认鱼就靠这个）。 */
    private static String fishTypeOf(ItemStack stack) {
        try {
            if (!fishApi() || stack == null) return null;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return null;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc == null) return null;
            if (!(fishTypeKey instanceof NamespacedKey key)) return null;
            if (pdc.has(key, PersistentDataType.STRING)) {
                return pdc.get(key, PersistentDataType.STRING);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 一条鱼的发电量 = 基础发电（按类型）× 重量 × 重量档位倍率。
     */
    private static long fishPowerOf(GenSpec spec, ItemStack stack) {
        try {
            if (!fishApi() || stack == null || spec == null || spec.power.isEmpty()) return 0L;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return 0L;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc == null) return 0L;
            if (!(fishTypeKey instanceof NamespacedKey tk)
                    || !(fishWeightKey instanceof NamespacedKey wk)
                    || !(fishRarityKey instanceof NamespacedKey rk)) {
                return 0L;
            }
            String type = null;
            double weight = 0.0d;
            String rarity = null;
            try {
                if (pdc.has(tk, PersistentDataType.STRING)) {
                    type = pdc.get(tk, PersistentDataType.STRING);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (pdc.has(wk, PersistentDataType.DOUBLE)) {
                    Double d = pdc.get(wk, PersistentDataType.DOUBLE);
                    if (d != null) weight = d;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (pdc.has(rk, PersistentDataType.STRING)) {
                    rarity = pdc.get(rk, PersistentDataType.STRING);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            if (type == null || weight <= 0.0d || rarity == null) return 0L;
            Long base = spec.power.get(type);
            if (base == null || base <= 0L) return 0L;
            double mult = 1.0d;
            if (rarityMultiplier != null) {
                try {
                    Object v = rarityMultiplier.invoke(null, rarity);
                    if (v instanceof Number n) mult = n.doubleValue();
                } catch (Throwable ignored) {
                    mult = 1.0d;
                }
            }
            if (mult <= 0.0d) mult = 1.0d;
            return Math.max(0L, (long) Math.floor(base * weight * mult));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 鱼类型名 → 该鱼的物品（给配方图标用）：扫一次全服已启用粘液物品，读它们 item 的 PDC。
     *
     * <p>找不到时兜底给「一张写名字的纸」，保证界面上永远有东西可看。</p>
     */
    private static ItemStack fishDisplayStack(String typeName) {
        try {
            if (typeName == null) return null;
            buildFishMap();
            ItemStack found = FISH_ITEM_MAP.get(typeName);
            if (found != null) return found.clone();
            ItemStack paper = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = paper.getItemMeta();
            if (meta != null) {
                meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§b" + typeName));
                paper.setItemMeta(meta);
            }
            return paper;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 建「鱼类型 → 鱼物品」表（只建一次）。 */
    private static void buildFishMap() {
        if (fishMapBuilt) return;
        synchronized (StackMachineGenerator.class) {
            if (fishMapBuilt) return;
            fishMapBuilt = true;
            try {
                if (!fishApi()) return;
                List<SlimefunItem> list = Slimefun.getRegistry().getEnabledSlimefunItems();
                if (list == null) return;
                int guard = 0;
                for (SlimefunItem si : list) {
                    if (guard++ > 4000) break;
                    if (si == null) continue;
                    ItemStack stack;
                    try {
                        stack = si.getItem();
                    } catch (Throwable ignored) {
                        stack = null;
                    }
                    if (stack == null) continue;
                    String tn = fishTypeOf(stack);
                    if (tn != null && !FISH_ITEM_MAP.containsKey(tn)) {
                        FISH_ITEM_MAP.put(tn, stack);
                    }
                }
            } catch (Throwable t) {
                TT.log("[堆叠机] 扫描鱼类物品失败: " + t);
            }
        }
    }

    /** 反射读静态字段的小工具（本类内部用）。 */
    @SuppressWarnings("unused")
    private static Object staticField(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
