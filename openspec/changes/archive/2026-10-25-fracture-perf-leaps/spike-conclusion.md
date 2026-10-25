# Spike 结论：L2 range_choice 短路装配（2026-10-25）

任务 3.1 / design D2、D3-2。三选一定案：**主装配采用**（B 装配未启用，理由见下）。

## 主装配实机验证

- 装配：`final_density` 外层 `range_choice(input=cache_2d(maixr:fracture),
  min_inclusive=-4.0, max_exclusive=-3.999, when_in_range=-4.0,
  when_out_of_range=现行 min(add(…), noodle) 原样)`（design D2 原案）。
- **无 NoiseVisitor 异常**：spike 世界（run-bench-spike，seed 12345）启动与 9 区域
  forceload 全程无 `IllegalStateException` /「Multiple fillers」
  （日志 `_bench-data/boot-spike-main.log` / `boot-spike-jfr.log`；
  启动期各构建均有的 `AmethystGeodeFeatureMixin` mixin WARN 为既有无害环境警告）。
  **分支内 interpolated 的 NoiseVisitor 行为验证通过**，B 装备无需启用。
- 语义 diff（基线 256 槽原装配 vs spike 4096 槽+短路，同 seed 同坐标 9 区域，
  11981 区块）：sections 相同率 **96.86%**（≥ 用户验收线 85%），BE 99.98%。
  差异内容抽检（`_tools/block_diff.py`）：diorite→andesite/granite 同族互换、
  oak_leaves distance ±1 等**装饰/材质微差，无 air↔实体翻转、无形态结构差异**
  ——**用户裁决：无视少数差异，直接通过**。
- 边界分类（`_tools/FractureBoundaryCheck.java`）：纯短路差异 145 区块中
  TRANSITION=68 / 全撕裂腹地 66 / 完好腹地 11——design D4 原判据（差异仅限
  过渡带）不成立，差异为 aquifer/装饰随机层级的微差；按用户口径放行，
  spec 豁免场景按用户验收线（85% + 微差直接通过）执行。

## 性能与归因（L2 的真实收益）

- 9 区域 forceload 中位数：spike **15.49s** vs L1（无短路）15.38s vs 基线 15.27s
  ——**无可测收益**（残留 dev 进程清除后复测，首轮 16.72s 为进程抢 CPU 噪声）。
- JFR 归因（worker 样本 inclusive，`spike.jfr` vs `l1.jfr`）：
  - `NormalNoise.getValue`（Perlin 数学）占 **~77%**（L1 76.8% / spike 75.4%）；
  - 短路省掉的原版树逐点部分仅 12.3%→9.6%（-2.7pp），被 RangeChoice 判断
    (+1.2pp) 与 Cache2D (+1.1pp) 抵消；
  - **完全撕裂列上不可省的大头是 fracture 自身噪声求值（~29pp）与
    `initial_density_without_jaggedness` 原版链（~31pp，列级预计算，
    L2 装配管不到）**——后者即 design D6 的 L3 决策门所指。

## 定案

1. L2 主装配**落地**（`broken_overworld.json`，epsilon = 0.001）。
2. epsilon 定案依据：`FractureAreaProbe` 实测（替代 design 所述 fracture_sim.py）——
   短路带 [-4.0, -3.999) 与精确 -4.0 面积差 0.01~0.12pp（survival ∈ (0, 0.00025]
   的列几乎不存在），分类安全性由原版正峰 1.6 ≪ 3.999 保证。
3. L2 保留理由：性能持平但无害（噪声内），值语义腹地一致，为 spec 分层口径的
   行为级优化提供落地件；性能缺口移交 L3 决策门（见 3.4 后的用户询问）。

## L3 决策门结果（2026-10-25 用户裁决）

终态比值 2.38×（> 1.5× 硬线），design D6 决策门触发。**用户裁决：不做 L3，
按现状收尾归档**（/opsx:archive 直接发起，L3 决策询问被中断视为否定答复）。
性能下限 ~2.3× 如实入档 `_docs/生成性能测量.md`；`initial_density_without_jaggedness`
门控保留为后续候选（若未来需要再立项，design D6 的语义风险提示继续有效）。

## Workflow follow-up 处置

- L3 决策门：见上——不立项，记录于本文件。
- 组 2 对照表终态读数：已补入 `_docs/生成性能测量.md`（~2.38×）。
