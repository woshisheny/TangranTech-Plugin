package com.tangran.tangrantech.load;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.MenuDef;
import com.tangran.tangrantech.util.Colors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加载 menus.yml → {@link TT#menus}（供机器取 GUI 布局）。支持 slots 单槽与 a-b 区间写法。 */
public final class MenusLoader {

    private MenusLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(TT.plugin, "menus.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                MenuDef menu = new MenuDef(id, Colors.c(s.getString("title", id)));
                int size = s.getInt("size", -1);
                if (size > 0) menu.size = size;
                ConfigurationSection slots = s.getConfigurationSection("slots");
                if (slots != null) {
                    for (String key : slots.getKeys(false)) {
                        ConfigurationSection slotSec = slots.getConfigurationSection(key);
                        if (slotSec == null) continue;
                        ItemStack item = Read.item(slotSec, false);
                        boolean isProgress = slotSec.getBoolean("progressbar", false)
                                || slotSec.isConfigurationSection("progressbar");
                        for (int slot : parseSlots(key)) {
                            if (item != null) menu.items.put(slot, item);
                            if (isProgress) {
                                menu.progressSlot = slot;
                                menu.progressItem = item;
                                menu.progressFromConfig = true;
                            }
                        }
                    }
                }
                // ★ 键统一大写：全部机器加载器（RecipeMachines/Machines/TemplateMachines/
                // Generators/MaterialGenerators/SuperMultiBlockMachines + ScriptMachineItem
                // 共 8 处）都按大写 id 取菜单，而 yml 键是混合大小写（Tr_xxx）——原先按原样
                // 存，所有机器的 menu 一律查空（GUI 无装饰、进度槽失效），并暴露了
                // constructMenu「无装饰 → 预设只有 27 格」的隐患：输出槽 ≥27 的机器
                // （Tr_cjzryhj）在 fitAll 取输出槽时数组越界（实机 2026-10-25 报错）。
                TT.menus.put(TT.upper(id), menu);
                ok++;
            } catch (Exception e) {
                TT.log("menus.yml " + id + " 失败: " + e);
            }
        }
        TT.plugin.getLogger().info("menus.yml: 注册 " + ok);
    }

    /** 解析槽位键：单值 "6" 或区间 "10-17"。非法/反转/越界（>53）返回空数组（仅跳过该槽）。 */
    private static int[] parseSlots(String key) {
        key = key.trim();
        int dash = key.indexOf('-');
        if (dash > 0) {
            try {
                int lo = Integer.parseInt(key.substring(0, dash));
                int hi = Integer.parseInt(key.substring(dash + 1));
                if (lo >= 0 && hi < 54 && hi >= lo) {
                    int[] out = new int[hi - lo + 1];
                for (int i = 0; i < out.length; i++) out[i] = lo + i;
                return out;
            }
            } catch (NumberFormatException ignored) { }
        }
        try {
            int v = Integer.parseInt(key);
            return (v >= 0 && v < 54) ? new int[] { v } : new int[0];
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }
}
