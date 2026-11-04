# 村民行为优化 · 设计文档

- 项目：`villageoptimized`（Fabric，Minecraft 1.21.1，官方 Mojang 映射）
- 日期：2026-11-04
- 状态：待确认（文末「待拍板」三条给出默认值，无异议即按默认实现）

## 1. 目标

三条彼此独立的小改动，统一放在本模组里：

1. **村民不再踩坏耕地**（仅豁免村民，其它生物保持原版）。
2. **铁傀儡受伤后，附近盔甲匠 / 工具匠 / 武器匠前往修复**，消耗铁锭。
3. **村民睡眠时恢复生命**（原版无此机制，见 §2.4）。

非目标：不做配置文件 / 游戏内 UI；不改交易、掉落、村庄生成；不改变原版其它数值；
不阻止铁傀儡、其它生物踩坏耕地。

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
铁傀儡没有自带回血。

### 2.4 村民没有自动回血

自然回血只存在于玩家：`FoodData#tick` 按饥饿值/饱和度回血，且受
`naturalRegeneration` 游戏规则控制（该规则分类为 `PLAYER`）。`LivingEntity` / `Villager` /
`Mob` 中没有任何按时间自愈的逻辑；村民唯一的回血来源是交易补货后获得的短暂
`REGENERATION` 效果。**所以第 3 条需要实现。**

### 2.5 村民拾取白名单

`Villager#wantsToPickUp` 只接受 `WANTED_ITEMS`（面包、作物、种子等）或
`profession.requestedItems()`；三名铁匠职业的 `requestedItems` 都是空集合，
因此村民默认**不会捡铁锭**。要让"用铁锭修复"成立，必须扩展这个白名单。

另外：村民核心行为包里的 `GoToWantedItem.create(0.5F, false, 4)` 会让村民主动走过去捡
4 格内的"想要物品"，`NearestItemSensor` 的感知范围是 32 格。所以扩白名单后，
玩家把铁锭丢在铁匠身边（或他路过时丢下）就会被捡进背包。

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

铁锭来源同样需要取舍：

- **A（默认）消耗村民背包里的铁锭**：每锭 +25，和原版右键完全一致；并通过扩展
  `wantsToPickUp` 让铁匠主动捡起玩家丢下的铁锭，形成"投喂—修复"的闭环。
- B 不消耗物品，按冷却"凭空"修复：零维护，但和"使用铁锭"的设定脱节。

### 需求三：睡眠回血

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. 睡眠中每 20 tick 回 1 点 | 睡满 20 秒即回满（村民 20 血）；被打醒不会瞬间回满，不能刷血；实现只用 `Villager#tick` | **采用** |
| B. 醒来瞬间 `setHealth(maxHealth)` | 更贴字面，但 `LivingEntity#hurt` 会先 `stopSleeping`，等于"挨打就回满"，睡前 1 血也能瞬间回满 | 不采用 |

## 4. 详细设计

### 4.1 参数（唯一设计源）

所有可调数值集中在 `com.polyaris.village.VillageSettings`，实现处只引用，不另写字面量。

| 参数 | 值 | 依据 |
| --- | --- | --- |
| 修复职业 | `ARMORER` / `TOOLSMITH` / `WEAPONSMITH` | 需求 |
| 每锭恢复 | 25 HP | 原版 `IRON_GOLEM_REPAIR` 数值 |
| 搜索半径 | 16 格 | 村民在村庄内活动的合理感知范围；路径长度仍由 `FOLLOW_RANGE`(48) 限制 |
| 修复距离 | 2 格 | 近身作业，约等于伸手；避免隔墙修 |
| 两次修复间隔 | 20 tick（1 秒） | 有节奏、可视，4 锭修满约 4 秒 |
| 目标复扫间隔 | 20 tick | 避免每个村民每 tick 做一次 AABB 查询 |
| 单次尝试超时 | 600 tick（30 秒） | 目标移动/堵路时不至于无限跟 |
| 不可达放弃 | 60 tick | 读 `CANT_REACH_WALK_TARGET_SINCE`，防止隔墙发呆 |
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

- `checkExtraStartConditions`（每 tick 被调用）：先做过职业/状态/背包的廉价判断，
  再按 20 tick 节流做一次 AABB 查询，选出符合"存活且未满血"的最近铁傀儡。
  若村民正在睡觉、交易，或处于 `PANIC` / `HIDE` / `RAID` 活动，直接不开始。
- `start`：记录开始时间，`LOOK_TARGET` 指向铁傀儡。
- `tick`：
  - 距离 > 2 格：写 `WALK_TARGET = new WalkTarget(new EntityTracker(golem, false), 0.5F, 1)`，
    由原版 `MoveToTargetSink` 寻路；同时 `LOOK_TARGET` 跟随。
  - 距离 ≤ 2 格：擦掉 `WALK_TARGET`（让 `MoveToTargetSink` 停下），每 20 tick 执行一次修复。
  - 修复：从村民 8 格背包扣 1 个铁锭 → `golem.heal(25)` → 在铁傀儡处播放
    `IRON_GOLEM_REPAIR`（音高带原版同款随机抖动）。
- `canStillUse`：目标死亡/移除/满血、背包没铁锭、睡觉或交易、距离超过 16 格、
  超过 600 tick 超时、不可达超过 60 tick —— 任一成立即结束。
- `stop`：清 `WALK_TARGET` / `LOOK_TARGET`，置 100 tick 冷却。

**挂载** `mixin/VillagerGoalPackagesMixin`：`@Inject` 到 `VillagerGoalPackages#getCorePackage`
`RETURN`，把该行为以优先级 1 插到行为包最前面。CORE 是常驻活动，所以工作/闲逛/开会/休息
时都能响应；优先级高于工作、闲逛（5）与交易跟随（3），但低于 `VillagerPanicTrigger`（0）。
婴儿与无职业村民在首个职业判断即返回，开销可忽略。

**供料** `mixin/VillagerMixin#wantsToPickUp`：当堆叠是铁锭、职业属于上述三种、且背包有空位时
返回 `true`。玩家把铁锭丢在铁匠附近（4 格内他会主动走过去，感知范围 32 格），
铁匠会捡起，随后用于修复。

**并发与非浪费**：每次修复前重新检查铁傀儡血量，多村民同时到场也不会为已满血的目标浪费铁锭。

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
    ├── VillagerMixin.java                   # 需求二（拾取）+ 需求三（睡眠回血）
    └── VillagerGoalPackagesMixin.java       # 需求二（挂载）
src/main/resources/villageoptimized.mixins.json  # 注册新 mixin
docs/superpowers/specs/2026-11-04-village-optimizations-design.md
```

## 6. 验证

1. `gradlew build` 通过（编译 + mixin 配置校验）。
2. 启动开发服务器，确认 mixin 全部应用、无 `Mixin apply failed`、运行 30 秒无异常。
3. 游戏内手动验收清单：
   - 让村民从 1 格高处落到耕地上多次 → 耕地保持耕地，且仍会受到摔落伤害；
   - 铁傀儡保持不被误伤，铁傀儡落地仍会把耕地踩成泥土（验证"仅限村民"）；
   - 丢 1 个铁锭给武器匠 → 他会捡起；打伤铁傀儡 → 他走到身边，每秒消耗 1 锭、+25 血，
     修满即停；背包没有铁锭时不会修；
   - 夜里观察受伤村民睡觉 → 生命值每秒 +1，天亮时已满。
4. 每次改动后 git 提交（项目当前无仓库，先 `git init` 并提交模板基线）。

## 7. 风险与限制

- 仅支持 1.21.1：mixin 目标使用官方映射方法名，跨版本会失效。
- 与其它改写村民 Brain 行为包的模组可能叠加冲突（本模组只往 CORE 包前插一条行为，风险低）。
- 铁锭需玩家投喂，不投喂则不会修复；若希望支持"自动去捡远处铁锭/从箱子取锭"，属于后续增强。

## 8. 待拍板

1. 铁锭来源：**默认 A**（消耗村民背包铁锭 + 铁匠会捡玩家丢下的铁锭）。
   备选 B：不消耗物品、按冷却凭空修复。
2. 睡眠回血：**默认 A**（睡眠中每秒 +1，睡满 20 秒回满）。备选 B：醒来瞬间回满。
3. 数值：搜索 16 格 / 近身 2 格 / 每锭 +25 / 每秒 1 锭 / 结束后冷却 5 秒 —— 是否照此实现。
