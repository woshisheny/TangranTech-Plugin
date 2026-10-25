package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import com.tangran.tangrantech.native_scripts.daofa.Pofabow;

/**
 * 天实商店 + 增益系统 + 吸血 + 水瓶链（原脚本 7727-8653 / 11022-11213 行，批次 6c）。
 *
 * <h2>四块内容</h2>
 * <ul>
 *   <li><b>天实商店</b>：右键天实（悦灵本体 / 头顶展示架）开 54 格菜单，
 *       主页 4 分类（农作物 / 增益购买 / 土壤 / 道具兑换）；土壤类只认左键取出。</li>
 *   <li><b>增益购买</b>：攻击 +3/级（上限 10）、护甲 +1/级（上限 10）、吸血 +5%/级（上限 4）、
 *       四重祝福 1 次（抗性/吸收/饱和/生命提升各 1）——次数存账本（{@link TwinLedger} 的 up* 键），
 *       属性用带 {@code tr_xu_} 前缀键的修饰符挂上，<b>只在双子阶段生效</b>，
 *       60 秒巡检保证「离开阶段就收掉、记录保留」。</li>
 *   <li><b>吸血</b>：近战命中按最终伤害的 pct% 回血（只在本局双子阶段生效）。</li>
 *   <li><b>水瓶链</b>：玻璃瓶 →（右键湿润耕地）水瓶 →（右键炼药锅）储水 →（铁桶右键）水桶。</li>
 * </ul>
 *
 * <h2>两个与原脚本的实现差异（已写进转化报告 9.8）</h2>
 * <ul>
 *   <li>1.21 的 {@link Inventory} 没有 {@code getTitle()}，分类判定改走
 *       {@code event.getView().getTitle()}（原脚本的 {@code top.getTitle()} 写法直接编译不过）。</li>
 *   <li>「双子阶段加成写进账本 JSON 的 twinStage 对象」省略 —— Java 账本是
 *       {@code Map<String,Integer>}，购买次数本身就是权威数据，数值可由次数×每级推得。</li>
 * </ul>
 */
public final class TwinShop {

    private TwinShop() {}

    // ==================================================================
    // 常量（原脚本 XU_ATK_PER / XU_ARMOR_PER / XU_LS_PER / XU_BUFF4_TICKS）
    // ==================================================================

    /** 每级攻击力加成。 */
    private static final int ATK_PER = 3;
    /** 每级护甲加成。 */
    private static final int ARMOR_PER = 1;
    /** 每级吸血百分比。 */
    private static final int LS_PER = 5;
    /** 四重祝福单次时长（1 小时，巡检任务会续）。 */
    private static final int BUFF4_TICKS = 20 * 60 * 60;

    /** 主菜单标题（原 XU_TRADE_TITLE）。 */
    private static final String MAIN_TITLE = "§d§l双子经营 · 天实";

    // ==================================================================
    // 商店数据（原脚本 XU_SHOP_CATS / XU_SHOP_GOODS，价格与槽位原样照抄）
    // ==================================================================

    /** 分类：id / 槽位 / 图标 / 名称 / 说明。 */
    private record Cat(String id, int slot, Material icon, String name, List<String> lore) {}

    /** 商品：槽位 / 图标 / 名称 / 说明 / 价格 / 发放（材质×数量）/ 增益键 / 上限 / 取出 / 破法弓。 */
    private record Goods(int slot, Material icon, String name, boolean loreAsItem, String itemName,
                         List<String> lore, Map<String, Integer> price,
                         String[][] give, String upgrade, int max,
                         boolean take, boolean bow) {}

    private static final List<Cat> CATS = List.of(
            new Cat("crop", 0, Material.WHEAT, "§a§l农作物类", List.of(
                    "§7兑换初始作物：胡萝卜 → 西瓜 → 甘蔗", "§7→ 下界疣 → 可可豆", "§7收割即可累积对应点数")),
            new Cat("buff", 1, Material.GOLDEN_APPLE, "§6§l增益购买类", List.of(
                    "§7购买永久增益：攻击力 / 护甲值", "§7吸血效果 / 四重祝福")),
            new Cat("soil", 2, Material.DIRT, "§e§l土壤类", List.of(
                    "§7左键取出 1 个土壤", "§7消耗对应泥土点 / 沙子点 / 灵魂点")),
            new Cat("item", 3, Material.CHEST, "§b§l道具兑换", List.of(
                    "§7炼药锅 / 玻璃瓶 / 铁桶 / 丛林原木", "§7破法弓 / 牛排")));

    private static final List<Goods> GOODS_CROP = List.of(
            new Goods(11, Material.CARROT, "§a胡萝卜 §f×1", false, null,
                    List.of("§7种在耕地上，收割给 §f萝卜点"),
                    TwinLedger.price("coin", 1, "wood", 1), new String[][] {{"CARROT", "1"}},
                    null, 0, false, false),
            new Goods(12, Material.MELON_SEEDS, "§a西瓜种子 §f×1", false, null,
                    List.of("§7收割西瓜给 §f西瓜点"),
                    TwinLedger.price("coin", 5, "wood", 5, "carrot", 128), new String[][] {{"MELON_SEEDS", "1"}},
                    null, 0, false, false),
            new Goods(13, Material.SUGAR_CANE, "§a甘蔗 §f×1", false, null,
                    List.of("§7种在沙子上（旁边要有水）", "§7收割给 §f甘蔗点"),
                    TwinLedger.price("coin", 25, "wood", 25, "melon", 64), new String[][] {{"SUGAR_CANE", "1"}},
                    null, 0, false, false),
            new Goods(14, Material.NETHER_WART, "§a下界疣 §f×1", false, null,
                    List.of("§7种在灵魂沙上，收割给 §f下界疣点"),
                    TwinLedger.price("coin", 125, "wood", 125, "cane", 128), new String[][] {{"NETHER_WART", "1"}},
                    null, 0, false, false),
            new Goods(15, Material.COCOA_BEANS, "§a可可豆 §f×1", false, null,
                    List.of("§7种在丛林原木侧面，收割给 §f可可豆点"),
                    TwinLedger.price("coin", 625, "wood", 250, "wart", 256), new String[][] {{"COCOA_BEANS", "1"}},
                    null, 0, false, false));

    private static final List<Goods> GOODS_BUFF = List.of(
            new Goods(11, Material.NETHERITE_SWORD, "§6攻击力提升 §f+3", false, null,
                    List.of("§7上限 §f+30 攻击力"),
                    TwinLedger.price("coin", 10), null, "upAtk", 10, false, false),
            new Goods(12, Material.NETHERITE_CHESTPLATE, "§6护甲值提升 §f+1", false, null,
                    List.of("§7上限 §f+10 护甲值"),
                    TwinLedger.price("coin", 5, "mineral", 5), null, "upArmor", 10, false, false),
            new Goods(13, Material.RED_DYE, "§6吸血效果 §f+5%", false, null,
                    List.of("§7上限 §f20%§7（近战命中回血）"),
                    TwinLedger.price("coin", 20, "carrot", 32), null, "upLs", 4, false, false),
            new Goods(14, Material.ENCHANTED_GOLDEN_APPLE, "§6四重祝福 §f抗性·吸收·饱和·生命", false, null,
                    List.of("§7永久获得 抗性提升1 / 伤害吸收1", "§7饱和1 / 生命提升1"),
                    TwinLedger.price("coin", 50, "dirt", 100, "wood", 64, "cane", 64),
                    null, "upBuff4", 1, false, false));

    private static final List<Goods> GOODS_SOIL = List.of(
            new Goods(11, Material.DIRT, "§e泥土 §f×1", false, null,
                    List.of("§7左键取出 1 个"), TwinLedger.price("dirt", 1),
                    new String[][] {{"DIRT", "1"}}, null, 0, true, false),
            new Goods(12, Material.SAND, "§e沙子 §f×1", false, null,
                    List.of("§7左键取出 1 个"), TwinLedger.price("sand", 1),
                    new String[][] {{"SAND", "1"}}, null, 0, true, false),
            new Goods(13, Material.SOUL_SAND, "§e灵魂沙 §f×1", false, null,
                    List.of("§7左键取出 1 个"), TwinLedger.price("soul", 1),
                    new String[][] {{"SOUL_SAND", "1"}}, null, 0, true, false));

    private static final List<Goods> GOODS_ITEM = List.of(
            new Goods(11, Material.CAULDRON, "§b炼药锅 §f×1", false, null,
                    List.of("§7水瓶右键它储存水源", "§7铁桶右键它取得水桶"),
                    TwinLedger.price("mineral", 128), new String[][] {{"CAULDRON", "1"}},
                    null, 0, false, false),
            new Goods(12, Material.GLASS_BOTTLE, "§b玻璃瓶 §f×1", true, "§b玻璃瓶",
                    List.of("§7右键湿润的耕地获取水瓶", "§7水瓶右键炼药锅储存水源", "§7使用桶右键炼药锅获取水桶~"),
                    TwinLedger.price("glass", 3), new String[][] {{"GLASS_BOTTLE", "1"}},
                    null, 0, false, false),
            new Goods(13, Material.BUCKET, "§b铁桶 §f×1", false, null,
                    List.of("§7右键已储水的炼药锅换水桶"),
                    TwinLedger.price("mineral", 64), new String[][] {{"BUCKET", "1"}},
                    null, 0, false, false),
            new Goods(14, Material.JUNGLE_LOG, "§b丛林原木 §f×1", false, null,
                    List.of("§7可可豆只能种在丛林原木侧面"),
                    TwinLedger.price("wood", 128), new String[][] {{"JUNGLE_LOG", "1"}},
                    null, 0, false, false),
            new Goods(15, Material.BOW, "§d破法弓 §f×1", false, null,
                    List.of("§7兑换后双子护体解除", "§7只有破法弓能伤到他们"),
                    TwinLedger.price5("cocoa", 512, "mineral", 1024, "wood", 512, "terracotta", 256, "coin", 768),
                    null, null, 0, false, true),
            new Goods(16, Material.COOKED_BEEF, "§b牛排 §f×1", false, null,
                    List.of("§7吃掉恢复饥饿值"),
                    TwinLedger.price("coin", 10), new String[][] {{"COOKED_BEEF", "1"}},
                    null, 0, false, false));

    private static List<Goods> goodsOf(String cat) {
        return switch (cat == null ? "main" : cat) {
            case "crop" -> GOODS_CROP;
            case "buff" -> GOODS_BUFF;
            case "soil" -> GOODS_SOIL;
            case "item" -> GOODS_ITEM;
            default -> List.of();
        };
    }

    private static String titleOf(String cat) {
        return switch (cat == null ? "main" : cat) {
            case "crop" -> "§d§l双子经营 · 天实 §8| §a农作物类";
            case "buff" -> "§d§l双子经营 · 天实 §8| §6增益购买类";
            case "soil" -> "§d§l双子经营 · 天实 §8| §e土壤类";
            case "item" -> "§d§l双子经营 · 天实 §8| §b道具兑换";
            default -> MAIN_TITLE;
        };
    }

    /** 从标题反查分类（null = 不是本店的界面）。 */
    private static String catOfTitle(String title) {
        try {
            if (title == null || !title.contains("双子经营")) return null;
            if (title.contains("农作物类")) return "crop";
            if (title.contains("增益购买类")) return "buff";
            if (title.contains("土壤类")) return "soil";
            if (title.contains("道具兑换")) return "item";
            return "main";
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 关掉该玩家还开着的本商店 GUI（双子经营收场时调）。
     *
     * <p>账本跨局保留，收场后残留的 GUI 若不关闭，点一下仍能走到 {@link #onClick}——
     * 那里虽有阶段闸兜底，主动关掉才是正道（玩家不用先点一下才发现商店失效）。</p>
     */
    public static void closeFor(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            InventoryView ov = player.getOpenInventory();
            if (ov != null && ov.getTopInventory() != null
                    && catOfTitle(ov.getTitle()) != null) {
                player.closeInventory();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 菜单持有者（只做标记；原脚本 XU_TRADE_HOLDER 的 Java 版）。 */
    private static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // ==================================================================
    // 打开 / 绘制 / 点击
    // ==================================================================

    /** 打开天实商店（{@code cat} 传 null 开主页）。 */
    public static boolean open(Player player, String cat) {
        try {
            if (player == null || !player.isOnline()) return false;
            Inventory inv = Bukkit.createInventory(new Holder(), 54, titleOf(cat));
            draw(inv, player, cat);
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            BossLog.warn("天实商店打开失败: " + t);
            return false;
        }
    }

    private static void draw(Inventory inv, Player player, String cat) {
        try {
            ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", null);
            for (int i = 0; i < 54; i++) inv.setItem(i, filler);
            String c = (cat == null) ? "main" : cat;
            if ("main".equals(c)) {
                for (Cat cc : CATS) {
                    inv.setItem(cc.slot, button(cc.icon, cc.name, cc.lore, null, "§a点击进入"));
                }
                List<String> bl = new ArrayList<>();
                bl.add("§7场上资源挖到即记账（不占背包）。");
                bl.add("§7矿石单方块 / 橡木 / 土壤 挖完立刻补。");
                bl.add("§7当前账本：" + TwinLedger.text(player));
                inv.setItem(4, named(Material.BOOK, "§d§l双子经营 · 账本", bl));
            } else {
                for (Goods gd : goodsOf(c)) {
                    List<String> lore = new ArrayList<>(gd.lore());
                    String status;
                    if (gd.upgrade() != null) {
                        int cur = TwinLedger.count(player, gd.upgrade());
                        lore.add("§7已购：§f" + cur + "§7/§f" + gd.max());
                        status = (cur >= gd.max()) ? "§c已达上限" : "§a点击购买";
                    } else {
                        status = gd.take() ? "§a左键取出 1 个" : "§a点击兑换";
                    }
                    if (gd.bow()) {
                        // 破法弓：按钮直接用本体（不是普通弓），价格/状态补在 lore 里
                        ItemStack bowBtn = Pofabow.make(player);
                        ItemMeta bm = bowBtn.getItemMeta();
                        if (bm != null) {
                            List<String> bl2 = new ArrayList<>(lore);
                            if (gd.price() != null) {
                                bl2.add("§7---------");
                                bl2.add("§e价格：" + TwinLedger.priceText(gd.price()));
                            }
                            bl2.add(status);
                            bm.setLore(bl2);
                            bowBtn.setItemMeta(bm);
                        }
                        inv.setItem(gd.slot(), bowBtn);
                        continue;
                    }
                    inv.setItem(gd.slot(), button(gd.icon(), gd.name(), lore, gd.price(), status));
                }
                inv.setItem(45, named(Material.ARROW, "§e返回", null));
            }
            inv.setItem(49, named(Material.BARRIER, "§c§l关闭", null));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            if (lore != null) m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private static ItemStack button(Material icon, String name, List<String> lore,
                                    Map<String, Integer> price, String actionText) {
        ItemStack it = new ItemStack(icon, 1);
        try {
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                m.setDisplayName(name);
                List<String> l = new ArrayList<>(lore);
                if (price != null) {
                    l.add("§7---------");
                    l.add("§e价格：" + TwinLedger.priceText(price));
                }
                l.add(actionText != null ? actionText : "§a点击兑换");
                m.setLore(l);
                it.setItemMeta(m);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return it;
    }

    /** 发放商品（loreAsItem 的把菜单说明写上物品）。 */
    private static void give(Player player, Goods gd) {
        try {
            if (gd.give() == null) return;
            for (String[] g : gd.give()) {
                Material mat = Material.matchMaterial(g[0]);
                if (mat == null) continue;
                int amt = 1;
                try {
                    amt = Math.max(1, Integer.parseInt(g[1]));
                } catch (Throwable ignored) {
                    // 忽略
                }
                ItemStack it = new ItemStack(mat, amt);
                if (gd.loreAsItem() && gd.lore() != null) {
                    ItemMeta m = it.getItemMeta();
                    if (m != null) {
                        m.setDisplayName(gd.itemName() != null ? gd.itemName() : gd.name());
                        m.setLore(new ArrayList<>(gd.lore()));
                        it.setItemMeta(m);
                    }
                }
                giveItem(player, it);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 发放物品（背包满掉脚下，原脚本 xuGiveItem 口径）。 */
    public static void giveItem(Player player, ItemStack item) {
        try {
            var left = player.getInventory().addItem(item);
            if (!left.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.updateInventory();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 商店点击（原脚本 {@code xuShopClickV2}）。
     *
     * <p>★ 1.21 的 {@link Inventory} 没有 {@code getTitle()}，分类判定走
     * {@code event.getView().getTitle()}。入口自己 {@code setCancelled(true)}，
     * 重复派发时第二遍跳过，避免一次买两份。</p>
     */
    public static void onClick(InventoryClickEvent event) {
        try {
            if (event.isCancelled()) return;
            InventoryView view = event.getView();
            if (view == null) return;
            String cat = catOfTitle(view.getTitle());
            if (cat == null) return;
            if (!(event.getWhoClicked() instanceof Player player)) return;
            // 阶段闸 + 召唤者闸（TwinMinigame.active 只认「点击者自己的经营局」）：
            // 商店 GUI 在收场后不会自动消失，账本又是跨局保留的——没有这道闸，
            // 残留 GUI 就能在阶段外继续消费（含破法弓兑换），路人也能花别人战局的账本。
            if (!TwinMinigame.active(player)) {
                event.setCancelled(true);
                try {
                    player.sendMessage("§7双子经营已结束，商店不可用。");
                    player.closeInventory();
                } catch (Throwable ignored) {
                    // 忽略
                }
                return;
            }
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 49) {
                player.closeInventory();
                return;
            }
            if ("main".equals(cat)) {
                for (Cat cc : CATS) {
                    if (cc.slot() == slot) {
                        open(player, cc.id());
                        return;
                    }
                }
                return;
            }
            if (slot == 45) {
                open(player, "main");
                return;
            }
            Goods gd = null;
            for (Goods g : goodsOf(cat)) {
                if (g.slot() == slot) {
                    gd = g;
                    break;
                }
            }
            if (gd == null) return;
            Inventory top = view.getTopInventory();

            // 土壤类：只认左键
            if (gd.take()) {
                if (event.getClick().isLeftClick()) {
                    if (!TwinLedger.pay(player, gd.price())) {
                        player.sendMessage("§c[天实] §f还缺：" + TwinLedger.shortText(player, gd.price()));
                        return;
                    }
                    give(player, gd);
                    player.sendMessage("§d[天实] §f取出：" + gd.name());
                    player.playSound(player.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.8f, 1.2f);
                    draw(top, player, cat);
                } else {
                    player.sendMessage("§e[天实] §f土壤类请用 §a左键 §f取出。");
                }
                return;
            }

            // 增益购买
            if (gd.upgrade() != null) {
                int cur = TwinLedger.count(player, gd.upgrade());
                if (cur >= gd.max()) {
                    player.sendMessage("§c[天实] §f已达上限。");
                    return;
                }
                if (!TwinLedger.pay(player, gd.price())) {
                    player.sendMessage("§c[天实] §f还缺：" + TwinLedger.shortText(player, gd.price()));
                    return;
                }
                TwinLedger.upgradeAdd(player, gd.upgrade(), 1);
                applyUpgrades(player);
                player.sendMessage("§d[天实] §f" + TwinLedger.name(gd.upgrade())
                        + " §a提升成功§f（" + (cur + 1) + "/" + gd.max() + "）");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.4f);
                draw(top, player, cat);
                return;
            }

            // 普通兑换（含破法弓）
            if (!TwinLedger.pay(player, gd.price())) {
                player.sendMessage("§c[天实] §f还缺：" + TwinLedger.shortText(player, gd.price()));
                return;
            }
            if (gd.bow()) {
                giveItem(player, Pofabow.make(player));
                TwinMinigame.unlockTwinBow(player);
            } else {
                give(player, gd);
                player.sendMessage("§d[天实] §f兑换成功：" + gd.name());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.2f);
            draw(top, player, cat);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 增益应用 / 收回（原脚本 xuApplyUpgrades / xuClearUpgrades / 巡检）
    // ==================================================================

    /** 吸血百分比（只在本局双子阶段生效；不在则 0）。 */
    public static int lifestealPct(Player player) {
        try {
            if (player == null || !TwinMinigame.active(player)) return 0;
            return TwinLedger.count(player, "upLs") * LS_PER;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 增益总次数>0 吗（巡检用；账本键都在）。 */
    private static boolean hasAnyUpgrade(Player player) {
        try {
            Map<String, Integer> led = TwinLedger.of(player);
            return led.getOrDefault("upAtk", 0) > 0
                    || led.getOrDefault("upArmor", 0) > 0
                    || led.getOrDefault("upLs", 0) > 0
                    || led.getOrDefault("upBuff4", 0) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清掉我们自己加的属性修饰符（键前缀 tr_xu_，别碰境界/丹药加的）。 */
    private static void attrSet(Player player, Attribute attribute, String keyName, double amount) {
        try {
            AttributeInstance attr = player.getAttribute(attribute);
            if (attr == null) return;
            NamespacedKey key = new NamespacedKey(TT.plugin(), keyName);
            for (AttributeModifier mod : new ArrayList<>(attr.getModifiers())) {
                try {
                    if (key.equals(mod.getKey())) {
                        attr.removeModifier(mod);
                    }
                } catch (Throwable ignored) {
                    // 单个失败不影响其它
                }
            }
            if (amount <= 0) return;
            attr.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 四重祝福的所有权标记（静态审查修正：摘效果前先看标记，别吞玩家自己喝的药水）。 */
    private static final String BUFF4_META = "TR_XU_BUFF4";

    /** 把账本里买过的增益套到玩家身上（只在双子阶段调用）。 */
    private static void applyUpgradesRaw(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            attrSet(player, Attribute.ATTACK_DAMAGE, "tr_xu_atk",
                    TwinLedger.count(player, "upAtk") * (double) ATK_PER);
            attrSet(player, Attribute.ARMOR, "tr_xu_armor",
                    TwinLedger.count(player, "upArmor") * (double) ARMOR_PER);
            if (TwinLedger.count(player, "upBuff4") > 0) {
                player.setMetadata(BUFF4_META,
                        new org.bukkit.metadata.FixedMetadataValue(TT.plugin(), Boolean.TRUE));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, BUFF4_TICKS, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, BUFF4_TICKS, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, BUFF4_TICKS, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, BUFF4_TICKS, 0, false, false, true));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按阶段套用/收掉（不在双子阶段只收属性，不碰玩家自己喝的药水）。 */
    public static void applyUpgrades(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            if (!TwinMinigame.active(player)) {
                removeUpgradeAttrs(player);
                return;
            }
            applyUpgradesRaw(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 只收属性修饰符。 */
    private static void removeUpgradeAttrs(Player player) {
        try {
            if (player == null) return;
            attrSet(player, Attribute.ATTACK_DAMAGE, "tr_xu_atk", 0);
            attrSet(player, Attribute.ARMOR, "tr_xu_armor", 0);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 收掉买来的增益（挑战通关 / 收场 / 重连时调；原脚本 {@code xuClearUpgrades}）。
     *
     * <p>只收效果，<b>不清账本记录</b> —— 下一局双子阶段还会自动套上。</p>
     */
    public static void clearUpgrades(Player player) {
        try {
            if (player == null) return;
            removeUpgradeAttrs(player);
            // 静态审查修正：只摘自己挂的四重祝福（有标记才摘），
            // 玩家自己喝的药水 / 信标 / 其它插件给的同名效果不动
            if (player.hasMetadata(BUFF4_META)) {
                player.removeMetadata(BUFF4_META, TT.plugin());
                player.removePotionEffect(PotionEffectType.RESISTANCE);
                player.removePotionEffect(PotionEffectType.ABSORPTION);
                player.removePotionEffect(PotionEffectType.SATURATION);
                player.removePotionEffect(PotionEffectType.HEALTH_BOOST);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 统一巡检：双子阶段中 → 套上；不在 → 收掉效果（记录保留）。 */
    private static void enforceScope(Player player) {
        try {
            if (player == null || !player.isOnline()) return;
            if (TwinMinigame.active(player)) {
                if (hasAnyUpgrade(player)) applyUpgradesRaw(player);
                return;
            }
            if (hasAnyUpgrade(player)) clearUpgrades(player);
            else removeUpgradeAttrs(player);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static volatile boolean enforceTaskStarted;

    /** 启动巡检任务：每 60 秒保证「买了的只在双子阶段生效、离开阶段就收掉」。 */
    private static void ensureEnforceTask() {
        if (enforceTaskStarted) return;
        enforceTaskStarted = true;
        Plugin plugin = TT.plugin();
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    enforceScope(p);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }, 200L, 1200L);
    }

    /** 商店被用过（打开/点买/吸血命中）时才起巡检任务。 */
    private static void touch() {
        ensureEnforceTask();
    }

    /** 重连时按当前阶段重套 / 收掉增益（原脚本 join 里的 xuEnforceUpgradeScope）。 */
    public static void onJoin(Player player) {
        touch();
        enforceScope(player);
    }

    // ==================================================================
    // 吸血（原脚本 xuOnLifestealHit）
    // ==================================================================

    /** 近战命中回血（MONITOR 优先级调；取消过的事件不回）。 */
    public static void onLifestealHit(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof Player d)) return;
            Entity victim = event.getEntity();
            if (victim == null || victim.equals(d)) return;
            int pct = lifestealPct(d);
            if (pct <= 0) return;
            double dmg = event.getFinalDamage();
            if (dmg <= 0) return;
            double heal = dmg * pct / 100.0d;
            if (heal <= 0) return;
            double maxHp = 20.0d;
            AttributeInstance attr = d.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) maxHp = attr.getValue();
            double nh = d.getHealth() + heal;
            if (nh > maxHp) nh = maxHp;
            if (nh > d.getHealth()) d.setHealth(nh);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 水瓶链（原脚本 xuOnWaterInteract，11022-11160 行）
    // ==================================================================

    /** 已储水的炼药锅坐标（原脚本 XU_WATER_SOURCE，重启丢失=重新储，无妨）。 */
    private static final Map<String, Boolean> WATER_SOURCE = new ConcurrentHashMap<>();

    /**
     * 清空储水炼药锅登记表（双子经营收场时调）。
     *
     * <p>表按坐标永久累积、无淘汰也无持久化（注释原话「重启丢失=重新储，无妨」）——
     * 收场清空与重启丢失同一口径：锅里的水还在，下次储水重新登记即可。</p>
     */
    public static void clearWaterSource() {
        WATER_SOURCE.clear();
    }

    private static String waterKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static boolean isWaterPotion(ItemStack item) {
        try {
            if (item == null || item.getType() != Material.POTION) return false;
            ItemMeta m = item.getItemMeta();
            return m instanceof PotionMeta pm && pm.getBasePotionType() == PotionType.WATER;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ItemStack makeWaterBottle() {
        ItemStack it = new ItemStack(Material.POTION, 1);
        try {
            ItemMeta m = it.getItemMeta();
            if (m instanceof PotionMeta pm) {
                pm.setBasePotionType(PotionType.WATER);
                pm.setDisplayName("§b水瓶");
                pm.setLore(List.of("§7右键炼药锅储存水源"));
                it.setItemMeta(pm);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return it;
    }

    /** 从背包扣掉 1 个指定材质（优先主手；原脚本 xuConsumeOne）。 */
    private static boolean consumeOne(Player player, Material mat) {
        try {
            var inv = player.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand != null && hand.getType() == mat) {
                int a = hand.getAmount();
                if (a <= 1) inv.setItemInMainHand(null);
                else {
                    hand.setAmount(a - 1);
                    inv.setItemInMainHand(hand);
                }
                return true;
            }
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack st = contents[i];
                if (st == null || st.getType() != mat) continue;
                int n = st.getAmount();
                if (n <= 1) inv.setItem(i, null);
                else {
                    st.setAmount(n - 1);
                    inv.setItem(i, st);
                }
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static void waterPoint(org.bukkit.block.Block b) {
        try {
            b.getWorld().spawnParticle(org.bukkit.Particle.SPLASH,
                    b.getX() + 0.5, b.getY() + 1.1, b.getZ() + 0.5, 12, 0.2, 0.2, 0.2, 0.01);
            b.getWorld().playSound(b.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1.4f);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 水瓶链右键（{@code PlayerInteractEvent} 主手）：返回 true = 已接管。
     *
     * <p>玻璃瓶 → 湿润耕地 → 水瓶 → 炼药锅储水 → 铁桶取水桶。</p>
     */
    public static boolean onWaterInteract(PlayerInteractEvent event) {
        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
            if (event.getHand() != EquipmentSlot.HAND) return false;
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) return false;
            ItemStack item = event.getItem();
            if (item == null) return false;
            org.bukkit.block.Block b = event.getClickedBlock();
            if (b == null) return false;
            String t = String.valueOf(b.getType());

            // ① 玻璃瓶 右键 湿润的耕地 → 水瓶
            if (item.getType() == Material.GLASS_BOTTLE && "FARMLAND".equals(t)) {
                int moist = 0;
                try {
                    if (b.getBlockData() instanceof Farmland fd) moist = fd.getMoisture();
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (moist <= 0) {
                    player.sendMessage("§e[天实] §f这耕地太干了，先润湿它。");
                    return true;
                }
                event.setCancelled(true);
                if (consumeOne(player, Material.GLASS_BOTTLE)) {
                    giveItem(player, makeWaterBottle());
                    waterPoint(b);
                    player.sendMessage("§d[天实] §f装了一瓶 §f水瓶§7（右键炼药锅储存水源）。");
                }
                return true;
            }

            // 炼药锅（空 / 已装水）
            if (!"CAULDRON".equals(t) && !"WATER_CAULDRON".equals(t)) return false;

            // ② 水瓶 右键 炼药锅 → 储存水源
            if (isWaterPotion(item)) {
                event.setCancelled(true);
                WATER_SOURCE.put(waterKey(b.getLocation()), Boolean.TRUE);
                if (consumeOne(player, Material.POTION)) {
                    waterPoint(b);
                    player.sendMessage("§d[天实] §f炼药锅已储满水源（拿铁桶右键它取水桶）。");
                }
                return true;
            }

            // ③ 铁桶 右键 已储水的炼药锅 → 水桶
            if (item.getType() == Material.BUCKET) {
                boolean has = WATER_SOURCE.containsKey(waterKey(b.getLocation()));
                if (!has && "WATER_CAULDRON".equals(t)) has = true;   // 手动灌满的炼药锅也算水源
                if (!has) {
                    // 没储水就别拦：让玩家能自己往里倒水
                    player.sendMessage("§e[天实] §f这口炼药锅还没储水（先用水瓶右键它）。");
                    return false;
                }
                event.setCancelled(true);
                if (consumeOne(player, Material.BUCKET)) {
                    giveItem(player, new ItemStack(Material.WATER_BUCKET, 1));
                    waterPoint(b);
                    player.sendMessage("§d[天实] §f取得 §f水桶§7（拿去浇在甘蔗旁边）。");
                }
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    // ==================================================================
    // 右键天实 / 天虚（原脚本 xuTryOpenTrade）
    // ==================================================================

    /**
     * 右键双子本体 / 展示架：天实开商店，天虚一句话；返回 true = 已接管。
     *
     * <p>★ 防重复打开：天实菜单已经开着就不再重开（右键实体事件可能重复派发，
     * 否则会连续闪菜单）。</p>
     */
    public static boolean onTradeOpen(PlayerInteractEntityEvent event) {
        try {
            Entity clicked = event.getRightClicked();
            if (clicked == null) return false;
            BossState st = BossFight.byEntity(clicked);
            boolean hit = false;
            boolean isVirtual = false;
            if (st != null && st.minigame != null) {
                BossState.GuardianSlot slot = st.guardians.twin;
                if (same(clicked, slot.body) || same(clicked, slot.displayA)) {
                    hit = true;
                    isVirtual = true;      // 天虚（Vex）
                } else if (same(clicked, slot.body2) || same(clicked, slot.displayB)) {
                    hit = true;            // 天实（Allay，开商店）
                }
            }
            if (!hit) {
                // 兜底：名字里带"天实/天虚"的实体（展示头颅等）也算。
                // 但只在该玩家正处于双子经营阶段时兜底 —— 展示架只在该阶段存在，
                // 且没有这道阶段闸的话，一张命名牌把任意实体改叫"天实"就能在
                // 阶段外打开商店 GUI、花掉残留账本（含破法弓兑换）。
                // 上面的 canonical 实体身份比对（same(...)）不受此闸影响。
                try {
                    if (!TwinMinigame.active(event.getPlayer())) return false;
                    String n = clicked.getCustomName();
                    if (n != null) {
                        if (n.contains("天虚")) {
                            hit = true;
                            isVirtual = true;
                        } else if (n.contains("天实")) {
                            hit = true;
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (!hit) return false;
            Player player = event.getPlayer();
            // 召唤者闸：战局是单人挑战，canonical 身份路径同样只对「正在经营中的
            // 召唤者本人」接管——路人右键别人的天实开不得商店（名字兜底路径
            // 在上面已过同款闸，这里补齐 canonical 路径）
            if (!TwinMinigame.active(player)) {
                try {
                    event.setCancelled(true);
                    player.sendMessage("§7这不是你的挑战，无法使用天实商店。");
                } catch (Throwable ignored) {
                    // 忽略
                }
                return true;
            }
            // 防重复打开
            try {
                InventoryView ov = player.getOpenInventory();
                if (ov != null && ov.getTopInventory() != null
                        && catOfTitle(ov.getTitle()) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            event.setCancelled(true);
            if (isVirtual) {
                player.sendMessage("§8[§f天虚§8] §f找我干蛤?略略略");
                return true;
            }
            if (!open(player, "main")) {
                player.sendMessage("§c[天实] 兑换菜单打开失败，请再试一次。");
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean same(Entity a, Entity b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            return a.equals(b) || a.getUniqueId().equals(b.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 事件监听（挂在 BossBattleScript 的 TwinMinigameListener 之外独立注册）
    // ==================================================================

    /** 6c 的事件入口（点击 / 右键实体 / 吸血）。 */
    public static final class ShopListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onClick(InventoryClickEvent event) {
            try {
                TwinShop.onClick(event);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onTradeOpen(PlayerInteractEntityEvent event) {
            try {
                TwinShop.onTradeOpen(event);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onLifesteal(EntityDamageByEntityEvent event) {
            try {
                TwinShop.onLifestealHit(event);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
