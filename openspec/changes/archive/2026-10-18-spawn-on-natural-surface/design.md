# Design

## Context

统一碎裂场（`rewrite-broken-world-gen` 已归档）下，出生点周围就是碎裂区。
vanilla 出生点选择（`MinecraftServer.setInitialSpawn` → 11×11 区块螺旋 →
`PlayerRespawnLogic.getSpawnPosInChunk`）的两层防线在碎裂世界全部失效：

- `isFaceFull(UP)` 对石头/深板岩断面同样成立——vanilla 不懂"碎裂"；
- 121 个螺旋区块全失败时的兜底分支**不做任何检查**，直接把出生点设到
  `chunkpos + (8, spawnHeight, 8)`——该处可能是纯虚空，玩家进入即掉虚空死亡。

目标：出生位置 MUST 落在"未被撕裂改造的自然地表"（泥土/草方块/树叶系）上，
上方两格空气，保证进入世界一定能正常开始。

## Goals / Non-Goals

**Goals:**

- 新建破碎世界首次出生 100% 落在**有真实地形的列**上（绝不落虚空）
- 无床复活自动沿用同一出生点
- 搜索高效：利用碎裂场纯函数做离线预筛，避免盲目生成区块

**Non-Goals:**

- 不改无床复活算法本身（沿用 `getSharedSpawnPos`）
- 不为下界/末地做出生点处理（原版逻辑天然适用）
- 不恢复开局空岛（已移除）
- 不保证出生点表面材质（泥土/断面均可）与开局木头（用户决定简化，见 D5）

## Decisions

### D1 挂点：`LevelEvent.CreateSpawnPosition`（NeoForge 官方事件，无 mixin）

vanilla `setInitialSpawn` 内预留 `EventHooks.onCreateWorldSpawn(level, levelData)`——
返回 true 时跳过 vanilla 搜索。NeoForge 事件 `LevelEvent.CreateSpawnPosition`
可 cancel；监听器内自定 `levelData.setSpawn(pos, 0.0F)` 后 cancel 即可。
事件仅在主世界出生点初始化时触发一次；仅当
`level.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator`
时介入，其它世界类型照常放行（spec「其它世界类型不受影响」）。

- 否决 **mixin `PlayerRespawnLogic`**（探索期方案 A）：只能在 vanilla 算法里
  加过滤，搜索范围（11×11）受 vanilla 硬编码限制，碎裂世界命中率低。
- 否决 **覆盖 normal preset**：前次用户已明确否决（vanilla normal 必须
  保持原版地形）。

### D2 两级搜索：掩码离线预筛 → 真实区块校验

碎裂场是纯坐标函数——不加载区块即可判定任意列有无地形：

1. **预筛**：以原点为心的**区块粒度螺旋**（半径逐轮扩大、无限），
   对区块内 16×16 列采样 `FractureDensityFunction.compute(SinglePointContext(x, 0, z))`
   ——减法语义下「该列可能有地形」⟺ 存活率 > 0 ⟺ `compute > -TEAR_SCALE`
   （`compute` 输出撕裂深度 ∈ [-4, 0]；文档早先写的「掩码 > 0」是初版
   乘法掩码口径，同一语义的减法映射）。掩码 = -TEAR_SCALE 的列
   （完全撕裂：死亡板块/宽裂缝中心）直接排除。
   每列 ~1 次掩码求值（含域扭曲 3 次噪声采样 + Voronoi）≈ 数十微秒，
   每区块 256 列 <1ms。
2. **校验**：预筛通过的区块按螺旋序 `level.getChunk(cx, cz)` 真实生成，
   逐列取 `Heightmap.Types.WORLD_SURFACE` 顶（首个非空气方块）：
   - 出生位（表面上方 1 格）与其上一格均为空气；
   - **全部合格 → `levelData.setSpawn(pos.above(), 0.0F)` + 事件 cancel + 搜索结束**。
3. **无限螺旋**：某区块校验失败 → 继续下一区块，半径逐轮扩大，
   **不设上限、不退化**（用户决定）。

### D5 口径简化（用户决定，2026-10-18 实测后）

原设计要求「表面 ∈ `BlockTags.DIRT` ∪ `BlockTags.LEAVES` + 出生点 30 格内
存在原木（开局可合成）」。runServer 实测：碎裂区存活碎块上树木稀疏，
该口径下螺旋推进到 13 区块、290 个预筛命中区块仍未找到合格列——
**30 格木头检查在碎裂世界过于苛刻**。用户决定简化为
「**生成在正常区块（有真实地形的列）即可**」：

- 表面材质不再受限（泥土/草/树叶/石头断面均可）；
- 移除 30 格木头检查（`BlockTags.LOGS` 扫描与区块级缓存一并移除）；
- 保留：虚空列排除（vanilla bug 的修复本体）、出生位+其上一格空气、
  掩码预筛、无限螺旋。

放宽后出生点不再保证「开局即木头」与「非断面」——碎石断面出生可玩
（vanilla 山地同款），此为用户接受的取舍。

### D3 事件线程与时序

`setInitialSpawn` 在服务器线程、`prepareLevels` 之前调用——此时区块按需生成
（`getChunk` 触发同步生成，vanilla 螺旋同款）。搜索耗时计入世界启动
（单人创建世界等待屏）；mask 预筛保证只会生成少量候选区块。

### D4 复活自动覆盖

出生点写入 `levelData` 后，`getSharedSpawnPos` 与无床复活
（`missingRespawnBlock` 兜底分支）都从同一来源读取——无需额外逻辑。

## Risks / Trade-offs

- **极端种子搜索耗时**：无限螺旋理论上无上限——概率上碎裂场有地形列
  密度足以在第一区块内命中（简化口径后）；可接受。
- **预筛误报成本**：掩码 > -TEAR_SCALE 但全列为断面虚空 → 白生成一个区块
  （~40ms）——螺旋序继续，无正确性影响。
- **断面/幽匿表面出生**：简化口径的既定取舍（D5）——石头断面、幽匿置换
  表面均可出生，可玩性优先。

## Open Questions

（无——口径已按用户决定简化为「正常区块」（D5）；无限螺旋不退化与
脚下+头顶两格空气维持原对齐。）
