# Tasks

## 1. BrokenSpawnSearch 三级搜索实现

- [x] 1.1 在掩码预筛与真实区块校验之间插入逐列高度场离线校验
      `chunkHasSpawnableColumn(cx, cz)`：对候选区块按 lz 外层 / lx 内层逐列调
      `generator.getBaseHeight(wx, wz, WORLD_SURFACE, level, randomState)`，
      返回值 > minY 即命中并短路；计数器 `screenedColumns` / `offlineHits`，
      验证方式：编译通过 + 日志出现「离线命中」统计。
- [x] 1.2 `tryChunk` 接入三级顺序（掩码 → 离线校验 → `getChunk` 真实校验），
      真实校验代码与合格口径不动；验证方式：runServer 新建世界，
      日志「出生点选定」中「实际生成区块」≤ 个位数。
- [x] 1.3 类注释两级→三级口径更新（含为什么不能单点 compute 的 API 结论）；
      `MaixrMod` 事件注册处注释同步；验证方式：grep 全仓不再残留
      「两级搜索」活口径描述（历史注记除外）。

## 2. 文档同步

- [x] 2.1 `_docs/破碎世界.md`「一点八」补第三级机制、性能背景
      （玩家实测 2 分钟 TPS=0 的根因）与本次实测数据；验证方式：
      文档口径与代码一致（AGENTS.md 文档-常量同步规则）。

## 3. 构建与验证

- [x] 3.1 `./gradlew build` 编译通过；`openspec validate`（变更 + specs strict）
      通过；验证方式：两条命令退出码 0。
- [x] 3.2 runServer 新建破碎世界实测：记录「出生点选定」日志的耗时与
      「预筛命中 / 离线命中 / 实际生成」三个计数；验证方式：耗时从分钟级
      降到秒级且真实生成区块数 ≤ 个位数。
