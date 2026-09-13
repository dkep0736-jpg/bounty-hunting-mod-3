package com.bountyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loaded from config/bountyhunter.json. Missing file = defaults are written out.
 */
public class BountyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Radius (blocks) inside which a bounty compass points at its target. Outside it just spins. */
	public double compassRangeBlocks = 300.0;

	/** After a bounty on someone is claimed, nobody can place a new bounty on them for this long (real time). */
	public double cooldownHours = 10.0;

	/** How often (in ticks, 20 = 1s) compasses are refreshed and stale compasses are purged. */
	public int compassUpdateIntervalTicks = 10;

	/** Announce new bounties and claimed bounties to the whole server. */
	public boolean broadcastEvents = true;

	/** If true, only the player who placed a bounty or an op can remove it. Ops can always remove. */
	public boolean allowPlacerToCancel = true;

	/** Max number of separate rewards that can be stacked on one target (each new placement adds one). */
	public int maxRewardsPerTarget = 9;

	public long cooldownMillis() {
		return (long) (cooldownHours * 60.0 * 60.0 * 1000.0);
	}

	public static BountyConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("bountyhunter.json");
		BountyConfig cfg = new BountyConfig();
		try {
			if (Files.exists(path)) {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				BountyConfig loaded = GSON.fromJson(json, BountyConfig.class);
				if (loaded != null) {
					cfg = loaded;
				}
			}
			// Always (re)write so new options show up after updates.
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			BountyHunterMod.LOGGER.error("Failed to load bountyhunter.json, using defaults", e);
		}
		return cfg;
	}
}
