# 创造猪咪复制配方 Specification

## Purpose

消耗版"创造猪咪"复制配方：合成台内 1 只创造猪咪（`ae2lt:creative_pigmee_fumo`）
+ 1 件任意物品，将该物品复制为一整组（64 个）；与 ae2lt 原版不同，
合成后猪咪本身会被消耗。对"NBT 里藏着猪咪"的容器物品拒绝复制，防套娃刷物品。

实现文件：`MaixrPigmeeDuplicationRecipe`（注册名 `maixr:creative_pigmee_duplication`）、
`NbtCompat`、`data/maixr/recipe/creative_pigmee_duplication.json`。

## Requirements

### Requirement: 复制为一组

配方 SHALL 在合成网格中恰好含 1 只创造猪咪与 1 件非猪咪物品时匹配
（多于一只猪咪或多余目标物品时 MUST 不匹配），
产物 MUST 为目标物品 `copyWithCount(64)`，且创造猪咪被消耗（`remainingItems` 全空）。

#### Scenario: 正常复制

- **WHEN** 合成台放入 1 只创造猪咪 + 1 个钻石
- **THEN** 产出 64 个钻石，猪咪消失

#### Scenario: 多余材料不匹配

- **WHEN** 合成网格中有 2 件非猪咪物品或 2 只猪咪
- **THEN** 配方不匹配

### Requirement: 容器防套娃

目标物品的自定义 NBT（`DataComponents.CUSTOM_DATA`，序列化为字符串扫描）中
MUST NOT 包含任一猪咪 id：`ae2lt:creative_pigmee_fumo`、
`ae2lt:hyperdimensional_pigmee_fumo`、`ae2lt:pigmee_fumo`；
含猪咪的容器物品（箱子/磁盘/抽屉/Mek 箱柜等）一律拒绝复制。

#### Scenario: 装着猪咪的箱子

- **WHEN** 合成目标是一只 NBT 里装着猪咪的容器物品
- **THEN** 合成不成立，产物为空
