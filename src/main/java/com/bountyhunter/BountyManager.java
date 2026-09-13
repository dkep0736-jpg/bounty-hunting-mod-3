package com.bountyhunter;

import com.bountyhunter.data.Bounty;
import com.bountyhunter.data.BountyData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * All the game rules live here. GUI and commands are thin wrappers around these methods.
 */
public final class BountyManager {
	private BountyManager() {}

	/** NBT key inside minecraft:custom_data that marks a compass as a bounty compass and names its target. */
	public static final String COMPASS_TARGET_KEY = "bh_target";
	public static final String COMPASS_TARGET_NAME_KEY = "bh_target_name";

	public static final MutableComponent PREFIX = Component.literal("[Bounty] ").withStyle(ChatFormatting.GOLD);

	private static int tickCounter = 0;

	// =====================================================================
	// Placing bounties
	// =====================================================================

	public sealed interface PlaceResult {
		record Ok(Bounty bounty) implements PlaceResult {}
		record Fail(Component reason) implements PlaceResult {}
	}

	/** Why a target can't currently receive a bounty, or empty if they can. */
	public static Optional<Component> whyNotTargetable(MinecraftServer server, ServerPlayer placer, UUID target) {
		if (placer.getUUID().equals(target)) {
			return Optional.of(Component.literal("You can't put a bounty on yourself."));
		}
		BountyData data = BountyData.get(server);
		long remaining = data.cooldownRemaining(target);
		if (remaining > 0) {
			return Optional.of(Component.literal(data.nameOf(target) + " was killed recently. Bounties allowed again in " + formatDuration(remaining) + "."));
		}
		Optional<Bounty> existing = data.getBounty(target);
		if (existing.isPresent() && existing.get().rewards().size() >= BountyHunterMod.CONFIG.maxRewardsPerTarget) {
			return Optional.of(Component.literal("That player already has the maximum number of rewards stacked on them."));
		}
		return Optional.empty();
	}

	/**
	 * Takes ownership of {@code reward} (caller must have already removed it from the placer's inventory).
	 */
	public static PlaceResult placeBounty(MinecraftServer server, ServerPlayer placer, UUID target, String targetName, ItemStack reward) {
		if (reward.isEmpty()) {
			return new PlaceResult.Fail(Component.literal("Put an item in the reward slot first."));
		}
		Optional<Component> blocked = whyNotTargetable(server, placer, target);
		if (blocked.isPresent()) {
			return new PlaceResult.Fail(blocked.get());
		}

		BountyData data = BountyData.get(server);
		Bounty.RewardEntry entry = new Bounty.RewardEntry(placer.getUUID(), placer.getName().getString(), reward.copy(), System.currentTimeMillis());
		Bounty bounty = data.addReward(target, targetName, entry);

		if (BountyHunterMod.CONFIG.broadcastEvents) {
			broadcast(server, PREFIX.copy()
					.append(Component.literal(placer.getName().getString()).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" placed a bounty on ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(targetName).withStyle(ChatFormatting.RED))
					.append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(reward.getCount() + "x ").withStyle(ChatFormatting.AQUA))
					.append(reward.getHoverName().copy().withStyle(ChatFormatting.AQUA))
					.append(Component.literal("  (total: " + bounty.rewardSummary() + ")").withStyle(ChatFormatting.GRAY)));
		}

		ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
		if (targetPlayer != null) {
			targetPlayer.sendSystemMessage(PREFIX.copy().append(Component.literal("There is a bounty on your head! Watch your back.").withStyle(ChatFormatting.RED)));
		}
		return new PlaceResult.Ok(bounty);
	}

	/** Admin/placer removal. Returns items to placers who are online; offline placers' items are dropped at the remover. */
	public static boolean removeBounty(MinecraftServer server, UUID target, ServerPlayer remover, boolean refund) {
		BountyData data = BountyData.get(server);
		Optional<Bounty> removed = data.removeBounty(target);
		if (removed.isEmpty()) return false;

		if (refund) {
			for (Bounty.RewardEntry e : removed.get().rewards()) {
				ServerPlayer placer = server.getPlayerList().getPlayer(e.placer());
				ServerPlayer receiver = placer != null ? placer : remover;
				if (receiver != null) {
					giveOrDrop(receiver, e.item().copy());
				}
			}
		}
		removeCompassesFor(server, target);
		if (BountyHunterMod.CONFIG.broadcastEvents) {
			broadcast(server, PREFIX.copy().append(Component.literal("The bounty on " + removed.get().targetName() + " has been withdrawn.").withStyle(ChatFormatting.GRAY)));
		}
		return true;
	}

	// =====================================================================
	// Claiming (death handling)
	// =====================================================================

	public static void onLivingDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer victim)) return;
		MinecraftServer server = victim.level().getServer();
		if (server == null) return;

		BountyData data = BountyData.get(server);
		Optional<Bounty> maybe = data.getBounty(victim.getUUID());
		if (maybe.isEmpty()) return;

		ServerPlayer killer = null;
		if (source.getEntity() instanceof ServerPlayer p) {
			killer = p;
		} else if (victim.getKillCredit() instanceof ServerPlayer p) {
			killer = p;
		}
		if (killer == null || killer.getUUID().equals(victim.getUUID())) {
			// Died to a mob/fall/etc. or suicide: bounty stays up.
			return;
		}

		Bounty bounty = maybe.get();
		data.removeBounty(victim.getUUID());
		data.setCooldownUntil(victim.getUUID(), System.currentTimeMillis() + BountyHunterMod.CONFIG.cooldownMillis());

		for (Bounty.RewardEntry e : bounty.rewards()) {
			giveOrDrop(killer, e.item().copy());
		}

		int removedCompasses = removeCompassesFor(server, victim.getUUID());
		BountyHunterMod.LOGGER.info("{} claimed the bounty on {} ({}); removed {} compasses",
				killer.getName().getString(), victim.getName().getString(), bounty.rewardSummary(), removedCompasses);

		if (BountyHunterMod.CONFIG.broadcastEvents) {
			broadcast(server, PREFIX.copy()
					.append(Component.literal(killer.getName().getString()).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" claimed the bounty on ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(victim.getName().getString()).withStyle(ChatFormatting.RED))
					.append(Component.literal(" and collected " + bounty.rewardSummary() + "!").withStyle(ChatFormatting.WHITE)));
			broadcast(server, PREFIX.copy().append(Component.literal(
					victim.getName().getString() + " can't be targeted again for " + formatDuration(BountyHunterMod.CONFIG.cooldownMillis()) + ".").withStyle(ChatFormatting.GRAY)));
		}
	}

	// =====================================================================
	// Compasses
	// =====================================================================

	public static ItemStack createCompass(UUID target, String targetName) {
		ItemStack stack = new ItemStack(Items.COMPASS);

		CompoundTag tag = new CompoundTag();
		tag.putString(COMPASS_TARGET_KEY, target.toString());
		tag.putString(COMPASS_TARGET_NAME_KEY, targetName);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

		// Starts "spinning" until the tracker tick finds the target in range.
		stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.empty(), false));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Bounty Compass: " + targetName)
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.RED)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				gray("Points at " + targetName + " within " + (int) BountyHunterMod.CONFIG.compassRangeBlocks + " blocks."),
				gray("Spins when they are out of range or in another dimension."),
				gray("Disappears when the bounty is claimed.")
		)));
		return stack;
	}

	/** UUID of the target if this stack is a bounty compass, else empty. */
	public static Optional<UUID> compassTarget(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.COMPASS)) return Optional.empty();
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null || !custom.contains(COMPASS_TARGET_KEY)) return Optional.empty();
		String raw = custom.copyTag().getStringOr(COMPASS_TARGET_KEY, "");
		try {
			return Optional.of(UUID.fromString(raw));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public static boolean hasCompassFor(ServerPlayer player, UUID target) {
		return containsCompass(player.getInventory(), target) || containsCompass(player.getEnderChestInventory(), target);
	}

	private static boolean containsCompass(Container container, UUID target) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			Optional<UUID> t = compassTarget(container.getItem(i));
			if (t.isPresent() && t.get().equals(target)) return true;
		}
		return false;
	}

	/** Deletes every bounty compass for {@code target} from all online players. Offline players are cleaned on join. */
	public static int removeCompassesFor(MinecraftServer server, UUID target) {
		int removed = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			removed += purge(p, p.getInventory(), target);
			removed += purge(p, p.getEnderChestInventory(), target);
			Optional<UUID> carried = compassTarget(p.containerMenu.getCarried());
			if (carried.isPresent() && carried.get().equals(target)) {
				p.containerMenu.setCarried(ItemStack.EMPTY);
				removed++;
			}
		}
		return removed;
	}

	private static int purge(ServerPlayer owner, Container container, UUID target) {
		int removed = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			Optional<UUID> t = compassTarget(container.getItem(i));
			if (t.isPresent() && t.get().equals(target)) {
				container.setItem(i, ItemStack.EMPTY);
				removed++;
			}
		}
		if (removed > 0) {
			container.setChanged();
			owner.sendSystemMessage(PREFIX.copy().append(Component.literal("Your bounty compass crumbled to dust - that bounty is over.").withStyle(ChatFormatting.GRAY)));
		}
		return removed;
	}

	/** Called every server tick. Refreshes compass headings and purges compasses whose bounty no longer exists. */
	public static void tick(MinecraftServer server) {
		int interval = Math.max(1, BountyHunterMod.CONFIG.compassUpdateIntervalTicks);
		if (++tickCounter % interval != 0) return;

		BountyData data = BountyData.get(server);
		for (ServerPlayer holder : server.getPlayerList().getPlayers()) {
			refreshContainer(server, data, holder, holder.getInventory());
			// Ender chest contents don't tick, but stale compasses in there should still vanish.
			purgeStale(data, holder, holder.getEnderChestInventory());
		}
	}

	public static void refreshPlayer(MinecraftServer server, ServerPlayer holder) {
		BountyData data = BountyData.get(server);
		refreshContainer(server, data, holder, holder.getInventory());
		purgeStale(data, holder, holder.getEnderChestInventory());
	}

	private static void refreshContainer(MinecraftServer server, BountyData data, ServerPlayer holder, Container container) {
		double rangeSq = BountyHunterMod.CONFIG.compassRangeBlocks * BountyHunterMod.CONFIG.compassRangeBlocks;
		boolean changed = false;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			Optional<UUID> maybeTarget = compassTarget(stack);
			if (maybeTarget.isEmpty()) continue;
			UUID target = maybeTarget.get();

			if (!data.hasBounty(target)) {
				container.setItem(i, ItemStack.EMPTY);
				changed = true;
				holder.sendSystemMessage(PREFIX.copy().append(Component.literal("Your bounty compass crumbled to dust - that bounty is over.").withStyle(ChatFormatting.GRAY)));
				continue;
			}

			ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
			LodestoneTracker desired;
			if (targetPlayer != null
					&& targetPlayer.level().dimension().equals(holder.level().dimension())
					&& holder.distanceToSqr(targetPlayer) <= rangeSq) {
				desired = new LodestoneTracker(Optional.of(GlobalPos.of(targetPlayer.level().dimension(), targetPlayer.blockPosition())), false);
			} else {
				desired = new LodestoneTracker(Optional.empty(), false);
			}

			if (!Objects.equals(stack.get(DataComponents.LODESTONE_TRACKER), desired)) {
				stack.set(DataComponents.LODESTONE_TRACKER, desired);
				changed = true;
			}
		}
		if (changed) {
			container.setChanged();
		}
	}

	private static void purgeStale(BountyData data, ServerPlayer holder, Container container) {
		boolean changed = false;
		for (int i = 0; i < container.getContainerSize(); i++) {
			Optional<UUID> t = compassTarget(container.getItem(i));
			if (t.isPresent() && !data.hasBounty(t.get())) {
				container.setItem(i, ItemStack.EMPTY);
				changed = true;
			}
		}
		if (changed) container.setChanged();
	}

	// =====================================================================
	// Helpers
	// =====================================================================

	/** Resolve a typed name to a UUID: online players first, then anyone who has joined before. */
	public static Optional<UUID> resolvePlayer(MinecraftServer server, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) return Optional.of(online.getUUID());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getName().getString().equalsIgnoreCase(name)) return Optional.of(p.getUUID());
		}
		return BountyData.get(server).findKnownPlayer(name);
	}

	public static List<String> suggestableNames(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) names.add(p.getName().getString());
		for (String n : BountyData.get(server).knownPlayers().values()) {
			if (!names.contains(n)) names.add(n);
		}
		return names;
	}

	public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}

	public static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	public static String formatDuration(long millis) {
		long totalMinutes = Math.max(0, millis / 60_000L);
		long hours = totalMinutes / 60;
		long minutes = totalMinutes % 60;
		if (hours > 0) return hours + "h " + minutes + "m";
		if (minutes > 0) return minutes + "m";
		return "less than a minute";
	}

	public static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
	}
}
