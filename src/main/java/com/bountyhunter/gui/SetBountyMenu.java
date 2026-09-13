package com.bountyhunter.gui;

import com.bountyhunter.BountyHunterMod;
import com.bountyhunter.BountyManager;
import com.bountyhunter.data.BountyData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * 9x3 chest-style menu (works with vanilla clients):
 *
 * <pre>
 *  [ ][ ][ ][ ][head][ ][ ][ ][ ]
 *  [ ][ ][OK][ ][ITEM][ ][NO][ ][ ]
 *  [ ][ ][ ][ ][ ][ ][ ][ ][ ]
 * </pre>
 */
public class SetBountyMenu extends AbstractContainerMenu {
	public static final int SIZE = 27;
	public static final int TARGET_INFO_SLOT = 4;
	public static final int CONFIRM_SLOT = 11;
	public static final int REWARD_SLOT = 13;
	public static final int CANCEL_SLOT = 15;

	private static final int PLAYER_INV_START = SIZE;          // 27
	private static final int PLAYER_INV_END = SIZE + 36;       // 63 (exclusive)

	private final SimpleContainer container = new SimpleContainer(SIZE);
	private final ServerPlayer placer;
	private final UUID target;
	private final String targetName;
	private boolean finished = false;

	public static void open(ServerPlayer placer, UUID target, String targetName) {
		placer.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new SetBountyMenu(id, inv, placer, target, targetName),
				Component.literal("Bounty on " + targetName)
		));
	}

	public SetBountyMenu(int containerId, Inventory playerInventory, ServerPlayer placer, UUID target, String targetName) {
		super(MenuType.GENERIC_9x3, containerId);
		this.placer = placer;
		this.target = target;
		this.targetName = targetName;

		for (int i = 0; i < SIZE; i++) {
			int x = 8 + (i % 9) * 18;
			int y = 18 + (i / 9) * 18;
			if (i == REWARD_SLOT) {
				addSlot(new Slot(container, i, x, y));
			} else {
				addSlot(new DisplaySlot(container, i, x, y));
			}
		}
		addStandardInventorySlots(playerInventory, 8, 84);
		layout();
	}

	private void layout() {
		ItemStack filler = GuiItems.filler();
		for (int i = 0; i < SIZE; i++) {
			if (i != REWARD_SLOT) container.setItem(i, filler.copy());
		}
		container.setItem(TARGET_INFO_SLOT, GuiItems.playerHead(target, targetName,
				GuiItems.title("Target: " + targetName, ChatFormatting.RED),
				List.of(
						GuiItems.line("Drag the reward item into the", ChatFormatting.GRAY),
						GuiItems.line("center slot, then click Confirm.", ChatFormatting.GRAY),
						GuiItems.line("Whoever kills " + targetName + " gets it.", ChatFormatting.GRAY)
				)));
		container.setItem(CONFIRM_SLOT, GuiItems.named(Items.LIME_STAINED_GLASS_PANE,
				GuiItems.title("Confirm bounty", ChatFormatting.GREEN),
				List.of(GuiItems.line("Locks in the item in the center slot.", ChatFormatting.GRAY))));
		container.setItem(CANCEL_SLOT, GuiItems.named(Items.RED_STAINED_GLASS_PANE,
				GuiItems.title("Cancel", ChatFormatting.RED),
				List.of(GuiItems.line("Closes this and returns your item.", ChatFormatting.GRAY))));
	}

	// ---------------------------------------------------------------
	// Click handling
	// ---------------------------------------------------------------

	@Override
	public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
		// Clicks on our decorative/button slots never move items.
		if (slotIndex >= 0 && slotIndex < SIZE && slotIndex != REWARD_SLOT) {
			if (player instanceof ServerPlayer sp) {
				if (slotIndex == CONFIRM_SLOT) confirm(sp);
				else if (slotIndex == CANCEL_SLOT) cancel(sp);
			}
			// The client predicted a pickup; push the real state back so nothing looks stuck to the cursor.
			sendAllDataToRemote();
			return;
		}
		super.clicked(slotIndex, button, input, player);
	}

	private void confirm(ServerPlayer sp) {
		if (finished) return;
		MinecraftServer server = sp.level().getServer();
		ItemStack reward = container.getItem(REWARD_SLOT);
		if (reward.isEmpty()) {
			sp.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("Put an item in the center slot first.").withStyle(ChatFormatting.RED)));
			return;
		}

		ItemStack taken = reward.copy();
		container.setItem(REWARD_SLOT, ItemStack.EMPTY);
		BountyManager.PlaceResult result = BountyManager.placeBounty(server, sp, target, targetName, taken);
		if (result instanceof BountyManager.PlaceResult.Fail fail) {
			// Give it back and keep the menu open.
			container.setItem(REWARD_SLOT, taken);
			sp.sendSystemMessage(BountyManager.PREFIX.copy().append(fail.reason().copy().withStyle(ChatFormatting.RED)));
			return;
		}

		finished = true;
		sp.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("Bounty placed on " + targetName + ". Good luck to the hunters.").withStyle(ChatFormatting.GREEN)));
		BountyHunterMod.runNextTick(sp::closeContainer);
	}

	private void cancel(ServerPlayer sp) {
		BountyHunterMod.runNextTick(sp::closeContainer);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();

		if (index == REWARD_SLOT) {
			if (!moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, true)) return ItemStack.EMPTY;
		} else if (index >= PLAYER_INV_START && index < PLAYER_INV_END) {
			if (!moveItemStackTo(stack, REWARD_SLOT, REWARD_SLOT + 1, false)) return ItemStack.EMPTY;
		} else {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		// Whatever is still sitting in the reward slot goes back to the player (or drops if they logged out).
		ItemStack leftover = container.removeItemNoUpdate(REWARD_SLOT);
		if (!leftover.isEmpty()) {
			SimpleContainer tmp = new SimpleContainer(1);
			tmp.setItem(0, leftover);
			clearContainer(player, tmp);
		}
	}

	public static boolean canOpen(MinecraftServer server, ServerPlayer placer, UUID target) {
		return BountyManager.whyNotTargetable(server, placer, target).isEmpty();
	}

	public static String displayName(MinecraftServer server, UUID target) {
		return BountyData.get(server).nameOf(target);
	}
}
