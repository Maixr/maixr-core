# 破碎世界类型 Specification

## Purpose

「破碎」世界类型（`maixr:broken`）把主世界本身变成一个逐方块碎裂的世界：
全图统一碎裂水平（无安全平静区）的碎裂场——撕纸质感裂缝网络、Voronoi 板块
碎裂散落、垂直漂移与断层错位，并叠加震中（含确定性叙事地标"被炸毁的
倒数第二基地"）与侵蚀材质（幽匿侵蚀 / 天空石结晶，绝不生成潜声装置）；
天空悬浮任意已注册结构，空域漂浮陨石与粗矿块。碎裂场仅作用于主世界
（下界/末地为原版生成）；通行与补给由裂缝桥、碎片道路网（配置门控）
与伴生岛承担，悬空晶洞经生成拦截与整坨清理治理。创建世界时破碎为默认选中类型。

实现文件：`BrokenOverworldChunkGenerator`、`FractureDensityFunction`、`PlateFieldLogic`、
`PlateFieldDensityFunction`、`PlateDisplaceDensityFunction`、`MaixrDensityFunctions`、
`MaixrDressingPass`、`SkyStructurePlacer`、`SkyStoneClusterFeature`、`BrokenWorld`、
`MaixrChunkGenerators`、`CrackBridges`、`FragmentRoadNetwork`、`SurfaceLakes`、
`VoidDebrisCleanup`、`GeodeBlobCleanup`、`AmethystGeodeFeatureMixin`、`MaixrBridgeEvents`，
数据包 `data/maixr/worldgen/`（noise_settings broken_overworld、world_preset、
noise 参数）等。设计细节与踩坑记录见 `_docs/破碎世界.md`。

## Requirements

### Requirement: 统一碎裂场

「破碎」世界的**主世界**地形 SHALL 由逐方块碎裂场生成，碎裂强度 SHALL 在全图
**均匀一致**（带进度恒定 `UNIFORM_BAND`，运行时由配置
`config/maixr-common.toml` 的 `uniformBand` 控制，默认 2.0——碎块散落 +
裂缝网络 + 中度板块死亡 + 强漂移），MUST NOT 存在"安全平静区"。
碎裂场 ONLY 作用于破碎预设的主世界（下界与末地 SHALL 使用原版噪声生成）。
同一坐标的碎裂判定 MUST 在同一种子下永远一致。

#### Scenario: 全图均匀碎裂

- **WHEN** 生成主世界任意坐标的区块
- **THEN** 碎裂水平一致（碎块与裂缝散布），不存在无碎裂的平静区

#### Scenario: 末地主岛核心静滞

- **WHEN** 玩家在新建破碎世界进入末地
- **THEN** 龙战所需地形（黑曜石柱、出口传送门、主岛平台）完好
  ——末地为原版生成，碎裂场不作用于末地

#### Scenario: 下界与末地为原版形态

- **WHEN** 新建破碎世界的下界或末地生成区块
- **THEN** 使用原版噪声设置生成（无碎裂场、无板块漂移与倒挂碎块）

### Requirement: 碎裂场的种子确定性

碎裂场 MUST 纯粹由世界坐标与世界种子决定：同一种子下同一坐标的生成结果
永远一致（跨区块、跨生成次序一致）；不同种子 MUST 产生不同的碎裂形态。

#### Scenario: 同种子确定性

- **WHEN** 同一种子两次生成同一坐标区块
- **THEN** 碎裂形态完全一致

#### Scenario: 异种子异形态

- **WHEN** 两个不同种子的破碎世界在相同坐标生成
- **THEN** 裂缝与碎块分布不同

### Requirement: 生成输出跨版本效果稳定（性能优化安全约束）

生成热路径的性能优化 MUST 保持同种子同坐标的生成**效果**稳定，按优化性质分
两层验收：**值恒等优化**（缓存扩容、实例复用、配置读取摊平等不改变求值数值
语义）生成输出 MUST 逐位一致，新区块 MUST NOT 出现接缝差异；**行为级优化**
（改变求值路径但语义等价，如完全撕裂列的短路求值）的实体/空气判定 MUST 与
优化前一致，短路常数输出 MUST 与完整路径同号，差异总量与性质 MUST 满足
「行为级优化材质微差豁免」场景。任何优化 MUST 保持输出同种子确定性；伴生岛
模板放置使用世界随机（`SurfaceLakes` 的 `placeInWorld`），不受本条约束。
凡引入优化的变更 MUST 以「同种子同坐标生成集的前后逐块哈希对比」作为验收
证据（`_tools/mca_blockhash.py` / `_tools/mca_diff.py` 口径）。

#### Scenario: 优化前后输出效果一致

- **WHEN** 同一种子分别在优化前与优化后的构建中生成同一批全新坐标区块（如
  `forceload` 同一组远区域），按区块对比 sections 方块哈希
- **THEN** 值恒等优化：逐块相同率不低于基线自漂移水平（实测同构建两次采集约
  97.5-98.6% 相同，差异区块限于伴生岛模板随机），且差异区块不出现系统性形态变化
- **THEN** 行为级优化：逐块相同率不低于**用户验收线 85%**（实测 ~96.9%），差异
  全部落入本需求的材质微差豁免场景条件（无 air↔实体翻转、无结构性形态变化、
  同种子确定性）

#### Scenario: 配置缓存不改变输出

- **WHEN** `uniformBand` 配置值经缓存路径读取（而非逐点配置 getter）
- **THEN** 碎裂场输出与直接读取配置时效果一致（缓存值 = 配置值，无精度差）

#### Scenario: 缓存跨实例一致

- **WHEN** 同一列的碎裂场/板块存活率被噪声阶段、dressing、离线高度场等不同调用
  路径（不同 `FractureDensityFunction` 实例）重复求值
- **THEN** 各路径得到的值相同（纯函数列缓存命中不引入任何路径差异）

#### Scenario: 行为级短路不改变实体/空气判定

- **WHEN** 完全撕裂列（撕裂深度 = `-TEAR_SCALE`）分别经短路路径（常数输出）与
  完整路径（原版密度 + 撕裂深度）求值
- **THEN** 两路径的实体/空气分类一致——原版 `final_density` 正峰远小于
  `TEAR_SCALE`，完整路径在该区间恒为负，与短路常数同号

#### Scenario: 行为级优化材质微差豁免

- **WHEN** 行为级优化上线后与优化前构建逐块对比（同种子、同坐标、全新世界）
- **THEN** 差异 MUST 同时满足：① 逐块 sections 相同率 ≥ **85%**（用户验收线，
  2026-10-25 裁决；实测 ~96.9%）；② 差异方块为 aquifer / 装饰随机层级的材质
  微差（石头同族互换、树叶 distance ±1 等），MUST NOT 出现 air↔实体翻转或
  大面积形态改变；③ 同种子重跑同构建，差异集合限于伴生岛世界随机漂移
  （确定性成立）
- **THEN**（反例）出现 air↔实体翻转、结构性形态变化、或相同率 < 85% 时，
  不构成豁免——该优化 MUST 回滚或修正

#### Scenario: 新旧区块并存无结构性接缝

- **WHEN** 同一存档中优化前生成的旧区块与优化后生成的新区块相邻
- **THEN** 新旧区块边界 MUST NOT 出现结构性错位（悬崖断面错位、裂缝走向断裂）；
  至多存在撕裂毛边的细微方块差异

#### Scenario: 短路不破坏旁路探测

- **WHEN** 出生点三级搜索的掩码预筛、`/maixr bench` 高度探针、dressing 等离线/
  旁路调用在完全撕裂列上求值
- **THEN** 判定结果与优化前一致——掩码口径直接读碎裂场（未改动），高度探针按
  「首个非空气方块」符号判定，短路常数与完整路径同号

### Requirement: 生成性能基线

凡影响破碎世界主世界生成热路径的变更，SHALL 按测量流程验收性能：64 块
`forceload` 批量到 FULL 的墙钟（同机同参：同种子、全新世界目录、相同
view/simulation distance、9 个远区域取中位数，原版对照同会话采集）测量比值
MUST 记录进 `_docs/生成性能测量.md`；**≤1.5× 为冲刺参考线**（2026-10-25
裁决：非阻塞硬线）。未达参考线时变更 MUST 按设计文档的回滚矩阵逐项处置、
如实报告可达下限与 JFR 归因，并经用户裁决后入档（不 MUST 未达线即回滚）。
测量 MUST 遵循 `_docs/生成性能测量.md` 的环境控制与口径陷阱。

#### Scenario: 批量生成测量与记录

- **WHEN** 同会话按测量手册分别采集原版与破碎世界的 64 块 forceload 批量墙钟
  （9 个远区域取中位数）
- **THEN** 破碎/原版比值连同环境参数、JFR 归因记录进 `_docs/生成性能测量.md`

#### Scenario: 性能不达参考线的处置

- **WHEN** 优化落地后实测比值 > 1.5×（冲刺参考线）
- **THEN** MUST 按设计文档的回滚矩阵逐项处置（含退层复测），如实报告可达下限
  与归因，经用户裁决后入档——2026-10-25 实例：L1+L2 后 2.38×，裁决收尾
  （`initial_density_without_jaggedness` 门控保留为后续候选）；该实例的归因
  已于 2026-10-31 更正（L1 容量从未生效，见「缓存生效容量」），修复后实测
  **0.76×** 已达标，`initial_density_without_jaggedness` 门控不再需要

### Requirement: 各向同性裂缝

裂缝网络 SHALL 无全局主导走向（各向同性），裂缝有宽窄变化与分叉；
破碎的不均匀性 MUST 由震中分布承担，而非裂缝方向。

#### Scenario: 裂缝无主导方向

- **WHEN** 统计大范围内裂缝走向的分布
- **THEN** 不存在单一主导走向

### Requirement: 垂直形变

碎裂场的板块 SHALL 具有 y 漂移与相邻断层错位（统一碎裂水平下
漂移幅度约 25 格，全图一致）。倾斜剪切 SHALL 实现为
可调参数且默认关闭。

#### Scenario: 全图统一漂移

- **WHEN** 生成主世界任意碎裂区块的地形
- **THEN** 板块存在 y 漂移与相邻断层错位（浮板悬空）

#### Scenario: 静滞核心无漂移

- **WHEN** 观察新建破碎世界的下界或末地地形
- **THEN** 为原版形态，不存在碎裂场板块（自然无漂移与错位）

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

### Requirement: 出生点选择

「破碎」世界的主世界 SHALL 接管世界出生点选择：`LevelEvent.CreateSpawnPosition`
事件中取消 vanilla 搜索后，SHALL 以三级搜索（碎裂场掩码离线预筛 → 逐列高度场
离线校验 → 真实区块校验）确定出生位置。出生位置 MUST 满足：出生列存在表面方块
（从上往下第一个非空气方块），出生位（表面方块上方 1 格）与其上一格均为空气；
表面方块的材质 MUST NOT 受限（泥土/草方块/树叶/石头断面等任何真实地形表面均可
出生，用户决定简化）。

#### Scenario: 出生在有地形的列

- **WHEN** 玩家首次进入新建的破碎世界
- **THEN** 出生位置脚下存在非空气方块（自然地表/断面均可），
  出生位与其上一格为空气

#### Scenario: 避开虚空

- **WHEN** 出生点搜索执行
- **THEN** 纯虚空列（无任何非空气方块）不被选为出生点，
  玩家进入世界后不因出生位置而掉虚空死亡

### Requirement: 出生点无限螺旋搜索

出生点搜索的区块螺旋 SHALL 以世界原点为心、按半径逐轮无限扩大，
直到找到满足全部合格条件的列；MUST NOT 退化为任意位置
（用户决定：宁可搜索更远也不出生在不合格位置）。

#### Scenario: 无限螺旋保证命中

- **WHEN** 靠近原点的区块无合格列
- **THEN** 搜索螺旋持续向外扩大直到找到合格列（碎裂场约 20% 列有地形，
  实际第一区块内即命中）

### Requirement: 出生点搜索生成开销

出生点搜索的真实区块校验 MUST ONLY 作用于离线两级预筛（碎裂场掩码预筛、
逐列高度场离线校验）全部命中的候选区块；逐列高度场离线校验 MUST 仅基于
世界生成实际使用的 `RandomState` 与密度函数做纯坐标离线求值，MUST NOT
加载或生成任何区块。合格口径与螺旋顺序 MUST NOT 因插入离线校验而改变。

#### Scenario: 无合格列区块不触发真实生成

- **WHEN** 候选区块经掩码预筛命中但逐列高度场离线校验判定全列无合格出生位
- **THEN** 该区块不发起真实区块生成，螺旋继续推进下一候选区块

#### Scenario: 离线校验命中才进入真实校验

- **WHEN** 逐列高度场离线校验判定某列存在表面方块（真实生成同噪声阶段
  逐位一致；树木/湖泊等 features 差异由真实区块校验兜底）
- **THEN** 该区块才发起真实区块生成并按既有合格口径逐列终验

### Requirement: 出生点接管范围与复活沿用

出生点接管 ONLY 作用于主世界生成器为 `BrokenOverworldChunkGenerator` 的世界；
vanilla `minecraft:normal` preset 与其它世界类型（含空岛）MUST 不受影响。
无床复活 SHALL 沿用同一出生点（`getSharedSpawnPos`）。

#### Scenario: 无床复活沿用

- **WHEN** 无床玩家死亡复活
- **THEN** 复活位置基于同一出生点（`getSharedSpawnPos`）

#### Scenario: 其它世界类型不受影响

- **WHEN** 主世界生成器不是 `BrokenOverworldChunkGenerator`
- **THEN** vanilla 出生点选择照常执行（事件不取消）

### Requirement: 裂缝桥

破碎世界的世界生成 dressing SHALL 在**本区块内**沿 +X 与 +Z 方向扫描
「实心 → 虚空 → 实心」的裂缝并直接架桥：裂缝宽度 2~12 格（跨出区块边界的不算）、
两端地表高差 ≤ 8 格、只连 y ≤ 100 的实心列；每区块以 1/2 概率尝试、最多 6 座。
桥面 SHALL 为 3 格宽、沿两端高度线性插值、只填充空气（不覆盖既有方块）、
桥面上方 2 格清空；材质 SHALL 为石砖系（苔藓/裂纹/普通石砖按坐标确定性混合）。
裂缝桥 MUST NOT 读取任何邻区块（不排队、不触发区块加载）。

#### Scenario: 裂缝上架桥

- **WHEN** 生成含 2~12 格宽裂缝的区块（1/2 概率命中且未达每区块 6 座上限）
- **THEN** 裂缝上出现 3 格宽石砖系桥面，两端落在两侧实心地表上，桥面上方 2 格为空气

#### Scenario: 纯本地生成

- **WHEN** 裂缝桥的扫描与铺设执行
- **THEN** 只访问本区块的高度图与方块，不读取、不加载任何邻区块

### Requirement: 碎片道路网连接规划

破碎世界主世界的跨碎片建桥 SHALL 以存活碎片（Voronoi 板块）为单位规划：
每碎片最多连 5 条，候选为 2 格邻域内最近的存活碎片，并经相对邻域图（RNG）过滤
——仅当存在第三方碎片「同时比两端距离的 0.8 倍更近」时抑制该边；过滤后一条都没有时
SHALL 兜底连接最近的存活碎片。每对碎片 SHALL 只建一次：已建连接持久化在
`maixr_road_network` SavedData 中，跨会话去重；每对连接只由坐标字典序较小的
一方碎片发起。

#### Scenario: 同一对碎片只建一座桥

- **WHEN** 同一世界跨会话重复加载覆盖同一对碎片的区块
- **THEN** 该对碎片之间不会出现第二座道路桥（SavedData 命中去重）

#### Scenario: RNG 全抑制时兜底连通

- **WHEN** 某碎片的全部候选边都被相对邻域图过滤抑制
- **THEN** 该碎片仍与最近存活碎片建立一条连接，不出现孤立碎片

### Requirement: 碎片道路网路径与桥面

道路网的路径 SHALL 由 A* 求得（4 格网格、边界外扩 20 格、搜索上限 2 万节点；
坡度计入代价、穿越实心重罚、地表 y > 180 视为不可通行以避开天空结构），
并经邻点加权平滑。竖直剖面坡度上限 SHALL 为 1.0（45°），桥面中段 SHALL 抬高
正弦拱 3 格。桥面 SHALL 为沙岩系 3 格宽、只填空气、上方 2 格清空、中线铺铁轨、
每 6 格一个向下 3 格的沙岩桥墩。桥端点 MUST 通过合格校验：属真实碎片地形
（±20 格内相连实心列 ≥ 60，排除浮岩/天空结构/小岛）、踩在实地（脚下方块非空气）、
两端高差不超过水平距离（否则坡度不可能达标）。

#### Scenario: 长跨桥可通行

- **WHEN** 一座道路桥跨越高差显著的碎片
- **THEN** 桥面坡度全程 ≤ 45°，玩家可步行或乘矿车通过（中线铁轨连续）

#### Scenario: 端点不合格放弃

- **WHEN** 候选端点落在空域浮岩/天空结构上或脚下悬空
- **THEN** 该连接放弃（不出现「通天桥」「无目标桥」），且标记为永久失败不重试

### Requirement: 碎片道路网触发与重试

道路网 SHALL 在区块**加载完成后**触发（MUST NOT 在区块生成阶段跨区块读取），
且只由拥有碎片中心所在区块的区块负责该碎片的连接。沿线邻区块未加载时 SHALL 把
该碎片记入待重试队列，由服务端 tick 补建（每 tick 最多 4 个碎片）；全程 MUST NOT
强制同步加载任何区块（仅探测已加载区块）。

#### Scenario: 邻区块未加载不卡世界

- **WHEN** 建桥所需的邻区块尚未加载
- **THEN** 该碎片进入重试队列，区块加载照常完成，不出现递归加载或世界卡死

#### Scenario: 服务端 tick 补建

- **WHEN** 待重试队列非空且玩家周围区块已就绪
- **THEN** 每个 tick 最多补建 4 个碎片的连接，成功后移出队列

### Requirement: 碎片道路网配置门控

跨碎片建桥系统 SHALL 由配置 `config/maixr-common.toml` 的 `bridgesEnabled`
控制，默认 **false**；关闭时 MUST NOT 执行任何加载期建桥逻辑。启用时 SHALL 由
碎片道路网（RNG + A* 实现）承担建桥；旧版「寻找落脚点」实现退役为历史参照，
不再被调用。

#### Scenario: 默认关闭

- **WHEN** 全新安装（未改配置）加载破碎世界区块
- **THEN** 不执行加载期建桥，世界加载无额外建桥开销

#### Scenario: 启用后生效

- **WHEN** 用户在配置中开启 `bridgesEnabled`
- **THEN** 跨碎片道路桥按上述规划/路径/重试规则逐步出现

### Requirement: 伴生岛

破碎世界主世界生成期 SHALL 在大碎片内陆空地放置伴生岛（每区块最多 1 座、
岛面 y ≤ 100、放置范围 MUST 无既有地形、以邻列真实地表定基准高度），类型按
区块坐标确定性分派：40% 甘蔗水岛（结构模板 `maixr:skyblock_water`）、
15% 梦晶岛（`maixr:skyblock_dream`）、12% 末地小岛（`maixr:skyblock_end`）、
11% 下界小岛（`maixr:skyblock_nether`）、11% 沙漠沙丘岛（程序生成：砂岩底盘
逐层收窄 + 沙层沙丘 + 7~10 根高 2~4 格仙人掌 + 枯木）、11% 球形玻璃水族馆
（玻璃球壳内充满水 + 海带柱 + 多种珊瑚、底面青金石块）。结构模板缺失时
SHALL 静默跳过该区块。

#### Scenario: 取水岛提供水与甘蔗

- **WHEN** 玩家在破碎世界探索并遇到 40% 分派的伴生岛
- **THEN** 岛上存在可取的水与甘蔗（碎裂场挖掉地表水后的补给点）

#### Scenario: 类型确定性

- **WHEN** 同一种子两次生成同一坐标区块
- **THEN** 伴生岛类型与位置一致

#### Scenario: 模板缺失不报错

- **WHEN** 结构模板 `maixr:skyblock_*` 未注册（数据包异常）
- **THEN** 该区块跳过伴生岛放置，世界生成不中断

### Requirement: 悬空晶洞生成拦截

破碎世界主世界 MUST NOT 生成悬空紫水晶洞：候选生成原点所在列自原点向下
9 格全为空气（地形已被碎裂场撕掉）时，该晶洞 SHALL 在放置入口被取消
（而非生成后删除）；原点下方存在任何实心方块时 SHALL 照常生成。
其它世界类型 MUST 不受此拦截影响。

#### Scenario: 悬空晶洞不生成

- **WHEN** 紫水晶洞的生成原点列向下 9 格全为空气（破碎世界）
- **THEN** 该晶洞不生成，天空中不出现悬浮晶洞

#### Scenario: 地下晶洞照常生成

- **WHEN** 紫水晶洞的生成原点下方存在实心方块
- **THEN** 晶洞正常生成（正常地下晶洞玩法保留）

#### Scenario: 其它世界类型不受影响

- **WHEN** 主世界生成器不是 `BrokenOverworldChunkGenerator`
- **THEN** 紫水晶洞照原版规则生成

### Requirement: 悬空岩浆清理

破碎世界主世界生成期 SHALL 移除悬空岩浆：正下方一格为空气（或已低于
世界底）的岩浆 SHALL 被替换为空气；有支撑的岩浆 SHALL 保留。

#### Scenario: 悬空岩浆消失

- **WHEN** 生成含「下方为空气的岩浆」的区块
- **THEN** 这些岩浆被替换为空气

#### Scenario: 有支撑岩浆保留

- **WHEN** 生成含正常岩浆湖的区块
- **THEN** 有方块支撑的岩浆不受影响

### Requirement: 悬空晶洞整坨清理

晶洞族方块（`maixr:void_geode_blocks` 标签：紫水晶块/芽母/晶簇/大中小芽/
方解石/平滑玄武岩）的悬空判定 SHALL 以 BFS 连通**整坨**为单位：坨内任一方块
在向下 96 格内存在「非空气且非晶洞」支撑、或与真实地形接触 → 整坨保留；
整坨无支撑 → 整坨删除。MUST NOT 出现「删一半留一半」：跨区块边界的坨、或
BFS 途中遇到未加载区块时 SHALL 整坨跳过（绝不强制加载邻区块）。单坨处理
SHALL 有防御上限（生成期 4000 块、加载期补扫 60000 块），超限保守不动。

#### Scenario: 嵌入地形的晶洞保留

- **WHEN** 一坨晶洞的任一方块接触真实地形或下方 96 格内有非晶洞支撑
- **THEN** 整坨保留，不出现「缺角/纸杯蛋糕」式半删

#### Scenario: 整坨悬空晶洞全删

- **WHEN** 一坨晶洞完全悬空（无任何支撑、不跨区块边界）
- **THEN** 整坨全部删除

#### Scenario: 跨界或未加载保守跳过

- **WHEN** 晶洞坨触及区块边界，或 BFS 途中所需邻区块未加载
- **THEN** 整坨本次不动，等后续时机再判定，绝不强制加载

#### Scenario: 雷霆大晶洞可被补扫

- **WHEN** 一坨悬空晶洞超过 6000 块（大型晶洞外壳）
- **THEN** 加载期补扫（上限 60000 块）仍能对它整坨判定并删除

### Requirement: 加载期清理配置门控

区块**加载期**的悬空残余清理（老区块补跑生成期清理 + 晶洞大坨补扫）SHALL 由
配置 `geodeCleanupEnabled` 控制，默认 **false**（重扫描拖慢世界加载）；
世界**生成期**的清理 SHALL 始终对新区块执行，不受该配置影响。

#### Scenario: 默认关闭时加载不清理

- **WHEN** 全新安装（未改配置）加载旧区块
- **THEN** 不执行加载期悬空残余扫描，区块加载无额外开销

#### Scenario: 开启后老区块补跑

- **WHEN** 用户开启 `geodeCleanupEnabled` 并加载生成于旧版本的区块
- **THEN** 该区块补跑悬空岩浆/晶洞整坨清理

### Requirement: 碎屑子层平滑侵蚀

碎裂场的碎屑子层存活率 SHALL 以抖动 Voronoi 平滑过渡（碎屑 cell 中心哈希
抖动 + 最近/次近邻距离比值的 smoothstep 过渡）计算，空洞边界 SHALL 呈渐变
侵蚀坡；MUST NOT 出现轴对齐的正方形空洞（8×8 整格判定）。

#### Scenario: 无方格空洞

- **WHEN** 观察高碎裂水平下碎屑子层的空洞形状
- **THEN** 不存在 1/4 区块大小的轴对齐正方形空洞

#### Scenario: 边缘渐变侵蚀

- **WHEN** 生成碎屑子层的存活/消亡边界
- **THEN** 边界沿抖动 Voronoi 线平滑过渡，呈侵蚀坡而非垂直断面

### Requirement: 缓存生效容量

生成热路径上的列缓存 SHALL 让其声明容量真正生效：槽位索引 MUST 覆盖
`[0, CACHE_SLOTS)` 全区间，MUST NOT 因索引位宽不足而退化到更小的有效槽位数。
缓存容量、内存代价与实测命中行为的表述 MUST 与代码一致（文档一致性硬规则），
不得以未生效的容量作为归因依据。

#### Scenario: 索引覆盖全部槽位

- **WHEN** 以不同 (x, z) 列键对列缓存做覆盖性求值（键数量远大于 64）
- **THEN** 命中/未命中分布与 `CACHE_SLOTS` 声明的容量一致；交叉验证方式为
  「同构建两次采集的输出哈希相同率」不低于基线自漂移水平（值恒等）

#### Scenario: 容量失效时文档不得据其归因

- **WHEN** 某次优化以「扩大缓存容量」为由宣称收益或解释无收益
- **THEN** MUST 先验证该容量实际生效（有效槽位数 = 声明值），再据此写入
  `_docs/生成性能测量.md`；验证不成立时旧归因 MUST 标注失效并保留原始读数
