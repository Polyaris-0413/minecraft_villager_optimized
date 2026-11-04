package com.polyaris.village.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.polyaris.village.VillageSettings;
import com.polyaris.village.ai.RepairIronGolemBehavior;
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
	private static void villageoptimized$addGolemRepair(
		VillagerProfession profession,
		float speed,
		CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir
	) {
		ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors = ImmutableList.builder();
		behaviors.add(Pair.of(VillageSettings.GOLEM_REPAIR_BEHAVIOR_PRIORITY, new RepairIronGolemBehavior()));
		behaviors.addAll(cir.getReturnValue());
		cir.setReturnValue(behaviors.build());
	}
}
