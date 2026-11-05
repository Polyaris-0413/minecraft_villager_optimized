package com.polyaris.villager.gametest;

import com.polyaris.villager.VillagerSettings;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * 各机制的集成测试，跑在服务端 GameTest 世界里（gradlew runGameTest）。
 * 结构模板用 Fabric API 提供的 8×8×8 空结构，地面由 {@link #buildFloor} 自己铺。
 */
public class VillagerOptimizedGameTest implements FabricGameTest {
	private static final int FLOOR_LAYER = 0;
	private static final int FLOOR_SIZE = 8;

	/** 村民落到耕地上不应把它踩成泥土；同高度的僵尸应当踩坏（对照组，证明测试条件成立）。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
	public void villagersDoNotTrampleFarmland(GameTestHelper helper) {
		buildFloor(helper);
		helper.setBlock(1, 1, 1, Blocks.FARMLAND);
		helper.setBlock(5, 1, 1, Blocks.FARMLAND);
		helper.spawn(EntityType.VILLAGER, 1.5F, 4.0F, 1.5F);
		helper.spawn(EntityType.ZOMBIE, 5.5F, 4.0F, 1.5F);
		helper.runAfterDelay(40L, () -> {
			helper.assertBlockPresent(Blocks.FARMLAND, 1, 1, 1);
			helper.assertBlockPresent(Blocks.DIRT, 5, 1, 1);
			helper.succeed();
		});
	}

	/** 工具匠应当自己走到受伤的铁傀儡身边并把它修好。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void smithsRepairDamagedGolem(GameTestHelper helper) {
		buildFloor(helper);
		helper.setBlock(2, 1, 2, Blocks.SMITHING_TABLE);
		Villager smith = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
		smith.setVillagerData(smith.getVillagerData().setType(VillagerType.PLAINS).setProfession(VillagerProfession.TOOLSMITH));
		// 村民生成时职业是 NONE、Brain 也按 NONE 建好；改职业后必须重建，否则 ResetProfession 与 POI 校验都按旧职业走
		smith.refreshBrain(helper.getLevel());
		smith.getBrain().setMemory(
			MemoryModuleType.JOB_SITE,
			GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(2, 1, 2)))
		);
		IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 6);
		golem.setHealth(40.0F);
		// 模拟原版 ShowTradesToPlayer 清空主手：修复期间铁锭应当被重新放回手里
		helper.runAfterDelay(30L, () -> smith.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY));
		helper.runAfterDelay(40L, () -> helper.assertTrue(
			smith.getMainHandItem().is(Items.IRON_INGOT),
			"主手被清空后没有重新拿起铁锭：" + smith.getMainHandItem()
		));
		helper.succeedWhen(() -> helper.assertTrue(golem.getHealth() > 40.0F, "铁傀儡没有被修复，当前血量 " + golem.getHealth()));
	}

	/** 夜里受伤的村民躺到床上后应当每秒回 1 点血。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600)
	public void sleepingVillagersRegenerateHealth(GameTestHelper helper) {
		buildFloor(helper);
		helper.setBlock(3, 1, 3, Blocks.RED_BED.defaultBlockState()
			.setValue(BedBlock.FACING, Direction.NORTH)
			.setValue(BedBlock.PART, BedPart.FOOT));
		helper.setBlock(3, 1, 2, Blocks.RED_BED.defaultBlockState()
			.setValue(BedBlock.FACING, Direction.NORTH)
			.setValue(BedBlock.PART, BedPart.HEAD));
		helper.setNight();
		Villager villager = helper.spawn(EntityType.VILLAGER, 4, 1, 3);
		villager.setHealth(5.0F);
		helper.succeedWhen(() -> helper.assertTrue(
			villager.isSleeping() && villager.getHealth() > 5.0F,
			"村民没有在睡眠中回血（睡觉=" + villager.isSleeping() + "，血量=" + villager.getHealth() + "）"
		));
	}

	/**
	 * 工作方块附近的水源覆盖范围内的裸露泥土，即使附近没有耕地也应被翻成耕地（D1）。
	 * 水放在 1×1 石头池里，避免水流到测试平台外面。
	 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "farmerBatch")
	public void farmersTillIrrigatedDirtAroundComposter(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		Villager farmer = spawnFarmerWithComposter(helper, 1, 1, 1, new BlockPos(2, 1, 2));
		BlockPos absoluteTarget = helper.absolutePos(new BlockPos(4, 1, 4));
		// 翻地那一刻必须站在目标方块上，而不是隔着两格就动手
		helper.failIfEver(() -> {
			if (helper.getLevel().getBlockState(absoluteTarget).is(Blocks.FARMLAND)) {
				// 耕地高 15/16，站上去后脚会陷进目标方块那一格，所以两种位置都算站对了
				BlockPos feet = farmer.blockPosition();
				helper.assertTrue(
					feet.equals(absoluteTarget) || feet.equals(absoluteTarget.above()),
					"农民没有站到目标方块上就翻地了：" + feet + "，目标：" + absoluteTarget
				);
			}
		});
		// 与原版锄头一致，翻好时是默认耕地（湿度 0），有水时由 randomTick 自然润湿
		helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.FARMLAND, 4, 1, 4));
	}

	/** 草方块不再开荒：水边草方块保持原样。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void irrigatedGrassIsNotTilled(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.GRASS_BLOCK);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockPresent(Blocks.GRASS_BLOCK, 4, 1, 4);
			helper.succeed();
		});
	}

	/** 水浇泥土上方有草时，先清草再翻；不能因为种子够而永久卡住。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "farmerBatch")
	public void farmersClearGrassOnIrrigatedDirt(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		helper.setBlock(4, 2, 4, Blocks.SHORT_GRASS);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.SHORT_GRASS, 4, 2, 4);
			helper.assertBlockPresent(Blocks.FARMLAND, 4, 1, 4);
		});
	}

	/** 空耕地离农民超过原版 3×3×3 时，也应该走过去补种。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "farmerBatch")
	public void farmersPlantEmptyFarmlandBeyondVanillaRange(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(6, 1, 6, Blocks.FARMLAND);
		placeContainedWater(helper, 7, 1, 7);
		helper.setDayTime(2000);
		Villager farmer = spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
		helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.WHEAT, 6, 2, 6));
	}

	/**
	 * 没有可翻的地、种子不足时，农民会打草。
	 * 掉率是原版 1/8，测试只断言草被打掉，不赌随机数。
	 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void farmersBreakGrassWhenOutOfSeeds(GameTestHelper helper) {
		buildFloor(helper, Blocks.DIRT);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.SHORT_GRASS);
		helper.setDayTime(2000);
		spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockNotPresent(Blocks.SHORT_GRASS, 4, 1, 4);
			helper.succeed();
		});
	}

	/** 小麦种子已经够了就不该再去打草；工作点放高一点，避免原版堆肥把种子消耗掉干扰断言。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void farmersDoNotGatherGrassWhenSeedsAreStocked(GameTestHelper helper) {
		buildFloor(helper, Blocks.DIRT);
		helper.setBlock(2, 5, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.SHORT_GRASS);
		helper.setDayTime(2000);
		Villager farmer = spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 5, 2));
		farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, VillagerSettings.FARMER_SEED_STOCK_TARGET));
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockPresent(Blocks.SHORT_GRASS, 4, 1, 4);
			helper.succeed();
		});
	}

	/** 维护期间农民主手应拿着铁锄，而不是空手修复。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "farmerBatch")
	public void farmerHoldsHoeWhileMaintainingFarmland(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		Villager farmer = spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.succeedWhen(() -> helper.assertTrue(
			farmer.getMainHandItem().is(Items.IRON_HOE),
			"农民维护田地时没有拿着铁锄：" + farmer.getMainHandItem()
		));
	}

	/** 上方被方块盖住的泥土不应被翻（要求 1）。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void coveredDirtIsNotTilled(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		helper.setBlock(4, 2, 4, Blocks.STONE);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockPresent(Blocks.DIRT, 4, 1, 4);
			helper.succeed();
		});
	}

	/** 水源覆盖范围外的泥土不应被翻：农田范围由水定义，不是工作方块周围的所有泥土。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void unirrigatedDirtIsNotTilled(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(2, 1, 2, Blocks.COMPOSTER);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		helper.setDayTime(2000);
		spawnFarmerWithComposter(helper, 3, 1, 4, new BlockPos(2, 1, 2));
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockPresent(Blocks.DIRT, 4, 1, 4);
			helper.succeed();
		});
	}

	/** 没有堆肥桶（没有工作点）的农民不应翻土（要求 2 的前提）。 */
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400, batch = "farmerBatch")
	public void farmerWithoutJobSiteDoesNotTill(GameTestHelper helper) {
		buildFloor(helper, Blocks.STONE);
		helper.setBlock(4, 1, 4, Blocks.DIRT);
		placeContainedWater(helper, 5, 1, 5);
		helper.setDayTime(2000);
		Villager farmer = helper.spawn(EntityType.VILLAGER, 3, 1, 4);
		farmer.setVillagerData(farmer.getVillagerData().setType(VillagerType.PLAINS).setProfession(VillagerProfession.FARMER));
		// 职业改了但没有任何工作站点，WORK 活动不会激活
		farmer.refreshBrain(helper.getLevel());
		helper.runAfterDelay(200L, () -> {
			helper.assertBlockPresent(Blocks.DIRT, 4, 1, 4);
			helper.succeed();
		});
	}

	private static Villager spawnFarmerWithComposter(GameTestHelper helper, int x, int y, int z, BlockPos composter) {
		Villager farmer = helper.spawn(EntityType.VILLAGER, x, y, z);
		farmer.setVillagerData(farmer.getVillagerData().setType(VillagerType.PLAINS).setProfession(VillagerProfession.FARMER));
		// 村民生成时职业是 NONE、Brain 也按 NONE 建好；改职业后必须重建
		farmer.refreshBrain(helper.getLevel());
		farmer.getBrain().setMemory(
			MemoryModuleType.JOB_SITE,
			GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(composter))
		);
		farmer.getBrain().setActiveActivityIfPossible(Activity.WORK);
		return farmer;
	}

	private static void placeContainedWater(GameTestHelper helper, int x, int y, int z) {
		helper.setBlock(x - 1, y, z, Blocks.STONE);
		helper.setBlock(x + 1, y, z, Blocks.STONE);
		helper.setBlock(x, y, z - 1, Blocks.STONE);
		helper.setBlock(x, y, z + 1, Blocks.STONE);
		helper.setBlock(x, y, z, Blocks.WATER);
	}

	private static void buildFloor(GameTestHelper helper) {
		buildFloor(helper, Blocks.DIRT);
	}

	private static void buildFloor(GameTestHelper helper, Block block) {
		for (int x = 0; x < FLOOR_SIZE; x++) {
			for (int z = 0; z < FLOOR_SIZE; z++) {
				helper.setBlock(x, FLOOR_LAYER, z, block);
			}
		}
	}
}
