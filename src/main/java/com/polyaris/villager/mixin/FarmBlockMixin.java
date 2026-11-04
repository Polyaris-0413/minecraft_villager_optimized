package com.polyaris.villager.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FarmBlock.class)
public class FarmBlockMixin {
	/**
	 * 1.21.1 中唯一带实体参数的踩踏入口就是 FarmBlock#fallOn 的这次 turnToDirt 调用，
	 * 其余几处实参为 null。这里只跳过村民，super.fallOn 的摔落伤害保持原样。
	 */
	@Redirect(
		method = "fallOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/FarmBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"
		)
	)
	private void villageroptimized$keepFarmlandFromVillagers(Entity entity, BlockState state, Level level, BlockPos pos) {
		if (!(entity instanceof Villager)) {
			FarmBlock.turnToDirt(entity, state, level, pos);
		}
	}
}
