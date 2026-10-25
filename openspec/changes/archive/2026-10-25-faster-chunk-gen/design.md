# Design: 区块生成纯缓存优化

## Context

测量结论（`_docs/生成性能测量.md`，2026-10-25）：worker 时间里模组占 ~10-15%
（`fineSurvival` 5% / `survival` 3% / `drift` 0.4% / `epicenterPress` 0.5% /
配置 getter 链 ~4-6%），其余 ~72% 为原版噪声数学、<5% 光照/装饰。主线程冻结
= 原版传送同步取块 × 单区块成本，模组侧优化等比缩短冻结。本变更只做**值恒等
缓存**，见 proposal 的 What Changes 与明确不做清单。

## Goals / Non-Goals

**Goals:**

- 三处值恒等缓存/复用，worker 侧拿回 ~10-15%
- 生成输出跨版本逐位一致（新 spec 约束，验收含哈希对比）
- 复用既有测量工具链验收（`/maixr bench` + forceload 吞吐探针）

**Non-Goals:**

- `hypot` 替换（舍入差风险，见 proposal）
- JSON/世界高度/网格参数调整
- 主线程机制改动（原版同步取块是 vanilla 行为，不在模组侧动）

## Decisions

**D1 `MaixrConfig.uniformBand()` 静态缓存 + 事件刷新。**
`UNIFORM_BAND.get()` 在 `bandProgress` 里被逐密度求值调用（噪声块每列×多处、
`FractureDensityFunction.bandProgress`/`PlateFieldLogic.survival`/`drift` 都走它）。
改为：static volatile double 缓存；`ModConfigEvent.Loading`/`Reloading` 时刷新；
`SPEC.isLoaded()` 为 false 时回落常量（与现行 `uniformBand()` 回退语义一致）。
值恒等：缓存值即配置值。备选「每次 get 但包线程局部缓存」被否——事件刷新更简单
且配置本就低频变更（NeoForge COMMON 配置不热更）。

**D2 `PlateFieldLogic.survival` 静态 per-thread 直接映射列缓存。**
现状：每个 `FractureDensityFunction` 实例自带 ThreadLocal 256 槽列缓存，但
噪声块、dressing（每区块新建实例）、离线 `getBaseHeight`（bench 探针/出生点
三级搜索）各有实例 → 同一列跨实例重复求值；且角点填充/逐块阶段同列多次进
`compute`。改为在 `PlateFieldLogic.survival` 入口加 **static ThreadLocal
直接映射缓存**（2048 槽，键 = (x,z) 打包，值 = survival 结果），纯函数值恒等；
`FractureDensityFunction` 自身列缓存保留（两级不冲突，外层实例缓存继续挡
`computeUncached` 内其余工作）。`drift()` 不加（角点级调用，量小，JFR 0.4%）。

**D3 `MaixrDressingPass` 按 `RandomState` 身份复用 fracture 实例。**
`buildFracture` 每区块 `new FractureDensityFunction` → ThreadLocal 缓存冷启动。
改为 static（`WeakHashMap<RandomState, FractureDensityFunction>` 或身份等价结构），
同维度 RandomState 复用实例，列缓存跨区块保温。线程安全：密度函数实例不可变
（NoiseHolder/参数均为 final），共享安全；ThreadLocal 缓存按线程隔离。
`epicenterPress` 的每列两次计算随实例复用 + D2 缓存收敛，不再改 compute 流程
（改流程需动 `computeUncached` 返回结构，超出值恒等缓存的范围）。

**D4 验收用既有工具链（口径：用户确认「效果相同即可」，不要求逐位）。**
- 一致性：`run-bench/` 临时世界（同 seed、独立端口）→ `forceload add` 同两组
  远区域（64+64 块）→ `_tools/mca_blockhash.py` 按区块比对 sections 方块哈希
  （palette+data）+ block_entities(id/坐标)。**1.1 spike 定案**：整文件 SHA256
  不可用（chunk 时间戳漂移）；同构建两次采集自漂移 = 每区域 12~19 块
  （1.4~2.5%），源 = `SurfaceLakes` NBT 岛 `placeInWorld` 的 `level.getRandom()`
  （设计内随机）。验收 = 优化后采集 vs 参考采集的逐块相同率 ≥ 基线自漂移水平
  且差异区块仍限于岛屿类。
- 性能：`forceload add` 64 块应答耗时（run-bench 口径基线 R1 27.4/24.0s、
  R2 26.9/25.5s）+ `/maixr bench tp 5`。预期批量 ≥10% 缩短；bench 墙钟同向。

## Risks / Trade-offs

- [缓存键碰撞污染（直接映射槽位复用）] → 与 `FractureDensityFunction` 既有
  256 槽同款模式（该模式已在线上验证两个版本）；槽位错位只是退化为重新求值，
  值仍正确（纯函数），无正确性风险。
- [static ThreadLocal 在维度切换时返回错误值] → 键含 (x,z) 全局坐标且值为纯函数
  结果，与维度无关（survival 参数不含维度状态；scale 由调用方传入的
  `PlateFieldDensityFunction` 决定——**注意**：D2 缓存键 MUST 把 scale 并入键，
  否则下界 1/8 折算列会串值。实现为键 = (scale, x, z) 打包，任务里显式验收）。
- [NeoForge 配置事件在专用服务器/集成的注册时序] → 缓存初值取常量回退路径，
  事件刷新覆盖；`SPEC.isLoaded()` 语义保留。
- [优化后反而变慢（缓存命中率低于预期）] → 任务含前后 forceload 计时对比，
  未达预期即在 change 内回滚该项（D2 与 D3 独立成任务，可单独退）。

## Migration Plan

纯代码级缓存，无数据迁移。回滚 = 按项还原三处改动。

## Open Questions

（无——region 哈希的具体比对粒度（文件 vs 区块 NBT）留任务首项做一次小 spike 定案。）
