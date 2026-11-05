package com.polyaris.villager.mixin;

import com.polyaris.villager.VillagerHeldItem;
import com.polyaris.villager.VillagerSettings;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public class VillagerMixin {
	/** 原版自然回血只对玩家生效（FoodData 驱动，游戏规则也归在 PLAYER 类）；村民只有补货后拿到的短暂再生效果。 */
	@Inject(method = "tick", at = @At("TAIL"))
	private void villageroptimized$regenerateWhileSleeping(CallbackInfo ci) {
		Villager villager = (Villager) (Object) this;
		if (villager.isSleeping()
			&& villager.tickCount % VillagerSettings.SLEEP_REGENERATION_INTERVAL_TICKS == 0
			&& villager.getHealth() < villager.getMaxHealth()) {
			villager.heal(VillagerSettings.SLEEP_REGENERATION_AMOUNT);
		}
	}

	/** 原版 ShowTradesToPlayer 会每 tick 清空主手，显示物品必须在实体 tick 末尾重新放回。 */
	@Inject(method = "tick", at = @At("TAIL"))
	private void villageroptimized$keepHeldItem(CallbackInfo ci) {
		VillagerHeldItem.keep((Villager) (Object) this);
	}
}
