package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Perms;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 「储物空间开辟石」的原生实现（对应 {@code scripts/道法/储物空间开辟.js}）。
 *
 * <p>右键持有 {@code TR_CWKJKPS} 的玩家 → 在插件数据目录下写出
 * {@code addon_configs/糖然科技/储物空间/<玩家游戏名>.json}（内容 {@code {"unlocked": true}}），
 * 并消耗一颗开辟石。文件已存在时不消耗、不重复开辟。</p>
 *
 * <p>存档路径刻意沿用原 RSC 版的相对路径，这样老档里已经开辟过的玩家不会被重置。</p>
 */
public final class StorageSpaceUnlockScript implements NativeLifecycleScript {

    private static final String ITEM_ID = "TR_CWKJKPS";
    /** 存档子目录（相对插件数据目录）。 */
    private static final String DATA_PATH = "addon_configs/糖然科技/储物空间";

    private static final String PREFIX =
            "§f[§x§B§8§E§3§F§F储§x§7§F§C§9§E§8物§x§4§A§A§3§C§7空§x§D§4§F§1§F§9间§f] ";

    @Override
    public Object invoke(String fn, Object... args) {
        if ("onUse".equals(fn) && args.length > 0
                && args[0] instanceof PlayerInteractEvent event) {
            handle(event);
            return Boolean.TRUE;
        }
        return null;
    }

    private void handle(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return;

        SlimefunItem sf = SlimefunItem.getByItem(held);
        if (sf == null || !ITEM_ID.equals(sf.getId())) return;

        File dir = new File(TT.plugin.getDataFolder(), DATA_PATH);
        if (!dir.exists() && !dir.mkdirs()) {
            player.sendMessage(PREFIX + "§c储物空间存档目录创建失败，请联系管理！");
            return;
        }

        File file = new File(dir, player.getName() + ".json");
        if (AddonPaths.docExists(file)) {
            player.sendMessage(PREFIX + "§c你已经开辟过储物空间了！");
            return;
        }

        // 先落盘再扣道具：原顺序是 consumeOne 之后才 Files.write，一旦写入抛
        // IOException（磁盘满/目录权限），开辟石已经被扣掉但开辟没生效 —— 吞物。
        // 现在后端是 SQLite（写失败会返回 false），语义不变：写不进去就不扣道具。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unlocked", Boolean.TRUE);
        if (!AddonPaths.writeJson(file, data)) {
            TT.log("储物空间存档写入失败: " + file.getAbsolutePath());
            player.sendMessage(PREFIX + "§c储物空间开辟失败，请联系管理！");
            return;
        }
        // 玩法准入也发一枚权限节点：管理员可以用权限组批量发放/回收储物资格，
        // 判定处（StorageSpaceScript#hasAccess）是「节点或存档」任一成立即放行。
        Perms.grant(player, Perms.NODE_STORAGE, true);

        consumeOne(player);

        playEffects(player);
        player.sendMessage(PREFIX + "§a储物空间开辟成功！现在可以使用储物空间了。");
    }

    private static void consumeOne(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack item = inv.getItemInMainHand();
        if (item == null || item.getType().isAir()) return;
        if (item.getAmount() <= 1) {
            inv.setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
            inv.setItemInMainHand(item);
        }
    }

    private static void playEffects(Player player) {
        try {
            var loc = player.getLocation();
            var world = loc.getWorld();
            var at = loc.clone().add(0, 1, 0);
            world.spawnParticle(Particle.END_ROD, at, 30, 0.5, 0.5, 0.5, 0.1);
            world.spawnParticle(Particle.CLOUD, at, 20, 0.5, 0.5, 0.5, 0.05);
            world.playSound(loc, "block.end_portal.spawn", 1.0f, 0.8f);
        } catch (Throwable ignored) {
            // 特效失败不影响道具功能
        }
    }

    @Override
    public Iterable<Listener> listeners() {
        // 右键入口走 ScriptItem → invoke("onUse") 这一条通道即可。
        // 刻意不再自注册 PlayerInteractEvent：两条通道同时存在会让一次右键先开辟成功、
        // 紧接着第二条通道又提示「你已经开辟过储物空间了」。
        return Collections.emptyList();
    }
}
