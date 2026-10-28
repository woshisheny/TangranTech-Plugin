# TangranTech（糖然科技）

<img width="1280" height="1280" alt="Tangran" src="https://github.com/user-attachments/assets/fa5332cf-0c4f-40ad-ab85-a92097298592" />

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
- 输出：`build/libs/TangranTech-2.1.0-standalone.jar`

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

---

## 四、数据存储

玩家数据统一存在 **SQLite**（`plugins/TangranTech/data/tangran.db`），
表结构是「命名空间 + 键 → 一份 JSON 文档」：

```sql
player_doc(ns TEXT, key TEXT, doc TEXT, updated_at INTEGER, PRIMARY KEY (ns, key))
```

- `ns` = 原来的子目录名（`境界` / `储物空间` / `丹方权限` / `异火权限` / `任务` / `BOSS战` …），
  `key` = 玩家名（与原 ` <玩家名>.json ` 的文件名一一对应）。
- 业务代码无感：`AddonPaths.readJson / writeJson` 就是切换点。玩家数据目录下的 `.json`
  走数据库；`addons/` 下的附属资源（语言文件、终刻合成台配方、当铺缓存）与 `.txt` 标记文件
  仍是真实文件。
- **旧档自动迁移**：首次启动会把 `addon_configs/糖然科技/**` 下的 JSON 全部导入（已存在的键不覆盖），
  单条读取时库里没有也会顺手补录。旧文件不会被删，可留作备份。
- **降级**：SQLite 打不开（驱动缺失 / 冷门平台原生库加载失败 / 目录不可写）时自动回落
  「按玩家分文件」，功能不受影响，只是少了事务与集中管理。
- 数据库文件与 `tangran.db-wal` / `-shm` 请一起备份。

### 从 RSC 导入

```bash
/tr import                    # 预览：扫描 RSC 数据、统计、列样例，不写任何数据
/tr import --apply            # 真正写入
/tr import --only 境界,储物空间  # 只导指定命名空间
/tr import --source <目录>     # RSC 数据不在默认位置时指定
/tr import --overwrite        # 库里已有同名文档时用 RSC 数据覆盖（默认跳过）
```

默认源目录是 `plugins/RykenSlimeCustomizer/addon_configs/糖然科技`。
权限：`tangrantech.command.import` 或 OP；控制台永远可用。

### 储物空间的 NBT

储物空间存档不再是「物品 id → 数量」，而是物品桶：

```json
"vanilla": {
  "STONE": 128,                                    // 白板物品（与旧档合并）
  "DIAMOND_SWORD#9f2c1a7b3d4e": {                  // 带数据的物品单独成桶
    "count": 3,
    "nbt": "<Base64 的完整物品字节>"
  }
}
```

- 白板判定 = 与「按 id 现造的同款物品」字节一致；附魔、自定义名、lore、损伤、
  容器内容、PDC 只要有一处不同就进独立桶，**存进去什么样，取出来什么样**。
- 桶内只存一份物品字节，数量单独计，因此堆叠不重复占空间。
- 旧档只有 id → 数量，读取时按白板处理，存取继续可用。

## 五、权限（LuckPerms 可选）

装了 LuckPerms 就走它的 API（含组继承与上下文），没装自动回落
`Player#hasPermission` / `isOp`，功能不降级。

| 节点 | 用途 | 谁发放 |
| --- | --- | --- |
| `tangrantech.admin` | 管理员总闸（指令、管理员物品、场地放行） | 服主在权限组配置 |
| `tangrantech.admin.compiler` | 终刻合成台编译器 | 服主 |
| `tangrantech.admin.arena` | 挑战世界场地保护放行 | 服主 |
| `tangrantech.command.import` | `/tr import` 数据导入 | 服主 |
| `tangrantech.storage.unlocked` | 储物空间准入 | 开辟石使用时自动发放，可被回收 |
| `tangrantech.yanhuo.<id>` | 已掌握某种异火 | 获得异火时自动发放 |
| `tangrantech.danfang.<id>` | 已学会某种丹方 | 学会丹方时自动发放 |

判定口径：**管理侧只认节点或 OP；玩法资格是「存档为主、权限为辅」**
—— 存档里有就算有（旧档不会因为没节点而失去资格），权限里有也算有
（管理员可以直接用 `/lp user <玩家> permission set ...` 发放资格）。

---

## 许可

MIT，见 `LICENSE`。
