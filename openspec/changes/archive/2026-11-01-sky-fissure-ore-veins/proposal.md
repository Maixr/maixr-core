# Proposal

## Why

玩家反馈空中矿物"太规则了"：旧版浮岩里 65% 是**通体同一种粗矿块的完美圆球**
（`placeOreBlob` 等轴椭球阈值 1.25），形状规则、材质规则、叙事违和（粗矿块是提纯资源，
本不该天然成球）。此前已把粗矿块分支重做为「天隙矿脉」（v6 跨区块协作生成），
但**陨石团簇仍是一条独立装饰分支**：与矿脉体系割裂，且锁在 110~170。

本轮按玩家裁定继续收敛：

- **陨石球并入矿脉体系**：天空石不再以椭球团簇单独生成，而是作为矿脉的
  **第三种宿主材质**，只在更高的高空出现；
- **矿脉宿主按高度分带**：深板岩区生成的矿脉**全部**为深板岩脉，不再按整脉
  30% 概率随机（旧 `DEEPSLATE_SHARE`），避免"深板岩地形里长出石脉"的违和感。

## What Changes

- **删除陨石团簇分支**：`placeBlob`、`METEOR_CHANCE`、`METEOR_MIN_Y`/`METEOR_MAX_Y`、
  内核 `ae2:smooth_sky_stone_block`、`COUNT_METEOR` 与相关统计一起移除；
  `place()` 之后只负责矿脉片段放置。
- **天空石改为矿脉宿主材质**：高空带的矿脉有概率整脉为
  `ae2:sky_stone_block`（原版式石脉形态，不再是圆球）；天空石脉照常嵌矿石矿囊。
- **宿主按矿脉 origin 的 y 分带**（确定性，仍由单元种子决定）：

  | origin y | 宿主 |
  |---|---|
  | `-50 ≤ y ≤ 8` | `minecraft:deepslate`（100%） |
  | `8 < y < 180` | `minecraft:stone` |
  | `180 ≤ y ≤ 300` | `minecraft:stone` 85% / `ae2:sky_stone_block` 15% |

- **高度范围抬升**：矿脉 origin y 范围由 `-50~170` 扩到 **-50~300**
  （`VEIN_MAX_Y = 300`）；placed_feature `height_range` 上限同步 170 → 300。
- **AE2 缺失回退**：`ae2:sky_stone_block` 未注册（未装 AE2）时，该脉回退为
  `minecraft:stone`——不整条消失，也不放空气。
- **矿囊规则不变**：每脉 6~14 处、r=1~3、每囊独立抽矿种、按权重；
  deepslate 脉取深板岩变体（缺变体回退常规），stone / sky_stone 脉取常规矿石。
- **命名与注册 id 不变**：类 `SkyStoneClusterFeature` 与注册 id
  `maixr:sky_stone_cluster` 保留（历史沿革，避免存档/数据包引用断裂）；
  内部方法 `isMeteorAllowed` 更名为 `isVeinAllowed`（现已无陨石语义）。
- 同步改 `broken-world-type` spec「空域浮岩」需求与 `_docs/破碎世界.md` 一点七。

## Capabilities

### New Capabilities

（无——本变更全部落在既有能力边界内）

### Modified Capabilities

- `broken-world-type`: 「空域浮岩」需求改写——陨石团簇分支删除，天空石并入矿脉
  宿主；宿主族群由「整脉 70/30 随机」改为「按 origin y 高度分带」
  （深板岩区 100% 深板岩 / 中空石头 / 高空 15% 天空石 + 85% 石头）；
  高度上限 -50~300。

## Impact

- `src/main/java/com/maixr/SkyStoneClusterFeature.java`：
  删除陨石分支（`placeBlob` / `METEOR_*` / `SMOOTH_SKY_STONE` / `COUNT_METEOR`）；
  `DEEPSLATE_SHARE` 退役，新增 `DEEPSLATE_ZONE_MAX_Y = 8`、
  `SKY_STONE_MIN_Y = 180`、`SKY_STONE_MAX_Y = 300`、`SKY_STONE_SHARE = 0.15D`；
  `VEIN_MAX_Y` 170 → 300；`buildVein` 按 origin y 解析宿主与矿囊变体；
  `isMeteorAllowed` → `isVeinAllowed`；类 javadoc 表格重写
- `src/main/resources/data/maixr/worldgen/placed_feature/sky_stone_cluster.json`：
  `height_range` 上限 170 → 300，注释块去掉陨石段落、写明实际 y 由单元种子派生
- `openspec/specs/broken-world-type/spec.md`：「空域浮岩」需求（归档时经本变更
  delta 合入）
- `_docs/破碎世界.md`：一点七整节重写（删陨石、宿主分带表、调参表常量替换、
  形态演进史补 v7）
- 性能：陨石分支删除后每区块固定成本略降；矿脉片段相关路径不变，
  `VEIN_MAX_Y` 抬到 300 只是采样上限，几何回放量与 y 无关。`/maixr bench` 复测
- 风险项：分带边界附近（y=8±3、y=180±3）因垂直轻摆可能有少量跨带方块；
  高空带与天空结构（y≥201）在空间上重叠——矿脉只写空气，结构先于 features
  落地，不会互相覆盖，但会共存
