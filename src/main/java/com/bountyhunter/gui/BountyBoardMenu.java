package com.bountyhunter.gui;

import com.bountyhunter.BountyHunterMod;
import com.bountyhunter.BountyManager;
import com.bountyhunter.data.Bounty;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 9x6 chest-style menu. Rows 1-5 are player heads (bounty targets first, then everyone else online).
 * Left-click a head to place a bounty, right-click to take a tracking compass. Bottom row is info/close.
 */
public class BountyBoardMenu extends AbstractContainerMenu {
	public static final int SIZE = 54;
	private static final int HEAD_SLOTS = 45;
	private static final int COOLDOWN_INFO_SLOT = 45;
	private static final int HELP_SLOT = 49;
	private static final int CLOSE_SLOT = 53;

	private final SimpleContainer container = new SimpleContainer(SIZE);
	private final ServerPlayer viewer;
	private final Map<Integer, UUID> slotTargets = new HashMap<>();
	private final Map<Integer, String> slotNames = new HashMap<>();

	public static void open(ServerPlayer viewer) {
		viewer.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new BountyBoardMenu(id, inv, viewer),
				Component.literal("Bounty Board")
		));
	}

	public BountyBoardMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(MenuType.GENERIC_9x6, containerId);
		this.viewer = viewer;
		for (int i = 0; i < SIZE; i++) {
			addSlot(new DisplaySlot(container, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18));
		}
		addStandardInventorySlots(playerInventory, 8, 140);
		populate();
	}

	private void populate() {
		MinecraftServer server = viewer.level().getServer();
		BountyData data = BountyData.get(server);

		// Ordered: active bounty targets first, then other online players.
		Set<UUID> ordered = new LinkedHashSet<>();
		for (Bounty b : data.allBounties()) ordered.add(b.target());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) ordered.add(p.getUUID());
		ordered.remove(viewer.getUUID());

		int slot = 0;
		for (UUID id : ordered) {
			if (slot >= HEAD_SLOTS) break;
			String name = data.nameOf(id);
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			if (online != null) name = online.getName().getString();

			Optional<Bounty> bounty = data.getBounty(id);
			long cooldown = data.cooldownRemaining(id);
			List<Component> lore = new ArrayList<>();

			if (bounty.isPresent()) {
				lore.add(GuiItems.line("Reward: " + bounty.get().rewardSummary(), ChatFormatting.AQUA));
				for (Bounty.RewardEntry e : bounty.get().rewards()) {
					lore.add(GuiItems.line("  " + e.item().getCount() + "x " + e.item().getHoverName().getString() + " - by " + e.placerName(), ChatFormatting.DARK_AQUA));
				}
			} else {
				lore.add(GuiItems.line("No bounty yet.", ChatFormatting.GRAY));
			}
			lore.add(GuiItems.line(online != null ? "Online" : "Offline", online != null ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
			if (cooldown > 0) {
				lore.add(GuiItems.line("Protected for " + BountyManager.formatDuration(cooldown), ChatFormatting.YELLOW));
			} else {
				lore.add(GuiItems.line("Left-click: place a bounty", ChatFormatting.WHITE));
			}
			if (bounty.isPresent()) {
				lore.add(GuiItems.line("Right-click: take a tracking compass", ChatFormatting.WHITE));
			}

			ChatFormatting nameColor = bounty.isPresent() ? ChatFormatting.RED : (cooldown > 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
			container.setItem(slot, GuiItems.playerHead(id, name, GuiItems.title(name, nameColor), lore));
			slotTargets.put(slot, id);
			slotNames.put(slot, name);
			slot++;
		}

		for (int i = slot; i < HEAD_SLOTS; i++) container.setItem(i, ItemStack.EMPTY);
		for (int i = HEAD_SLOTS; i < SIZE; i++) container.setItem(i, GuiItems.filler());

		List<Component> cooldownLore = new ArrayList<>();
		if (data.allCooldowns().isEmpty()) {
			cooldownLore.add(GuiItems.line("Nobody is protected right now.", ChatFormatting.GRAY));
		} else {
			for (Map.Entry<UUID, Long> e : data.allCooldowns().entrySet()) {
				long left = e.getValue() - System.currentTimeMillis();
				if (left <= 0) continue;
				cooldownLore.add(GuiItems.line(data.nameOf(e.getKey()) + ": " + BountyManager.formatDuration(left), ChatFormatting.YELLOW));
			}
		}
		container.setItem(COOLDOWN_INFO_SLOT, GuiItems.named(Items.CLOCK, GuiItems.title("Protected players", ChatFormatting.YELLOW), cooldownLore));

		container.setItem(HELP_SLOT, GuiItems.named(Items.BOOK, GuiItems.title("How it works", ChatFormatting.GOLD), List.of(
				GuiItems.line("Left-click a head to put a bounty on them.", ChatFormatting.GRAY),
				GuiItems.line("Right-click a red head for a compass that", ChatFormatting.GRAY),
				GuiItems.line("points at them within " + (int) BountyHunterMod.CONFIG.compassRangeBlocks + " blocks.", ChatFormatting.GRAY),
				GuiItems.line("Kill the target to collect every reward.", ChatFormatting.GRAY),
				GuiItems.line("Killed targets are protected for " + BountyManager.formatDuration(BountyHunterMod.CONFIG.cooldownMillis()) + ".", ChatFormatting.GRAY),
				GuiItems.line("Commands: /bounty set <name>, /bounty compass <name>, /bounty list", ChatFormatting.DARK_GRAY)
		)));
		container.setItem(CLOSE_SLOT, GuiItems.named(Items.BARRIER, GuiItems.title("Close", ChatFormatting.RED), List.of()));
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < SIZE) {
			if (player instanceof ServerPlayer sp) handleClick(slotIndex, button, sp);
			// The client predicted a pickup; push the real state back so nothing looks stuck to the cursor.
			sendAllDataToRemote();
			return;
		}
		super.clicked(slotIndex, button, input, player);
	}

	private void handleClick(int slotIndex, int button, ServerPlayer sp) {
		if (slotIndex == CLOSE_SLOT) {
			BountyHunterMod.runNextTick(sp::closeContainer);
			return;
		}
		UUID target = slotTargets.get(slotIndex);
		if (target == null) return;
		String name = slotNames.get(slotIndex);
		MinecraftServer server = sp.level().getServer();

		if (button == 1) {
			// Right-click: tracking compass
			giveCompass(server, sp, target, name);
			return;
		}

		Optional<Component> blocked = BountyManager.whyNotTargetable(server, sp, target);
		if (blocked.isPresent()) {
			sp.sendSystemMessage(BountyManager.PREFIX.copy().append(blocked.get().copy().withStyle(ChatFormatting.RED)));
			return;
		}
		BountyHunterMod.runNextTick(() -> SetBountyMenu.open(sp, target, name));
	}

	public static void giveCompass(MinecraftServer server, ServerPlayer hunter, UUID target, String name) {
		BountyData data = BountyData.get(server);
		if (!data.hasBounty(target)) {
			hunter.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("There is no bounty on " + name + " right now.").withStyle(ChatFormatting.RED)));
			return;
		}
		if (hunter.getUUID().equals(target)) {
			hunter.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("You already know where you are.").withStyle(ChatFormatting.RED)));
			return;
		}
		if (BountyManager.hasCompassFor(hunter, target)) {
			hunter.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("You already have a compass for " + name + ".").withStyle(ChatFormatting.RED)));
			return;
		}
		BountyManager.giveOrDrop(hunter, BountyManager.createCompass(target, name));
		hunter.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal("Tracking compass for " + name + " added. It only works within "
				+ (int) BountyHunterMod.CONFIG.compassRangeBlocks + " blocks.").withStyle(ChatFormatting.GREEN)));
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
