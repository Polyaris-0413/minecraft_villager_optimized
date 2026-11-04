# 村民行为优化 · 设计文档

- 项目：`villageoptimized`（Fabric，Minecraft 1.21.1，官方 Mojang 映射）
- 日期：2026-11-04
- 状态：**已确认**——铁锭方案 B（不消耗物品）、睡眠回血方案 A（睡眠中每秒 +1）、数值按 §4.1

## 1. 目标

三条彼此独立的小改动，统一放在本模组里：

1. **村民不再踩坏耕地**（仅豁免村民，其它生物保持原版）。
2. **铁傀儡受伤后，附近盔甲匠 / 工具匠 / 武器匠前往修复**。
3. **村民睡眠时恢复生命**（原版无此机制，见 §2.4）。

非目标：不做配置文件 / 游戏内 UI；不改交易、掉落、村庄生成；不改变原版其它数值；
不阻止铁傀儡、其它生物踩坏耕地。模组定位为服务端 MOD，没有客户端入口或自定义渲染代码
（村民手持铁锭直接复用原版 `CrossedArmsItemLayer`）。

## 2. 原版现状调研（1.21.1 反编译源码核对）

以下结论来自 1.21.1 官方映射反编译源码，是方案选择的依据。

### 2.1 耕地被踩坏的唯一路径

`net.minecraft.world.level.block.FarmBlock#fallOn` 是唯一可能带实体触发踩踏的入口，
它内部调用静态方法 `FarmBlock.turnToDirt(entity, state, level, pos)`（`turnToDirt` 的另外
三处调用都在 `tick` / `randomTick` / `DirtPathBlock`，实参为 `null`）。触发条件：

- `fallDistance > 0.5` 且随机数通过；
- 实体是 `LivingEntity`，且是玩家或 `mobGriefing` 开启；
- 碰撞体积 `宽×宽×高 > 0.512`（村民 0.6×0.6×1.95 ≈ 0.70，满足）。

村民只在"从高处落到耕地上"时才会踩坏耕地（走下台阶、跳跃落点），并不会因为站上去就踩坏。
因此只需在这一处对村民放行即可，且**必须保留摔落伤害**（`fallOn` 末尾还会走
`Block#fallOn` → `entity.causeFallDamage`）。

### 2.2 村民 AI 已改为 Brain（关键约束）

1.21.1 的 `Villager` 没有 `GoalSelector` 可用了：它实现 `brainProvider` / `makeBrain` /
`registerBrainGoals`，行为包由 `VillagerGoalPackages` 的若干 `getXxxPackage` 静态方法生成，
再 `brain.addActivity(Activity.X, ...)`。`Activity.CORE` 在 `setCoreActivities` 中登记，
**所有活动期间都持续运行**（`Brain#setActiveActivity` 会把 coreActivities 一起放进
activeActivities）。

结论：新增 AI 行为必须写成 `Behavior<Villager>` 并挂进 Brain 行为包，不能沿用旧版 Goal。
直接调 `navigation.moveTo` 会被 Brain 每 tick 派发的 `WALK_TARGET` 覆盖，出现抖动，排除。

### 2.3 铁傀儡修复的原版数值

`IronGolem#mobInteract`：玩家手持铁锭右键 → `heal(25.0F)`（铁傀儡满血 100），
播放 `SoundEvents.IRON_GOLEM_REPAIR`，消耗 1 个铁锭。修复量即每锭 +25 点生命。
铁傀儡没有自带回血。Minecraft Wiki 同样记为"一个铁锭恢复 25 HP"。

### 2.4 村民没有自动回血

自然回血只存在于玩家：`FoodData#tick` 按饥饿值/饱和度回血（饱食度 ≥18 时每 80 tick 回 1 点，
有饱和度时每 10 tick 回 1 点），且受 `naturalRegeneration` 游戏规则控制（该规则分类为
`PLAYER`）。`LivingEntity` / `Villager` / `Mob` 中没有任何按时间自愈的逻辑；村民唯一的
回血来源是交易补货后获得的短暂 `REGENERATION` 效果。**所以第 3 条需要实现。**

## 3. 方案取舍

### 需求一：村民不踩坏耕地

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. `@Redirect` 掉 `fallOn` 里的 `turnToDirt` 调用，村民时跳过 | 只掐掉踩踏这一条逻辑，摔落伤害、`pushEntitiesUp` 等原版行为全部保留 | **采用** |
| B. `HEAD` 取消 `fallOn` 并手动补摔落伤害 | 需要复制 `Block#fallOn` 的 `causeFallDamage` 逻辑，版本升级易漂移 | 不采用 |
| C. 监听方块变化做补偿 | 无法区分原因，且会产生额外方块更新 | 不采用 |

### 需求二：铁匠修铁傀儡

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. 自定义 `Behavior<Villager>` 注入 `Activity.CORE` | 与村民原生 AI 同构：写 `WALK_TARGET` / `LOOK_TARGET` 记忆，由原版 `MoveToTargetSink` 驱动寻路；工作、闲逛、开会、休息时都能响应 | **采用** |
| B. 全局 tick 轮询 + 直接调 `navigation.moveTo` | 代码略少，但与原版行为抢导航，且无法与交易/恐慌等状态自然互斥 | 不采用 |
| C. 加旧版 Goal | 1.21.1 村民已无 Goal 体系 | 不可行 |

修复的"代价"两种做法都讨论过：

- 消耗村民背包里的铁锭（需要额外扩展拾取白名单，并让玩家投喂）。
- **不消耗物品，只用职业能力按节奏修复（采用）**：零维护，村民到场后每 20 tick 恢复 25 点生命。

### 需求三：睡眠回血

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. 睡眠中每 20 tick 回 1 点 | 睡满 20 秒即回满（村民 20 血）；被打醒不会瞬间回满，不能刷血；实现只用 `Villager#tick` | **采用** |
| B. 醒来瞬间 `setHealth(maxHealth)` | 更贴字面，但 `LivingEntity#hurt` 会先 `stopSleeping`，等于"挨打就回满"，睡前 1 血也能瞬间回满 | 不采用 |

## 4. 详细设计

### 4.1 参数（唯一设计源）

所有可调数值集中在 `com.polyaris.village.VillageSettings`，实现处只引用，不另写字面量。
数值来源与对照（2026-11-04 联网核对）：

- **铁傀儡 25 HP/次**：原版行为（`IronGolem#mobInteract` 的 `heal(25.0F)`），
  [Minecraft Wiki](https://minecraft.wiki/w/Iron_Golem) 同记。
- **睡眠回血 1 HP/秒**：与现成的同类实现完全一致 ——
  [Villagers Regeneration](https://modrinth.com/mod/villagers-regeneration)（13,679 次下载，支持 1.21.1）
  明确写"村民睡觉时每秒恢复 1 点生命值"。
- **搜索半径 16 格**：没有权威推荐值；村民 `FOLLOW_RANGE=48` 是寻路长度上限，
  原版感知半径常见 8–16，取 16。
- **同类参考**：[Helpful Armorers](https://modrinth.com/mod/helpful-armorers)（Fabric，1.21.1+）
  也做"盔甲匠修铁傀儡"，但未公开具体数值；它额外限制"敌对生物在附近 / 夜晚 / 受伤时不修"，
  本模组未采用，属于可选增强。


| 参数 | 值 | 依据 |
| --- | --- | --- |
| 修复职业 | `ARMORER` / `TOOLSMITH` / `WEAPONSMITH` | 需求 |
| 每次修复恢复 | 25 HP | 原版 `IRON_GOLEM_REPAIR` 数值 |
| 搜索半径 | 16 格 | 村民在村庄内活动的合理感知范围；路径长度仍由 `FOLLOW_RANGE`(48) 限制 |
| 修复距离 | 2 格 | 近身作业，约等于伸手；避免隔墙修 |
| 修复间隔 | 20 tick（1 秒） | 有节奏、可视，4 次修满约 4 秒 |
| 目标复扫间隔 | 20 tick | 避免每个村民每 tick 做一次 AABB 查询 |
| 单次尝试超时 | 600 tick（30 秒） | 目标移动/堵路时不至于无限跟 |
| 不可达放弃 | 60 tick | 读 `CANT_REACH_WALK_TARGET_SINCE`，防止隔墙反复寻路 |
| 结束后冷却 | 100 tick（5 秒） | 防止刚修完又立刻重新选中 |
| 行走速度修正 | 0.5 | 与村民工作/闲逛一致 |
| 睡眠回血 | 每 20 tick +1 HP | 见 §3 需求三 |

### 4.2 需求一实现

`mixin/FarmBlockMixin`：`@Redirect` `FarmBlock#fallOn` 内的 `turnToDirt` 调用，
`entity instanceof Villager` 时直接返回，其余实体照常调用。

- 保留摔落伤害（`super.fallOn` 不受影响）。
- 与 `mobGriefing` 无关：即使开启，村民也不再踩坏耕地。
- 全部方块状态（湿润/干燥耕地）一视同仁。

### 4.3 需求二实现

**新行为** `com.polyaris.village.ai.RepairIronGolemBehavior extends Behavior<Villager>`：

- `checkExtraStartConditions`（每 tick 被调用）：先做过职业/状态判断，
  再按 20 tick 节流做一次 AABB 查询，选出"存活且未满血"的最近铁傀儡。
  若村民正在睡觉、交易，或处于 `PANIC` / `RAID` / `PRE_RAID` / `HIDE` 活动，直接不开始。
- `start`：记录开始时间与初始的"不可达时间戳"（旧目标留下的时间戳不算数），
  并把主手临时换成铁锭（`VillageSettings.GOLEM_REPAIR_HELD_ITEM`，只是视觉，不扣物品）。
- `tick`：
  - 距离 > 2 格：写 `WALK_TARGET = new WalkTarget(new EntityTracker(golem, false), 0.5F, 1)`，
    由原版 `MoveToTargetSink` 寻路；`LOOK_TARGET` 跟随铁傀儡。
  - 距离 ≤ 2 格：擦掉 `WALK_TARGET`（让 `MoveToTargetSink` 停下），每 20 tick 修复一次：
    `golem.heal(25)`，并在铁傀儡处播放 `IRON_GOLEM_REPAIR`（音高与原版同款随机抖动）。
- `canStillUse`：目标死亡/移除/满血、睡觉或交易、距离超过 16 格、超过 600 tick、
  或寻路判定不可达超过 60 tick —— 任一成立即结束。
- `stop`：清 `WALK_TARGET` / `LOOK_TARGET`，把主手物品换回原来的，置 100 tick 冷却。

**挂载** `mixin/VillagerGoalPackagesMixin`：`@Inject` 到 `VillagerGoalPackages#getCorePackage`
`RETURN`，把该行为以优先级 1 插到行为包最前面。CORE 是常驻活动，所以工作/闲逛/开会/休息
时都能响应；优先级高于工作、闲逛（5）与交易跟随（3），但低于 `VillagerPanicTrigger`（0）。
婴儿与其它职业在首个职业判断即返回，开销可忽略。

**非浪费**：每次修复前都重新检查铁傀儡血量，多村民同时到场也不会把 25 HP 打空。

### 4.4 需求三实现

`mixin/VillagerMixin#tick`：`isSleeping()` 且 `tickCount % 20 == 0` 且未满血时
`heal(1.0F)`。不回满就不回满——被打醒、床被拆都不会白送满血。

## 5. 交付物

```
src/main/java/com/polyaris/village/
├── VillageSettings.java                     # 唯一设计源
├── ai/RepairIronGolemBehavior.java          # 需求二
└── mixin/
    ├── FarmBlockMixin.java                  # 需求一
    ├── VillagerMixin.java                   # 需求三
    └── VillagerGoalPackagesMixin.java       # 需求二（挂载）
src/main/resources/villageoptimized.mixins.json  # 注册新 mixin
src/gametest/java/com/polyaris/village/gametest/VillageOptimizedGameTest.java  # 三条机制的 GameTest
src/gametest/resources/fabric.mod.json       # 测试模组（fabric-gametest 入口）
build.gradle                                  # fabricApi.configureTests 配置
docs/superpowers/specs/2026-11-04-village-optimizations-design.md
```

## 6. 验证

1. `gradlew build` 通过（编译 + mixin 配置校验；`check` 会顺带跑 GameTest）。
2. `gradlew runGameTest` 跑 `src/gametest` 下的 3 个服务端 GameTest（空结构 + 自铺地面）：
   - `villagersDoNotTrampleFarmland`：村民落地后耕地保留，僵尸落地后变泥土（对照组）；
   - `smithsRepairDamagedGolem`：工具匠把 40 HP 的铁傀儡修到 40 以上；
   - `sleepingVillagersRegenerateHealth`：夜里受伤村民躺床后血量回升。
3. 启动开发服务器，确认 mixin 全部应用、无 `Mixin apply failed`、运行一段时间无异常。
4. 游戏内手动验收清单（人员手测，见下方验证记录）：
   - 让村民从 1 格高处落到耕地上多次 → 耕地保持耕地，且仍会受到摔落伤害；
   - 铁傀儡落地仍会把耕地踩成泥土（验证"仅限村民"）；
   - 打伤铁傀儡 → 盔甲匠/工具匠/武器匠走到身边，每秒 +25 血，修满即停；
     非铁匠职业、正在交易的村民不会前往；
   - 夜里观察受伤村民睡觉 → 生命值每秒 +1，天亮时已满。

### 验证记录（2026-11-04，本机 dev server + RCON + GameTest）

| 项目 | 操作 | 结果 |
| --- | --- | --- |
| 编译 | `gradlew build` | BUILD SUCCESSFUL（含 3 个 GameTest） |
| GameTest | `gradlew runGameTest` | All 3 required tests passed |
| mixin | `gradlew runServer` 启动 | 无 `Mixin apply failed`、无异常 |
| 耕地保护 | 村民与僵尸分别从 3 格高处落到耕地 | 村民脚下仍是 farmland；僵尸脚下变成 dirt（对照组，证明测试有效） |
| 铁匠修复 | 武器匠 + 6 格外的 40 HP 铁傀儡 | 约 2–3 秒修到 100 HP，之后不再变化 |
| 职业过滤 | 农民 + 6 格外的 40 HP 铁傀儡 | 6 秒内血量保持 40 |
| 范围过滤 | 工具匠 + 20 格外的 40 HP 铁傀儡 | 6 秒内血量保持 40 |
| 睡眠回血 | 睡在床上的 5 HP 村民 | 约 7 秒后 12 HP，继续睡至 20 HP 封顶 |


## 7. 风险与限制

- 仅支持 1.21.1：mixin 目标使用官方映射方法名，跨版本会失效。
- 与其它改写村民 Brain 行为包的模组可能叠加冲突（本模组只往 CORE 包前插一条行为，风险低）。
- 修复不消耗铁锭，属于纯增益（手上的铁锭只是视觉）；若以后想加消耗，可在 `repair()` 里补扣物品与拾取白名单。
