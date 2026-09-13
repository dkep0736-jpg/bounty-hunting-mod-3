package com.bountyhunter.gui;

import com.bountyhunter.BountyHunterMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;

public final class GuiItems {
	private GuiItems() {}

	public static ItemStack named(Item item, Component name, List<Component> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name);
		if (lore != null && !lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		return stack;
	}

	public static ItemStack pane(Item paneItem, String name) {
		return named(paneItem, Component.literal(name).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.WHITE)), List.of());
	}

	public static ItemStack filler() {
		return pane(Items.GRAY_STAINED_GLASS_PANE, " ");
	}

	/**
	 * A player head showing the given player's skin. Built through the component codec so it doesn't
	 * depend on ResolvableProfile's constructors, which have moved around between versions.
	 */
	public static ItemStack playerHead(UUID id, String playerName, Component displayName, List<Component> lore) {
		ItemStack stack = named(Items.PLAYER_HEAD, displayName, lore);
		try {
			CompoundTag tag = new CompoundTag();
			tag.putString("name", playerName);
			tag.putIntArray("id", UUIDUtil.uuidToIntArray(id));
			DataComponents.PROFILE.codecOrThrow()
					.parse(NbtOps.INSTANCE, tag)
					.result()
					.ifPresent(profile -> stack.set(DataComponents.PROFILE, profile));
		} catch (RuntimeException e) {
			BountyHunterMod.LOGGER.debug("Could not attach skin profile for {}", playerName, e);
		}
		return stack;
	}

	public static Component title(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(color));
	}

	public static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(color));
	}
}
