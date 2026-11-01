package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import scriptify.api.ScriptifyContext;
import scriptify.feature.FeatureManager;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /scriptify} dashboard — the mod's primary interface.
 *
 * <p>Laid out as a bordered 6-row grid: a nether star header, then four rows of feature buttons
 * grouped by kind (content, player state, inventory, illusions), with the close button centred at the
 * bottom. Toggles flip in place and reopen so the icon always reflects live state.
 */
public final class MainMenu {
	private MainMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.GUI_OPEN)) {
			Menus.deny(player, "You do not have permission to open Scriptify.");
			return;
		}
		FeatureManager features = ScriptifyRuntime.features();
		if (features == null) {
			Menus.deny(player, "Scriptify is still starting up.");
			return;
		}

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Feature Controls «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		// ---- header ----------------------------------------------------------
		gui.setSlot(4, Menus.button(Items.NETHER_STAR, "Scriptify", ChatFormatting.LIGHT_PURPLE,
			"Server-side toolkit for scripted SMP content.",
			"Everything here works on a vanilla client.").glow().build());

		// ---- row 2: content -------------------------------------------------
		gui.setSlot(10, Menus.button(Items.CHEST, "Kits", ChatFormatting.GOLD,
				"Save and hand out full loadouts.")
			.setCallback((index, type, action, ignored) -> KitsMenu.open(context, player, 0)).build());

		// Rolling a new identity is the common case here — a scene needs somebody who is not
		// recognisable, not somebody impersonating a specific account. Copying a named account is still
		// one shift-click away for when that is genuinely the shot.
		gui.setSlot(11, Menus.button(Items.NAME_TAG, "Nicknames", ChatFormatting.AQUA,
				"Click: pick a player, roll them a name.",
				"Shift click: choose the name yourself.")
			.setCallback((index, type, action, ignored) ->
				NickMenu.open(context, player, !type.shift)).build());

		gui.setSlot(12, Menus.button(Items.PLAYER_HEAD, "Skins", ChatFormatting.AQUA,
				"Click: pick a player, roll them a skin.",
				"Shift click: copy a specific account's skin.")
			.setCallback((index, type, action, ignored) ->
				SkinMenu.open(context, player, !type.shift)).build());

		// Flanking the header rather than in the grid below: the four content rows are full, and both of
		// these change how the whole server behaves rather than acting on one player, so they read
		// correctly beside the title.
		gui.setSlot(3, Menus.button(Items.PLAYER_HEAD, "Fake Tab", ChatFormatting.LIGHT_PURPLE,
				"Pad the tab list with players",
				"who are not really here.")
			.setCallback((index, type, action, ignored) -> FakeTabMenu.open(context, player)).build());

		gui.setSlot(5, Menus.button(Items.NOTE_BLOCK, "Proximity Chat",
				ScriptifyRuntime.proximity() != null && ScriptifyRuntime.proximity().isEnabled()
					? ChatFormatting.GREEN : ChatFormatting.GRAY,
				"Players only hear people near them.",
				ScriptifyRuntime.proximity() != null && ScriptifyRuntime.proximity().isEnabled()
					? "Currently ON, carrying "
						+ (int) Math.round(ScriptifyRuntime.proximity().radius()) + " blocks."
					: "Currently OFF.")
			.setCallback((index, type, action, ignored) -> ProximityMenu.open(context, player)).build());

		gui.setSlot(13, Menus.button(Items.COMMAND_BLOCK, "Gamemode", ChatFormatting.YELLOW,
				"Left click cycles your own gamemode.",
				"Also available as /gms /gmc /gma /gmsp")
			.setCallback((index, type, action, ignored) -> {
				GameType next = switch (player.gameMode()) {
					case SURVIVAL -> GameType.CREATIVE;
					case CREATIVE -> GameType.ADVENTURE;
					case ADVENTURE -> GameType.SPECTATOR;
					case SPECTATOR -> GameType.SURVIVAL;
				};
				player.setGameMode(next);
				Menus.notify(player, "Gamemode: " + next.getName());
				open(context, player);
			}).build());

		gui.setSlot(14, Menus.toggle(Items.IRON_SWORD, "PvP", features.isPvpEnabled(),
				"Toggles the PvP game rule in every world.")
			.setCallback((index, type, action, ignored) -> {
				features.setPvp(!features.isPvpEnabled());
				open(context, player);
			}).build());

		gui.setSlot(15, Menus.toggle(Items.PACKED_ICE, "Freeze", features.isFrozen(player),
				"Locks you in place; jumping and sneaking still work.",
				"Also available as /freeze and /unfreeze")
			.setCallback((index, type, action, ignored) -> {
				features.setFrozen(player, !features.isFrozen(player));
				open(context, player);
			}).build());

		gui.setSlot(16, Menus.button(Items.TNT, "Death Control", ChatFormatting.RED,
				"Staged deaths: " + (ScriptifyRuntime.deathControl().isEnabled() ? "ON" : "OFF"),
				"A held totem pops as normal instead of staging.",
				"Click to toggle. /ondeath restore <player> brings them back.")
			.setCallback((index, type, action, ignored) -> {
				ScriptifyRuntime.deathControl().setEnabled(!ScriptifyRuntime.deathControl().isEnabled());
				open(context, player);
			}).build());

		// ---- row 3: player state --------------------------------------------
		gui.setSlot(19, Menus.toggle(Items.POTION, "Bottle Invisibility", features.isInvisible(player),
				"Full invisibility including worn armour.",
				"Also available as /invis on|off")
			.setCallback((index, type, action, ignored) -> {
				features.setInvisible(player, !features.isInvisible(player));
				open(context, player);
			}).build());

		gui.setSlot(20, Menus.button(Items.BREWING_STAND, "Potion Presets", ChatFormatting.LIGHT_PURPLE,
				"Named effect bundles for a scene.")
			.setCallback((index, type, action, ignored) -> PotionMenu.open(context, player)).build());

		gui.setSlot(21, Menus.button(Items.TOTEM_OF_UNDYING, "Totems", ChatFormatting.GOLD,
				"Left click: 1 totem  •  Right click: a full stack",
				"Also available as /totem <count>")
			.setCallback((index, type, action, ignored) -> {
				int count = type.isRight ? 64 : 1;
				features.giveTotems(player, count);
				Menus.notify(player, "Gave " + count + " totem(s).");
			}).build());

		gui.setSlot(22, Menus.button(Items.ENCHANTED_GOLDEN_APPLE, "Heal", ChatFormatting.GREEN,
				"Full health, clears fire and effects.")
			.setCallback((index, type, action, ignored) -> {
				features.heal(player);
				Menus.notify(player, "Healed.");
			}).build());

		gui.setSlot(23, Menus.button(Items.COOKED_BEEF, "Feed", ChatFormatting.GREEN,
				"Restores hunger and saturation.")
			.setCallback((index, type, action, ignored) -> {
				features.feed(player);
				Menus.notify(player, "Fed.");
			}).build());

		gui.setSlot(24, Menus.toggle(Items.TOTEM_OF_UNDYING, "Immortal", features.isImmortal(player),
				"Hits still land and knock back — you just cannot die.",
				"A held totem still pops, and the void still kills.",
				"Left click: toggle for yourself",
				"Right click: toggle globally (everyone)",
				"Shift click: fall immunity " + (features.isFallImmune(player) ? "ON" : "OFF")
					+ ", armour repair " + (features.isArmorProtected(player) ? "ON" : "OFF"),
				"Also available as /immortal set <players> [seconds]")
			.setCallback((index, type, action, ignored) -> {
				if (type.shift) {
					// Cycles this player's two side-options together; the command exposes them separately.
					boolean next = !features.isFallImmune(player);
					features.setFallImmune(player, next);
					features.setArmorProtected(player, next);
					Menus.notify(player, "Fall immunity and armour repair set to " + next + ".");
				} else if (type.isRight) {
					boolean next = !features.isGlobalImmortal();
					features.setGlobalImmortal(next, FeatureManager.FOREVER);
					Menus.notify(player, "Global immortality " + (next ? "enabled" : "disabled") + ".");
				} else {
					features.setImmortal(player, !features.isImmortal(player));
				}
				open(context, player);
			}).glow().build());

		gui.setSlot(25, Menus.button(Items.SPLASH_POTION, "Max Health", ChatFormatting.RED,
				"Changes the heart row via the max_health attribute.",
				"Left click: +1 heart  •  Right click: -1 heart",
				"Shift click: reset to 10 hearts")
			.setCallback((index, type, action, ignored) -> {
				if (type.shift) {
					features.resetMaxHealth(player);
					Menus.notify(player, "Reset to 10 hearts.");
				} else {
					double hearts = player.getMaxHealth() / 2.0D + (type.isRight ? -1.0D : 1.0D);
					double applied = features.setMaxHealth(player, hearts);
					Menus.notify(player, "Max health set to " + applied + " heart(s).");
				}
				Sfx.success(player);
				open(context, player);
			}).build());

		// ---- row 4: inventory tools -----------------------------------------
		gui.setSlot(28, Menus.button(Items.LADDER, "Teleport To Top", ChatFormatting.YELLOW,
				"Jump to the highest block above you.",
				"Also available as /top")
			.setCallback((index, type, action, ignored) -> {
				if (features.teleportToTop(player)) {
					Menus.notify(player, "Teleported to the surface.");
				} else {
					Menus.deny(player, "Nothing solid above you.");
				}
			}).build());

		gui.setSlot(29, Menus.button(Items.IRON_PICKAXE, "Durability", ChatFormatting.YELLOW,
				"Repair everything, or wear it down",
				"for a battle-worn look.",
				"Also available as /durability")
			.setCallback((index, type, action, ignored) -> DurabilityMenu.open(context, player)).build());

		gui.setSlot(30, Menus.button(Items.HOPPER, "Restock", ChatFormatting.YELLOW,
				"Top every stackable item back up to a full stack.",
				"Also available as /restock")
			.setCallback((index, type, action, ignored) ->
				Menus.notify(player, "Restocked " + features.restock(player) + " stack(s).")).build());

		gui.setSlot(31, Menus.button(Items.DROPPER, "Random Fill", ChatFormatting.YELLOW,
				"Fill the container you are looking at.",
				"Pick categories, amounts and presets.",
				"Also available as /randomfill")
			.setCallback((index, type, action, ignored) -> FillMenu.open(context, player)).build());

		gui.setSlot(32, Menus.button(Items.COMPASS, "Inventory Backups", ChatFormatting.AQUA,
				"Snapshot and roll back a loadout.",
				"Also available as /backupinv")
			.setCallback((index, type, action, ignored) -> BackupMenu.open(context, player, 0)).build());

		gui.setSlot(33, Menus.toggle(Items.REDSTONE, "Mute Chat", features.isChatBlocked(),
				"Blocks chat for everyone except operators.")
			.setCallback((index, type, action, ignored) -> {
				features.setChatBlocked(!features.isChatBlocked());
				open(context, player);
			}).build());

		gui.setSlot(34, Menus.toggle(Items.STRUCTURE_VOID, "Vanish", features.isVanished(player),
				"Invisible and removed from the player list.")
			.setCallback((index, type, action, ignored) -> {
				features.setVanished(player, !features.isVanished(player));
				open(context, player);
			}).build());

		// ---- row 5: illusions ------------------------------------------------
		gui.setSlot(38, Menus.button(Items.OAK_DOOR, "Fake Join / Leave", ChatFormatting.LIGHT_PURPLE,
				"Left click: broadcast a leave and hide you",
				"Right click: broadcast a join and restore you")
			.setCallback((index, type, action, ignored) -> {
				if (type.isRight) {
					ScriptifyRuntime.fakeMessages().stageJoin(player);
					Menus.notify(player, "Staged a join.");
				} else {
					ScriptifyRuntime.fakeMessages().stageLeave(player);
					Menus.notify(player, "Staged a leave.");
				}
			}).build());

		gui.setSlot(40, Menus.button(Items.SKELETON_SKULL, "Fake Death Message", ChatFormatting.LIGHT_PURPLE,
				"Broadcast a death line without dying.")
			.setCallback((index, type, action, ignored) -> {
				ScriptifyRuntime.fakeMessages().broadcastDeath(player);
				Sfx.death(player);
				Menus.notify(player, "Broadcast a death message.");
			}).build());

		gui.setSlot(42, Menus.button(Items.COMMAND_BLOCK_MINECART, "Sudo", ChatFormatting.LIGHT_PURPLE,
				"Run a command or say a line as another player.",
				"/sudo <player> command <...>  •  /sudo <player> say <...>")
			.setCallback((index, type, action, ignored) ->
				Menus.notify(player, "Use /sudo <player> command|say <...>")).build());

		// ---- row 5: authoring tools ------------------------------------------
		gui.setSlot(37, Menus.button(Items.ANVIL, "Item Maker", ChatFormatting.GOLD,
				"Rename, add lore, enchant and more.",
				"Hold an item and click.")
			.setCallback((index, type, action, ignored) -> ItemMenu.open(context, player)).build());

		gui.setSlot(39, Menus.button(Items.EMERALD, "Villager Maker", ChatFormatting.GREEN,
				"Shopkeepers with hand-written trades.",
				"/villager create <name> [profession] [level]",
				"Then look at it: /villager addtrade")
			.setCallback((index, type, action, ignored) ->
				Menus.notify(player, "Use /villager create <name>.")).build());

		gui.setSlot(41, Menus.button(scriptify.ui.DyedItems.banner(DyeColor.WHITE), "Teams", ChatFormatting.AQUA,
				"Colours, prefixes, friendly fire, collision.",
				"/steams create <name>  •  /steams add <name> <players>")
			.setCallback((index, type, action, ignored) ->
				Menus.notify(player, "Use /steams to manage teams.")).build());

		gui.setSlot(43, Menus.button(Items.BOOKSHELF, "Access", ChatFormatting.GOLD,
				"Ranks, and who holds them.",
				"Scriptify features and vanilla commands.")
			.setCallback((index, type, action, ignored) -> AccessMenu.open(context, player)).build());

		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((index, type, action, ignored) -> {
				Sfx.click(player);
				player.closeContainer();
			}).build());

		gui.open();
		Sfx.menuOpen(player);
	}
}
