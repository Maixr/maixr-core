# Maixr Core

NeoForge **1.21.1** 整合包《Project Negentropy》的核心模组。

- **modid**：`maixr`
- **版本**：2.0.0
- **依赖**：Minecraft 1.21.1、NeoForge（mods.toml 要求 ≥21.1.0，开发环境 21.1.234）、
  **AE2 19.2+**（必需，玩偶接网络功能）；
  Curios（可选，缺失时佩戴自动回退原版头盔槽）、
  SkyblockBuilder（可选，缺失时 `maixr:overworld_broken` 维度加载失败，其余功能不受影响）

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

### 4. 玩偶（fumo）

`maixr:maixr_skin_doll` 与 `maixr:truth_doll`

- 各占 **1 个频道**，接入后显示为在线设备；
- 接入期间**每分钟产出 1 个自己**，直接写入网络存储；
- 存储满时每 5 秒重试一次，不会丢产出；
- 没有频道 / 网络没电时暂停计时；
- 佩戴（头盔 / Curios 头饰槽；Curios 头饰槽标签仅注册了 Maixr 玩偶）时
  Maixr 玩偶给**夜视**；
- 真理玩偶在**头盔槽 / Curios 头饰槽 / 副手 / 主手**任一位置时，
  死亡触发**不死图腾保护**（生命值 1 + 再生 II + 吸收 II）并消耗 1 个；
- 可切换**旋转动画**：放置的玩偶空手右键、佩戴中空手右键空气。

### 5. 致回忆中的明日（流萤/萨姆双形态玩偶，`maixr:memory_tomorrow_doll`）

崩坏：星穹铁道流萤主题玩偶，完整继承玩偶体系（占 1 频道 / 每分钟产出自己 /
可佩戴 / 可切换旋转动画），另有专属玩法：

- **双形态外观**：初始**流萤**，变身时变为**萨姆**（物品、放置方块、玩家皮肤三处同步切换，
  周围玩家也能看到换肤）；放置态**蹲下右键**在流萤/萨姆外观间切换（仅视觉，挖掘仍掉落流萤）；
- **变身**：手持右键（不蹲下），或**佩戴时按下快捷键**（默认 R，可在控制设置改键）触发：
  播放"我将点燃星海"语音、获得 **10 秒创造飞行**（立即起飞）+ **速度 II**；
  到期自动还原（收回飞行、皮肤还原、播放流萤大招结束音效）；
- **冷却 30 秒**：从触发瞬间起算，物品上有原版冷却条；
- 佩戴无被动效果（夜视/图腾保护仍专属前两只玩偶）。

### 6. 创造猪咪复制配方

合成台内 1 只创造猪咪（`ae2lt:creative_pigmee_fumo`）+ 1 件任意物品 →
复制为一整组（64 个）。与 ae2lt 原版不同：**猪咪会被消耗**；
凡是"NBT 里藏着猪咪"的容器物品一律拒绝复制，防套娃刷物品。

---

## 历史

早期版本还包含伊甸工程（世界修复/提交池/纪元）、裂隙抉择、弧光 Boss、
转化桌、故事物品、虚空凝尘等系统 —— 已整体移除，设计文档见 git 历史。

早期的人造排污鸟巢 / 粉红鸟巢多方块机器（Forge 1.20.1 旧代码，原放在 `_deferred_nest/`）
已**整体删除** —— 它们依赖已被 NeoForge 移除的 `LazyOptional` 能力体系，
旧代码见 git 历史；如需重做须按 NeoForge 的能力注册方式整套重写。

---

## 项目规范（OpenSpec）

本工程用 [OpenSpec](https://github.com/Fission-AI/OpenSpec) 管理规范与变更：

- `openspec/config.yaml` —— 项目约定（技术栈、代码硬规则、文档规范）；
- `openspec/specs/` —— 现有能力规范（`broken-world-type` / `doll-ae2-network` / `pigmee-duplication`），
  改动对应行为前先改规范；
- `openspec/changes/` —— 变更提案（`/opsx:propose` 发起，归档后进 `changes/archive/`）；
- 校验：`openspec validate --specs --strict`。

设计与排查文档在 `_docs/`：[玩偶接入AE2](\_docs/玩偶接入AE2.md)、[破碎世界](\_docs/破碎世界.md)。

---

## 构建

本工程使用 **ModDevGradle 2.0.146** + JDK 21（本机 JDK：`C:\Users\Administrator\.jdks\ms-21.0.12`，
已配好 `JAVA_HOME`；`build_run.cmd` 未设 `JAVA_HOME` 时自动回退到该路径）。

```bash
./gradlew build          # 网络可达：自动从 gradle.org / maven.neoforged.net / mavenCentral 在线解析
./gradlew build --offline  # 离线：需先备好 _maven/ 本地镜像（存在才启用，见 settings.gradle）
```

产物：`build/libs/MaixrCore-1.21.1-2.0.0.jar`

> `maven.neoforged.net` 在旧开发机不可达，因此 `settings.gradle` 保留了 `_maven`
> 本地镜像优先的机制 —— **`_maven/` 目录存在时才启用**并摘掉 neoforged.net 远程仓库
> （完全离线构建）；目录不存在时自动走官方仓库在线解析。`_maven/` 与 `libs/`
> 均在 `.gitignore` 中排除（体积大 / 第三方版权），需自行准备。

### 编译与开发运行依赖（放在 `libs/`，已配好）

| jar | 来源（Modrinth，NeoForge 1.21.1） | 用途 |
|---|---|---|
| `appliedenergistics2-19.2.18.jar` | ae2 | 玩偶接入 ME 网络（mods.toml 必需依赖） |
| `guideme-21.1.19.jar` | guideme | AE2 的必需传递依赖 |
| `curios-neoforge-9.5.1+1.21.1.jar` | curios | 玩偶佩戴（头饰槽） |
| `[空岛建立者] SkyblockBuilder-21.1.37.jar` | skyblock-builder | 破碎空岛生成器 |
| `LibX-1.21.1-6.0.15.jar` | libx | SkyblockBuilder 的必需传递依赖 |

> 重建 `libs/` 时从 Modrinth 按上表版本号下载对应文件并重命名即可（文件名与
> `build.gradle` 里的 `devModJars` 声明一致，SkyblockBuilder 需加 `[空岛建立者] ` 前缀）。

### 开发运行（`gradlew runClient`）

★ FML 在 dev 环境只从 `run/mods/` 文件夹发现模组 —— classpath 上的 jar
**不会被当作模组**（`additionalRuntimeClasspath` 实测无效，踩过的坑）。
`build.gradle` 里的 `syncDevMods` 任务挂在 runClient / runServer 之前，
自动把上面 5 个 jar 同步到 `run/mods/`，直接 `./gradlew runClient` 即可。

---

## 许可

见 [LICENSE](LICENSE)。
