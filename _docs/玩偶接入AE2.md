# 玩偶接入 AE2 ME 网络

## 需求

`maixr:maixr_skin_doll` 与 `maixr:truth_doll` 两个**放置出来的玩偶方块**要能：

1. 接入 AE2 的 ME 网络，**各占 1 个频道**；
2. 接入后显示为**在线设备**；
3. 接入期间**每分钟产出 1 个自己**，写进网络存储；
4. 存储不够时不产出（"如果磁盘够存储的话"）。

---

## 实现

### 1. 方块实体继承 AE2 基类

`MaixrDollBlockEntity extends AENetworkedBlockEntity implements IGridTickable`
（`TruthDollBlockEntity` 继承它，只覆写 `getProducedItem()`）。

构造器里：

```java
this.getMainNode()
        .setFlags(GridFlags.REQUIRE_CHANNEL)     // 必须真拿到 1 个频道
        .addService(IGridTickable.class, this);  // 注册进 AE2 tick 服务
```

- `REQUIRE_CHANNEL` → 一个这样的节点默认就吃 **1 频道**；
- 拿到频道后 `getMainNode().isOnline()` 为真 → 网络里显示为**在线设备**；
- `AENetworkedBlockEntity` 已经接好节点生命周期（`clearRemoved → scheduleInit →
  onReady → mainNode.create`，`setRemoved/onChunkUnloaded → mainNode.destroy`）、
  NBT 存读、朝向变化后重新暴露侧面。

### 2. ★ 必须自己注册能力（少这一步线缆永远连不上）

AE2 的 `InitCapabilityProviders.register` 只遍历**它自己**的类型：

```java
for (BlockEntityType<?> type : AEBlockEntities.getImplementorsOf(IInWorldGridNodeHost.class)) {
    event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, type,
            (object, context) -> (IInWorldGridNodeHost) object);
}
```

我们模组的类型不在那张表里 —— 哪怕类实现了 `IGridConnectedBlockEntity`，
**线缆在旁边也拿不到节点宿主能力**，表现为"贴上线缆完全没反应、网络里看不到它"。

所以 `MaixrAe2Integration.registerCapabilities` 里显式注册：

```java
event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
        MaixrBlockEntities.SKIN_DOLL.get(), (blockEntity, context) -> blockEntity);
event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
        MaixrBlockEntities.TRUTH_DOLL.get(), (blockEntity, context) -> blockEntity);
```

并在 `MaixrMod` 构造器里 `modBus.addListener(MaixrAe2Integration::registerCapabilities)`。

### 3. 每分钟产出

```java
@Override
public TickingRequest getTickingRequest(IGridNode node) {
    return new TickingRequest(20, 20, false, 20);   // isSleeping=false：保持持续 tick
}

@Override
public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
    if (!this.getMainNode().isOnline()) return TickRateModulation.SAME;  // 没接入 → 暂停计时
    this.cooldown -= ticksSinceLastCall;
    if (this.cooldown > 0) return TickRateModulation.SAME;
    if (this.produceOne()) this.cooldown = PRODUCE_INTERVAL;   // 1200 tick = 1 分钟
    else this.cooldown = RETRY_INTERVAL;                       // 存储满 → 5 秒后重试
    return TickRateModulation.SAME;
}

private boolean produceOne() {
    IGrid grid = this.getMainNode().getGrid();
    if (grid == null) return false;
    MEStorage storage = grid.getStorageService().getInventory();
    AEItemKey key = AEItemKey.of(new ItemStack(this.getProducedItem()));
    return storage.insert(key, 1L, Actionable.MODULATE, this.actionSource) > 0;
}
```

- `insert` 返回**实际插入的数量**，返回 0 即"存储满了"；
- 满的时候不重置成 1 分钟，而是 5 秒重试，避免满了以后要等很久才恢复；
- 没频道 / 网络没电时 `isOnline()` 为假，**计时原地暂停**；
- `TickingRequest` 的 `isSleeping` 传 **false**（AE2 的自动休眠默认会睡，睡了就不产出了）；
- `onReady()` 里 `getMainNode().setVisualRepresentation(玩偶物品)` ——
  网络状态面板里用玩偶本体当图标；
- `Spinning` 与 `Cooldown` 随方块实体持久化（`saveAdditional` / `loadTag`），
  重启后产出周期不丢失。

### 4. 客户端同步

★★ **不要**自己覆写 `getUpdateTag()` 往 AE2 的 tag 里塞字段。
`AEBaseBlockEntity.loadAdditional` 只有在 tag **仅含** `#upd` 一个键时才走流式解析，
多塞一个键会让**整条同步失效**。

正确做法是走 AE2 的流式通道：

```java
@Override
protected void writeToStream(RegistryFriendlyByteBuf data) {
    super.writeToStream(data);
    data.writeBoolean(this.spinning);
}

@Override
protected boolean readFromStream(RegistryFriendlyByteBuf data) {
    boolean changed = super.readFromStream(data);
    boolean now = data.readBoolean();
    if (now != this.spinning) { this.spinning = now; changed = true; }
    return changed;
}
```

---

## 相关文件

| 文件 | 作用 |
|---|---|
| `MaixrDollBlockEntity.java` | 玩偶方块实体：继承 `AENetworkedBlockEntity`，占频道 + 每分钟产出 |
| `TruthDollBlockEntity.java` | 真理玩偶方块实体：只覆写 `getProducedItem()` |
| `MemoryTomorrowDollBlockEntity.java` | 致回忆中的明日方块实体：同样只覆写 `getProducedItem()` |
| `MaixrAe2Integration.java` | 给三个方块实体类型显式注册 `IN_WORLD_GRID_NODE_HOST` 能力 |
| `MaixrBlockEntities.java` | 注册 `maixr_skin_doll` / `truth_doll` / `memory_tomorrow_doll` 三个方块实体类型 |
| `MaixrNetwork.java` + `client/MaixrClientPackets.java` | 真理玩偶触发不死保护时通知客户端（客户端类只收在 client 包里） |

> 现行行为规范见 `openspec/specs/doll-ae2-network/spec.md`。

### 第三只玩偶：致回忆中的明日（memory_tomorrow_doll）

流萤/萨姆双形态玩偶（行为规范见 `openspec/specs/memory-tomorrow-doll`，机制设计见
`openspec/changes/add-memory-tomorrow-doll/design.md`）。对 AE2 部分**零新增逻辑**：

- `MemoryTomorrowDollBlockEntity` 继承 `MaixrDollBlockEntity`，仅覆写产出物；
- 外观状态（流萤/萨姆）放在 **BlockState 的 `sam` 属性**上而不是方块实体字段，
  因此 AE2 流式同步通道（`writeToStream`）完全不用动；
- `MaixrAe2Integration` 给新类型补了一行 `IN_WORLD_GRID_NODE_HOST` 注册（漏了线缆连不上，同前两只的坑）。

---

## 依赖

`build.gradle`：

```groovy
compileOnly files("libs/appliedenergistics2-19.2.18.jar")
```

`neoforge.mods.toml` 里声明必需依赖（方块实体直接继承 AE2 类，没有 AE2 会类加载失败）：

```toml
[[dependencies.${mod_id}]]
modId = "ae2"
type = "required"
versionRange = "[19.2,)"
ordering = "AFTER"
side = "BOTH"
```

---

## 进游戏验证清单

1. 在 ME 网络（有控制器或 ad-hoc 网络）旁放玩偶，**贴上线缆**；
2. 网络状态里能看到它、且占用 **1 个频道**；
3. 放一个 ME 驱动器 + 空存储元件；
4. 等 1 分钟 → 驱动器里出现 1 个对应玩偶；
5. 把存储元件拔掉（或塞满）→ 不再产出；插回去 → 约 5 秒内恢复产出；
6. 断开线缆 → 停止产出；接回 → 继续。
