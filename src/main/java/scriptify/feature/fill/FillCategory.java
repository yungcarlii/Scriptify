package scriptify.feature.fill;

import java.util.ArrayList;
import net.minecraft.tags.BlockTags;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The item groups a container can be filled from.
 *
 * <p>Each category resolves to a concrete pool of items the first time it is used and then caches it —
 * the pools are derived from tags and item components, so they pick up modded items automatically
 * rather than being a hardcoded list that goes stale.
 */
public final class FillCategory {
	private static final Map<String, FillCategory> CATEGORIES = new LinkedHashMap<>();

	private final String id;
	private final String displayName;
	private final Item icon;
	private final Predicate<ItemStack> matches;
	/** Resolved lazily: the item registry is not populated when this class loads. */
	private List<Item> pool;

	private FillCategory(String id, String displayName, Item icon, Predicate<ItemStack> matches) {
		this.id = id;
		this.displayName = displayName;
		this.icon = icon;
		this.matches = matches;
	}

	private static void register(String id, String displayName, Item icon, Predicate<ItemStack> matches) {
		CATEGORIES.put(id, new FillCategory(id, displayName, icon, matches));
	}

	static {
		register("blocks", "Building Blocks", Items.STONE_BRICKS,
			stack -> stack.getItem() instanceof BlockItem && !isNatural(stack) && !isDecorative(stack));
		register("decoration", "Decoration", Items.ITEM_FRAME, FillCategory::isDecorative);
		register("redstone", "Redstone", Items.REDSTONE, FillCategory::isRedstone);
		register("combat", "Weapons", Items.DIAMOND_SWORD,
			stack -> inAny(stack, ItemTags.SWORDS, ItemTags.AXES, ItemTags.ARROWS)
				|| is(stack, Items.BOW, Items.CROSSBOW, Items.SHIELD, Items.TRIDENT, Items.MACE));
		register("armor", "Armour", Items.DIAMOND_CHESTPLATE,
			stack -> inAny(stack, ItemTags.HEAD_ARMOR, ItemTags.CHEST_ARMOR,
				ItemTags.LEG_ARMOR, ItemTags.FOOT_ARMOR));
		register("food", "Food", Items.COOKED_BEEF, stack -> stack.has(DataComponents.FOOD));
		register("potions", "Potions", Items.POTION,
			stack -> is(stack, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.HONEY_BOTTLE));
		register("valuables", "Valuables", Items.DIAMOND,
			stack -> is(stack, Items.DIAMOND, Items.EMERALD, Items.NETHERITE_INGOT, Items.NETHERITE_SCRAP,
				Items.GOLD_INGOT, Items.IRON_INGOT, Items.COPPER_INGOT, Items.LAPIS_LAZULI,
				Items.QUARTZ, Items.AMETHYST_SHARD, Items.ECHO_SHARD, Items.GOLD_NUGGET, Items.IRON_NUGGET));
		register("nature", "Nature", Items.OAK_SAPLING, FillCategory::isNatural);
		register("drops", "Mob Drops", Items.BONE,
			stack -> is(stack, Items.BONE, Items.STRING, Items.GUNPOWDER, Items.SPIDER_EYE,
				Items.ROTTEN_FLESH, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.SLIME_BALL,
				Items.LEATHER, Items.FEATHER, Items.PHANTOM_MEMBRANE, Items.MAGMA_CREAM,
				Items.GHAST_TEAR, Items.SHULKER_SHELL, Items.PRISMARINE_SHARD));
		register("discs", "Music Discs", Items.MUSIC_DISC_CAT,
			stack -> stack.has(DataComponents.JUKEBOX_PLAYABLE));
		register("books", "Books", Items.BOOK,
			stack -> is(stack, Items.BOOK, Items.ENCHANTED_BOOK, Items.WRITABLE_BOOK,
				Items.WRITTEN_BOOK, Items.KNOWLEDGE_BOOK, Items.BOOKSHELF));
		register("tools", "Tools", Items.IRON_PICKAXE,
			stack -> inAny(stack, ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.HOES)
				|| is(stack, Items.FISHING_ROD, Items.FLINT_AND_STEEL, Items.SHEARS, Items.BUCKET));
	}

	public static List<FillCategory> all() {
		return List.copyOf(CATEGORIES.values());
	}

	public static FillCategory byId(String id) {
		return CATEGORIES.get(id);
	}

	public String id() {
		return this.id;
	}

	public String displayName() {
		return this.displayName;
	}

	public Item icon() {
		return this.icon;
	}

	/** The concrete items in this category, resolved once against the live registry. */
	public List<Item> pool() {
		if (this.pool == null) {
			List<Item> resolved = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				// Random pools stay to things a player could actually have found.
				if (item == Items.AIR || !SurvivalItems.isObtainable(item)) {
					continue;
				}
				ItemStack stack = new ItemStack(item);
				if (this.matches.test(stack)) {
					resolved.add(item);
				}
			}
			this.pool = List.copyOf(resolved);
		}
		return this.pool;
	}

	public int size() {
		return pool().size();
	}

	// ---------------------------------------------------------------- predicates

	private static boolean is(ItemStack stack, Item... items) {
		for (Item item : items) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	@SafeVarargs
	private static boolean inAny(ItemStack stack, TagKey<Item>... tags) {
		for (TagKey<Item> tag : tags) {
			if (stack.is(tag)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNatural(ItemStack stack) {
		return inAny(stack, ItemTags.SAPLINGS, ItemTags.LOGS, ItemTags.LEAVES)
			|| isFlower(stack)
			|| is(stack, Items.DIRT, Items.GRASS_BLOCK, Items.PODZOL, Items.MOSS_BLOCK,
				Items.WHEAT_SEEDS, Items.BONE_MEAL, Items.KELP, Items.SUGAR_CANE);
	}

	/**
	 * Whether this is a flower.
	 *
	 * <p>26.2 removed {@code ItemTags.FLOWERS} and kept only the block tag, so the item has to be
	 * asked which block it places. Every flower is a block item, so nothing is missed by this.
	 */
	private static boolean isFlower(ItemStack stack) {
		return stack.getItem() instanceof BlockItem block
			&& block.getBlock().defaultBlockState().is(BlockTags.FLOWERS);
	}

	private static boolean isDecorative(ItemStack stack) {
		return inAny(stack, ItemTags.BANNERS, ItemTags.CANDLES, ItemTags.BEDS, ItemTags.WOOL_CARPETS)
			|| is(stack, Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME, Items.PAINTING, Items.FLOWER_POT,
				Items.LANTERN, Items.SOUL_LANTERN, Items.TORCH, Items.ARMOR_STAND);
	}

	private static boolean isRedstone(ItemStack stack) {
		return is(stack, Items.REDSTONE, Items.REDSTONE_TORCH, Items.REPEATER, Items.COMPARATOR,
			Items.PISTON, Items.STICKY_PISTON, Items.OBSERVER, Items.HOPPER, Items.DISPENSER,
			Items.DROPPER, Items.LEVER, Items.TRIPWIRE_HOOK, Items.TARGET, Items.SCULK_SENSOR,
			Items.REDSTONE_BLOCK, Items.SLIME_BLOCK, Items.HONEY_BLOCK, Items.NOTE_BLOCK);
	}
}
