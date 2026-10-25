# Proposal: 区块生成纯缓存优化（uniformBand / plate 场 / dressing 实例）

## Why

2026-10-25 测量会话（`_docs/生成性能测量.md` 首条样例 + 组 2 对照）定位到：随机传送
TPS≈0 的机制是「原版传送同步取块 × 破碎世界单区块生成成本」，后者为原版的
2~4 倍（同协议 64 块批量：破碎 23.4s vs 原版 8.8s）。JFR worker 归因显示模组自身
占 worker 时间 ~10-15%，其中可白拿回的部分：`MaixrConfig.uniformBand()` 被
NeoForge 配置 getter 逐密度求值调用（~4-6%）、`PlateFieldLogic` Voronoi 在多实例
间重复求值（~8%）。本变更为**纯缓存优化：生成输出效果一致（用户确认口径：不要求逐位一致，
效果相同即可；已知设计内随机=伴生岛模板世界随机不在此约束内）**。

## What Changes

- `MaixrConfig.uniformBand()` 增加静态原语缓存：配置加载/重载事件时刷新，
  密度求值热路径不再逐点走 `ModConfigSpec$ConfigValue.get()`（JFR 实测该调用链
  含 ArrayList.forEach/Objects.hashCode，占 worker ~4-6%）。
- `PlateFieldLogic.survival()` 增加静态 per-thread 直接映射列缓存（同款思路已在
  `FractureDensityFunction` 的 256 槽列缓存验证过）：噪声阶段/角点填充/dressing/
  离线 `getBaseHeight` 多个调用方对同一列的重复 Voronoi 求值收敛为每列一次。
- `MaixrDressingPass.buildFracture` 按 `RandomState` 身份复用 `FractureDensityFunction`
  实例（现为每区块新建，ThreadLocal 列缓存每区块冷启动，256 列重复求值）；
  同一列上的 `epicenterPress` 重复计算随实例复用自然收敛。
- **明确不做**：`hypot → 平方距离` 替换（`StrictMath.hypot` 与 `sqrt(a²+b²)` 存在
  末位舍入差，可能翻转裂缝边界方块 → 新旧区块接缝，风险大于 ~0.5% 收益）；
  JSON 级调整（height/cell 参数，影响地形手感，另行设计讨论）；
  任何改变生成输出的行为性改动。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `broken-world-type`: 新增性能优化安全约束——「生成输出跨版本稳定」：性能优化
  MUST NOT 改变同种子同坐标的生成输出（逐位一致），防止新旧区块接缝。
  既有「碎裂场的种子确定性」Requirement 保证版本内一致；本条补跨版本一致层。

## Impact

- `src/main/java/com/maixr/MaixrConfig.java`：静态缓存 + 配置事件刷新。
- `src/main/java/com/maixr/PlateFieldLogic.java`：静态列缓存（纯函数值不变）。
- `src/main/java/com/maixr/MaixrDressingPass.java`：实例复用。
- `_docs/生成性能测量.md`：优化后复测数据追加到组 2 节。
- 无网络包、无存档格式、无 worldgen JSON、无配置键变更；玩家可见变化仅
  「传送冻结时长缩短 ~10-15%」（测量口径见 `_docs/生成性能测量.md`）。
- 风险控制：三项优化均为值恒等缓存/复用；验证含逐位一致性对比（同 seed 生成
  参考区块集前后哈希对比）与 `/maixr bench` 复测。
