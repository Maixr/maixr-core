# Tasks

> 全程遵守 design D2：只做定点 Edit（唯一短语锚定），禁止整文件重写；
> `_docs/破碎世界.md` 含未提交本地新增，零丢失。

## 1. openspec/config.yaml（上下文）

- [x] 1.1 「渐变虚空主世界 BrokenOverworldChunkGenerator（出生点 20% 虚空 → 50 区块≈800 格处 95%，确定性哈希）；」→ 改为统一碎裂场口径：全图统一碎裂水平（`UNIFORM_BAND=2.0`，无平静区、无渐变虚空；同种子同坐标确定性）；验证方式：文档走查
- [x] 1.2 「地形阶段（fillFromNoise/buildSurface/applyCarvers）跳过虚空区块，结构阶段永不跳过；」→ 改为「碎裂在 vanilla 密度管线内求值（不覆写三阶段；旧跳过逻辑已随 v2 重写删除，主 spec 明文 MUST NOT 存在）」；验证方式：文档走查
- [x] 1.3 兜底：通读 config.yaml context 全文，发现清单外同类过时表述按 design D1 口径一并修正，并在本任务勾选说明里列出；验证方式：全文件通读（结论：其余条目均为现行口径，无清单外修正）

## 2. _docs/破碎世界.md（定点修正，锚定短语定位）

- [x] 2.1 顶部一句话：「唯一静滞保留在末地主岛核心（~225 格，保护龙战）。」+「出生/复活走原版逻辑。」→「碎裂场仅作用于主世界（下界/末地为原版生成）。出生点由 `BrokenSpawnSearch` 接管（见「一点八」）。」；验证方式：文档走查
- [x] 2.2 「三种世界类型」表破碎行：「逐方块碎裂场（三维度全上）」→「逐方块碎裂场（仅主世界，下界/末地为原版）」；「平静区地表（原版出生点逻辑）」→「`BrokenSpawnSearch` 接管（见「一点八」）」；验证方式：文档走查
- [x] 2.3 「统一带进度」段：「仅末地主岛核心（`calm_radius` 参数，~225 格）为 0」→ 删除该从句（末地不再应用碎裂场，`CALM_RADIUS=0`）；验证方式：文档走查
- [x] 2.4 `CALM_RADIUS` 常量行含义列：「末地 JSON 覆盖 300」→「（历史：旧 `broken_end.json` 曾覆盖 300；该文件已删除，末地为原版生成）」；验证方式：文档走查
- [x] 2.5 「三维度应用」节：标题改「主世界应用」；主世界行「四带 300/3072/6144…副作用：平静区海盆干涸」→ 统一带进度口径（`aquifers_enabled=false` + `sea_level=-1000` 两点保留）；删除下界/末地两行，原位补迁移说明：`broken_nether.json` / `broken_end.json` / `noise/nether_shard.json` 已删除，下界/末地 settings 指向 `minecraft:nether` / `minecraft:end`，旧存档不受影响（维度设置创建时固化）；验证方式：文档走查
- [x] 2.6 一点五概率段：「★ v2.0 起概率随四带变化…平静区 0…碎屑带 1/40」→ 按 design D4 口径改写：默认 `uniformBand=2.0` 下全图 1/100；代码保留 `band<=0` 不抽签与 `band>=2.5 → 1/40`（`DEBRIS_CHANCE_DENOMINATOR`）分支，仅调参后可达；验证方式：文档走查
- [x] 2.7 「三、世界类型列表」末段：「主世界指向 `maixr:broken_overworld`（碎裂场全量四带），下界指向 `maixr:broken_nether`…末地指向 `maixr:broken_end`（主岛静滞 ~768 格…）」→「主世界指向 `maixr:broken_overworld`（统一碎裂场）；下界/末地指向原版 `minecraft:nether` / `minecraft:end`（旧 broken_nether/broken_end 已删除）」；验证方式：文档走查
- [x] 2.8 「五、独立维度」：「玩家不会自动进去 —— 「破碎」世界类型的出生点在主世界平静区地表」→「……出生点由 `BrokenSpawnSearch` 接管（见「一点八」）」；验证方式：文档走查
- [x] 2.9 「七、相关代码」表：`FractureDensityFunction` 行「四带渐变 × 脊状裂缝」→「统一带进度 × 脊状裂缝」；`SkyStructurePlacer` 行「四带密度曲线，y≥201」→「带进度分档抽签（默认全图 1/100），y≥201」；验证方式：文档走查
- [x] 2.10 「八、排查清单」出生点行：「出生点走原版地表逻辑（平静区内）；确认…平静区 0~1024 格地形完好…`spawnRadius` 未被别的模组改大」→「出生点由 `BrokenSpawnSearch` 接管：确认日志有 `[破碎] 出生点选定`（掩码预筛 + 真实区块校验，见「一点八」）；无日志则确认世界类型确实是「破碎」且为新建世界（仅新世界搜索）」；验证方式：文档走查
- [x] 2.11 收尾核对：`git diff --stat -- _docs/破碎世界.md` 行数变化仅来自本清单各处（未提交的出生点章节等本地新增零丢失）；验证方式：git diff 审阅（+202/−21，删除全部来自定点替换；一点八/一点九章节抽查完好）

## 3. README.md（2 处）

- [x] 3.1 「2. 破碎结构」小节概率句：「平静区 **0** 座（静滞场内天空干净）、裂缝带/碎块带每 100 区块一座、碎屑带约每 40 区块一座」→「默认全图每 100 区块一座（1/100）；代码保留按带加密分支（`uniformBand` 调至 ≥2.5 时碎屑水平 1/40）」；验证方式：文档走查
- [x] 3.2 「项目规范」specs 清单「`broken-world-type` / `doll-ae2-network` / `pigmee-duplication`」→ 补全为五个（加 `memory-tomorrow-doll` / `maixr-recipe-commands`）；验证方式：文档走查

## 4. 验证

- [x] 4.1 关键词 grep 回归：「平静区」「三维度」「倒挂」「broken_nether」「broken_end」「渐变」在三个文件中的剩余命中仅出现在「⚠️ 历史注记」「（历史：…）」或迁移说明内；验证方式：grep 输出逐条审阅（额外补标 1 处：CRACK_THRESHOLD 曲线说明的「平静区外」改为「历史调参：渐变时代要求…」）
- [x] 4.2 `openspec validate --specs --strict` 仍 5/5 通过（确认 specs 未被误触）；验证方式：命令退出码 0
- [x] 4.3 常量抽查：文档引用值与代码一致（`UNIFORM_BAND=2.0`、`CHANCE_DENOMINATOR=100`、`DEBRIS_CHANCE_DENOMINATOR=40`、`CALM_RADIUS=0.0`、`MIN_Y=201`、浮岩 1/8 与 35%/65%）；验证方式：逐个 grep 代码常量比对
