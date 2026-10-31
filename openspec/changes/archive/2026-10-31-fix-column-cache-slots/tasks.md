# Tasks

> 代码修复与测量已完成（本变更在提案前先做了验证性实验，读数见 design D3/D4）。
> 任务 1、2 的实测工作已落地，勾选时附验证证据。

## 1. 缓存槽位修复

- [x] 1.1 `FractureDensityFunction` 新增 `SLOT_SHIFT = 64 - Integer.numberOfTrailingZeros(CACHE_SLOTS)`，索引由 `>>> 58` 改为 `>>> SLOT_SHIFT`；验证方式：`javap -c` 确认编译产物含 `getstatic SLOT_SHIFT` 与 `sipush 4095`（已核）
- [x] 1.2 `./gradlew build` 全量通过，产物 `build/libs/MaixrCore-1.21.1-2.0.0.jar` 生成；验证方式：构建退出码 0（已核）
- [x] 1.3 算术交叉验证：`>>> 58` 对 256/4096/16384 均只保留 6 位 → 有效槽位恒为 `min(64, CACHE_SLOTS)`；验证方式：离线计算比对（已核，确认 256 与 4096 行为等价）

## 2. 性能测量与值恒等验收

- [x] 2.1 同机同日重采原版基线（全新世界、seed 12345、9 区域中位数）：**7.11s**；验证方式：`_tools/forceload_bench.py --label vanilla-20261031`（已核）
- [x] 2.2 破碎世界修复后两次独立采集（各用全新世界）：**5.40s / 5.42s**；验证方式：同上 `--label broken-fix` / `broken-fix-run2`（已核，复验差 0.02s）
- [x] 2.3 计算比值 5.40 / 7.11 ≈ **0.76×**（旧口径 2.38×），确认已低于 1.5× 参考线；验证方式：中位数相除（已核）
- [x] 2.4 值恒等验收（对照 A：同构建重跑自漂移）：11935 共同区块 sections **97.70%**、BE **99.94%**；验证方式：`_tools/mca_diff.py` 全 44 region（已核，报告存 `_bench-data/cachefix-20261031/selfdrift.txt`）
- [x] 2.5 值恒等验收（对照 B：vs L2 短路前基线集）：11941 共同区块 sections **97.14%**、BE **99.95%**，高于行为级验收线 85%；验证方式：同上，报告存 `blockdiff.txt`（已核）
- [x] 2.6 差异性质核查：差异区块集中于 `r.10.20` / `r.18.10` / `r.27.36` 等伴生岛模板随机文件，与既往归档漂移模式一致；无 air↔实体翻转、无结构性形态变化；验证方式：逐 region 差异计数对比自漂移（已核）

## 3. 文档更正（本变更一并交付）

- [x] 3.1 `_docs/破碎世界.md`「实现踩坑记录 1」：删除「4096 槽 ≈ 8 对碰撞、风暴消除至 1.5%」的失效归因，改写为「槽位索引写死 `>>> 58` 致容量从未生效」+ 修复后实测 0.76×；验证方式：文档走查，确认文中常量与代码一致（`SLOT_SHIFT`、4096、64KB）（已核）
- [x] 3.2 `_docs/生成性能测量.md` L1 复测节：插入勘误段（标明该表采于容量未生效的构建，容量结论作废，保留原始读数）；验证方式：文档走查（已核）
- [x] 3.3 `_docs/生成性能测量.md` L2 JFR 归因节：标注「worker 大头是 NormalNoise」的结论建立在容量未生效之上，需以修复后口径重新理解；验证方式：文档走查（已核；另修正了 251 行附近一段重复错位残文）
- [x] 3.4 `_docs/生成性能测量.md` 新增「2026-10-31 缓存修复」小节：环境、协议、原版 7.11s / 破碎 5.40+5.42s、比值 0.76×、两组哈希验收读数；验证方式：文档走查，数字与本变更任务 2 的实测一致（已核）
- [x] 3.5 主 spec「性能不达参考线的处置」场景归档实例更正（2.38× → 归因更正 + 0.76× 达标）：经 `/opsx:archive` 把 delta 合入主 spec 完成，不直接手改；验证方式：归档后 `openspec validate --specs --strict` 通过

## 4. 版本与发布

- [x] 4.1 `gradle.properties` 的 `mod_version` 2.0.0 → 2.3.1；验证方式：`./gradlew build` 产物名为 `MaixrCore-1.21.1-2.3.1.jar`
- [x] 4.2 同步 `README.md` / `AGENTS.md` / `openspec/config.yaml` 中的版本与产物名表述；验证方式：三处 grep 结果与 `gradle.properties` 一致
- [x] 4.3 更新 `release/` 下的 jar 与 `SHA256SUMS.txt`；验证方式：`sha256sum -c` 通过（4fd23f3e…159，OK）
- [x] 4.4 提交并推送 `main`，打 annotated tag `v2.3.1`，创建 GitHub Release 并上传 jar + 校验和；验证方式：`git ls-remote --tags` 可见、release API 返回该 tag 且 asset 可下载

## Workflow follow-up

- 归档本变更（`/opsx:archive`），把 delta 合入主 spec。
- 归档后确认 `openspec validate --specs --strict` 全绿。
