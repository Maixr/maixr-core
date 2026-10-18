# 破碎世界类型 Specification

## Purpose

「破碎」世界类型（`maixr:broken`）把主世界本身变成一个逐方块碎裂的世界：
全图统一碎裂水平（无安全平静区）的碎裂场——撕纸质感裂缝网络、Voronoi 板块
碎裂散落、垂直漂移与断层错位，并叠加震中（含确定性叙事地标"被炸毁的
倒数第二基地"）与侵蚀材质（幽匿侵蚀 / 天空石结晶，绝不生成潜声装置）；
天空悬浮任意已注册结构，空域漂浮陨石与粗矿块。末地主岛核心保留静滞
（保护龙战）。创建世界时破碎为默认选中类型。

实现文件：`BrokenOverworldChunkGenerator`、`FractureDensityFunction`、`PlateFieldLogic`、
`PlateFieldDensityFunction`、`PlateDisplaceDensityFunction`、`MaixrDensityFunctions`、
`MaixrDressingPass`、`SkyStructurePlacer`、`SkyStoneClusterFeature`、`BrokenWorld`、
`MaixrChunkGenerators`，数据包 `data/maixr/worldgen/`（noise_settings 三份、
world_preset、noise 参数）等。设计细节与踩坑记录见 `_docs/破碎世界.md`。

## Requirements

### Requirement: 统一碎裂场

「破碎」世界的地形 SHALL 由逐方块碎裂场生成，碎裂强度 SHALL 在全图
**均匀一致**（带进度恒定 `UNIFORM_BAND`，运行时由配置
`config/maixr-common.toml` 的 `uniformBand` 控制，默认 2.0——碎块散落 +
裂缝网络 + 中度板块死亡 + 强漂移），MUST NOT 存在"安全平静区"。
静滞核心 SHALL 仅保留在末地主岛（半径 ~225 格，保护龙战）。
同一坐标的碎裂判定 MUST 在同一种子下永远一致。

#### Scenario: 全图均匀碎裂

- **WHEN** 生成任意坐标的区块（含静滞锚点附近）
- **THEN** 碎裂水平一致（碎块与裂缝散布），不存在无碎裂的平静区

#### Scenario: 末地主岛核心静滞

- **WHEN** 生成末地主岛 225 格核心内的区块
- **THEN** 龙战所需地形完好

### Requirement: 碎裂场的种子确定性

碎裂场 MUST 纯粹由世界坐标与世界种子决定：同一种子下同一坐标的生成结果
永远一致（跨区块、跨生成次序一致）；不同种子 MUST 产生不同的碎裂形态。

#### Scenario: 同种子确定性

- **WHEN** 同一种子两次生成同一坐标区块
- **THEN** 碎裂形态完全一致

#### Scenario: 异种子异形态

- **WHEN** 两个不同种子的破碎世界在相同坐标生成
- **THEN** 裂缝与碎块分布不同

### Requirement: 各向同性裂缝

裂缝网络 SHALL 无全局主导走向（各向同性），裂缝有宽窄变化与分叉；
破碎的不均匀性 MUST 由震中分布承担，而非裂缝方向。

#### Scenario: 裂缝无主导方向

- **WHEN** 统计大范围内裂缝走向的分布
- **THEN** 不存在单一主导走向

### Requirement: 垂直形变

碎裂场的板块 SHALL 具有 y 漂移与相邻断层错位（统一碎裂水平下
漂移幅度约 25 格 ×维度折算，全图一致）。倾斜剪切 SHALL 实现为
可调参数且默认关闭。

#### Scenario: 全图统一漂移

- **WHEN** 生成任意碎裂区块的地形
- **THEN** 板块存在 y 漂移与相邻断层错位（浮板悬空）

#### Scenario: 静滞核心无漂移

- **WHEN** 生成末地主岛静滞核心内的地形
- **THEN** 板块无垂直漂移与错位

### Requirement: 震中分布与被炸基地地标

碎裂场 SHALL 叠加局部加压的震中：3~5 个随机震中（种子决定，间距不小于 1024 格）
与 1 个确定性叙事地标——「被炸毁的倒数第二基地」震中，位于静滞锚点固定方位、
距锚点 2048~4096 格，碎裂强度高于周边。震中 MUST 不落入末地主岛静滞核心。

#### Scenario: 地标震中确定存在

- **WHEN** 以任意种子生成破碎世界
- **THEN** 固定方位 2048~4096 格处存在一个碎裂显著加重的震中

#### Scenario: 随机震中互不相邻

- **WHEN** 统计一个种子的随机震中位置
- **THEN** 任意两个震中间距不小于 1024 格

### Requirement: 侵蚀材质语言

碎裂面的材质 SHALL 按三层表达侵蚀：撕裂断面裸露原版地层剖面
（土/石/深板岩）；震中区域地表以幽匿块与幽匿脉络替换侵蚀
（替换密度随震中距离衰减）；远端浮板偶尔结晶出天空石。
潜声装置（潜声传感器、潜声尖啸体、潜声催化剂）MUST NOT 由世界生成放置。

#### Scenario: 震中无潜声装置

- **WHEN** 检查任意震中区域的生成方块
- **THEN** 只存在幽匿块/幽匿脉络，不存在任何潜声装置方块

#### Scenario: 远端浮板结晶

- **WHEN** 生成碎裂场的浮空碎板
- **THEN** 部分浮板表面或内部出现天空石

### Requirement: 下界碎裂应用

「破碎」世界类型的下界 SHALL 应用碎裂场（与主世界同为统一碎裂水平，
锚点为下界 (0,0)，与主世界出生传送门 8:1 对位，无平静区），
基岩天花板 SHALL 存在倒挂的漂移碎块。

#### Scenario: 下界天花板倒挂碎块

- **WHEN** 生成下界任意区块
- **THEN** 基岩天花板下存在倒挂漂浮的碎块

#### Scenario: 下界碎裂水平

- **WHEN** 生成下界任意区块
- **THEN** 碎裂水平与主世界一致（无平静区）

### Requirement: 末地碎裂应用

「破碎」世界类型的末地 SHALL 应用碎裂场：主岛核心为静滞区
（平静半径 ~225 格，龙战平台、黑曜石柱、出口传送门 MUST 完好），
主岛静滞区以外的外岛 MUST 按统一碎裂水平处理（碎裂、漂移、散落）。
末影折跃门与末影城市（结构）不受碎裂场影响。

#### Scenario: 末地主岛核心完好

- **WHEN** 生成末地主岛 225 格核心内的区块
- **THEN** 龙战所需地形与出口传送门完好，无碎裂空洞

#### Scenario: 末地外岛碎裂

- **WHEN** 生成末地外岛区域
- **THEN** 岛屿呈统一碎裂水平形态（碎裂、漂移、散落）

### Requirement: 天空结构（破碎结构）

「破碎」世界主世界 SHALL 以每区块 1/100（`CHANCE_DENOMINATOR = 100`）的
**均匀概率**，从服务器上全部已注册结构（原版 + 全部模组，自动收集）中
抽一座抬到天空生成：结构底部在
`[MIN_Y = 201, 319 - 结构高度 - TOP_MARGIN = 2]` 内随机。
搬移前 MUST 逐 piece 做 dry-run 校验，任一 piece 包围盒未随 `move` 同步时
MUST 整座放弃，不得留下搬不动的残骸。

#### Scenario: 不可搬移的结构放弃

- **WHEN** 某 piece `move` 后包围盒 minY 未按预期移动
- **THEN** 整座结构放弃，放弃原因记入 `SkyStructurePlacer.SKIP_REASONS`

### Requirement: 天空结构放置防线

天空结构 MUST 通过两道防线保证放置安全：

piece 包围盒超出注册区块写半径（±`WRITE_RADIUS_CHUNKS = 8` 区块）时
MUST 整座放弃——结构模板 processor 阶段会读取生成区域外的方块，
越界即崩服。

带 `terrain_adaptation` 的结构 MUST 同样能上天：
`BrokenOverworldChunkGenerator.createBiomes` SHALL 调
`SkyStructurePlacer.maskSkyReferences` 在 BIOMES 阶段临时摘掉天空结构的
structure references（`whenComplete` 还原），使 Beardifier 不在虚空里摊出浮空薄板。

#### Scenario: 超出写半径的结构放弃

- **WHEN** 抽中某结构的 piece 包围盒超出注册区块 ±8 区块
- **THEN** 整座结构放弃，放弃原因记入 `SkyStructurePlacer.SKIP_REASONS`

#### Scenario: 村庄上天且无浮空薄板

- **WHEN** 区块命中天空结构概率且抽中带 terrain_adaptation 的结构
- **THEN** 结构登记为天空 `StructureStart`，Beardifier 看不到它，地形正常破碎，结构完整悬浮

### Requirement: 地形与结构完全解耦

碎裂场 MUST 只作用于地形密度：地形可以碎裂为虚空；
结构 MUST 完全不受碎裂场影响——村庄/要塞/末地传送门等结构
SHALL 在无地形区域照常完整落地（悬空遗迹），
结构放置 MUST NOT 以任何方式依赖该处地形是否存在。
碎裂在 vanilla 密度管线内求值，密度为 0 的地方自动无地形，
因此旧版的地形阶段跳过逻辑 MUST NOT 存在；`applyBiomeDecoration`
MUST 照常执行（链尾附加确定性材质 dressing）。

#### Scenario: 虚空区块中的结构

- **WHEN** 一个结构覆盖了被碎裂成空洞的区域
- **THEN** 该区块的结构部分仍被完整放置（悬空遗迹），只有地形缺失

### Requirement: 空域浮岩

y=110~170 空域 SHALL 按每区块 1/16（`rarity_filter chance=16`）生成一团浮岩：
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

### Requirement: 世界类型判定

「破碎」世界类型 SHALL 以"主世界生成器 `instanceof BrokenOverworldChunkGenerator`"
判定（`BrokenWorld.isBrokenWorldType`）；「默认」与「空岛」世界类型
MUST 不受本能力任何逻辑影响（vanilla `minecraft:normal` preset 不被本模组覆盖）。
世界类型列表由 `data/minecraft/tags/worldgen/world_preset/normal.json` 追加
（`replace: false`）。

#### Scenario: 普通世界不受影响

- **WHEN** 玩家选择「默认」或「空岛」世界类型
- **THEN** 无天空结构与天空浮岩逻辑生效，地形为该类型应有的形态

### Requirement: 新建世界默认选中破碎

新建世界的创建界面 SHALL 默认选中「破碎」（client mixin 作用于
`WorldCreationUiState`：初始项为空或 `WorldPresets.NORMAL` 时替换为
`maixr:broken` preset 并经 `setWorldType` 同步维度设置；
调试世界等其它显式传入的 preset 不替换）。

#### Scenario: 新建世界默认选中破碎

- **WHEN** 玩家打开创建世界界面
- **THEN** 世界类型初始为「破碎」（仍可手动循环切换为原版普通等其它类型）
