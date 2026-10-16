package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TangranTechPlugin;
import com.tangran.tangrantech.TT;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 从 jar 资源加载 YAML。
 *
 * <p>加载期文件名缓存（parse-once）：{@code preloadDisplays} 与各 Loader 会访问同一组内容文件，
 * 缓存后每个文件在单次加载中只解析一次，第二次访问为 HashMap 命中。
 *
 * <p>安全性：全部 Loader 仅读取配置、从不写入，故跨调用方共享同一 {@link YamlConfiguration}
 * 实例行为等价。加载结束后由 {@code Setup#loadAll()} 调用 {@link #clearCache()} 释放解析树。</p>
 */
public final class Yaml {

    private Yaml() {}

    /** 加载期文件名缓存：filename -> 已解析配置（只读共享，加载结束释放）。 */
    private static final Map<String, YamlConfiguration> CACHE = new HashMap<>();

    public static YamlConfiguration loadResource(TangranTechPlugin plugin, String name) {
        YamlConfiguration cached = CACHE.get(name);
        if (cached != null) return cached;
        YamlConfiguration cfg = doLoad(plugin, name);
        CACHE.put(name, cfg);
        return cfg;
    }

    private static YamlConfiguration doLoad(TangranTechPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                TT.log("资源缺失: " + name);
                return new YamlConfiguration();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            TT.log("读取 " + name + " 失败: " + e);
            return new YamlConfiguration();
        }
    }

    /** 加载完成后释放解析树缓存（Setup#loadAll 末尾调用）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
