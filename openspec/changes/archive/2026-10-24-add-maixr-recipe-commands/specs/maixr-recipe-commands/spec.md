# Spec Delta

## Purpose

`/maixr` 配方脚本命令族：玩家把材料放进快捷栏、产物拿在副手，即可一键生成
可点击复制的 KubeJS 配方脚本（原版配方类型 + Create/Mekanism/AE2 常用类型），
并提供配方 id 与配方结构查询，消除整合包配方调整中逐条手写物品 id 的负担。

## ADDED Requirements

### Requirement: 命令入口与执行对象

`/maixr` 命令族 SHALL 在 `RegisterCommandsEvent` 注册，不设权限门槛
（所有玩家可用）；SHALL 仅限玩家本人执行，非玩家来源（控制台/命令方块）
执行取材类子命令时 SHALL 失败并提示。子命令 SHALL 包含：`list-recipe`、
`list-recipeless`、`list-smelting`、`list-blasting`、`list-smoking`、
`list-campfire`、`list-stonecutting`、`list-smithing`、`list-remove`、
`list-create`、`list-mekanism`、`list-ae2`、`recipe-ids`、`recipe-dump`；
`list-*` 的下划线命名（如 `list_recipe`）SHALL 作为等价别名保留。

#### Scenario: 玩家执行命令

- **WHEN** 任意玩家执行 `/maixr list-recipe`
- **THEN** 命令接受并按取材约定生成脚本（或给出明确的取材失败提示）

#### Scenario: 非玩家来源被拒绝

- **WHEN** 控制台执行 `/maixr list-recipe`
- **THEN** 命令失败并提示需要玩家执行（不抛未处理异常）

### Requirement: 取材约定

取材类子命令 SHALL 统一按「快捷栏 0~8 格为材料、副手为产物」读取玩家背包：
shaped/shapeless/mod 配方取 0~8 全部非空格，烹饪类（smelting/blasting/smoking/
campfire/stonecutting）只取 0 号位材料，锻造（smithing）取 0=模板、1=底、
2=添加；`list-remove` 只读副手。副手为空时 SHALL 失败并提示
「请把合成产物放到副手」；所需材料格为空时 SHALL 失败并提示缺失。

#### Scenario: 缺副手产物

- **WHEN** 副手为空时执行任一取材类子命令
- **THEN** 命令失败，提示把合成产物放到副手

#### Scenario: 缺材料

- **WHEN** 执行 `list-smelting` 而快捷栏 0 号位为空
- **THEN** 命令失败，提示 0 号位没有材料

### Requirement: 原版配方脚本生成

原版配方子命令 SHALL 生成对应 KubeJS 脚本：`list-recipe` → `event.shaped`
（3×3 网格按行映射字母 A~I，空格代表空位，key 映射只含非空格物品）；
`list-recipeless` → `event.shapeless`（非空材料依次列出）；烹饪五类 →
`event.smelting` / `event.blasting` / `event.smoking` / `event.campfireCooking` /
`event.stonecutting`（取 0 号位单材料）；`list-smithing` → `event.smithing`
（模板/底/添加三参数）；`list-remove` 只生成 `event.remove({output:'<产物 id>'});`。

#### Scenario: shaped 脚本结构

- **WHEN** 快捷栏 0~8 摆放材料且副手持产物时执行 `/maixr list-recipe`
- **THEN** 生成含 3 行 pattern、字母 key 映射的完整 `event.shaped` 脚本

#### Scenario: 仅移除行

- **WHEN** 副手持产物执行 `/maixr list-remove`
- **THEN** 只输出一行 `event.remove({output:'<产物 id>'});`

### Requirement: 脚本覆盖语义

除 `list-remove` 外，所有生成的脚本 SHALL 以
`event.remove({output:'<产物 id>'});` 行开头（生成即覆盖同产物旧配方），并以
`.id('maixr:<产物路径>[_<类型后缀>]')` 结尾：shaped/shapeless 不带后缀，
烹饪/锻造带类型后缀，模组配方带 `<mod>_<type>` 后缀；产物路径取产物 id
冒号之后的部分。

#### Scenario: 配方 id 规则

- **WHEN** 任一生成器为产物 `minecraft:iron_ingot` 生成熔炼脚本
- **THEN** 脚本 id 为 `maixr:iron_ingot_smelting`（路径取冒号后部分，
  类型后缀以下划线拼接）

#### Scenario: shaped 无类型后缀

- **WHEN** `/maixr list-recipe` 为产物 `minecraft:iron_ingot` 生成脚本
- **THEN** 脚本 id 为 `maixr:iron_ingot`（无类型后缀）

### Requirement: 点击复制输出

脚本与查询结果 SHALL 以聊天消息输出，正文 SHALL 挂载
`COPY_TO_CLIPBOARD` 点击事件（点击复制全文）与「点击复制」悬停提示；
脚本生成类输出 SHALL 带类型标题行。输出 SHALL 避免刷屏：一条脚本
（含多行）作为单条可复制消息发送。

#### Scenario: 点击复制

- **WHEN** 玩家点击生成脚本的消息正文
- **THEN** 剪贴板内容为完整脚本文本（含 `event.remove` 前缀与 `.id(...)` 结尾）

### Requirement: 模组配方脚本生成

`list-create` / `list-mekanism` / `list-ae2` SHALL 按子命令与 `<type>` 参数的
白名单生成对应模组的 KubeJS 脚本：Create——crushing、milling、mixing、
compacting、pressing、cutting、splashing；Mekanism——enriching、crushing、
smelting、injecting、purifying（后两者气体固定 `mekanism:oxygen`）、
metallurgic（生成 `metallurgic_infusing`，注入剂 `mekanism:redstone`、用量 10）；
AE2——charger、inscriber（top/middle/bottom 同为 0 号位物品并附
「请按实际修改」注释）。不支持的 `<type>` SHALL 失败并列出全部可用类型。

#### Scenario: 生成 Create 压制配方

- **WHEN** 执行 `/maixr list-create pressing`
- **THEN** 生成 `event.recipes.create.pressing(...)` 脚本，id 后缀为
  `_create_pressing`

#### Scenario: 不支持的类型

- **WHEN** 执行 `/maixr list-ae2 electrifying`
- **THEN** 命令失败并列出 ae2 可用类型（charger/inscriber）

### Requirement: 配方查询

`recipe-ids <mod>` SHALL 列出指定命名空间下的全部配方 id（可点击复制），
最多展示前 80 条，并汇总输出总数（截断时说明仅展示前 80 条）；
`recipe-dump <id>` SHALL 打印该配方的序列化器、类型、产物（物品 id × 数量）
与逐个输入（可点击复制）。id 格式非法或配方不存在时 SHALL 失败并提示。

#### Scenario: 命名空间配方清单

- **WHEN** 执行 `/maixr recipe-ids minecraft`
- **THEN** 输出 minecraft 命名空间的配方 id（前 80 条可点击）与总数汇总行

#### Scenario: 配方不存在

- **WHEN** 执行 `/maixr recipe-dump "maixr:not_a_recipe"`
- **THEN** 命令失败并提示找不到配方

#### Scenario: 配方结构转储

- **WHEN** 对一个有效的有序合成配方执行 `/maixr recipe-dump`
- **THEN** 输出其序列化器 id、配方类型 id、产物与数量、逐个输入物品
