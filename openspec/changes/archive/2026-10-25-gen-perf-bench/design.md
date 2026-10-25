# Design: 生成性能测量基准

## Context

随机传送后 TPS≈0 的三个候选病灶（噪声阶段 / 天空结构尖峰 / 加载期挂钩）需要测量裁决；模组内已有两块可复用的地基：`BrokenSpawnSearch` 的离线高度场 API 用法（`getBaseHeight` + `RandomState`）与 `FragmentRoadNetwork` 的 `getChunkNow` 非阻塞探测模式。本变更是纯测量工具，见 proposal.md 的 Why 与 Non-Goals。

## Goals / Non-Goals

**Goals:**

- 同种子可复现的随机传送序列（A/B 对比的前提）
- 单次传送的墙钟成本 + 期间 MSPT 峰值 + 恢复时长三个数
- 会话零残留（监听用完即摘，不碰存档）
- 手册能把「测量结果 → 病灶 → 修复方向」一步映射

**Non-Goals:**

- 不做任何生成优化本身
- 不做区块阶段级插桩（spark 火焰图覆盖归因，见 decision D6）
- 不支持主世界以外的维度（下界/末地非本模组生成，测了没有意义）

## Decisions

**D1 会话状态机 + 临时 tick 采样器，不用 mixin、不用常驻监听。**
`MaixrBench` 持有一个静态会话引用；`bench tp` 启动时向 `NeoForge.EVENT_BUS` 注册 `ServerTickEvent.Post` 监听，`stop/完成/玩家退出/服务器停止` 注销。空闲路径 = 一个空判（监听不存在），满足「零残留」与「零开销」。备选的常驻监听 + 空闲短路被否：生命周期挂在会话上更直观，也避免将来别的系统误触发采样。

**D2 「目标区块 FULL」判定用 tick 轮询 `getChunkNow`，不监听 ChunkEvent.Load。**
TP 执行瞬间记 `t0`，此后每个 Post tick 取玩家 `chunkPosition()` 对应的 `serverLevel.getChunkSource().getChunkNow(cx, cz)`——返回非 null 即该列已到 FULL（`LevelChunk` 只有 FULL 才存在），记 `t1 = t1 - t0`。精度是 tick 粒度（~50ms），对本测量（秒级窗口）足够；比事件监听少一套挂载/摘除路径，且与道路网同款 API、非阻塞。首次满足的 tick 即判定，不等待周围区块。

**D3 目的地派生只用「世界种子 + 参数 + 序号」。**
`RandomSource.create(seed * 31 + minR * 100003 + maxR * 1000003 + index)`，从该 RNG 取角度与半径（`minR..maxR` 缺省 3000..20000——远离原点预生成区，覆盖震中与碎屑带）。同种子同参数必出同序列。落点 Y 用 `NoiseBasedChunkGenerator#getBaseHeight(x, z, WORLD_SURFACE, level, randomState)` + 2（与 `BrokenSpawnSearch` 第三级同一 API；破碎主世界 `aquifers=false` + `sea_level=-1000` 下「返回 > minY ⇔ 有地表」语义已验证过）。探测异常/返回 ≤ minY → 跳过该序号并记原因。玩家不在主世界时命令直接拒绝。

**D4 MSPT 采集优先用 vanilla tick 计时环形缓冲，降级自测。**
首选 `MinecraftServer` 的最近 100 tick 计时缓冲（1.21.1 的 `TickTimes`，实现时以反编译为准）；窗口内最大值即峰值，恢复时长 = 窗口末尾回落的判定。若 API 对不上，降级方案：采样器在 Post 事件里用 `System.nanoTime()` 逐 tick 自测（首尾差），精度足够。实现第一步先核实可用 API 再落笔（tasks 已标注）。

**D5 命令接线：`MaixrCommands` 只挂 `bench` 字面量，逻辑独立 `MaixrBench`。**
`.requires(src -> src.hasPermission(2))` 收紧权限（现有 /maixr 配方命令是全员可用，bench 会主动制造负载，必须分级）。报告走聊天（逐行，避免单包截断）+ `LOGGER.info` 双通道。

**D6 区块阶段归因交给 spark，不自己做插桩。**
手册指导把 spark 放进 mods（服务端单独放即可，客户端不必），TP 后 `spark profiler --timeout 60` 抓 Server thread 火焰图。自己做 per-status 计时要 mixin 进区块系统（侵入大、维护贵），而火焰图给出的就是同一份归因信息。手册同时给出无 spark 的最小替代：`/perf start/stop`（vanilla 自带，输出可读性差，仅兜底）。

**D7 手册落 `_docs/生成性能测量.md`，结构与决策表。**
内容固定六节：环境控制（固定种子/全新世界目录/热身/对照/记录 view-distance 与核数）→ spark 抓取步骤 → A/B 矩阵（破碎 vs 原版同种子；`bridgesEnabled` 开关组）→ 记录模板 → 栈顶决策表（`pollTask` 内 noise 栈 / features 栈 / `FragmentRoadNetwork` 主线程栈 / 晋升发包）→ 已知口径陷阱（第二次跑同区域因 region 缓存变快；耗时确定性不在承诺内，坐标序列确定性才在）。AGENTS.md「关键文档」与 README 概览各挂一行。

## 实现期修订（2026-10-25 端到端实测沉淀）

**D8 目的地过采样**：首轮实测碎裂世界随机列 4/4「全虚列」（uniformBand=2.0 下随机点大量落在死板块/裂缝）。「次数」改按<b>成功传送数</b>计，候选最多自动尝试 次数 × `OVERSAMPLE_FACTOR=3`，失败跳过换下一候选；候选序号仍纯由种子+参数派生，spec 确定性承诺不变。首轮两个实现 bug（基线相位未切换致越界、成功行坐标未回填）已修复。

**D9 火焰图工具链**：spark 内置 java 引擎在 dev 实测只采样到 Server thread（worker 线程缺失），worker 侧归因改用 JDK 自带 JFR（`jcmd JFR.start` + `jfr print` + `_tools/jfr_tops.py` 聚合）；spark 原始 payload 可离线解析（`_tools/SparkDump.java` + spark jar 自带 proto 类）。手册第五节已补「传送同步等待」病灶行与 worker 归因流程。

**dev 并行运行**：gradle 项目锁使 runServer/runClient 无法并发，`_tools/devlaunch.py` 镜像 gradle 的实际命令（`net.neoforged.devlaunch.Main` + `@argfile` + `-Dfml.modFolders`）裸启 JVM——缺 `-Dfml.modFolders` 时 FML 自动发现只认 resources 根，客户端 mixin 类不可见（实测踩坑）。`_tools/rcon.py` 驱动控制台（enable-rcon 已在测试 server.properties 中打开）。

## Risks / Trade-offs

- [1.21.1 tick 计时 API 名称与预期不符] → D4 的降级自测路径兜底；tasks 首项核实。
- [TP 后玩家实体 chunk 位置的更新时序与预期不符（影响 t1 判定）] → 以玩家实体每 tick 的 `chunkPosition()` 为准（单一事实源），不假设 packet 时序；玩家掉线/换维度 → 会话立即终止并清理。
- [同种子第二轮比第一轮快（region 文件缓存）造成误读] → 手册明示「全新世界目录或删除 region」，并把「坐标确定性」与「耗时确定性」在口径上分开（spec 只承诺前者）。
- [bench 自身的传送/探测开销混入测量] → 探测为单列离线调用（毫秒级），轮询为 O(1) 判空；报告不扣除这部分，手册标注误差方向（测得值略高于纯生成成本）。
- [聊天栏刷屏] → 每次 TP 只回显一行，汇总集中一次输出。

## Migration Plan

纯增量：新命令类 + 新文档 + 两处清单挂行。无存档、无网络包、无配置迁移。回滚 = 移除 bench 字面量与 `MaixrBench` 类。

## Open Questions

（无——tick 计时 API 的具体名称属实现核实项，已有降级路径，不影响 spec 与任务拆分。）
