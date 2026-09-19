package scriptify.feature;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Builds villagers with hand-written trades, for shopkeepers and quest-givers in a scripted scene.
 *
 * <p>Scene villagers are deliberately inert: no AI, no wandering, no restocking, invulnerable, and
 * immune to despawning. A merchant that walks off mid-take or runs out of stock is useless on camera.
 */
public final class VillagerMakerService {
	/** Effectively unlimited stock, so a trade never greys out during filming. */
	private static final int UNLIMITED_USES = Integer.MAX_VALUE;

	private VillagerMakerService() {
	}

	/**
	 * Spawns a scene villager at the player's position.
	 *
	 * @param profession registry id, with or without the {@code minecraft:} prefix.
	 */
	public static Villager spawn(ServerPlayer creator, String name, String profession, int level) {
		ServerLevel world = creator.level();
		Villager villager = EntityType.VILLAGER.create(world, EntitySpawnReason.COMMAND);
		if (villager == null) {
			throw new IllegalStateException("Could not create a villager.");
		}
		villager.snapTo(creator.getX(), creator.getY(), creator.getZ(), creator.getYRot(), 0.0F);
		if (name != null && !name.isBlank()) {
			villager.setCustomName(Component.literal(name));
			villager.setCustomNameVisible(true);
		}
		applyProfession(world.getServer(), villager, profession, level);
		makeStatic(villager);
		// Start with no trades at all; vanilla would otherwise generate a random profession set.
		villager.setOffers(new MerchantOffers());
		world.addFreshEntity(villager);
		return villager;
	}

	/**
	 * Freezes a villager into a fixture: no AI, no pushing, no damage, never despawns.
	 */
	public static void makeStatic(Villager villager) {
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.setPersistenceRequired();
		villager.setSilent(true);
	}

	public static void applyProfession(MinecraftServer server, Villager villager, String profession, int level) {
		Registry<VillagerProfession> registry = server.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
		Identifier identifier = Identifier.tryParse(profession.contains(":") ? profession : "minecraft:" + profession);
		if (identifier == null) {
			throw new IllegalArgumentException("'" + profession + "' is not a valid profession id.");
		}
		var holder = registry.get(identifier)
			.orElseThrow(() -> new IllegalArgumentException("No villager profession called '" + profession + "'."));
		VillagerData data = villager.getVillagerData()
			.withProfession(holder)
			// Levels run 1-5; the badge on the villager's belt reflects this.
			.withLevel(Math.max(1, Math.min(5, level)));
		villager.setVillagerData(data);
	}

	/**
	 * Appends a trade.
	 *
	 * @param first  what the player pays.
	 * @param second an optional second payment slot, or an empty stack for none.
	 * @param result what they receive.
	 */
	public static void addTrade(Villager villager, ItemStack first, ItemStack second, ItemStack result) {
		if (first.isEmpty() || result.isEmpty()) {
			throw new IllegalArgumentException("A trade needs at least one cost and a result.");
		}
		MerchantOffers offers = villager.getOffers();
		ItemCost costA = new ItemCost(first.getItem(), first.getCount());
		Optional<ItemCost> costB = second.isEmpty()
			? Optional.empty()
			: Optional.of(new ItemCost(second.getItem(), second.getCount()));
		// Zero XP and a flat price multiplier: scene trades should not level the villager up or drift
		// in price because of demand or reputation.
		offers.add(new MerchantOffer(costA, costB, result.copy(), 0, UNLIMITED_USES, 0, 0.0F));
		villager.setOffers(offers);
	}

	/** Removes the trade at {@code index}, 1-based as shown to operators. */
	public static boolean removeTrade(Villager villager, int index) {
		MerchantOffers offers = villager.getOffers();
		if (index < 1 || index > offers.size()) {
			return false;
		}
		offers.remove(index - 1);
		villager.setOffers(offers);
		return true;
	}

	public static void clearTrades(Villager villager) {
		villager.setOffers(new MerchantOffers());
	}

	public static List<MerchantOffer> trades(Villager villager) {
		return List.copyOf(villager.getOffers());
	}

	/** Every profession id the server knows, for command suggestions. */
	public static List<String> professionIds(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION)
			.listElementIds()
			.map(key -> key.identifier().getPath())
			.sorted()
			.toList();
	}

	/**
	 * The scene villager the player is looking at, within {@code range} blocks.
	 *
	 * <p>Targeting by look direction rather than by name keeps the commands short — you point at the
	 * villager you are editing.
	 */
	public static Optional<Villager> lookingAt(ServerPlayer player, double range) {
		var eye = player.getEyePosition();
		var reach = eye.add(player.getLookAngle().scale(range));
		var box = player.getBoundingBox().expandTowards(player.getLookAngle().scale(range)).inflate(1.0D);
		return player.level().getEntitiesOfClass(Villager.class, box).stream()
			.filter(villager -> villager.getBoundingBox().inflate(0.4D).clip(eye, reach).isPresent())
			.min(java.util.Comparator.comparingDouble(villager -> villager.distanceToSqr(player)));
	}
}
