package scriptify.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Fills a player's inventory with random items from a themed category.
 *
 * <p>Made for dressing a scene fast: a "looted" chest, a survival-looking inventory, or a junk drawer
 * that reads as lived-in on camera without hand-picking every stack.
 */
public final class RandomFillService {
	private static final Map<String, Category> CATEGORIES = new LinkedHashMap<>();

	static {
		register("blocks", "Building blocks", Items.STONE_BRICKS, ItemTags.STONE_BRICKS, ItemTags.PLANKS, ItemTags.WOOL);
		register("combat", "Weapons and armour", Items.DIAMOND_SWORD, ItemTags.SWORDS, ItemTags.AXES,
			ItemTags.CHEST_ARMOR, ItemTags.HEAD_ARMOR, ItemTags.LEG_ARMOR, ItemTags.FOOT_ARMOR);
		register("tools", "Tools", Items.IRON_PICKAXE, ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.HOES);
		register("decor", "Decoration", Items.FLOWER_POT, ItemTags.FLOWERS, ItemTags.CANDLES, ItemTags.BANNERS);
		register("nature", "Plants and saplings", Items.OAK_SAPLING, ItemTags.SAPLINGS, ItemTags.LEAVES, ItemTags.LOGS);
	}

	private final Random random = new Random();

	@SafeVarargs
	private static void register(String name, String description, Item icon, TagKey<Item>... tags) {
		CATEGORIES.put(name, new Category(name, description, icon, List.of(tags)));
	}

	public static List<Category> categories() {
		return List.copyOf(CATEGORIES.values());
	}

	/**
	 * Replaces the player's main inventory (hotbar and backpack, not armour) with random picks.
	 *
	 * @return how many slots were filled, or {@code -1} when the category is unknown.
	 */
	public int fill(ServerPlayer player, String categoryName) {
		Category category = CATEGORIES.get(categoryName.toLowerCase(Locale.ROOT));
		if (category == null) {
			return -1;
		}
		List<Item> pool = category.resolve();
		if (pool.isEmpty()) {
			return 0;
		}
		int filled = 0;
		// Slots 0-35 are the hotbar and main inventory; armour and offhand are deliberately left alone.
		for (int slot = 0; slot < 36; slot++) {
			Item item = pool.get(this.random.nextInt(pool.size()));
			ItemStack stack = new ItemStack(item, 1 + this.random.nextInt(Math.max(1, item.getDefaultMaxStackSize())));
			player.getInventory().setItem(slot, stack);
			filled++;
		}
		player.getInventory().setChanged();
		return filled;
	}

	public record Category(String name, String description, Item icon, List<TagKey<Item>> tags) {
		/** Expands the tags into a concrete item pool against the live registry. */
		private List<Item> resolve() {
			List<Item> items = new ArrayList<>();
			for (TagKey<Item> tag : this.tags) {
				BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(holder -> items.add(holder.value()));
			}
			return items;
		}
	}
}
