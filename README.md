# Maixr Core

NeoForge **1.21.1** 整合包《Project Arc Light》的核心模组。

- **modid**：`maixr`
- **版本**：2.0.0
- **依赖**：NeoForge 21.1.234+、Minecraft 1.21.1、**AE2 19.2+**（玩偶接网络功能）

---

## 主要功能

### 1. 破碎世界类型（`maixr:broken`）

一个"越走越碎"的主世界：

| 距离出生点 | 虚空比例 |
|---|---|
| 0（出生点） | **20%** |
| 约 400 格 | 57.5% |
| **约 800 格（50 区块）** | **95%** |
| 更远 | 恒定 95% |

判定用坐标派生的确定性哈希，不依赖世界种子 —— 同一坐标永远同一结果。

### 2. 破碎结构（天空结构）

「破碎」世界里，天空（y ≥ 201）会随机悬浮**任意已注册结构**（原版 + 全部模组），
平均每 100 区块一座。走的是原版 `StructureStart` 注册路线，跨区块放置由原版保证。

> **天空结构不带地基**：村庄/前哨站/要塞这类带 `terrain_adaptation` 的结构，
> 其地形适配（Beardifier）会在 BIOMES 阶段被临时屏蔽，避免在虚空里摊成
> 两三格厚的浮空薄板。实现见 `SkyStructurePlacer.maskSkyReferences`。

### 3. 空域浮岩

y=110~170 的空域随机漂浮**陨石团簇**（可粉碎成 AE2 陨石粉）与**粗矿块**
（铁/铜/金/锌/锇/铅/锡/铀/硅岩/银/钨/铝/钛/粗钢，按已装模组自动过滤）。

### 4. 玩偶接入 AE2 ME 网络

`maixr:maixr_skin_doll` 与 `maixr:truth_doll` 可作为 AE2 网络设备接入：

- 各占 **1 个频道**，接入后显示为在线设备；
- 接入期间**每分钟产出 1 个自己**，直接写入网络存储；
- 存储满时每 5 秒重试一次，不会丢产出；
- 没有频道 / 网络没电时暂停计时。

### 5. 其它

- **破碎空岛**：出生在 (0,160,0) 的悬浮小岛，世界出生点/复活点都落在岛上。
- **伊甸修复引擎**：从取样源世界把地形/方块搬回破碎世界。
- **弧光 Boss**（`ArcLightBoss`）、转化桌、真理图腾等。

---

## 构建

本工程使用 **ModDevGradle 2.0.146** + JDK 21。

```bash
# 1) 准备本地依赖
#    - _maven/     ：本地 Maven 镜像（Gradle 插件与 NeoForge 构件）
#    - libs/       ：第三方模组 jar（compileOnly）
# 2) 构建
./gradlew build
```

产物：`build/libs/MaixrCore-1.21.1-2.0.0.jar`

> 由于 `maven.neoforged.net` 在开发环境不可达，`settings.gradle` 里把
> 本地 `_maven` 镜像排在最前。`_maven/` 与 `libs/` 已在 `.gitignore` 中排除
> （体积大 / 第三方版权），需要自行准备。

### 编译期依赖（放在 `libs/`）

| jar | 用途 |
|---|---|
| `appliedenergistics2-19.2.18.jar` | 玩偶接入 ME 网络 |
| `[空岛建立者] SkyblockBuilder-21.1.37.jar` | 破碎空岛生成器 |
| `[等价交换重制版] ProjectE-1.21.1-PE1.1.0.jar` | 转化桌 |
| `curios-neoforge-9.5.1+1.21.1.jar` | 饰品佩戴 |
| `geckolib-neoforge-1.21.1-4.9.3.jar` | 模型动画 |
| `jei-1.21.1-neoforge-19.57.0.449.jar` | JEI 集成 |

---

## 许可

见 [LICENSE](LICENSE)。
