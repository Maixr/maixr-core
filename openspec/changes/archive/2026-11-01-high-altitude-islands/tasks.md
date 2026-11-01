# Tasks

## 1. 高度常量与净空判定（核心重写）

- [x] 1.1 `SurfaceLakes` 高度常量替换：删除 `MAX_SURFACE_Y(100)`，新增
  `ISLAND_MIN_Y = 250`、`ISLAND_MAX_Y = 300`，以及程序岛净空盒高度
  `PROGRAM_ISLAND_HEIGHT = 14`。类 javadoc 补充"伴生岛全部在 y ≥ 250 高空"
  与常量含义。
  验证：`grep -n "MAX_SURFACE_Y" src/main/java/com/maixr/SurfaceLakes.java`
  无残留；新增三个常量存在且值符合

- [x] 1.2 `run()` 的基准高度改为区间抽取：删除"读邻列 `WORLD_SURFACE` 定 baseY"
  的那段循环，改为 `baseY = ISLAND_MIN_Y + rnd.nextInt(ISLAND_MAX_Y - ISLAND_MIN_Y + 1)`，
  继续用区块种子派生的 `rnd`（保持确定性）。
  验证：同 seed 两次生成同区块，baseY 一致

- [x] 1.3 空判定改为**方块盒净空**：新增 `isClearVolume(level, chunk, x, z, y, w, d, h)`
  —— 对水平 `[x, x+w) × [z, z+d)`、竖直 `[y, y+h)` 逐块 `isEmptyBlock`，
  任一块非空气即返回 false。`run()` 的候选位置筛选改用该函数
  （用 `baseY` 与对应高度盒），替换原来的 `getHeight(WORLD_SURFACE) > minY + 1`
  判定。净空判定 MUST 只读本区块。
  验证：在已知有天空结构/矿脉的高空区块跑一次，该区块跳过放置；
  正常区块照常放置

- [x] 1.4 NBT 岛与程序岛分别传正确高度盒：`placeNbt` 用
  `template.getSize().getY()` 作为净空盒高度；沙漠岛/水族馆用
  `PROGRAM_ISLAND_HEIGHT`。放置调用的 `y` 语义统一为"岛底基准"。
  验证：六型岛放置后，其上方 1 格仍为空气（无被地形/结构截断）

- [x] 1.5 **频率降为原来的 1/4**：新增 `ISLAND_CHUNK_DENOMINATOR = 4`，
  `run()` 开头做区块坐标确定性哈希门控（`floorMod(gateHash, 4) != 0` 直接
  `return`），平均每 4 区块约 1 座。门控判据与类型分派判据
  （`floorMod(x*7+z*11, 100)`）必须互相独立。
  验证：离线模拟 200×200 区块，门控命中率 ≈ 25%、相邻双命中 ≈ 6.25%
  （实测 25.1% / 6.4%，无成片聚集）

## 2. 文档与 spec

- [x] 2.1 `_docs/破碎世界.md` 伴生岛小节改写：`MAX_SURFACE_Y(100)` → 
  `ISLAND_MIN_Y=250` / `ISLAND_MAX_Y=300`；写清新口径（高空净空、不叠结构/矿脉）、
  取水点搬到高空的玩法影响，以及"`FragmentRoadNetwork`/`FragmentBridges` 的
  `MAX_SURFACE_Y=180` 天然排除高空岛"这条耦合说明。
  验证：文档常量值与代码逐一对得上

- [x] 2.2 `README.md` 通行与伴生岛一节同步：把小岛描述从"贴地补给"改为
  "y≥250 高空悬浮岛"。
  验证：README 中不再有"贴地/地表伴生岛"表述

## 3. 构建与运行时验收

- [x] 3.1 `./gradlew build` 通过，无新增编译错误。
  验证：构建输出 `BUILD SUCCESSFUL`

- [x] 3.2 验收（自动化，region 方块解码）：破碎世界 forceload 生成 168 区块，
  ①地面高度无伴生岛 —— 基准高度全部 ≥ 250（最低 251）；
  ②六型岛均出现（沙漠 cactus/dead_bush、水族馆 glass/lapis/kelp/coral、
  水岛 sugar_cane）；③水族馆悬空无水外漏（无 water 出现在岛体盒外）；
  ④岛与天空结构/矿脉无镶嵌（净空检查兜底，失败即跳过）；
  ⑤密度实测 **14.3%**（24/168），门控命中 25.3%、净空成功 55%
  —— 用户裁定接受该值并改写 spec 为"约 10%~15%"。
  验证：`_tools/verify/island_check.py` + `island_density.py` + `gate_vs_actual.py`

- [x] 3.3 `/maixr bench` 复测，确认 3D 净空扫描未造成明显回退。
  本轮重采（含 C2ME）：破碎 7.10s / 原版 4.24s = 1.67×，用户确认通过。
  读数入档 `_docs/生成性能测量.md`「五之二」

## Workflow follow-up

- 归档前 `openspec validate high-altitude-islands` 通过
- 实现完成、用户验收后走 `/opsx:archive`（spec delta 合入
  `broken-world-type`，提交落在 `feat/broken-gen-rewrite` 分支）
