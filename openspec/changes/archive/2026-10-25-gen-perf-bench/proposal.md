# Proposal: 生成性能测量基准（/maixr bench 随机传送 + 火焰图抓取手册）

## Why

破碎世界随机传送后出现「TPS≈0 持续几十秒 + 区块缓慢补齐」。代码审计已排除主线程同步取块（模组内唯一同步 `getChunk` 仅启动期出生点搜索），剩余嫌疑（噪声阶段成本 / 天空结构尖峰 / 加载期挂钩配置）无法从代码进一步裁决——**缺一份可复现的量化测量**。文档里唯一的历史数据（FULL 单候选 1~2s）采于 fracture 列缓存 bug 修复之前，已过期。当前没有工具能在不做任何代码改动的情况下量化「每次随机传送的生成成本」并定位热点阶段，先有测量再谈优化，避免盲改。

## What Changes

- 新增 `/maixr bench tp <次数> [最近距离] [最远距离]` 随机传送基准命令（OP 权限）：
  - 目的地由「世界种子 + 序号」确定性派生（同种子同世界重复跑得到同一条传送序列，可 A/B 对比）；
  - 目的地 Y 用生成器离线高度场 API（`getBaseHeight`，与出生点搜索第三级同款）取地表高度，传送落地不坠虚空；
  - 每次 TP 记录：目的地坐标、TP 发出 → 玩家所在区块 FULL 的墙钟时长、期间 MSPT 峰值；跑完后输出汇总表（总时长 / 单次均值 / MSPT 恢复时长）；
  - 基准期间挂临时 tick 采样器采集 MSPT 曲线，会话结束自动摘除，不做任何持久化，不影响世界行为。
- 新增 `/maixr bench stop`：中断进行中的基准会话并输出已完成部分。
- 新增操作手册 `_docs/生成性能测量.md`：火焰图抓取流程（spark 放入 mods → TP 后 `spark profiler --timeout 60` → 看 Server thread 栈顶）、环境控制清单（固定种子 / 全新世界目录 / 热身丢数据 / 破碎 vs 原版对照 / 记录 view-distance 与核数）、测量记录模板、以及「栈顶 → 病灶 → 对应修复方向」的决策表。
- README/AGENTS.md 关键文档清单同步挂接新文档。
- **不做**：任何生成性能优化本身（另立变更）；区块各阶段计时埋点（spark 火焰图已覆盖归因，避免冗余插桩）；spark 依赖打包（外部工具，手册指导放置）。

## Capabilities

### New Capabilities

- `maixr-bench-commands`: `/maixr bench` 随机传送基准——确定性目的地派生、落地安全、测量口径（墙钟 + MSPT）、会话生命周期（开始/中断/自动摘除）、权限要求。

### Modified Capabilities

（无——不改变任何现有能力的对外行为；破碎世界生成逻辑零改动。）

## Impact

- `src/main/java/com/maixr/MaixrCommands.java`：挂接 `bench` 子命令字面量。
- 新增 `src/main/java/com/maixr/MaixrBench.java`：会话状态机 + tick 采样器（NeoForge `ServerTickEvent.Post` 临时监听）+ 目的地派生与测量。
- 新增 `_docs/生成性能测量.md`；`AGENTS.md` 关键文档清单、`README.md` 功能概览各加一行。
- 无网络包、无存档格式、无 worldgen JSON、无配置项变更；对世界生成的唯一影响 = 基准传送本身触发的正常区块加载。
- 风险低：命令不写方块（除玩家正常落地交互）、临时监听会话结束必摘除、传送目的地远离玩家已有区域不碰老区块。
