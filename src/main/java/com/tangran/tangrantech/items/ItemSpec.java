package com.tangran.tangrantech.items;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 从物品段解析出的属性集合（对齐 RSC ItemReader / MachineReader 读取的字段）。
 *
 * <p>糖然科技用到的字段：{@code script}、{@code placeable}、{@code energy}（含 capacity/type）、
 * 以及通用的 {@code soulbound / anti_wither / radiation / piglin_trade_chance / vanilla / drop_from / drop_chance}。
 */
public class ItemSpec {

    public String id;
    public String script;
    /** RSC 默认不可放置，需显式 {@code placeable: true}。 */
    public boolean placeable = false;
    public boolean soulbound;
    public boolean antiWither;
    /** Radioactivity 枚举名；null 表示无。 */
    public String radiation;
    /** 0-100；null 表示未配置。 */
    public Integer piglinChance;
    /** 能量容量；null 表示非能量物品。 */
    public Integer energyCapacity;
    /** 能量组件类型名：CONSUMER / GENERATOR / CAPACITOR 等；null 表示未配置。 */
    public String energyType;
    /** setUseableInWorkbench。 */
    public boolean vanilla;
    /** drop_from：方块材质名。 */
    public String dropFrom;
    /** drop_chance：0-100，缺省 100。 */
    public int dropChance = 100;

    public static ItemSpec from(String id, ConfigurationSection s) {
        ItemSpec spec = new ItemSpec();
        spec.id = id;
        spec.script = s.getString("script");
        if (spec.script != null) spec.script = spec.script.trim();
        spec.placeable = s.getBoolean("placeable", false);
        spec.soulbound = s.getBoolean("soulbound", false);
        spec.antiWither = s.getBoolean("anti_wither", false);
        spec.radiation = s.getString("radiation");
        if (s.isSet("piglin_trade_chance")) spec.piglinChance = s.getInt("piglin_trade_chance", 100);
        spec.vanilla = s.getBoolean("vanilla", false);
        spec.dropFrom = s.getString("drop_from");
        spec.dropChance = s.getInt("drop_chance", 100);

        ConfigurationSection energy = s.getConfigurationSection("energy");
        if (energy != null) {
            spec.energyCapacity = energy.getInt("capacity", 0);
            spec.energyType = energy.getString("type");
        }
        return spec;
    }
}
