package com.polyaris.villager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 村民做动作期间手上显示的工具。
 * 原版 ShowTradesToPlayer 在玩家靠近时会每 tick 清空主手，所以显示物品必须在实体 tick 末尾重新放回，
 * 只在行为开始时设置一次会被清掉。
 */
public final class VillagerHeldItem {
	private static final Map<UUID, ItemStack> HELD_ITEMS = new ConcurrentHashMap<>();

	private VillagerHeldItem() {
	}

	public static void hold(Villager villager, Item item) {
		ItemStack stack = new ItemStack(item);
		HELD_ITEMS.put(villager.getUUID(), stack);
		villager.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
	}

	public static void release(Villager villager, ItemStack previous) {
		HELD_ITEMS.remove(villager.getUUID());
		villager.setItemSlot(EquipmentSlot.MAINHAND, previous);
	}

	public static void keep(Villager villager) {
		ItemStack stack = HELD_ITEMS.get(villager.getUUID());
		if (stack == null) {
			return;
		}

		if (!villager.isAlive()) {
			HELD_ITEMS.remove(villager.getUUID());
			return;
		}

		villager.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
	}
}
