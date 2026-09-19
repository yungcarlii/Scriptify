package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.feature.fill.FillCategory;
import scriptify.feature.fill.FillService;
import scriptify.feature.fill.FillSettings;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /randomfill} screen: choose categories, tune the amounts, then fill the container you are
 * looking at.
 *
 * <p>Category tiles are the item they represent, so the grid reads as a shelf of contents rather than
 * a list of words. Enabled ones glow.
 */
public final class FillMenu {
	private static final int[] CATEGORY_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
	};
	private static final int MIN_SLOT = 29;
	private static final int MAX_SLOT = 30;
	private static final int DENSITY_SLOT = 31;
	private static final int ALL_SLOT = 32;
	private static final int NONE_SLOT = 33;
	private static final int ITEMS_SLOT = 34;
	private static final int FILL_SLOT = 40;
	private static final int SAVE_SLOT = 38;
	private static final int LOAD_SLOT = 42;

	private FillMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.INVENTORY)) {
			Menus.deny(player, "You do not have permission to use random fill.");
			return;
		}
		FillService fill = ScriptifyRuntime.fill();
		FillSettings settings = fill.settings(player);
		boolean hasTarget = fill.lookingAt(player).isPresent();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Random Fill «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		// ---- header: what will be filled ------------------------------------
		gui.setSlot(4, Menus.button(hasTarget ? Items.CHEST : Items.BARRIER,
			hasTarget ? "Ready to fill" : "No container in sight",
			hasTarget ? ChatFormatting.GREEN : ChatFormatting.RED,
			hasTarget
				? "Looking at a container."
				: "Look at a chest, barrel or shulker box,",
			hasTarget
				? "Use the button below to fill it."
				: "then reopen this menu.").build());

		// ---- category toggles -------------------------------------------------
		List<FillCategory> categories = FillCategory.all();
		for (int index = 0; index < categories.size() && index < CATEGORY_SLOTS.length; index++) {
			FillCategory category = categories.get(index);
			boolean enabled = settings.isEnabled(category.id());
			gui.setSlot(CATEGORY_SLOTS[index], Menus.toggle(category.icon(), category.displayName(), enabled,
					category.size() + " survival-obtainable item(s).",
					"",
					"Click to pick items one by one.",
					enabled ? "Shift click to exclude the whole category."
						: "Shift click to include the whole category.")
				.setCallback((slot, type, action, ignored) -> {
					if (!type.shift) {
						// Browsing is the common case: a believable container is a specific handful, not
						// every building block in the game.
						Sfx.click(player);
						FillItemsMenu.open(context, player, category.id(), 0);
						return;
					}
					settings.toggle(category.id());
					Menus.notifyToggle(player, category.displayName() + " "
						+ (settings.isEnabled(category.id()) ? "included" : "excluded"),
						settings.isEnabled(category.id()));
					open(context, player);
				}).build());
		}

		// ---- amount and density ----------------------------------------------
		gui.setSlot(MIN_SLOT, Menus.button(Items.COPPER_INGOT, "Minimum Stack: " + settings.minCount(),
				ChatFormatting.AQUA,
				"Smallest stack size placed.",
				"Left click +1  •  Right click -1")
			.setCallback((slot, type, action, ignored) -> {
				settings.setMinCount(settings.minCount() + (type.isRight ? -1 : 1));
				Sfx.click(player);
				open(context, player);
			}).build());

		gui.setSlot(MAX_SLOT, Menus.button(Items.GOLD_INGOT, "Maximum Stack: " + settings.maxCount(),
				ChatFormatting.AQUA,
				"Largest stack size placed.",
				"Left click +1  •  Right click -1",
				"Shift click ±16")
			.setCallback((slot, type, action, ignored) -> {
				int step = type.shift ? 16 : 1;
				settings.setMaxCount(settings.maxCount() + (type.isRight ? -step : step));
				Sfx.click(player);
				open(context, player);
			}).build());

		gui.setSlot(DENSITY_SLOT, Menus.button(Items.HOPPER, "Fullness: " + settings.density() + "%",
				ChatFormatting.AQUA,
				"How many slots receive an item.",
				"Below 100% leaves gaps, which reads",
				"as looted rather than generated.",
				"Left click +10  •  Right click -10")
			.setCallback((slot, type, action, ignored) -> {
				settings.setDensity(settings.density() + (type.isRight ? -10 : 10));
				Sfx.click(player);
				open(context, player);
			}).build());

		gui.setSlot(ALL_SLOT, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Select All", ChatFormatting.GREEN,
				"Include every category.")
			.setCallback((slot, type, action, ignored) -> {
				categories.forEach(category -> settings.setEnabled(category.id(), true));
				Menus.notify(player, "All categories included.");
				open(context, player);
			}).build());

		gui.setSlot(NONE_SLOT, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.GRAY), "Select None", ChatFormatting.RED,
				"Exclude every category.")
			.setCallback((slot, type, action, ignored) -> {
				settings.clearCategories();
				Menus.notify(player, "All categories excluded.");
				open(context, player);
			}).build());

		gui.setSlot(ITEMS_SLOT, Menus.button(Items.ITEM_FRAME, "Specific Items", ChatFormatting.LIGHT_PURPLE,
				settings.items().isEmpty()
					? "No individual items chosen."
					: settings.items().size() + " item(s) chosen.",
				"Pick exact items instead of whole groups.")
			.setCallback((slot, type, action, ignored) -> ItemPickerMenu.open(context, player)).build());

		// ---- presets and action ----------------------------------------------
		if (fill.canManagePresets(player)) {
			gui.setSlot(SAVE_SLOT, Menus.button(Items.WRITABLE_BOOK, "Save Preset", ChatFormatting.GOLD,
					"Stores these settings under a name.")
				.setCallback((slot, type, action, ignored) -> promptSave(context, player)).build());
		}
		List<String> visiblePresets = fill.presetNames().stream()
			.filter(name -> fill.canManagePresets(player) || fill.canUsePreset(name, player))
			.toList();

		gui.setSlot(LOAD_SLOT, Menus.button(Items.BOOKSHELF, "Load Preset", ChatFormatting.GOLD,
				visiblePresets.isEmpty()
					? "No presets saved yet."
					: "Saved: " + visiblePresets)
			.setCallback((slot, type, action, ignored) -> PresetMenu.open(context, player)).build());

		gui.setSlot(FILL_SLOT, Menus.button(Items.NETHER_STAR, "Fill Container", ChatFormatting.LIGHT_PURPLE,
				hasTarget ? "Fills the container you are looking at." : "Look at a container first.",
				"This replaces its current contents.")
			.glow()
			.setCallback((slot, type, action, ignored) -> {
				doFill(context, player);
				open(context, player);
			}).build());

		gui.setSlot(48, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/** Runs the fill and reports precisely why it did nothing, when it does nothing. */
	public static void doFill(ScriptifyContext context, ServerPlayer player) {
		FillService fill = ScriptifyRuntime.fill();
		var target = fill.lookingAt(player);
		if (target.isEmpty()) {
			Menus.deny(player, "Look at a container first.");
			return;
		}
		Container container = target.get();
		int filled = fill.fill(container, fill.settings(player));
		if (filled < 0) {
			Menus.deny(player, "Select at least one category.");
			return;
		}
		Sfx.restock(player);
		Menus.notify(player, "Filled " + filled + " slot(s).");
	}

	private static void promptSave(ScriptifyContext context, ServerPlayer player) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Name this preset"));
		anvil.setDefaultInputValue("preset");
		anvil.setSlot(0, new GuiElementBuilder(Items.WRITABLE_BOOK)
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Save", ChatFormatting.GREEN,
				"Stores the current categories and amounts.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					String name = anvil.getInput();
					ScriptifyRuntime.fill().savePreset(name, ScriptifyRuntime.fill().settings(player));
					Menus.notify(player, "Saved preset '" + name + "'.");
				} catch (IllegalArgumentException exception) {
					Menus.deny(player, exception.getMessage());
				}
				open(context, player);
			}).build());
		anvil.open();
	}

	/**
	 * The individual-item picker.
	 *
	 * <p>Items are added by holding them, which sidesteps typing registry ids and guarantees the item
	 * exists. Hand-picked entries deliberately bypass the survival filter.
	 */
	public static final class ItemPickerMenu {
		private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
		};

		private ItemPickerMenu() {
		}

		public static void open(ScriptifyContext context, ServerPlayer player) {
			FillSettings settings = ScriptifyRuntime.fill().settings(player);
			List<String> ids = new java.util.ArrayList<>(settings.items());
			ids.sort(String::compareTo);

			SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
			gui.setTitle(Component.literal("» Chosen Items «").withStyle(ChatFormatting.LIGHT_PURPLE));
			gui.setLockPlayerInventory(true);
			Menus.fill(gui);

			ItemStack held = player.getInventory().getSelectedItem();
			gui.setSlot(4, Menus.button(held.isEmpty() ? Items.BARRIER : held.getItem(),
					held.isEmpty() ? "Hold an item to add it" : "Add " + held.getHoverName().getString(),
					held.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN,
					held.isEmpty()
						? "Put the item you want in your hand,"
						: "Click to add it to the fill pool.",
					held.isEmpty() ? "then reopen this menu." : "It will be mixed in with the categories.")
				.setCallback((slot, type, action, ignored) -> {
					ItemStack current = player.getInventory().getSelectedItem();
					if (current.isEmpty()) {
						Menus.deny(player, "Hold an item first.");
						return;
					}
					settings.addItem(net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(current.getItem()).toString());
					Menus.notify(player, "Added " + current.getHoverName().getString() + ".");
					open(context, player);
				}).build());

			for (int index = 0; index < ids.size() && index < SLOTS.length; index++) {
				String id = ids.get(index);
				var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getValue(net.minecraft.resources.Identifier.parse(id));
				if (item == null) {
					continue;
				}
				gui.setSlot(SLOTS[index], Menus.button(item, new ItemStack(item).getHoverName().getString(),
						ChatFormatting.GOLD, id, "Click to remove.")
					.setCallback((slot, type, action, ignored) -> {
						settings.removeItem(id);
						Menus.notify(player, "Removed that item.");
						open(context, player);
					}).build());
			}

			if (ids.isEmpty()) {
				gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "No items chosen",
					ChatFormatting.GRAY,
					"Categories alone will be used.",
					"Hold an item and click above to add one.").build());
			}

			gui.setSlot(46, Menus.button(Items.BARRIER, "Clear All", ChatFormatting.RED,
					"Removes every hand-picked item.")
				.setCallback((slot, type, action, ignored) -> {
					settings.clearItems();
					Menus.notify(player, "Cleared the item list.");
					open(context, player);
				}).build());

			gui.setSlot(48, Menus.backButton()
				.setCallback((slot, type, action, ignored) -> FillMenu.open(context, player)).build());
			gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
				.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

			gui.open();
			Sfx.menuOpen(player);
		}
	}

	/** The saved-preset browser. */
	public static final class PresetMenu {
		private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
		};

		private PresetMenu() {
		}

		/** The preset's own icon, falling back to a book if it names something that is not an item. */
		private static net.minecraft.world.item.Item iconOf(FillSettings preset) {
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
				net.minecraft.resources.Identifier.parse(preset.icon()));
			return item == null || item == Items.AIR ? Items.BOOK : item;
		}

		/** Presets built from a hand-picked list have no categories, so counting those reads as empty. */
		private static String describeContents(FillSettings preset) {
			int categories = preset.categories().size();
			int items = preset.items().size();
			if (categories == 0) {
				return items + " hand-picked item(s)";
			}
			return categories + " categor(ies)" + (items == 0 ? "" : " + " + items + " item(s)");
		}

		public static void open(ScriptifyContext context, ServerPlayer player) {
			FillService fill = ScriptifyRuntime.fill();
		boolean canManage = fill.canManagePresets(player);
		List<String> names = fill.presetNames().stream()
			.filter(name -> canManage || fill.canUsePreset(name, player))
			.toList();

			SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
			gui.setTitle(Component.literal("» Fill Presets «").withStyle(ChatFormatting.GOLD));
			gui.setLockPlayerInventory(true);
			Menus.fill(gui);

			for (int index = 0; index < names.size() && index < SLOTS.length; index++) {
				String name = names.get(index);
				FillSettings preset = fill.preset(name).orElse(null);
				if (preset == null) {
					continue;
				}
				gui.setSlot(SLOTS[index], Menus.button(iconOf(preset), name, ChatFormatting.GOLD,
						describeContents(preset),
						preset.minCount() + "-" + preset.maxCount() + " per stack",
						preset.density() + "% full",
						fill.allowedRanks(name).isEmpty() ? "Access: every eligible rank."
							: "Access: " + fill.allowedRanks(name).size() + " rank(s).",
						"Left click to load.",
						canManage ? "Right click: manage access." : "",
						canManage ? "Shift + right click: delete." : "")
					.setCallback((slot, type, action, ignored) -> {
						if (type.shift && type.isRight && canManage) {
							fill.deletePreset(name);
							Menus.notify(player, "Deleted preset '" + name + "'.");
							open(context, player);
							return;
						}
						if (type.isRight && canManage) {
							openAccess(context, player, name);
							return;
						}
						fill.setSettings(player, preset);
						Menus.notify(player, "Loaded preset '" + name + "'.");
						FillMenu.open(context, player);
					}).build());
			}

			if (names.isEmpty()) {
				gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "No presets yet",
					ChatFormatting.GRAY, "Save one from the fill menu.").build());
			}

			gui.setSlot(48, Menus.backButton()
				.setCallback((slot, type, action, ignored) -> FillMenu.open(context, player)).build());
			gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
				.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

			gui.open();
			Sfx.menuOpen(player);
		}

		private static void openAccess(ScriptifyContext context, ServerPlayer viewer, String preset) {
			FillService fill = ScriptifyRuntime.fill();
			RankAccessMenu.open(context, viewer, preset, "Inventory", new RankAccessMenu.Controller() {
				@Override public java.util.Set<String> allowedRanks() { return fill.allowedRanks(preset); }
				@Override public boolean setAllowed(String rank, boolean allowed) {
					return fill.setRankAllowed(preset, rank, allowed);
				}
				@Override public void clear() { fill.clearRankAccess(preset); }
				@Override public void back() { open(context, viewer); }
			}, 0);
		}
	}
}
