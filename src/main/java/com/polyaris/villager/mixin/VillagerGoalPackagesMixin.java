package com.polyaris.villager.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.polyaris.villager.VillagerSettings;
import com.polyaris.villager.ai.FarmlandMaintenanceBehavior;
import com.polyaris.villager.ai.RepairIronGolemBehavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerGoalPackages.class)
public class VillagerGoalPackagesMixin {
	/** CORE 是村民的常驻活动，挂在这里的行为在作息切换时也不会被顶掉。 */
	@Inject(method = "getCorePackage", at = @At("RETURN"), cancellable = true)
	private static void villageroptimized$addGolemRepair(
		VillagerProfession profession,
		float speed,
		CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
	) {
		ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors = ImmutableList.builder();
		behaviors.add(Pair.of(VillagerSettings.GOLEM_REPAIR_BEHAVIOR_PRIORITY, new RepairIronGolemBehavior()));
		behaviors.addAll(cir.getReturnValue());
		cir.setReturnValue(behaviors.build());
	}

	/**
	 * 水浇地维护属于农活，挂在 WORK 包里：只在白天、有堆肥桶工作点时生效，
	 * 夜里或恐慌、袭击时不会把农民拉去翻地。
	 */
	@Inject(method = "getWorkPackage", at = @At("RETURN"), cancellable = true)
	private static void villageroptimized$addFarmlandMaintenance(
		VillagerProfession profession,
		float speed,
		CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
	) {
		if (!VillagerSettings.isFarmlandMaintainer(profession)) {
			return;
		}

		ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors = ImmutableList.builder();
		// 与工作、收割、漫步同优先级，放在最前面，保证有活时先被考虑
		behaviors.add(Pair.of(VillagerSettings.FARMLAND_MAINTENANCE_BEHAVIOR_PRIORITY, new FarmlandMaintenanceBehavior()));
		behaviors.addAll(cir.getReturnValue());
		cir.setReturnValue(behaviors.build());
	}
}
