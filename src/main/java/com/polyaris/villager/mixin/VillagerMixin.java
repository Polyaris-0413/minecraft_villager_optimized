package com.polyaris.villager.mixin;

import com.polyaris.villager.VillagerSettings;
import com.polyaris.villager.ai.RepairIronGolemBehavior;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
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

	/**
	 * 原版 ShowTradesToPlayer 在玩家靠近时会每 tick 清空主手（自己手里没匹配物品时走 clearHeldItem），
	 * 所以修复用的铁锭必须在实体 tick 的末尾重新放回去，否则玩家一靠近就看不见。
	 */
	@Inject(method = "tick", at = @At("TAIL"))
	private void villageroptimized$keepRepairItemInHand(CallbackInfo ci) {
		Villager villager = (Villager) (Object) this;
		if (!RepairIronGolemBehavior.isHoldingRepairItem(villager)) {
			return;
		}

		if (!villager.isAlive()) {
			RepairIronGolemBehavior.forgetHoldingRepairItem(villager);
			return;
		}

		villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(VillagerSettings.GOLEM_REPAIR_HELD_ITEM));
	}
}
