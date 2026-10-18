# Proposal

## Why

整包 Project Negentropy 需要新的收藏向玩偶"致回忆中的明日"（崩坏：星穹铁道流萤/萨姆主题）。它复用现有玩偶的全部基座（放置、旋转、佩戴、AE2 接入），并首次引入"玩偶主动触发玩家增益与换装"的玩法（短时创造飞行 + 玩家换肤 + 语音播报），为后续同类联动玩偶建立可复用的能力规范。

## What Changes

- 新增方块/物品 `maixr:memory_tomorrow_doll`（玩偶"致回忆中的明日"），初始流萤外观，支持流萤/萨姆双形态：
  - 放置态：空手右键切换旋转动画（与现有玩偶一致）；蹲下右键切换流萤/萨姆外观（仅视觉，挖掘后仍掉落流萤物品）
  - 手持右键（不蹲下）触发"变身"：娃娃与玩家皮肤变萨姆、**随机播放一条变身语音**（终结技·激活 / 终结技·施放 / 完全燃烧战技·一 / 完全燃烧战技·二 共四条），获得 200 tick（10 秒）创造飞行 + 速度 II；冷却 600 tick（30 秒，原版物品冷却条）；到期自动还原为流萤并**播放变身结束音效**（真身 SFX 获取前以末地传送门框架放上末影之眼的音效代替）
  - 蹲下 + 手持右键 = 佩戴（Curios 头饰槽优先，其次原版头盔槽，与现有玩偶一致）
  - 佩戴时按下注册的快捷键（默认 R，可在控制设置改键）触发变身
  - 玩家换肤状态同步给追踪该玩家的所有客户端，周围玩家同样看到萨姆
- 新增方块实体（继承 `MaixrDollBlockEntity`）：完整继承 ME 网络接入（占 1 频道、显示在线、每 1200 tick 产出 1 个自己、满仓 100 tick 重试）
- 新增两条网络包：快捷键触发（客户端 → 服务端）、换肤状态广播（服务端 → 自身与周围玩家）
- 新增项目首个声音注册（`DeferredRegister<SoundEvent>` + `sounds.json`）：变身语音为四条随机（崩铁流萤终结技×2 + 完全燃烧战技×2，单声道 ogg，服务端广播随距离衰减），结束音效暂由代码直接播放原版末地传送门框架音效（拿到真身 SFX 后注册替换）；两条声音均以玩家实体为声源（随移动）、可闻距离 3 倍（约 48 格）
- 新增资源：流萤/萨姆两张 64×64 皮肤同时用作玩家换肤贴图与玩偶方块贴图（玩偶方块模型 UV 为 0–16 归一化空间，直接复用现有几何）
- 文档同步：README 功能清单、`_docs/玩偶接入AE2.md` 补充第三只玩偶

## Capabilities

### New Capabilities

- `memory-tomorrow-doll`: 玩偶"致回忆中的明日"的完整行为——双形态外观与放置态切换、变身触发（手持右键 / 佩戴快捷键）、变身效果包（换肤 + 语音 + 创造飞行 + 速度加成）、冷却与到期自动还原、佩戴与快捷键、放置态交互与 AE2 产出继承。

### Modified Capabilities

（无——`doll-ae2-network` 的既有需求对新玩偶自然成立：新方块实体继承 `MaixrDollBlockEntity` 即满足全部 ME 网络要求，该能力的需求文本不变）

## Impact

- **代码**：`MaixrMod`（方块/物品/创造标签注册、`isDollItem` 纳入新物品）、`MaixrBlockEntities`（新 BE 类型）、`MaixrNetwork`（+2 payload）、新文件 `MemoryTomorrowDollBlock` / `MemoryTomorrowDollBlockEntity` / `MemoryTomorrowDollItem`、新 `client/` 类（按键绑定 + 玩家换肤管理）、新声音注册类
- **资源**：blockstate（8 变体 = 4 朝向 × 2 皮肤）、2 个方块模型（复用现有几何、分别指流萤/萨姆贴图）、2 个物品模型 + 物品模型谓词、`textures/entity/` 换肤贴图、`sounds/` ogg、`sounds.json`、`lang/zh_cn.json` 与 `en_us.json`
- **依赖**：无新增；Curios 仍为可选依赖且调用带 `ModList.isLoaded()` 守卫
- **风险**：玩家换肤机制经 spike 定案为 mixin 拦 `PlayerInfo#getSkin`（1.21.1 无 `registerSkins` API；与 CustomSkinLoader 钩子点不同、互不冲突），需给项目引入 mixin 基础设施，详见 design.md
