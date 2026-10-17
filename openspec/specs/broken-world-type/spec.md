# 破碎世界类型 Specification

## Purpose

「破碎」世界类型（`maixr:broken`）把主世界本身变成一个"越走越碎"的世界：
真实地形 + 渐变虚空，天空悬浮任意已注册结构，空域漂浮陨石与粗矿块，
玩家出生在主世界原点 (0,160,0) 的开局空岛上，死亡复活也落回岛上。

实现文件：`BrokenOverworldChunkGenerator`、`SkyStructurePlacer`、`SkyStoneClusterFeature`、
`BrokenIslandHandler`、`BrokenIslandData`、`BrokenWorld`、`BrokenSkyblockChunkGenerator`、
`MaixrChunkGenerators`，数据包 `data/maixr/worldgen/world_preset/broken.json` 等。
设计细节与踩坑记录见 `_docs/破碎世界.md`。

## Requirements

### Requirement: 渐变虚空地形

`BrokenOverworldChunkGenerator` SHALL 按区块到原点的切比雪夫距离
`d = max(|chunkX|, |chunkZ|)` 以确定性哈希（`roll01`，不依赖世界种子）判定虚空：
出生点虚空率 `NEAR_VOID_CHANCE = 0.20`，到 `RAMP_CHUNKS = 50` 区块（≈800 格）处
线性升到 `FAR_VOID_CHANCE = 0.95`，之后恒定 95%。
同一坐标的判定结果 MUST 永远一致。

#### Scenario: 出生点附近基本完好

- **WHEN** 生成与原点切比雪夫距离为 0 的区块
- **THEN** 该区块约 20% 概率为虚空

#### Scenario: 远处恒定高虚空率

- **WHEN** 生成距离超过 50 区块的区块
- **THEN** 虚空率恒定为 95%

### Requirement: 地形与结构完全解耦

生成器 MUST 只在纯地形阶段（`fillFromNoise` / `buildSurface` / `applyCarvers`）
跳过虚空区块；结构阶段（`createStructures` / `applyBiomeDecoration`）
MUST 永远照常执行（即使虚空区块），使村庄/要塞/末地传送门等结构完整落地。

#### Scenario: 虚空区块中的结构

- **WHEN** 一个结构覆盖了虚空区块
- **THEN** 该区块的结构部分仍被 `placeInChunk` 放置（悬空遗迹），只有地形被挖空

### Requirement: 天空结构（破碎结构）

「破碎」世界主世界 SHALL 以每区块 1/100（`CHANCE_DENOMINATOR = 100`）的概率，
从服务器上全部已注册结构（原版 + 全部模组，自动收集）中抽一座抬到天空生成：
结构底部在 `[MIN_Y = 201, 319 - 结构高度 - TOP_MARGIN = 2]` 内随机。
搬移前 MUST 逐 piece 做 dry-run 校验，任一 piece 包围盒未随 `move` 同步时
MUST 整座放弃，不得留下搬不动的残骸。

带 `terrain_adaptation` 的结构 MUST 同样能上天：
`BrokenOverworldChunkGenerator.createBiomes` SHALL 调
`SkyStructurePlacer.maskSkyReferences` 在 BIOMES 阶段临时摘掉天空结构的
structure references（`whenComplete` 还原），使 Beardifier 不在虚空里摊出浮空薄板。

#### Scenario: 村庄上天且无浮空薄板

- **WHEN** 区块命中 1/100 概率且抽中带 terrain_adaptation 的结构
- **THEN** 结构登记为天空 `StructureStart`，Beardifier 看不到它，地形正常破碎，结构完整悬浮

#### Scenario: 不可搬移的结构放弃

- **WHEN** 某 piece `move` 后包围盒 minY 未按预期移动
- **THEN** 整座结构放弃，放弃原因记入 `SkyStructurePlacer.SKIP_REASONS`

### Requirement: 空域浮岩

y=110~170 空域 SHALL 按每区块 1/8（`rarity_filter chance=8`）生成一团浮岩：
`METEOR_SHARE = 0.35` 概率为陨石团簇（椭球 r=2~4，阈值 1.22，外壳 `ae2:sky_stone_block`、
内核 `ae2:smooth_sky_stone_block`），否则为粗矿块（球 r=2~3，阈值 1.25，
从 `RAW_ORE_IDS` 16 种候选随机一种通体填充）。
候选池 MUST 在首次使用时按注册表过滤，未安装模组的方块不进池。
浮岩 ONLY 在主世界为破碎或空岛世界类型（及 `maixr:overworld_broken` 维度）时生成。

#### Scenario: 未安装模组的矿石自动剔除

- **WHEN** 候选池中某方块的所属模组未安装
- **THEN** 该方块不进入池子，世界不会出现空气团

#### Scenario: 默认世界类型不生成浮岩

- **WHEN** 主世界使用原版噪声生成器
- **THEN** 空域不生成任何浮岩

### Requirement: 开局空岛与出生点

服务器完全启动（`ServerStartedEvent`）时，若主世界生成器为
`BrokenOverworldChunkGenerator`，SHALL 在 (0,160,0) 放置结构
`maixr:broken_skyblock`（放置前强制加载周边区块；仅放一次，
由维度级 `SavedData` `maixr_broken_island` 记录），
把世界出生点设为岛上草方块上方（只认 `minecraft:grass_block`、上方两格空气、
离岛心最近），并把 `spawnRadius` 游戏规则设为 0。
玩家无床复活若被原版落点修正扔出岛外，MUST 在 `PlayerRespawnEvent` 后被传送回岛上。

#### Scenario: 首次进服出生

- **WHEN** 玩家首次进入破碎世界
- **THEN** 出生在岛上草方块上方

#### Scenario: 死亡复活兜底

- **WHEN** 无床玩家死亡复活且落点不在岛上
- **THEN** 复活完成后立即被传送回岛上（有床/重生锚的玩家不受干预）

### Requirement: 世界类型判定

「破碎」世界类型 SHALL 以"主世界生成器 `instanceof BrokenOverworldChunkGenerator`"
判定（`BrokenWorld.isBrokenWorldType`）；「默认」与「空岛」世界类型
MUST 不受本能力任何逻辑影响。世界类型列表由
`data/minecraft/tags/worldgen/world_preset/normal.json` 追加（`replace: false`）。

#### Scenario: 普通世界不受影响

- **WHEN** 玩家选择「默认」或「空岛」世界类型
- **THEN** 不放开局空岛、不改出生点、无天空结构与天空浮岩逻辑生效
