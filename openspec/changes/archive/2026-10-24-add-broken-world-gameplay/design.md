# Design

## Context

碎裂场把主世界撕成碎块后，「通行」成为玩法主瓶颈：裂缝与虚空让步行无法跨碎片，
地表水被挖光让补水靠运气，悬空晶洞/岩浆在虚空里视觉穿帮。远程合入的实现在
`CrackBridges` / `FragmentRoadNetwork` / `FragmentBridges` / `SurfaceLakes` /
`VoidDebrisCleanup` / `GeodeBlobCleanup` / `AmethystGeodeFeatureMixin` 中已基本落地，
但有三个遗留问题：道路网没有任何调用点；配置默认值的注释互相矛盾；
下界/末地回退原版后留下死数据与过时注释。本文记录接线与收尾的技术决策。
多轮实机翻车的背景结论（为什么代码长这样）直接决定设计，见 D1/D4/D5。

## Goals / Non-Goals

**Goals:**

- 跨碎片通行：生成期裂缝桥（常开）+ 加载期碎片道路网（门控）双轨，玩家可步行/乘铁路穿行
- 补给：伴生岛提供水/甘蔗/维度小岛等兴趣点，且确定性可复现
- 治理：悬空晶洞从「生成拦截 → 生成期清理 → 加载期补扫」三层收敛，绝不半删
- 规范对齐：spec 与代码一致（下界/末地回退、配置默认值、常量值）

**Non-Goals:**

- 不恢复破碎下界/末地（已回退原版，不做折中方案）
- 不做开局空岛/出生点相关改动（spawn-on-natural-surface 已归档）
- 不引入 KubeJS 依赖或任何新第三方库
- 不优化旧版 `FragmentBridges`（退役保留，仅作历史参照）

## Decisions

**D1 跨区块访问禁忌（全系统最高约束）**
生成阶段（dressing 链内）的 pass 只读本区块高度图与方块；加载阶段的系统只用
`getChunkNow` 探测已加载区块，绝不 `getChunk` 强制加载。原因（实测两次）：
生成阶段读跨区块高度抛 `Requested chunk unavailable during world generation`
把世界卡死在 0%；`ChunkEvent.Load` 里强制加载触发「加载 → 事件 → 再加载」
无限递归把进世界拖到分钟级。替代方案「排队等邻区块加载后建」在旧版连桥上
实测失败率极高（尝试 1000 / 建成 0），仅保留为道路网的兜底重试队列（tick 补建，
每 tick ≤4 个碎片，避免主线程 A* 风暴）。

**D2 建桥双轨分工**
- 裂缝桥（`CrackBridges`）：生成期、常开、纯本区块、低成本——解决「眼前的裂口」，
  1/2 概率 × 每区块 ≤6 座 × 裂宽 2~12 格，因为全世界都是裂缝所以命中必然。
- 道路网（`FragmentRoadNetwork`）：加载期、`bridgesEnabled` 门控、跨碎片——
  解决「碎片间可达」，A* 自动绕行 + 坡度 1.0 约束 + 中线铁轨，是「基建」。
- 旧版 `FragmentBridges` v6 退役：保留源码不再调用。它的端点吸附 + 直线弓形
  方案被道路网的 RNG+A* 全面替代（v6 注释里的补洞/坡度修补是旧形状模型的补丁）。

**D3 道路网接线方式（本变更唯一的新增代码行为）**
`MaixrBridgeEvents.onChunkLoad` 中 `bridgesEnabled` 分支改调
`FragmentRoadNetwork.run(...)`；`MaixrMod` 构造器注册服务端 tick 调
`FragmentRoadNetwork.tick(level)`（挂 `ServerTickEvent.Post`）。
替代方案：新增独立开关让两套连桥并存——否决，双系统会对同一对碎片重复建桥
（裂缝桥无 SavedData 去重，与道路网叠罗汉），配置面也无谓翻倍。

**D4 晶洞治理三层防线**
1. 生成拦截（治本）：mixin 打 `Feature.place` HEAD（`GeodeFeature` 不覆写
   `place`，打子类会静默跳过；`instanceof` 过滤），原点列向下 9 格全空气 → 取消。
   mixin 类**禁止任何字段**（SpongePowered/Mixin #576：`@Unique` 静态字段误判
   导致整个注入失败——之前晶洞照生成的根因），`required=false` +
   `defaultRequire=0` 保证不阻断启动。
2. 生成期清理（`VoidDebrisCleanup`，dressing 链尾、常开）：悬空岩浆单块判定 +
   晶洞整坨 BFS（≤4000 块）。调色板 `maybeHas` 快路径跳过无关 section。
3. 加载期补扫（`VoidDebrisCleanup` + `GeodeBlobCleanup` ≤60000 块，
   `geodeCleanupEnabled` 门控默认关）：覆盖旧版本生成的大晶洞。

**D5 晶洞整坨判定语义（两次实机翻车的结论）**
按列判支撑（v1）会把晶洞切成「底部留着、上部消失」；按列判 96 格深支撑（v2）
把贴壁半球保留、悬空半球删掉（「纸杯蛋糕」）。唯一正确语义是 **BFS 连通整坨 +
整坨决策**：任一块有非晶洞支撑/地形接触 → 整坨保留；全无 → 整坨删。
跨区块边界的坨与 BFS 碰到未加载区块时一律跳过（无法确认另一半，宁可留着）。

**D6 伴生岛放置约束**
变体按 `floorMod(x*7 + z*11, 100)` 确定性分派；NBT 岛需要 12×12 无地形空地、
程序岛 10×10；基准高度取邻列 `WORLD_SURFACE`（保证「贴着」碎片表面而非悬空）；
y ≤ 100（玩家反馈更高的岛登不上）。水族馆底面用青金石块（沙在水中悬空会掉落）；
海带用 `KELP_PLANT` + 顶端 `KELP`。模板缺失静默跳过（数据包异常不炸生成）。

**D7 配置语义修正**
实际默认值以 `define(...)` 为准：`bridgesEnabled=false`、
`geodeCleanupEnabled=false`。修正 `MaixrConfig.geodeCleanupEnabled()` javadoc
（「默认 true」）与 `MaixrBridgeEvents` 注释（「晶洞清理开启」）两处与代码矛盾。
`uniformBand` 语义不变（仍是唯一碎裂强度入口）。

**D8 下界/末地回退收尾**
`broken.json` 已引用原版 settings；本变更删除死数据
`noise_settings/broken_nether.json`、`noise_settings/broken_end.json`、
`noise/nether_shard.json`（仅被 broken_nether 引用），并修正 `broken.json`
头部仍宣称「下界/末地同用碎裂场」的注释。旧存档不受影响：维度生成设置在
世界创建时固化于 level.dat，数据包文件删除不回溯已建世界。

## Risks / Trade-offs

- [道路网 A* 在主线程 tick 执行，长距离最坏 4 万次节点展开] → 每 tick 限额 4 个
  碎片；网格 2 万节点上限（超限直接失败入队不重试）；端点永久失败（NO_END）
  不进重试队列，避免死循环。
- [铁轨朝向需要邻居更新] → 铺轨 `setBlock` flag 3（其余桥面 flag 2）。
- [mixin 与其它改动 `Feature.place` 的模组叠加] → `require=0` 注入失败不崩；
  双重过滤（`GeodeFeature` + 破碎世界生成器）把影响面压到最小。
- [道路网与裂缝桥视觉重叠（同一裂口既有裂缝桥又有道路桥）] → 接受：道路网按
  碎片对去重、裂缝桥低频且纯本地，重叠概率低、玩法上是冗余保险不是 bug。
- [伴生岛刷新率与天空结构（y≥201）/浮岩（y=110~170）视觉抢戏] → 岛 y ≤ 100
  且要求 10~12 格无地形空地，天然错层；不额外加去重。
- [删除 noise_settings 死数据后被外部数据包引用] → 本项目无外部引用
  （grep 全仓核实仅 preset 与内部互相引用）；git 历史可随时找回。

## Migration Plan

1. 合入后默认行为：配置全关 + 常开项（裂缝桥/伴生岛/生成期清理/mixin 拦截）只影响
   **新区块**，现存世界无需迁移。
2. 想要道路网/加载期清理的玩家在 `config/maixr-common.toml` 打开
   `bridgesEnabled` / `geodeCleanupEnabled`；关闭即回滚，无持久化副作用
   （道路网 SavedData 残留的已建记录无害）。
3. 整体回滚 = git revert 本变更提交（SavedData 里多出的 `maixr_road_network`
   数据被新版本忽略，不需清理）。

## Open Questions

- 道路网实机成功率与加载卡顿需实测标定（诊断计数器 C_BUILT/C_UNLOADED/C_NO_PATH
  已内置，任务里含一次 runServer 采样）；若默认参数下建桥率仍不可接受，调
  RNG 容忍度 0.8 与每 tick 限额，不动 spec 语义。
