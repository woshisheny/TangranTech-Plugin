package com.tangran.tangrantech.native_scripts.daofa.boss;

import java.util.Iterator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * BOSS战 事件层总表补全（批次 8，原脚本 12635-13156 行的事件注册区）。
 *
 * <p>批次 1-7 各阶段顺手落了各自<b>必须</b>的规则（虚仆/决斗/弹反/挑战道具/水瓶链/商店），
 * 这里补齐剩下「整场挑战公共」的那一批 —— 全部照抄原脚本对应 handler 的口径：</p>
 *
 * <ol>
 *   <li>死亡掉落 / 复活还包 / 传送逃逸 / 退出判负 —— 一阶段监听已有，缺的只有<b>复活还包</b>。</li>
 *   <li>糖然展示架（召唤台本体 + 四阶段跪像）禁右键操作（{@code PlayerArmorStandManipulateEvent}
 *       + {@code PlayerInteractEntityEvent}），不能被扒装备/被拴绳。</li>
 *   <li>天降铁砧：原版坠落伤害一律取消，由脚本固定结算 1 点。</li>
 *   <li>清明之水期间新上的负面效果直接拦（只靠每秒清一次会"看起来没生效"）。</li>
 *   <li>场地内禁点火 / 禁烧毁；爆炸方块列表摘出场地 / 资源 / 地板。</li>
 *   <li>双子挑战道具（掉落物）不许被爆炸 / 火 / 攻击清掉。</li>
 *   <li>二阶段防收纳：禁与方块交互 / 禁开任何界面 / 禁 Q 丢弃
 *       （双子阶段与三阶段放开，但中章装备仍不可丢/不可进容器）。</li>
 *   <li>场地石英地板不许破坏。</li>
 *   <li>zqx125 箭矢命中由脚本独占结算；举盾格挡拦 {@code ProjectileHitEvent}
 *       （否则原版弹反分支直接 return，脚本格挡口径被抢走）。</li>
 *   <li>本体展示架被外部移除 / 被打死 → 整局收场（先摘字段再 remove 的自家转换不受影响）。</li>
 * </ol>
 *
 * <p><b>注册顺序</b>：本监听必须排在 {@link TwinShop.ShopListener} 之后 ——
 * 右键天实开商店的规则要先于「护法禁交互」跑。</p>
 */
public final class BossEventRules {

    private BossEventRules() {}

    // ==================================================================
    // 事件入口
    // ==================================================================

    public static final class Rules implements Listener {

        // ---- 复活还包（原脚本 PlayerRespawnEvent）----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onRespawn(PlayerRespawnEvent event) {
            try {
                Player p = event.getPlayer();
                if (p == null) return;
                var plugin = com.tangran.tangrantech.TT.plugin();
                if (plugin == null) return;
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        BossInventory.restore(p);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }, 1L);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 糖然展示架：禁拿装备 / 禁放装备（原脚本 PlayerArmorStandManipulateEvent）----

        @EventHandler(priority = EventPriority.LOWEST)
        public void onStandManipulate(PlayerArmorStandManipulateEvent event) {
            try {
                if (isProtectedStand(event.getRightClicked())) {
                    event.setCancelled(true);
                    event.getPlayer().updateInventory();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 右键实体：跪像/召唤台禁交互；护法本体禁一切交互；锁定容器实体（原脚本 12730-12742）----

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            try {
                Entity clicked = event.getRightClicked();
                if (clicked == null) return;
                // 天实交易已由 TwinShop.ShopListener 先处理；这里只管「禁交互」的份额
                if (isProtectedStand(clicked)) {
                    event.setCancelled(true);
                    return;
                }
                // 护法本体/展示架：拴绳、命名、剪毛之类一律取消
                if (isGuardianEntity(clicked)) {
                    event.setCancelled(true);
                    return;
                }
                Player player = event.getPlayer();
                if (BossChallengeState.isPhaseTwoLocked(player) && isLockedContainerEntity(clicked)) {
                    event.setCancelled(true);
                    warnLock(player, null);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 天降铁砧：原版坠落伤害取消，脚本固定结算 1 点（原脚本 12749-12756）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onFallingBlock(EntityDamageEvent event) {
            try {
                if (event.getCause() != EntityDamageEvent.DamageCause.FALLING_BLOCK) return;
                if (event.getEntity() instanceof Player p
                        && BossChallengeState.isPhaseTwoLocked(p)) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 清明之水：新上的负面效果直接拦（原脚本 EntityPotionEffectEvent HIGHEST）----

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPotionEffect(EntityPotionEffectEvent event) {
            try {
                if (!(event.getEntity() instanceof Player p)) return;
                if (!TwinMinigame.buffActive(p, "clear")) return;
                EntityPotionEffectEvent.Action act = event.getAction();
                if (act == EntityPotionEffectEvent.Action.REMOVED
                        || act == EntityPotionEffectEvent.Action.CLEARED) {
                    return;
                }
                PotionEffect newEf = event.getNewEffect();
                if (newEf == null) return;
                if (isBadEffect(newEf.getType())) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 场地内禁点火 / 禁烧毁（原脚本 BlockIgniteEvent / BlockBurnEvent）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onIgnite(BlockIgniteEvent event) {
            try {
                if (inAnyFightArena(event.getBlock()) || TwinMinigame.isResourceBlock(event.getBlock())) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onBurn(BlockBurnEvent event) {
            try {
                if (inAnyFightArena(event.getBlock()) || TwinMinigame.isResourceBlock(event.getBlock())) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 双子挑战道具防爆/防火/禁打（原脚本 EntityDamageEvent HIGHEST 12885）----

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onChallengeItemDamaged(EntityDamageEvent event) {
            try {
                if (!TwinMinigame.isChallengeItem(event.getEntity())) return;
                EntityDamageEvent.DamageCause cause = event.getCause();
                // 只拦爆炸/攻击/火类（原版消失/虚空照旧）
                if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                        || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                        || cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                        || cause == EntityDamageEvent.DamageCause.FIRE
                        || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                        || cause == EntityDamageEvent.DamageCause.LAVA) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 二阶段防收纳：禁与方块交互（原脚本 12976-12995）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onBlockInteractLock(PlayerInteractEvent event) {
            try {
                Action action = event.getAction();
                if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;
                Player player = event.getPlayer();
                if (!BossChallengeState.isPhaseTwoLocked(player)) return;
                if (twinStageUnlocked(player)) return;      // 双子/三阶段：方块交互放行
                if (event.isCancelled()) return;            // 御剑等已自行处理，不重复拦
                event.setCancelled(true);
                // 拿着二阶段道具（甘露/反击之盾）时不提示：那是想"用道具"，不是想开容器
                PlayerInventory inv = player.getInventory();
                boolean holdingP2Item =
                        BossPhaseTwoItems.kindOf(inv.getItemInMainHand()) != null
                                || BossPhaseTwoItems.kindOf(inv.getItemInOffHand()) != null;
                if (!holdingP2Item) {
                    warnLock(player, "§c糖然二阶段挑战期间，无法与方块交互！");
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 场地石英地板不许破坏（原脚本 BlockBreakEvent LOWEST）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onFloorBreak(BlockBreakEvent event) {
            try {
                if (!isPhaseTwoFloorBlock(event.getBlock())) return;
                event.setCancelled(true);
                event.getPlayer().sendMessage("§c场地石英地板不能被破坏。");
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 双子期间：中章装备不许进容器（原脚本 xuOnTwinContainerClick）----

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onContainerClick(InventoryClickEvent event) {
            try {
                if (!(event.getWhoClicked() instanceof Player player)) return;
                if (!inTwinStage(player)) return;
                var view = event.getView();
                if (view == null) return;
                var top = view.getTopInventory();
                if (top == null || top.getHolder() instanceof Player) return;
                int rawSlot = event.getRawSlot();
                boolean intoContainer = rawSlot >= 0 && rawSlot < top.getSize();
                if (!intoContainer && !event.isShiftClick()
                        && event.getHotbarButton() < 0) {
                    return;
                }
                // 静态审查修正：热键栏交换（数字键 1-9）时 currentItem 是容器内原物品
                // （常为 null），真正的来源是热键栏槽位——不补判就绕过了中章装备保护
                ItemStack stack = event.getCurrentItem();
                if (stack == null) stack = event.getCursor();
                if (stack == null && event.getHotbarButton() >= 0) {
                    stack = player.getInventory().getItem(event.getHotbarButton());
                }
                if (!isProtectedTwinStack(stack)) return;
                event.setCancelled(true);
                player.sendMessage("§c双子挑战期间，中章装备不能放进容器！");
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 爆炸方块列表：摘出地板 / 场地 / 资源（原脚本 EntityExplodeEvent LOWEST）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            try {
                List<org.bukkit.block.Block> list = event.blockList();
                if (list == null || list.isEmpty()) return;
                Iterator<org.bukkit.block.Block> it = list.iterator();
                boolean changed = false;
                while (it.hasNext()) {
                    org.bukkit.block.Block b = it.next();
                    if (isPhaseTwoFloorBlock(b) || inAnyFightArena(b)
                            || TwinMinigame.isResourceBlock(b)) {
                        it.remove();
                        changed = true;
                    }
                }
                if (changed) {
                    event.setYield(0f);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 二阶段防收纳兜底：禁开任何界面（原脚本 InventoryOpenEvent LOWEST）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onInventoryOpen(InventoryOpenEvent event) {
            try {
                if (!(event.getPlayer() instanceof Player player)) return;
                if (!BossChallengeState.isPhaseTwoLocked(player)) return;
                if (twinStageUnlocked(player)) return;      // 双子阶段：容器正常打开
                var inv = event.getInventory();
                if (inv != null && inv.getHolder() instanceof Player) return;  // 只放行自己的背包
                event.setCancelled(true);
                warnLock(player, "§c糖然二阶段挑战期间，无法打开任何界面！");
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 二阶段防收纳：禁 Q 丢弃（原脚本 PlayerDropItemEvent LOWEST）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            try {
                Player player = event.getPlayer();
                if (!BossChallengeState.isPhaseTwoLocked(player)) return;
                if (twinStageUnlocked(player)) {
                    // 双子阶段放开丢弃，但戮仙剑与糖b四工具仍不可丢
                    if (isProtectedTwinStack(event.getItemDrop().getItemStack())) {
                        event.setCancelled(true);
                        warnLock(player, "§c双子挑战期间，不能丢弃戮仙剑与糖b四工具！");
                    }
                    return;
                }
                event.setCancelled(true);
                warnLock(player, "§c糖然二阶段挑战期间，无法丢弃物品！");
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- zqx125 箭矢命中由脚本独占结算（原脚本 EntityDamageByEntityEvent LOWEST）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onGuardianArrowHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            try {
                Zqx125Guardian.onArrowDamage(event);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 举盾格挡拦原版弹反分支（原脚本 ProjectileHitEvent LOWEST 13105-13126）----

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onProjectileHit(ProjectileHitEvent event) {
            try {
                if (!(event.getEntity() instanceof org.bukkit.entity.Arrow arrow)) return;
                if (!(event.getHitEntity() instanceof Player hit)) return;
                BossState st = BossFight.byArrow(arrow);
                if (st == null) return;
                if (st.playerName == null || !st.playerName.equals(hit.getName())) return;
                if (BossPhaseTwoItems.isBlocking(st, hit)) {
                    // 取消 ⇒ 服务器内部 hitCancelled ⇒ 既不反弹也不结算，箭照常穿过，
                    // 格挡/伤害口径完全交给脚本的箭矢结算（见 Zqx125Guardian.onArrowDamage）
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 实体死亡：展示架被打死 → 整局收场；护法死了清掉落（原脚本 EntityDeathEvent）----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onEntityDeath(EntityDeathEvent event) {
            try {
                LivingEntity dead = event.getEntity();
                // ① 本体展示架 / 跪像被打死（自家转换会先摘字段，走到这里=外部干的）
                BossState st = BossFight.byEntity(dead);
                if (st != null && st.owns(dead) && isStandLike(st, dead)) {
                    BossBattleScript.forceStop(st, "本体展示架被移除");
                    return;
                }
                // ② 护法死了：原版掉落/经验清空（倒地结算由各自的追踪任务处理）
                if (st != null && st.owns(dead) && isGuardianBody(st, dead)) {
                    event.getDrops().clear();
                    event.setDroppedExp(0);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 实体移除：展示架被外部清掉 → 整局收场（原脚本 EntityRemoveEvent）----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onEntityRemove(EntityRemoveEvent event) {
            try {
                Entity e = event.getEntity();
                if (!(e instanceof ArmorStand)) return;
                BossState st = BossFight.byEntity(e);
                if (st == null) return;
                if (st.stand != null && same(st.stand, e)) {
                    st.stand = null;
                    BossBattleScript.forceStop(st, "本体展示架被移除");
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ==================================================================
    // 判定与工具
    // ==================================================================

    /** 糖然本体展示架 / 四阶段跪像（原脚本 isProtectedTangranStand）。 */
    private static boolean isProtectedStand(Entity e) {
        try {
            BossState st = BossFight.byEntity(e);
            if (st == null) return false;
            return (st.stand != null && same(st.stand, e))
                    || (st.tangran.kneelStand != null && same(st.tangran.kneelStand, e));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 展示架类实体（本体盔甲架 / 跪像）。 */
    private static boolean isStandLike(BossState st, Entity e) {
        return (st.stand != null && same(st.stand, e))
                || (st.tangran.kneelStand != null && same(st.tangran.kneelStand, e));
    }

    /** 护法本体（不含展示架）。 */
    private static boolean isGuardianBody(BossState st, Entity e) {
        for (BossState.GuardianSlot slot : st.guardians.all()) {
            if (same(slot.body, e) || same(slot.body2, e)) return true;
        }
        return false;
    }

    /** 护法本体 / 护法展示架（右键一律取消）。 */
    private static boolean isGuardianEntity(Entity e) {
        try {
            BossState st = BossFight.byEntity(e);
            return st != null && st.owns(e);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 锁定的容器实体（漏斗矿车 / 运输矿车 / 运输船，原脚本 isLockedContainerEntity）。 */
    private static boolean isLockedContainerEntity(Entity e) {
        return e instanceof org.bukkit.entity.minecart.HopperMinecart
                || e instanceof org.bukkit.entity.minecart.StorageMinecart
                || e instanceof org.bukkit.entity.ChestBoat;
    }

    /** 双子阶段 / 三阶段：方块交互、容器、丢弃放开（原脚本 xuTwinStageUnlocked 的主口径）。 */
    private static boolean twinStageUnlocked(Player player) {
        try {
            if (inTwinStage(player)) return true;
            BossState st = BossFight.bySummoner(player);
            if (st == null) st = BossFight.byName(player.getName());
            if (st == null) return true;      // 没有战局：锁是残留，不拦
            if (st.tangran.phaseThreeStarted) return true;
            for (BossState.GuardianSlot slot : st.guardians.all()) {
                if (alive(slot.body) || alive(slot.body2)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前在双子经营阶段吗。 */
    private static boolean inTwinStage(Player player) {
        return TwinMinigame.active(player);
    }

    /** 本体倒下前后「格子在任意一局的二阶段场地里」吗（原脚本 xuInAnyFightArena）。 */
    private static boolean inAnyFightArena(org.bukkit.block.Block b) {
        try {
            if (b == null) return false;
            for (BossState st : BossFight.all()) {
                BossArena.Region r = st.arena.twoRegion;
                if (r == null) continue;
                if (st.arena.twoWorld != null && !st.arena.twoWorld.equals(b.getWorld())) continue;
                int x = b.getX();
                int y = b.getY();
                int z = b.getZ();
                if (x >= r.minX() && x <= r.maxX() && y >= r.minY() && y <= r.maxY()
                        && z >= r.minZ() && z <= r.maxZ()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 二阶段场地的石英地板层（原脚本 isPhaseTwoArenaFloorBlock）。 */
    private static boolean isPhaseTwoFloorBlock(org.bukkit.block.Block b) {
        try {
            if (b == null || b.getType() != Material.QUARTZ_BLOCK) return false;
            for (BossState st : BossFight.all()) {
                BossArena.Region r = st.arena.twoRegion;
                if (r == null) continue;
                if (st.arena.twoWorld != null && !st.arena.twoWorld.equals(b.getWorld())) continue;
                if (b.getY() != r.minY()) continue;
                if (b.getX() < r.minX() || b.getX() > r.maxX()) continue;
                if (b.getZ() < r.minZ() || b.getZ() > r.maxZ()) continue;
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 中章装备（戮仙剑 + 糖b四工具）不许丢出 / 进容器（原脚本 isProtectedTwinStageStack）。 */
    private static boolean isProtectedTwinStack(ItemStack stack) {
        try {
            if (stack == null || !stack.hasItemMeta()) return false;
            String name = stack.getItemMeta().getDisplayName();
            if (name == null) return false;
            if (name.contains("戮仙剑")) return true;
            // 糖b四工具：按名字兜底（四件工具的名字固定）
            return name.contains("鸿蒙开天镐") || name.contains("太虚斩劫斧")
                    || name.contains("九幽覆海铲") || name.contains("长生种道锄");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清明之水要拦的负面效果（原脚本 XU_BAD_EFFECT_NAMES；1.21 API 没有 isBad()，走名单）。 */
    private static boolean isBadEffect(PotionEffectType type) {
        try {
            if (type == null) return false;
            String n = type.getName();
            return n.equals("SLOWNESS") || n.equals("SLOW")
                    || n.equals("MINING_FATIGUE") || n.equals("SLOW_DIGGING")
                    || n.equals("BLINDNESS") || n.equals("WITHER") || n.equals("POISON")
                    || n.equals("WEAKNESS") || n.equals("HUNGER")
                    || n.equals("NAUSEA") || n.equals("CONFUSION")
                    || n.equals("LEVITATION") || n.equals("UNLUCK") || n.equals("DARKNESS")
                    || n.equals("INSTANT_DAMAGE") || n.equals("HARM")
                    || n.equals("INFESTED") || n.equals("WEAVING") || n.equals("OOZING")
                    || n.equals("WIND_CHARGED") || n.equals("BAD_OMEN");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 锁定提示限流（1.5 秒一条，原脚本 PHASE2_LOCK_MSG_TIME）。 */
    private static final java.util.Map<String, Long> LOCK_MSG = new java.util.concurrent.ConcurrentHashMap<>();

    private static void warnLock(Player player, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = LOCK_MSG.get(player.getName());
            if (last != null && now - last < 1500L) return;
            LOCK_MSG.put(player.getName(), now);
            player.sendMessage(msg != null ? msg : "§c糖然二阶段挑战期间，无法与容器交互或打开储物空间！");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static boolean alive(Entity e) {
        try {
            return e != null && e.isValid() && !e.isDead();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean same(Entity a, Entity b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            return a.getUniqueId().equals(b.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
