# Design

## Context

现行 `BrokenSpawnSearch` 两级搜索（掩码预筛 → 真实区块校验）的瓶颈在第二级：
每个掩码命中的候选区块都在 `ServerStartedEvent` 回调里同步 `getChunk` 完整生成
（FULL 状态，含 8 邻块依赖链 + surface + features + 光照）。文档「一点八」实测
「第一区块命中 2~3ms」依赖 `prepareLevels` 恰好预生成原点附近区块；玩家实际
种子下原点附近可大片无合格列，螺旋逐候选整块生成即分钟级主线程阻塞
（用户实测 TPS≈0 持续约 2 分钟）。

关键约束（来自反编译源码核验，1.21.1）：

- `NoiseChunk.NoiseInterpolator.compute` 在插值循环外按点求值会直接抛
  `IllegalStateException` —— **不能**拿离线 `NoiseChunk` 逐点 `compute`
  final_density 做单点查询；单点路径会退化成未插值的 cell 函数，值不对。
- vanilla 自己的离线地形查询 `NoiseBasedChunkGenerator#getBaseHeight(x, z,
  Heightmap.Types, LevelHeightAccessor, RandomState)` 是**公开 API**，内部
  `iterateNoiseColumn` 用一次性 `NoiseChunk(1, …, Blender.empty())`
  （`BeardifierMarker.INSTANCE`，无结构地基）从顶向下按真实插值管线逐 cell
  推进，返回首个满足高度图谓词的 y。完全不接触区块系统，无死锁面。
- 本模组 `broken_overworld.json`：`aquifers_enabled=false`、`sea_level=-1000`
  （高于 `minY=-64`）。`Aquifer.createDisabled` 的语义是
  `density > 0 → null（即 default_block 石头）`，流体选择器在 `y ≥ -1000`
  全部返回空气 ⇒ **离线求值「非空气」⇔ 插值后 `final_density > 0`**，
  与真实生成噪声阶段逐位一致（同一 `RandomState`/噪声实例）。
- `WORLD_SURFACE` 谓词是 `NOT_AIR`，与真实区块校验读的
  `chunk.getHeight(WORLD_SURFACE)` 同一谓词。

## Goals / Non-Goals

**Goals:**

- 把「逐候选同步整块生成」降为「仅离线预筛命中的候选整块生成」（预期个位数）。
- 保持既有合格口径、无限螺旋顺序、仅新世界生效、两段式挂点完全不变。
- 复用 vanilla 公开 API，不引反射、不碰 `NoiseChunk` 内部类、不加 Mixin。

**Non-Goals:**

- 不改变出生点落位（同种子选中的区块序不变；离线校验只做剔除，不做重排）。
- 不处理 features（树/湖/浮岩/晶洞）阶段的差异——仍由真实区块校验兜底。
- 不做异步化/分 tick 搜索（三级化后阻塞已降到秒级以内，不值得引入并发复杂度）。
- 不改下界/末地与其它世界类型。

## Decisions

1. **第二级预筛用逐列 `getBaseHeight` 而非自建 `NoiseChunk` 遍历全列。**
   备选：① 自建 `NoiseChunk(4, …)` + `mapAll(nc::wrap)` 复刻生成状态机
   （`initializeForFirstCellX/advanceCellX/selectCellYZ/updateForY/X/Z`）——
   `wrap` 是 protected、`NoiseInterpolator` 构造器包私有，需要继承
   `NoiseChunk` 做桥接子类并复刻遍历次序，API 面大、错一处即值漂移；
   ② 直接对未包装 final_density 单点 compute——被
   `NoiseInterpolator.compute` 的异常/退化路径否决（见 Context）。
   `getBaseHeight` 每列新建一个单 cell 宽 `NoiseChunk(1)`，代价是同区块
   16 倍 cell 求值，但实测量级仍是每区块毫秒~几十毫秒纯 CPU，且零 API 风险、
   与 `getBaseHeight` 的其它调用方（结构放置）同一行为。
2. **逐列校验在先、整块生成在后；列扫描次序与真实校验同序（lz 外层、lx 内层）
   便于诊断时对齐。** 命中即短路返回（有地形列几乎必在表层前段命中）。
3. **保留掩码预筛为第一级**：全撕列区块（死亡板块/宽裂缝中心）用 ~0.3ms 的
   纯函数即可排除，省掉整块 256 列 × 全列深度求值；且保持既有日志口径。
4. **真实区块校验代码不动**（含 h 向下找非空、向上找双空气的踩坑修正），
   作为最终兜底：离线校验与真实生成的差异只可能来自 features/结构地基，
   出现时表现为「多生成一两个候选」，不影响正确性。
5. **统计与日志**：新增「离线命中」计数并入选定日志；保留预筛限流日志口径。

## Risks / Trade-offs

- [离线校验比整块生成多花 CPU（无地形候选 ~几十 ms/区块）] →
  相比每候选 1~2s 的整块生成仍高 1~2 个数量级收益；掩码预筛先排除大头。
- [features/结构地基与离线求值存在理论差异] → 真实区块校验原样兜底，
  假阳性只浪费一次候选生成，不产生错误出生点。
- [`getBaseHeight` 每 `LevelHeightAccessor` 走 `clampToHeightAccessor`] →
  主世界为全高维度，无截断影响。
- [同种子下搜索耗时分布仍取决于碎裂场布局] → 螺旋推进本身改为毫秒级，
  极端远距命中也只是离线求值累积，不再有整块生成的二次方开销。

## Migration Plan

无存档/数据迁移。实现合并后对**新建世界**立即生效；老存档不触发搜索，
行为不变。回滚即还原 `BrokenSpawnSearch.java` 单文件。

## Open Questions

（无）
