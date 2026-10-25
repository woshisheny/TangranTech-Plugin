package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.MainHand;

/**
 * 糖然本体的两种载体（2026-10-25 由纯盔甲架改造）。
 *
 * <ul>
 *   <li><b>玩家模型（1.21.9+ 的 {@link Mannequin}）</b>：使用玩家名 {@code Tang_ran}
 *       的档案数据渲染玩家外观；装备走 {@code EntityEquipment}（玩家模型天然可穿），
 *       转向沿用 {@code setRotation}。盔甲架的肢体动作全部舍去（玩家模型没有可摆的关节），
 *       {@code setImmovable(true)} 补上玩家模型会被推挤的问题。</li>
 *   <li><b>盔甲架（低版本回退）</b>：与改造前完全一致——带手臂、无底座；
 *       老服务端没有 {@code Mannequin} 类或生成失败时走这条。</li>
 * </ul>
 *
 * <p>注意 {@code Mannequin} 的装备槽没有「禁拿」通道（{@code setDisabledSlots} 是盔甲架
 * 专属），但玩家模型本身不存在右键换装的交互路径，天然不会被扒装备。</p>
 */
public final class TangranBody {

    /** 玩家模型使用的档案名（皮肤与名字都来自它）。 */
    public static final String PROFILE_NAME = "Tang_ran";

    private TangranBody() {}

    /** 运行服务端是否带 Mannequin API（1.21.9+）。探测一次后缓存。 */
    public static boolean mannequinSupported() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个实体是不是玩家模型本体。
     *
     * <p>用 {@code EntityType} 判断而不是 {@code instanceof}：低版本服务端没有
     * {@code Mannequin} 类，instanceof 的类引用在低版本会在首次执行时直接
     * {@code NoClassDefFoundError}；枚举名查找失败只会抛异常并被吞掉。</p>
     */
    public static boolean isMannequin(Entity e) {
        try {
            return e.getType() == EntityType.valueOf("MANNEQUIN");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 生成糖然本体（无敌 / 无重力 / 带名字），玩家模型优先、盔甲架回退。
     * 装备由调用方按载体类型补（玩家模型的头部就是皮肤，不再戴头颅）。
     */
    public static Entity spawn(World world, Location loc, String customName) {
        if (mannequinSupported()) {
            try {
                Mannequin m = world.spawn(loc, Mannequin.class);
                configureMannequin(m, customName);
                return m;
            } catch (Throwable t) {
                BossLog.warn("Mannequin 本体生成失败，回退盔甲架: " + t);
            }
        }
        return spawnFallbackStand(world, loc, customName);
    }

    private static void configureMannequin(Mannequin m, String customName) {
        m.setInvulnerable(true);
        m.setGravity(false);
        m.setImmovable(true);
        m.setAI(false);
        m.setCanPickupItems(false);
        m.setRemoveWhenFarAway(false);
        m.setCustomName(customName);
        m.setCustomNameVisible(true);
        try {
            // 清掉人偶默认的描述文字（会显示在名字下面）
            m.setDescription(null);
        } catch (Throwable ignored) {
            // 忽略
        }
        m.setMainHand(MainHand.RIGHT);
        try {
            // 只给名字的档案：客户端/服务端按 Tang_ran 解析皮肤，服务端无需预取
            m.setProfile(ResolvableProfile.resolvableProfile().name(PROFILE_NAME).build());
        } catch (Throwable t) {
            BossLog.warn("Mannequin 档案设置失败（保持默认皮肤）: " + t);
        }
    }

    /** 盔甲架回退形态：与原实现逐项一致（带手臂、无底座）。 */
    private static ArmorStand spawnFallbackStand(World world, Location loc, String customName) {
        ArmorStand stand = world.spawn(loc, ArmorStand.class);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setCanPickupItems(false);
        stand.setRemoveWhenFarAway(false);
        stand.setCustomName(customName);
        stand.setCustomNameVisible(true);
        return stand;
    }

    /** 装备槽全部禁用（盔甲架专属；玩家模型没有换装通道，不需要）。 */
    public static void lockArmorStandSlots(LivingEntity e) {
        try {
            if (e instanceof ArmorStand stand) {
                stand.setDisabledSlots(new org.bukkit.inventory.EquipmentSlot[]{
                        org.bukkit.inventory.EquipmentSlot.HEAD,
                        org.bukkit.inventory.EquipmentSlot.CHEST,
                        org.bukkit.inventory.EquipmentSlot.LEGS,
                        org.bukkit.inventory.EquipmentSlot.FEET,
                        org.bukkit.inventory.EquipmentSlot.HAND,
                        org.bukkit.inventory.EquipmentSlot.OFF_HAND,
                });
            }
        } catch (Throwable t) {
            if (TT.plugin != null) TT.plugin.getLogger().warning("装备槽锁定失败: " + t);
        }
    }
}
