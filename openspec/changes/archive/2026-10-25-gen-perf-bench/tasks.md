# Tasks

## 1. API 核实（design D2/D3/D4 的实现前提）

- [x] 1.1 反编译核实 1.21.1 tick 计时可用 API（`MinecraftServer` 最近 100 tick 缓冲的确切名称/可见性）；不可用则采用 design D4 降级自测路径。核实结论写进 `MaixrBench` 类注释；验证方式：结论落在代码注释，含二选一决策。
- [x] 1.2 核实 `NoiseBasedChunkGenerator#getBaseHeight(int, int, Heightmap.Types, LevelHeightAccessor, RandomState)` 签名与 `BrokenSpawnSearch` 现行调用一致；验证方式：编译期引用通过（任务 2.x 一起编译验证）。

## 2. MaixrBench 会话与测量核心

- [x] 2.1 新建 `MaixrBench`：静态会话状态机（空闲/运行中）、目的地确定性派生（`seed/参数/序号` → 坐标序列）、`getBaseHeight` 落点探测（异常/≤minY 跳过并记原因）；验证方式：编译通过 + 单元口径自查（同参数两次派生序列一致，控制台跑两个会话打印对比）。
- [x] 2.2 tick 采样器：会话启动注册 `ServerTickEvent.Post` 监听（含 MSPT 采集与「玩家 chunk 已 FULL」轮询判定），结束/stop/玩家退出/服务器停止注销；验证方式：runServer 跑会话，日志确认监听注册与摘除各一次，空闲期无 tick 输出。（运行时验证并入 5.2）
- [x] 2.3 报告：逐次回显（坐标/墙钟/MSPT 峰值）+ 结束汇总（成功数/总墙钟/均值/MSPT 恢复时长/跳过清单）聊天 + `LOGGER.info` 双通道；验证方式：runServer 实跑 `/maixr bench tp 3`，日志与聊天栏内容一致。（运行时验证并入 5.2）
- [x] 2.4 会话类注释记录测量口径与误差方向（含 region 缓存陷阱、探测开销不扣除），验证方式：注释与 design D7 手册口径一致。

## 3. 命令接线与权限

- [x] 3.1 `MaixrCommands` 挂接 `bench` 字面量（`.requires(hasPermission(2))`）：`tp <次数> [最近] [最远]` 参数校验（非玩家执行、非法参数、会话互斥 → 失败提示）、`stop` 中断并输出已完成部分；验证方式：runServer 依次验证 拒绝控制台/拒绝非 OP/非法参数/会话互斥/stop 中断 五条路径。（五条路径运行时验证并入 5.2）
- [x] 3.2 非 OP 与会话互斥场景在聊天栏的提示文案包含下一步指引（`/maixr bench stop`）；验证方式：实跑确认文案出现。（运行时验证并入 5.2）

## 4. 操作手册与文档挂接

- [x] 4.1 新建 `_docs/生成性能测量.md` 六节结构（环境控制 / spark 抓取 / A/B 矩阵 / 记录模板 / 栈顶决策表 / 口径陷阱），决策表含「pollTask-noise 栈 → 嫌疑①、features 栈 → 嫌疑②、FragmentRoadNetwork 主线程栈 → M2、晋升发包 → M3」四行；验证方式：文档内命令全部可复制执行，常量值与代码一致（AGENTS.md 文档规范）。
- [x] 4.2 `AGENTS.md`「关键文档」与 `README.md` 功能概览各挂一行指向新文档；验证方式：链接可达（文件存在）。

## 5. 构建与端到端验证

- [x] 5.1 `./gradlew build` 编译通过 + `openspec validate`（变更 + specs strict）通过；验证方式：两条命令退出码 0。
- [x] 5.2 端到端实测：runServer（或 release jar + dedicated）固定种子新世界，热身 TP 一次后跑 `/maixr bench tp 5 3000 20000`，对照手册完成一轮 spark 火焰图抓取；同时覆盖前述并入的运行时验证项（监听注册/摘除日志、五条拒绝路径、会话互斥、同种子两轮坐标序列一致）；验证方式：产出一份「报告汇总 + 火焰图结论（栈顶落在哪个病灶）」的测量记录，回填 `_docs/生成性能测量.md` 记录模板处作为首条样例。（实测记录与结论已回填；确定性 ✓、拒绝路径 ✓；运行中发现并修复基线相位越界与坐标回填两个 bug，见 design.md 实现期修订 D8/D9）

## Workflow follow-up

- 测量记录回填后，若裁决出的病灶成立，另立优化变更（`/opsx:propose`）承接修复，不在本变更内实现。
- 归档本变更前确认 `2026-10-24-faster-spawn-search` 的文档同步任务状态，避免两份文档口径冲突。
