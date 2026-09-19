package scriptify.feature;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Edits the item in a player's hand: name, lore, enchantments, glint and unbreakability.
 *
 * <p>Everything works on the live held stack rather than building a new one, so an item keeps whatever
 * else it already carries — durability, trims, potion contents — instead of being silently rebuilt.
 *
 * <p>Colour codes are accepted with {@code &} because operators type them by reflex; they are
 * translated to the section sign the client actually reads.
 */
public final class ItemMakerService {
	/** Vanilla caps enchantments at 255; beyond that the level simply stops rendering sensibly. */
	private static final int MAX_ENCHANT_LEVEL = 255;

	private ItemMakerService() {
	}

	/** Renames the item. Italics are switched off, because a custom name inherits them otherwise. */
	public static void setName(ItemStack stack, String name) {
		stack.set(DataComponents.CUSTOM_NAME, colour(name).withStyle(style -> style.withItalic(false)));
	}

	public static void clearName(ItemStack stack) {
		stack.remove(DataComponents.CUSTOM_NAME);
	}

	public static void addLoreLine(ItemStack stack, String line) {
		List<Component> lines = new ArrayList<>(currentLore(stack));
		if (lines.size() >= ItemLore.MAX_LINES) {
			throw new IllegalArgumentException("An item can hold at most " + ItemLore.MAX_LINES + " lore lines.");
		}
		lines.add(colour(line).withStyle(style -> style.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(lines));
	}

	/** Drops the last lore line — the undo for a typo, without clearing the whole block. */
	public static boolean removeLastLoreLine(ItemStack stack) {
		List<Component> lines = new ArrayList<>(currentLore(stack));
		if (lines.isEmpty()) {
			return false;
		}
		lines.remove(lines.size() - 1);
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return true;
	}

	/**
	 * Drops one line by position, keeping the rest exactly as they were.
	 *
	 * <p>Works on the components rather than re-adding the lines as text: reading them back as plain
	 * strings would strip the colours off every surviving line, so deleting line three would quietly
	 * repaint the other four.
	 *
	 * @return {@code true} when there was a line at that position.
	 */
	public static boolean removeLoreLine(ItemStack stack, int index) {
		List<Component> lines = new ArrayList<>(currentLore(stack));
		if (index < 0 || index >= lines.size()) {
			return false;
		}
		lines.remove(index);
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return true;
	}

	public static void clearLore(ItemStack stack) {
		stack.remove(DataComponents.LORE);
	}

	public static List<Component> currentLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null ? List.of() : lore.lines();
	}

	/**
	 * Applies an enchantment by id, ignoring whether it normally belongs on this item.
	 *
	 * <p>Deliberately unrestricted: a Sharpness X carrot is exactly the sort of prop a scripted scene
	 * asks for, and vanilla's compatibility rules only get in the way here.
	 *
	 * @param level pass 0 to remove the enchantment.
	 */
	public static void enchant(MinecraftServer server, ItemStack stack, String enchantmentId, int level) {
		Holder<Enchantment> enchantment = resolveEnchantment(server, enchantmentId);
		int clamped = Math.max(0, Math.min(MAX_ENCHANT_LEVEL, level));
		EnchantmentHelper.updateEnchantments(stack, mutable -> {
			if (clamped == 0) {
				mutable.removeIf(held -> held.equals(enchantment));
			} else {
				mutable.set(enchantment, clamped);
			}
		});
	}

	private static Holder<Enchantment> resolveEnchantment(MinecraftServer server, String id) {
		// Enchantments are registry data, so they are looked up per-server rather than from a static list.
		Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (identifier == null) {
			throw new IllegalArgumentException("'" + id + "' is not a valid enchantment id.");
		}
		Registry<Enchantment> registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		return registry.get(identifier)
			.map(reference -> (Holder<Enchantment>) reference)
			.orElseThrow(() -> new IllegalArgumentException("No enchantment called '" + id + "'."));
	}

	/**
	 * Every enchantment the server knows, ordered by the name a player actually sees.
	 *
	 * <p>Holders rather than ids so the menu can render a real name and read a level without doing a
	 * registry lookup per row, per redraw.
	 */
	public static List<Holder<Enchantment>> enchantments(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
			.listElements()
			.map(reference -> (Holder<Enchantment>) reference)
			.sorted(java.util.Comparator.comparing(holder -> holder.value().description().getString()))
			.toList();
	}

	/** The level of one enchantment on a stack, or 0 when it is not present. */
	public static int levelOf(Holder<Enchantment> enchantment, ItemStack stack) {
		return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
	}

	/** Sets one enchantment to a level, clamped to sane bounds. Level 0 removes it. */
	public static void setLevel(ItemStack stack, Holder<Enchantment> enchantment, int level) {
		int clamped = Math.max(0, Math.min(MAX_ENCHANT_LEVEL, level));
		EnchantmentHelper.updateEnchantments(stack, mutable -> {
			if (clamped == 0) {
				mutable.removeIf(held -> held.equals(enchantment));
			} else {
				mutable.set(enchantment, clamped);
			}
		});
	}

	/**
	 * A representative item to show for an enchantment.
	 *
	 * <p>Vanilla enchantments get a symbolic item from {@link EnchantmentIcons}. Everything else falls
	 * back to the enchantment's own data — primary items first, since those are what it is really meant
	 * for — so a modded enchantment still gets something better than a book.
	 */
	public static net.minecraft.world.item.Item iconFor(Holder<Enchantment> enchantment) {
		// A symbolic icon first — a feather for Feather Falling says more than the boots it goes on.
		net.minecraft.world.item.Item symbolic = enchantment.unwrapKey()
			.map(key -> EnchantmentIcons.forId(key.identifier().toString()))
			.orElse(null);
		if (symbolic != null) {
			return symbolic;
		}
		Enchantment.EnchantmentDefinition definition = enchantment.value().definition();
		net.minecraft.core.HolderSet<net.minecraft.world.item.Item> items =
			definition.primaryItems().orElse(definition.supportedItems());
		for (Holder<net.minecraft.world.item.Item> holder : items) {
			return holder.value();
		}
		// Curses and anything else with no item list at all.
		return net.minecraft.world.item.Items.ENCHANTED_BOOK;
	}

	/** The name as the client renders it, with the level numeral already appended. */
	public static Component displayName(Holder<Enchantment> enchantment, int level) {
		return Enchantment.getFullname(enchantment, level);
	}

	/** The highest level vanilla would ever put on this enchantment. */
	public static int naturalMaxLevel(Holder<Enchantment> enchantment) {
		return enchantment.value().getMaxLevel();
	}

	/** The ceiling the editor allows, well past what vanilla would ever generate. */
	public static int maxLevel() {
		return MAX_ENCHANT_LEVEL;
	}

	/**
	 * The item's current name as plain text, with any formatting dropped.
	 *
	 * <p>Used by the colour picker, which rebuilds the name around a new colour code and therefore needs
	 * the words on their own.
	 */
	public static String plainName(ItemStack stack) {
		return stack.getHoverName().getString();
	}

	/**
	 * Repaints the name in one colour, keeping the words.
	 *
	 * <p>An item with no custom name takes its default one — colouring "Diamond Sword" red is a
	 * reasonable reading of the click, and much less surprising than nothing happening.
	 */
	public static void recolour(ItemStack stack, ChatFormatting colour) {
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(plainName(stack))
			.withStyle(style -> style.withColor(colour).withItalic(false)));
	}

	/** Every enchantment id the server knows, for command suggestions. */
	public static List<String> enchantmentIds(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
			.listElementIds()
			.map(key -> key.identifier().toString())
			.sorted()
			.toList();
	}

	public static void clearEnchantments(ItemStack stack) {
		EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.removeIf(held -> true));
	}

	public static void setUnbreakable(ItemStack stack, boolean unbreakable) {
		if (unbreakable) {
			stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		} else {
			stack.remove(DataComponents.UNBREAKABLE);
		}
	}

	public static boolean isUnbreakable(ItemStack stack) {
		return stack.has(DataComponents.UNBREAKABLE);
	}

	/** Forces the enchantment shimmer on or off independently of actual enchantments. */
	public static void setGlint(ItemStack stack, boolean glint) {
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
	}

	public static boolean hasGlintOverride(ItemStack stack) {
		Boolean override = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
		return override != null && override;
	}

	public static void clearGlintOverride(ItemStack stack) {
		stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
	}

	public static void setCount(ItemStack stack, int count) {
		stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
	}

	/** Translates {@code &} colour codes into the section sign the client renders. */
	private static net.minecraft.network.chat.MutableComponent colour(String text) {
		return Component.literal(text.replace('&', ChatFormatting.PREFIX_CODE));
	}
}
