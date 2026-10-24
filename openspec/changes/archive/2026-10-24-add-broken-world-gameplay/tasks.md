# Tasks

> 说明：裂缝桥 / 伴生岛 / 晶洞三层治理 / 碎屑子层平滑 / 下界末地回退的主体实现
> 已随远程提交落地（`CrackBridges` / `SurfaceLakes` / `VoidDebrisCleanup` /
> `GeodeBlobCleanup` / `AmethystGeodeFeatureMixin` / `PlateFieldLogic.fineSurvival` /
> `world_preset/broken.json`），本清单只覆盖**接线、收尾与验证**。

## 1. 碎片道路网接线（唯一新增代码行为）

- [x] 1.1 `MaixrBridgeEvents.onChunkLoad` 的 `bridgesEnabled` 分支改调
  `FragmentRoadNetwork.run(level, chunk)`（替换 `FragmentBridges.run`）；
  `FragmentBridges` 源码保留、javadoc 注明退役为历史参照；验证方式：编译通过
- [x] 1.2 `MaixrMod` 构造器注册 `ServerTickEvent.Post` 监听：对每个 `ServerLevel`
  调 `FragmentRoadNetwork.tick(level)`（待重试队列为空时零开销；破碎世界判定由
  队列按维度隔离天然保证）；验证方式：编译通过 + runServer 日志
- [x] 1.3 确认道路网全程不强制加载区块（`getChunkNow` 探测、NO_END 永久失败
  不进重试队列、每 tick ≤4 碎片）；验证方式：代码走查 + runServer 无
  「加载递归/世界卡 100%」复现

## 2. 配置与注释修正

- [x] 2.1 `MaixrConfig.geodeCleanupEnabled()` javadoc「默认 true」改为
  「默认 false」（与 `define(...)` 实际默认一致）；验证方式：代码走查
- [x] 2.2 `MaixrBridgeEvents` 注释「默认：连桥关闭、晶洞清理开启」改为
  「两个开关默认均关闭」；验证方式：代码走查

## 3. 死数据清理（下界/末地回退收尾）

- [x] 3.1 删除 `data/maixr/worldgen/noise_settings/broken_nether.json`、
  `noise_settings/broken_end.json`、`noise/nether_shard.json`（预设已回退
  原版 settings，三者成死数据；git 历史可找回）；验证方式：全仓 grep 无引用 +
  runServer 数据包加载无报错
- [x] 3.2 修正 `world_preset/broken.json` 头部 `_comment`（仍宣称
  「下界/末地同用碎裂场」→ 改为「下界/末地为原版生成」）；验证方式：代码走查

## 4. 文档同步

- [x] 4.1 `_docs/破碎世界.md` 补「通行与伴生岛」章节：裂缝桥参数（2~12 格/6 座/
  1/2 概率/y≤100）、道路网机制（RNG 0.8 容忍度 / A* 4 格网格 / 坡度 1.0 / 拱高 3 /
  SavedData `maixr_road_network` / tick 限额 4）、伴生岛六型概率、晶洞三层治理、
  两个配置开关，以及 D1/D4/D5 踩坑记录（跨区块禁忌 / Mixin #576 无字段 /
  整坨判定语义）；验证方式：文档常量与代码常量一致
- [x] 4.2 README 功能概览补一行「碎片道路网 / 裂缝桥 / 伴生岛」；验证方式：文档走查
- [x] 4.3 归档时更新 `openspec/config.yaml` 上下文（能力清单补通行系统；
  下界/末地表述改为原版生成）与主 spec `broken-world-type` 的 Purpose
  （删去「末地主岛静滞」表述）；验证方式：文档走查

## 5. 验证

- [x] 5.1 `./gradlew build` 编译通过；验证方式：构建退出码 0
- [ ] 5.2 新建破碎世界 runServer：取消 `SkyStructurePlacer` / 相关调试日志注释，
  确认新区块出现裂缝桥与伴生岛（含水/甘蔗）、悬空晶洞不再生成且无
  「Mixin apply failed」日志；验证方式：实机 + 日志
- [ ] 5.3 开启 `bridgesEnabled` 实机验证道路网：`C_BUILT > 0`、首桥 info 日志、
  桥面坡度可步行、中线铁轨连续；二次启动同一存档确认 SavedData 去重
  （不重复建桥）；验证方式：实机 + 日志采样
- [ ] 5.4 开启 `geodeCleanupEnabled` 加载旧区块：悬空岩浆/大悬空晶洞被整坨清理，
  无半删残骸；默认关闭时加载耗时无明显退化；验证方式：实机对比
- [ ] 5.5 回归：普通/空岛世界类型无任何新逻辑介入；下界/末地为原版形态
  （新建破碎世界进下界确认无碎裂与倒挂碎块）；验证方式：实机
- [x] 5.6 `openspec validate --specs --strict` 通过；验证方式：命令退出码 0
