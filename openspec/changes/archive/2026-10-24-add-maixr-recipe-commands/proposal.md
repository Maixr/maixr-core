# Proposal

## Why

整合包《Project Negentropy》的配方调整全靠手写 KubeJS 脚本，逐条敲物品 id 效率极低。
`/maixr` 配方脚本命令（从背包取材、一键生成可点击复制的 KubeJS 脚本）曾实现后被整体删除，
`openspec/config.yaml` 上下文也把它列在「已整体移除的系统」里；最新构建（提交 0a11dd6）
又将其恢复并扩充（模组配方 + 配方查询）。按项目流程，需要为恢复后的行为建立独立能力规范，
并同步修正「已移除」的过时表述。

## What Changes

- **新增能力 `maixr-recipe-commands`**：`/maixr` 玩家命令族，统一取材约定
  「快捷栏 0~8 = 材料、副手 = 产物」，全部输出**点击即复制**的 KubeJS 脚本文本：
  - 原版配方：`list-recipe`（shaped 3×3）、`list-recipeless`（shapeless）、
    `list-smelting` / `list-blasting` / `list-smoking` / `list-campfire` /
    `list-stonecutting`（单材料烹饪）、`list-smithing`（锻造 0 模板/1 底/2 添加）、
    `list-remove`（仅移除行）；下划线命名保留为别名。
  - 模组配方：`list-create` / `list-mekanism` / `list-ae2` `<type>` ——
    Create 7 类（crushing/milling/mixing/compacting/pressing/cutting/splashing）、
    Mekanism 6 类（enriching/crushing/smelting/injecting/purifying/metallurgic）、
    AE2 2 类（charger/inscriber）；不支持的类型明确报错并列出可用清单。
  - 查询：`recipe-ids <mod>`（列某命名空间全部配方 id，前 80 条可点击复制）、
    `recipe-dump <id>`（打印序列化器/类型/产物/输入）。
- 生成的每条脚本 SHALL 以 `event.remove({output:…});` 开头并显式
  `.id('maixr:<物品路径>[_<类型>]')`（生成即覆盖语义，避免旧配方残留）。
- **本模组不依赖 KubeJS**：只生成脚本文本，不解析、不执行、不注册配方。

## Capabilities

### New Capabilities

- `maixr-recipe-commands`: `/maixr` 配方脚本生成与查询命令族——从玩家背包取材生成
  可点击复制的 KubeJS 配方脚本（原版 + Create/Mekanism/AE2），以及配方 id / 结构查询。

### Modified Capabilities

（无——该命令族此前无规范，「已移除」状态由本变更的文档同步任务修正，
不构成对现有能力需求的修改。）

## Impact

- **代码**：`MaixrCommands`（命令树与脚本模板，已随拉取落地）、`MaixrMod`
  （构造器 `NeoForge.EVENT_BUS.addListener(MaixrCommands::register)` 注册
  `RegisterCommandsEvent`）。
- **依赖**：无新增（纯原版 brigadier + RecipeManager 只读访问；KubeJS 非依赖，
  产物是文本，在整合包侧由 KubeJS 加载）。
- **权限面**：命令不设权限门槛（等级 0，所有玩家可用），仅玩家本人执行；
  只读访问配方管理器与自己的背包，无破坏性操作。
- **文档同步**：`openspec/config.yaml` 上下文「已整体移除的系统」清单需移除
  「/maixr 指令」（归档时处理）；README 功能概览补命令说明。
