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
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 农民维护水浇地：把水源覆盖范围内的裸露泥土翻回耕地，草方块不再开荒。
 * 泥土上方挡着矮草/高草时，先清草再翻；清障不受种子存量限制，否则种子够时这块地会永久卡住。
 * 搜索跟着农民走（以农民自身为中心的前瞻范围），堆肥桶只负责启用行为，不限制修复区域；
 * 因此整片田（包括大块田和湖岸）会被农民逐块走过去覆盖，而不是只在堆肥桶附近。
 * 范围判定复用原版 {@link FarmBlock} 的润湿规则，所以修完的耕地不会因缺水再次退化；
 * 被方块盖住或已经不再是泥土的位置不会被选中，符合“无法修复就跳过”的要求。
 * 没有可翻的地时，若小麦种子低于 {@link VillagerSettings#FARMER_SEED_STOCK_TARGET}，会打草补种子，
 * 掉率与原版一致（1/8），补够就收手，不碰食物与繁衍逻辑。
 * 只注册在 WORK 行为包里，并用 {@link Activity#WORK} 门禁保证换班、袭击时立即收手。
 * 翻地前必须站到目标方块正上方，站定一小段（与原版一次挥臂等长）再落锄。
 */
public class FarmlandMaintenanceBehavior extends Behavior<Villager> {
	@Nullable
	private BlockPos target;
	private boolean gathering;
	private long lastProgressTime;
	private long nextScanTime;
	private long actionReadyAt = -1L;
	private long unreachableSince = -1L;
	private boolean gaveUpUnreachable;
	private boolean finished;
	@Nullable
	private BlockPos ignoredTarget;
	private long ignoreUntil;
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
		this.gathering = false;
		if (!canMaintain(level, villager)) {
			return false;
		}

		BlockPos center = villager.blockPosition();
		// 搜索跟着农民走：堆肥桶只负责启用行为，不限制修复区域；整片田靠农民逐块走过去覆盖
		this.target = findTarget(level, villager, center, now);
		if (this.target != null) {
			this.gathering = isGrassTarget(level, this.target);
			return true;
		}

		// 先把能翻的地翻完，再谈补种子，避免种子不够时把地翻出来却种不上
		this.target = findGrassTarget(level, villager, center);
		this.gathering = this.target != null;
		return this.target != null;
	}

	@Override
	protected void start(ServerLevel level, Villager villager, long now) {
		this.lastProgressTime = now;
		this.actionReadyAt = -1L;
		this.unreachableSince = -1L;
		this.gaveUpUnreachable = false;
		this.finished = false;
		this.previousMainHand = villager.getMainHandItem().copy();
		VillagerHeldItem.hold(villager, VillagerSettings.FARMLAND_MAINTENANCE_HELD_ITEM);
		walkTo(villager);
	}

	@Override
	protected boolean canStillUse(ServerLevel level, Villager villager, long now) {
		if (this.finished || !canMaintain(level, villager)) {
			return false;
		}

		// 连续工作：翻到一块会重置；走路本身不算进度，超过上限说明卡住了
		if (now - this.lastProgressTime > VillagerSettings.FARMLAND_MAINTENANCE_ATTEMPT_TIMEOUT_TICKS) {
			return false;
		}

		Optional<Long> unreachableSince = villager.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
		if (unreachableSince.isEmpty()) {
			this.unreachableSince = -1L;
			return true;
		}

		// 原版只在“记忆不存在”时写入时间戳，所以按本次会话第一次观察到不可达的时间计时
		if (this.unreachableSince < 0L) {
			this.unreachableSince = now;
		} else if (now - this.unreachableSince > VillagerSettings.FARMLAND_MAINTENANCE_UNREACHABLE_GIVE_UP_TICKS) {
			this.gaveUpUnreachable = true;
			return false;
		}

		return true;
	}

	@Override
	protected void tick(ServerLevel level, Villager villager, long now) {
		Brain<Villager> brain = villager.getBrain();
		if (this.target != null && !isEligible(level, this.target)) {
			this.target = null;
			this.actionReadyAt = -1L;
			this.nextScanTime = now;
		}

		if (this.target == null) {
			if (now < this.nextScanTime) {
				return;
			}

			this.nextScanTime = now + VillagerSettings.FARMLAND_MAINTENANCE_SCAN_INTERVAL_TICKS;
			BlockPos center = villager.blockPosition();
			this.target = findTarget(level, villager, center, now);
			if (this.target != null) {
				this.gathering = isGrassTarget(level, this.target);
			} else {
				this.target = findGrassTarget(level, villager, center);
				this.gathering = this.target != null;
			}

			if (this.target == null) {
				// 没活就把控制权交回原版；注视目标不清掉会挡住要求 LOOK_TARGET 缺席的 HarvestFarmland
				brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
				this.finished = true;
				return;
			}
		}

		brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(this.target));
		if (!isAtTarget(villager, this.target, this.gathering)) {
			this.actionReadyAt = -1L;
			walkTo(villager);
			return;
		}

		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		if (this.actionReadyAt < 0L) {
			this.actionReadyAt = now + VillagerSettings.FARMLAND_MAINTENANCE_ACTION_TICKS;
			return;
		}

		if (now < this.actionReadyAt) {
			return;
		}

		boolean worked = this.gathering
			? harvestGrass(level, villager, this.target)
			: till(level, villager, this.target);
		if (worked) {
			// 立刻准备下一块，避免停下来的那一拍被原版的“回工作点”牵引
			this.lastProgressTime = now;
			this.target = null;
			this.actionReadyAt = -1L;
			this.nextScanTime = now;
		}
	}

	@Override
	protected void stop(ServerLevel level, Villager villager, long now) {
		boolean noWork = this.finished;
		Brain<Villager> brain = villager.getBrain();
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
		VillagerHeldItem.release(villager, this.previousMainHand);
		this.previousMainHand = ItemStack.EMPTY;
		if (this.gaveUpUnreachable && this.target != null && isEligible(level, this.target)) {
			// 确实走不过去，短时间内别再反复选它
			this.ignoredTarget = this.target;
			this.ignoreUntil = now + VillagerSettings.FARMLAND_MAINTENANCE_IGNORE_TICKS;
		}

		this.target = null;
		this.gathering = false;
		this.actionReadyAt = -1L;
		this.unreachableSince = -1L;
		this.gaveUpUnreachable = false;
		this.finished = false;
		// 只是没活时用普通扫描间隔，别让新放的水/新铺的土等上 100 tick 的冷却
		this.nextScanTime = now + (noWork
			? VillagerSettings.FARMLAND_MAINTENANCE_SCAN_INTERVAL_TICKS
			: VillagerSettings.FARMLAND_MAINTENANCE_RETRY_COOLDOWN_TICKS);
	}

	private void walkTo(Villager villager) {
		if (this.target == null) {
			return;
		}

		// 翻地要站到方块正上方；打草则直接走进草所在的那一格
		BlockPos walkTarget = this.gathering ? this.target : this.target.above();
		villager.getBrain().setMemory(
			MemoryModuleType.WALK_TARGET,
			new WalkTarget(
				new BlockPosTracker(walkTarget),
				VillagerSettings.FARMLAND_MAINTENANCE_WALK_SPEED,
				VillagerSettings.FARMLAND_MAINTENANCE_WALK_CLOSE_ENOUGH
			)
		);
	}

	private static boolean isAtTarget(Villager villager, BlockPos pos, boolean gathering) {
		if (!villager.onGround()) {
			return false;
		}

		return gathering
			? villager.blockPosition().equals(pos)
			: villager.blockPosition().below().equals(pos);
	}

	private static boolean canMaintain(ServerLevel level, Villager villager) {
		if (!VillagerSettings.isFarmlandMaintainer(villager.getVillagerData().getProfession())) {
			return false;
		}

		if (villager.isBaby() || villager.isSleeping() || villager.isTrading()) {
			return false;
		}

		// 换班、袭击、躲藏时 WORK 不再激活；正在跑的行为不会被 Brain 自动停掉，必须自己收手
		if (!villager.getBrain().isActive(Activity.WORK)) {
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
					if (!level.getBlockState(cursor).is(Blocks.DIRT)) {
						continue;
					}

					BlockPos action;
					if (isCandidate(level, cursor)) {
						action = cursor.immutable();
					} else if (isClearableGrass(level.getBlockState(cursor.above()))) {
						// 草盖住了可浇水的泥土：先清草，下一轮再翻；这一步不受种子存量限制，否则会永久卡住
						action = cursor.above().immutable();
					} else {
						continue;
					}

					if (this.ignoredTarget != null && now < this.ignoreUntil && action.equals(this.ignoredTarget)) {
						continue;
					}

					double distance = villager.distanceToSqr(Vec3.atCenterOf(action));
					if (distance < bestDistance) {
						bestDistance = distance;
						best = action;
					}
				}
			}
		}

		return best;
	}

	/**
	 * 找最近的矮草/高草。高草统一取下半格，避免把行走目标设到需要跳上去的上半格。
	 */
	@Nullable
	private static BlockPos findGrassTarget(ServerLevel level, Villager villager, BlockPos center) {
		if (countWheatSeeds(villager) >= VillagerSettings.FARMER_SEED_STOCK_TARGET || !level.isLoaded(center)) {
			return null;
		}

		int radius = VillagerSettings.FARMLAND_MAINTENANCE_SEARCH_RADIUS;
		int vertical = VillagerSettings.FARMLAND_MAINTENANCE_VERTICAL_RADIUS;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int dy = -vertical; dy <= vertical; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = level.getBlockState(cursor);
					if (state.is(Blocks.TALL_GRASS) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
						cursor.set(cursor.below());
						state = level.getBlockState(cursor);
					}

					if (!state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.TALL_GRASS)) {
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

	private static boolean harvestGrass(ServerLevel level, Villager villager, BlockPos pos) {
		if (!isGrassTarget(level, pos)) {
			return false;
		}

		BlockState state = level.getBlockState(pos);
		if (!level.destroyBlock(pos, false, villager)) {
			return false;
		}

		// 高草的另一半会随方块更新消失；这里只兜底处理没被带走的半截
		if (state.is(Blocks.TALL_GRASS)) {
			DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
			BlockPos other = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
			if (level.getBlockState(other).is(Blocks.TALL_GRASS)) {
				level.removeBlock(other, false);
			}
		}

		if (level.random.nextFloat() < VillagerSettings.FARMER_GRASS_SEED_CHANCE) {
			ItemStack leftover = villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS));
			if (!leftover.isEmpty()) {
				Block.popResource(level, pos, leftover);
			}
		}

		return true;
	}

	private static int countWheatSeeds(Villager villager) {
		return villager.getInventory().countItem(Items.WHEAT_SEEDS);
	}

	/**
	 * 预计算农民周围哪些位置被水润湿。水覆盖自己所在层和下一层的耕地，
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

	private boolean isEligible(ServerLevel level, BlockPos pos) {
		return this.gathering ? isGrassTarget(level, pos) : isFarmlandTarget(level, pos);
	}

	private static boolean isFarmlandTarget(ServerLevel level, BlockPos pos) {
		return isCandidate(level, pos) && isIrrigated(level, pos);
	}

	private static boolean isGrassTarget(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos) && isClearableGrass(level.getBlockState(pos));
	}

	private static boolean isClearableGrass(BlockState state) {
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS);
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
		if (!isFarmlandTarget(level, pos)) {
			return false;
		}

		// 与原版锄头一致：落成默认耕地（湿度 0），有水时由 randomTick 自然涨到 7；
		// 直接写 7 会跳过“刚翻好的地慢慢被润湿”的过程
		BlockState farmland = Blocks.FARMLAND.defaultBlockState();
		if (!level.setBlock(pos, farmland, 11)) {
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
