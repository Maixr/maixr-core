# 本轮改动：停用天空盒 + ProjectE 降为可选

## 一、天空盒已全局停用

`MaixrEdenSky`（动态天色 / 雾色 / 粒子）**整块关闭**：

```java
public static final boolean ENABLED = false;   // 改 true 即可重新启用
```

三个事件入口（`onClientTick` / `onFogColor` / `onRenderStage`）都以它为先决条件，
关掉之后零开销。**代码全部保留** —— 想恢复只改这一个常量。

### 为什么停用
它原本是给破碎世界做的氛围效果，判定依据是**维度**（`overworld_broken`）。
但玩家的预期是"世界类型"，两者对不上，导致普通世界也被染色。
氛围不是必需品，所以整块关掉。

---

## 二、ProjectE 已降为可选依赖

### 原来的问题

两个类**编译期引用 ProjectE**：

| 文件 | 引用方式 | 后果 |
|---|---|---|
| `MaixrDiscountedTransmutationContainer` | `extends TransmutationContainer` | **继承是编译期绑定** —— PE 缺失时光加载这个类就 NoClassDefFoundError，整个模组崩 |
| `ProjectEBridge` | `import ...PECapabilities` | 同上 |

### 现在怎么改的

**1) MaixrDiscountedTransmutationContainer → 动态代理**

不再继承，改用 java.lang.reflect.Proxy：

```java
// 1. 反射构造 ProjectE 的真实容器
Object real = cls.getConstructor(int.class, Inventory.class, InteractionHand.class, int.class)
                 .newInstance(containerId, inv, hand, selected);
// 2. 包一层代理，只拦 clickPostValidate，其余原样转发
return Proxy.newProxyInstance(loader, interfaces, new DiscountHandler(real, sellRate));
```

**效果完全一致**（还是量点击前后的 EMC 差值来扣折扣），
但本类里没有任何 ProjectE 类型出现在签名或 import 里。

**2) ProjectEBridge → 纯反射**

类名以字符串出现，方法查找失败就静默返回 null：

```java
Class.forName("moze_intel.projecte.api.capabilities.PECapabilities")
```

**3) 物品行为（选项 B）**

三个转化桌仍然注册，ProjectE 缺失时右键提示：

```
[转化桌] 需要安装 等价交换（ProjectE） 才能使用。
```

---

## 三、已删除的模组

```
mods/[等价交换重制版] ProjectE-1.21.1-PE1.1.0.jar     <- 已删
mods/[等价元件] projectcell-1.21.1-1.0.3.jar          <- 已删
```

**备份在** `D:\cfpatch\backups\projecte_removed\`（想装回来直接拷回去）。

---

## 四、验证结果

```
jar 里 moze_intel/projecte 类引用：  0 处  OK（全走反射）
模组里硬依赖 projecte（mandatory）：  0 个  OK
```

**注意**：有 3 个模组引用了 projecte 的类，但都是可选集成（非硬依赖），不会导致加载失败：

| 模组 | 用途 |
|---|---|
| ExtendedAE | 数据生成器里引用 |
| Mekanism | CraftTweaker 集成 |
| data_energistics | 魔法集成 |

它们检测不到 PE 会自动跳过对应集成。

---

## 五、相关代码

| 文件 | 改动 |
|---|---|
| `client/MaixrEdenSky.java` | 加 ENABLED = false，三入口加开关 |
| `MaixrDiscountedTransmutationContainer.java` | 继承 → 动态代理（零编译期依赖） |
| `ProjectEBridge.java` | 编译期 API → 纯反射 |
| `MaixrTransmutationTabletItem.java` | 加 isLoaded() 检查 + 缺失提示 |

---

## 六、如果要恢复 ProjectE

1. 把备份的两个 jar 拷回 mods/
2. **不用改代码** —— 反射会自动找到 PE 的类，转化桌立刻可用（含贬值功能）

这就是反射实现的好处：装或不装，代码都不用动。
