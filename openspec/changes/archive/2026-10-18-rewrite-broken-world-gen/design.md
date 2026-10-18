# Design

## Context

现有生成器用区块级哈希决定"整块保留/整块挖空"（`BrokenOverworldChunkGenerator.isVoided`），
碎只存在于洞的边界。背景叙事（见 proposal Why）与目标观感要求逐方块的碎裂场。
约束：NeoForge 1.21.1 / Java 21；区块生成器与密度函数注册表值类型均为 `MapCodec`；
`_docs/破碎世界.md` 记录的 v5 结论——`StructurePiece.postProcess()` 无条件 `setBlock`，
结构落地与脚下地形无关——是本设计的物理基础。

## Goals / Non-Goals

**Goals:**

- 逐方块碎裂场：四带静滞渐变 + 震中 + 各向同性裂缝 + Voronoi 板块，跨区块连续
- 垂直形变（漂移/断层/倾斜）全在密度管线内完成，天然跨区块一致
- 三维度共用同一套 DF 类型（三份 noise_settings JSON）
- 材质三层：断面裸露地层（白拿）、震中幽匿替换（无装置）、远端天空石结晶
- 保留全部既有系统（天空结构/浮岩/出生岛/判定）

**Non-Goals:**

- 结构残迹化、群系漂移、修复度接口、动态侵蚀（均二期或明确不做，见 proposal）
- 出生岛容器化重做；整合包内容侧玩法（生物疯狂等）
- 老存档已生成区块的任何迁移

## Decisions

### D1 实现路线：自定义 DensityFunction + noise_settings JSON（路线 B）

碎裂几何完全在 vanilla 密度管线内求值：注册自定义 DF 类型
（`Registries.DENSITY_FUNCTION_TYPE` 的值是 `MapCodec`，硬规则同区块生成器），
新增 `data/maixr/worldgen/noise_settings/` 三份 JSON（抄改 vanilla overworld/nether/end_islands，
`final_density` 外裹碎裂场），`broken.json` preset 三维度分别指向。

- 否决**后处理路线**（super.fillFromNoise 后逐方块重写）：拿不到邻区块数据，
  水平撕裂/倾斜/漂移无法跨区块连续，只能做侵蚀+垂直位移，"全都要"不成立；
  且含水层水会被切出悬空水面。
- 否决**自绘生成器**：重造地表/装饰/结构集成，风险与工作量不成比例。
- **关键红利**：密度为 0 的地方自动无地形，`StructurePiece` 照常 `setBlock` ——
  「地形与结构完全解耦」不再需要任何跳过逻辑，v5 教训自动成立。

### D2 自定义 DF 类型清单（全部常量写在代码里，JSON 只引用类型）

沿用项目"调参常量在类顶部"惯例（同 `NEAR_VOID_CHANCE` 风格），文档引用代码常量：

| 类型 | 职责 | 关键常量（默认值） |
|---|---|---|
| `maixr:fracture` | 输出撕裂深度（≤0，`原密度 - 深度`）：静滞场带渐变 x 裂缝脊噪声 x 板块存活 + 震中加压 | `CALM_RADIUS=1024`/`CRACK_END=3072`/`FRAG_END=6144`/`TEAR_SCALE=4`；带内裂缝宽/密度参数 |
| `maixr:plate_field` | 2D Voronoi 板块（xz cell + 每 cell 哈希状态），输出存活/漂移量 | cell 尺寸 ~64 格；碎屑子层 cell ~8 格 |
| `maixr:plate_displace` | 包装子 DF：按板块漂移量偏移采样坐标（漂移/断层/倾斜都在这） | 漂移幅度按带 `0/5/15..25/30..40`；`TILT=0`（默认关） |

裂缝用脊状噪声（`1-|noise|` 阈值化）保证各向同性且天然分叉；震中 = 若干
哈希散布点 + 1 个确定性方位地标点，对碎裂掩码做局部乘压。
末地主岛保护 = 末地 settings 的静滞半径覆盖主岛（~768 格）。

### D3 种子回归：噪声携带种子，哈希仅做抖动

旧 `roll01` 不依赖种子是 KubeJS 遗产。新设计：裂缝/震中散布用注册
`NoiseParameters` 的噪声实例（RandomState 自动注入世界种子 → 随种子变化）；
板块 cell 哈希无种子，但只做 cell 内抖动，叠加在带种子的噪声上，整体形态仍随种子变化。
同种子内一切判定确定性不变（spec 硬要求）。

### D4 材质置换用轻量 dressing pass（不动几何）

密度函数只能控密度，不能换方块类型。**断面裸露地层零成本**——原版地表规则
早已把土/石/深板岩放好，碎裂只是露出来。需要置换的只有两处：
震中幽匿替换与远端天空石结晶。做法：`BrokenOverworldChunkGenerator` 在
`applyBiomeDecoration` 链尾（装饰完成后）跑一次确定性逐方块 pass，
按碎裂场现算值置换表面方块（幽匿块+脉络 / 天空石）。幽匿置换必须覆盖
草方块等表层（否则草皮长在侵蚀上）。

### D5 下界/末地不需要自定义生成器子类

主世界保留 `BrokenOverworldChunkGenerator` 薄标记子类（settings 指向新 JSON）：
`instanceof` 世界类型判定、`createBiomes` 的 `maskSkyReferences`、
`createStructures` 的天空结构挂钩、D4 的 dressing pass 都挂在这里，一律不动逻辑只换 settings。
下界/末地无挂钩需求，`broken.json` 直接用 vanilla `minecraft:noise` 生成器 + 新 settings。

### D6 性能预算

碎裂掩码每插值 cell 求值一次（vanilla 密度管线原生插值），每 cell 约 3~6 次噪声采样
+ 1 次 Voronoi（哈希 + 最近 2 cell 距离）；dressing pass 每区块一次 O(16x16x高) 扫描、
只碰表面。量级与原版噪声生成相当。下界天花板倒挂碎块走同一板块场（y 取负域），
不加独立系统。

### D7 Spike 结论（任务 1.3 回填，runServer 实测通过）

注册链路可行，最大未知已消除。实测方式：最小常量 DF（`maixr:spike`）注册进
`Registries.DENSITY_FUNCTION_TYPE`（值类型 `Registry<MapCodec<? extends DensityFunction>>`，
与区块生成器同款硬规则），`broken_spike.json`（抄 vanilla overworld，
`final_density = mul(maixr:spike(0.0), 原final_density)`）挂在测试维度
`maixr:spike_test` 上；runServer 启动即完成 worldgen 注册表加载（MapCodec 契约满足，
无 ClassCastException），启动后采样 `final_density` 恒为 0.0、测试维度区块生成后
y=64 为纯空气 —— 注册→JSON 解析→DF 求值→地形受控全链路验证通过。

Spike 过程中确认的实现约束（后续任务必须遵守）：

1. **自定义 DF 类的 codec 三件套**：`RecordCodecBuilder.mapCodec` 构造 `MapCodec`、
   `KeyDispatchDataCodec.of(mapCodec)` 作为 `codec()` 返回值、实现
   `SimpleFunction`（白拿 `fillArray`/`mapAll` 默认实现）。
2. **种子注入**：`RandomState` 的 `NoiseWiringHelper` 通过 `mapAll` 递归整棵树，
   自定义 DF 的 `mapAll` MUST 对内部 `NoiseHolder` 调 `visitor.visitNoise(...)`
   并用带种子的噪声实例重建自身（否则 `NormalNoise` 为 null，噪声恒 0）。
3. **坐标偏移安全**：`NoiseInterpolator.compute` 在 `ctx != NoiseChunk` 时直接下钻
   求值、`FlatCache`/`Cache2D` 对任意坐标均正确（越界回退直算）—— 因此
   `maixr:plate_displace` 放在 `minecraft:interpolated` **内层**包住原版地形子树
   （享受逐 cell 插值性能），偏移采样跨过外层插值器时值仍正确。
   被引用的 vanilla density_function JSON 中没有任何 `interpolated` 标记（已验证），
   `plate_displace` 子树内不会出现嵌套插值器。
4. **碎裂作用必须用减法撕裂而非乘法掩码**（实现阶段 runServer 实测修正）：
   方块实体判定是 `final_density (+Beardifier) > 0.0`（严格大于）—— 乘法掩码在
   原版密度为负的区域（洞穴/空腔/末地外围）会把空气反转成实体（实测末地外岛
   29/32 实心、下界碎裂带 21/32 高于原版 11/32）。最终实现：fracture 输出
   撕裂深度 `-(1-存活率)×4`，JSON 里 `add(原final, cache_2d(fracture))` ——
   完好处深度 0 精确还原原版（含负密度洞穴），完全撕裂处 -4 强制空气，
   对任意原版密度符号单调。
5. **空洞区流体陷阱**：密度 ≤0 的区域由流体选择器填充 —— aquifers 关闭时
   `y < sea_level` 全是水柱。三份 broken settings MUST 设
   `aquifers_enabled=false` 并把 `sea_level` 降到 -1000（流体永不填充），
   碎裂区才是纯虚空；副作用是平静区的原版海盆变干涸盆地（可接受，文档记录）。
6. **final_density 注入结构**（broken_overworld.json，减法撕裂终版）：
   `min( add( cache_2d(maixr:fracture), squeeze(mul(0.64, interpolated(
   blend_density(add(0.1171875, mul(底梯度, add(..., mul(顶梯度, add(...,
   maixr:plate_displace(原 range_choice 子树))))))))))) , 原 min 第二参数 )`。
   撕裂深度放 `interpolated` 外层并用 `cache_2d` 逐列缓存（纯 2D xz 场）；
   原版底部/顶部 y 梯度保留在 `plate_displace` 外层（基岩层与建造上限渐变不随板块漂移）。
   下界为 `max( add(原地形链, 撕裂), 天花板倒挂碎块项 )`，末地为 `add(原地形链, 撕裂)`
   + fracture/plate_field/displace 均带 `band_floor=2.0`、`scale=0.75`（外岛直接碎块带）。

## Risks / Trade-offs

- **自定义 DF 类型注册/noise_settings 引用是本次最大未知** → 第一个任务就是 spike：
  最小 DF 类型 + 最小 settings JSON 跑通 runClient 再铺开。
  保底降级：若注册路径有不可绕过的坑，退到后处理路线（侵蚀+垂直位移，
  放弃水平撕裂/倾斜），spec 的垂直形变要求相应降级——需回到变更流程改 spec。
- **地形漂移可能与悬空结构穿模**（结构在原高度落地，地形漂上去）→ 漂移幅度
  按带限幅（裂缝带 ≤±5），碎块带以远结构本就悬空、穿模可接受；实测观感再调。
- **Beardifier 在新架构下会给悬空的 terrain_adaptation 结构长"土根"**
  （旧实现跳过噪声故无此现象）→ 可能反而像"撕裂的地根"，先实测再定；
  不行则对天空结构维持现有 `maskSkyReferences`（已保留，不额外做事）。
- **裂缝阈值离散跳变**（浮岩圆度阈值的教训）→ 调参前先写体素模拟脚本出图，
  不凭手感直接上世界。
- **幽匿 dressing 与雪/植被叠序** → pass 在装饰完成后执行、覆盖表层，顺序固定；
  置换密度按震中距离衰减，避免震中变成纯黑块。
- **三维度同开测试面大** → 主世界先行验证通过后才铺下界/末地 settings，
  tasks 按此排序。

## Migration Plan

只影响未生成区块：老存档新探索区域按碎裂场生成（新旧地形交界即裂缝，观感自然可接受）；
`broken.json` preset 变更对新世界生效。回滚 = git revert + 还原 preset/settings JSON。
无数据迁移、无 NBT 兼容问题（浮岩/出生岛/SavedData 全部不动）。

## Open Questions

- Beardifier"土根"的实际观感（保留为特性 or 维持屏蔽）——runClient 实测定。
- 各带裂缝阈值、板块存活率、碎屑密度的具体数值——体素模拟脚本确定后回填
  常量表与 `_docs/破碎世界.md`（文档规范：常量值必须与代码一致）。
- 下界倒挂碎块密度上限（防天花板挂成钟乳石海）——下界实测再定。
