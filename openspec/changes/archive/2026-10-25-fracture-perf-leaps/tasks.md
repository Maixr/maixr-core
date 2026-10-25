# Tasks

## 1. 前置与基线（先于任何优化落地）

- [x] 1.1 确认 `2026-10-25-faster-chunk-gen` 已收尾（任务 4.2 复测）并归档，然后跑
      `openspec validate --specs --strict` 与本变更校验通过（本变更 MODIFIED 的
      需求由它引入，design D7）。验证方式：两条命令退出码 0。
- [x] 1.2 基线重采（design D3-3）：同一会话内按测量手册采集原版与破碎世界
      64 块 forceload 墙钟（3 远区域 × 3 次，全新世界、同种子、同参），中位数
      比值作为本次变更的分母。验证方式：读数与环境参数写入
      `_docs/生成性能测量.md`，并注明采集命令。
- [x] 1.3 完全撕裂面积占比实测（design D3-1）：离线 `RandomState` + fracture
      采样（每 4 格一个样本，≥64×64 区块范围，固定 seed 12345）。验证方式：
      占比数字与采样方法落盘（写入本变更目录或测量文档），校准 L2 收益模型。

## 2. L1 缓存扩容（值恒等，逐位一致）

- [x] 2.1 `FractureDensityFunction.CACHE_SLOTS` 256 → 4096（若 2.2 的 JFR 复测
      显示仍有可测风暴则升 16384，两档同属本任务）；键打包与混洗不变，注释
      更新槽位依据（碰撞期望计算）。验证方式：`./gradlew build` 通过 + JFR 复测
      `FractureDensityFunction` inclusive 占比显著下降（目标 ≤10%）。
- [x] 2.2 一致性 + 性能双验：固定 seed 临时世界两组远区域 blockhash 对比
      （相同率 ≥ 基线自漂移、差异限于伴生岛）；forceload 3 区域中位数对比
      1.2 基线。验证方式：两份数据写入 `_docs/生成性能测量.md`；不达标按
      design D5 处置并记录。

## 3. L2 range_choice 短路（行为级，效果类似口径）

- [x] 3.1 装配 spike（design D2）：主装配（final_density 外层 range_choice）
      与备选 B 装配（下沉到 plate_displace 内层）在临时世界各生成覆盖
      「完全撕裂 / 完好 / 边界」三类区域的区块集，确认无 NoiseVisitor 类异常
      并逐块 diff 定位差异位置。验证方式：spike 结论三选一（主装配 / B 装配 /
      放弃 L2）落盘；若放弃 L2，本组后续任务取消并在本变更记录原因，
      性能验收回退为 L1 单项口径如实上报。
- [x] 3.2 按 spike 定案修改 `broken_overworld.json`（range_choice 装配 +
      epsilon 定案——用 `_tools/fracture_sim.py` 体素采样核对 survival 分布后
      写死）。验证方式：`./gradlew build` 通过 + 临时世界生成覆盖三类区域
      无异常、完好区腹地与完全撕裂区腹地与优化前零差异。
- [x] 3.3 一致性三验（design D4）：① blockhash 差异区块逐个核验「差异方块处于
      撕裂边界过渡带 + 腹地零差异 + 新旧构建各自重跑差异集合逐块一致」；
      ② 旁路抽查——出生点三级搜索（新世界日志）、`getBaseHeight` 探针、
      dressing 侵蚀位置。验证方式：核验记录落盘，全部通过才构成 spec 豁免。
- [x] 3.4 性能复测：forceload 3 区域中位数比值 + `/maixr bench tp 5` 一轮 +
      JFR 归因复测（风暴消失、原版链占比按预期下降）。验证方式：比值数据
      写入 `_docs/生成性能测量.md`；≤1.5 达标，>1.5 按 design D5 先退 L2 复测
      并如实报告下限。

## 4. 收尾

- [x] 4.1 文档同步：`_docs/破碎世界.md`（碎裂场 JSON 装配公式加入 range_choice
      短路、踩坑记录 256 槽表述更新为最终档位、新增「短路语义与豁免边界」小节）、
      `_docs/生成性能测量.md`（归因勘误：inclusive vs top-frame、本变更全部读数）。
      验证方式：文档引用的常量与代码/JSON 一致（AGENTS.md 文档规范）。
- [x] 4.2 `./gradlew build` + `openspec validate`（本变更 + specs strict）通过。
      验证方式：两条命令退出码 0。

## Workflow follow-up

- 归档前确认 L3 决策门结果（design D6）：若 3.4 比值 > 1.5，向用户提出
  `initial_density_without_jaggedness` 门控的后续变更；若达标，记录 L3 不需要。
- 归档后 `_docs/生成性能测量.md` 组 2 对照表补一行终态读数（本变更后的比值）。
