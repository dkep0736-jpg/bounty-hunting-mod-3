package com.bountyhunter;

import com.bountyhunter.data.Bounty;
import com.bountyhunter.data.BountyData;
import com.bountyhunter.gui.BountyBoardMenu;
import com.bountyhunter.gui.SetBountyMenu;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BountyCommands {
	private BountyCommands() {}

	private static final SuggestionProvider<CommandSourceStack> ANY_PLAYER = (ctx, builder) ->
			SharedSuggestionProvider.suggest(BountyManager.suggestableNames(ctx.getSource().getServer()), builder);

	private static final SuggestionProvider<CommandSourceStack> BOUNTY_TARGETS = (ctx, builder) -> {
		List<String> names = new ArrayList<>();
		for (Bounty b : BountyData.get(ctx.getSource().getServer()).allBounties()) names.add(b.targetName());
		return SharedSuggestionProvider.suggest(names, builder);
	};

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bounty")
				.executes(ctx -> {
					BountyBoardMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("board").executes(ctx -> {
					BountyBoardMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("set")
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests(ANY_PLAYER)
								.executes(BountyCommands::set)))
				.then(Commands.literal("compass")
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests(BOUNTY_TARGETS)
								.executes(BountyCommands::compass)))
				.then(Commands.literal("list").executes(BountyCommands::list))
				.then(Commands.literal("cancel")
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests(BOUNTY_TARGETS)
								.executes(BountyCommands::cancel)))
				.then(Commands.literal("admin")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("remove")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests(BOUNTY_TARGETS)
										.executes(ctx -> adminRemove(ctx, true))))
						.then(Commands.literal("delete")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests(BOUNTY_TARGETS)
										.executes(ctx -> adminRemove(ctx, false))))
						.then(Commands.literal("clearcooldown")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests(ANY_PLAYER)
										.executes(BountyCommands::clearCooldown)))
						.then(Commands.literal("reload").executes(ctx -> {
							BountyHunterMod.CONFIG = BountyConfig.load();
							ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal("Config reloaded.")), true);
							return 1;
						}))));
	}

	private static Optional<UUID> resolveOrFail(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		Optional<UUID> id = BountyManager.resolvePlayer(ctx.getSource().getServer(), name);
		if (id.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Unknown player '" + name + "'. They must have joined this server at least once."));
		}
		return id;
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer placer = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		Optional<UUID> id = resolveOrFail(ctx);
		if (id.isEmpty()) return 0;

		Optional<Component> blocked = BountyManager.whyNotTargetable(server, placer, id.get());
		if (blocked.isPresent()) {
			ctx.getSource().sendFailure(blocked.get());
			return 0;
		}
		SetBountyMenu.open(placer, id.get(), SetBountyMenu.displayName(server, id.get()));
		return 1;
	}

	private static int compass(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer hunter = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		Optional<UUID> id = resolveOrFail(ctx);
		if (id.isEmpty()) return 0;
		BountyBoardMenu.giveCompass(server, hunter, id.get(), BountyData.get(server).nameOf(id.get()));
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		BountyData data = BountyData.get(server);
		if (data.allBounties().isEmpty()) {
			ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal("No active bounties.").withStyle(ChatFormatting.GRAY)), false);
		} else {
			ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal("Active bounties:").withStyle(ChatFormatting.WHITE)), false);
			for (Bounty b : data.allBounties()) {
				boolean online = server.getPlayerList().getPlayer(b.target()) != null;
				ctx.getSource().sendSuccess(() -> Component.literal("  " + b.targetName())
						.withStyle(ChatFormatting.RED)
						.append(Component.literal(online ? " (online) " : " (offline) ").withStyle(online ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
						.append(Component.literal(b.rewardSummary()).withStyle(ChatFormatting.AQUA)), false);
			}
		}
		Map<UUID, Long> cooldowns = data.allCooldowns();
		if (!cooldowns.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("Protected (recently killed):").withStyle(ChatFormatting.YELLOW), false);
			for (Map.Entry<UUID, Long> e : cooldowns.entrySet()) {
				long left = e.getValue() - System.currentTimeMillis();
				if (left <= 0) continue;
				String line = "  " + data.nameOf(e.getKey()) + ": " + BountyManager.formatDuration(left);
				ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
			}
		}
		return 1;
	}

	/** A placer withdraws their own contribution(s). If nothing is left, the bounty disappears. */
	private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer placer = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		if (!BountyHunterMod.CONFIG.allowPlacerToCancel) {
			ctx.getSource().sendFailure(Component.literal("Cancelling bounties is disabled on this server."));
			return 0;
		}
		Optional<UUID> id = resolveOrFail(ctx);
		if (id.isEmpty()) return 0;

		BountyData data = BountyData.get(server);
		Optional<Bounty> bounty = data.getBounty(id.get());
		if (bounty.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("There is no bounty on that player."));
			return 0;
		}
		List<Bounty.RewardEntry> mine = new ArrayList<>();
		List<Bounty.RewardEntry> others = new ArrayList<>();
		for (Bounty.RewardEntry e : bounty.get().rewards()) {
			(e.placer().equals(placer.getUUID()) ? mine : others).add(e);
		}
		if (mine.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("You didn't place any of the rewards on that bounty."));
			return 0;
		}
		for (Bounty.RewardEntry e : mine) BountyManager.giveOrDrop(placer, e.item().copy());

		if (others.isEmpty()) {
			BountyManager.removeBounty(server, id.get(), placer, false);
		} else {
			data.removeBounty(id.get());
			for (Bounty.RewardEntry e : others) data.addReward(id.get(), bounty.get().targetName(), e);
		}
		ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal("Your reward(s) were returned.").withStyle(ChatFormatting.GREEN)), false);
		return 1;
	}

	private static int adminRemove(CommandContext<CommandSourceStack> ctx, boolean refund) {
		MinecraftServer server = ctx.getSource().getServer();
		Optional<UUID> id = resolveOrFail(ctx);
		if (id.isEmpty()) return 0;
		ServerPlayer remover = ctx.getSource().getPlayer(); // may be null from console
		boolean ok = BountyManager.removeBounty(server, id.get(), remover, refund);
		if (!ok) {
			ctx.getSource().sendFailure(Component.literal("There is no bounty on that player."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal(refund ? "Bounty removed and rewards refunded." : "Bounty deleted.")), true);
		return 1;
	}

	private static int clearCooldown(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		Optional<UUID> id = resolveOrFail(ctx);
		if (id.isEmpty()) return 0;
		BountyData.get(server).clearCooldown(id.get());
		ctx.getSource().sendSuccess(() -> BountyManager.PREFIX.copy().append(Component.literal("Cooldown cleared.")), true);
		return 1;
	}
}
