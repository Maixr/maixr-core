# Maixr Core — Agent 指南

Minecraft NeoForge 1.21.1 核心模组（modid `maixr`，v2.3.1）。Java 21 + ModDevGradle 2.0.146。

## 规范入口（先读，按序）

1. `openspec/config.yaml` — 项目约定全文（技术栈、当前能力清单、1.21.1 移植硬规则、文档规范）。
2. `openspec/specs/` — 能力规范：`broken-world-type` / `doll-ae2-network` / `pigmee-duplication`。
   **改行为前先改对应 spec，再改代码**；校验用 `openspec validate --specs --strict`。
3. 变更流程：`/opsx:propose` 发起变更提案（写进 `openspec/changes/`），实现后归档。

## 关键文档

- `_docs/破碎世界.md` — 破碎世界类型全部设计与踩坑（碎裂场 / 天空结构 / 浮岩；开局空岛已移除）。
- `_docs/玩偶接入AE2.md` — 玩偶方块实体接入 ME 网络的实现要点。
- `_docs/生成性能测量.md` — `/maixr bench` 随机传送基准与 spark 火焰图定位流程（测量口径陷阱必读）。

## 硬规则速查（详见 config.yaml）

- 自定义区块生成器必须注册 `MapCodec<? extends ChunkGenerator>`（写成 `Codec` 会 ClassCastException）。
- 网络包走 `RegisterPayloadHandlersEvent`；客户端类引用只允许出现在 `com/maixr/client/`。
- 物品 NBT 走 `NbtCompat`（Data Components）；`getOrCreate` 返回副本，改完必须 `set` 写回。
- Curios 调用必须有 `ModList.isLoaded()` 守卫。
- 文档中的常量值必须与代码常量一致，改代码同步改文档。

## Git 约定

- **当前工作分支：`feat/broken-gen-rewrite`（C 方案：破碎世界生成器结构性重写）。所有代码提交一律落在这个分支，不要直接提交 main**；main 仅作稳定基线（已同步 origin）。开工前确认 `git branch` 停在该分支。
- 基准/测量产物（`run-bench*/`、`_bench-data/`、`logs/`、`_tools/classes/`，约 300MB 世界存档）已在 .gitignore，永不入库。

## 构建

```bash
./gradlew build   # 本机网络可达，在线解析；JDK 21 在 C:\Users\Administrator\.jdks\ms-21.0.12
```

- `libs/` 共 5 个 jar 已就位（AE2 19.2.18 / guideme 21.1.19 / Curios 9.5.1+1.21.1 /
  SkyblockBuilder 21.1.37 / LibX 1.21.1-6.0.15），均在 .gitignore 中；
  丢失时按 README 构建一节的 Modrinth 版本号重新下载并保持文件名一致。
- dev 运行（runClient/runServer）从 `run/mods/` 发现模组：`syncDevMods` 任务自动把 libs/ 同步过去，
  runClient/runServer 已依赖它。FML 不认 classpath 上的 jar（additionalRuntimeClasspath 无效，实测踩坑）。
- `ae2` 是 mods.toml 里的必需依赖，dev 环境缺它启动即崩（"Mod maixr requires ae2"）。
- 调试 info 日志已注释（`SkyStructurePlacer` / `SkyStoneClusterFeature`，
  搜 `已注释` 可定位）；error / warn 保留。排查时取消对应注释再复现。
- `_maven/` 离线镜像**存在才启用**（离线开发机用）；本机没有它，走在线解析，不要手工创建空目录。

人造排污/粉红鸟巢多方块机（Forge 1.20.1 旧代码，原 `_deferred_nest/`）已整体删除（git 历史可查）；
如需重做须按 NeoForge 的能力注册方式整套重写，不要尝试恢复旧代码直接编译。
