package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTRecipe;
import com.tangran.tangrantech.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineGenerator;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRegistry;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineScript;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineStore;

/**
 * 「糖然牌堆叠机」的配方层（原脚本阶段 4）。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的 {@code pdNormalizeRecipe} / {@code pdNormalizeGeneric} /
 * {@code pdIterToArray} / {@code pdGf} / {@code pdRecipeListOf} / {@code pdPickRecipe} /
 * {@code pdRecipeInputsOk} / {@code pdCountInput} / {@code pdTakeInput} / {@code pdRollChance} /
 * {@code pdAllocate*} / {@code pdPutOutput} 一族。</p>
 *
 * <h2>为什么配方读取要「两条通道」</h2>
 * <p>用户口径原话：「为什么逻辑工艺的堆叠就能识别服务器中所有机器的配方（比如无尽贪婪的磨粉机），
 * 我们的识别不出来？」</p>
 * <p>答案：逻辑工艺读的是<b>老粘液 {@link MachineRecipe} API</b>（javap 实证：
 * {@code MachineRecipeUtils#FromMachine} 只调 {@code getInput() / getOutput() / getTicks()}），
 * 而服务器上绝大多数附属（无尽贪婪、动力科技、MagicExpansion…）的机器配方都是这个形状。
 * 原脚本以前只用 RSC 的 {@code CustomMachineRecipe.from()} 包装，非 RSC 机器就<b>读不出来</b>。</p>
 * <p>本独立化版本里 RSC 已经不存在，所以：</p>
 * <ol>
 *   <li>{@link TTRecipe}（<b>本项目自己</b>的配方机器）→ 走「精确通道」{@link #normalizeTT}，
 *       能读到 {@code chance} / {@code noConsume} / {@code chooseOne}；</li>
 *   <li>其余一律走「通用通道」{@link #normalizeGeneric}，覆盖
 *       {@code ItemStack[]} / {@code List} / {@code Map} / 各种包装类，
 *       多产物按各自的 {@code amount} / {@code chance} 一起收进来。</li>
 * </ol>
 *
 * <h2>三条不能违反的约束</h2>
 * <ol>
 *   <li><b>配方读取永远不抛异常</b>：读不到就返回空列表（机器「不动」而不是「崩」）。</li>
 *   <li><b>不凭空造物、不凭空吞物</b>：产出量一律经 {@link #rollChance} 掷点，
 *       落不下槽位的部分宁可留给下一周期（{@link #putOutput} 返回剩余量），绝不丢弃。</li>
 *   <li><b>顺序敏感处逐字保留</b>：通用通道里「先读包装类的 amount/chance、再取它的 stack」
 *       这个顺序是关键 —— {@code ItemStack} 自己的 {@code getAmount()} 是堆叠数量，
 *       不能反过来覆盖 Map/包装里给的「产出数量」。</li>
 * </ol>
 */
public final class StackMachineRecipe {

    private StackMachineRecipe() {}

    // ==================================================================
    // 布局常量（与父界面 / 配置界面严格对应，数值来自原脚本）
    // ==================================================================

    /** 配方布局的输入槽：前三列 18 格。 */
    public static final int[] INPUT_SLOTS = {
            0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38, 45, 46, 47};

    /** 配方布局的输出槽：后五列 30 格。 */
    public static final int[] RECIPE_OUTPUT_SLOTS = {
            4, 5, 6, 7, 8, 13, 14, 15, 16, 17, 22, 23, 24, 25, 26,
            31, 32, 33, 34, 35, 40, 41, 42, 43, 44, 49, 50, 51, 52, 53};

    /** 默认布局 / 生成器的输出槽：前 5 行 45 格全是输出槽。 */
    public static final int[] DEFAULT_OUTPUT_SLOTS = buildDefaultOutputSlots();

    /**
     * 配置界面的模板槽（第三行右侧 8 格）。
     *
     * <p>放进去就算「持有该模板」，<b>永不消耗</b>。它<b>不是</b>特殊槽：
     * 玩家可以自由存取（见 {@code CFG_SPECIAL} 里刻意不含这几格）。</p>
     */
    public static final int[] CFG_TEMPLATE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26};

    private static int[] buildDefaultOutputSlots() {
        int[] out = new int[45];
        for (int i = 0; i < 45; i++) out[i] = i;
        return out;
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一条输入项。 */
    public static final class Input {
        /** 需要的物品（原型，不持有实时数量）。 */
        public final ItemStack stack;
        /** 每台机器一次需要的量。 */
        public final int need;
        /** 真正会被消耗的量（{@code noConsume} 的那部分为 0）。 */
        public final int consume;
        /**
         * 本周期按并行数放大后的需求量（{@code runWorkCycle} 临时写入）。
         *
         * <p>用 {@code long} 而不是 {@code int}：并行数 = 台数 × 2，玩家实测叠到 97695 台
         * 时并行已达 195390，再乘每台需求量会超出 {@code int} 的安全区间。</p>
         */
        public long needAll;
        /** 本周期按并行数放大后的消耗量。 */
        public long takeAll;

        Input(ItemStack stack, int need, int consume) {
            this.stack = stack;
            this.need = need;
            this.consume = consume;
        }
    }

    /** 一条产出项。 */
    public static final class Output {
        public final ItemStack stack;
        /** 产出数量（未乘并行数）。 */
        public final int amount;
        /** 产出概率，百分比（100 = 必出）。 */
        public final double chance;

        Output(ItemStack stack, int amount, double chance) {
            this.stack = stack;
            this.amount = amount;
            this.chance = chance;
        }
    }

    /** 归一化后的一条配方。字段名与原脚本的归一化对象一一对应。 */
    public static final class Recipe {
        public final List<Input> inputs = new ArrayList<>();
        public final List<Output> outputs = new ArrayList<>();
        /** 处理耗时（粘液刻）。 */
        public int ticks = 10;
        /** {@code chooseOne} 语义（只选其中一条产物）。 */
        public boolean chooseOne;
        /** 模板机器的模板物品（只当模具，不消耗）。 */
        public ItemStack template;
        /** 模板槽位（-1 = 未知）。 */
        public int templateSlot = -1;
        /** 模板的显示名（生成器用，没有实体模板物品时给玩家看的提示）。 */
        public String templateName;
        /**
         * 这条配方的「输入」其实是<b>模板</b>。
         *
         * <p>生成器（{@code mat_generators}）本身没有输入槽：它配方里的输入就是模板，
         * 放在配置界面第三行即可，永不被消耗，缺模板就不算满足。</p>
         */
        public boolean inputsAreTemplates;
        /** 是否来自通用通道（原脚本用它区分 RSC 通道）。 */
        public boolean fromGeneric;
        /** 配方图标用的展示物品（生成器伪配方里是燃料 / 鱼）。 */
        public ItemStack display;
        /** 生成器伪配方：对应第几种燃料。 */
        public int genFuelIndex = -1;
        /** 生成器伪配方：对应的鱼类型名。 */
        public String genFishType;
        /** 生成器伪配方：通用 provider 型（问机器自己要产量）。 */
        public boolean genProvider;

        /** 产物种类（供分配用）。 */
        public boolean isEmpty() {
            return inputs.isEmpty() && outputs.isEmpty();
        }
    }

    /** 分配阶段的一种待产出物。 */
    public static final class Kind {
        public final ItemStack stack;
        public long count;
        /** 网络里是否已有这种产物（有就优先全额入网）。 */
        public boolean net;

        public Kind(ItemStack stack, long count) {
            this.stack = stack;
            this.count = count;
        }
    }

    // ==================================================================
    // 状态
    // ==================================================================

    /** 机器 id（大写） → 归一化配方列表。 */
    private static final Map<String, List<Recipe>> CACHE = new ConcurrentHashMap<>();

    private static final Random RANDOM = new Random();

    /** 供调试 / 卸载使用。 */
    public static void shutdown() {
        CACHE.clear();
    }

    // ==================================================================
    // 对外：取配方列表
    // ==================================================================

    /**
     * 机器 id → 归一化配方列表（带缓存）。
     *
     * <p>没有 {@code MachineRecipe} 列表的机器（发电机）会用「燃料 / 鱼」合成<b>伪配方</b>，
     * 这样配方子菜单 / 纸标签 / 进度条都能共用同一套 UI（见
     * {@link StackMachineGenerator#pseudoRecipes}）。</p>
     */
    public static List<Recipe> listOf(String machineId) {
        try {
            if (machineId == null) return new ArrayList<>();
            String ck = machineId.toUpperCase(Locale.ROOT);
            List<Recipe> cached = CACHE.get(ck);
            if (cached != null) return cached;

            List<Recipe> out = new ArrayList<>();
            SlimefunItem sf = TT.sfItem(machineId);
            Object raw = rawRecipesOf(sf);
            if (raw != null) {
                int n = rawCount(raw);
                for (int i = 0; i < n; i++) {
                    Object rec = rawAt(raw, i);
                    if (rec == null) continue;
                    Recipe norm = normalize(rec);
                    if (norm != null && !norm.isEmpty()) out.add(norm);
                }
            }
            // 发电机没有 MachineRecipe 列表 ⇒ 用「燃料 / 模板鱼」合成伪配方
            if (out.isEmpty()) {
                try {
                    out = StackMachineGenerator.pseudoRecipes(sf);
                } catch (Throwable t) {
                    TT.log("[堆叠机] 生成器伪配方失败: " + t);
                    out = new ArrayList<>();
                }
            }
            // 生成器（mat_generators）：配方里的「输入」其实是模板
            boolean isMatgen = false;
            try {
                String type = null;
                if (sf != null) {
                    try {
                        type = StackMachineRegistry.identify(sf.getItem());
                    } catch (Throwable ignored) {
                        type = null;
                    }
                }
                isMatgen = StackMachineRegistry.TYPE_MATGEN.equals(type);
            } catch (Throwable ignored) {
                isMatgen = false;
            }
            if (isMatgen) {
                for (Recipe r : out) r.inputsAreTemplates = true;
            }

            CACHE.put(ck, out);
            return out;
        } catch (Throwable t) {
            TT.log("[堆叠机] 读取配方失败(" + machineId + "): " + t);
            return new ArrayList<>();
        }
    }

    /**
     * 本机当前「生效」的配方：选了固定配方就是它，否则按顺序挑第一条（用于模板提示）。
     *
     * <p>自动模式的口径与 {@link #pick} 一致：<b>不需要模板的配方最先胜出</b>；
     * 全都需要模板时，退回「第一条需要模板的」来提示玩家该放什么。</p>
     */
    public static Recipe activeOf(Location loc, String machineId) {
        try {
            List<Recipe> recipes = listOf(machineId);
            if (recipes.isEmpty()) return null;
            Integer sel = selectedIndexOf(loc);
            if (sel != null && sel >= 0 && sel < recipes.size()) return recipes.get(sel);
            Recipe needTpl = null;
            for (Recipe r : recipes) {
                if (templatesOf(r).isEmpty()) return r;
                if (templatesAllPresent(loc, r)) return r;
                if (needTpl == null) needTpl = r;
            }
            if (needTpl != null) return needTpl;
            return recipes.get(0);
        } catch (Throwable t) {
            TT.log("[堆叠机] 选生效配方失败: " + t);
            return null;
        }
    }

    /** 本机 BlockData 里固定的配方下标；未固定 / 解析失败返回 null（= 自动检测）。 */
    public static Integer selectedIndexOf(Location loc) {
        try {
            String v = StackMachineStore.localGet(loc, StackMachineStore.DATA_RECIPE, "");
            if (v == null || v.isEmpty()) return null;
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 对外：选配方 / 模板
    // ==================================================================

    /**
     * 挑一条能跑的配方。
     *
     * <p>选了固定配方就用它（不满足就返回 null，<b>不偷偷换别的</b>）；
     * 否则挑「输入槽里材料够」的第一条（生成器没有输入 → 第一条）。</p>
     */
    public static Recipe pick(List<Recipe> recipes, Inventory inv, Integer selectedIndex,
                              String type, Location loc) {
        try {
            if (recipes == null || recipes.isEmpty()) return null;
            if (selectedIndex != null && selectedIndex >= 0 && selectedIndex < recipes.size()) {
                Recipe want = recipes.get(selectedIndex);
                return inputsOk(inv, want, type, loc) ? want : null;
            }
            for (Recipe r : recipes) {
                if (inputsOk(inv, r, type, loc)) return r;
            }
            return null;
        } catch (Throwable t) {
            TT.log("[堆叠机] 选配方失败: " + t);
            return null;
        }
    }

    /**
     * 一条配方需要的<b>模板</b>清单。
     *
     * <ul>
     *   <li>生成器（{@code matgen}）：配方的「输入」就是模板（放配置界面第三行，永不消耗）；</li>
     *   <li>模板机器：用 {@link Recipe#template}。</li>
     * </ul>
     */
    public static List<ItemStack> templatesOf(Recipe rec) {
        List<ItemStack> out = new ArrayList<>();
        try {
            if (rec == null) return out;
            if (rec.inputsAreTemplates) {
                for (Input in : rec.inputs) {
                    if (in != null && in.stack != null) out.add(in.stack);
                }
            } else if (rec.template != null) {
                out.add(rec.template);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /**
     * 这条配方要的模板是不是<b>全都</b>在配置界面第三行的模板槽里（顺序无关、不消耗）。
     *
     * <p>模板槽要能在「玩家没开着配置界面」时也算数 —— 装好模板关掉界面，机器照样要工作。
     * 所以 {@link StackMachineScript#configInvFor} 会从 BlockData 复原一份只读快照。</p>
     */
    public static boolean templatesAllPresent(Location loc, Recipe rec) {
        try {
            List<ItemStack> list = templatesOf(rec);
            for (ItemStack tpl : list) {
                if (!hasTemplate(loc, tpl)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 配置界面第三行的模板槽里有没有这一件。 */
    public static boolean hasTemplate(Location loc, ItemStack templateStack) {
        try {
            if (templateStack == null) return true;
            Inventory inv = StackMachineScript.configInvFor(loc, true);
            if (inv == null) return false;
            for (int slot : CFG_TEMPLATE_SLOTS) {
                ItemStack it = inv.getItem(slot);
                if (it != null && !it.getType().isAir() && sameItem(it, templateStack)) return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 模板的显示名（给纸标签 / 配方子菜单用）。 */
    public static List<String> templateNamesOf(Recipe rec) {
        List<String> names = new ArrayList<>();
        try {
            List<ItemStack> list = templatesOf(rec);
            for (ItemStack it : list) {
                String nm = plainName(it);
                if (nm.isEmpty()) {
                    nm = it == null ? "?" : String.valueOf(it.getType());
                }
                names.add(nm);
            }
            if (names.isEmpty() && rec != null && rec.templateName != null
                    && !rec.templateName.isEmpty()) {
                names.add(rec.templateName);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return names;
    }

    // ==================================================================
    // 对外：输入检查 / 扣减
    // ==================================================================

    /** 输入够不够（含输入槽里存储物品的内容）。 */
    public static boolean inputsOk(Inventory inv, Recipe rec, String type, Location loc) {
        try {
            if (rec == null) return false;
            // 生成器配方：只查模板槽（模板永不消耗）
            if (rec.inputsAreTemplates) return templatesAllPresent(loc, rec);
            if (inv == null) return false;
            for (Input in : rec.inputs) {
                if (countInput(inv, in.stack, in.need) < in.need) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 数输入槽里某种物品的总量（普通堆叠 + 存储物品里的内容）。 */
    public static long countInput(Inventory inv, ItemStack want, long cap) {
        long total = 0L;
        try {
            if (inv == null || want == null) return 0L;
            for (int slot : INPUT_SLOTS) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) continue;
                if (sameItem(it, want)) total += it.getAmount();
                if (total >= cap) return total;
                StackMachineRegistry.StorageInfo st = StackMachineRegistry.storageOf(it);
                if (st != null && st.content() != null && sameItem(st.content(), want)) {
                    total += st.amount();
                }
                if (total >= cap) return total;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return total;
    }

    /**
     * 扣输入：先扣普通堆叠，再扣输入槽里存储物品的内容。
     *
     * <p>{@code consume == false} 时（{@code noConsume} 的输入）<b>只检查不扣</b>。</p>
     *
     * @return 实际「够数」的量（consume=false 时是按量计的检查结果）
     */
    public static long takeInput(Inventory inv, ItemStack want, long count, boolean consume) {
        long taken = 0L;
        try {
            if (inv == null || want == null || count <= 0) return 0L;
            for (int slot : INPUT_SLOTS) {
                if (taken >= count) break;
                ItemStack it = inv.getItem(slot);
                if (it == null || !sameItem(it, want)) continue;
                long take = Math.min(it.getAmount(), count - taken);
                if (consume) {
                    long left = it.getAmount() - take;
                    if (left <= 0) {
                        inv.setItem(slot, null);
                    } else {
                        it.setAmount((int) left);
                    }
                }
                taken += take;
            }
            for (int slot : INPUT_SLOTS) {
                if (taken >= count) break;
                ItemStack it = inv.getItem(slot);
                if (it == null) continue;
                StackMachineRegistry.StorageInfo st = StackMachineRegistry.storageOf(it);
                if (st == null || st.content() == null || !sameItem(st.content(), want)) continue;
                long want2 = count - taken;
                if (consume) {
                    taken += StackMachineRegistry.storageWithdraw(it, want2);
                } else {
                    taken += Math.min(st.amount(), want2);
                }
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 扣输入失败: " + t);
        }
        return taken;
    }

    // ==================================================================
    // 对外：产出掷点与分配
    // ==================================================================

    /**
     * 概率（百分比）→ 实际产出数量。
     *
     * <p>小数量<b>精确逐次掷</b>；大数量用<b>二项分布正态近似</b> ——
     * 否则「叠了 195390 台、每台 1 个产物」就要循环近 20 万次，一台机器一 tick 就卡服。</p>
     *
     * <p>近似用的是 6 个均匀分布之和（Irwin–Hall）标准化：均值 3、标准差 √0.5，
     * 比直接用 {@code Random.nextGaussian()} 更贴近原脚本的抽样方式。</p>
     */
    public static long rollChance(long n, double chance) {
        try {
            if (n <= 0) return 0L;
            if (Double.isNaN(chance)) chance = 100.0;
            if (chance >= 100.0) return n;
            if (chance <= 0.0) return 0L;
            double p = chance / 100.0;
            if (n <= ROLL_EXACT_LIMIT) {
                long c = 0L;
                for (long i = 0; i < n; i++) {
                    if (RANDOM.nextDouble() < p) c++;
                }
                return c;
            }
            double mean = n * p;
            double sd = Math.sqrt(n * p * (1.0 - p));
            double g = (RANDOM.nextDouble() + RANDOM.nextDouble() + RANDOM.nextDouble()
                    + RANDOM.nextDouble() + RANDOM.nextDouble() + RANDOM.nextDouble() - 3.0)
                    / Math.sqrt(0.5);
            long v = Math.round(mean + sd * g);
            if (v < 0L) v = 0L;
            if (v > n) v = n;
            return v;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 逐次掷的阈值：超过它改用正态近似。 */
    private static final long ROLL_EXACT_LIMIT = 256L;

    /**
     * 槽位均分（water-filling）：把 {@code cap} 个容量尽量平均分给若干种产物。
     *
     * <p>谁要得少谁先拿满，剩下的容量再摊给其它产物 ——
     * 对应「槽位只装得下 400 个时 A800/B400/C200/D100 → 各 100」。</p>
     */
    public static long[] allocate(long[] desired, long cap) {
        int n = desired == null ? 0 : desired.length;
        long[] out = new long[n];
        try {
            if (n == 0) return out;
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (desired[i] > 0) pending.add(i);
            }
            long remaining = Math.max(0L, cap);
            if (remaining <= 0 || pending.isEmpty()) return out;
            while (!pending.isEmpty()) {
                long share = remaining / pending.size();
                if (share <= 0) break;
                List<Integer> next = new ArrayList<>();
                boolean took = false;
                for (int idx : pending) {
                    if (desired[idx] <= share) {
                        out[idx] = desired[idx];
                        remaining -= out[idx];
                        took = true;
                    } else {
                        next.add(idx);
                    }
                }
                if (!took) {
                    for (int idx : next) out[idx] = share;
                    break;
                }
                pending = next;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 输出槽里「已有同类物品的槽还能再塞多少」——<b>空槽不算</b>（空槽由 {@link #emptySlotRoom} 统一算）。 */
    public static int slotRoomFor(Inventory inv, ItemStack want, int[] slots) {
        int room = 0;
        try {
            if (inv == null || slots == null) return 0;
            for (int slot : slots) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) continue;
                if (sameItem(it, want)) {
                    int r = MAX_STACK - it.getAmount();
                    if (r > 0) room += r;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return room;
    }

    /** 空的输出槽数量。 */
    public static int emptySlotCount(Inventory inv, int[] slots) {
        int n = 0;
        try {
            if (inv == null || slots == null) return 0;
            for (int slot : slots) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) n++;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return n;
    }

    /** 空输出槽容量（所有产物共享）。 */
    public static int emptySlotRoom(Inventory inv, int[] slots) {
        return emptySlotCount(inv, slots) * MAX_STACK;
    }

    /**
     * 按「槽」均分：一个槽只能放一种产物，所以某种产物至少要 {@code ceil(n / 64)} 个槽。
     *
     * <p>这里把空槽尽量平均分给各种产物（余数给前面的），再加上各产物在「已有同类槽」里的零散空间，
     * 得到一个<b>真实能摆下</b>的上限 —— 只按物品数均分，会在「4 种产物各 96 个」时算出 384
     * 但实际摆不下（槽被切碎）。</p>
     */
    public static long[] allocateSlots(long[] desired, int freeSlots, int[] partialRooms) {
        int k = desired == null ? 0 : desired.length;
        long[] out = new long[k];
        try {
            if (k == 0) return out;
            int fs = Math.max(0, freeSlots);
            int base = fs / k;
            int extra = fs - base * k;
            for (int j = 0; j < k; j++) {
                int slotsForKind = base + (j < extra ? 1 : 0);
                long cap = (long) slotsForKind * MAX_STACK
                        + Math.max(0, partialRooms != null && j < partialRooms.length
                        ? partialRooms[j] : 0);
                out[j] = Math.min(desired[j], cap);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /**
     * 把产物塞进输出槽（合并同类堆叠）。
     *
     * @return <b>没塞下</b>的数量（调用方负责把它记为「本周期丢弃 / 下周期再来」，
     *         绝不能直接当成已产出）
     */
    public static int putOutput(Inventory inv, ItemStack want, long amount, int[] slots) {
        long left = amount;
        try {
            if (inv == null || want == null || slots == null || left <= 0) {
                return (int) Math.max(0L, left);
            }
            for (int slot : slots) {
                if (left <= 0) break;
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) {
                    int put = (int) Math.min(MAX_STACK, left);
                    ItemStack st = want.clone();
                    st.setAmount(put);
                    inv.setItem(slot, st);
                    left -= put;
                    continue;
                }
                if (!sameItem(it, want)) continue;
                int r = MAX_STACK - it.getAmount();
                if (r <= 0) continue;
                int put2 = (int) Math.min(r, left);
                it.setAmount(it.getAmount() + put2);
                left -= put2;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return (int) Math.max(0L, left);
    }

    /** 输出槽里能同时容纳多少<b>种</b>产物 = 空槽数 + 已经出现在槽里的种类数。 */
    public static int kindCapacity(Inventory inv, int[] slots, List<Kind> kinds) {
        int n = 0;
        try {
            n = emptySlotCount(inv, slots);
            if (kinds == null) return n;
            for (Kind k : kinds) {
                for (int slot : slots) {
                    ItemStack it = inv.getItem(slot);
                    if (it != null && !it.getType().isAir() && sameItem(it, k.stack)) {
                        n++;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return n;
    }

    /** 一个槽的最大堆叠数（原脚本写死 64）。 */
    public static final int MAX_STACK = 64;

    // ==================================================================
    // 对外：物品比对与显示名
    // ==================================================================

    /** 只看单个物品是否相同（先比材质，再比 {@code isSimilar}，最后兜底比显示名）。 */
    public static boolean sameItem(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) return false;
            if (a.getType() != b.getType()) return false;
            ItemMeta ia = a.getItemMeta();
            ItemMeta ib = b.getItemMeta();
            if (ia == null || ib == null) return true;
            try {
                return a.isSimilar(b);
            } catch (Throwable ignored) {
                // 落到显示名兜底
            }
            return plainName(a).equals(plainName(b));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 去色后的显示名（没有显示名返回空串）。 */
    public static String plainName(ItemStack stack) {
        try {
            ItemStack it = stack;
            if (it == null || it.getType().isAir()) return "";
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return "";
            Component comp = meta.displayName();
            if (comp == null) return "";
            String raw = LegacyComponentSerializer.legacySection().serialize(comp);
            return Colors.strip(raw);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================================================================
    // 配方归一化
    // ==================================================================

    /** 任意形状的配方 → 规范对象（对外暴露，供生成器复用）。 */
    public static Recipe normalize(Object rec) {
        if (rec == null) return null;
        try {
            if (rec instanceof TTRecipe tt) return normalizeTT(tt);
            return normalizeGeneric(rec);
        } catch (Throwable t) {
            TT.log("[堆叠机] 归一化配方失败: " + t);
            return null;
        }
    }

    /**
     * 「精确通道」：本项目自己的 {@link TTRecipe}。
     *
     * <p>它能表达 RSC 时代 {@code CustomMachineRecipe} 才有的信息 ——
     * 逐项 {@code chance}（{@code outputChance(i)}）与 {@code noConsume}（{@code isNoConsume(i)}）。
     * 原脚本那条 RSC 通道在独立化后没有对应类，这里由 {@link TTRecipe} 顶上：
     * <b>语义完全一致，只是承载类换成了本项目自己的配方对象</b>。</p>
     */
    private static Recipe normalizeTT(TTRecipe tt) {
        Recipe out = new Recipe();
        out.ticks = Math.max(1, tt.getTicks());
        out.chooseOne = tt.isChooseOne();

        ItemStack[] ins = tt.getInput();
        if (ins != null) {
            for (int i = 0; i < ins.length; i++) {
                ItemStack st = ins[i];
                if (st == null || st.getType().isAir()) continue;
                int need = Math.max(1, st.getAmount());
                // 原 RSC 通道读的是 ItemWrapper#getConsumeAmount()；
                // TTRecipe 用 noConsume 布尔表达同一语义：不消耗 → consume = 0
                int consume = tt.isNoConsume(i) ? 0 : need;
                out.inputs.add(new Input(st.clone(), need, consume));
            }
        }
        ItemStack[] outs = tt.getOutput();
        if (outs != null) {
            for (int j = 0; j < outs.length; j++) {
                ItemStack st = outs[j];
                if (st == null || st.getType().isAir()) continue;
                double ch = tt.outputChance(j);
                if (ch <= 0.0) continue;       // 概率 0 = 不产出
                out.outputs.add(new Output(st.clone(), Math.max(1, st.getAmount()), ch));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 「通用通道」：老粘液 {@link MachineRecipe} 及其所有变体。
     *
     * <p>用户点名的那类机器（无尽贪婪磨粉机、逻辑工艺堆叠机、MagicExpansion…）都是这个形状。
     * 读取原则：</p>
     * <ol>
     *   <li>属性名给<b>多个候选</b>（{@code getInput}/{@code getInputs}/{@code getRecipeInput}…），
     *       先按无参方法找、再按同名字段找；</li>
     *   <li>集合类型不限：数组 / {@code List} / {@code Map} / 迭代器 / 包装类都收；</li>
     *   <li>概率的来源优先级：<b>包装类自带 &gt; 外部 {@code chances} 数组 &gt; 100</b>
     *       （老 {@code MachineRecipe} 没有概率概念 = 必出）。</li>
     * </ol>
     */
    private static Recipe normalizeGeneric(Object raw) {
        Recipe out = new Recipe();
        out.fromGeneric = true;

        // ① 耗时
        Object t = firstOf(raw, "getTicks", "getProcessingTime", "getTime", "getDuration",
                "getSecond", "getSpeed");
        if (t instanceof Number n) {
            int tn = (int) Math.floor(n.doubleValue());
            if (tn > 0) out.ticks = tn;
        }
        // ② 模板机（只当模具）
        Object ts = firstOf(raw, "getTemplateStack", "getTemplate", "getMould");
        if (ts instanceof ItemStack tpl && !tpl.getType().isAir()) {
            out.template = tpl;
            Object tsl = firstOf(raw, "getTemplateSlot");
            if (tsl instanceof Number n2) out.templateSlot = (int) Math.floor(n2.doubleValue());
        }
        // ③ chooseOne
        if (Boolean.TRUE.equals(firstOf(raw, "isChooseOne", "isChooseOneIfHas"))) {
            out.chooseOne = true;
        }
        // ④ noConsume 的输入下标（逻辑工艺 StackMachineRecipe 的「不消耗输入」）
        Set<Integer> noConsume = toIntSet(firstOf(raw, "getNoConsumeInputs", "getNoConsumedInputs",
                "getUnconsumedInputs", "getNoConsume", "getNoConsumeList"));

        // ⑤ 输入
        List<Object> ins = toList(firstOf(raw, "getInput", "getInputs", "getRecipeInput",
                "getInputArray", "getIn"));
        for (int i = 0; i < ins.size(); i++) {
            Object orig = ins.get(i);
            ItemStack st = stackOfEntry(orig);
            if (st == null || st.getType().isAir()) continue;
            int need;
            if (st == orig) {
                need = Math.max(1, st.getAmount());        // 元素本身就是 ItemStack → 需求 = 堆叠数
            } else {
                need = numOf(firstOf(orig, "getAmount"), 1);
            }
            int consume = need;
            Object ca = firstOf(orig, "getConsumeAmount", "getUseAmount");
            if (ca instanceof Number n3) consume = Math.max(0, (int) Math.floor(n3.doubleValue()));
            if (noConsume.contains(i)) consume = 0;
            out.inputs.add(new Input(st.clone(), need, consume));
        }

        // ⑥ 输出（多产物 + 各自 amount / chance）
        List<Object> outs = toList(firstOf(raw, "getOutput", "getOutputs", "getRecipeOutput",
                "getOutputArray", "getOut"));
        List<Object> chances = toList(firstOf(raw, "getChances", "getOutputChances",
                "getProbabilities", "getRates"));
        for (int j = 0; j < outs.size(); j++) {
            Object ow0 = outs.get(j);
            if (ow0 == null) continue;
            long amount = 1L;
            Double chance = null;
            Object ow = ow0;
            if (ow0 instanceof Map.Entry<?, ?> e) {          // Map 条目：key = 物品，value = 数量
                if (e.getValue() instanceof Number vn) {
                    amount = Math.max(1L, (long) Math.floor(vn.doubleValue()));
                }
                ow = e.getKey();
            }
            if (ow == null) continue;
            // ★ 只有「包装类」（不是 ItemStack 本身）才读它自己的 amount / chance —— 顺序要紧：
            //   ItemStack 自己的 getAmount() 是堆叠数量，不能反过来覆盖 Map/包装里给的产出数量
            boolean rawIsStack = ow instanceof ItemStack;
            if (!rawIsStack) {
                Object wa = firstOf(ow, "getAmount");
                if (wa instanceof Number wn) amount = numOfLong(wa, amount, wn);
                Object wc = firstOf(ow, "getChance", "getProbability", "getRate");
                if (wc instanceof Number cn) chance = cn.doubleValue();
                Object os = firstOf(ow, "getStack", "getItemStack", "getItem");
                if (os != null) ow = os;
            }
            if (!(ow instanceof ItemStack ois) || ois.getType().isAir()) continue;
            // ItemStack 自身的堆叠数作为「至少这么多」
            if (ois.getAmount() > 0) amount = Math.max(amount, ois.getAmount());
            if (chance == null && j < chances.size()) {
                Object c = chances.get(j);
                if (c instanceof Map.Entry<?, ?> ce) c = ce.getValue();
                if (c instanceof Number cn2) chance = cn2.doubleValue();
            }
            if (chance == null || Double.isNaN(chance)) chance = 100.0;
            if (chance <= 0.0) continue;                     // 概率 0 = 不产出
            long amt = Math.max(1L, amount);
            out.outputs.add(new Output(ois.clone(), (int) Math.min(Integer.MAX_VALUE, amt), chance));
        }

        return out.isEmpty() ? null : out;
    }

    // ==================================================================
    // 原始配方列表读取
    // ==================================================================

    /**
     * 从机器物品拿「原始配方列表」。
     *
     * <p>先试 {@code AContainer#getMachineRecipes()}，再反射常见字段名
     * （{@code recipes} / {@code machineRecipes} / …）。本项目的 {@code TTRecipeMachine}
     * 用的就是 {@code protected final List<TTRecipe> recipes}，走字段那条路。</p>
     */
    private static Object rawRecipesOf(SlimefunItem sf) {
        try {
            if (sf == null) return null;
            Object m = call0(sf, "getMachineRecipes");
            if (m != null && sizeOf(m) > 0) return m;
            for (String name : new String[]{"recipes", "machineRecipes", "recipeList", "recipeMap",
                    "recipesMap", "craftingRecipes", "craftRecipes", "recipeEntries"}) {
                Object v = fieldVal(sf, name);
                if (v != null && sizeOf(v) > 0) return v;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 配方容器的元素个数（集合 / Map / 数组都行）。 */
    private static int rawCount(Object raw) {
        return sizeOf(raw);
    }

    /** 取第 i 条配方（List 用 {@code get}，Map 用 {@code values()} 的顺序）。 */
    private static Object rawAt(Object raw, int i) {
        try {
            if (raw == null) return null;
            if (raw instanceof Map<?, ?> m) {
                int k = 0;
                for (Object v : m.values()) {
                    if (k == i) return v;
                    k++;
                }
                return null;
            }
            if (raw instanceof List<?> l) return l.get(i);
            if (raw.getClass().isArray()) return Array.get(raw, i);
            return call1(raw, "get", i);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int sizeOf(Object raw) {
        try {
            if (raw == null) return 0;
            if (raw instanceof Collection<?> c) return c.size();
            if (raw instanceof Map<?, ?> m) return m.size();
            if (raw.getClass().isArray()) return Array.getLength(raw);
        } catch (Throwable ignored) {
            // 忽略
        }
        return 0;
    }

    // ==================================================================
    // 类型转换 / 反射工具
    // ==================================================================

    /**
     * 任意集合 → {@code List<Object>}。
     *
     * <p>对应原脚本 {@code pdIterToArray}。{@code Map} 转成 {@code Map.Entry} 列表
     * （原脚本转成 {@code {key, value}} 对象，语义一致）。</p>
     */
    public static List<Object> toList(Object v) {
        List<Object> out = new ArrayList<>();
        try {
            if (v == null) return out;
            if (v instanceof Map<?, ?> m) {
                out.addAll(m.entrySet());
                return out;
            }
            if (v.getClass().isArray()) {
                int n = Array.getLength(v);
                for (int i = 0; i < n; i++) out.add(Array.get(v, i));
                return out;
            }
            if (v instanceof Collection<?> c) {
                out.addAll(c);
                return out;
            }
            if (v instanceof Iterable<?> it) {
                for (Object o : it) out.add(o);
                return out;
            }
            if (v instanceof Iterator<?> it) {
                while (it.hasNext()) out.add(it.next());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 元素 → {@code ItemStack}（元素本身是 ItemStack、或 Map 条目的 key、或包装类的 stack）。 */
    private static ItemStack stackOfEntry(Object o) {
        try {
            if (o == null) return null;
            if (o instanceof ItemStack is) return is;
            if (o instanceof Map.Entry<?, ?> e) {
                return (e.getKey() instanceof ItemStack k) ? k : null;
            }
            Object v = firstOf(o, "getStack", "getItemStack", "getItem");
            return (v instanceof ItemStack is2) ? is2 : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 任意集合 → 整数集合（{@code noConsume} 的下标表）。 */
    private static Set<Integer> toIntSet(Object v) {
        Set<Integer> out = new HashSet<>();
        try {
            for (Object o : toList(v)) {
                if (o instanceof Number n) out.add((int) Math.floor(n.doubleValue()));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** {@code Number} → 正整数；非正 / 非数返回默认值。 */
    private static int numOf(Object v, int dflt) {
        try {
            if (v instanceof Number n) {
                int x = (int) Math.floor(n.doubleValue());
                return x <= 0 ? dflt : x;
            }
            if (v != null) {
                int x = (int) Math.floor(Double.parseDouble(String.valueOf(v)));
                return x <= 0 ? dflt : x;
            }
        } catch (Throwable ignored) {
            // 落到默认值
        }
        return dflt;
    }

    private static long numOfLong(Object v, long dflt, Number fallback) {
        try {
            if (v instanceof Number n) {
                long x = (long) Math.floor(n.doubleValue());
                return x <= 0 ? dflt : x;
            }
        } catch (Throwable ignored) {
            // 落到默认值
        }
        return dflt;
    }

    /**
     * 按多个候选名字取属性：<b>先试无参方法、再试同名字段</b>。
     *
     * <p>对应原脚本 {@code pdGf}。这是整个通用通道能「吃下各种形状的配方」的关键 ——
     * 不同附属给同一个概念起了不同名字（{@code getInput} / {@code getInputs} /
     * {@code getRecipeInput}…），只能挨个试。</p>
     */
    private static Object firstOf(Object obj, String... names) {
        if (obj == null) return null;
        for (String n : names) {
            Object v = call0(obj, n);
            if (v != null) return v;
        }
        for (String n : names) {
            Object v = fieldVal(obj, n);
            if (v != null) return v;
        }
        return null;
    }

    /** 调无参方法（逐层向上找 declared，最后退 public）。 */
    private static Object call0(Object obj, String name) {
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (m.getParameterCount() != 0) continue;
                    m.setAccessible(true);
                    return m.invoke(obj);
                } catch (Throwable ignored) {
                    // 继续向上
                }
            }
            Method m = obj.getClass().getMethod(name);
            if (m.getParameterCount() != 0) return null;
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调单参方法（{@code get(int)} 之类）。 */
    private static Object call1(Object obj, String name, Object arg) {
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                    m.setAccessible(true);
                    return m.invoke(obj, arg);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 读字段（逐层向上，最多 8 层 —— 与原脚本 {@code pdGenField} 的 guard 一致）。 */
    private static Object fieldVal(Object obj, String name) {
        try {
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

    /** 供其它类复用：材质是否为空（含 CAVE_AIR / VOID_AIR）。 */
    public static boolean isAir(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getType().isAir();
    }
}
