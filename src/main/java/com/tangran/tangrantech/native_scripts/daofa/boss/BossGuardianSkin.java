package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/**
 * 护法头颅（本体戴的 + 读档菜单里显示的）。
 *
 * <h2>当前进度：只有「按名字出头颅」这一条通道</h2>
 * <p>原脚本的头颅皮肤解析有<b>四条来源</b>，优先级从高到低：</p>
 * <ol>
 *   <li>本地覆盖文件（OP 手动放进去的皮肤 JSON）；</li>
 *   <li>SkinsRestorer 已存的皮肤（带签名，本服是离线服，{@code getOfflinePlayer} 拿不到皮肤）；</li>
 *   <li>Paper 的档案补全（{@code PlayerTextures} 通道）；</li>
 *   <li>兜底：一个随机 UUID + 护法名字的头颅（<b>永远有返回值，不会是 null</b>）。</li>
 * </ol>
 *
 * <p>本类现在只实现<b>第 4 条</b>：前三条的来源探测（SkinsRestorer / Paper 双通道）
 * 属于表现层，排在批次 9。之所以现在就把它独立成类，是因为
 * {@code summonPhaseTwoGuardian} 与 {@code makeTangranGuardianHead} 都要调它 ——
 * 两处各写一份「造头颅」的逻辑，将来接皮肤时必然只改一处。</p>
 *
 * <h2>两个必须保留的点</h2>
 * <ul>
 *   <li><b>永不返回 {@code null}</b>。原脚本的 {@code createGuardianHeadItem} 在所有
 *       皮肤来源都失败时也返回一个随机 UUID 的头颅；只有构造过程本身抛异常才返回 null。
 *       调用方（{@code equip.setHelmet(...)}）对 null 是容忍的，但「戴着一个没皮肤的头」
 *       比「光头」更接近原版观感。</li>
 *   <li><b>显示名与 lore 一次写入</b>。原脚本注释特别说明：分两次写 meta 会把皮肤丢掉
 *       （第二次 {@code setItemMeta} 用的是没有 profile 的旧 meta）。</li>
 * </ul>
 */
public final class BossGuardianSkin {

    private BossGuardianSkin() {}

    /**
     * 造一个护法头颅。
     *
     * @param guardianId 护法 id（同时也是头颅的「主人名」）
     * @param displayName 显示名（{@code null} 表示不写名字）
     * @param loreLines   lore（{@code null} / 空表示不写）
     */
    public static ItemStack head(String guardianId, String displayName, List<String> loreLines) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
            if (!(head.getItemMeta() instanceof SkullMeta meta)) return head;

            if (displayName != null && !displayName.isEmpty()) {
                meta.displayName(BossFx.legacy(displayName));
            }
            if (loreLines != null && !loreLines.isEmpty()) {
                meta.lore(BossFx.legacyList(loreLines));
            }

            // 兜底通道：随机 UUID + 护法名字（原脚本的第四条来源）
            String name = (guardianId == null || guardianId.isEmpty())
                    ? "tangran_guardian" : guardianId;
            PlayerProfile profile = org.bukkit.Bukkit.createProfile(UUID.randomUUID(), name);
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (Throwable t) {
            BossLog.warn("造护法头颅失败（" + guardianId + "）：" + t);
            return new ItemStack(Material.PLAYER_HEAD, 1);
        }
    }

    /**
     * 读档菜单里用的护法头像。
     *
     * <p>与 {@link #head} 目前走同一条兜底通道。区别只在<b>调用时机</b>：
     * 菜单是玩家点开的，原脚本特意强调「只读本地来源，绝不在开菜单时联网」——
     * 联网查皮肤会把菜单打开卡成几百毫秒。批次 9 接皮肤时也要守这条。</p>
     */
    public static ItemStack menuHead(String guardianId, String displayName, List<String> loreLines) {
        return head(guardianId, displayName, loreLines);
    }

    /** 给一个 profile 填皮肤（批次 9 用；现在只能填 Mojang 默认纹理，故不实现）。 */
    static void applySkin(PlayerProfile profile, PlayerTextures.SkinModel model, String url) {
        // 批次 9 实现：SkinsRestorer / Paper 双通道
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        // 无状态
    }

    /** 未使用的插件引用占位：保留 {@link TT#plugin} 的获取路径说明（批次 9 联网探测要用）。 */
    @SuppressWarnings("unused")
    private static org.bukkit.plugin.Plugin plugin() {
        return TT.plugin;
    }
}
