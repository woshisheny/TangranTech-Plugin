# TangranTech（糖然科技）

<img width="1280" height="1280" alt="Tangran" src="https://github.com/user-attachments/assets/fa5332cf-0c4f-40ad-ab85-a92097298592" />

>糖丸了

[糖王](https://github.com/tangran666)一大*力♂作*！
为粘液科技添加了许多糖分超标的妙妙机制与神秘机器❤️
为你的粘液之路加点糖😋！！！

~~去码头整点薯条~~

## 😋 项目介绍

RSC（RykenSlimefunCustomizer）附属项目 **糖然科技 TangranTech**[tangran666](https://github.com/tangran666) 的原生化版本：
把原来依赖 RSC 运行时 + Graal.js 脚本的附属，改写为**独立可运行的 Slimefun 附属插件 jar**。

- 目标环境：Paper 1.21.x（编译期对齐 1.21.11 API），Slimefun4 4.9.x（`Slimefun4-*-Beta.jar`）
- 包名根：`com.tangran.tangrantech`
- 主类：`com.tangran.tangrantech.TangranTechPlugin`

---

## ⛏️ 构建与安装

```bash
./gradlew build
# 产物：libs/Slimefun4-347d926-Beta.jar
```

1. 将构建出的 jar放入服务器的 `plugins/` 目录。
2. 重启服务器。

---

## 📊数据存储

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

---

### 🖊️从 RSC 导入

```bash
/tr import                    # 预览：扫描 RSC 数据、统计、列样例，不写任何数据
/tr import --apply            # 真正写入
/tr import --only 境界,储物空间  # 只导指定命名空间
/tr import --source <目录>     # RSC 数据不在默认位置时指定
/tr import --overwrite        # 库里已有同名文档时用 RSC 数据覆盖（默认跳过）
```

默认源目录是 `plugins/RykenSlimeCustomizer/addon_configs/糖然科技`。  
权限：`tangrantech.command.import` &op。

---

## ❗权限（LuckPerms 可选）

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
