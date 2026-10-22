package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeScriptRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 内容加载编排：分组 → 配方类型 → 预加载展示物品 → 各内容文件 → 脚本生命周期 → 释放缓存。 */
public final class Setup {

    private Setup() {}

    /**
     * 含有「可被其它配方以 material_type:slimefun 引用」的物品的文件（需预加载展示堆）。
     * 顺序无关——预加载只是为了打破跨文件前向引用。
     */
    private static final String[] ITEM_FILES = {
        "items.yml", "machines.yml", "mob_drops.yml", "mat_generators.yml", "generators.yml",
        "recipe_machines.yml", "mb_machines.yml", "template_machines.yml",
        "super_multi_block_machines.yml"
    };

    public static void loadAll() {
        long t = System.currentTimeMillis();
        // 「物品未找到」折叠上报：加载开始清零、结尾汇总
        MissingItems.reset();

        // 脚本层必须在解析任何 script: 字段之前就绪
        NativeScriptRegistry.bootstrap();

        GroupLoader.load();
        RecipeTypes.load();
        preloadDisplays();

        // 菜单必须早于机器：机器按 upper(id) 从 TT.menus 取菜单
        MenusLoader.load();

        ItemsLoader.load();
        MachinesLoader.load();
        MobDropsLoader.load();
        RecipeMachinesLoader.load();
        MaterialGeneratorsLoader.load();
        GeneratorsLoader.load();
        TemplateMachinesLoader.load();
        MultiBlockMachinesLoader.load();
        SuperMultiBlockMachinesLoader.load();

        // 外观反查索引：所有物品注册完毕后构建一次，供运行期按材质分桶 O(1) 查询
        com.tangran.tangrantech.util.Stacks.buildAppearanceIndex();

        // 所有物品注册完毕后再剔除「引用了禁用/缺失物品」的配方——此时 isDisabled 才已确定
        com.tangran.tangrantech.machines.TTRecipeMachine.filterDisabledRecipes();

        // 汇总 yml 里引用到但尚未原生化的脚本名，便于按批推进移植
        NativeScriptRegistry.reportUnregistered();

        // 脚本生命周期监听器注册（对应原 JS 脚本「加载期注册监听」的行为）
        NativeScriptRegistry.registerLifecycleListeners(TT.plugin);

        // 加载期缓存释放：解析树、头颅贴图、展示堆表
        Yaml.clearCache();
        Read.clearSkinCache();
        MissingItems.report();

        // 诊断：物品数为 0 的子分类（帮助定位「分类点进去空无一物」——
        // 若某组为空，说明它的条目在上方某个 loader 里注册失败/被跳过，往前翻日志即可）
        try {
            StringBuilder emptyGroups = new StringBuilder();
            for (var e : TT.groups.entrySet()) {
                if (e.getValue().getItems().isEmpty()) {
                    if (emptyGroups.length() > 0) emptyGroups.append(", ");
                    emptyGroups.append(e.getKey());
                }
            }
            if (emptyGroups.length() > 0) {
                TT.log("诊断：以下分组没有任何物品（对应条目注册失败或被跳过）: " + emptyGroups);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        TT.preload.clear();

        TT.plugin.getLogger().info("基础内容加载完成，耗时 " + (System.currentTimeMillis() - t) + "ms");
    }

    /** 第一遍：把各物品/机器的展示堆加入 TT.preload，使后续配方解析能跨文件按 id 引用。 */
    private static void preloadDisplays() {
        int n = 0;
        for (String file : ITEM_FILES) {
            YamlConfiguration y = Yaml.loadResource(TT.plugin, file);
            // 逐条 try/catch 故障隔离：单条坏展示数据只跳过该项，不会导致后续物品全部
            // 因 preload 查空而被跳过（插件近乎空载启用）
            for (String id : y.getKeys(false)) {
                try {
                    ConfigurationSection s = y.getConfigurationSection(id);
                    if (s == null) continue;
                    ConfigurationSection itemSec = s.getConfigurationSection("item");
                    if (itemSec == null) continue;
                    ItemStack display = Read.item(itemSec, false);
                    if (display == null) continue;
                    String effId = TT.upper(s.getString("id_alias", id));
                    TT.preload.put(effId, display);
                    n++;
                } catch (Exception e) {
                    TT.log("预加载展示物品 " + id + " 失败，跳过: " + e);
            }
            }
        }
        TT.plugin.getLogger().info("预加载展示物品: " + n);
    }
}
