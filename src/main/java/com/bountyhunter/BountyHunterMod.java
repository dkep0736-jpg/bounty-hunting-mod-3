package com.bountyhunter;

import com.bountyhunter.data.Bounty;
import com.bountyhunter.data.BountyData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BountyHunterMod implements ModInitializer {
	public static final String MOD_ID = "bountyhunter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static BountyConfig CONFIG = new BountyConfig();

	private static final Queue<Runnable> NEXT_TICK = new ConcurrentLinkedQueue<>();

	/** Run something at the end of the next server tick (used to open/close menus outside of click handlers). */
	public static void runNextTick(Runnable task) {
		NEXT_TICK.add(task);
	}

	@Override
	public void onInitialize() {
		CONFIG = BountyConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BountyCommands.register(dispatcher));

		ServerLivingEntityEvents.AFTER_DEATH.register(BountyManager::onLivingDeath);

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Runnable task;
			while ((task = NEXT_TICK.poll()) != null) {
				try {
					task.run();
				} catch (RuntimeException e) {
					LOGGER.error("Deferred task failed", e);
				}
			}
			BountyManager.tick(server);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			BountyData data = BountyData.get(server);
			data.rememberPlayer(player.getUUID(), player.getName().getString());

			// Compasses left in an offline player's inventory get cleaned up here.
			BountyManager.refreshPlayer(server, player);

			Optional<Bounty> onMe = data.getBounty(player.getUUID());
			if (onMe.isPresent()) {
				player.sendSystemMessage(BountyManager.PREFIX.copy().append(Component.literal(
						"There is a bounty on your head (" + onMe.get().rewardSummary() + "). Hunters may be tracking you.").withStyle(ChatFormatting.RED)));
			}
		});

		LOGGER.info("Bounty Hunter loaded: compass range {} blocks, cooldown {}h", CONFIG.compassRangeBlocks, CONFIG.cooldownHours);
	}
}
