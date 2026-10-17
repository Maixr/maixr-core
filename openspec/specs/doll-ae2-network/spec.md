# 玩偶接入 ME 网络 Specification

## Purpose

两个放置出来的玩偶方块（`maixr:maixr_skin_doll` / `maixr:truth_doll`）接入 AE2 的
ME 网络：各占 1 个频道、显示为在线设备，接入期间每分钟产出 1 个自己写入网络存储；
同时提供佩戴效果（夜视 / 不死图腾保护）与旋转动画。

实现文件：`MaixrDollBlockEntity`、`TruthDollBlockEntity`、`MaixrAe2Integration`、
`MaixrBlockEntities`、`MaixrDollItem`、`SkinDollBlock`、`TruthSkinDollBlock`、
`MaixrCurios`、`MaixrDollEffects`、`TruthTotemHandler`、`MaixrNetwork`、
`MaixrDollBlockRenderer`、`MaixrClientEvents`、`client/MaixrClientPackets`。
设计细节见 `_docs/玩偶接入AE2.md`。

## Requirements

### Requirement: 频道与在线状态

玩偶方块实体 SHALL 继承 `AENetworkedBlockEntity` 并设置
`GridFlags.REQUIRE_CHANNEL`：接入网络必须真实占用 1 个频道，
取得频道后 MUST 显示为在线设备。
两个方块实体类型 MUST 经 `MaixrAe2Integration` 在 `RegisterCapabilitiesEvent`
中显式注册 `AECapabilities.IN_WORLD_GRID_NODE_HOST`
（AE2 只为它自己的方块实体类型注册该能力，缺这一步线缆永远连不上）。

#### Scenario: 线缆连接

- **WHEN** 玩偶方块旁边贴上 ME 线缆
- **THEN** 网络中可见该设备且占用 1 个频道

### Requirement: 周期产出

接入且在线时 SHALL 每 `PRODUCE_INTERVAL = 1200` tick（1 分钟）以 `MachineSource`
身份往网络存储插入 1 个对应的玩偶物品（Maixr 玩偶 → Maixr 玩偶，真理玩偶 → 真理玩偶）；
插入返回 0（存储满）时 MUST 改为每 `RETRY_INTERVAL = 100` tick 重试；
未取得频道或网络断电（`isOnline()` 为假）时 MUST 原地暂停计时。
AE2 tick 请求 `TickingRequest` 的 isSleeping MUST 为 false。
网络状态面板的图标 SHALL 为对应玩偶物品（`setVisualRepresentation`）。
Spinning 与 Cooldown MUST 随方块实体持久化。

#### Scenario: 正常产出

- **WHEN** 网络有电、玩偶拿到频道、存储有余位
- **THEN** 每 1 分钟网络存储新增 1 个该玩偶

#### Scenario: 存储满

- **WHEN** 存储已满
- **THEN** 停止产出，每 5 秒重试；恢复后周期不丢失

#### Scenario: 断电或断线

- **WHEN** 玩偶失去频道或网络断电
- **THEN** 产出计时原地暂停，恢复后继续

### Requirement: 客户端同步

玩偶方块实体的自定义同步字段（Spinning）MUST 走 AE2 的流式通道
（覆写 `writeToStream` / `readFromStream`），
MUST NOT 自行覆写 `getUpdateTag()` 往 tag 里塞额外键
（`AEBaseBlockEntity.loadAdditional` 仅在 tag 只含 `#upd` 时走流式解析，多键会让同步整条失效）。

#### Scenario: 旋转状态同步

- **WHEN** 服务端切换玩偶旋转状态
- **THEN** 客户端经流式通道收到变化并驱动渲染动画

### Requirement: 佩戴与互动

玩偶物品 SHALL 可放置为方块，也可佩戴：对空气右键 = 佩戴
（优先 Curios 头饰槽，其次原版头盔槽；`getEquipmentSlot` 返回 HEAD）。
佩戴 Maixr 玩偶（头盔槽或 Curios 头饰槽）时玩家 SHALL 持续获得夜视
（420 tick，剩余低于 220 tick 时刷新）。
真理玩偶在头盔槽 / Curios 头饰槽 / 副手 / 主手任一位置时，
死亡 SHALL 被取消并按不死图腾逻辑复活（生命值 1、再生 II 45 秒、吸收 II 5 秒），
并消耗 1 个真理玩偶、向客户端发送图腾激活动画包。
Curios 相关调用 MUST 有 `ModList.isLoaded()` 守卫。

#### Scenario: 真理玩偶救主

- **WHEN** 佩戴或手持真理玩偶的玩家死亡
- **THEN** 死亡取消，生命值恢复到 1，获得再生 II 与吸收 II，真理玩偶消耗 1 个

#### Scenario: 佩戴 Maixr 玩偶获得夜视

- **WHEN** 玩家头盔槽或 Curios 头饰槽戴着 Maixr 玩偶
- **THEN** 夜视效果持续刷新

### Requirement: 旋转动画

放置的玩偶（空手右键方块）与佩戴中的玩偶（空手右键空气）SHALL 支持旋转开关，
旋转状态存于物品自定义 NBT（`maixr_spin`）与方块实体。
客户端 SHALL 经方块实体渲染器（cutout，绕中心 `SPIN_SPEED = 60` 度/秒）
与玩家头部渲染层（`CustomHeadLayer` 锚点）驱动动画。

#### Scenario: 放置的玩偶旋转

- **WHEN** 空手右键放置的玩偶方块
- **THEN** 旋转状态切换并同步到客户端渲染

#### Scenario: 佩戴的玩偶旋转

- **WHEN** 玩家佩戴玩偶时空手右键空气（主手）
- **THEN** 头上玩偶的旋转状态切换，动作栏提示开始/停止旋转
