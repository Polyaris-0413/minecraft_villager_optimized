package com.polyaris.village;

import java.util.Set;

import net.minecraft.world.entity.npc.VillagerProfession;

/**
 * 本模组全部可调参数的唯一来源，实现处只引用、不另写字面量。
 * 数值依据见 docs/superpowers/specs/2026-11-04-village-optimizations-design.md。
 */
public final class VillageSettings {
	private VillageSettings() {
	}

	/** 会主动修复铁傀儡的职业。 */
	public static final Set<VillagerProfession> GOLEM_REPAIR_PROFESSIONS = Set.of(
		VillagerProfession.ARMORER,
		VillagerProfession.TOOLSMITH,
		VillagerProfession.WEAPONSMITH
	);

	/** 单次修复恢复的生命值，与原版玩家手持铁锭右键铁傀儡一致。 */
	public static final float GOLEM_REPAIR_AMOUNT = 25.0F;

	/** 村民搜索受伤铁傀儡的半径（格）。 */
	public static final double GOLEM_REPAIR_SEARCH_RADIUS = 16.0;

	/** 村民近身作业的距离（格）。 */
	public static final double GOLEM_REPAIR_REACH = 2.0;

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

	/** 睡眠时每次恢复的生命值。 */
	public static final float SLEEP_REGENERATION_AMOUNT = 1.0F;

	/** 睡眠回血的间隔（tick）。 */
	public static final int SLEEP_REGENERATION_INTERVAL_TICKS = 20;

	public static boolean isGolemRepairer(VillagerProfession profession) {
		return GOLEM_REPAIR_PROFESSIONS.contains(profession);
	}
}
