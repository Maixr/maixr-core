# Tasks

## 1. Spike：自定义 DensityFunction 注册链路（最高风险先行）

- [x] 1.1 注册一个最小自定义 DF 类型（`MapCodec` 进 vanilla 密度函数类型注册表，常量返回值），确认 `MaixrMod` 构造器注册路径可用；验证方式：runClient 启动无注册错误
- [x] 1.2 写一份最小 `noise_settings` JSON 引用该 DF 类型（抄 vanilla 一份改 `final_density`），临时指向测试维度/ preset；验证方式：创建测试世界进入后地形按 DF 生效、创建世界页面不卡（ClassCastException 症状消失即 MapCodec 契约满足）
- [x] 1.3 回填 spike 结论到 design.md（若注册链路不可行，停下回到变更流程走降级路线，不得擅自改 spec）

## 2. 碎裂场核心 DF 与调参工具

- [x] 2.1 实现 `maixr:fracture`（四带静滞渐变 x 脊状裂缝噪声 x 板块存活 + 震中乘压）与 `maixr:plate_field`（2D Voronoi + cell 哈希状态 + 碎屑子层），常量集中在类顶部（`CALM_RADIUS=1024`/`CRACK_END=3072`/`FRAG_END=6144` 等）；验证方式：DF 单独采样若干坐标的输出符合带间单调性（平静区≈0、碎屑带高位）
- [x] 2.2 写体素模拟脚本（或临时渲染命令）输出裂缝/板块形态俯视与剖面图，按图定阈值（吸取浮岩圆度阈值离散跳变的教训）；验证方式：模拟图确认裂缝各向同性、有分叉宽窄变化、平静区干净，形态经用户确认
- [x] 2.3 实现 `maixr:plate_displace`（包装子 DF 按板块漂移量偏移采样；漂移幅度按带 `0/5/15~25/30~40`，`TILT` 参数默认 0）；验证方式：同坐标重复采样结果一致（确定性），跨区块边界无缝（邻区块接缝处连续）

## 3. 主世界接入

- [x] 3.1 新增 `data/maixr/worldgen/noise_settings/broken_overworld.json`（抄 vanilla overworld，`final_density` 外裹碎裂场），`world_preset/broken.json` 主世界 settings 切换；验证方式：runClient 新建破碎世界，四带观感成立（平静区 1024 格完好→碎屑带散落）
- [x] 3.2 `BrokenOverworldChunkGenerator` 删除旧虚空逻辑（`isVoided`/`roll01`/三常量/`fillFromNoise`·`buildSurface`·`applyCarvers` 跳过），保留 `createBiomes` maskSkyReferences 与 `createStructures` 天空结构挂钩；验证方式：编译通过 + 虚空区域结构完整落地（悬空遗迹）、要塞/末地传送门可获得
- [x] 3.3 主世界出生链路回归：开局空岛、`spawnRadius=0`、复活拉回在新生成器下照常；验证方式：runClient 出生上岛→自杀复活仍上岛

## 4. 震中与被炸基地地标

- [x] 4.1 震中散布（3~5 随机种子点 + 1 个确定性方位地标，间距 ≥1024 格，不落入任何静滞区），碎裂掩码局部乘压；验证方式：dev 世界用指令定位各震中，观察加压碎裂与间距
- [x] 4.2 地标常量（方位角、2048~4096 距离）进代码常量并在 `_docs/破碎世界.md` 记录；验证方式：文档值与代码一致（文档规范）

## 5. 材质 dressing（幽匿替换 + 天空石结晶）

- [x] 5.1 `applyBiomeDecoration` 链尾确定性 dressing pass：震中区域按距离衰减置换表层为幽匿块/幽匿脉络，碎屑带浮板按概率结晶天空石；验证方式：runClient 观察震中侵蚀渐变、远端浮板天空石
- [x] 5.2 断言/检查世界生成不放置任何潜声装置（`sculk_sensor`/`sculk_shrieker`/`sculk_catalyst`）；验证方式：对 dressing pass 代码路径的方块集合断言 + 世界内抽查
- [x] 5.3 确认断面裸露地层零成本成立（原版地表规则自动暴露土/石/深板岩剖面，无需代码）；验证方式：碎裂边缘观察剖面

## 6. 天空结构密度曲线

- [x] 6.1 `SkyStructurePlacer` 概率按四带参数化（平静 0 / 裂缝·碎块 1/100 / 碎屑 ~1/40），`CHANCE_DENOMINATOR` 语义改为"带相关"；验证方式：dev 世界统计平静区 0 次、碎屑带频次明显高于裂缝带
- [x] 6.2 确认 `maskSkyReferences` 在新 settings 下仍阻断 Beardifier 浮空薄板；验证方式：高空白 sky 无两三格浮板

## 7. 下界与末地接入

- [x] 7.1 `noise_settings/broken_nether.json`：锚点 (0,0)、半径 1/8 折算（128/384/768）、天花板倒挂碎块；`broken.json` 下界指向；验证方式：runClient 下界四带 + 天花板倒挂碎块、出生传送门落点在平静区
- [x] 7.2 `noise_settings/broken_end.json`：主岛静滞（~768 格，龙战/出口传送门/黑曜石柱完好）、外岛碎块~碎屑带；`broken.json` 末地指向；验证方式：runClient 主岛完整可龙战、外岛碎裂、折跃门可用
- [x] 7.3 下界/末地巡检回归：末影城市/要塞类结构完整、无 Beardifier 薄板、性能无异常回退；验证方式：runClient 三维度跑图 + 观察 MSPT

## 8. 清理与文档

- [x] 8.1 删除全部死代码（旧虚空判定、无用常量），`MaixrChunkGenerators` 注册清理；验证方式：编译零警告相关引用、全局搜索无 `isVoided` 残留
- [x] 8.2 重写 `_docs/破碎世界.md` 生成章节（碎裂场模型/常量表/震中/三维度；旧渐变虚空章节标注历史），常量值与代码逐一对账；验证方式：文档常量与代码 grep 对账一致（AGENTS.md 硬规则）
- [x] 8.3 README 功能概览同步新破碎世界描述；调试 info 日志按项目惯例标注"已注释"保留；验证方式：README 与 `_docs` 陈述一致
- [x] 8.4 `openspec validate --specs --strict` 通过；验证方式：命令退出码 0

## 9. 追加（用户指令）：移除初始空岛系统

- [x] 9.1 delta spec 增加 REMOVED Requirement「开局空岛与出生点」（理由：平静区地形完好 + 天空结构为 0，原版出生点逻辑已安全；Migration：出生/复活回原版逻辑，老存档已放置的岛方块保留）
- [x] 9.2 删除 `BrokenIslandHandler` / `BrokenIslandData` / `maixr:broken_skyblock` 结构文件 / `MaixrMod` 两个事件监听；验证方式：编译通过 + runServer 全新破碎世界启动无异常、无空岛放置日志
- [x] 9.3 文档同步：`_docs/破碎世界.md`（开局空岛章节改历史注记、排查清单/代码清单清理）、README、`openspec/config.yaml`；验证方式：grep 无"已移除"注记之外的空岛实现性描述

## 10. 追加（用户指令）：默认世界类型与浮岩频率

- [x] 10.1 「破碎」设为创建世界界面的默认选中类型 —— client mixin（WorldCreationUiStateMixin）在新建默认世界时把初始项换成 maixr:broken；**不覆盖 vanilla normal preset**（保持"普通"= 原版地形，避免依赖 normal 的兼容 bug）；验证方式：mixin 注入冒烟（defaultRequire=1，启动无注入失败即生效）+ 用户实机确认
- [x] 10.2 空域浮岩整体生成概率减半：rarity_filter chance 8 → 16（矿石占比 35:65 保持不变）；验证方式：配置加载冒烟

## 11. 追加（用户指令）：配置文件

- [x] 11.1 新增 `config/maixr-common.toml`（COMMON，MaixrConfig）：`dollSoundEnabled`（玩偶**变身语音**开关，默认 false=禁用；其它玩偶音效不受影响）、`uniformBand`（全图统一碎裂水平，0~3，默认 2.0，改动只影响未生成区块）；验证方式：构建通过 + 用户实机改配置确认
- [x] 11.2 接线：`MaixrMod` 构造器 `registerConfig(COMMON)`；`FractureDensityFunction.bandProgress` 运行时读 `MaixrConfig.uniformBand()`（未加载回退默认值）；变身语音播放点按配置守卫

## Workflow follow-up

- 实现经 `/opsx:apply` 进行；全部任务勾选后按项目评审要求 `/opsx:archive` 归档。
- 归档前在 runClient 用真实种子完整走一遍四带旅程（步行→跨步→搭桥→飞行）做最终观感验收。
