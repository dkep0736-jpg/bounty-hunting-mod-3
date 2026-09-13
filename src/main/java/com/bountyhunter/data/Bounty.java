package com.bountyhunter.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An active bounty on one player. Several people can pile rewards onto the same target;
 * whoever gets the kill takes all of them.
 */
public record Bounty(UUID target, String targetName, List<RewardEntry> rewards) {

	public record RewardEntry(UUID placer, String placerName, ItemStack item, long placedAt) {
		public static final Codec<RewardEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("placer").forGetter(RewardEntry::placer),
				Codec.STRING.fieldOf("placer_name").forGetter(RewardEntry::placerName),
				ItemStack.CODEC.fieldOf("item").forGetter(RewardEntry::item),
				Codec.LONG.fieldOf("placed_at").forGetter(RewardEntry::placedAt)
		).apply(instance, RewardEntry::new));
	}

	public static final Codec<Bounty> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("target").forGetter(Bounty::target),
			Codec.STRING.fieldOf("target_name").forGetter(Bounty::targetName),
			RewardEntry.CODEC.listOf().fieldOf("rewards").forGetter(Bounty::rewards)
	).apply(instance, Bounty::new));

	public Bounty {
		rewards = List.copyOf(rewards);
	}

	public Bounty withReward(RewardEntry entry) {
		List<RewardEntry> list = new ArrayList<>(rewards);
		list.add(entry);
		return new Bounty(target, targetName, list);
	}

	public Bounty withName(String name) {
		return new Bounty(target, name, rewards);
	}

	/** Short human summary, e.g. "3x Diamond, 1x Netherite Sword". */
	public String rewardSummary() {
		StringBuilder sb = new StringBuilder();
		for (RewardEntry e : rewards) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(e.item().getCount()).append("x ").append(e.item().getHoverName().getString());
		}
		return sb.length() == 0 ? "nothing" : sb.toString();
	}
}
