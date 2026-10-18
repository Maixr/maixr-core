# Design

## Context

现有玩偶体系（`SkinDollBlock` + `MaixrDollBlockEntity` + `MaixrDollItem` + `MaixrDollEffects` + `MaixrNetwork`）已提供：放置/朝向/旋转动画、AE2 接入与周期产出、Curios/头盔佩戴、NBT 状态存储、payload 注册先例（`TruthTotemMsg`）。新玩偶大部分行为可直接继承。

两个关键既有事实决定本设计：

1. **玩偶方块模型的 UV 在 0–16 归一化空间，与贴图分辨率无关**，且现有几何的 UV 本来就是按 64×64 皮肤布局的 1/4 比例摆放的。因此用户提供的流萤/萨姆两张 64×64 皮肤可以**不经缩放**直接作为玩偶方块贴图，与玩家换肤共用同一份文件。
2. **项目当前没有 mixin、没有声音注册**。选型优先走无 mixin 的官方/NeoForge 钩子，声音注册需要从零新建。

硬规则约束（来自 openspec/config.yaml）：客户端类引用只进 `com/maixr/client/`；payload 走 `RegisterPayloadHandlersEvent`；物品 NBT 走 `NbtCompat` 且改完必须 `set` 写回；Curios 调用带 `ModList.isLoaded()` 守卫。

## Goals / Non-Goals

**Goals:**

- 一只双形态玩偶：流萤/萨姆外观切换、变身触发（手持右键 + 佩戴快捷键）、变身效果包、冷却与自动还原
- 玩家换肤对周围玩家可见（广播同步），单机无感知差异
- AE2 行为零成本继承（复用 `MaixrDollBlockEntity`）
- 玩家换肤方案经 spike 验证后再定型

**Non-Goals:**

- 不为佩戴本玩偶设计被动效果（夜视仍专属 Maixr 玩偶，图腾仍专属真理玩偶）
- 不做变身期的额外视觉特效（粒子/光环）、不做变身音效以外的 BGM
- 不改现有两只玩偶的任何行为；不动 `doll-ae2-network` 规范
- 语音文件的版权问题不在本设计解决范围（整包私用，文件由任务阶段获取）

## Decisions

### D1. 放置态皮肤状态放 BlockState（`SAM` 布尔属性），不放方块实体字段

方块状态客户端自动同步，现有 `MaixrDollBlockRenderer` 按 `be.getBlockState()` 取烘焙模型渲染——加一个属性后 blockstate JSON 指向两个模型即可，**渲染器零改动**；AE2 流式同步通道也不用碰。备选方案（BE 布尔字段 + `writeToStream` 扩展）要多写同步代码且渲染器要动态换贴图，无收益。

"挖掘仍掉落流萤"由此天然成立：BlockItem 掉落不携带方块状态。

### D2. 物品外观状态存 NbtCompat 自定义数据（`maixr_sam`），渲染走物品模型谓词 + overrides

与现有 `TAG_SPIN` 同一套机制（1.21.1 物品模型仍是传统 `overrides` 体系）：注册一个 `ItemProperties` 谓词读自定义数据，物品模型 JSON 里按谓词值在流萤/萨姆两个子模型间切换。备选（独立第二个物品 ID）被否：用户需求是"同一个娃娃换皮肤"，双 ID 会把 AE2 产出、佩戴判定、冷却全部复杂化。

注意 NbtCompat 硬规则：`getOrCreate` 返回副本，改完必须 `set` 写回。

### D3. 玩偶贴图直接使用 64×64 皮肤文件，零像素画

> ★ 8.1 走查修复（2026-10-18）：皮肤放在 `textures/entity/` 下，而 1.19.3+ 的方块图集只自动收 `textures/block|item/` 目录——模型引用的贴图不在图集就会渲染成紫黑"缺失贴图"。修复：新增 `assets/minecraft/atlases/blocks.json`（★ 必须放 **minecraft 命名空间**——blocks 图集的定义路径锁死为 `minecraft:atlases/blocks.json`，mod 命名空间的同名文件不会被读；资源栈逐层解析、来源合并），用两个 `single` 图集源把两张皮肤并入方块图集。玩家换肤走实体贴图系统、不经图集，不受影响。

同一几何克隆两份方块模型 JSON（`memory_doll_firefly.json` / `memory_doll_sam.json`），仅贴图引用不同；物品模型同理。皮肤文件同时用作客户端玩家换肤贴图（`textures/entity/`）。备选（把皮肤缩成 16×16 迷你贴图）被否：细节损失大且多一道人工工序。

### D4. 玩家换肤：mixin 拦 `PlayerInfo#getSkin`（spike 结论，2026-10-18 定案）

spike（源码级验证：NeoForm 1.21.1 反编译源 + CustomSkinLoader 源码比对）结论：

- **原首选方案不存在**：1.20.2 重构后 `SkinManager` 已没有 `registerSkins(GameProfile, Type, ResourceLocation)`。皮肤缓存按 `CacheKey(profileId, packedTextures)` 键控、15 秒过期，且 `PlayerInfo` 把 `getOrLoad` 的 future **memoize 住**——任何 SkinManager 级钩子每个 PlayerInfo 只生效一次，无法做到"变身开/关"的动态切换与干净还原。
- **选定方案**：`@Inject(HEAD, cancellable)` 拦 `PlayerInfo#getSkin`——它是全客户端玩家皮肤查询单点（`AbstractClientPlayer#getSkin` 直接委托它；第三人称、第一人称手臂、旁观者渲染都走它）。覆盖表（UUID → 萨姆 `PlayerSkin`）命中即返回萨姆，未命中原样放行。还原 = 移除表项，**不污染皮肤缓存、披风/鞘翅贴图无损**。
- **CustomSkinLoader 不能作为依赖**（用户提议后已调研）：对外没有可调用的换肤 API（CustomSkinAPI 仓库只是皮肤站接入规范），本体不发布 maven；其钩子是启动期自用 ASM 补丁（`SkinManagerPatch`）。但它的源码证实了 1.20.2+ 的钩子点（`SkinManager.getOrLoad`/CacheKey），与 spike 结论一致。
- **与 CustomSkinLoader 不冲突**：钩子点不同（我们拦 `PlayerInfo#getSkin` 的 HEAD，CSL 改 `SkinManager` 内部字节码，未触碰该方法）；未变身时我们零干预，CSL 行为完全不变；变身的 10 秒内绕过 CSL 返回萨姆属于预期；还原后 CSL 皮肤管线照常工作。
- **免 refmap mixin**：NeoForge 21.1 运行时即 Mojang 官方映射，mixin 按 mojmap 命名书写在 dev 与生产一致；FML 4.0.45 的 `ModFileParser` 支持 `neoforge.mods.toml` 里 `[[mixins]]` 声明配置文件。
- **皮肤模型类型**：两张皮肤经像素判定（手臂区 x=47 列不透明）均为 CLASSIC（宽臂）→ `PlayerSkin.Model.WIDE`。萨姆 `PlayerSkin` 构造为 `(maixr:textures/entity/memory_doll_sam.png, null, null, null, WIDE, false)`——变身的 10 秒内披风/鞘翅贴图暂不可见，属预期造型。
- **否决**：`RenderPlayerEvent.Pre` 取消重画——第一人称手臂、旁观者视角等渲染路径覆盖不全，还原复杂。

### D5. 变身状态服务端权威：玩家数据附件 + `PlayerTickEvent.Post` 到期回收

附件存 `{到期 gameTime, 飞行是否本能力授予}`，跨重登持久。到期时：仅当"飞行由本能力授予且玩家非创造"才收回 `mayfly`/`flying` 并 `onUpdateAbilities()`；随后清附件、广播还原包、清物品上的 `maixr_sam` 标记（扫描背包/头盔/Curios 头槽）。死亡时附件按 NeoForge 默认不复制即视为清除；飞行能力原版在重生时自动重建，无泄漏路径。备选（背包物品上存到期时间）被否：物品被移出背包后无法保证回收。

### D6. 速度 II 用原版药水效果（200 tick），冷却用 `player.getCooldowns()`

药水效果自带图标/粒子/同步，零额外代码；备选（属性修饰符）被否：要自己管理加/减与同步。冷却走 `getCooldowns().addCooldown(item, 600)`：原版冷却条 UI 免费获得，且原版在 `useItem` 路径自动拦截冷却中的物品——两个触发入口在服务端都先查冷却。

### D7. 网络：两条 payload，客户端逻辑全进 `com/maixr/client/`

- `MemoryDollHotkeyMsg`（C2S，空负载）：快捷键触发，`PacketDistributor.sendToServer`；服务端校验佩戴 + 冷却后走统一变身入口。
- `MemoryDollSkinMsg(UUID, boolean)`（S2C）：变身开/关广播，发给玩家本人 + `sendToPlayersTrackingEntity`（周围玩家）。
- 快捷键经 `RegisterKeyMappingsEvent` 注册（分类 `maixr`，默认 `R`），`ClientTickEvent` 里 `consumeClick()` 检测。

### D8. 声音：新建 `DeferredRegister<SoundEvent>`（项目首个），ogg 单声道 + `stream: true`，服务端广播随距离衰减

两条声音（2026-10-18 按用户要求定稿），均由服务端以**玩家实体为声源**播放（`level.playSound(null, player, …)` 实体重载 → `ClientboundSoundEntityPacket`，客户端把声音挂在玩家身上，**玩家飞行移动时声源跟随**）；音量 `SOUND_VOLUME = 3.0F`（原版机制 volume>1 按倍数扩大可闻半径，基线 16 格）→ **可闻距离约 48 格 = 3 倍**，近距离响度不变，随距离衰减（语音文件必须是单声道，立体声在原版不衰减）：

- **变身语音 `memory_doll_transform`**：`sounds.json` 一条事件挂 **4 个音频文件随机播放**（原版机制，Java 零改动）——终结技·激活「飞萤扑火，向死而生。」、终结技·施放「我为自我而战，直至一切…燃烧殆尽！」、完全燃烧战技·一「我将，点燃星海！」、完全燃烧战技·二「为战而生，为生而战！」（文件 `memory_doll_transform_1..4.ogg`）。
- **结束音效**：真身"大招结束"SFX 各渠道未能自动获取（BWIKI 已下架版权音频、fandom 主站被墙、网盘需登录），按用户指定**代码直接播放原版 `SoundEvents.END_PORTAL_FRAME_FILL`**（末地传送门框架镶嵌末影之眼）；后续拿到真身 SFX 时注册 `memory_doll_revert` 声音事件 + 单声道 ogg 即可无缝替换。

语音来源：B 站语音合集（BV1piv6ePEbN）下载音频 → 本地 faster-whisper 定位四条台词时间戳 → ffmpeg 切片转单声道 ogg。

## Risks / Trade-offs

- **[换肤 spike 结果不确定]** → 任务 0 就是 spike（runClient 30 分钟内验证覆盖生效、还原干净、重登不残留）；失败即切 D4 兜底 mixin 分支，任务量 +约半天，规格不受影响。
- **[语音文件网上获取不确定]** → 任务链路先用占位音频打通，真文件可后补替换；不阻塞其余任务。
- **[变身期掉线/死亡时序]** → 附件持久化 + tick 到期兜底 + 死亡自然清除（能力重建），已在 D5 覆盖；重登后若发现已过期，tick 内即回收并广播还原。
- **[变身中物品被移出背包（扔出/容器）]** → 到期回收只扫描玩家自己的槽位；被扔出的萨姆外观玩偶会保持萨姆贴图直至被再次拾取后由下一次到期清理。可接受的边缘残留，不影响物品身份。
- **[快捷键默认 R 与其它模组冲突]** → 可在控制设置改键（D7）；冲突无功能性损害。

## Migration Plan

纯新增，无数据迁移。回滚 = 移除新注册项与资源文件即可，不触碰现有玩偶数据。

## Open Questions

（无——spike 分支与语音文件获取都在任务内解决，不影响规格与任务分解。）
