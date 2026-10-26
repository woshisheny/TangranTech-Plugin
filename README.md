# TangranTech（糖然科技）

>糖丸了

[糖王](https://github.com/tangran666)一大*力♂作*！
为粘液科技添加了许多糖分超标的妙妙机制与神秘机器❤️
为你的粘液之路加点糖😋！！！

~~去码头整点薯条~~

## 项目介绍

RSC（RykenSlimefunCustomizer）附属项目 **糖然科技 TangranTech**[tangran666](https://github.com/tangran666) 的原生化版本：
把原来依赖 RSC 运行时 + Graal.js 脚本的附属，改写为**独立可运行的 Slimefun 附属插件 jar**。

- 目标环境：Paper 1.21.x（编译期对齐 1.21.11 API），Slimefun4 4.9.x（`Slimefun4-*-Beta.jar`）
- 包名根：`com.tangran.tangrantech`
- 主类：`com.tangran.tangrantech.TangranTechPlugin`
- 输出：`build/libs/TangranTech-2.0.0-standalone.jar`

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
> API 适配清单 / 内容存档路径 / 脚本移植进度 / 已知事项等细节，见 **`转化报告.md`**
>（第 6 节适配清单、第 12 节自 README 迁移的内容）。


## 许可

MIT，见 `LICENSE`。
