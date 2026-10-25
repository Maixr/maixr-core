# maixr-bench-commands Specification

## Purpose

提供 `/maixr bench` 随机传送基准：用可复现的传送序列量化区块生成成本（墙钟 + MSPT），配合 spark 火焰图为生成性能定位提供测量入口。测量本身 MUST NOT 改变世界生成行为。

## Requirements

### Requirement: 随机传送基准命令与权限
系统 SHALL 提供 `/maixr bench tp <次数> [最近距离] [最远距离]`，仅限权限等级 ≥ 2（OP）且由玩家执行；参数非法（次数 ≤ 0、最近 > 最远、距离 < 0）SHALL 报错且不启动会话。已有会话进行中时再次启动 SHALL 被拒绝并提示先 stop。

#### Scenario: 非玩家执行被拒绝
- **WHEN** 控制台或命令方块执行 `/maixr bench tp 3`
- **THEN** 命令失败并提示需要玩家执行，不产生任何传送

#### Scenario: 会话互斥
- **WHEN** 基准会话运行中再次执行 `/maixr bench tp`
- **THEN** 命令失败并提示当前已有会话，可用 `/maixr bench stop` 中断

### Requirement: 目的地确定性派生
传送目的地序列 SHALL 仅由「世界种子 + 会话参数（次数/距离区间）+ 序号」确定性派生；同一种子、同参数 SHALL 产生完全相同的坐标序列，MUST NOT 混入任何运行时随机源（时间、玩家状态等）。

#### Scenario: 同种子重复运行序列一致
- **WHEN** 同一世界先后两次运行 `/maixr bench tp 5 3000 20000`（期间无新区块生成影响）
- **THEN** 两次打印的目的地坐标序列逐项相同

### Requirement: 落地安全
每个目的地的落点 Y SHALL 由主世界生成器的离线高度场接口（按 WORLD_SURFACE 求值，不触发区块系统）计算，传送至地表上方；高度探测失败或返回值不高于世界最低可建高度时 SHALL 跳过该目的地并记录原因，MUST NOT 使玩家坠入虚空或令服务器异常。

#### Scenario: 正常落地
- **WHEN** 某目的地离线高度探测成功
- **THEN** 玩家被传送至该列地表上方数格处，不坠虚空

#### Scenario: 高度探测失败
- **WHEN** 某目的地高度探测抛出异常或返回值不可用
- **THEN** 该序号被跳过并在报告中标注「探测失败」，会话继续

### Requirement: 测量口径与报告
每次传送 SHALL 记录：目的地坐标、从传送执行到「玩家所在目标区块达到 FULL（已加载可交互）」的墙钟时长、该窗口内服务器 MSPT 峰值；会话结束 SHALL 输出汇总：成功次数、总墙钟、单次均值、MSPT 从峰值回落至基线（±20% 内）的时长、跳过清单。所有结果 SHALL 通过聊天栏与日志双通道输出。

#### Scenario: 单次测量与汇总
- **WHEN** `/maixr bench tp 3` 三次传送全部完成
- **THEN** 每次传送后立即回显该次坐标与墙钟时长，全部结束后输出含总时长/均值/MSPT 恢复时长的汇总表

#### Scenario: 报告同时落日志
- **WHEN** 会话结束输出报告
- **THEN** 服务器日志出现同一份报告内容（便于 dedicated server 事后取证）

### Requirement: 会话生命周期与零残留
采样监听 SHALL 仅在会话期间挂载；会话正常结束、`/maixr bench stop` 中断、玩家退出或服务器停止时 SHALL 自动摘除，不写入任何持久化数据；空闲（无会话）时 SHALL 无每 tick 开销。

#### Scenario: stop 中断后无残留
- **WHEN** 会话运行中执行 `/maixr bench stop`
- **THEN** 输出已完成部分报告，采样监听被摘除，后续每 tick 无基准相关计算

#### Scenario: 服务器停止时自动清理
- **WHEN** 会话运行中服务器关闭
- **THEN** 不抛异常、不留存任何基准状态到存档

### Requirement: 测量无世界副作用
基准 MUST NOT 放置或破坏任何方块（玩家正常落地交互除外）、MUST NOT 写入配置或 SavedData、MUST NOT 修改世界生成行为；其对世界的影响 SHALL 仅限于传送引发的正常区块加载与玩家实体迁移。

#### Scenario: 基准后世界无篡改
- **WHEN** 在未探索区域跑完一轮基准
- **THEN** 除玩家落点周围正常生成的地形外，无本命令写入的任何方块或数据
