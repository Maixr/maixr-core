# Proposal

## Why

破碎世界经历多轮重写（v1 渐变虚空 → v2 统一碎裂场 → 下界/末地回退原版 →
出生点接管 → 通行系统补全），`openspec/config.yaml` 的项目上下文与
`_docs/`、README 里残留了大量**只对旧版本成立**的表述——「渐变虚空 20%→95%」
「地形阶段跳过虚空区块」「三维度全上」「末地主岛静滞」「平静区地表出生」等。
`config.yaml` 的 context 是后续 OpenSpec 会话直接读入的提示上下文，错误表述会
持续误导开发与 spec 编写；文档表述与已同步主 spec / 代码常量矛盾则违反项目的
文档一致性硬规则。本变更做一次全面定点清理，不改任何行为。

## What Changes

- **`openspec/config.yaml`（上下文，2 处）**：
  - 「渐变虚空主世界…出生点 20% 虚空 → 50 区块≈800 格处 95%」→ 改为统一碎裂场口径
    （全图 `UNIFORM_BAND=2.0`，无平静区）；
  - 「地形阶段（fillFromNoise/buildSurface/applyCarvers）跳过虚空区块」→ 删除
    ——v2 重写后跳过逻辑已整体移除（生成器不覆写三阶段，纯密度管线碎裂），
    主 spec 已明文 MUST NOT 存在。
- **`_docs/破碎世界.md`（约 14 处定点修正，全部在既有章节内）**：
  顶部一句话（末地静滞 / 出生走原版）、三种世界类型表（三维度全上 / 平静区出生）、
  统一带进度小节（末地主岛 calm 为 0）、`CALM_RADIUS` 常量行（末地 JSON 覆盖 300）、
  「三维度应用」表（下界/末地两行——对应 settings 文件已删除）、
  天空结构概率段（默认全图 1/100；band≥2.5→1/40 与平静区 0 为默认配置下不可达分支）、
  「三、世界类型列表」末段（broken_nether/broken_end 指向）、
  「五」独立维度出生表述、相关代码表两行（「四带渐变」措辞）、
  排查清单出生点行（改指 `BrokenSpawnSearch`）；
  并补一段「下界/末地已回退原版」的迁移说明（含旧存档不受影响）。
- **`README.md`（2 处）**：
  - 天空结构小节的「平静区 0 座…碎屑带约每 40 区块一座」→ 改为默认口径
    （1/100 全图；按带分支仅在 `uniformBand` ≥2.5 时生效）；
  - 「项目规范」一节的 specs 清单缺 `memory-tomorrow-doll` 与
    `maixr-recipe-commands` → 补全为五个。
- **明确不动**：已标注「⚠️ 历史注记」的章节（渐变虚空、开局空岛、伊甸工程、
  旧实现备份说明）原样保留；`_docs/玩偶接入AE2.md` 经排查无破碎世界相关过时
  表述，不动；`broken.json` 头部注释已在 add-broken-world-gameplay 归档时修正，
  不动；代码内注释（`SkyStructurePlacer` 的 band 分支 javadoc 等）与代码一致，不动。
- **不改任何代码与行为**（纯文档 / OpenSpec 上下文清理，`skip_specs: true`）。

## Capabilities

### New Capabilities

（无——纯文档与 OpenSpec 上下文清理，无 spec 级行为变化，
`.openspec.yaml` 已设 `skip_specs: true`。）

### Modified Capabilities

（无。）

## Impact

- **文件**：`openspec/config.yaml`、`_docs/破碎世界.md`（该文件含未提交的
  本地新增，只做定点 Edit、绝不整文件重写）、`README.md`。
- **事实源**：全部修正以已同步的主 specs（`openspec/specs/`，本会话归档时
  `validate --specs --strict` 5/5 通过）与当前代码常量为准；两处「文档 vs 代码」
  疑点已先核实——天空结构概率代码保留 band 分支但默认不可达（`SkyStructurePlacer`
  `band>=2.5 ? 40 : 100`，`uniformBand` 默认 2.0）；地形阶段跳过已确认删除
  （`BrokenOverworldChunkGenerator` javadoc + 无覆写）。
- **风险**：极低——纯文本修正；唯一注意点是 `破碎世界.md` 的未提交本地改动
  不能丢失（定点编辑 + 事后 `git diff` 核对）。
- **验证**：关键词 grep 回归（过时词只允许出现在历史注记内）+ 抽查常量与代码一致。
