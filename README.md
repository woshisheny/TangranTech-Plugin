# TangranTech（糖然科技）

RSC（RykenSlimefunCustomizer）附属项目 **糖然科技 TangranTech** 的原生化版本：
把原来依赖 RSC 运行时 + Graal.js 脚本的附属，改写为**独立可运行的 Slimefun 附属插件 jar**。

- 目标环境：Paper 1.21.x（编译期对齐 1.21.11 API），Slimefun4 4.9.x（`Slimefun4-*-Beta.jar`）
- 包名根：`com.tangran.tangrantech`
- 主类：`com.tangran.tangrantech.TangranTechPlugin`
- 输出：`build/libs/TangranTech-1.0.0-standalone.jar`

---

## 一、构建

```bash
./gradlew build
```

依赖全部走 `compileOnly`（运行期由服务器提供），只有本地 Slimefun jar 需要预置：

```
libs/Slimefun4-347d926-Beta.jar
```

`build.gradle.kts` 的 `processResources` 会把 `content/` 下的 13 个 yml 与 `content/data/**`
平铺到 jar 根目录（与原 RSC 附属的目录布局一致）。

---

## 二、与原 RSC 版本的对应关系

| 原 RSC | 本插件 | 说明 |
| --- | --- | --- |
| `items.yml` | `load/ItemsLoader` + `items/*` | 粘液物品；带 `script:` 的走 `ScriptItem` / `UnplaceableScriptItem` |
| `machines.yml` | `load/MachinesLoader` + `items/ScriptMachineItem` | 脚本驱动的方块机器 |
| `recipe_machines.yml` | `load/RecipeMachinesLoader` + `machines/TTRecipeMachine` | 配方机器（GUI + 能量 + 进度条） |
| `template_machines.yml` | `load/TemplateMachinesLoader` + `machines/TTTemplateMachine` | 模板机器 |
| `mb_machines.yml` | `load/MultiBlockMachinesLoader` + `machines/TTMultiBlockMachine` | 经典多方块（右键成形即合成） |
| `super_multi_block_machines.yml` | `load/SuperMultiBlockMachinesLoader` + `machines/TTSuperMultiBlockMachine` | 超多方块（多层结构矩阵） |
| `generators.yml` | `load/GeneratorsLoader` + `machines/TTGenerator` | 发电机 |
| `mat_generators.yml` | `load/MaterialGeneratorsLoader` + `machines/TTMaterialGenerator` | 材料生成器 |
| `menus.yml` | `load/MenusLoader` + `machines/MenuDef` | 机器 GUI 布局 |
| `groups.yml` | `load/GroupLoader` + `groups/TTButtonGroup` | 物品组；`type: button` + `actions` 走按钮组 |
| `recipe_types.yml` | `load/RecipeTypes` | 自定义配方类型 |
| `mob_drops.yml` | `load/MobDropsLoader` + `behavior/MobDrops` | 生物掉落 |
| `scripts/*.js` | `native_scripts/**` | **全部改成纯 Java 原生实现**，不使用任何 JS 引擎 |

脚本钩子契约（与原 RSC 一致）：

| 原 JS 钩子 | Java 入口 |
| --- | --- |
| `function onUse(event)` | `NativeScript#invoke("onUse", PlayerInteractEvent)` |
| `function tick(...)` | `invoke("tick", ScriptMachineItem.TickInfo)` |
| `function onButtonGroupClick(player, slot, clickedItem, clickAction, mode)` | `invoke("onButtonGroupClick", …)` |

---

## 三、脚本原生化的五条硬约定

这些都是移植过程中踩出来的，改动脚本时请遵守。

### 1. 右键物品只用 `onUse` 单一入口，禁止再自注册 `PlayerInteractEvent`

原 JS 脚本常同时存在两条通道（RSC 的 `onUse` 回调 + 脚本自己 `registerEvent`），
原版靠 Services 注册表清理黑魔法让两条通道往往只活一条。但在本插件里
`ScriptItemListener → ScriptItem.onRightClick → invoke("onUse")` 是**必然生效**的，
再自注册一个监听器就会**一次右键走两遍**。

已因此修掉三处真实 bug：`SaccharinScript`（一次吃掉两颗糖）、
`StorageSpaceUnlockScript`（先「开辟成功」又「已经开辟过了」）、
`TaixuHubScript`（原本靠 150ms 节流碰巧没出错，已改为不依赖节流）。

想给物品加右键行为，**写 `invoke("onUse", …)`，不要写监听器**。

### 2. 周期性机器逻辑优先用 `tick` 钩子，而不是 `onPlace`/`onBreak`

`ScriptMachineItem` 每刻回调 `invoke("tick", TickInfo)`（`block()` / `menu()`）。
用「按坐标维护的计数器」把 tick 压成想要的周期即可（例如 `age % 20 == 1` 对齐
`runTaskTimer(delay=0, period=20)` 的触发时刻）。

原脚本靠 `onPlace` 起的任务在**服务器重启后全部丢失**（已放置的机器不再恢复进度），
改用 `tick` 后天然免疫，也天然随方块被破坏/区块卸载而停止。

### 3. 物品身份只认粘液 id，绝不用显示名兜底

统一走 `TT.sfIdOf(ItemStack)`。原脚本注释里明确记录过这个坑：

- 「名字里含『原初刻印』就放行」→ 铁砧改名即可触发任务，连本脚本产出的奖励都会被误判；
- 「名字里含『糖天画戟』就放行」→ 铁砧改名即可白拿武器技能。

### 4. 右键投掷类物品必须 `setCancelled(true)`

材质是 `splash_potion` 这类物品，原版右键本身就会投掷。RSC 会无条件 cancel 掉，
本插件没有这层保护，不取消就会「自定义投掷 + 原版投掷」双发并多扣一颗。
（见 `LethalSugarWaterScript`。）

### 5. 跨脚本依赖 / 死代码不要照抄

原脚本里存在「调用另一个 JS 里定义的函数」和「引用未定义变量」的写法，靠
`try/catch` 吞异常，运行期表现为「什么都没发生」。原生化时应改为本类内的事件驱动实现，
并在 Javadoc 注明原写法与等价性论证。

例：`初章祭坛.js` 调用 RSC 自己的 `SuperMultiBlockManager.getProjectiles()` 清理发射器上的
展示实体——独立插件没有这个 API，且本插件机器从不生成此类实体，故退化为空实现
（若照搬「删半径内所有发射器上的 ItemDisplay」会误删玩家自己的展示实体）。

---

## 四、1.21.x API 适配清单

以下都是 javap 实测结论，写代码时按这个来。

| 旧写法 | 1.21 正确写法 | 引入版本 |
| --- | --- | --- |
| `Particle.REDSTONE` | `Particle.DUST` | 1.20.5 |
| `PotionEffectType.CONFUSION` | `PotionEffectType.NAUSEA` | 1.20.5 |
| `PotionEffectType.JUMP` | `PotionEffectType.JUMP_BOOST` | 1.20.5 |
| `livingEntity.setMaxHealth(x)` | `getAttribute(Attribute.MAX_HEALTH).setBaseValue(x)` | 1.21 移除 |
| `Attribute.GENERIC_MAX_HEALTH` | `Attribute.MAX_HEALTH` | 1.21.2 |
| `ItemFlag.HIDE_POTION_EFFECTS` | `ItemFlag.HIDE_ADDITIONAL_TOOLTIP` | 1.20.5 |
| `Vector#rotateAroundY(float)` | `Vector#rotateAroundY(double)` | 1.21 |
| `InventoryView`（抽象类） | `InventoryView`（接口） | 1.21 |

其他要点：

- **`Player#playSound(Location, String, float, float)` 仍然存在**，可以直接沿用原脚本的
  命名空间字符串音效名，不必换算成 `Sound` 枚举。
- **NMS 通常可以避开**：移除烟花组件用
  `itemStack.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.FIREWORKS)` 即可
  （编译期可用），不必走 `CraftItemStack.asNMSCopy`。
- `InventoryView` 跨版本问题集中在 `util/Views`（`Views.top(view)` / `Views.title(view)`）。
- `launchProjectile` 定义在 `ProjectileSource` 上（`LivingEntity` / `Player` 均继承）。
- `setTarget` 在 `Mob` 上而非 `LivingEntity`；`setRemoveWhenFarAway` 在 `LivingEntity` 上。
- 查询运行中机器进度走标准通道：
  `AContainer#getMachineProcessor().getOperation(loc)` → `CraftingOperation`
  （`getProgress()` / `getTotalTicks()` / `getIngredients()` / `getResults()`）。
- 方块身份判断用 `native_scripts/SlimefunBridge`（`itemId` / `blockId` / `isBlock`）。

---

## 五、内容存档路径

沿用原 RSC 版的相对路径（挂在插件数据目录下），保证老档数据不失效：

```
plugins/TangranTech/addon_configs/糖然科技/
├── 丹方权限/<玩家名>.json     { owned: [...], exp: 2000 }
├── 异火权限/<玩家名>.json     { owned: [...], selected: "" }
├── 境界/<玩家名>.json         { opened, realm, sugarPower, maxSugarPower, attrs, enabledAttrs }
├── 任务/<玩家名>.json         { completed: [...], ascensionStage: 0 }
└── 储物空间/<玩家名>.json     { unlocked: true }
```

JSON 读写统一走 `util/Json`（无外部依赖；`writePretty` 输出 2 空格缩进，
与原脚本 `JSON.stringify(x, null, 2)` 一致）。

---

## 六、脚本移植进度

**32 个脚本 / 已全部接入**。唯一分阶段进行中的是 `道法/BOSS战`（13452 行 / 465 个函数）：
日志、存档层（`BossProgress`）、场地层（`BossArena`）、状态容器（`BossState`）、
战局表（`BossFight`）、背包安全网（`BossInventory`）、门面桥接（`BossBattleScript`）已完成，
护法与阶段机制待续（批次表见 `转化报告.md` 第 9.4 节）。

> `BossState` 是**按模块切开**的状态容器（arena / tangran / guardians / phaseOne / phaseTwo /
> minigame / sword / cutscene）。这是刻意的：原脚本曾用一个 `phaseTwoGuardian` 字段
> 被三位护法共用，导致「拿戮仙剑砍 Katarina 一点伤害都没有」。现在每位护法一个独立槽位，
> 「谁能不能被打」读自己的 `damageable`，那类 bug 从结构上不可能再发生。

护法层已按策略接口落地：`BossGuardian`（接口）+ `GuardianManager`（注册与路由）+
`Zqx125Guardian`（第一位护法：射箭 / 红石雷击 / 天降铁砧 / 狂暴 / 举盾挡箭 / 甘露档位）。
**每位护法由 `GuardianManager` 为每一局新建一个实例**（不是单例）——
单例会让「上一局的箭矢节拍」漏到下一局，那种 bug 极难复现。

一阶段躲避场与战斗入口也已打通：`BossPhaseOne`（21×21×12：粒子激光 / 飞天猪猪 /
砂岩台阶 / 幻魔者尖牙 + 二段跳 + 10 条命 60 秒）、`BossFightEntry`（召唤 → 8 秒升空 →
对白 → 可点击 √/× → 一阶段）、`BossMusic`（一阶段音符盒 BGM）。
从「任务」里点「开始挑战中章BOSS！」起，整条链现在能一路走到一阶段开打。

二阶段门面（批次 4）也已落地：`BossPhaseTwo`（落地检测 → 开场对白 + 背包备份门 →
规则旁白 → 戮仙剑抽剑/投掷序列 → 51×51×20 石英场分批建造 → 召唤仪式 → zqx125 开打）。
一阶段通过后链会自动接进二阶段；zqx125 退场后由 `BossPhaseTwo.arriveNext` 钩子
呼叫下一位护法。细节见 `转化报告.md` 第 9.6 节。

第二位护法 Katarina（批次 5）也已落地：`BossKatarina`（蓝色登场阵法 → 凋灵骷髅 600 血 →
三面环绕盾限伤 1 → 挥砍弹反火球破盾 → 虚弱硬直 → 剑气风暴 → 加强甘露）。
她退场后同样经 `arriveNext` 呼叫双子护法。细节见 `转化报告.md` 第 9.7 节。

双子护法与经营小游戏（批次 6）也已落地：`BossTwin`（太极召唤阵 → 天虚+天实双本体双血条，
只吃破法弓计数器伤害 → 一位倒地先退场、两位都空进糖然决斗钩子）、
`TwinLedger`（账本持久化）+ `TwinMinigame`（虚仆/资源/挑战道具/回血压制）。
细节见 `转化报告.md` 第 9.8 节。

三·四阶段（批次 7）也已落地：`BossPhaseThreeFour`（糖然下场 → 补中章装备 → 场地内部分帧清空
→ 僵尸形态决斗（血条剩余 ×100）→ 通关 → 跪像 → 四阶段对话 → 散去/场地消散 → 天道证明 +
任务其八 → 中章挑战彻底结束）。阶段 3/4 读档直开同步打通。
**BOSS战 主线（阶段 1→2→3→4）至此全线可玩**。细节见 `转化报告.md` 第 9.9 节。

收尾四批（批次 6c/8/9/10）同样完成，**BOSS战 13452 行 JS 全部原生化**：
`TwinShop`（天实商店四分类 GUI / 增益购买与属性巡检 / 吸血 / 水瓶链）、
`BossEventRules`（事件层 18 条公共规则并表：复活还包 / 防收纳三道锁 / 防爆防火 /
举盾拦原版弹反 / 展示架移除收场）、`BossMusic` 二阶段实装（Fever Pitch 循环 +
MIDI JSON 曲目 + 鸟之诗换曲）、`BossArena.cleanupStaleStartup`（启动自清理：
活战局保护闸 + 场地记录回放 + 标记实体清扫）。细节见 `转化报告.md` 第 9.10 节。

已原生化的脚本见 `native_scripts/NativeScriptRegistry#registerBuiltins()`；
尚未原生化的脚本名在加载结束时由 `NativeScriptRegistry#reportUnregistered()`
汇总打印到日志（「以下脚本尚未原生化（对应物品已注册但暂无行为）」），可据此按批推进。
详细的进度表、拆文件方案与缺陷记录见 `转化报告.md`。

未原生化的脚本即使没实现，对应物品/机器**仍会正常注册、合成与显示**，只是没有行为。

---

## 七、已知事项

- `generators.yml` 的 `Tr_cjhfyd` 声明了 18 输入 / 18 输出槽（54 格）。若 `menus.yml` 缺少
  同名菜单条目，会退回父类 27 格布局导致槽位错位。
- 物品组按钮（`type: button` + `actions`）在本插件中注册为标准 Slimefun `FlexItemGroup`，
  因此按钮出现在**指南的物品组列表**里，而 RSC 版是嵌在父组页面内的
  （RSC 自己重写了指南渲染）。功能等价，入口位置不同。
- `TTButtonGroup` 目前支持 `script` / `console` / `link` / `open_itemgroup` 四类 action。

---

## 许可

MIT，见 `LICENSE`。
