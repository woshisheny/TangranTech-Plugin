package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 「糖然牌堆叠机」的机器识别层：判定一台机器属于三类中的哪一类。
 *
 * <p>对应 {@code scripts/道法/堆叠机.js} 的 {@code pdLoadFamilies} / {@code pdBuildServerMap} /
 * {@code pdClassifyServer} / {@code pdIdentifyType} 一族。</p>
 *
 * <h2>三个类型</h2>
 * <table border="1">
 *   <caption>机器类型</caption>
 *   <tr><th>类型</th><th>含义</th><th>来源</th></tr>
 *   <tr><td>{@link #TYPE_RECIPE}</td><td>配方机器：需要输入才出成品</td>
 *       <td>{@code recipe_machines.yml} / {@code super_multi_block_machines.yml} / {@code template_machines.yml}</td></tr>
 *   <tr><td>{@link #TYPE_MATGEN}</td><td>生成器：无输入即可产出</td><td>{@code mat_generators.yml}</td></tr>
 *   <tr><td>{@link #TYPE_GEN}</td><td>发电机</td><td>{@code generators.yml}</td></tr>
 * </table>
 *
 * <h2>识别来源三层（优先级从上到下）</h2>
 * <ol>
 *   <li><b>RSC 家族 yml</b>（{@link #FAMILY_MAP}）—— 扫<b>所有附属</b>的
 *       {@code addons/}<b>*</b>{@code /} 下那 5 个家族 yml 的**顶层 id**（RSC 注册 id = yml 键转大写）。
 *       最精确，先到先得（配方机器优先）。</li>
 *   <li><b>其它附属的机器</b>（{@link #SERVER_MAP}）—— 两层：
 *       先读 <b>逻辑工艺（LogiTech）</b>已经算好的识别结果
 *       （{@code RecipeSupporter.STACKMACHINE_LIST} / {@code STACKMGENERATOR_LIST}，
 *       <b>它就是扫全服粘液物品得出的表</b>，直接读等于与逻辑工艺识别完全一致）；
 *       再叠一层<b>通用扫描</b>兜住逻辑工艺漏掉的 / 没装它的场合。</li>
 *   <li><b>当场深挖</b>（{@link #classify} 的 {@code deep} 模式）—— 表里没有的新注册机器，
 *       按类现场判这一台（含反射找 {@code recipes} 等字段）。</li>
 * </ol>
 *
 * <h2>通用分类规则（不依赖任何附属）</h2>
 * <ul>
 *   <li>实现 {@link EnergyNetComponent} 且 {@code getEnergyComponentType() == GENERATOR} → 发电机；</li>
 *   <li>能拿到配方列表（{@code getMachineRecipes()} 或反射 {@code recipes} / {@code machineRecipes}
 *       等字段）→ 看配方<b>有没有输入</b>：有输入 = 配方机器，无输入 = 生成器；</li>
 *   <li>都没有 → 识别失败（返回 null，堆叠机保持「未识别」状态）。</li>
 * </ul>
 *
 * <h2>可选依赖：LogiTech 与 NetworksExpansion</h2>
 * <p>原脚本用 {@code Java.type(...)} + {@code try/catch} 做「有就用、没有就跳过」。
 * Java 编译期没有这两个 jar，因此这里全部走<b>反射</b>，并且失败一次就记住「不可用」，
 * 不再反复试 —— 不装这两个插件时识别仍然工作（只是少了逻辑工艺那张表和量子存储读取）。</p>
 */
public final class StackMachineRegistry {

    private StackMachineRegistry() {}

    // ==================================================================
    // 类型
    // ==================================================================

    /** 配方机器：需要输入才出成品。 */
    public static final String TYPE_RECIPE = "recipe";
    /** 生成器：无输入即可产出。 */
    public static final String TYPE_MATGEN = "matgen";
    /** 发电机。 */
    public static final String TYPE_GEN = "generator";

    /** 类型 → 中文标签。 */
    public static String typeLabel(String type) {
        if (TYPE_RECIPE.equals(type)) return "配方机器";
        if (TYPE_MATGEN.equals(type)) return "生成器";
        if (TYPE_GEN.equals(type)) return "发电机";
        return type == null ? "" : type;
    }

    // ==================================================================
    // 家族 yml → 类型表
    // ==================================================================

    /** 家族 yml → 类型（顺序即优先级：配方机器优先）。 */
    private static final String[][] FAMILY_FILES = {
            {TYPE_RECIPE, "recipe_machines.yml"},
            {TYPE_RECIPE, "super_multi_block_machines.yml"},
            {TYPE_RECIPE, "template_machines.yml"},
            {TYPE_MATGEN, "mat_generators.yml"},
            {TYPE_GEN, "generators.yml"},
    };

    /** SFID（大写）→ 类型（来自 RSC 家族 yml）。 */
    private static final Map<String, String> FAMILY_MAP = new ConcurrentHashMap<>();

    /** SFID（大写）→ 类型（来自其它附属：逻辑工艺的表 + 通用扫描）。 */
    private static final Map<String, String> SERVER_MAP = new ConcurrentHashMap<>();

    /** 服务器表是否已建（失败也只试一次）。 */
    private static volatile boolean serverReady = false;

    /** 家族表是否已扫。 */
    private static volatile boolean familyReady = false;

    // ==================================================================
    // 初始化
    // ==================================================================

    /**
     * 扫所有附属的家族 yml，建立「机器 id → 类型」表。
     *
     * <p>只认<b>顶层</b>键（行首无缩进的 {@code Key:}）—— RSC 注册的 id 就是这些顶层键转大写。
     * 不解析 YAML：原脚本也是逐行正则，因为要跨<b>其它附属</b>的目录扫，
     * 不能依赖自己的配置加载器。</p>
     */
    private static synchronized void loadFamilies() {
        if (familyReady) return;
        familyReady = true;
        try {
            // ① 先扫**本插件自带**的 5 个家族 yml。
            //
            //    ⚠ 路径：`processResources` 把这 13 个 yml **平铺在 jar 根目录**
            //    （不是 content/ 子目录 —— 用 `unzip -l` 核对过），
            //    所以是 plugin#getResource("recipe_machines.yml")，不能加前缀。
            //
            //    ★ 这一步不能省。糖然科技自己的机器在独立化后<b>由本插件注册</b>，
            //    RSC 的 addons/<附属>/ 目录可能已经不存在（不再是 RSC 附属）。
            //    只靠 addons 的话，自家机器会退化成「通用扫描」识别 ——
            //    结果通常相同，但会额外依赖 LogiTech 的表，
            //    并且丢掉「配方机器优先」这条既有的优先级顺序。
            int bundled = 0;
            for (String[] entry : FAMILY_FILES) {
                String text = readBundled(entry[1]);
                if (text == null) continue;
                for (String raw : text.split("\\R")) {
                    String id = topLevelKey(raw);
                    if (id == null) continue;
                    // 先到先得（配方机器优先）
                    if (FAMILY_MAP.putIfAbsent(id.toUpperCase(Locale.ROOT), entry[0]) == null) {
                        bundled++;
                    }
                }
            }

            // ② 再扫所有 RSC 附属的家族 yml（认**别人的**机器）
            int external = 0;
            File dataFolder = com.tangran.tangrantech.TT.plugin == null
                    ? null : com.tangran.tangrantech.TT.plugin.getDataFolder();
            File addons = (dataFolder == null) ? null : new File(dataFolder, "addons");
            File[] dirs = (addons != null && addons.isDirectory()) ? addons.listFiles() : null;
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir == null || !dir.isDirectory()) continue;
                    for (String[] entry : FAMILY_FILES) {
                        String type = entry[0];
                        File file = new File(dir, entry[1]);
                        if (!file.isFile()) continue;
                        List<String> lines;
                        try {
                            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                        } catch (Throwable t) {
                            TT.log("[堆叠机] 读家族 yml 失败 " + file.getAbsolutePath() + ": " + t);
                            continue;
                        }
                        for (String raw : lines) {
                            String id = topLevelKey(raw);
                            if (id == null) continue;
                            if (FAMILY_MAP.putIfAbsent(id.toUpperCase(Locale.ROOT), type) == null) {
                                external++;
                            }
                        }
                    }
                }
            }
            TT.log("[堆叠机] 家族表就绪：" + FAMILY_MAP.size() + " 台机器（自带 "
                    + bundled + " / 其它附属 " + external + "）");
        } catch (Throwable t) {
            TT.log("[堆叠机] 家族表扫描失败: " + t);
        }
    }

    /** 读本插件 jar 内的文本资源；找不到返回 null。 */
    private static String readBundled(String path) {
        try {
            var plugin = com.tangran.tangrantech.TT.plugin;
            if (plugin == null) return null;
            try (java.io.InputStream in = plugin.getResource(path)) {
                if (in == null) return null;
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从一行里取「顶层键」；不是顶层键返回 null。 */
    private static String topLevelKey(String line) {
        if (line == null || line.isEmpty()) return null;
        char c0 = line.charAt(0);
        if (c0 == ' ' || c0 == '\t' || c0 == '#' || c0 == '-') return null;
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        String key = line.substring(0, colon).trim();
        if (key.isEmpty()) return null;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return null;
        }
        return key;
    }

    /**
     * 建立「其它附属的机器 → 类型」表。
     *
     * <p>先读逻辑工艺的识别结果，再用通用规则扫一遍所有已启用的粘液物品兜底。
     * 失败也只试一次（原脚本同样如此：{@code PD_SERVER_READY} 一旦置位就不再重试）。</p>
     */
    private static void buildServerMap() {
        if (serverReady) return;
        serverReady = true;

        // ① 逻辑工艺的识别结果（覆盖它支持的全部附属：RSC + 各种 jar 附属）
        try {
            Class<?> rs = Class.forName("me.matl114.logitech.core.Registries.RecipeSupporter");
            collectIds(rs, "STACKMACHINE_LIST", TYPE_RECIPE);
            collectIds(rs, "STACKMGENERATOR_LIST", TYPE_MATGEN);
        } catch (Throwable ignored) {
            // 没装逻辑工艺 → 跳过（不是错误）
        }

        // ② 通用兜底：遍历所有已启用的粘液物品，按类判
        try {
            for (SlimefunItem sf : Slimefun.getRegistry().getEnabledSlimefunItems()) {
                if (sf == null) continue;
                String id = String.valueOf(sf.getId()).toUpperCase(Locale.ROOT);
                if (SERVER_MAP.containsKey(id)) continue;
                String t = classify(sf, false);
                if (t != null) SERVER_MAP.put(id, t);
            }
        } catch (Throwable t) {
            TT.log("[堆叠机] 通用扫描失败: " + t);
        }
    }

    /** 把逻辑工艺那张表的 key 收进来（表是 {@code Map<SlimefunItem, ...>}）。 */
    @SuppressWarnings("unchecked")
    private static void collectIds(Class<?> holder, String fieldName, String type) {
        try {
            Field f = holder.getField(fieldName);
            Object v = f.get(null);
            if (!(v instanceof Map<?, ?> map)) return;
            for (Object key : map.keySet()) {
                if (!(key instanceof SlimefunItem sf)) continue;
                String id = String.valueOf(sf.getId()).toUpperCase(Locale.ROOT);
                SERVER_MAP.putIfAbsent(id, type);   // 先到先得（配方机器优先）
            }
        } catch (Throwable ignored) {
            // 字段不存在/类型不符 → 跳过
        }
    }

    // ==================================================================
    // 分类
    // ==================================================================

    /**
     * 单台机器的分类。
     *
     * @param deep true 表示「当场深挖这一台」（会反射找 {@code recipes} 等字段）。
     *             全量扫描时必须传 false —— 否则会对几千台机器逐个反射，拖慢启动。
     * @return 类型；判不出来返回 null
     */
    public static String classify(SlimefunItem sf, boolean deep) {
        try {
            if (sf == null) return null;

            // 发电机：能量网络的 GENERATOR 组件（各附属的发电机都实现 EnergyNetComponent）
            try {
                if (sf instanceof EnergyNetComponent enc) {
                    var t = enc.getEnergyComponentType();
                    if (t != null && "GENERATOR".equals(t.name())) return TYPE_GEN;
                }
            } catch (Throwable ignored) {
                // 继续往下判
            }

            List<?> recipes = recipesOf(sf, deep);
            if (recipes != null && !recipes.isEmpty()) {
                for (Object r : recipes) {
                    if (recipeHasInput(r)) return TYPE_RECIPE;
                }
                return TYPE_MATGEN;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取机器的配方列表。
     *
     * <p>先调 {@code getMachineRecipes()}（{@code AContainer} 系列的标准接口）；
     * {@code deep} 时再反射找 {@code recipes} / {@code machineRecipes} 字段
     * —— 只给「当场判这一台」用。</p>
     */
    private static List<?> recipesOf(SlimefunItem sf, boolean deep) {
        try {
            if (sf == null) return null;
            try {
                Method m = sf.getClass().getMethod("getMachineRecipes");
                Object r = m.invoke(sf);
                if (r instanceof List<?> list && !list.isEmpty()) return list;
                if (r instanceof java.util.Collection<?> c && !c.isEmpty()) {
                    return new ArrayList<>(c);
                }
            } catch (Throwable ignored) {
                // 没有这个方法 → 走反射
            }
            if (!deep) return null;

            String[] names = {"recipes", "machineRecipes"};
            for (String name : names) {
                Class<?> c = sf.getClass();
                for (int d = 0; d < 8 && c != null; d++) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        Object val = f.get(sf);
                        if (val instanceof java.util.Collection<?> col && !col.isEmpty()) {
                            return new ArrayList<>(col);
                        }
                        if (val instanceof Map<?, ?> map && !map.isEmpty()) {
                            return new ArrayList<>(map.values());
                        }
                        break;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 判不出来
        }
        return null;
    }

    /** 配方有没有输入（{@code MachineRecipe#getInput()} 是 {@code ItemStack[]}）。 */
    private static boolean recipeHasInput(Object recipe) {
        try {
            if (recipe == null) return false;
            Method m = recipe.getClass().getMethod("getInput");
            Object input = m.invoke(recipe);
            if (input == null) return false;
            if (input instanceof ItemStack[] arr) {
                for (ItemStack it : arr) {
                    if (it != null && it.getType() != Material.AIR) return true;
                }
                return false;
            }
            if (input instanceof java.util.Collection<?> col) {
                for (Object o : col) {
                    if (o instanceof ItemStack it && it.getType() != Material.AIR) return true;
                }
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 对外：识别一台机器
    // ==================================================================

    /**
     * 识别一个机器物品属于哪一类。
     *
     * <p>优先级：RSC 家族表 → 服务器表 → 当场深挖（并把深挖结果记进服务器表）。</p>
     *
     * @return {@link #TYPE_RECIPE} / {@link #TYPE_MATGEN} / {@link #TYPE_GEN}；认不出返回 null
     */
    public static String identify(ItemStack stack) {
        try {
            SlimefunItem sf = slimefunItemOf(stack);
            if (sf == null) return null;
            String id = String.valueOf(sf.getId()).toUpperCase(Locale.ROOT);

            loadFamilies();
            String fromFamily = FAMILY_MAP.get(id);
            if (fromFamily != null) return fromFamily;

            buildServerMap();
            String fromServer = SERVER_MAP.get(id);
            if (fromServer != null) return fromServer;

            // 表里没有的新机器 → 当场深挖这一台，并记下来（下次就不用再反射）
            String t = classify(sf, true);
            if (t != null) SERVER_MAP.put(id, t);
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 物品辅助
    // ==================================================================

    /** 物品 → 粘液 id；空气 / 非粘液物品返回 null。 */
    public static String machineIdOf(ItemStack stack) {
        try {
            if (stack == null || stack.getType() == Material.AIR) return null;
            SlimefunItem sf = SlimefunItem.getByItem(stack);
            return sf == null ? null : String.valueOf(sf.getId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 物品 → SlimefunItem。 */
    public static SlimefunItem slimefunItemOf(ItemStack stack) {
        try {
            if (stack == null || stack.getType() == Material.AIR) return null;
            return SlimefunItem.getByItem(stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 机器 id → 显示名（取注册物品的自定义名，没有就用 id）。 */
    public static String machineName(String id) {
        try {
            if (id == null) return "";
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf == null) return id;
            ItemStack item = sf.getItem();
            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().serialize(meta.displayName());
                }
            }
            return String.valueOf(sf.getId());
        } catch (Throwable ignored) {
            return id == null ? "" : id;
        }
    }

    // ==================================================================
    // 存储物品读取（网络量子存储 / 概念奇点 等）
    // ==================================================================

    /** 一次存储读取的结果。 */
    public record StorageInfo(ItemStack content, long amount, long max, Object storageAdapter,
                              ItemMeta meta) {}

    /** 逻辑工艺的 StorageType 入口（反射缓存；不可用存 null）。 */
    private static Method storageTypeOf;
    /** 逻辑工艺存储适配器的方法（{@code isStorage / getStorageContent / getStorageAmount / getStorageMaxSize}）。 */
    private static final Map<String, Method> STORAGE_METHODS = new ConcurrentHashMap<>();
    /** 是否已经尝试解析过逻辑工艺的入口。 */
    private static volatile boolean storageProbeDone = false;

    /**
     * 读一个物品「作为存储物品」里的内容与数量。
     *
     * <p>两条路：</p>
     * <ol>
     *   <li>优先用<b>逻辑工艺</b>的 {@code StorageType.getStorageType(meta)} —— 它自己注册了各种
     *       存储适配器，能认的（网络量子存储 / 概念奇点 等）都能读，不必一个个手写；</li>
     *   <li>兜底直接读 <b>NetworksExpansion</b> 量子存储的 PDC（没装逻辑工艺时也能用）。</li>
     * </ol>
     *
     * @return 存储信息；不是存储 / 空存储返回 null
     */
    public static StorageInfo storageOf(ItemStack item) {
        try {
            if (item == null || item.getType() == Material.AIR) return null;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;

            Object st = resolveStorageAdapter(meta);
            if (st != null) {
                Object content = invokeStorage(st, "getStorageContent", meta);
                long amount = asLong(invokeStorage(st, "getStorageAmount", meta));
                long max = asLong(invokeStorage(st, "getStorageMaxSize", meta));
                if (content instanceof ItemStack is
                        && is.getType() != Material.AIR && amount > 0) {
                    return new StorageInfo(is, amount, max, st, meta);
                }
                return null;
            }

            StorageInfo quantum = readQuantumStorage(item, meta);
            return quantum;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解析逻辑工艺的存储适配器；不是存储物品返回 null。 */
    private static Object resolveStorageAdapter(ItemMeta meta) {
        try {
            if (!storageProbeDone) {
                synchronized (StackMachineRegistry.class) {
                    if (!storageProbeDone) {
                        try {
                            Class<?> c = Class.forName(
                                    "me.matl114.logitech.utils.UtilClass.StorageClass.StorageType");
                            storageTypeOf = c.getMethod("getStorageType", ItemMeta.class);
                            for (String name : new String[]{"isStorage", "getStorageContent",
                                    "getStorageAmount", "getStorageMaxSize"}) {
                                // 缓存键带参数个数：同名方法在不同 arity 下是不同方法
                                STORAGE_METHODS.put(name + "#1", c.getMethod(name, ItemMeta.class));
                            }
                        } catch (Throwable ignored) {
                            // 没装逻辑工艺 → 永远走量子存储兜底
                        }
                        storageProbeDone = true;
                    }
                }
            }
            if (storageTypeOf == null) return null;
            Object st = storageTypeOf.invoke(null, meta);
            if (st == null) return null;
            Method isStorage = STORAGE_METHODS.get("isStorage#1");
            if (isStorage == null) return null;
            Object yes = isStorage.invoke(st, meta);
            return Boolean.TRUE.equals(yes) ? st : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 调逻辑工艺存储适配器的方法。
     *
     * <p>原脚本只有「读」那 4 个方法被预注册，写操作（{@code clearStorage} /
     * {@code onStorageAmountWrite} / {@code setStorage}）是运行时按名找的。
     * 这里同样做成<b>按名 + 参数个数动态查找</b>，找到就缓存 —— 不然写操作全都调不动。</p>
     */
    private static Object invokeStorage(Object st, String method, Object... args) {
        try {
            Method m = storageMethod(st.getClass(), method, args);
            return m == null ? null : m.invoke(st, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按名 + arity 找存储适配器方法（逐层向上找 declared，最后退 public）。 */
    private static Method storageMethod(Class<?> cls, String name, Object[] args) {
        String key = name + "#" + args.length;
        Method cached = STORAGE_METHODS.get(key);
        if (cached != null) return cached;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (!paramsMatch(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 不允许改可见性就照原样用
                }
                STORAGE_METHODS.put(key, m);
                return m;
            }
        }
        return null;
    }

    /** 参数能否按位匹配（允许 Number 与 long/int/double 之间的拆装箱）。 */
    private static boolean paramsMatch(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = types[i];
            if (a == null) {
                if (t.isPrimitive()) return false;
                continue;
            }
            if (t.isInstance(a)) continue;
            if (a instanceof Number && (t == long.class || t == int.class || t == double.class
                    || t == Long.class || t == Integer.class || t == Double.class)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 从「存储物品」里扣掉 want 个。
     *
     * <p>对应原脚本 {@code pdStorageWithdraw}。返回<b>实际扣掉</b>的数量：
     * 写完再读一次核对，读不回来（空存储读取返回 null）时按「数量变了」判定成功。</p>
     */
    public static long storageWithdraw(ItemStack item, long want) {
        try {
            if (item == null || want <= 0) return 0;
            StorageInfo info = storageOf(item);
            if (info == null) return 0;
            long take = Math.min(want, Math.max(0L, info.amount()));
            if (take <= 0) return 0;
            long left = info.amount() - take;
            if (!(info.meta() instanceof ItemMeta meta)) return 0;
            Object st = info.storageAdapter();
            if (st != null) {
                if (left <= 0) {
                    invokeStorage(st, "clearStorage", meta);
                } else {
                    invokeStorage(st, "onStorageAmountWrite", meta, Long.valueOf(left));
                }
                item.setItemMeta(meta);
            } else {
                // 兜底：直接改 NetworksExpansion 的量子存储 PDC
                PersistentDataContainer root = meta.getPersistentDataContainer();
                NamespacedKey qkey = new NamespacedKey("networks", "quantum_storage");
                NamespacedKey akey = new NamespacedKey("networks", "amount");
                PersistentDataContainer child = root.get(qkey, PersistentDataType.TAG_CONTAINER);
                if (child == null) return 0;
                try {
                    child.set(akey, PersistentDataType.LONG, Long.valueOf(left));
                } catch (Throwable t) {
                    return 0;
                }
                try {
                    root.set(qkey, PersistentDataType.TAG_CONTAINER, child);
                } catch (Throwable t) {
                    return 0;
                }
                item.setItemMeta(meta);
            }
            StorageInfo after = storageOf(item);
            return (after == null || after.amount() != info.amount()) ? take : 0;
        } catch (Throwable t) {
            TT.log("[堆叠机] 存储物品扣减失败: " + t);
            return 0;
        }
    }

    /**
     * 往「存储物品」里塞一叠。
     *
     * <p>对应原脚本 {@code pdStorageDeposit}。返回<b>实际塞进去</b>的数量。
     * 存储里已有别的东西、或已满 → 0（不收）。</p>
     */
    public static long storageDeposit(ItemStack item, ItemStack stack) {
        try {
            if (item == null || stack == null || stack.getType() == Material.AIR) return 0;
            StorageInfo info = storageOf(item);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return 0;
            if (info == null) {
                // 空存储：把这一叠写进去（逻辑工艺的抽象；它判 isStorage 为假时同样返回 0，与原脚本一致）
                Object st = resolveStorageAdapter(meta);
                if (st != null) {
                    invokeStorage(st, "setStorage", meta, stack.clone());
                    item.setItemMeta(meta);
                    return stack.getAmount();
                }
                return 0;
            }
            boolean same;
            try {
                same = info.content().isSimilar(stack);
            } catch (Throwable t) {
                same = false;
            }
            if (!same) return 0;
            long add = stack.getAmount();
            if (info.max() > 0) add = Math.min(add, Math.max(0L, info.max() - info.amount()));
            if (add <= 0) return 0;
            Object st = info.storageAdapter();
            if (st != null) {
                invokeStorage(st, "onStorageAmountWrite", meta, Long.valueOf(info.amount() + add));
                item.setItemMeta(meta);
                return add;
            }
            PersistentDataContainer root = meta.getPersistentDataContainer();
            NamespacedKey qkey = new NamespacedKey("networks", "quantum_storage");
            NamespacedKey akey = new NamespacedKey("networks", "amount");
            PersistentDataContainer child = root.get(qkey, PersistentDataType.TAG_CONTAINER);
            if (child == null) return 0;
            try {
                child.set(akey, PersistentDataType.LONG, Long.valueOf(info.amount() + add));
            } catch (Throwable t) {
                return 0;
            }
            try {
                root.set(qkey, PersistentDataType.TAG_CONTAINER, child);
            } catch (Throwable t) {
                return 0;
            }
            item.setItemMeta(meta);
            return add;
        } catch (Throwable t) {
            TT.log("[堆叠机] 存储物品写入失败: " + t);
            return 0;
        }
    }

    /**
     * 直接读 NetworksExpansion 的量子存储 PDC。
     *
     * <p>走的是与「逻辑工艺 NetworksQuantumStorageAdapter」同一套 API：
     * {@code Keys.QUANTUM_STORAGE_INSTANCE} + {@code PersistentQuantumStorageType.TYPE}
     * + {@code DataTypeMethods.getCustom(meta, key, type)}。</p>
     */
    private static StorageInfo readQuantumStorage(ItemStack item, ItemMeta meta) {
        try {
            Class<?> keysClass = Class.forName("io.github.sefiraat.networks.utils.Keys");
            Class<?> dataTypeMethods = Class.forName(
                    "io.github.sefiraat.networks.utils.datatypes.DataTypeMethods");
            Class<?> quantumType = Class.forName(
                    "io.github.sefiraat.networks.utils.datatypes.PersistentQuantumStorageType");

            Object key = keysClass.getField("QUANTUM_STORAGE_INSTANCE").get(null);
            Object type = quantumType.getField("TYPE").get(null);
            // getCustom(ItemMeta, NamespacedKey, PersistentDataType) —— 参数类型要按真实类找，
            // 不能拿拿到的实例去猜（Keys 里那个是 NamespacedKey，TYPE 是 PersistentDataType）
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            Class<?> persistenDataTypeClass =
                    Class.forName("org.bukkit.persistence.PersistentDataType");
            Method getCustom = dataTypeMethods.getMethod(
                    "getCustom", ItemMeta.class, namespacedKeyClass, persistenDataTypeClass);
            Object cache = getCustom.invoke(null, meta, key, type);
            if (cache == null) return null;

            Object content = null;
            long amount = 0L;
            try {
                content = cache.getClass().getMethod("getItemStack").invoke(cache);
            } catch (Throwable ignored) {
                content = null;
            }
            try {
                amount = asLong(cache.getClass().getMethod("getAmountLong").invoke(cache));
            } catch (Throwable ignored) {
                try {
                    amount = asLong(cache.getClass().getMethod("getAmount").invoke(cache));
                } catch (Throwable ignored2) {
                    amount = 0L;
                }
            }
            if (content instanceof ItemStack is && is.getType() != Material.AIR && amount > 0) {
                return new StorageInfo(is, amount, -1L, null, meta);
            }
        } catch (Throwable ignored) {
            // 没装 NetworksExpansion / API 变了 → 当作非存储物品
        }
        return null;
    }

    /**
     * 这一格里放的「机器来源」。
     *
     * <p>直接放的机器 = <b>按这一叠的数量</b>（原脚本曾经写死 1，导致「放 64 台也只算 1 台」）；
     * 存储物品 = 里面的机器 + 数量（long，不设 64 上限）。</p>
     */
    public static MachineSource machineSourceOf(ItemStack stack) {
        try {
            if (stack == null || stack.getType() == Material.AIR) return null;
            StorageInfo st = storageOf(stack);
            if (st != null) return new MachineSource(st.content(), st.amount(), true);
            long n = Math.max(1, stack.getAmount());
            return new MachineSource(stack, n, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 一格里的机器来源。 */
    public record MachineSource(ItemStack item, long amount, boolean storage) {}

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 已识别的机器总数（日志用）。 */
    public static int knownCount() {
        loadFamilies();
        return FAMILY_MAP.size() + SERVER_MAP.size();
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        FAMILY_MAP.clear();
        SERVER_MAP.clear();
        STORAGE_METHODS.clear();
        storageTypeOf = null;
        storageProbeDone = false;
        familyReady = false;
        serverReady = false;
    }
}
