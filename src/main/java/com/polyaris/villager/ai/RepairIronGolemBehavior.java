package com.polyaris.villager.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;
import com.polyaris.villager.VillagerHeldItem;
import com.polyaris.villager.VillagerSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * 铁匠职业的村民在附近铁傀儡受伤时前往修复。
 * 挂在 {@link net.minecraft.world.entity.ai.behavior.VillagerGoalPackages} 的 CORE 行为包里，
 * 由原版的 MoveToTargetSink 负责实际寻路。
 */
public class RepairIronGolemBehavior extends Behavior<Villager> {
	@Nullable
	private IronGolem golem;
	private long attemptStartTime;
	private long nextActionTime;
	private long nextScanTime;
	private long unreachableSinceAtStart = Long.MIN_VALUE;
	private ItemStack previousMainHand = ItemStack.EMPTY;

	public RepairIronGolemBehavior() {
		super(ImmutableMap.of(), VillagerSettings.GOLEM_REPAIR_BEHAVIOR_DURATION_TICKS);
	}

	@Override
	protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
		long now = level.getGameTime();
		if (now < this.nextScanTime) {
			return false;
		}

		this.nextScanTime = now + VillagerSettings.GOLEM_REPAIR_SCAN_INTERVAL_TICKS;
		if (!VillagerSettings.isGolemRepairer(villager.getVillagerData().getProfession()) || isUnavailable(villager)) {
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
		this.previousMainHand = villager.getMainHandItem().copy();
		VillagerHeldItem.hold(villager, VillagerSettings.GOLEM_REPAIR_HELD_ITEM);
	}

	@Override
	protected boolean canStillUse(ServerLevel level, Villager villager, long now) {
		if (this.golem == null || !this.golem.isAlive() || this.golem.isRemoved()) {
			return false;
		}

		if (this.golem.getHealth() >= this.golem.getMaxHealth() || isUnavailable(villager)) {
			return false;
		}

		if (villager.distanceToSqr(this.golem) > Mth.square(VillagerSettings.GOLEM_REPAIR_SEARCH_RADIUS)) {
			return false;
		}

		if (now - this.attemptStartTime > VillagerSettings.GOLEM_REPAIR_ATTEMPT_TIMEOUT_TICKS) {
			return false;
		}

		Optional<Long> unreachableSince = villager.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
		return unreachableSince.isEmpty()
			|| unreachableSince.get() == this.unreachableSinceAtStart
			|| now - unreachableSince.get() <= VillagerSettings.GOLEM_REPAIR_UNREACHABLE_GIVE_UP_TICKS;
	}

	@Override
	protected void tick(ServerLevel level, Villager villager, long now) {
		if (this.golem == null) {
			return;
		}

		Brain<Villager> brain = villager.getBrain();
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(this.golem, true));
		if (villager.distanceToSqr(this.golem) > Mth.square(VillagerSettings.GOLEM_REPAIR_REACH)) {
			brain.setMemory(
				MemoryModuleType.WALK_TARGET,
				new WalkTarget(
					new EntityTracker(this.golem, false),
					VillagerSettings.GOLEM_REPAIR_WALK_SPEED,
					VillagerSettings.GOLEM_REPAIR_WALK_CLOSE_ENOUGH
				)
			);
			return;
		}

		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		if (now >= this.nextActionTime) {
			repair();
			this.nextActionTime = now + VillagerSettings.GOLEM_REPAIR_ACTION_INTERVAL_TICKS;
		}
	}

	@Override
	protected void stop(ServerLevel level, Villager villager, long now) {
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
		VillagerHeldItem.release(villager, this.previousMainHand);
		this.previousMainHand = ItemStack.EMPTY;
		this.golem = null;
		this.nextScanTime = now + VillagerSettings.GOLEM_REPAIR_RETRY_COOLDOWN_TICKS;
	}

	private void repair() {
		if (this.golem == null || this.golem.getHealth() >= this.golem.getMaxHealth()) {
			return;
		}

		float healthBefore = this.golem.getHealth();
		this.golem.heal(VillagerSettings.GOLEM_REPAIR_AMOUNT);
		if (this.golem.getHealth() > healthBefore) {
			// 音高抖动与原版 IronGolem#mobInteract 的修复音效一致
			float pitch = 1.0F
				+ (this.golem.getRandom().nextFloat() - this.golem.getRandom().nextFloat())
					* VillagerSettings.GOLEM_REPAIR_SOUND_PITCH_JITTER;
			this.golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, VillagerSettings.GOLEM_REPAIR_SOUND_VOLUME, pitch);
		}
	}

	private static boolean isUnavailable(Villager villager) {
		if (villager.isSleeping() || villager.isTrading() || villager.isBaby()) {
			return true;
		}

		Brain<Villager> brain = villager.getBrain();
		for (Activity activity : VillagerSettings.GOLEM_REPAIR_BLOCKED_ACTIVITIES) {
			if (brain.isActive(activity)) {
				return true;
			}
		}

		return false;
	}

	@Nullable
	private static IronGolem findHurtGolem(ServerLevel level, Villager villager) {
		AABB searchArea = villager.getBoundingBox().inflate(VillagerSettings.GOLEM_REPAIR_SEARCH_RADIUS);
		List<IronGolem> candidates = level.getEntitiesOfClass(
			IronGolem.class,
			searchArea,
			golem -> golem.isAlive() && golem.getHealth() < golem.getMaxHealth()
		);
		return candidates.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElse(null);
	}
}
