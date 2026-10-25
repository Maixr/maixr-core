# Design: 破碎世界生成性能冲刺（≤1.5× 原版基线）

## Context

归因与量化依据见 proposal（`run/gen.jfr` inclusive 重分析：fracture 风暴 ~36%、
原版链 ~48%、完全撕裂面积 55~65%）。现行装配：
`final_density = min(add(cache_2d(maixr:fracture), squeeze(mul(0.64, interpolated(
blend_density(... 梯度包 plate_displace(原版链 range_choice ...) ...))))), noodle)`。
关键事实（实现时直接引用）：

- survival 精确 = 0 ⟺ 撕裂深度精确 = `-TEAR_SCALE = -4.0`：死亡板块内部
  （`PlateFieldLogic.survivalUncached` 的 `own = 0.0D`）且震中加压外
  （`epicenterPress` 的 smoothstep 在 `PRESSURE_RADIUS` 外精确归 0），
  `(0 - 1) × 4 = -4.0` 在 double 下精确；活板块裂缝线上 `crackMask` 经
  smoothstep 饱和也精确为 0。
- 原版主世界 `final_density` 正峰 ~1.6（`FractureDensityFunction.TEAR_SCALE`
  注释：取 4 保证完全撕裂区一定无地形）。
- 逐方块填充阶段 `Cache2D.fillArray` 直通 + `SimpleFunction` 默认
  `fillAllDirectly` → fracture 每方块一次 `compute`（列缓存挡重复）；
  256 列 × 256 槽直接映射 → ~94 列碰撞逐行互踢。

## Goals / Non-Goals

**Goals:**

- forceload 64 块批量墙钟（3 区域 × 3 次中位数）≤ 1.5× 同会话原版基线；
  冲刺 1.1~1.3×。
- L1 保持逐位一致（值恒等）；L2 保持完好区/部分撕裂带逐位一致 + 完全撕裂区
  实体/空气判定一致（差异限于撕裂边界插值带，spec 豁免口径）。
- 两项优化相互独立、可单独回滚。

**Non-Goals:**

- 不改碎裂场常量/曲线/噪声、不改插值网格参数、不动主线程区块调度（见 proposal）。
- 不优化光照/装饰/天空结构等非密度管线阶段（JFR 实测合计 <5%，非瓶颈）。
- L3（initial_density 门控）默认不做，仅留决策门（见 D6）。

## Decisions

**D1 L1：fracture 列缓存 256 → 4096 槽（`CACHE_SLOTS`），键打包与混洗不变。**
理由：碰撞对期望 ≈ C(256,2)/4096 ≈ 8 对（现状 256 槽 ≈ 94 对），风暴量级
~3.6 万 → ~3 千次/区块；内存代价 4096 × (8+8) B = 64KB/线程/实例，worker 7 线程
× 少数实例 ≈ 亚 MB 级。备选：① 16384 槽（碰撞 ≈ 2 对，192KB/线程/实例）——若
复测显示 4096 仍有可测风暴再升档；② 开放寻址/双路组相联——复杂度不值；③
fillArray 列感知散播——`ContextProvider` 只暴露 `fillAllDirectly`，无法感知列
边界，本质仍靠缓存挡，放弃。验证：JFR 复测 `FractureDensityFunction` inclusive
占比 + forceload 计时；逐位一致按 blockhash 对比。

**D2 L2：`final_density` 外层 range_choice 短路。**

```json
"final_density": {
  "type": "minecraft:range_choice",
  "input": { "type": "minecraft:cache_2d", "argument": { "type": "maixr:fracture", …原参数… } },
  "min_inclusive": -4.0,
  "max_exclusive": -3.999,
  "when_in_range": -4.0,
  "when_out_of_range": { …现行 min(add(cache_2d(fracture), squeeze…), noodle) 原样… }
}
```

- **语义**：撕裂深度 ∈ [-4, -3.999) ⟺ survival ≤ 0.00025 → 该列实/空判定与
  优化前一致（原版正峰 1.6 ≪ 3.999，完整路径恒负），输出常数 -4 只是把它从
  「原版密度 - 4」换成同号的 -4；完好区与部分撕裂带走 `when_out_of_range`
  = 现行表达式原样，逐位一致。epsilon 取 0.001（而非 1e-9）是为抗浮点抖动的
  可读余量，分类安全性由 1.6 ≪ 3.999 保证。
- **代价**：`fracture` 在 JSON 里出现两次 → 两个实例、两套 ThreadLocal 列缓存，
  存活点每相位求值 2 次（完全撕裂点只算 input 1 次）；L1 之后每次求值退化为
  查表，可接受。备选「把 input 包进 cache_once 共享」不成立——两个 JSON 节点
  本就是两个实例。
- **风险与 spike**：`minecraft:interpolated` 节点（包住 blend_density/plate_displace
  子树）现在处于 range_choice 的分支内。vanilla router 自身大量使用 range_choice
  且其分支内引用含 interpolated 包装的函数（如 `vein_toggle` 的
  `interpolated(range_choice(y,…))` 为外包形态），但「interpolated 在分支内」
  的 NoiseVisitor 包装行为必须实机确认：新建破碎世界生成覆盖「完全撕裂 +
  完好 + 边界」三类区域的区块集，观察启动与生成无 `IllegalStateException`
  （「Multiple fillers」类症状）。**回退方案**：把 range_choice 下沉到
  `plate_displace` 的 `input` 外层（角点级短路，省原版链角点求值、不省
  noodle 逐方块），收益约减半且不含分支内 interpolated，作为 spike 失败时的
  B 装配。
- **旁路不变式**：`BrokenSpawnSearch` 掩码预筛直接读 fracture（不经 JSON 装配）、
  `getBaseHeight` 按符号判实体、dressing 用独立 fracture 实例——三者语义均不受
  L2 影响；验收含旁路抽查。

**D3 spike 清单（先于任何 L2 落地，半天量级）**：
1. 完全撕裂面积占比实测：离线 `RandomState` + fracture 采样（每 4 格一个样本，
   ≥64×64 区块范围），校准 L2 收益模型（预期 55~65%）。
2. D2 装配实机验证（三类区域区块集 + 逐块 diff 定位差异是否限于撕裂边界）。
3. 3 区域 × 3 次基线重测（原版 + 破碎同会话；现 8.82s 基线两次读数
   11.53/6.10s 方差过大，须重采作分母）。

**D4 验收口径（spec 两层的落地判据）**：
- L1：blockhash 相同率 ≥ 基线自漂移（同现行口径），且 `_tools/mca_diff.py`
  差异区块仍限于伴生岛。
- L2：blockhash 对比后，差异区块逐个核验「差异方块与裂缝/海岸线对齐」：
  对差异方块坐标离线采样 fracture，MUST 处于撕裂边界过渡带（同一区块内同时
  存在 survival=0 与 survival>0 的采样点邻域）；完好区腹地与完全撕裂腹地
  零差异；新旧构建各自重跑差异集合逐块一致（确定性）。三项全过才构成 spec 豁免。
- 性能：D3-3 的同会话 3 区域中位数比值 ≤ 1.5；JFR 复测归因（fracture 风暴消失、
  原版链占比按预期下降）。

**D5 回滚矩阵**：L1 与 L2 独立成任务、独立可退。性能不达标时先退 L2 复测
（L1 无语义风险总是保留）；L2 退后仍不达标则如实报告下限并归因。逐位一致性
失败（L1）直接回滚该项。

**D6 L3 决策门（默认关闭）**：L1+L2 落地复测后若比值仍 > 1.5，提出
`initial_density_without_jaggedness` 同样按撕裂门控的后续变更（它影响
`above_preliminary_surface` 表面规则条件与地下群系赋值，语义风险高于 L2，
须单独 spec delta 与用户批准），本变更不实施。

**D7 归档顺序**：本变更 MODIFIED 的「生成输出跨版本效果稳定」需求由
`2026-10-25-faster-chunk-gen` 引入——该变更须先收尾（任务 4.2 复测）并归档，
本变更的 `openspec validate --specs --strict` 才能通过；实施顺序里以此为前置。

## Risks / Trade-offs

- [分支内 interpolated 的 NoiseVisitor 行为未证实] → D2 spike 实机验证 + B 装配
  （下沉到 plate_displace 内层）兜底；最坏情况放弃 L2，只交付 L1（~1.9-2.1×）。
- [epsilon 边界（survival ∈ (0, 0.00025] 列按 -4 处理）] → 分类安全由原版正峰
  1.6 ≪ 3.999 保证；插值差 ≤ 0.001，视觉不可辨。
- [新旧区块同存档的撕裂边界方块翻转] → spec 显式豁免（确定性 + 与裂缝对齐 +
  非结构性）；玩家感知为裂缝毛边细节变化。
- [fracture 双引用使存活点求值 ×2] → L1 后为查表级；若 JFR 显示仍有热点，
  把 out 分支的 fracture 换成独立低频近似不可行（语义漂移），接受 ×2。
- [基线方差（原版 8.82s 来自方差极大的两次读数）] → D3-3 重采 3 区域 × 3 次
  中位数后再下结论；比值口径固定为「同会话」。
- [优化后反而变慢] → 每项独立计时验收，未达预期按 D5 回退。

## Migration Plan

纯生成管线优化，无数据迁移。老存档已生成区块不动；新区块按新装配生成。
回滚 = 按项还原 JSON 装配与 `CACHE_SLOTS` 常量。

## Open Questions

- `CACHE_SLOTS` 最终取 4096 还是 16384：由 L1 落地后的 JFR 复测定（不改变
  任务拆分，两档都属「扩容」同一任务）。
- epsilon 精确值（-3.999 vs 更紧/更松）：spike 用 `_tools/fracture_sim.py`
  体素采样核对 survival 分布后定案，安全性论证不变。
