# Proposal

## Why

破碎世界取消四带静滞场后，出生点周围就是碎裂区。vanilla 出生点搜索
（`MinecraftServer.setInitialSpawn` → `PlayerRespawnLogic`）的兜底分支
不做任何合格性检查——121 个螺旋区块全部失败时直接把出生点设到候选区块
的 `spawnHeight` 高度上，该处可能是纯虚空：玩家进入后掉虚空死亡、无法正常开始
（用户实测）。需要一个"一定出生在自然地表上"的出生位置保证。

## What Changes

- **新增**：破碎世界（`maixr:broken`）的世界出生点选择接管——挂 NeoForge
  `LevelEvent.CreateSpawnPosition` 事件（vanilla `setInitialSpawn` 内预留、
  可 cancel），取消 vanilla 搜索后自定搜索出生点：
  - **两级搜索**：碎裂场掩码离线预筛（纯函数逐列判定有无地形，不加载区块）
    → 命中区块真实生成后逐列校验；
  - **合格列判定**：出生列存在表面方块（首个非空气方块，排除纯虚空列），
    出生位（表面上方 1 格）与其上一格均为空气；表面材质不限（用户决定简化：
    泥土/草/树叶/断面均可，不再要求 DIRT 系或 30 格内木头）；
  - **无限螺旋**：mask 预筛与真实校验交替向外扩大，直到找到合格列
    （不退化为任意位置）；
- 合格出生点设置后由 `getSharedSpawnPos` 天然覆盖**无床复活**（同一来源）；
- vanilla `minecraft:normal` preset 与其它世界类型不受影响（事件仅对
  `BrokenOverworldChunkGenerator` 的主世界生效）。

## Capabilities

### New Capabilities

（无——出生点选择是 `broken-world-type` 能力的行为细化，不新增能力边界。）

### Modified Capabilities

- `broken-world-type`：新增 Requirement「出生点选择」（保证出生在有真实
  地形的列、头顶两格空气、无限螺旋搜索）；「地形与结构完全解耦」等既有需求不变。

## Impact

- **代码**：新增出生点搜索类（事件监听 + 螺旋搜索 + 掩码预筛 + 列校验，
  复用 `FractureDensityFunction` 纯函数做离线预筛）；`MaixrMod` 注册事件监听。
  无 mixin、无 vanilla 覆盖。
- **兼容性**：仅影响新世界的初始出生点设定（`setInitialSpawn` 时机）；
  已有存档的 level.dat spawn 不变。下界/末地不受影响。
- **风险**：极小概率（极端种子）搜索耗时变长——mask 预筛每区块 <1ms，
  碎裂场约 20% 列有地形且地表多为 DIRT 系，实际上几百格内必命中。
