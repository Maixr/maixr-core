# Tasks

## 1. 效果一致性基准（先于任何优化采集；口径：用户确认「效果相同即可」）

- [x] 1.1 定案哈希比对粒度（spike）：固定 seed 临时世界（`run-bench/`，独立端口
      25566/25576），`forceload add` 两组远区域（64+64 块）三次采集。定案：
      **按区块 sections 方块哈希（palette+data）+ block_entities(id/坐标) 比对，
      整文件 SHA256 因 chunk 时间戳不可用**；同构建两次采集自漂移 = 每区域
      12~19/750+864 区块（1.4~2.5%），漂移源实证为 `SurfaceLakes` NBT 岛
      `placeInWorld` 使用 `level.getRandom()`（设计内随机，效果相同）；
      结论落在 `_tools/mca_blockhash.py` 头注释与 D4；验证方式：B vs C 逐块
      diff 数据（BE 层 100% 一致，sections 差异块均为岛屿所在区块）。
- [x] 1.2 用定案口径采集优化前参考哈希（破碎世界 seed 12345，两组远区域）与
      基线自漂移率，存档 `openspec/changes/2026-10-25-faster-chunk-gen/ref-hash.txt`；
      同流程记录 forceload 计时基线（run-bench 口径 A/B/C 三次：R1 27.4/24.0/~
      R2 26.9/25.5/~s）；验证方式：参考哈希与计时落盘并注明采集命令与环境。

## 2. uniformBand 静态缓存（design D1）

- [x] 2.1 `MaixrConfig` 增加 static volatile double 缓存 + `ModConfigEvent.Loading`
      /`Reloading` 刷新 + `SPEC.isLoaded()` 为 false 时回落常量；`uniformBand()`
      返回缓存值；验证方式：编译通过 + runServer 日志确认配置加载时缓存刷新。
- [x] 2.2 一致性抽查：临时世界生成同一批坐标，与 1.2 参考哈希对比；验证方式：
      逐块 sections 哈希相同率 ≥ 基线自漂移（实测 R1 98.0% vs 基线 97.5%、
      R2 98.6% = 基线 98.6%，BE 100%）；计时 R1 24.24s / R2 24.78s。

## 3. PlateFieldLogic 静态列缓存（design D2）

- [x] 3.1 `survival` 入口加 static ThreadLocal 直接映射缓存（2048 槽），键 = 含
      scale 的 (scale, x, z) 打包（防下界 1/8 折算串值，见 design 风险第 2 条）；
      注释声明值恒等依据与槽位模式出处；验证方式：编译通过。
- [x] 3.2 一致性 + 性能双验：参考哈希对比（1.2）逐位一致；forceload 64 块
      计时对比 1.2 时的基线（破碎 24.2/22.5s）记录降幅；验证方式：哈希一致 +
      计时数据落进 `_docs/生成性能测量.md` 组 2 节。
- [x] 3.3 跨实例一致性抽查（spec 场景 3）：临时 JVM 断言因无头 Bootstrap 需要 FML
      上下文不可行（FeatureFlagLoader 依赖 LoadingModList，已放弃并删除临时代码）；
      改以 (a) 结构性论证收口：cfg 码占键高位、坐标占低 52 位，位域无交叠 →
      不同参数构造上不串值；(b) 3.2 的 forceload 逐块 diff 本身贯穿噪声阶段/
      dressing/离线高度场三条调用路径，相同率在漂移带内（98.5%/98.4%，BE 100%）
      即跨路径一致的端到端实证。

## 4. dressing 实例复用（design D3）

- [x] 4.1 `buildFracture` 按 RandomState 身份缓存实例（static 结构 + 线程安全
      说明）；验证方式：编译通过 + 连续生成两个区块日志/断言确认实例复用。
- [x] 4.2 一致性 + 性能复测：参考哈希对比 + forceload 计时 + `/maixr bench tp 5`
      一轮；验证方式：哈希一致；三处优化合计降幅写入 `_docs/生成性能测量.md`。
      （复测数据已核实落盘：优化后 64 块中位 ~22.6s（-10.3%），逐块相同率
      97.9%~98.6% ≥ 基线自漂移，见 `_docs/生成性能测量.md` 模组侧优化复测节；
      2026-10-25 归档时补勾。）

## 5. 构建与收尾

- [x] 5.1 `./gradlew build` + `openspec validate`（变更 + specs strict）通过；
      验证方式：两条命令退出码 0。
- [x] 5.2 文档收口：`_docs/生成性能测量.md` 组 2 节追加「优化后」读数与结论；
      若某项未达 ≥5% 降幅，按 design 风险第 4 条回滚该项并在任务注释记录；
      验证方式：文档口径与代码常量一致（AGENTS.md 文档规范）。

## Workflow follow-up

- 归档前确认 `2026-10-25-gen-perf-bench` 已归档（本变更验收依赖其工具链与手册）。
- 若复测后决定追做 JSON 级调整（height/cell），另立变更单独设计讨论。
