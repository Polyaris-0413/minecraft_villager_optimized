package com.polyaris.village.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;
import com.polyaris.village.VillageSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * 铁匠职业的村民在附近铁傀儡受伤时前往修复。
 * 挂在 {@link net.minecraft.world.entity.ai.behavior.VillagerGoalPackages} 的 CORE 行为包里，
 * 由原版的 MoveToTargetSink 负责实际寻路。
 */
public class RepairIronGolemBehavior extends Behavior<Villager> {
	/** Behavior 自带时长的兜底值，真正是否继续由 {@link #canStillUse} 决定。 */
	private static final int FALLBACK_DURATION_TICKS = 1200;

	@Nullable
	private IronGolem golem;
	private long attemptStartTime;
	private long nextActionTime;
	private long nextScanTime;
	private long unreachableSinceAtStart = Long.MIN_VALUE;

	public RepairIronGolemBehavior() {
		super(ImmutableMap.of(), FALLBACK_DURATION_TICKS);
	}

	@Override
	protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
		long now = level.getGameTime();
		if (now < this.nextScanTime) {
			return false;
		}

		this.nextScanTime = now + VillageSettings.GOLEM_REPAIR_SCAN_INTERVAL_TICKS;
		if (!VillageSettings.isGolemRepairer(villager.getVillagerData().getProfession()) || isUnavailable(villager)) {
			return false;
		}

		this.golem = findHurtGolem(level, villager);
		return this.golem != null;
	}

	@Override
	protected void start(ServerLevel level, Villager villager, long now) {
		this.attemptStartTime = now;
		this.nextActionTime = now;
		// 旧目标留下的不可达时间戳不能算到本次尝试头上
		this.unreachableSinceAtStart = villager.getBrain()
			.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
			.orElse(Long.MIN_VALUE);
	}

	@Override
	protected boolean canStillUse(ServerLevel level, Villager villager, long now) {
		if (this.golem == null || !this.golem.isAlive() || this.golem.isRemoved()) {
			return false;
		}

		if (this.golem.getHealth() >= this.golem.getMaxHealth() || isUnavailable(villager)) {
			return false;
		}

		if (villager.distanceToSqr(this.golem) > square(VillageSettings.GOLEM_REPAIR_SEARCH_RADIUS)) {
			return false;
		}

		if (now - this.attemptStartTime > VillageSettings.GOLEM_REPAIR_ATTEMPT_TIMEOUT_TICKS) {
			return false;
		}

		Optional<Long> unreachableSince = villager.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
		return unreachableSince.isEmpty()
			|| unreachableSince.get() == this.unreachableSinceAtStart
			|| now - unreachableSince.get() <= VillageSettings.GOLEM_REPAIR_UNREACHABLE_GIVE_UP_TICKS;
	}

	@Override
	protected void tick(ServerLevel level, Villager villager, long now) {
		if (this.golem == null) {
			return;
		}

		Brain<Villager> brain = villager.getBrain();
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(this.golem, true));
		if (villager.distanceToSqr(this.golem) > square(VillageSettings.GOLEM_REPAIR_REACH)) {
			brain.setMemory(
				MemoryModuleType.WALK_TARGET,
				new WalkTarget(new EntityTracker(this.golem, false), VillageSettings.GOLEM_REPAIR_WALK_SPEED, 1)
			);
			return;
		}

		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		if (now >= this.nextActionTime) {
			repair(villager);
			this.nextActionTime = now + VillageSettings.GOLEM_REPAIR_ACTION_INTERVAL_TICKS;
		}
	}

	@Override
	protected void stop(ServerLevel level, Villager villager, long now) {
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
		this.golem = null;
		this.nextScanTime = now + VillageSettings.GOLEM_REPAIR_RETRY_COOLDOWN_TICKS;
	}

	private void repair(Villager villager) {
		if (this.golem == null || this.golem.getHealth() >= this.golem.getMaxHealth()) {
			return;
		}

		float healthBefore = this.golem.getHealth();
		this.golem.heal(VillageSettings.GOLEM_REPAIR_AMOUNT);
		if (this.golem.getHealth() > healthBefore) {
			float pitch = 1.0F + (this.golem.getRandom().nextFloat() - this.golem.getRandom().nextFloat()) * 0.2F;
			this.golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.0F, pitch);
		}
	}

	private static boolean isUnavailable(Villager villager) {
		if (villager.isSleeping() || villager.isTrading() || villager.isBaby()) {
			return true;
		}

		Brain<Villager> brain = villager.getBrain();
		return brain.isActive(Activity.PANIC)
			|| brain.isActive(Activity.RAID)
			|| brain.isActive(Activity.PRE_RAID)
			|| brain.isActive(Activity.HIDE);
	}

	@Nullable
	private static IronGolem findHurtGolem(ServerLevel level, Villager villager) {
		AABB searchArea = villager.getBoundingBox().inflate(VillageSettings.GOLEM_REPAIR_SEARCH_RADIUS);
		List<IronGolem> candidates = level.getEntitiesOfClass(
			IronGolem.class,
			searchArea,
			golem -> golem.isAlive() && golem.getHealth() < golem.getMaxHealth()
		);
		return candidates.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElse(null);
	}

	private static double square(double value) {
		return value * value;
	}
}
