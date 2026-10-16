package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineNet;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineRecipe;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineScript;

/**
 * 「糖然牌堆叠机」的一个工作周期（原脚本 {@code pdRunWorkCycle}）。
 *
 * <h2>流程</h2>
 * <pre>
 *   选一条能跑的配方
 *     → 输入：先<b>全都确认够</b>，再按 consume 量扣（noConsume 的只检查不扣）
 *     → 产出：按 chance 掷点，得到「本周期实际产出哪些、各多少个」
 *     → 入网：网络里<b>已经有</b>这种产物的，全额入网 + 把输出槽里的同类残留也一起清进去
 *     → 落槽：剩下的按「总槽位容量均分」限流产进输出槽，塞不下的记为 left（不丢给空气）
 * </pre>
 *
 * <h2>四条刻在注释里的血泪教训</h2>
 * <ol>
 *   <li><b>输入必须「先全查、再全扣」</b>，不能查一条扣一条：否则第一条扣完才发现第二条不够，
 *       玩家会看到「材料凭空少了一半、什么都没产出」。原脚本就是这个顺序。</li>
 *   <li><b>清入网残留要按「本机布局」的输出槽扫</b>，不能硬编码配方布局的后五列。
 *       用户实测：生成器有 45 格输出槽，但老代码按配方布局的 30 格扫
 *       ⇒ 前四列那几格里同类的残留物<b>永远不会</b>被抓进网络。</li>
 *   <li><b>不能按 {@code !kind.net} 过滤落槽产物</b>：网络装不下时那种产物还有剩余，
 *       必须给它落输出槽，否则凭空消失。</li>
 *   <li><b>种类数超上限时只保留前 N 种，机器照常跑</b>。
 *       一个槽只能放一种产物 ⇒ 能同时容纳的种数 = 空槽数 + 已在槽里的种数。
 *       产物种数超过它时（例：未接网生成器 45 个输出槽、配方却有 50 种产物）只产前 N 种，
 *       后面的不产进槽 —— <b>机器绝不停摆</b>。这一点逻辑工艺没考虑，种类多了会卡住不产出。</li>
 * </ol>
 */
public final class StackMachineWork {

    private StackMachineWork() {}

    /** 初始堆叠效率（{@code machines.yml} 的 lore 第 1 行写的就是它）。 */
    public static final double DEFAULT_EFFICIENCY = 2.0;

    /** 一次工作周期的结果。 */
    public static final class Result {
        public boolean ok;
        /** 没跑起来的原因（{@code noinv} / {@code norecipe} / {@code template} / {@code input}…）。 */
        public String reason = "";
        /** 进网络的量。 */
        public long toNet;
        /** 落输出槽的量。 */
        public long toSlot;
        /** 槽位塞不下、本周期没地方放的数量。 */
        public long left;
        /** 因「种类数超过槽位能容纳的种数」而被丢弃的产物种数。 */
        public int droppedKinds;
    }

    /**
     * 跑一个工作周期。
     *
     * @param count         本机堆叠的机器台数
     * @param selectedIndex 玩家固定选择的配方下标；null = 自动检测
     */
    public static Result run(Location loc, String type, String machineId, long count,
                             Inventory inv, List<StackMachineRecipe.Recipe> recipes,
                             Integer selectedIndex) {
        return run(loc, type, machineId, count, inv, recipes, selectedIndex, null);
    }

    /**
     * 跑一个工作周期（性能优化重载）。
     *
     * <p>{@code pre} 不为 null 时直接复用调用方（tickBlock）已挑好的配方，
     * 省掉每 tick 第二次全输入槽扫描 + meta 克隆的 {@code pick}。</p>
     */
    public static Result run(Location loc, String type, String machineId, long count,
                             Inventory inv, List<StackMachineRecipe.Recipe> recipes,
                             Integer selectedIndex, StackMachineRecipe.Recipe pre) {
        Result res = new Result();
        try {
            if (inv == null) {
                res.reason = "noinv";
                return res;
            }
            StackMachineRecipe.Recipe rec = (pre != null) ? pre
                    : StackMachineRecipe.pick(recipes, inv, selectedIndex, type, loc);
            if (rec == null) {
                res.reason = "norecipe";
                return res;
            }
            long parallel = (long) Math.floor(count * DEFAULT_EFFICIENCY);
            if (parallel <= 0L) {
                res.reason = "noparallel";
                return res;
            }
            // 模板机器的模板：在配置界面第三行持有即可，不消耗（生成器可以有多个模板，需全在）
            if (!StackMachineRecipe.templatesAllPresent(loc, rec)) {
                res.reason = "template";
                return res;
            }

            // ① 输入：先确认都够，再按 consume 量扣。
            //    量都要乘并行数：need 是「每台机器一次」的量 ⇒ 本周期需要 need × 并行。
            //    ★ 生成器（matgen）没有输入槽：它配方里的「输入」是模板，上面已查过 → 这里不扣任何东西
            if (!rec.inputsAreTemplates) {
                for (StackMachineRecipe.Input in : rec.inputs) {
                    long needAll = Math.max(1L, (long) Math.floor(in.need * (double) parallel));
                    in.needAll = needAll;
                    in.takeAll = (long) Math.floor(in.consume * (double) parallel);
                    if (StackMachineRecipe.countInput(inv, in.stack, needAll) < needAll) {
                        res.reason = "input";
                        return res;
                    }
                }
                for (StackMachineRecipe.Input in : rec.inputs) {
                    if (in.takeAll > 0L) {
                        StackMachineRecipe.takeInput(inv, in.stack, in.takeAll, true);
                    } else {
                        // noConsume：只检查不扣
                        StackMachineRecipe.takeInput(inv, in.stack, in.needAll, false);
                    }
                }
            }

            // ② 产出（含 chance）
            List<StackMachineRecipe.Kind> kinds = new ArrayList<>();
            for (StackMachineRecipe.Output o : rec.outputs) {
                long n = StackMachineRecipe.rollChance((long) o.amount * parallel, o.chance);
                if (n > 0L) kinds.add(new StackMachineRecipe.Kind(o.stack, n));
            }
            if (kinds.isEmpty()) {
                res.ok = true;
                res.reason = "nooutput";
                return res;
            }

            // ③ 网络：网络里「已经有」的产物全额入网，并且把输出槽里的残留一起送进网络
            Object root = StackMachineNet.networkRoot(loc);
            int[] clearSlots = StackMachineScript.outputSlotsOf(type);
            for (StackMachineRecipe.Kind kind : kinds) {
                boolean hasIt = StackMachineNet.netAmount(root, kind.stack) > 0L;
                kind.net = hasIt;
                if (!hasIt) continue;
                // 槽位残留先清进网络（清完就不再出现在堆叠槽里）
                for (int slot : clearSlots) {
                    ItemStack it = inv.getItem(slot);
                    if (it == null || !StackMachineRecipe.sameItem(it, kind.stack)) continue;
                    long amt = it.getAmount();
                    long moved = StackMachineNet.pushNet(loc, root, it, amt);
                    if (moved > 0L) res.toNet += moved;
                    if (moved >= amt) {
                        inv.setItem(slot, null);
                    } else if (moved > 0L) {
                        it.setAmount((int) (amt - moved));
                    }
                }
                long sent = StackMachineNet.pushNet(loc, root, kind.stack, kind.count);
                res.toNet += sent;
                kind.count -= sent;
            }

            // ④ 剩下的只能进槽位 → 按「总槽位容量均分」限流
            int[] outSlots = StackMachineScript.outputSlotsOf(type);
            List<StackMachineRecipe.Kind> slotKinds = new ArrayList<>();
            for (StackMachineRecipe.Kind k : kinds) {
                if (k.count > 0L) slotKinds.add(k);
            }
            // ★ 种类上限：一个槽只能放一种产物 ⇒ 能同时容纳的「种数」= 空槽数 + 已在槽里的种数。
            //   超过它时只保留前 N 种，后面的不产进槽 —— 机器照常工作、绝不停摆。
            int kindCap = StackMachineRecipe.kindCapacity(inv, outSlots, slotKinds);
            if (slotKinds.size() > kindCap) {
                res.droppedKinds = slotKinds.size() - kindCap;
                slotKinds = new ArrayList<>(slotKinds.subList(0, Math.max(0, kindCap)));
            }
            if (!slotKinds.isEmpty()) {
                int n = slotKinds.size();
                long[] desired = new long[n];
                int[] partial = new int[n];
                for (int d = 0; d < n; d++) {
                    desired[d] = slotKinds.get(d).count;
                    partial[d] = StackMachineRecipe.slotRoomFor(inv, slotKinds.get(d).stack, outSlots);
                }
                // ① 用户口径：总容量（空槽 + 零散空间）均分给所有产物
                int freeSlots = StackMachineRecipe.emptySlotCount(inv, outSlots);
                long totalRoom = (long) freeSlots * StackMachineRecipe.MAX_STACK;
                for (int p : partial) totalRoom += p;
                long[] byItem = StackMachineRecipe.allocate(desired, totalRoom);
                // ② 真实可行性：按「槽」均分（一个槽只能放一种产物，摆不下就白产）
                long[] bySlot = StackMachineRecipe.allocateSlots(desired, freeSlots, partial);
                for (int a = 0; a < n; a++) {
                    long allocN = Math.min(byItem[a], bySlot[a]);
                    int put = StackMachineRecipe.putOutput(inv, slotKinds.get(a).stack, allocN, outSlots);
                    res.toSlot += (allocN - put);
                    res.left += put;
                }
            }
            res.ok = true;
        } catch (Throwable t) {
            res.reason = "error:" + t;
            TT.log("[堆叠机] 工作周期异常: " + t);
        }
        return res;
    }
}
