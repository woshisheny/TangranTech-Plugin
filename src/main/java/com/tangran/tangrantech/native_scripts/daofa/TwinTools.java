package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossBattleBridge;
import com.tangran.tangrantech.native_scripts.daofa.LuxianSword;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;
import com.tangran.tangrantech.native_scripts.daofa.ToolState;

/**
 * 「糖b四工具」—— {@code scripts/道法/装备.js} 的第二部分（约 1370 行）。
 *
 * <p>四件中章装备工具，都是石质材质但「挖掘掉落视作下界合金材质」（否则石镐挖不动钻石矿）：</p>
 * <table border="1">
 *   <caption>四工具</caption>
 *   <tr><th>工具</th><th>id</th><th>范围效果</th></tr>
 *   <tr><td>鸿蒙开天镐</td><td>{@code Tr_ktg}</td><td>3×3×3 范围挖矿（只连锁<b>同种</b>矿石/石材）+ 自动熔炼 + 自动入包</td></tr>
 *   <tr><td>太虚斩劫斧</td><td>{@code Tr_zjf}</td><td>连锁伐木（BFS 只连<b>同种</b>原木，上限 200）+ 顺带清树叶（上限 400）</td></tr>
 *   <tr><td>九幽覆海铲</td><td>{@code Tr_fhc}</td><td>5×5 同层范围铲土（只连锁<b>同种</b>）+ 清掉正上方水</td></tr>
 *   <tr><td>长生种道锄</td><td>{@code Tr_zdc}</td><td>右键：5×5 范围耕地 / 5×3×5 催熟 / 5×3×5 收割并自动补种</td></tr>
 * </table>
 *
 * <p>所有范围效果在<b>潜行</b>时自动关闭（只处理当前这一块）。</p>
 *
 * <h2>几个必须保留的设计</h2>
 * <ol>
 *   <li><b>「只连锁同种」</b>：范围挖矿/铲土都用 {@code block.getType() == 目标类型} 过滤，
 *       不是「所有矿石」—— 3×3×3 里混着煤和铁时只挖与目标同种的那些。</li>
 *   <li><b>范围破块先发一次 {@link BlockBreakEvent}</b>：这样场地地板保护、其它保护插件
 *       都能拦下；被取消就不动这块。</li>
 *   <li><b>「视为下界合金材质」</b>：掉落一律用同款下界合金工具结算，
 *       精准采集/时运照常生效。</li>
 *   <li><b>功能关闭时掉落丢在方块处</b>（走原版观感），功能开启才自动入包。</li>
 *   <li><b>锄的「两拍循环」</b>：同一个位置连续右键，第一拍催熟、第二拍收割并补种、
 *       之后回到催熟。原脚本注释说明过原因：同一次右键可能被派发多次，
 *       不做节流会「催熟和收割一起发生」。这里保留 120ms 节流 + 逐地块状态机。</li>
 *   <li><b>补种只用「刚收割的同种作物」的种子</b>（副手 → 背包最上最左）。
 *       原脚本明确警告：绝不拿别的种子顶上，否则收下界疣会被萝卜种子替换掉。</li>
 * </ol>
 *
 * <h2>开关存档</h2>
 * <p>存在 {@code addon_configs/糖然科技/糖b工具/<玩家名>.json}，见 {@link ToolState}。
 * 每把工具有三个开关：功能 / 精准采集 / 自动熔炼（自动熔炼只对镐与铲有效）。</p>
 */
public final class TwinTools {

    private TwinTools() {}

    // ==================================================================
    // 定义
    // ==================================================================

    /** 一件工具的静态定义。 */
    public record Def(String id, String material, String netherite, String name,
                      String plain, List<String> lore) {}

    private static final List<Def> DEFS = List.of(
            new Def("Tr_ktg", "STONE_PICKAXE", "NETHERITE_PICKAXE", "§6§l鸿蒙开天镐", "鸿蒙开天镐",
                    List.of("§f一镐开天，万矿朝宗。", "§7适合范围挖矿、自动熔炼、自动入包。",
                            "§7石质工具，挖掘矿物掉落视作下界合金材质。")),
            new Def("Tr_zjf", "STONE_AXE", "NETHERITE_AXE", "§5§l太虚斩劫斧", "太虚斩劫斧",
                    List.of("§f一斧斩劫，万木归元。", "§7适合连锁伐木与树叶、自动收集木头",
                            "§7石质工具，挖掘矿物掉落视作下界合金材质。")),
            new Def("Tr_fhc", "STONE_SHOVEL", "NETHERITE_SHOVEL", "§3§l九幽覆海铲", "九幽覆海铲",
                    List.of("§f一铲覆海，群山平岳。", "§7适合范围铲土、平整地形、填海造陆。",
                            "§7石质工具，挖掘矿物掉落视作下界合金材质。")),
            new Def("Tr_zdc", "STONE_HOE", "NETHERITE_HOE", "§a§l长生种道锄", "长生种道锄",
                    List.of("§f一锄种道，万灵长生。", "§7适合范围耕地、右键作物催熟；收割后自动补种",
                            "§7石质工具，挖掘矿物掉落视作下界合金材质。")));

    /** 套装语（lore 的判定关键词是「鸿蒙开天」）。 */
    private static final String SET_LINE = "§d鸿蒙开天，太虚斩劫；九幽覆海，长生种道。";
    private static final String TOGGLE_LINE = "§7可以 shift 右键来打开功能菜单";
    private static final String LORE_KEY = "鸿蒙开天";

    // ---- 范围参数 ----

    private static final int PICK_AREA = 1;      // 3×3×3
    private static final int SHOVEL_AREA = 2;    // 5×5 同层
    private static final int HOE_AREA = 2;       // 5×5 耕地
    private static final int HOE_AREA_Y = 1;     // 催熟/收割 y 方向 ±1 → 5×3×5
    private static final int LOG_MAX = 200;      // 连锁伐木上限
    private static final int LEAF_MAX = 400;     // 顺带清树叶上限

    /** 连锁破块的重入保护。 */
    private static boolean busy = false;

    /** 范围处理时永不碰的方块。 */
    private static final Set<Material> BLOCKED_TYPES = Set.of(
            Material.BEDROCK, Material.BARRIER, Material.NETHER_PORTAL, Material.END_PORTAL,
            Material.END_PORTAL_FRAME, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID,
            Material.JIGSAW, Material.LIGHT, Material.MOVING_PISTON,
            Material.REINFORCED_DEEPSLATE, Material.SPAWNER);

    /** 自动熔炼表（矿石/原矿 → 锭，沙 → 玻璃，黏土 → 陶瓦…）。 */
    private static final Map<Material, Material> SMELT_MAP = smeltMap();

    private static Map<Material, Material> smeltMap() {
        Map<Material, Material> m = new LinkedHashMap<>();
        m.put(Material.IRON_ORE, Material.IRON_INGOT);
        m.put(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT);
        m.put(Material.RAW_IRON, Material.IRON_INGOT);
        m.put(Material.GOLD_ORE, Material.GOLD_INGOT);
        m.put(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT);
        m.put(Material.NETHER_GOLD_ORE, Material.GOLD_INGOT);
        m.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        m.put(Material.COPPER_ORE, Material.COPPER_INGOT);
        m.put(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT);
        m.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        m.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
        m.put(Material.SAND, Material.GLASS);
        m.put(Material.RED_SAND, Material.GLASS);
        m.put(Material.CLAY_BALL, Material.BRICK);
        m.put(Material.CLAY, Material.TERRACOTTA);
        m.put(Material.COBBLESTONE, Material.STONE);
        m.put(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE);
        m.put(Material.NETHERRACK, Material.NETHER_BRICK);
        m.put(Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ);
        return Map.copyOf(m);
    }

    /** 镐可挖的「石材」类（除此之外还认名字含 ORE 或以 RAW_ 开头的）。 */
    private static final Set<Material> PICK_TYPES = Set.of(
            Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
            Material.DRIPSTONE_BLOCK, Material.NETHERRACK, Material.BASALT, Material.SMOOTH_BASALT,
            Material.BLACKSTONE, Material.END_STONE, Material.OBSIDIAN, Material.ANCIENT_DEBRIS,
            Material.AMETHYST_BLOCK, Material.GLOWSTONE);

    /** 铲可挖的土方块。 */
    private static final Set<Material> SHOVEL_TYPES = Set.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.SAND, Material.RED_SAND, Material.GRAVEL,
            Material.CLAY, Material.SOUL_SAND, Material.SOUL_SOIL, Material.SNOW,
            Material.SNOW_BLOCK, Material.MUD, Material.MOSS_BLOCK, Material.DIRT_PATH);

    /** 锄可开垦的土方块。 */
    private static final Set<Material> TILL_TYPES = Set.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH);

    /** 种子 → 成熟作物（补种与识别都用它）。 */
    private record Seed(Material item, Material crop) {}

    private static final List<Seed> SEED_MAP = List.of(
            new Seed(Material.WHEAT_SEEDS, Material.WHEAT),
            new Seed(Material.BEETROOT_SEEDS, Material.BEETROOTS),
            new Seed(Material.CARROT, Material.CARROTS),
            new Seed(Material.POTATO, Material.POTATOES),
            new Seed(Material.MELON_SEEDS, Material.MELON_STEM),
            new Seed(Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM),
            new Seed(Material.SWEET_BERRIES, Material.SWEET_BERRY_BUSH),
            new Seed(Material.SUGAR_CANE, Material.SUGAR_CANE),
            new Seed(Material.CACTUS, Material.CACTUS),
            new Seed(Material.COCOA_BEANS, Material.COCOA),
            new Seed(Material.NETHER_WART, Material.NETHER_WART),
            new Seed(Material.BAMBOO, Material.BAMBOO),
            new Seed(Material.KELP, Material.KELP),
            new Seed(Material.TORCHFLOWER_SEEDS, Material.TORCHFLOWER_CROP),
            new Seed(Material.PITCHER_POD, Material.PITCHER_CROP));

    // ==================================================================
    // 状态
    // ==================================================================

    /** 一把工具的三个开关。 */
    public record State(boolean on, boolean silk, boolean smelt) {}

    /** 自动熔炼只对镐与铲有效（斧、锄没有可熔炼的产出）。 */
    public static boolean supportsSmelt(String defId) {
        return "Tr_ktg".equals(defId) || "Tr_fhc".equals(defId);
    }

    /** 读开关（默认：功能开、精准关、自动熔炼开）。 */
    public static State stateOf(Player player, String defId) {
        boolean on = true;
        boolean silk = false;
        boolean smelt = true;
        try {
            if (player == null || defId == null) return new State(on, silk, smelt);
            Map<String, Object> e = ToolState.sub(player.getName(), defId);
            if (ToolState.isFalse(e, "on")) on = false;
            if (ToolState.isTrue(e, "silk")) silk = true;
            if (ToolState.isFalse(e, "smelt")) smelt = false;
        } catch (Throwable ignored) {
            // 用默认值
        }
        return new State(on, silk, smelt);
    }

    /**
     * 写开关。
     *
     * @param smelt null 表示「沿用原值」（原脚本用 undefined 表达同一语义）
     */
    public static void setState(Player player, String defId, boolean on, boolean silk, Boolean smelt) {
        try {
            if (player == null || defId == null) return;
            Map<String, Object> prev = ToolState.sub(player.getName(), defId);
            boolean smeltVal = (smelt == null)
                    ? !ToolState.isFalse(prev, "smelt")
                    : smelt;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("on", on);
            value.put("silk", silk);
            value.put("smelt", smeltVal);
            ToolState.put(player.getName(), defId, value);
        } catch (Throwable t) {
            TT.log("[装备/四工具] 写入开关失败: " + t);
        }
    }

    /** 取原始子对象（破法弓的状态读写也走这里，见 {@code Pofabow}）。 */
    public static Map<String, Object> rawState(Player player, String key) {
        return player == null ? new LinkedHashMap<>() : ToolState.sub(player.getName(), key);
    }

    // ==================================================================
    // 物品
    // ==================================================================

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    private static String plainName(ItemMeta meta) {
        try {
            if (meta != null && meta.hasDisplayName()) {
                return LegacyComponentSerializer.legacySection().serialize(meta.displayName());
            }
        } catch (Throwable ignored) {
            // 无视
        }
        return null;
    }

    private static boolean hasLore(ItemMeta meta, String key) {
        try {
            List<Component> lore = meta.lore();
            if (lore == null) return false;
            for (Component c : lore) {
                if (LegacyComponentSerializer.legacySection().serialize(c).contains(key)) return true;
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return false;
    }

    private static boolean enchAtLeast(ItemMeta meta, Enchantment ench, int minLevel) {
        try {
            int cur = 0;
            try {
                cur = meta.getEnchantLevel(ench);
            } catch (Throwable ignored) {
                cur = 0;
            }
            if (cur >= minLevel) return false;
            meta.addEnchant(ench, minLevel, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean stripRscGlow(ItemMeta meta) {
        boolean changed = false;
        try {
            if (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA)) {
                meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
                changed = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return changed;
    }

    /** 补名称 / 套装 lore / 效率 50 / 时运 10 / 无法破坏；精准采集按开关增删。 */
    public static ItemStack applyMeta(ItemStack item, Def def, boolean silk) {
        try {
            if (item == null || def == null) return item;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;
            boolean changed = false;

            String dn = plainName(meta);
            if (dn == null || dn.isEmpty()) {
                meta.displayName(legacy(def.name()));
                changed = true;
            }
            if (!meta.isUnbreakable()) {
                meta.setUnbreakable(true);
                changed = true;
            }
            changed |= enchAtLeast(meta, Enchantment.EFFICIENCY, 50);
            changed |= enchAtLeast(meta, Enchantment.FORTUNE, 10);

            try {
                boolean hasSilk = meta.hasEnchant(Enchantment.SILK_TOUCH);
                if (silk && !hasSilk) {
                    meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
                    changed = true;
                } else if (!silk && hasSilk) {
                    meta.removeEnchant(Enchantment.SILK_TOUCH);
                    changed = true;
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            changed |= stripRscGlow(meta);

            if (!hasLore(meta, LORE_KEY)) {
                List<Component> lore = new ArrayList<>();
                for (String line : def.lore()) lore.add(legacy(line));
                lore.add(legacy(SET_LINE));
                lore.add(legacy(TOGGLE_LINE));
                meta.lore(lore);
                changed = true;
            }
            if (changed) item.setItemMeta(meta);
        } catch (Throwable t) {
            TT.log("[装备/四工具] 补元数据失败: " + t);
        }
        return item;
    }

    public static Def def(String defId) {
        for (Def d : DEFS) {
            if (d.id().equals(defId)) return d;
        }
        return null;
    }

    /** 按 id 造一件工具（优先用注册的粘液物品）。 */
    public static ItemStack make(String defId, Player player) {
        Def def = def(defId);
        if (def == null) return null;
        ItemStack item = LuxianSword.slimefunItem(def.id());
        if (item == null) {
            Material mat = Material.matchMaterial(def.material());
            if (mat == null) return null;
            item = new ItemStack(mat);
        }
        boolean silk = player != null && stateOf(player, def.id()).silk();
        return applyMeta(item, def, silk);
    }

    /** 判断物品是不是这四件之一（只认粘液 id；显示名可被铁砧伪造，不能作判据）。 */
    public static boolean isTool(ItemStack item, String defId) {
        try {
            if (item == null) return false;
            String sf = TT.sfIdOf(item);
            if (sf == null) return false;
            for (Def d : DEFS) {
                if (defId != null && !d.id().equals(defId)) continue;
                if (sf.equalsIgnoreCase(d.id())) return true;
            }
        } catch (Throwable ignored) {
            // 视为不是
        }
        return false;
    }

    /** 物品对应的工具 id；不是四件之一返回 null。 */
    public static String defIdOf(ItemStack item) {
        for (Def d : DEFS) {
            if (isTool(item, d.id())) return d.id();
        }
        return null;
    }

    /** 背包里是否已有这件工具（按名字认）。 */
    public static boolean playerHasTool(Player player, String defId) {
        try {
            if (player == null) return false;
            for (ItemStack it : player.getInventory().getContents()) {
                if (isTool(it, defId)) return true;
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return false;
    }

    /** 一次性发四件（背包满则掉脚下），返回实际发出的件数。 */
    public static int giveTools(Player player) {
        int given = 0;
        try {
            if (player == null || !player.isOnline()) return 0;
            for (Def d : DEFS) {
                ItemStack tool = make(d.id(), player);
                if (tool == null) continue;
                try {
                    var leftover = player.getInventory().addItem(tool);
                    for (ItemStack rest : leftover.values()) {
                        if (rest != null) player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                    given++;
                } catch (Throwable ignored) {
                    // 单件失败不影响其它
                }
            }
            if (given > 0) player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
        return given;
    }

    /** 补齐：缺哪件发哪件，返回本次补发件数（四件都在包里返回 0）。 */
    public static int ensureTools(Player player) {
        int given = 0;
        try {
            if (player == null || !player.isOnline()) return 0;
            for (Def d : DEFS) {
                if (playerHasTool(player, d.id())) continue;
                ItemStack tool = make(d.id(), player);
                if (tool == null) continue;
                try {
                    var leftover = player.getInventory().addItem(tool);
                    for (ItemStack rest : leftover.values()) {
                        if (rest != null) player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                    given++;
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (given > 0) player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
        return given;
    }

    /** 掉落结算用的「视为下界合金材质」工具。 */
    private static ItemStack dropTool(ItemStack tool, Def def, boolean silk) {
        try {
            if (def == null || def.netherite() == null) return tool;
            Material mat = Material.matchMaterial(def.netherite());
            if (mat == null) return tool;
            return applyMeta(new ItemStack(mat, 1), def, silk);
        } catch (Throwable ignored) {
            return tool;
        }
    }

    /** 自动熔炼一个掉落物。 */
    static ItemStack smeltStack(ItemStack stack) {
        try {
            if (stack == null) return null;
            Material target = SMELT_MAP.get(stack.getType());
            if (target == null) return stack;
            return new ItemStack(target, stack.getAmount());
        } catch (Throwable ignored) {
            return stack;
        }
    }

    // ==================================================================
    // 交付 / 记账
    // ==================================================================

    /** 自动入包：背包放不下就掉在脚下。 */
    static void giveStacks(Player player, java.util.Collection<ItemStack> stacks) {
        try {
            if (player == null || stacks == null) return;
            for (ItemStack st : stacks) {
                if (st == null || st.getType().isAir()) continue;
                try {
                    var left = player.getInventory().addItem(st);
                    for (ItemStack rest : left.values()) {
                        if (rest != null) player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                } catch (Throwable ignored) {
                    try {
                        player.getWorld().dropItemNaturally(player.getLocation(), st);
                    } catch (Throwable ignored2) {
                        // 放弃
                    }
                }
            }
            player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按原版方式把掉落物丢在方块处（功能关闭时用）。 */
    private static void dropStacks(Block block, List<ItemStack> stacks) {
        try {
            if (block == null || stacks == null) return;
            World world = block.getWorld();
            Location loc = block.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack st : stacks) {
                if (st == null || st.getType().isAir()) continue;
                try {
                    world.dropItemNaturally(loc, st);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 破坏粒子与音效。 */
    private static void breakFx(World world, Block block, BlockData data) {
        try {
            if (data == null) return;
            try {
                world.spawnParticle(org.bukkit.Particle.BLOCK,
                        block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5,
                        14, 0.3, 0.3, 0.3, data);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                // 直接用 Sound 对象（Sound#getKey() 在 1.21 已标记待删除）
                world.playSound(block.getLocation(), data.getSoundGroup().getBreakSound(), 0.45f, 1.0f);
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 双子经营进行中（破坏方块不掉落、不自动入包，只在天实账本里记）。 */
    private static boolean xuStageActive(Player player) {
        return BossBattleBridge.xuStageActive(player);
    }

    /** 收割产出：双子阶段不入背包（改成按掉落数量记作物点）；其它情况自动入包。 */
    static void harvestYield(Player player, Block block, java.util.Collection<ItemStack> drops) {
        try {
            if (xuStageActive(player)) {
                BossBattleBridge.xuLedgerCropBlock(player, block, drops);
                return;
            }
            if (drops != null && !drops.isEmpty()) giveStacks(player, drops);
            BossBattleBridge.xuLedgerCropBlock(player, block, drops);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 范围破块：先发一次 BlockBreakEvent（保护插件能拦下），掉落自动入包。
     *
     * @return 是否真的破坏了
     */
    static boolean breakExtra(Block block, Player player, ItemStack tool, boolean smelt) {
        try {
            if (block == null) return false;
            Material type = block.getType();
            if (type.isAir()) return false;
            if (BLOCKED_TYPES.contains(type)) return false;

            BlockBreakEvent event = new BlockBreakEvent(block, player);
            try {
                Bukkit.getPluginManager().callEvent(event);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (event.isCancelled()) return false;

            BlockData data = block.getBlockData();
            List<ItemStack> drops = new java.util.ArrayList<>(block.getDrops(tool, player));
            if (!drops.isEmpty() && !xuStageActive(player)) {
                List<ItemStack> out = new java.util.ArrayList<>(drops.size());
                for (ItemStack st : drops) {
                    if (st == null) continue;
                    out.add(smelt ? smeltStack(st) : st);
                }
                giveStacks(player, out);
            }
            block.setType(Material.AIR, false);
            breakFx(block.getWorld(), block, data);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 只破坏与目标完全同类型的方块。 */
    private static boolean sameType(Block block, Material want) {
        try {
            return block != null && block.getType() == want;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPickaxeBlock(Material type) {
        String n = type.name();
        if (n.contains("ORE")) return true;
        if (n.startsWith("RAW_")) return true;
        return PICK_TYPES.contains(type);
    }

    private static boolean isLogType(Material type) {
        String n = type.name();
        if (n.contains("_LOG") || n.contains("_WOOD") || n.contains("_STEM")
                || n.contains("_HYPHAE")) return true;
        return type == Material.MANGROVE_ROOTS || type == Material.MUDDY_MANGROVE_ROOTS;
    }

    private static boolean isLeafType(Material type) {
        return type.name().contains("LEAVES");
    }

    // ==================================================================
    // 镐：3×3×3 范围挖矿
    // ==================================================================

    private static void pickaxeArea(Block block, Player player, ItemStack tool) {
        try {
            World world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();
            Material want = block.getType();   // 只连锁同种矿石/石材

            for (int dx = -PICK_AREA; dx <= PICK_AREA; dx++) {
                for (int dy = -PICK_AREA; dy <= PICK_AREA; dy++) {
                    for (int dz = -PICK_AREA; dz <= PICK_AREA; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        if (!sameType(b, want)) continue;
                        breakExtra(b, player, tool, true);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 斧：连锁伐木 + 顺带清树叶
    // ==================================================================

    private static void axeChain(Block block, Player player, ItemStack tool) {
        try {
            World world = block.getWorld();
            String startKey = key(block);
            Material wantLog = block.getType();   // 只连锁同种原木

            Set<String> seen = new java.util.HashSet<>();
            ArrayDeque<Block> queue = new ArrayDeque<>();
            List<Block> logs = new ArrayList<>();
            queue.add(block);

            int minX = block.getX();
            int maxX = block.getX();
            int minY = block.getY();
            int maxY = block.getY();
            int minZ = block.getZ();
            int maxZ = block.getZ();

            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

            while (!queue.isEmpty() && logs.size() < LOG_MAX) {
                Block b = queue.poll();
                if (b == null) continue;
                String k = key(b);
                if (!seen.add(k)) continue;
                if (!sameType(b, wantLog)) continue;
                logs.add(b);
                minX = Math.min(minX, b.getX());
                maxX = Math.max(maxX, b.getX());
                minY = Math.min(minY, b.getY());
                maxY = Math.max(maxY, b.getY());
                minZ = Math.min(minZ, b.getZ());
                maxZ = Math.max(maxZ, b.getZ());
                for (int[] d : dirs) {
                    Block nb = world.getBlockAt(b.getX() + d[0], b.getY() + d[1], b.getZ() + d[2]);
                    if (sameType(nb, wantLog)) queue.add(nb);
                }
            }

            // 跳过玩家刚挖掉的那一块（它由原版事件结算）
            for (Block lg : logs) {
                if (key(lg).equals(startKey)) continue;
                breakExtra(lg, player, tool, false);
            }

            // 树叶：原木群包围盒外扩 2 格，只清树叶
            int leafCount = 0;
            for (int x = minX - 2; x <= maxX + 2 && leafCount < LEAF_MAX; x++) {
                for (int y = minY - 2; y <= maxY + 2 && leafCount < LEAF_MAX; y++) {
                    for (int z = minZ - 2; z <= maxZ + 2 && leafCount < LEAF_MAX; z++) {
                        Block lb = world.getBlockAt(x, y, z);
                        if (!isLeafType(lb.getType())) continue;
                        if (breakExtra(lb, player, tool, false)) leafCount++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String key(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    // ==================================================================
    // 铲：5×5 同层范围铲土 + 清上方水
    // ==================================================================

    /** 填海造陆：铲掉后把正上方的水也带走，免得马上又被淹。 */
    private static void clearWaterAbove(Block block) {
        try {
            Block above = block.getWorld().getBlockAt(block.getX(), block.getY() + 1, block.getZ());
            Material n = above.getType();
            if (n == Material.WATER || n == Material.BUBBLE_COLUMN) above.setType(Material.AIR, false);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void shovelArea(Block block, Player player, ItemStack tool) {
        try {
            World world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();
            Material want = block.getType();
            for (int dx = -SHOVEL_AREA; dx <= SHOVEL_AREA; dx++) {
                for (int dz = -SHOVEL_AREA; dz <= SHOVEL_AREA; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Block b = world.getBlockAt(bx + dx, by, bz + dz);
                    if (!sameType(b, want)) continue;   // 只连锁同种土
                    if (breakExtra(b, player, tool, false)) clearWaterAbove(b);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 锄：范围耕地 / 催熟 / 收割补种
    // ==================================================================

    /** 找「与刚收割作物同类」的种子（背包 + 副手都找；找不到返回 null）。 */
    private record SeedLoc(Integer slot, Seed seed, boolean offhand) {}

    private static SeedLoc findSeedFor(Player player, Material cropType) {
        try {
            Seed def = null;
            for (Seed s : SEED_MAP) {
                if (s.crop() == cropType) {
                    def = s;
                    break;
                }
            }
            if (def == null) return null;
            var inv = player.getInventory();
            ItemStack off = inv.getItemInOffHand();
            if (off != null && off.getType() == def.item()) return new SeedLoc(null, def, true);

            int[] order = new int[36];
            int p = 0;
            for (int a = 9; a <= 35; a++) order[p++] = a;
            for (int b = 0; b <= 8; b++) order[p++] = b;
            for (int slot : order) {
                ItemStack st = inv.getItem(slot);
                if (st != null && st.getType() == def.item()) return new SeedLoc(slot, def, false);
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return null;
    }

    /** 背包里有什么可种的（副手优先，然后主背包 9→35、再快捷栏 0→8）。 */
    private static SeedLoc findSeed(Player player) {
        try {
            var inv = player.getInventory();
            ItemStack off = inv.getItemInOffHand();
            Seed offSeed = seedOf(off);
            if (offSeed != null) return new SeedLoc(null, offSeed, true);

            int[] order = new int[36];
            int p = 0;
            for (int i = 9; i <= 35; i++) order[p++] = i;
            for (int j = 0; j <= 8; j++) order[p++] = j;
            for (int slot : order) {
                Seed seed = seedOf(inv.getItem(slot));
                if (seed != null) return new SeedLoc(slot, seed, false);
            }
        } catch (Throwable ignored) {
            // 视为没有
        }
        return null;
    }

    private static Seed seedOf(ItemStack stack) {
        try {
            if (stack == null) return null;
            for (Seed s : SEED_MAP) {
                if (stack.getType() == s.item()) return s;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static boolean takeOne(Player player, int slot) {
        try {
            var inv = player.getInventory();
            ItemStack st = inv.getItem(slot);
            if (st == null) return false;
            int a = st.getAmount();
            if (a <= 1) inv.setItem(slot, null);
            else st.setAmount(a - 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean takeSeedOne(Player player, SeedLoc info) {
        try {
            if (info == null) return false;
            if (info.offhand()) {
                var inv = player.getInventory();
                ItemStack off = inv.getItemInOffHand();
                if (off == null) return false;
                int a = off.getAmount();
                if (a <= 1) inv.setItemInOffHand(null);
                else off.setAmount(a - 1);
                return true;
            }
            return takeOne(player, info.slot());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 双子阶段的免费补种信息：收割物不进背包，就地免费补种同种作物。 */
    private static SeedLoc freeReplant(Player player, Material crop) {
        // 竖直作物与西瓜/南瓜果实另有补种逻辑，不走这里
        if (crop == Material.SUGAR_CANE || crop == Material.CACTUS || crop == Material.BAMBOO) return null;
        if (crop == Material.MELON || crop == Material.PUMPKIN
                || crop == Material.MELON_STEM || crop == Material.PUMPKIN_STEM) return null;
        Seed free = null;
        for (Seed s : SEED_MAP) {
            if (s.crop() == crop) {
                free = s;
                break;
            }
        }
        // 找不到对应种子表项时仍然允许「同格重置成该作物」（原脚本的 free 分支就是这样）
        return new SeedLoc(null, free != null ? free : new Seed(Material.AIR, crop), true);
    }

    private static boolean tillArea(Block block, Player player, ItemStack tool) {
        int tilledCount = 0;
        try {
            World world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();

            for (int dx = -HOE_AREA; dx <= HOE_AREA; dx++) {
                for (int dz = -HOE_AREA; dz <= HOE_AREA; dz++) {
                    Block b = world.getBlockAt(bx + dx, by, bz + dz);
                    if (!TILL_TYPES.contains(b.getType())) continue;
                    Block above = world.getBlockAt(bx + dx, by + 1, bz + dz);
                    if (!above.getType().isAir()) continue;   // 上面有东西就不翻
                    try {
                        b.setType(Material.FARMLAND, false);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    // 锄开垦出的耕地直接拉满湿润度，并登记进全局表（双子阶段定期重新补湿 → 永久湿润）
                    try {
                        BlockData bd = b.getBlockData();
                        if (bd instanceof org.bukkit.block.data.type.Farmland farmland) {
                            farmland.setMoisture(7);
                            b.setBlockData(bd, false);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    registerFarmland(b);
                    tilledCount++;
                }
            }
            if (tilledCount > 0) {
                try {
                    player.playSound(player.getLocation(), "item.hoe.till", 0.8f, 1.0f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                player.updateInventory();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return tilledCount > 0;
    }

    /** 一把锄开垦出的耕地登记项。 */
    public record Farmland(String world, int x, int y, int z) {}

    /**
     * 锄开垦出的耕地（双子阶段会定期重新补湿 → 实现「永久湿润」）。
     *
     * <p>原脚本挂在 {@code globalThis.XU_FARMLAND}；Java 改为本类的静态表，
     * BOSS战 通过 {@link #farmland()} 读取。上限 4000，超出时丢掉最早的一半
     * （与原脚本的 {@code splice(0, 2000)} 一致）。</p>
     */
    private static final List<Farmland> FARMLAND = new ArrayList<>();

    private static void registerFarmland(Block b) {
        try {
            FARMLAND.add(new Farmland(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
            if (FARMLAND.size() > 4000) {
                FARMLAND.subList(0, 2000).clear();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 耕地登记表快照（供 BOSS战 定期补湿）。 */
    public static List<Farmland> farmland() {
        return List.copyOf(FARMLAND);
    }

    /** 给可可豆设置朝向（先 face，2 刻后发现没活下来就用 alt 再补一次，并清掉掉出来的可可豆）。 */
    private static void setCocoaFacing(Block block, String face, String alt) {
        try {
            if (block == null) return;
            BlockData cd = block.getBlockData();
            if (cd instanceof org.bukkit.block.data.type.Cocoa cocoa) {
                try {
                    cocoa.setFacing(BlockFace.valueOf(face));
                    block.setBlockData(cocoa, false);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            Block target = block;
            var plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("TangranTech");
            if (plugin == null) return;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (target.getType() == Material.COCOA) return;
                    // 没活下来 → 换另一侧朝向，并把周围掉出来的可可豆清掉
                    try {
                        target.setType(Material.COCOA, false);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    BlockData cd2 = target.getBlockData();
                    if (cd2 instanceof org.bukkit.block.data.type.Cocoa cocoa2) {
                        try {
                            cocoa2.setFacing(BlockFace.valueOf(alt));
                            target.setBlockData(cocoa2, false);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    for (var en : target.getWorld().getNearbyEntities(
                            target.getLocation().add(0.5, 0.5, 0.5), 2.0, 2.0, 2.0)) {
                        try {
                            if (en instanceof org.bukkit.entity.Item item
                                    && item.getItemStack().getType() == Material.COCOA_BEANS) {
                                item.remove();
                            }
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 2L);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 5×3×5 范围收割成熟作物（只收准心对着的那一种），收完自动补种。
     *
     * @return 收割的株数
     */
    private static int harvestArea(Block block, Player player, ItemStack tool, Material onlyType) {
        int harvested = 0;
        Set<String> doneCols = new java.util.HashSet<>();
        try {
            World world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();

            for (int dx = -HOE_AREA; dx <= HOE_AREA; dx++) {
                for (int dy = -HOE_AREA_Y; dy <= HOE_AREA_Y; dy++) {
                    for (int dz = -HOE_AREA; dz <= HOE_AREA; dz++) {
                        Block b;
                        try {
                            b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        Material bt = b.getType();
                        if (onlyType != null && bt != onlyType) continue;

                        // ① 竖直叠放型（甘蔗/仙人掌/竹子）：整列 ≥3 格才收，基座原位补种 1 格
                        if (bt == Material.SUGAR_CANE || bt == Material.CACTUS || bt == Material.BAMBOO) {
                            Block base = b;
                            int h = 1;
                            try {
                                while (base.getRelative(0, -1, 0).getType() == bt) {
                                    base = base.getRelative(0, -1, 0);
                                }
                                Block tp = base;
                                while (tp.getRelative(0, 1, 0).getType() == bt) {
                                    tp = tp.getRelative(0, 1, 0);
                                    h++;
                                }
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            if (!doneCols.add(key(base))) continue;
                            if (h < 3) continue;
                            for (int vy = 1; vy < h; vy++) {
                                Block vb = base.getRelative(0, vy, 0);
                                try {
                                    harvestYield(player, vb, vb.getDrops(tool, player));
                                    vb.setType(Material.AIR, false);
                                    harvested++;
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                            }
                            try {
                                base.getRelative(0, 1, 0).setType(bt, false);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            continue;
                        }

                        // ② 西瓜/南瓜的茎
                        if (bt == Material.MELON_STEM || bt == Material.PUMPKIN_STEM) {
                            Material wantFruit = (bt == Material.MELON_STEM) ? Material.MELON : Material.PUMPKIN;
                            int[][] dirs4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                            int harvestedFruit = 0;
                            for (int[] d : dirs4) {
                                Block fb = b.getRelative(d[0], 0, d[1]);
                                try {
                                    if (fb.getType() != wantFruit) continue;
                                    harvestYield(player, fb, fb.getDrops(tool, player));
                                    fb.setType(Material.AIR, false);
                                    harvestedFruit++;
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                            }
                            if (harvestedFruit > 0) {
                                harvested++;
                                continue;
                            }
                            // 没果实：把成熟的茎旁边结一个出来
                            boolean mature = false;
                            try {
                                BlockData sdata = b.getBlockData();
                                if (sdata instanceof Ageable ageable) {
                                    mature = ageable.getAge() >= ageable.getMaximumAge();
                                }
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            if (!mature) continue;
                            for (int[] d : dirs4) {
                                Block fb = b.getRelative(d[0], 0, d[1]);
                                try {
                                    Material below = fb.getRelative(0, -1, 0).getType();
                                    if (fb.getType() == Material.AIR
                                            && (below == Material.FARMLAND || below == Material.DIRT
                                            || below == Material.GRASS_BLOCK)) {
                                        fb.setType(wantFruit, false);
                                        break;
                                    }
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                            }
                            continue;
                        }

                        // ③ 西瓜/南瓜果实：直接收割
                        if (bt == Material.MELON || bt == Material.PUMPKIN) {
                            try {
                                harvestYield(player, b, b.getDrops(tool, player));
                                b.setType(Material.AIR, false);
                                harvested++;
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            continue;
                        }

                        // ④ 常规作物（有 age）：只收成熟的
                        boolean mature = false;
                        try {
                            BlockData data = b.getBlockData();
                            if (!(data instanceof Ageable ageable)) continue;
                            mature = ageable.getAge() >= ageable.getMaximumAge();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (!mature) continue;

                        List<ItemStack> drops;
                        try {
                            drops = new java.util.ArrayList<>(b.getDrops(tool, player));
                        } catch (Throwable ignored) {
                            drops = new java.util.ArrayList<>();
                        }
                        harvestYield(player, b, drops);
                        harvested++;

                        // 自动补种：同格重种，且只用刚收割的同种作物种子
                        boolean replanted = false;
                        SeedLoc info = findSeedFor(player, b.getType());
                        // 双子阶段：收割物不进背包，就地免费补种同种作物
                        if (info == null && xuStageActive(player)) info = freeReplant(player, b.getType());
                        if (info != null) {
                            Material nextCrop = info.seed().crop();
                            try {
                                b.setType(nextCrop, false);   // 同格重置成幼苗
                                if (nextCrop == Material.COCOA) {
                                    // 可可豆朝向必须对准依附的丛林原木，否则会浮空/掉成物品
                                    int[][] probe = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
                                    String[][] faces = {{"NORTH", "SOUTH"}, {"SOUTH", "NORTH"},
                                            {"EAST", "WEST"}, {"WEST", "EAST"}};
                                    for (int pi = 0; pi < probe.length; pi++) {
                                        Block lgB = b.getRelative(probe[pi][0], 0, probe[pi][1]);
                                        String lgT = lgB.getType().name();
                                        if (lgT.contains("JUNGLE_LOG") || lgT.contains("JUNGLE_WOOD")) {
                                            setCocoaFacing(b, faces[pi][0], faces[pi][1]);
                                            break;
                                        }
                                    }
                                }
                                if (!info.offhand()) takeSeedOne(player, info);
                                replanted = true;
                            } catch (Throwable ignored) {
                                replanted = false;
                            }
                        }
                        if (!replanted) {
                            try {
                                b.setType(Material.AIR, false);
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                        }
                    }
                }
            }

            if (harvested > 0) {
                try {
                    player.playSound(player.getLocation(), "block.crop.break", 0.9f, 1.0f);
                } catch (Throwable ignored) {
                    // 忽略
                }
                player.updateInventory();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return harvested;
    }

    /** 5×3×5 范围催熟（只催与准心同种、且尚未成熟的）。 */
    private static int ripenArea(Block block, Material onlyType) {
        int n = 0;
        try {
            World world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();
            for (int dx = -HOE_AREA; dx <= HOE_AREA; dx++) {
                for (int dy = -HOE_AREA_Y; dy <= HOE_AREA_Y; dy++) {
                    for (int dz = -HOE_AREA; dz <= HOE_AREA; dz++) {
                        Block b;
                        try {
                            b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (onlyType != null && b.getType() != onlyType) continue;
                        if (ripen(b)) n++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return n;
    }

    private static boolean ripen(Block block) {
        try {
            BlockData data = block.getBlockData();
            if (!(data instanceof Ageable ageable)) return false;
            if (ageable.getAge() >= ageable.getMaximumAge()) return false;
            ageable.setAge(ageable.getMaximumAge());
            block.setBlockData(data, false);
            try {
                World world = block.getWorld();
                world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                        block.getX() + 0.5, block.getY() + 0.6, block.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.0);
            } catch (Throwable ignored) {
                // 忽略
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 功能菜单
    // ==================================================================

    private static final int MENU_SLOT_FUNC = 11;
    private static final int MENU_SLOT_SMELT = 13;
    private static final int MENU_SLOT_SILK = 15;
    private static final int MENU_SLOT_CLOSE = 22;
    private static final String MENU_TITLE = "§d§l糖b四器 · 功能菜单";
    private static final String MENU_TITLE_KEY = "糖b四器";

    /** 菜单的会话标记。 */
    static final class TwinMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static ItemStack button(Inventory inv, int slot, String matName, String name, List<String> lore) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String l : lore) lines.add(legacy(l));
                meta.lore(lines);
            }
            it.setItemMeta(meta);
        }
        if (inv != null) inv.setItem(slot, it);
        return it;
    }

    /** 按当前手持工具刷新菜单按钮。 */
    static void refreshMenu(Inventory inv, Player player) {
        try {
            if (inv == null) return;
            ItemStack tool = player.getInventory().getItemInMainHand();
            Def def = def(defIdOf(tool));
            State st = stateOf(player, def == null ? null : def.id());
            boolean on = st.on();
            boolean silk = st.silk();

            button(inv, MENU_SLOT_FUNC, on ? "LIME_DYE" : "GRAY_DYE",
                    "§e工具功能：" + (on ? "§a开启" : "§c关闭"),
                    List.of("§7当前工具：§f" + (def != null ? def.plain() : "未知"),
                            "§7点击切换",
                            on ? "§7开启后：范围挖矿/连锁伐木/范围铲土等全部生效"
                                    : "§7关闭后：等同普通木质工具"));
            button(inv, MENU_SLOT_SILK, silk ? "ENCHANTED_BOOK" : "BOOK",
                    "§b精准采集：" + (silk ? "§a开启" : "§c关闭"),
                    List.of("§7开启后：方块按原样掉落（精准采集），并停止自动熔炼", "§7点击切换"));

            boolean canSmelt = def != null && supportsSmelt(def.id());
            boolean smelt = st.smelt();
            button(inv, MENU_SLOT_SMELT, smelt ? "FURNACE" : "BLAST_FURNACE",
                    "§6自动熔炼：" + (canSmelt ? (smelt ? "§a开启" : "§c关闭") : "§8该工具不支持"),
                    canSmelt
                            ? List.of("§7开启后：掉落直接熔炼（粗铁→铁锭、沙子→玻璃、黏土→陶瓦…）",
                                    "§7精准采集开启时不熔炼", "§7点击切换")
                            : List.of("§7自动熔炼只给 §f鸿蒙开天镐 §7与 §f九幽覆海铲 §7"));

            // 补满装饰玻璃
            for (int i = 0; i < inv.getSize(); i++) {
                if (i == MENU_SLOT_FUNC || i == MENU_SLOT_SMELT || i == MENU_SLOT_SILK
                        || i == MENU_SLOT_CLOSE) continue;
                if (inv.getItem(i) == null) {
                    button(inv, i, "GRAY_STAINED_GLASS_PANE", "§0", null);
                }
            }
            button(inv, MENU_SLOT_CLOSE, "BARRIER", "§c§l关闭菜单", List.of("§7点击关闭"));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static boolean openMenu(Player player) {
        try {
            if (player == null || !player.isOnline()) return false;
            Inventory inv = Bukkit.createInventory(new TwinMenuHolder(), 27, legacy(MENU_TITLE));
            for (int i = 0; i < 27; i++) {
                button(inv, i, "GRAY_STAINED_GLASS_PANE", "§0", null);
            }
            refreshMenu(inv, player);
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            TT.log("[装备/四工具] 打开功能菜单失败: " + t);
            return false;
        }
    }

    /**
     * 认菜单：自定义 holder 为主，标题兜底。
     *
     * <p>标题必须从 {@link org.bukkit.inventory.InventoryView} 取 —— 1.21 起
     * {@code Inventory} 已经没有 {@code getTitle()} 了。</p>
     */
    private static boolean isOurMenu(Inventory top, org.bukkit.inventory.InventoryView view) {
        try {
            if (top.getHolder() instanceof TwinMenuHolder) return true;
        } catch (Throwable ignored) {
            // 用标题兜底
        }
        try {
            return view.getTitle().contains(MENU_TITLE_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void handleMenuClick(InventoryClickEvent event, Inventory top) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (slot == MENU_SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot != MENU_SLOT_FUNC && slot != MENU_SLOT_SILK && slot != MENU_SLOT_SMELT) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        Def def = def(defIdOf(tool));
        if (def == null) return;

        State st = stateOf(player, def.id());
        boolean on = st.on();
        boolean silk = st.silk();
        boolean smelt = st.smelt();

        if (slot == MENU_SLOT_FUNC) {
            on = !on;
        } else if (slot == MENU_SLOT_SMELT) {
            if (!supportsSmelt(def.id())) {
                player.sendMessage("§c自动熔炼只给 鸿蒙开天镐 与 九幽覆海铲。");
                return;
            }
            smelt = !smelt;
        } else {
            silk = !silk;
        }

        setState(player, def.id(), on, silk, smelt);
        try {
            applyMeta(tool, def, silk);
            player.getInventory().setItemInMainHand(tool);
            player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
        refreshMenu(top, player);
        try {
            player.playSound(player.getLocation(), "ui.button.click", 0.8f, on ? 1.3f : 0.9f);
        } catch (Throwable ignored) {
            // 忽略
        }
        player.sendMessage("§e" + def.plain() + "：功能 " + (on ? "§a开启" : "§c关闭")
                + "§7，精准采集 " + (silk ? "§a开启" : "§c关闭")
                + (supportsSmelt(def.id()) ? ("§7，自动熔炼 " + (smelt ? "§a开启" : "§c关闭")) : ""));
    }

    // ==================================================================
    // 事件
    // ==================================================================

    /** 锄的右键节流（同一玩家 120ms 内只处理一次）。 */
    private static final Map<String, Long> HOE_TICK = new ConcurrentHashMap<>();
    /** 锄的「催熟 → 收割」两拍循环状态：地块位置 → 下一拍该做什么。 */
    private static final Map<String, String> HOE_CYCLE = new ConcurrentHashMap<>();

    private static void onBlockBreak(BlockBreakEvent event) {
        try {
            if (busy) return;
            if (event.isCancelled()) return;
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) return;

            ItemStack tool = player.getInventory().getItemInMainHand();
            String id = defIdOf(tool);
            if (id == null) return;
            Def def = def(id);
            if (def == null) return;

            State st = stateOf(player, id);
            boolean silk = st.silk();
            Block block = event.getBlock();
            if (block == null) return;

            // 掉落一律按「视为下界合金材质」结算：功能关掉也能正常挖钻石矿等
            ItemStack dropToolItem = dropTool(tool, def, silk);
            List<ItemStack> drops;
            try {
                drops = new java.util.ArrayList<>(block.getDrops(dropToolItem, player));
            } catch (Throwable ignored) {
                drops = new java.util.ArrayList<>();
            }
            try {
                event.setDropItems(false);
            } catch (Throwable ignored) {
                // 忽略
            }

            if (!drops.isEmpty() && !xuStageActive(player)) {
                // 自动熔炼是工具独立开关（镐/铲）；精准采集开启时同样不熔炼
                boolean smelt = supportsSmelt(id) && st.smelt() && !silk;
                List<ItemStack> out = new java.util.ArrayList<>(drops.size());
                for (ItemStack d : drops) {
                    if (d == null) continue;
                    out.add(smelt ? smeltStack(d) : d);
                }
                if (st.on()) giveStacks(player, out);   // 功能开启：自动入包
                else dropStacks(block, out);            // 功能关闭：照原版掉在方块处
            }

            if (!st.on()) return;        // 功能关闭 → 不做任何范围效果
            if ("Tr_zdc".equals(id)) return;   // 锄只管右键
            if (player.isSneaking()) return;   // 潜行 = 只处理当前这一块

            busy = true;
            try {
                if ("Tr_ktg".equals(id)) pickaxeArea(block, player, dropToolItem);
                else if ("Tr_zjf".equals(id)) axeChain(block, player, dropToolItem);
                else if ("Tr_fhc".equals(id)) {
                    shovelArea(block, player, dropToolItem);
                    clearWaterAbove(block);
                }
            } finally {
                busy = false;
            }
        } catch (Throwable t) {
            busy = false;
            TT.log("[装备/四工具] 挖掘处理异常: " + t);
        }
    }

    private static void onInteract(PlayerInteractEvent event) {
        try {
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) return;
            ItemStack tool = player.getInventory().getItemInMainHand();
            String id = defIdOf(tool);
            if (id == null) return;

            // Shift + 右键：打开功能菜单（四个工具通用，不触发工具本身功能）
            if (player.isSneaking()) {
                event.setCancelled(true);
                if (!openMenu(player)) player.sendMessage("§c功能菜单打开失败，请稍后再试。");
                return;
            }

            if (!"Tr_zdc".equals(id)) return;    // 其余工具不需要右键功能
            if (action != Action.RIGHT_CLICK_BLOCK) return;
            if (!stateOf(player, id).on()) return;   // 未启用 → 当普通锄头用
            Block block = event.getClickedBlock();
            if (block == null) return;

            // 同一次右键可能被派发多次（RSC script 钩子 + 本监听器 / 双手事件），
            // 不做节流会「第一拍催熟、第二拍紧接着收割」。这里 120ms 节流。
            String tk = player.getUniqueId().toString();
            long now = System.currentTimeMillis();
            Long last = HOE_TICK.get(tk);
            if (last != null && (now - last) < 120) return;
            HOE_TICK.put(tk, now);

            Material vt = block.getType();
            // 竖直叠放型（甘蔗/仙人掌/竹子）：没有 age，右键先把它们补到 3 格高
            if (vt == Material.SUGAR_CANE || vt == Material.CACTUS || vt == Material.BAMBOO) {
                int grown = 0;
                try {
                    Block vbase = block;
                    while (vbase.getRelative(0, -1, 0).getType() == vt) {
                        vbase = vbase.getRelative(0, -1, 0);
                    }
                    for (int gi = 0; gi < 3; gi++) {
                        Block nb = vbase.getRelative(0, gi, 0);
                        if (nb.getType() == Material.AIR) {
                            nb.setType(vt, false);
                            grown++;
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (grown > 0) {
                    try {
                        player.playSound(player.getLocation(), "item.bone_meal.use", 0.8f, 1.2f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    player.sendMessage("§a已催熟 §e" + grown + " §a格（3 格封顶）");
                    return;
                }
                // 长满 → 本次右键 = 5×5 范围同种竖直作物整列收割
                int nV = harvestArea(block, player, tool, vt);
                if (nV > 0) {
                    try {
                        player.playSound(player.getLocation(), "block.crop.break", 0.9f, 1.0f);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    player.sendMessage("§a已收割 §e" + nV + " §a格（5×5 范围，已补种 1 格）");
                    player.updateInventory();
                } else {
                    player.sendMessage("§7至少要长到 3 格才能收割。");
                }
                return;
            }

            BlockData data;
            try {
                data = block.getBlockData();
            } catch (Throwable ignored) {
                data = null;
            }
            Material btClick = block.getType();

            // 作物（有 age）与西瓜/南瓜果实都走「催熟 → 收割补种」的两拍循环
            if ((data instanceof Ageable) || btClick == Material.MELON || btClick == Material.PUMPKIN) {
                String hkey = block.getWorld().getName() + ":" + block.getX() + ":"
                        + block.getY() + ":" + block.getZ();
                if (!"harvest".equals(HOE_CYCLE.get(hkey))) {
                    int ripened = ripenArea(block, btClick);
                    HOE_CYCLE.put(hkey, "harvest");
                    if (ripened > 0) {
                        try {
                            player.playSound(player.getLocation(), "item.bone_meal.use", 0.8f, 1.2f);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        player.sendMessage("§a已催熟 §e" + ripened + " §a株作物（5×5 范围）");
                    } else {
                        player.sendMessage("§7这一片已经成熟了，再右键一次即收割并补种。");
                    }
                    return;
                }
                int n = harvestArea(block, player, tool, btClick);
                HOE_CYCLE.put(hkey, "ripen");
                if (n > 0) {
                    player.sendMessage("§a已收割 §e" + n + " §a株成熟作物（5×5 范围，已自动补种）");
                }
                return;
            }

            // 范围耕地（不自动播种：第一颗种子要玩家自己种）
            // 不 cancel 事件，让原版该做的动作照常（普通模式下手感与原版一致）
            if (!TILL_TYPES.contains(block.getType())) return;
            tillArea(block, player, tool);
        } catch (Throwable t) {
            TT.log("[装备/四工具] 右键处理异常: " + t);
        }
    }

    /** 四工具的事件监听。 */
    static final class ListenerImpl implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onBreak(BlockBreakEvent event) {
            onBlockBreak(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            TwinTools.onInteract(event);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = event.getView().getTopInventory();
                if (top == null || !isOurMenu(top, event.getView())) return;
                handleMenuClick(event, top);
            } catch (Throwable t) {
                TT.log("[装备/四工具] 菜单点击异常: " + t);
            }
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        HOE_TICK.clear();
        HOE_CYCLE.clear();
        FARMLAND.clear();
        busy = false;
    }
}
