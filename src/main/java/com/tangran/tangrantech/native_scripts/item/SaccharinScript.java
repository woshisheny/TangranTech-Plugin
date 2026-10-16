package com.tangran.tangrantech.native_scripts.item;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.native_scripts.NativePluginHelper;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 「糖精获取」的原生实现（对应 {@code scripts/糖精获取.js}）。
 *
 * <p>右键食用 {@code TR_SUGAR}：给予永久饱和效果（{@code Integer.MAX_VALUE} 刻、等级 1、
 * 无粒子、无图标），消耗一颗糖，播放进食音效；40 刻后播放饱嗝音效并按递增概率产出
 * {@code TR_TJ}。概率初始 10%，每次失败 +10%，成功即重置回 10%，按玩家 UUID 记忆。</p>
 */
public final class SaccharinScript implements NativeLifecycleScript {

    private static final String SUGAR_ID = "TR_SUGAR";
    private static final String REWARD_ID = "TR_TJ";

    private static final int BASE_CHANCE = 10;
    private static final int CHANCE_STEP = 10;
    private static final long BURP_DELAY = 40L;

    private static final Random RANDOM = new Random();

    /** 玩家 UUID → 当前成功概率（%）。 */
    private static final Map<UUID, Integer> SUCCESS_CHANCE = new HashMap<>();

    @Override
    public Object invoke(String fn, Object... args) {
        switch (fn) {
            case "onUse":
                if (args.length > 0 && args[0] instanceof PlayerInteractEvent event) {
                    if (isTrSugar(event.getItem())) {
                        grantSaturationAndConsume(event.getPlayer());
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            case "eat":
                if (args.length > 0 && args[0] instanceof Player p) {
                    grantSaturationAndConsume(p);
                    return Boolean.TRUE;
                }
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 判断物品是否为「糖然牌糖」。 */
    public static boolean isTrSugar(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        SlimefunItem sugar = SlimefunItem.getById(SUGAR_ID);
        return sugar != null && sugar.getItem().isSimilar(item);
    }

    /** 吃掉一颗糖并安排后续产出。 */
    public static void grantSaturationAndConsume(Player player) {
        try {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SATURATION,
                    Integer.MAX_VALUE,
                    0,
                    false,
                    false,
                    false));
        } catch (Throwable ignored) {
            // 效果添加失败不阻断后续流程
        }

        removeOneSugar(player);
        playSound(player, "entity.generic.eat", 1.0f, 1.0f);
        player.sendMessage("§x§b§e§6§c§0§e吃饱啦！");

        NativePluginHelper.runLater(() -> {
            try {
                if (!player.isOnline()) return;
                playSound(player, "entity.player.burp", 1.0f, 1.0f);
                tryGiveTj(player);
            } catch (Throwable t) {
                TT.log("糖精产出回调出错: " + t);
            }
        }, BURP_DELAY);
    }

    /** 按概率产出 {@code TR_TJ}；成功返回 true。 */
    public static boolean tryGiveTj(Player player) {
        int chance = getChance(player);
        if (RANDOM.nextDouble() * 100 < chance) {
            SlimefunItem sf = SlimefunItem.getById(REWARD_ID);
            if (sf == null) {
                player.sendMessage("§c无法生成" + REWARD_ID + "物品，请联系管理");
                return false;
            }
            ItemStack reward = sf.getItem().clone();
            player.getWorld().dropItemNaturally(player.getLocation(), reward);
            player.sendMessage("§x§f§0§0§0§b§c拉出一坨奇妙的东西?");
            setChance(player, BASE_CHANCE);
            return true;
        }
        setChance(player, chance + CHANCE_STEP);
        return false;
    }

    private static int getChance(Player player) {
        UUID id = player.getUniqueId();
        Integer v = SUCCESS_CHANCE.get(id);
        if (v == null) {
            SUCCESS_CHANCE.put(id, BASE_CHANCE);
            return BASE_CHANCE;
        }
        return v;
    }

    private static void setChance(Player player, int value) {
        SUCCESS_CHANCE.put(player.getUniqueId(), Math.min(100, Math.max(0, value)));
    }

    private static void removeOneSugar(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        int amount = hand.getAmount();
        if (amount > 1) {
            hand.setAmount(amount - 1);
            inv.setItemInMainHand(hand);
        } else {
            inv.setItemInMainHand(null);
        }
    }

    private static void playSound(Player player, String sound, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Throwable ignored) {
            // 音效缺失不应抛给玩家
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 右键入口走 ScriptItem → invoke("onUse") 这一条通道即可。
        // 刻意不再自注册 PlayerInteractEvent：两条通道同时存在会导致一次右键
        // 触发两次 grantSaturationAndConsume，把玩家手里的糖一次吃掉两颗。
        return Collections.emptyList();
    }
}
