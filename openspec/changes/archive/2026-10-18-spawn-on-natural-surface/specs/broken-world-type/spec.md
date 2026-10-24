# Spec Delta

## ADDED Requirements

### Requirement: 出生点选择

「破碎」世界的主世界 SHALL 接管世界出生点选择：`LevelEvent.CreateSpawnPosition`
事件中取消 vanilla 搜索后，SHALL 以两级搜索（碎裂场掩码离线预筛 → 真实区块校验）
确定出生位置。出生位置 MUST 满足：出生列存在表面方块（从上往下第一个非空气
方块），出生位（表面方块上方 1 格）与其上一格均为空气；表面方块的材质 MUST NOT
受限（泥土/草方块/树叶/石头断面等任何真实地形表面均可出生，用户决定简化）。

#### Scenario: 出生在有地形的列

- **WHEN** 玩家首次进入新建的破碎世界
- **THEN** 出生位置脚下存在非空气方块（自然地表/断面均可），
  出生位与其上一格为空气

#### Scenario: 避开虚空

- **WHEN** 出生点搜索执行
- **THEN** 纯虚空列（无任何非空气方块）不被选为出生点，
  玩家进入世界后不因出生位置而掉虚空死亡

### Requirement: 出生点无限螺旋搜索

出生点搜索的区块螺旋 SHALL 以世界原点为心、按半径逐轮无限扩大，
直到找到满足全部合格条件的列；MUST NOT 退化为任意位置
（用户决定：宁可搜索更远也不出生在不合格位置）。

#### Scenario: 无限螺旋保证命中

- **WHEN** 靠近原点的区块无合格列
- **THEN** 搜索螺旋持续向外扩大直到找到合格列（碎裂场约 20% 列有地形，
  实际第一区块内即命中）

### Requirement: 出生点接管范围与复活沿用

出生点接管 ONLY 作用于主世界生成器为 `BrokenOverworldChunkGenerator` 的世界；
vanilla `minecraft:normal` preset 与其它世界类型（含空岛）MUST 不受影响。
无床复活 SHALL 沿用同一出生点（`getSharedSpawnPos`）。

#### Scenario: 无床复活沿用

- **WHEN** 无床玩家死亡复活
- **THEN** 复活位置基于同一出生点（`getSharedSpawnPos`）

#### Scenario: 其它世界类型不受影响

- **WHEN** 主世界生成器不是 `BrokenOverworldChunkGenerator`
- **THEN** vanilla 出生点选择照常执行（事件不取消）
