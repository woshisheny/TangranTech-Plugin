package com.tangran.tangrantech.native_scripts;

import com.tangran.tangrantech.TT;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import com.tangran.tangrantech.native_scripts.daofa.AscensionPathScript;
import com.tangran.tangrantech.native_scripts.daofa.AutoTaixuHubScript;
import com.tangran.tangrantech.native_scripts.daofa.ClearChallengeAreaScript;
import com.tangran.tangrantech.native_scripts.daofa.DivineCrystalScript;
import com.tangran.tangrantech.native_scripts.daofa.EquipmentScript;
import com.tangran.tangrantech.native_scripts.daofa.FinalCraftingCompilerScript;
import com.tangran.tangrantech.native_scripts.daofa.FinalCraftingTableScript;
import com.tangran.tangrantech.native_scripts.daofa.FullAnalysisScript;
import com.tangran.tangrantech.native_scripts.daofa.HeavenCurrencyScript;
import com.tangran.tangrantech.native_scripts.daofa.LargeStructureFillScript;
import com.tangran.tangrantech.native_scripts.daofa.LifeshopScript;
import com.tangran.tangrantech.native_scripts.daofa.PillsScript;
import com.tangran.tangrantech.native_scripts.daofa.QuestsScript;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;
import com.tangran.tangrantech.native_scripts.daofa.StackMachineScript;
import com.tangran.tangrantech.native_scripts.daofa.StorageSpaceScript;
import com.tangran.tangrantech.native_scripts.daofa.StorageSpaceUnlockScript;
import com.tangran.tangrantech.native_scripts.daofa.YanhuoSelectionScript;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleScript;

/**
 * 原生脚本注册表：脚本名 → Java 单例。
 *
 * <p>对齐 Komutech 的 {@code NativeScriptRegistry}：原 RSC 版每个 {@code scripts/*.js}
 * 在加载期被求值一次；原生化后改为在此登记 Java 实现，按脚本名（yml 里
 * {@code script:} 的原样字符串）索引。</p>
 *
 * <p>尚未原生化的脚本不登记，对应条目按「无脚本」注册（物品仍可正常合成/显示），
 * 加载结束时 {@link #reportUnregistered()} 汇总提示。</p>
 */
public final class NativeScriptRegistry {

    private static final Map<String, NativeScript> SCRIPTS = new LinkedHashMap<>();
    /** 登记过的脚本名，用于启动后比对 yml 里实际用到的脚本。 */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private static boolean bootstrapped;

    private NativeScriptRegistry() {}

    /** 在解析任何 script: 字段之前调用一次；幂等。 */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        registerBuiltins();
    }

    /** 登记脚本实现；重复登记同一名字会被后者覆盖并告警。 */
    public static void register(String id, NativeScript script) {
        if (id == null || id.isBlank() || script == null) return;
        if (SCRIPTS.put(id, script) != null) {
            TT.log("脚本重复登记，已覆盖: " + id);
        }
        REGISTERED.add(id);
    }

    /** 取脚本实例；未登记返回 null。 */
    @Nullable
    public static NativeScript get(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        return SCRIPTS.get(id.trim());
    }

    /** 已登记的脚本名（不可修改视图）。 */
    public static Set<String> registered() {
        return Collections.unmodifiableSet(REGISTERED);
    }

    /**
     * 调用于所有内容注册完成之后：为每个脚本注册其运行期监听器。
     *
     * <p><b>历史坑（2026-10 修复）</b>：原实现只调用 {@link NativeScript#registerListeners}，
     * 而 31 个脚本实现的是 {@link NativeLifecycleScript#listeners()}——
     * 后者唯一的上链路径是 {@code ScriptEval#doInit → NativeScriptEval.evalFunction("init")
     * → NativeLifecycle.registerLifecycleOnce}，但 {@code doInit()} 与 {@code ScriptLoader.load()}
     * 全库无调用方（RSC 时代的「求值触发 init」链在原生版被弃用后没有补接线），
     * 导致这些脚本的监听器<b>从未注册</b>：老吴召唤器、境界糖之力循环、装备三件套、
     * BOSS战全部事件、堆叠机界面防护等大量玩法静默失效，且日志无任何报错。</p>
     *
     * <p>现在统一在这里补登：{@code registerListeners}（5 个脚本的自家初始化）与
     * {@code listeners()}（34 处事件监听器，经 {@link NativeLifecycle} 幂等去重）都走本方法。</p>
     */
    public static void registerLifecycleListeners(@Nullable Plugin plugin) {
        for (Map.Entry<String, NativeScript> e : SCRIPTS.entrySet()) {
            NativeScript script = e.getValue();
            try {
                // ① 脚本自家初始化（破法弓任务 / EquipmentBridge 注册 / 灵圃自动收获等）
                script.registerListeners(plugin);
                // ② 事件监听器：NativeLifecycleScript 的 listeners() 经幂等注册器登记
                //    （同一脚本被多条 yml 引用也只注册一次）
                if (script instanceof NativeLifecycleScript lifecycle) {
                    NativeLifecycle.registerLifecycleOnce(lifecycle, plugin);
                }
            } catch (Throwable t) {
                TT.log("脚本 " + e.getKey() + " 注册监听器失败: " + t);
            }
        }
        try {
            Bukkit.getPluginManager().registerEvents(new ScriptItemListener(), plugin);
        } catch (Throwable t) {
            TT.log("ScriptItemListener 注册失败: " + t);
        }
    }

    /** 插件卸载时清理运行期资源。 */
    public static void shutdown() {
        NativeLifecycle.reset();
    }

    /** 登记全部已原生化的脚本实现。 */
    private static void registerBuiltins() {
        // 分批原生化：已完成的脚本在此登记。
        // 尚未原生化的脚本名见 ScriptIds，加载结束由 reportUnregistered() 汇总提示。
        register(ScriptIds.TANGRAN_BASIC_MATTER, new com.tangran.tangrantech.native_scripts.item.TangranBasicMatterScript());
        register(ScriptIds.SACCHARIN, new com.tangran.tangrantech.native_scripts.item.SaccharinScript());
        register(ScriptIds.HAQI_COLLECTOR, new com.tangran.tangrantech.native_scripts.item.HaqiCollectorScript());
        register(ScriptIds.INJECTION, new com.tangran.tangrantech.native_scripts.item.InjectionScript());
        register(ScriptIds.RAW_SUGAR_WATER, new com.tangran.tangrantech.native_scripts.item.RawSugarWaterScript());
        register(ScriptIds.TREASURE_SIMULATOR, new com.tangran.tangrantech.native_scripts.item.TreasureSimulatorScript());
        register(ScriptIds.STORAGE_SPACE_OPEN, new com.tangran.tangrantech.native_scripts.daofa.StorageSpaceUnlockScript());
        register(ScriptIds.TAIXU_HUB, new com.tangran.tangrantech.native_scripts.item.TaixuHubScript());
        register(ScriptIds.QIAOLEZI_COOLANT, new com.tangran.tangrantech.native_scripts.item.QiaoleziCoolantScript());
        register(ScriptIds.FIRST_CHAPTER_ALTAR, new com.tangran.tangrantech.native_scripts.item.FirstChapterAltarScript());
        register(ScriptIds.PRIMORDIAL_MARK, new com.tangran.tangrantech.native_scripts.item.PrimordialMarkScript());
        register(ScriptIds.TANGTIAN_HALBERD, new com.tangran.tangrantech.native_scripts.item.TangtianHalberdScript());
        register(ScriptIds.LETHAL_SUGAR_WATER, new com.tangran.tangrantech.native_scripts.item.LethalSugarWaterScript());
        register(ScriptIds.LAOWU_SUMMONER, new com.tangran.tangrantech.native_scripts.item.LaowuSummonerScript());
        register(ScriptIds.AUTO_TAIXU_HUB, new com.tangran.tangrantech.native_scripts.daofa.AutoTaixuHubScript());
        register(ScriptIds.FULL_ANALYSIS, new com.tangran.tangrantech.native_scripts.daofa.FullAnalysisScript());
        register(ScriptIds.ASCENSION_PATH, new com.tangran.tangrantech.native_scripts.daofa.AscensionPathScript());
        register(ScriptIds.SPIRIT_CRYSTAL, new com.tangran.tangrantech.native_scripts.daofa.DivineCrystalScript());
        register(ScriptIds.LARGE_STRUCTURE_FILL, new com.tangran.tangrantech.native_scripts.daofa.LargeStructureFillScript());
        register(ScriptIds.FINAL_CRAFTING_COMPILER, new com.tangran.tangrantech.native_scripts.daofa.FinalCraftingCompilerScript());
        register(ScriptIds.HEAVEN_CURRENCY_EXCHANGE, new com.tangran.tangrantech.native_scripts.daofa.HeavenCurrencyScript());
        register(ScriptIds.REALM, new com.tangran.tangrantech.native_scripts.daofa.RealmScript());
        register(ScriptIds.FINAL_CRAFTING_TABLE, new com.tangran.tangrantech.native_scripts.daofa.FinalCraftingTableScript());
        register(ScriptIds.EXOTIC_FLAME_CHOICE, new com.tangran.tangrantech.native_scripts.daofa.YanhuoSelectionScript());
        register(ScriptIds.QUESTS, new com.tangran.tangrantech.native_scripts.daofa.QuestsScript());
        register(ScriptIds.PILLS, new com.tangran.tangrantech.native_scripts.daofa.PillsScript());
        register(ScriptIds.STORAGE_SPACE, new com.tangran.tangrantech.native_scripts.daofa.StorageSpaceScript());
        register(ScriptIds.FLOATING_PAWN_SHOP, new com.tangran.tangrantech.native_scripts.daofa.LifeshopScript());
        register(ScriptIds.EQUIPMENT, new com.tangran.tangrantech.native_scripts.daofa.EquipmentScript());
        register(ScriptIds.CLEAR_CHALLENGE_AREA, new com.tangran.tangrantech.native_scripts.daofa.ClearChallengeAreaScript());
        register(ScriptIds.STACK_MACHINE, new com.tangran.tangrantech.native_scripts.daofa.StackMachineScript());
        register(ScriptIds.BOSS_BATTLE, new com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleScript());
    }

    /**
     * 加载收尾：汇总「yml 里引用了但尚未原生化」的脚本名，便于按批推进。
     * 由 {@code Setup#loadAll} 在 MissingItems.report() 之后调用。
     */
    public static void reportUnregistered() {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (String file : new String[]{"items.yml", "machines.yml"}) {
            org.bukkit.configuration.file.YamlConfiguration y =
                    com.tangran.tangrantech.load.Yaml.loadResource(TT.plugin, file);
            for (String id : y.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection s = y.getConfigurationSection(id);
                if (s == null) continue;
                String sc = s.getString("script");
                if (sc != null && !sc.isBlank()) used.add(sc.trim());
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String u : used) if (get(u) == null) missing.add(u);
        if (!missing.isEmpty()) {
            TT.log("以下脚本尚未原生化（对应物品已注册但暂无行为）: " + String.join(", ", missing));
        }
    }
}
