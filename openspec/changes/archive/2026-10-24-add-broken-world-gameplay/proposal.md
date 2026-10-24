# Proposal

## Why

远程合入的「破碎世界玩法补全」提交（连桥 / 道路网 / 伴生岛 / 取水湖 / 晶洞拦截与清理）已在
`src/main/java/com/maixr/` 落地，但没有任何 spec 覆盖；同一批提交还把破碎预设的下界/末地改回
原版噪声生成、并把碎屑子层改为平滑侵蚀，这两点与现行 `broken-world-type` 规范的
「下界碎裂应用」「末地碎裂应用」需求**直接矛盾**。按项目流程（改行为前先改 spec），必须把这批
已落地/待收尾的行为补进规范，同时收尾遗留的实现缺口（道路网未接线、配置默认值注释互相矛盾）。

## What Changes

- **新增：裂缝桥**（`CrackBridges`，世界生成期 dressing 链内、只用本区块高度图、不读邻区块、
  不排队——2~12 格裂缝上架 3 格宽石砖桥面，每区块最多 6 座、1/2 概率、只连 y≤100 的地表）。
- **新增：碎片道路网 v5**（`FragmentRoadNetwork`，学 RoadWeaver：相对邻域图（RNG）选边 +
  A* 代价寻路 + 样条平滑 + 坡度上限 1.0 + 中段拱高 + 沙岩桥面中线铁轨 + `SavedData`
  持久化去重 + 服务端 tick 重试队列）。★ 代码已完整但**当前无任何调用点**——本变更将其
  接线为「连桥配置」启用时的实现（替换旧版 `FragmentBridges`）。
- **新增：伴生岛**（`SurfaceLakes`，世界生成期单区块内放置：40% 甘蔗水岛 `skyblock_water` /
  15% 梦晶岛 `skyblock_dream` / 12% 末地小岛 `skyblock_end` / 11% 下界小岛 `skyblock_nether` /
  11% 沙漠沙丘岛 / 11% 球形玻璃水族馆；岛面 y≤100，每区块最多 1 座）——补上碎裂场挖掉
  地表水之后的**取水点**。
- **新增：悬空晶洞生成拦截**（`AmethystGeodeFeatureMixin` 打 `Feature.place` HEAD：破碎世界
  生成原点列下方 9 格全空气 → 直接取消紫水晶洞生成，而非生成后删除）。
- **新增：悬空残余清理**：生成期 `VoidDebrisCleanup`（悬空岩浆单块判定删除；悬空晶洞按 BFS
  整坨判定，任一块有非晶洞支撑则整坨保留、跨区块边界的坨不动）；加载期 `VoidDebrisCleanup` +
  `GeodeBlobCleanup`（上限 6 万块的整坨判定，兼容雷霆大晶洞）由新配置
  `geodeCleanupEnabled` 控制。
- **新增配置项**：`bridgesEnabled`（连桥系统，默认 **false**）、`geodeCleanupEnabled`
  （加载期清理，默认 **false**），以及既有的 `uniformBand` 语义不变。
- **BREAKING：破碎预设的下界/末地改回原版生成**——`world_preset/broken.json` 的
  下界 `maixr:broken_nether` → `minecraft:nether`、末地 `maixr:broken_end` → `minecraft:end`；
  规范中的「下界碎裂应用」「末地碎裂应用」需求随之删除。旧存档不受影响（世界维度设置在
  创建时固化），仅新建的破碎世界下界/末地为原版形态。
- **碎屑子层平滑侵蚀**（`PlateFieldLogic.fineSurvival`：碎屑子层由 8×8 整格判定改为抖动
  Voronoi 平滑过渡，消除玩家实测的「1/4 区块轴对齐正方形空洞」）。

## Capabilities

### New Capabilities

（无——全部归属既有能力 `broken-world-type`。）

### Modified Capabilities

- `broken-world-type`：
  - **ADDED** 裂缝桥、碎片道路网、伴生岛、悬空晶洞生成拦截、悬空残余清理、
    碎屑子层平滑侵蚀六组需求；
  - **REMOVED** 「下界碎裂应用」「末地碎裂应用」（破碎预设下界/末地回归原版生成）；
  - **MODIFIED** 「统一碎裂场」「垂直形变」中涉及末地主岛静滞核心的表述
    （碎裂场现在 ONLY 作用于破碎预设的主世界）。

## Impact

- **代码**：`CrackBridges` / `FragmentRoadNetwork` / `FragmentBridges`（退役） /
  `SurfaceLakes` / `VoidDebrisCleanup` / `GeodeBlobCleanup` / `MaixrBridgeEvents` /
  `AmethystGeodeFeatureMixin` / `PlateFieldLogic` / `MaixrDressingPass`（链尾追加三个 pass） /
  `MaixrMod`（注册 ChunkLoad 监听与 mixin 配置）/ `MaixrConfig`（两个新开关）。
- **资源**：`maixr.common.mixins.json`（新增，common mixin，`required=false` +
  `defaultRequire=0`）、`neoforge.mods.toml`（`[[mixins]]` 声明）、
  `data/maixr/tags/block/void_geode_blocks.json`（晶洞族 8 方块标签）、
  `data/maixr/structure/skyblock_{water,dream,end,nether}.nbt`（伴生岛结构模板）、
  `data/maixr/worldgen/world_preset/broken.json`（下界/末地设置回退）。
- **遗留缺口（本变更收尾）**：`FragmentRoadNetwork` 无调用点；`MaixrConfig` /
  `MaixrBridgeEvents` 中「默认 true / 默认关闭」的注释与实际默认值（均 false）矛盾；
  `broken_nether.json` / `broken_end.json` 沦为无人引用的死数据。
- **性能**：加载期清理与建桥是区块加载主线程开销，全部默认关闭；裂缝桥/伴生岛在
  生成期执行、纯本区块访问，无跨区块递归加载风险（跨区块读取 = 世界卡死，见设计文档）。
- **文档同步**：`_docs/破碎世界.md`、README 功能概览、`openspec/config.yaml` 上下文
  （下界/末地碎裂表述与「已移除系统」清单）需随本变更更新。
