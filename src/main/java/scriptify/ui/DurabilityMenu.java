package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.FeatureManager;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * {@code /durability}: repair everything, or wear it down for a battle-worn look.
 *
 * <p>Wear comes as three presets plus an exact level. Every wear icon is a sword already worn to that
 * level, so its durability bar previews the result before anything is clicked.
 */
public final class DurabilityMenu {
	/** A wear preset: how much durability is left, where it sits, and what it looks like on camera. */
	private record Wear(int percent, int slot, String title, ChatFormatting colour, String flavour) {
	}

	private static final Wear[] WEARS = {
		new Wear(15, 10, "Heavily Worn", ChatFormatting.RED, "Barely held together after a long fight."),
		new Wear(50, 12, "Moderately Worn", ChatFormatting.GOLD, "Been through a few fights."),
		new Wear(85, 14, "Lightly Worn", ChatFormatting.GREEN, "A few scratches and dents."),
	};

	private DurabilityMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		FeatureManager features = features(context, player);
		if (features == null) {
			return;
		}

		SimpleGui gui = new SimpleGui(Menus.typeForRows(3), player, false);
		gui.setTitle(Component.literal("» Durability «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(11, Menus.button(Items.ANVIL, "Max", ChatFormatting.GREEN,
				"Repairs everything you carry and wear.")
			.setCallback((slot, type, action, ignored) -> {
				Menus.notify(player, "Repaired " + features.repairAll(player) + " item(s).");
				player.closeContainer();
			}).build());

		gui.setSlot(15, preview(50, "Random", ChatFormatting.GOLD,
				"Wear your gear down for a battle-worn look.",
				"Every item lands a little differently.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				openWear(context, player);
			}).build());

		gui.setSlot(18, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(26, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	private static void openWear(ScriptifyContext context, ServerPlayer player) {
		FeatureManager features = features(context, player);
		if (features == null) {
			return;
		}

		SimpleGui gui = new SimpleGui(Menus.typeForRows(3), player, false);
		gui.setTitle(Component.literal("» Random Durability «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		for (Wear wear : WEARS) {
			gui.setSlot(wear.slot(), preview(wear.percent(), wear.title(), wear.colour(),
					wear.flavour(),
					"About " + wear.percent() + "% durability left.")
				.setCallback((slot, type, action, ignored) -> apply(features, player, wear.percent())).build());
		}

		gui.setSlot(16, Menus.button(Items.PAPER, "Custom", ChatFormatting.YELLOW,
				"Type how much durability is left, 1-100.",
				"100: untouched  •  1: about to break")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				promptPercent(context, player);
			}).build());

		gui.setSlot(18, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> open(context, player)).build());
		gui.setSlot(26, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
	}

	private static void promptPercent(ScriptifyContext context, ServerPlayer player) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Durability left, 1-100"));
		anvil.setDefaultInputValue("50");
		anvil.setSlot(0, new GuiElementBuilder(Items.PAPER)
			.setName(Msg.hint("Type a number, then click the output")).build());
		anvil.setSlot(2, Menus.button(DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"100: untouched  •  1: about to break")
			.setCallback((slot, type, action, ignored) -> {
				int percent;
				try {
					percent = Integer.parseInt(anvil.getInput().trim().replace("%", ""));
				} catch (NumberFormatException exception) {
					percent = 0;
				}
				if (percent < 1 || percent > 100) {
					Menus.deny(player, "Pick a whole number from 1 to 100.");
					openWear(context, player);
					return;
				}
				FeatureManager features = features(context, player);
				if (features != null) {
					apply(features, player, percent);
				}
			}).build());
		anvil.open();
	}

	private static void apply(FeatureManager features, ServerPlayer player, int percent) {
		int changed = features.wearDurability(player, percent);
		Menus.notify(player, "Wore " + changed + " item(s) down to about " + percent + "% durability.");
		player.closeContainer();
	}

	/** An iron sword whose durability bar already shows {@code percent} left. */
	private static GuiElementBuilder preview(int percent, String title, ChatFormatting colour, String... lore) {
		int max = new ItemStack(Items.IRON_SWORD).getMaxDamage();
		return Menus.button(Items.IRON_SWORD, title, colour, lore)
			.setDamage(max - max * percent / 100)
			.hideDefaultTooltip();
	}

	/** The feature manager, or {@code null} after telling the player why they cannot use this. */
	private static FeatureManager features(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.INVENTORY)) {
			Menus.deny(player, "You do not have permission to change durability.");
			return null;
		}
		FeatureManager features = ScriptifyRuntime.features();
		if (features == null) {
			Menus.deny(player, "Scriptify is still starting up.");
		}
		return features;
	}
}
