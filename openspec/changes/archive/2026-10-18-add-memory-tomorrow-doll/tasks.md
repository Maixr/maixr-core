# Tasks

## 1. 换肤方案 spike（决定 design D4 走首选还是兜底）

- [x] 1.1 Spike（源码级验证，原"runClient 肉眼验证"因首选 API 不存在而改法）：核对 NeoForm 1.21.1 反编译源，确认 `SkinManager` 无 `registerSkins`（1.20.2 重构移除）、皮肤缓存按 `CacheKey(profileId, packedTextures)` 键控且 15s 过期、`PlayerInfo` 将 `getOrLoad` future memoize、`AbstractClientPlayer#getSkin` 委托 `PlayerInfo#getSkin`。验证：SkinManager.java / PlayerInfo.java / AbstractClientPlayer.java 三处源码结论在 design.md D4 引用。
- [x] 1.2 Spike（还原路径与依赖调研）：mixin 覆盖表方案"还原=移除表项"，不污染皮肤缓存、披风无损；调研 CustomSkinLoader（用户提议的依赖）——对外无换肤 API、不上 maven、钩子为自用 ASM，不可作为依赖，但其 SkinManagerPatch 证实钩子点；确认与 CSL 钩子点不同互不冲突；核实 FML 4.0.45 支持 `[[mixins]]`、NeoForge 21.1 运行时为 mojmap（免 refmap）。验证：CSL 仓库源码与 FML loader 字节码证据已记入 design.md D4。
- [x] 1.3 spike 结论写回 design.md D4（mixin 拦 `PlayerInfo#getSkin`）并同步 proposal.md 风险行；mixin 基础设施（`maixr.mixins.json` + mods.toml `[[mixins]]` + mixin 类）随任务 6 落地。验证：design.md D4 已更新为定案方案。

## 2. 注册与资源骨架

- [x] 2.1 新建 `MemoryTomorrowDollBlock`（FACING + SAM 布尔属性，codec 用 simpleCodec）、`MemoryTomorrowDollBlockEntity`（继承 `MaixrDollBlockEntity`，产出物 = 本玩偶物品）、`MemoryTomorrowDollItem`（继承 `MaixrDollItem`），在 `MaixrMod` / `MaixrBlockEntities` 注册并加入创造标签与 `isDollItem`。验证：`./gradlew build` 通过，物品出现在 maixr 标签页。
- [x] 2.2 资源文件：blockstate（4 朝向 × SAM 两种共 8 变体）、两个方块模型（克隆 `maixr_skin_doll.json` 几何，贴图分别指流萤/萨姆皮肤）、两个物品模型（父模型 + `maixr_sam` 谓词 overrides）、两张 64×64 皮肤入库（`textures/entity/`，方块与换肤共用）。注册 `ItemProperties` 谓词读 `NbtCompat` 自定义数据 `maixr_sam`。验证：runClient 中手持/放置两种外观渲染正确。
- [x] 2.3 lang 条目：zh_cn「致回忆中的明日」/ en_us（含物品名）。验证：游戏内物品名正确显示。

## 3. 放置态交互与外观切换

- [x] 3.1 `useWithoutItem` 按蹲下分流：蹲下 = `cycle(SAM)` 切换外观，不蹲 = `toggleSpinning()`（沿用基类逻辑）；`getStateForPlacement` 读取手持物品的 `maixr_sam` 决定初始 SAM 状态。验证：runClient 中蹲下右键切换流萤/萨姆、普通右键切换旋转。
- [x] 3.2 验证掉落规则：挖掘萨姆外观的放置玩偶掉落流萤物品（BlockItem 默认战利品，不携带方块状态）。验证：破坏后地面物品为流萤外观。

## 4. 变身服务端逻辑

- [x] 4.1 实现 `MemoryTomorrowDollItem#tryTransform(player)` 统一入口：查冷却（`getCooldowns`）→ 写玩家数据附件（到期 gameTime+200、飞行授予标记）→ `mayfly`+`flying` 置真并 `onUpdateAbilities()` → 速度 II 200 tick → 给触发用的那件玩偶（手持或佩戴）写入 `maixr_sam`（NbtCompat set 写回）→ 600 tick 冷却 → 播放语音 → 广播换肤开启包。验证：runClient 触发后 10 秒内可飞行、有速度 II、物品冷却条显示。
- [x] 4.2 覆写 `use()`：不蹲下 = 变身（冷却中/pass 时返回 PASS），蹲下 = 复用父类佩戴逻辑（Curios 头槽优先，其次头盔槽）。验证：两条路径各自动作正确且互不串扰。
- [x] 4.3 到期回收：服务端 `PlayerTickEvent.Post` 检查附件到期 → 仅当飞行由本能力授予且玩家非创造时收回 `mayfly`/`flying` 并同步 → 清附件 → 广播还原包 → 扫描背包/头盔/Curios 头槽清除 `maixr_sam`。验证：10 秒后自动还原；创造模式玩家飞行不被误收。
- [x] 4.4 死亡与重登时序：变身中死亡重生无能力/皮肤残留（附件不复制、飞行由原版重生重置）；重登后若附件已过期在 tick 内回收。验证：两种场景手动复现确认无残留。

## 5. 网络同步与快捷键

- [x] 5.1 `MaixrNetwork` 新增 `MemoryDollSkinMsg(UUID, boolean)`（S2C）注册与广播辅助（本人 + `sendToPlayersTrackingEntity`）；客户端处理器落在 `com/maixr/client/`。验证：双人联机第二客户端能看到换肤/还原。
- [x] 5.2 `MemoryDollHotkeyMsg`（C2S）注册：服务端校验佩戴本玩偶（头盔槽或 Curios 头槽，Curios 带 `isLoaded()` 守卫）且不在冷却，通过后走 `tryTransform`。验证：未佩戴/冷却中按快捷键无效果。
- [x] 5.3 客户端注册 KeyMapping（分类 maixr，默认 R）+ `ClientTickEvent` 中 `consumeClick()` 检测后 `sendToServer`。验证：默认键与改键均生效。

## 6. 客户端换肤

- [x] 6.1 新建 `client/MemoryDollSkinManager`：UUID → 覆盖状态表；收到开启包时先捕获原 `PlayerSkin` 再按 spike 结论应用萨姆贴图（classic/slim 按皮肤实际类型），收到关闭包还原；客户端断线清表。验证：第一/第三人称换肤与还原一致，退出重进无残留。
- [x] 6.2 （1.3 结论为 mixin 分支）实现换肤 mixin——spike 后落点定为更优的 `PlayerInfo#getSkin`（`AbstractClientPlayer#getSkin` 直接委托它），`client/mixin/PlayerInfoSkinMixin` @Inject(HEAD, cancellable) 读取覆盖表。验证：编译通过，生效走查归 8.1。

## 7. 声音与语音文件

- [x] 7.1 新建 `DeferredRegister<SoundEvent>`（项目首个）注册 `maixr:memory_doll_transform`（变身随机语音事件），`sounds.json` 挂 4 个音频随机播放、`stream: true`、单声道 ogg，挂入 `MaixrMod` 构造器注册；到期还原由代码直接播放原版 `SoundEvents.END_PORTAL_FRAME_FILL`（真身 SFX 到位前）。音效以玩家实体为声源（随移动）、`SOUND_VOLUME = 3.0F`（可闻约 48 格）。验证：变身/还原时周围玩家在 48 格内可听到、声源随使用玩家移动。
- [x] 7.2 获取四条变身语音（终结技·激活 / 终结技·施放 / 完全燃烧战技·一 / 完全燃烧战技·二，文件 `memory_doll_transform_1..4.ogg`）：B 站语音合集（BV1piv6ePEbN）下载音频 → faster-whisper 定位四条台词时间戳 → ffmpeg 切片转单声道 ogg 入库；`sounds.json` 随机池生效（备用来源：用户提供的百度网盘素材包，需登录）。验证：变身时四条语音随机播放、清晰可辨。
- [x] 7.3 变身结束音效：真身"大招结束"SFX 未获取到，代码暂播原版 `END_PORTAL_FRAME_FILL`（用户指定替代）；后续拿到真身 SFX 注册 `memory_doll_revert` + ogg 替换。验证：变身结束时可听到末地门框架音效。

## 8. 集成验证与文档

- [x] 8.1 对照 specs/memory-tomorrow-doll/spec.md 的 8 条需求逐条走查验收（默认外观、放置交互、手持变身/佩戴、快捷键、冷却 30s、变身效果包、到期还原、AE2 产出接入线缆等 1 分钟）。验证：用户 runClient 实机走查通过（2026-10-18 确认：贴图正常 + 其余走查项全部正常；走查中发现的两处问题——图集缺失贴图、语音截断——已修复并复验）。
- [x] 8.2 `./gradlew build` 全量通过。验证：构建产物 `build/libs/MaixrCore-1.21.1-2.0.0.jar` 生成。
- [x] 8.3 文档同步：README 功能清单加入新玩偶；`_docs/玩偶接入AE2.md` 补充第三只玩偶的继承说明；核对文档常量（200/600/1200 tick 等）与代码一致。验证：文档与代码常量逐项对上。

## Workflow follow-up

- 实施完成后用 `/opsx:archive` 归档本变更。
