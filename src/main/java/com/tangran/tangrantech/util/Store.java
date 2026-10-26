package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家数据文档库（SQLite）。
 *
 * <h2>模型：命名空间 + 键 → 一份 JSON 文档</h2>
 * <p>旧实现把每个玩家的数据写成
 * {@code addon_configs/糖然科技/<命名空间>/<玩家名>.json}；这里保留同样的语义，
 * 只是把「文件」换成「表里的一行」：</p>
 * <pre>
 * player_doc(ns TEXT, key TEXT, doc TEXT, updated_at INTEGER, PRIMARY KEY(ns, key))
 * </pre>
 * <p>业务代码不需要知道后端换了 —— {@link AddonPaths#readJson}/{@link AddonPaths#writeJson}
 * 对玩家数据目录下的 JSON 路径会转到这里，其余（{@code addons/} 里的附属资源、{@code .txt} 标记文件）
 * 仍是真实文件。</p>
 *
 * <h2>旧档迁移</h2>
 * <p>首次启动（{@code app_meta} 里没有 {@code legacy_json_import} 标记）会把
 * {@code addon_configs/糖然科技/&#42;&#42;} 下的所有 JSON 一次性导入，已存在的键不覆盖；
 * 单条读取时若库里没有而文件在，也会顺手导入（{@link AddonPaths#readJson} 里做）。
 * 也就是说：<b>玩家数据只可能从文件迁到库里，不会反过来丢数据</b>。</p>
 *
 * <h2>降级</h2>
 * <p>{@link Sqlite} 打不开（驱动缺失 / 冷门平台原生库加载失败 / 目录不可写）时，
 * {@link #available()} 恒 false，调用方全部回落文件后端，功能不受影响。</p>
 */
public final class Store {

    private Store() {}

    /** 玩家数据文档表。 */
    private static final String TABLE = "player_doc";

    /** 键值元数据表（迁移标记、库结构版本）。 */
    private static final String META = "app_meta";

    /** 旧文件导入完成的标记。 */
    private static final String META_LEGACY = "legacy_json_import";

    private static boolean schemaReady;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 打开数据库并建表；随后尝试把旧 JSON 一次性导入。
     *
     * <p>由 {@code Setup#loadAll()} 在所有内容加载<b>之前</b>调用 —— 加载期有些脚本会读玩家数据。</p>
     */
    public static void init() {
        try {
            File db = databaseFile();
            if (!Sqlite.open(db)) return;
            if (!ensureSchema()) return;
            schemaReady = true;
            TT.log("[存储] SQLite 就绪: " + db.getAbsolutePath());
            autoImportLegacyOnce();
        } catch (Throwable t) {
            TT.log("[存储] 初始化失败，回落文件存储: " + t);
        }
    }

    /** 卸载时关库。 */
    public static void shutdown() {
        schemaReady = false;
        Sqlite.close();
    }

    /** 库是否可用（false → 调用方必须走文件后端）。 */
    public static boolean available() {
        return schemaReady && Sqlite.available();
    }

    /** 数据库文件：{@code plugins/TangranTech/data/tangran.db}。 */
    public static File databaseFile() {
        return new File(AddonPaths.dataFolder(), "data/tangran.db");
    }

    private static boolean ensureSchema() {
        Boolean ok = Sqlite.within(c -> {
            try (var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "ns TEXT NOT NULL,"
                        + "key TEXT NOT NULL,"
                        + "doc TEXT NOT NULL,"
                        + "updated_at INTEGER NOT NULL,"
                        + "PRIMARY KEY (ns, key))");
                st.execute("CREATE INDEX IF NOT EXISTS idx_" + TABLE + "_ns ON " + TABLE + "(ns)");
                st.execute("CREATE TABLE IF NOT EXISTS " + META + " ("
                        + "k TEXT PRIMARY KEY, v TEXT)");
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
        return Boolean.TRUE.equals(ok);
    }

    // ==================================================================
    // 文档读写
    // ==================================================================

    /** 读一份文档；不存在或解析失败返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String ns, String key) {
        if (!available() || ns == null || key == null) return null;
        String text = Sqlite.select("SELECT doc FROM " + TABLE + " WHERE ns=? AND key=?",
                rs -> rs.next() ? rs.getString(1) : null, null, ns, key);
        if (text == null || text.isBlank()) return null;
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            // 旧档里极少见（顶层数组）→ 包一层，保证读取方拿到的仍是对象
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("value", parsed);
            return wrapped;
        } catch (Throwable t) {
            TT.log("[存储] 文档解析失败 " + ns + "/" + key + ": " + t);
            return null;
        }
    }

    /** 写一份文档（UPSERT）。 */
    public static boolean save(String ns, String key, Object doc) {
        if (!available() || ns == null || key == null) return false;
        String text;
        try {
            text = Json.writePretty(doc == null ? new LinkedHashMap<>() : doc);
        } catch (Throwable t) {
            TT.log("[存储] 文档序列化失败 " + ns + "/" + key + ": " + t);
            return false;
        }
        long now = System.currentTimeMillis();
        int n = Sqlite.exec("INSERT INTO " + TABLE + " (ns, key, doc, updated_at) VALUES (?,?,?,?) "
                + "ON CONFLICT(ns, key) DO UPDATE SET doc=excluded.doc, updated_at=excluded.updated_at",
                ns, key, text, now);
        return n >= 0;
    }

    /** 删一份文档。 */
    public static boolean delete(String ns, String key) {
        if (!available() || ns == null || key == null) return false;
        return Sqlite.exec("DELETE FROM " + TABLE + " WHERE ns=? AND key=?", ns, key) >= 0;
    }

    /** 文档是否存在。 */
    public static boolean exists(String ns, String key) {
        if (!available() || ns == null || key == null) return false;
        Integer one = Sqlite.select("SELECT 1 FROM " + TABLE + " WHERE ns=? AND key=? LIMIT 1",
                rs -> rs.next() ? 1 : 0, 0, ns, key);
        return one != null && one == 1;
    }

    /** 某命名空间下的全部键（字典序）。 */
    public static List<String> keys(String ns) {
        List<String> out = Sqlite.select("SELECT key FROM " + TABLE + " WHERE ns=? ORDER BY key",
                rs -> {
                    List<String> list = new ArrayList<>();
                    while (rs.next()) list.add(rs.getString(1));
                    return list;
                }, new ArrayList<>(), ns);
        return out == null ? new ArrayList<>() : out;
    }

    /** 某命名空间的文档数。 */
    public static int count(String ns) {
        Integer n = Sqlite.select("SELECT COUNT(*) FROM " + TABLE + " WHERE ns=?",
                rs -> rs.next() ? rs.getInt(1) : 0, 0, ns);
        return n == null ? 0 : n;
    }

    /** 某份文档的最后写入时间（毫秒）；不存在返回 0。 */
    public static long updatedAt(String ns, String key) {
        Long v = Sqlite.select("SELECT updated_at FROM " + TABLE + " WHERE ns=? AND key=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, 0L, ns, key);
        return v == null ? 0L : v;
    }

    /** 全部命名空间（按文档数降序）。 */
    public static Map<String, Integer> namespaces() {
        Map<String, Integer> out = Sqlite.select(
                "SELECT ns, COUNT(*) FROM " + TABLE + " GROUP BY ns ORDER BY COUNT(*) DESC",
                rs -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
                    return m;
                }, new LinkedHashMap<>());
        return out == null ? new LinkedHashMap<>() : out;
    }

    // ==================================================================
    // 元数据
    // ==================================================================

    /** 读一项元数据；不存在返回 null。 */
    public static String meta(String k) {
        if (!available()) return null;
        return Sqlite.select("SELECT v FROM " + META + " WHERE k=?", rs -> rs.next() ? rs.getString(1) : null, null, k);
    }

    /** 写一项元数据。 */
    public static void setMeta(String k, String v) {
        if (!available()) return;
        Sqlite.exec("INSERT INTO " + META + " (k, v) VALUES (?,?) "
                + "ON CONFLICT(k) DO UPDATE SET v=excluded.v", k, v);
    }

    // ==================================================================
    // 旧 JSON 导入
    // ==================================================================

    /**
     * 导入结果。
     *
     * @param dryRun      是不是预览（true 时什么都没写）
     * @param perNs       命名空间 → 导入（或将要导入）的文档数
     * @param created     新建的文档数
     * @param overwritten 覆盖的文档数
     * @param skipped     库里已有、本次跳过的文档数
     * @param failed      解析/写入失败的文档数
     * @param samples     每个命名空间挑 1 个键做样例（最多 12 条）
     */
    public record ImportReport(boolean dryRun, Map<String, Integer> perNs,
                               int created, int overwritten, int skipped, int failed,
                               List<String> samples) {

        public int total() {
            return created + overwritten + skipped + failed;
        }

        /** 面向指令输出的一行摘要。 */
        public String summary() {
            return (dryRun ? "预览" : "导入完成") + "：共 " + total() + " 份（新建 " + created
                    + " / 覆盖 " + overwritten + " / 跳过 " + skipped + " / 失败 " + failed + "）";
        }
    }

    /** 首次启动自动导入（只填空缺，不覆盖已有）。 */
    private static void autoImportLegacyOnce() {
        try {
            if (meta(META_LEGACY) != null) return;
            File root = AddonPaths.configRoot();
            if (root == null || !root.isDirectory()) {
                setMeta(META_LEGACY, "skip:no-legacy-dir");
                return;
            }
            ImportReport rep = importLegacy(root, null, false, false);
            if (rep.total() == 0) {
                setMeta(META_LEGACY, "skip:no-file");
                return;
            }
            setMeta(META_LEGACY, "done:" + rep.created() + "/" + rep.skipped() + "/" + rep.failed());
            TT.log("[存储] 旧 JSON 导入 " + rep.summary());
            for (Map.Entry<String, Integer> e : rep.perNs().entrySet()) {
                TT.log("[存储]   " + e.getKey() + " ← " + e.getValue() + " 份");
            }
            if (rep.failed() > 0) {
                TT.log("[存储] 有 " + rep.failed() + " 份文件解析失败，仍按文件后端保留（未删除）");
            }
        } catch (Throwable t) {
            TT.log("[存储] 旧 JSON 自动导入失败（不影响运行，可稍后用 /tr import 重跑）: " + t);
        }
    }

    /**
     * 从旧目录导入玩家数据。
     *
     * @param root      旧数据根目录（RSC 的 {@code addon_configs/糖然科技}，或本插件同名目录）
     * @param onlyNs    只导这些命名空间；null / 空 = 全部
     * @param overwrite 库里已有同键时是否覆盖
     * @param dryRun    true = 只统计不写库
     */
    public static ImportReport importLegacy(File root, Collection<String> onlyNs,
                                           boolean overwrite, boolean dryRun) {
        Map<String, Integer> perNs = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int created = 0, overwritten = 0, skipped = 0, failed = 0;
        if (root == null || !root.isDirectory()) {
            return new ImportReport(dryRun, perNs, 0, 0, 0, 0, samples);
        }
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return new ImportReport(dryRun, perNs, 0, 0, 0, 0, samples);
        for (File dir : dirs) {
            String ns = dir.getName();
            if (onlyNs != null && !onlyNs.isEmpty() && !onlyNs.contains(ns)) continue;
            File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files == null || files.length == 0) continue;
            for (File f : files) {
                String key = f.getName().substring(0, f.getName().length() - 5);
                try {
                    boolean exists = available() && exists(ns, key);
                    if (exists && !overwrite) {
                        skipped++;
                        perNs.merge(ns, 0, Integer::sum);
                        continue;
                    }
                    Map<String, Object> doc = readJsonFile(f);
                    if (doc == null) {
                        failed++;
                        continue;
                    }
                    if (!dryRun && available()) {
                        if (!save(ns, key, doc)) {
                            failed++;
                            continue;
                        }
                    }
                    if (exists) overwritten++;
                    else created++;
                    perNs.merge(ns, 1, Integer::sum);
                    if (samples.size() < 12 && key.length() > 0) {
                        String line = ns + "/" + key;
                        if (!samples.contains(line)) samples.add(line);
                    }
                } catch (Throwable t) {
                    failed++;
                }
            }
        }
        return new ImportReport(dryRun, perNs, created, overwritten, skipped, failed, samples);
    }

    /** 读一个真实 JSON 文件（导入用；不经过 {@link AddonPaths} 以免被拦回库里）。 */
    public static Map<String, Object> readJsonFile(File file) {
        try {
            if (file == null || !file.isFile()) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> map = Json.parseObject(text);
            return (map == null || map.isEmpty()) ? null : map;
        } catch (Throwable t) {
            return null;
        }
    }
}
