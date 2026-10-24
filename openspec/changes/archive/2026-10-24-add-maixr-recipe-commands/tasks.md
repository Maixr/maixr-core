# Tasks

> 说明：命令实现已随远程提交 0a11dd6 落地（`MaixrCommands` + `MaixrMod` 注册），
> 本清单以**规范核对、文档同步与验证**为主。

## 1. 实现核对（对照 spec 逐条验证）

- [ ] 1.1 命令树核对：14 个子命令 + `list-*` 下划线别名全部可用；验证方式：
  runClient `/maixr` 补全列表逐条执行
- [ ] 1.2 取材约定核对：缺副手 →「请把合成产物放到副手」；`list-smelting`
  缺 0 号位 → 对应提示；锻造缺任一格 → 三格齐全提示；控制台执行 → 明确失败
  不抛异常；验证方式：runClient / runServer 双端实测
- [ ] 1.3 输出核对：shaped 含 3 行 pattern + 字母 key；`event.remove` 前缀；
  `.id('maixr:<路径>[_<类型>]')` 规则（shaped/shapeless 无后缀、烹饪带类型、
  模组配方带 `<mod>_<type>`）；点击复制与「点击复制」悬停生效；验证方式：
  runClient 实测各类型各一条
- [ ] 1.4 模组白名单核对：create 7 类 / mekanism 6 类（injecting/purifying 带
  oxygen、metallurgic → metallurgic_infusing + redstone 10）/ ae2 2 类
  （inscriber 带修改注释）；未知 type 报错并列可用清单；验证方式：runClient
  逐类型采样
- [ ] 1.5 查询核对：`recipe-ids minecraft` 前 80 条可点击 + 总数汇总；
  `recipe-dump` 对有效/非法/不存在 id 三种输入行为正确；验证方式：runClient

## 2. 文档同步

- [x] 2.1 归档时更新 `openspec/config.yaml` 上下文：从「已整体移除的系统」
  清单移除「/maixr 指令」，能力清单补 `maixr-recipe-commands` 一行；验证方式：
  文档走查
- [x] 2.2 README 功能概览补「/maixr 配方脚本命令」一行；验证方式：文档走查

## 3. 验证

- [x] 3.1 `./gradlew build` 编译通过；验证方式：构建退出码 0
- [ ] 3.2 脚本可用性抽查：实机生成的 shaped / smelting / create.mixing /
  ae2.charger 样本贴入整合包 `kubejs/server_scripts` 重载无报错、配方生效
  （本模组不加载脚本，验证在整合包侧）；验证方式：整合包实机
- [x] 3.3 `openspec validate --specs --strict` 通过；验证方式：命令退出码 0
