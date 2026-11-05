package com.polyaris.villager.mixin;

import com.polyaris.villager.ai.FarmlandMaintenanceBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorkAtComposter.class)
public class WorkAtComposterMixin {
	/**
	 * 附近还有空耕地、手里也有种子时，先把田种满；
	 * 否则原版会在农民离开工作点之前就把种子塞进堆肥桶，田一直种不满。
	 * 面包和堆肥都跳过，等田种完再恢复。
	 */
	@Inject(method = "useWorkstation", at = @At("HEAD"), cancellable = true)
	private void villageroptimized$plantBeforeComposting(ServerLevel level, Villager villager, CallbackInfo ci) {
		if (villager.hasFarmSeeds() && FarmlandMaintenanceBehavior.hasEmptyFarmland(level, villager)) {
			ci.cancel();
		}
	}
}
