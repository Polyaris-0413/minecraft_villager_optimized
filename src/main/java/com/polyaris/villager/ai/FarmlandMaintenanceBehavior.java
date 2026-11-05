package com.polyaris.villager.ai;

import java.util.Optional;

import com.google.common.collect.ImmutableMap;
import com.polyaris.villager.VillagerHeldItem;
import com.polyaris.villager.VillagerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 农民维护水浇地：把水源覆盖范围内的裸露泥土翻回耕地。
 * 搜索跟着农民走（以农民自身为中心的前瞻范围），堆肥桶只负责启用行为，不限制修复区域；
 * 因此整片水浇地（包括大块田和湖岸）会被农民逐块走过去覆盖，而不是只在堆肥桶附近。
 * 范围判定复用原版 {@link FarmBlock} 的润湿规则，所以修完的耕地不会因缺水再次退化；
 * 被方块盖住或已经不再是泥土的位置不会被选中，符合“无法修复就跳过”的要求。
 * 只注册在 WORK 行为包里，因此只在白天、有工作站点时生效。
 * 翻地前必须站到目标方块正上方，不能隔着几格动手。
 */
public class FarmlandMaintenanceBehavior extends Behavior<Villager> {
	@Nullable
	private BlockPos target;
	private long attemptStartTime;
	private long nextScanTime;
	private long unreachableSinceAtStart = Long.MIN_VALUE;
	@Nullable
	private BlockPos ignoredTarget;
	private long ignoreUntil;
	private boolean tilled;
	private ItemStack previousMainHand = ItemStack.EMPTY;

	public FarmlandMaintenanceBehavior() {
		super(
			ImmutableMap.of(MemoryModuleType.JOB_SITE, MemoryStatus.VALUE_PRESENT),
			VillagerSettings.FARMLAND_MAINTENANCE_BEHAVIOR_DURATION_TICKS
		);
	}

	@Override
	protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
		long now = level.getGameTime();
		if (now < this.nextScanTime) {
			return false;
		}

		this.nextScanTime = now + VillagerSettings.FARMLAND_MAINTENANCE_SCAN_INTERVAL_TICKS;
		this.target = null;
		if (!canMaintain(level, villager)) {
			return false;
		}

		// 搜索跟着农民走：堆肥桶只负责启用行为，不限制修复区域；整片水浇地靠农民逐块走过去覆盖
		this.target = findTarget(level, villager, villager.blockPosition(), now);
		return this.target != null;
	}

	@Override
	protected void start(ServerLevel level, Villager villager, long now) {
		this.attemptStartTime = now;
		this.unreachableSinceAtStart = villager.getBrain()
			.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
			.orElse(Long.MIN_VALUE);
		this.tilled = false;
		this.previousMainHand = villager.getMainHandItem().copy();
		VillagerHeldItem.hold(villager, VillagerSettings.FARMLAND_MAINTENANCE_HELD_ITEM);
		walkTo(villager);
	}

	@Override
	protected boolean canStillUse(ServerLevel level, Villager villager, long now) {
		if (this.target == null || this.tilled) {
			return false;
		}

		if (now - this.attemptStartTime > VillagerSettings.FARMLAND_MAINTENANCE_ATTEMPT_TIMEOUT_TICKS) {
			return false;
		}

		if (!canMaintain(level, villager) || !isEligible(level, this.target)) {
			return false;
		}

		Optional<Long> unreachableSince = villager.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
		return unreachableSince.isEmpty()
			|| unreachableSince.get() == this.unreachableSinceAtStart
			|| now - unreachableSince.get() <= VillagerSettings.FARMLAND_MAINTENANCE_UNREACHABLE_GIVE_UP_TICKS;
	}

	@Override
	protected void tick(ServerLevel level, Villager villager, long now) {
		if (this.target == null) {
			return;
		}

		Brain<Villager> brain = villager.getBrain();
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(this.target));
		if (!isStandingOn(villager, this.target)) {
			walkTo(villager);
			return;
		}

		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		this.tilled = till(level, villager, this.target);
	}

	@Override
	protected void stop(ServerLevel level, Villager villager, long now) {
		Brain<Villager> brain = villager.getBrain();
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
		VillagerHeldItem.release(villager, this.previousMainHand);
		this.previousMainHand = ItemStack.EMPTY;
		if (!this.tilled && this.target != null && isEligible(level, this.target)) {
			// 目标仍然有效却没修完，多半是走不过去；短时间内别再反复选它
			this.ignoredTarget = this.target;
			this.ignoreUntil = now + VillagerSettings.FARMLAND_MAINTENANCE_IGNORE_TICKS;
		}

		this.target = null;
		this.tilled = false;
		this.nextScanTime = now + VillagerSettings.FARMLAND_MAINTENANCE_RETRY_COOLDOWN_TICKS;
	}

	private void walkTo(Villager villager) {
		if (this.target == null) {
			return;
		}

		// 目标方块本身是实心的，寻路必须指向它正上方那一格，村民才会站到方块上
		villager.getBrain().setMemory(
			MemoryModuleType.WALK_TARGET,
			new WalkTarget(
				new BlockPosTracker(this.target.above()),
				VillagerSettings.FARMLAND_MAINTENANCE_WALK_SPEED,
				VillagerSettings.FARMLAND_MAINTENANCE_WALK_CLOSE_ENOUGH
			)
		);
	}

	private static boolean isStandingOn(Villager villager, BlockPos pos) {
		return villager.onGround() && villager.blockPosition().below().equals(pos);
	}

	private static boolean canMaintain(ServerLevel level, Villager villager) {
		if (!VillagerSettings.isFarmlandMaintainer(villager.getVillagerData().getProfession())) {
			return false;
		}

		if (villager.isBaby() || villager.isSleeping() || villager.isTrading()) {
			return false;
		}

		// 与原版 HarvestFarmland 一样，mobGriefing 关闭时村民不做农活
		if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
			return false;
		}

		Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
		if (jobSite.isEmpty()) {
			return false;
		}

		GlobalPos globalPos = jobSite.get();
		if (globalPos.dimension() != level.dimension() || !level.isLoaded(globalPos.pos())) {
			return false;
		}

		return level.getBlockState(globalPos.pos()).is(Blocks.COMPOSTER);
	}

	@Nullable
	private BlockPos findTarget(ServerLevel level, Villager villager, BlockPos center, long now) {
		int radius = VillagerSettings.FARMLAND_MAINTENANCE_SEARCH_RADIUS;
		int vertical = VillagerSettings.FARMLAND_MAINTENANCE_VERTICAL_RADIUS;
		if (!level.isLoaded(center)) {
			return null;
		}

		boolean[] irrigated = buildIrrigationMask(level, center, radius, vertical);
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int dy = -vertical; dy <= vertical; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (!irrigated[index(dx, dy, dz, radius, vertical)]) {
						continue;
					}

					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (this.ignoredTarget != null && now < this.ignoreUntil && cursor.equals(this.ignoredTarget)) {
						continue;
					}

					if (!isCandidate(level, cursor)) {
						continue;
					}

					double distance = villager.distanceToSqr(Vec3.atCenterOf(cursor));
					if (distance < bestDistance) {
						bestDistance = distance;
						best = cursor.immutable();
					}
				}
			}
		}

		return best;
	}

	/**
	 * 预计算工作站点周围哪些位置被水润湿。水覆盖自己所在层和下一层的耕地，
	 * 水平半径与 {@link FarmBlock} 的润湿规则一致；逐块调用会做太多次流体查询。
	 */
	private static boolean[] buildIrrigationMask(ServerLevel level, BlockPos center, int radius, int vertical) {
		boolean[] mask = new boolean[(2 * radius + 1) * (2 * vertical + 1) * (2 * radius + 1)];
		int hydration = VillagerSettings.FARMLAND_HYDRATION_RADIUS;
		BlockPos.MutableBlockPos water = new BlockPos.MutableBlockPos();

		for (int dy = -vertical; dy <= vertical + 1; dy++) {
			for (int dz = -radius - hydration; dz <= radius + hydration; dz++) {
				for (int dx = -radius - hydration; dx <= radius + hydration; dx++) {
					water.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.isLoaded(water) || !level.getFluidState(water).is(FluidTags.WATER)) {
						continue;
					}

					for (int coveredDy = 0; coveredDy >= -1; coveredDy--) {
						int candidateDy = dy + coveredDy;
						if (candidateDy < -vertical || candidateDy > vertical) {
							continue;
						}

						for (int offsetZ = -hydration; offsetZ <= hydration; offsetZ++) {
							int candidateDz = dz + offsetZ;
							if (candidateDz < -radius || candidateDz > radius) {
								continue;
							}

							for (int offsetX = -hydration; offsetX <= hydration; offsetX++) {
								int candidateDx = dx + offsetX;
								if (candidateDx < -radius || candidateDx > radius) {
									continue;
								}

								mask[index(candidateDx, candidateDy, candidateDz, radius, vertical)] = true;
							}
						}
					}
				}
			}
		}

		return mask;
	}

	private static int index(int dx, int dy, int dz, int radius, int vertical) {
		return (dx + radius)
			+ (dz + radius) * (2 * radius + 1)
			+ (dy + vertical) * (2 * radius + 1) * (2 * radius + 1);
	}

	private static boolean isCandidate(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos)
			&& level.getBlockState(pos).is(Blocks.DIRT)
			&& level.getBlockState(pos.above()).isAir();
	}

	private static boolean isEligible(ServerLevel level, BlockPos pos) {
		return isCandidate(level, pos) && isIrrigated(level, pos);
	}

	/** 与原版 FarmBlock#isNearWater 相同：水平 ±4、竖直 y 到 y+1 内有水。 */
	private static boolean isIrrigated(ServerLevel level, BlockPos pos) {
		int radius = VillagerSettings.FARMLAND_HYDRATION_RADIUS;
		for (BlockPos water : BlockPos.betweenClosed(pos.offset(-radius, 0, -radius), pos.offset(radius, 1, radius))) {
			if (level.isLoaded(water) && level.getFluidState(water).is(FluidTags.WATER)) {
				return true;
			}
		}

		return false;
	}

	private static boolean till(ServerLevel level, Villager villager, BlockPos pos) {
		if (!isEligible(level, pos)) {
			return false;
		}

		BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE);
		if (!level.setBlockAndUpdate(pos, farmland)) {
			return false;
		}

		level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(villager, farmland));
		level.playSound(
			null,
			pos.getX() + 0.5,
			pos.getY() + 0.5,
			pos.getZ() + 0.5,
			SoundEvents.HOE_TILL,
			SoundSource.BLOCKS,
			VillagerSettings.FARMLAND_MAINTENANCE_SOUND_VOLUME,
			VillagerSettings.FARMLAND_MAINTENANCE_SOUND_PITCH
		);
		return true;
	}
}
