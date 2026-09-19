package scriptify.feature.fill;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Filters out items a player could never legitimately obtain.
 *
 * <p>Random fill is meant to make a container look like something a player found, so a chest that
 * turns up holding a command block or a barrier immediately breaks the illusion. There is no vanilla
 * tag for "obtainable in survival", so this is an explicit list — it only needs to cover what the
 * category pools can actually reach, and anything missed is a wrong-looking item rather than a crash.
 *
 * <p>This applies to the random pools only. An item picked deliberately is always allowed: choosing a
 * barrier by hand is a decision, not an accident.
 */
public final class SurvivalItems {
	private static final Set<Item> BLOCKED = Set.of(
		// Creative and operator tools.
		Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK,
		Items.COMMAND_BLOCK_MINECART, Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID,
		Items.JIGSAW, Items.BARRIER, Items.LIGHT, Items.DEBUG_STICK, Items.KNOWLEDGE_BOOK,
		// Unbreakable or unobtainable world blocks.
		Items.BEDROCK, Items.END_PORTAL_FRAME, Items.SPAWNER, Items.TRIAL_SPAWNER, Items.VAULT,
		Items.BUDDING_AMETHYST, Items.REINFORCED_DEEPSLATE, Items.PETRIFIED_OAK_SLAB,
		Items.SUSPICIOUS_SAND, Items.SUSPICIOUS_GRAVEL,
		// Not craftable and not dropped.
		Items.FARMLAND, Items.DIRT_PATH, Items.CHORUS_PLANT, Items.FROGSPAWN);

	private SurvivalItems() {
	}

	/**
	 * @return {@code true} when this item can legitimately be obtained in survival.
	 */
	public static boolean isObtainable(Item item) {
		if (BLOCKED.contains(item)) {
			return false;
		}
		// Spawn eggs are creative-only and there are dozens of them, which would otherwise flood any
		// category they landed in.
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return !path.endsWith("_spawn_egg");
	}
}
