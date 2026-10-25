# Proposal: 破碎世界生成性能冲刺（目标 ≤1.5× 原版基线）

## Why

2026-10-25 实测：破碎世界 64 块 forceload 批量 ~22.6s vs 原版同协议 8.82s（~2.56×）。
探索阶段用既有 `run/gen.jfr`（优化前抓取，10295 个 worker 样本）按 **inclusive 口径**
重新归因，推翻了 `_docs/生成性能测量.md` 里「模组侧只占 10~15%、天花板 ~2.3×」的旧结论
——那是 top-frame 归因把碎裂场自己的四倍频 crack 噪声误计入原版链。真实构成：

1. **fracture 重复求值风暴（worker ~36%）**：`FractureDensityFunction` 列缓存为
   256 槽直接映射（`CACHE_SLOTS=256`），而逐方块填充阶段按「256 列 × 384 行」扫描，
   生日悖论下 ~94 列两两共享槽位、逐行互相踢出——每区块 ~3.6 万次重复求值
   （设计意图 256 次，~140 倍），每次 = 4 次四倍频噪声 + 震中 9 格候选噪声 + Voronoi。
2. **完全撕裂列上原版链白算（原版链实际占 worker ~48%）**：统一带 2.0 下死亡板块
   内部 + 活板块裂缝线的 survival 精确 = 0、撕裂深度精确 = -4（强制空气），
   约占全图 55~65% 面积；现有装配 `min(add(cache_2d(fracture), 原版链), noodle)`
   在这些列上仍然完整求值原版链（角点）与 noodle（逐方块）。

用户目标（2026-10-25 确认）：生成性能达到原版基线 **1.1~1.5×**，效果类似即可。
评估结论：**1.5× 可达；1.1× 为乐观线，不承诺**（取决于残余阶段成本与 spike 结果）。

## What Changes

- **L1 缓存扩容（值恒等，逐位一致）**：`FractureDensityFunction` 列缓存
  256 → 4096 槽（键打包不变），消除碰撞风暴；如 spike 证实收益不足，追加
  fillArray 列感知填充（同列只算一次、散播到批次数组）。
- **L2 range_choice 短路（行为级，效果类似口径）**：`broken_overworld.json` 的
  `final_density` 外层包一层
  `range_choice(cache_2d(maixr:fracture), min_inclusive=-4.0, max_exclusive=-3.999,
  when_in_range=-4.0, when_out_of_range=现行表达式)`——完全撕裂列输出常数 -4，
  跳过原版链角点求值与 noodle 逐方块求值；完好区与部分撕裂带的求值路径与现行为
  逐位一致。分类安全性依据：原版 final_density 正峰 ~1.6，tear ∈ [-4, -3.999) 时
  原版链 + tear 恒 < 0，与常数 -4 的实体/空气判定完全一致。
- **spec 修订**：「生成输出跨版本效果稳定（性能优化安全约束）」从单一「效果一致」
  改为**分层口径**——值恒等优化（L1）仍须逐位一致；行为级优化（L2）显式豁免
  「撕裂边界插值带」的方块差异（附豁免条件与验收方式），并新增性能基线 Requirement。
- **条件可选 L3（默认不做）**：`initial_density_without_jaggedness` 按撕裂同样门控
  ——仅当 L1+L2 落地后实测仍 > 1.5× 且用户批准时再立项实施。
- **明确不做**：不改碎裂场常量/曲线/噪声参数（地形手感不变）；不改 `size_vertical`
  等插值网格参数（垂直手感风险，此前已否决）；不动主线程区块调度/传送同步机制
  （vanilla 行为）；不做 `hypot` 替换（此前已否决）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `broken-world-type`:
  ① 修订「生成输出跨版本效果稳定（性能优化安全约束）」Requirement——从单一
  「效果一致（差异限于伴生岛随机）」改为分层：值恒等优化逐位一致；行为级优化
  允许撕裂边界插值微差，附豁免条件（同种子确定性、完好区/完全撕裂区实体-空气
  判定不变、差异限于撕裂带邻域）与验收方式；
  ② 新增「生成性能基线」Requirement——forceload 64 块批量（同种子、全新世界、
  3 区域 × 3 次取中位数）≤ 1.5× 同协议原版基线为硬验收线，1.1~1.3× 为冲刺线
  （不阻塞验收）。

## Impact

- `src/main/java/com/maixr/FractureDensityFunction.java`：`CACHE_SLOTS` 扩容
  （256 → 4096；文档「实现踩坑记录 1」中的 256 槽表述同步更新）。
- `src/main/resources/data/maixr/worldgen/noise_settings/broken_overworld.json`：
  `final_density` 外层 range_choice 短路装配。
- `openspec/specs/broken-world-type/spec.md`：归档时同步上述需求修订。
- `_docs/破碎世界.md`（碎裂场 JSON 装配公式、踩坑记录）、
  `_docs/生成性能测量.md`（新基线读数与归因勘误）：随实现同步更新。
- **前置依赖**：`2026-10-25-faster-chunk-gen` 须先收尾归档——本变更 MODIFIED 的
  需求由它引入（其任务 4.2 复测完成后归档），归档前本变更的 spec delta 无法通过
  strict 校验。
- **风险**：
  - range_choice 分支内含 `minecraft:interpolated` 节点在 NoiseVisitor 包装下的
    行为需 spike 实机确认（vanilla router 自身有 range_choice 用法先例，但
    interpolated 处于分支内需验证无「Multiple fillers」类问题）；备选方案为
    把 range_choice 下沉到 `plate_displace` 内层（只省角点求值、不省 noodle，
    收益减半），spike 定案。
  - 新旧区块同存档并存时，撕裂边界可能出现个别方块翻转（确定性、与裂缝对齐、
    不产生结构性错位接缝）——按用户确认的「效果类似」口径接受，spec 显式豁免。
- **玩家可见变化**：随机传送主线程冻结时长随单块成本同比例缩短（预期 ~35-45%）；
  地形形态、裂缝分布、材质语言不变。
