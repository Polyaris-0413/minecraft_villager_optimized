package com.polyaris.villager;

import java.util.Set;

import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 本模组全部可调参数与行为开关的唯一来源，实现处只引用、不另写字面量。
 * 数值与原版对齐（单个铁锭 25 HP、睡眠 1 HP/秒），其余为手感取值。
 */
public final class VillagerSettings {
	private VillagerSettings() {
	}

	/** 会主动修复铁傀儡的职业。 */
	public static final Set<VillagerProfession> GOLEM_REPAIR_PROFESSIONS = Set.of(
		VillagerProfession.ARMORER,
		VillagerProfession.TOOLSMITH,
		VillagerProfession.WEAPONSMITH
	);

	/** 这些活动期间不修铁傀儡：逃跑、袭击、袭击前、躲藏。 */
	public static final Set<Activity> GOLEM_REPAIR_BLOCKED_ACTIVITIES = Set.of(
		Activity.PANIC,
		Activity.RAID,
		Activity.PRE_RAID,
		Activity.HIDE
	);

	/** 修复行为在村民 CORE 行为包里的优先级：1 高于工作、闲逛（5），低于恐慌（0）。 */
	public static final int GOLEM_REPAIR_BEHAVIOR_PRIORITY = 1;

	/** 行为的兜底时长（tick）；真正是否继续由 canStillUse 判断。 */
	public static final int GOLEM_REPAIR_BEHAVIOR_DURATION_TICKS = 1200;

	/** 单次修复恢复的生命值，与原版玩家手持铁锭右键铁傀儡一致。 */
	public static final float GOLEM_REPAIR_AMOUNT = 25.0F;

	/** 修复期间村民手上显示的物品（纯视觉，不消耗）。 */
	public static final Item GOLEM_REPAIR_HELD_ITEM = Items.IRON_INGOT;

	/** 村民搜索受伤铁傀儡的半径（格）。 */
	public static final double GOLEM_REPAIR_SEARCH_RADIUS = 16.0;

	/** 村民近身作业的距离（格）。 */
	public static final double GOLEM_REPAIR_REACH = 2.0;

	/** 走向铁傀儡时，离目标多近算“到了”（格）。 */
	public static final int GOLEM_REPAIR_WALK_CLOSE_ENOUGH = 1;

	/** 两次修复动作的间隔（tick）。 */
	public static final int GOLEM_REPAIR_ACTION_INTERVAL_TICKS = 20;

	/** 没有可修目标时重新扫描的间隔（tick），避免每个村民每 tick 做实体查询。 */
	public static final int GOLEM_REPAIR_SCAN_INTERVAL_TICKS = 20;

	/** 单次修复尝试的最长跟随时间（tick）。 */
	public static final int GOLEM_REPAIR_ATTEMPT_TIMEOUT_TICKS = 600;

	/** 寻路判定不可达后允许继续尝试的时间（tick），超过就放弃，避免隔墙反复寻路。 */
	public static final int GOLEM_REPAIR_UNREACHABLE_GIVE_UP_TICKS = 60;

	/** 一次修复结束后的冷却（tick），避免刚修完立刻重新选中目标。 */
	public static final int GOLEM_REPAIR_RETRY_COOLDOWN_TICKS = 100;

	/** 走向铁傀儡的速度修正，与村民工作、闲逛一致。 */
	public static final float GOLEM_REPAIR_WALK_SPEED = 0.5F;

	/** 修复音效音量，与原版 IronGolem#mobInteract 一致。 */
	public static final float GOLEM_REPAIR_SOUND_VOLUME = 1.0F;

	/** 修复音效音高的随机抖动幅度，与原版 IronGolem#mobInteract 一致。 */
	public static final float GOLEM_REPAIR_SOUND_PITCH_JITTER = 0.2F;

	/** 睡眠时每次恢复的生命值。 */
	public static final float SLEEP_REGENERATION_AMOUNT = 1.0F;

	/** 睡眠回血的间隔（tick）。 */
	public static final int SLEEP_REGENERATION_INTERVAL_TICKS = 20;

	public static boolean isGolemRepairer(VillagerProfession profession) {
		return GOLEM_REPAIR_PROFESSIONS.contains(profession);
	}
}
