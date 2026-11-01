# Tasks

## 1. 写入边距实证（spike，先行）

- [x] 1.1 读取依赖中 1.21.1 `ChunkStatus`/`ChunkPyramid`（及 `WorldGenRegion`
  构造）源码，确认 feature 阶段的写入边距（区块数）；结论与出处（类/字段名）
  写进 `SkyStoneClusterFeature` javadoc（实测：FEATURES = 中心区块 ±1 区块，
  越界静默丢块；v5 起钳制策略为 `canPlace` 精确范围 + `headingBudget` 朝向
  预算，无固定包络常数）。
  验证：javadoc 含边距数字与依据；所有写入点过统一闸门

## 2. 矿池映射（先于算法，矿种是算法输入）

- [x] 2.1 现 `RAW_ORE_IDS` 16 项逐条核实对应矿石方块 id 及其深板岩变体 id
  （开发客户端注册表查询或各模组 jar 内方块清单），产出完整映射表。
  验证：javadoc 记录 16 项逐一结果——有 ore id 的列出常规+深板岩变体两列，
  无对应矿石方块的（如 `enderio_evolution:crude_steel_block`）或缺深板岩
  变体的写明处理（剔除 / 回退常规）
- [x] 2.2 代码以「矿石 id → 权重」表重建矿池（替换 raw 池，权重：原版铁/铜
  20、金 8、钻石 3，模组常规 8、稀有 4；registry 过滤保留），恢复被注释的
  池子日志行。验证：runClient 启动日志打出"[空域浮岩] 矿脉矿池：N/M 种可用"

## 3. 裂隙生成算法（`placeOreBlob` 重写为 `placeFissureVein`）

- [x] 3.1 主径随机游走（v6 跨区块协作）：矿脉几何由「世界种子 + 4x4 区块单元
  坐标」确定性回放（`buildVein` 纯函数）；每区块扫描邻单元（包围盒粗筛），
  只放置落在本区块可写范围内的片段；长度 96~224 步、每步方向扰动 ±5~15°、
  整体摆动限幅 ±~20°（长脉不蜷线）、origin 居中（起点回退半长，包络 ≤
  `CELL_BBOX_HALF`）；
  截面为扁椭球（水平半径呼吸 2~4、垂直约其七成，每 4~8 步一段渐变，非纯平面）；
  首尾楔形收尖（楔长 baseR+2~3 步，半径逐级 0,0,1,2,…,baseR 不跳级，
  末端单块须尖）。
  验证：runClient 多观察几道——细长直脉、两端渐变尖灭、跨区块无"切断面"
- [x] 3.2 错台与挂屑：低概率（~5%/次）±1 格中轴横向错位；每 4~8 步在主径
  上下两侧随机附着 1~3 格小碎屑。验证：目视两壁参差、上缘不塌平（不全是
  侧挂）
- [x] 3.3 矿囊：主径取 6~14 锚点（v6 长脉加量），各放 r=1~3 小矿团——只替换已放的
  宿主方块（走 `inWindow` 纯算术窗口，**不能用 `canPlace`**：宿主已非空气，
  `isEmptyBlock` 恒 false 会让矿石永不生成 —— v6.3 修的 bug）；
  **每囊独立抽矿种**（按 2.2 权重表，同一条石脉可混生多种矿石），
  矿方块按宿主族群取变体（deepslate 脉刷深板岩矿石，缺变体回退常规）。
  验证：采掘矿囊得矿石方块；deepslate 脉全脉深板岩且矿囊为
  `deepslate_*_ore`；同一脉上能观察到 ≥2 种矿石；无 `raw_*_block`

## 3A. 宿主按高度分带 + 天空石并入矿脉（v7）

- [x] 3A.1 **删除陨石团簇分支**：移除 `placeBlob`、`METEOR_CHANCE`、
  `METEOR_MIN_Y` / `METEOR_MAX_Y`、`SMOOTH_SKY_STONE`、`COUNT_METEOR` 及相关统计与
  日志；`place()` 只保留矿脉片段放置；`isMeteorAllowed` 更名 `isVeinAllowed`。
  验证：`grep -rn "METEOR\|placeBlob\|SMOOTH_SKY_STONE" src/main/java` 无残留；
  类 javadoc 不再描述陨石

- [x] 3A.2 **宿主分带常量与解析**：新增 `DEEPSLATE_ZONE_MAX_Y = 8`、
  `SKY_STONE_MIN_Y = 180`、`SKY_STONE_SHARE = 0.15D`；`VEIN_MAX_Y` 170 → **300**；
  删除 `DEEPSLATE_SHARE`。`buildVein` 按 `veinOrigin(...).getY()` 解析宿主：
  `y ≤ 8` → deepslate；`8 < y < 180` → stone；`y ≥ 180` → 按 `SKY_STONE_SHARE`
  抽 sky_stone，否则 stone。抽取必须用单元 `rnd`（不得引入 `ctx.random()`），
  且顺序要保证跨区块回放一致（宿主抽取在 origin 解析后、主径游走前）。
  验证：同种子回放宿主一致；每条带抽样比例接近设定值

- [x] 3A.3 **AE2 缺失回退**：`ae2:sky_stone_block` 注册表查不到（或为 AIR）时，
  该脉宿主回退 `minecraft:stone`，矿囊仍走常规矿石池。
  验证：临时代码强制返回 null 跑一次生成，确认无空气段、无异常

- [x] 3A.4 **类 javadoc 与形态说明重写**：表格改为"一类浮岩（天隙矿脉）"；
  写入宿主分带表、`SKY_STONE_SHARE`、`DEEPSLATE_ZONE_MAX_Y`、`SKY_STONE_MIN_Y`、
  `VEIN_MAX_Y = 300`、AE2 缺失回退规则，并注明"sky_stone 是历史命名，
  注册 id `maixr:sky_stone_cluster` 不变"。
  验证：`./gradlew build` 通过，启动无注册/加载错误

## 4. 数据与文档

- [x] 4.1 `placed_feature/sky_stone_cluster.json`：`height_range` 上限 170 → **300**；
  注释块删除陨石段落，写明"矿脉 origin y 实际由单元种子派生（-50~300）"。
  验证：runClient 数据包加载无 parse error

- [x] 4.2 `_docs/破碎世界.md` 一点七整节重写：删除陨石段与 `METEOR_CHANCE`；
  保留形态四要素表；新增宿主分带表（y 阈值 + 天空石占比 + AE2 回退规则）；
  矿池表保留；调参表常量全部替换（`DEEPSLATE_ZONE_MAX_Y` / `SKY_STONE_MIN_Y` /
  `SKY_STONE_SHARE` / `VEIN_MAX_Y = 300`，删除 `DEEPSLATE_SHARE` / `METEOR_*`）；
  形态演进史补 v7 一行（陨石并入矿脉 + 宿主分带 + 高度上限 300）。
  验证：文档中常量值逐一对得上代码

## 5. 集成验证

- [x] 5.1 破碎世界验收 spec 场景：①细长直脉（96~224 格）+ 两壁参差 +
  端头尖灭（无支脉）；②相邻区块片段无缝拼合，无齐头截断、无缺失段
  —— ①② 由用户实机确认通过；③矿囊产出矿石而非粗矿块（自动化：
  15 种 `*_ore`，高空带无 `raw_*_block`）；④**宿主分带**（自动化：
  `deepslate` 仅 y≤8、`sky_stone` 全部 y≥240、无混拼）；
  ⑤**空域不再有陨石圆球**（自动化：`smooth_sky_stone` 计数 0）。
  工具：`_tools/verify/gen_stats.py` / `sky_locate.py` / `vein_shape.py`
- [x] 5.2 同种子确定性抽查：同 seed 两次全新生成同坐标批次，裂隙形态、
  宿主材质与矿囊位置一致。验证：**按 section-Y 分组比对**，矿脉高度带
  （y ≥ 180）差异数必须为 0；低空带的流体/深暗之域漂移不计入失败
  （`_tools/verify/chunk_diff_by_y.py`）。实测：高位带 0 差异、
  sections 总相同率 94.9%（31/609 差异全在 y<180 的 water/lava）
- [x] 5.3 `/maixr bench` 复测：本轮**重采**同机同协议的原版/破碎对照作为分母
  （旧 2.38x 口径已作废）。实测含 C2ME 环境：破碎 7.10s / 原版 4.24s =
  **1.67×**，用户确认符合预期并通过。读数与 mods 清单已入档
  `_docs/生成性能测量.md`「五之二」

## Workflow follow-up

- 归档前 `openspec validate sky-fissure-ore-veins` 通过
- 实现完成、用户验收后走 `/opsx:archive`（spec delta 合入
  `broken-world-type`，提交落在 `feat/broken-gen-rewrite` 分支）
