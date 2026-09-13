package com.bountyhunter.data;

import com.bountyhunter.BountyHunterMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persisted in the world save. Survives restarts.
 */
public class BountyData extends SavedData {

	private static final Codec<BountyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Bounty.CODEC).fieldOf("bounties").forGetter(d -> d.bounties),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).fieldOf("cooldowns").forGetter(d -> d.cooldowns),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).fieldOf("known_players").forGetter(d -> d.knownPlayers)
	).apply(instance, BountyData::new));

	public static final SavedDataType<BountyData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(BountyHunterMod.MOD_ID, "bounties"),
			BountyData::new,
			CODEC,
			null
	);

	/** target uuid -> bounty */
	private final Map<UUID, Bounty> bounties;
	/** target uuid -> epoch millis when a new bounty may be placed again */
	private final Map<UUID, Long> cooldowns;
	/** every uuid that has joined since the mod was installed -> last known name */
	private final Map<UUID, String> knownPlayers;

	public BountyData() {
		this(Map.of(), Map.of(), Map.of());
	}

	private BountyData(Map<UUID, Bounty> bounties, Map<UUID, Long> cooldowns, Map<UUID, String> knownPlayers) {
		this.bounties = new HashMap<>(bounties);
		this.cooldowns = new HashMap<>(cooldowns);
		this.knownPlayers = new HashMap<>(knownPlayers);
	}

	public static BountyData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	// ---- bounties ----

	public Optional<Bounty> getBounty(UUID target) {
		return Optional.ofNullable(bounties.get(target));
	}

	public boolean hasBounty(UUID target) {
		return bounties.containsKey(target);
	}

	public Collection<Bounty> allBounties() {
		return Collections.unmodifiableCollection(bounties.values());
	}

	public Bounty addReward(UUID target, String targetName, Bounty.RewardEntry entry) {
		Bounty current = bounties.get(target);
		Bounty updated = current == null
				? new Bounty(target, targetName, java.util.List.of(entry))
				: current.withName(targetName).withReward(entry);
		bounties.put(target, updated);
		setDirty();
		return updated;
	}

	public Optional<Bounty> removeBounty(UUID target) {
		Bounty removed = bounties.remove(target);
		if (removed != null) setDirty();
		return Optional.ofNullable(removed);
	}

	// ---- cooldowns ----

	public void setCooldownUntil(UUID target, long epochMillis) {
		cooldowns.put(target, epochMillis);
		setDirty();
	}

	public void clearCooldown(UUID target) {
		if (cooldowns.remove(target) != null) setDirty();
	}

	/** Millis remaining, or 0 if none. Also prunes expired entries. */
	public long cooldownRemaining(UUID target) {
		Long until = cooldowns.get(target);
		if (until == null) return 0L;
		long remaining = until - System.currentTimeMillis();
		if (remaining <= 0) {
			cooldowns.remove(target);
			setDirty();
			return 0L;
		}
		return remaining;
	}

	public Map<UUID, Long> allCooldowns() {
		return Collections.unmodifiableMap(cooldowns);
	}

	// ---- known players (so bounties can be placed on offline players by name) ----

	public void rememberPlayer(UUID id, String name) {
		String old = knownPlayers.put(id, name);
		if (!name.equals(old)) setDirty();
	}

	public Map<UUID, String> knownPlayers() {
		return Collections.unmodifiableMap(knownPlayers);
	}

	public Optional<UUID> findKnownPlayer(String name) {
		for (Map.Entry<UUID, String> e : knownPlayers.entrySet()) {
			if (e.getValue().equalsIgnoreCase(name)) return Optional.of(e.getKey());
		}
		return Optional.empty();
	}

	public String nameOf(UUID id) {
		Bounty b = bounties.get(id);
		if (b != null) return b.targetName();
		return knownPlayers.getOrDefault(id, id.toString().substring(0, 8));
	}
}
