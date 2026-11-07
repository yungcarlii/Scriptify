package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;

/**
 * Scriptify's own permission editor: pick a player, then click nodes on and off.
 *
 * <p>Every node carries the icon of the feature it unlocks and a sentence saying what it actually
 * lets somebody do. A node id is precise and tells a reader nothing; "Kits — save and hand out
 * loadouts" is what the decision is really about.
 *
 * <p>Roles are here because handing out permissions one node at a time is the tedious part. Most
 * crews split three ways — whoever is directing, whoever is helping, and the cast — so those are one
 * click each, and the grid underneath is still there for anything unusual.
 *
 * <p>When LuckPerms is installed it answers first, so this screen would be quietly lying if it let
 * you edit without saying so. It stays usable — the grants are still stored — but says plainly who
 * is actually in charge.
 */
public final class PermissionsMenu {
	// Row four as well: there are more nodes than two rows hold, and the ones past the fourteenth were
	// simply never drawn.
	private static final int[] NODE_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		37, 38, 39, 40, 41, 42, 43,
	};

	/** What each node unlocks, in the words somebody deciding would use. */
	public record NodeInfo(Item icon, String title, String description) {
	}

	private static final Map<String, NodeInfo> NODES = new LinkedHashMap<>();

	/** The icon and wording for a node, shared with the rank editor so the two cannot drift apart. */
	public static NodeInfo describe(String node) {
		return NODES.getOrDefault(node, new NodeInfo(Items.PAPER, node, ""));
	}

	static {
		NODES.put(PermissionService.ADMIN,
			new NodeInfo(Items.NETHER_STAR, "Admin", "Everything, including sudo and reload."));
		NODES.put(PermissionService.GUI_OPEN,
			new NodeInfo(Items.COMPASS, "Open Menu", "Can open /scriptify at all."));
		NODES.put(PermissionService.DEATH,
			new NodeInfo(Items.TNT, "Staged Deaths", "Fake deaths and bringing people back."));
		NODES.put(PermissionService.NICK,
			new NodeInfo(Items.NAME_TAG, "Nicknames", "Rename players."));
		NODES.put(PermissionService.SKIN,
			new NodeInfo(Items.PLAYER_HEAD, "Skins", "Change skins and identities."));
		NODES.put(PermissionService.PLAYER,
			new NodeInfo(Items.IRON_SWORD, "Player State", "Freeze, invis, vanish, immortal, health, gamemode."));
		NODES.put(PermissionService.INVENTORY,
			new NodeInfo(Items.CHEST, "Inventory", "Invsee, backups, copyinv, fill, restock."));
		NODES.put(PermissionService.KITS,
			new NodeInfo(Items.DIAMOND_CHESTPLATE, "Use Kits", "Equip loadouts your rank can access."));
		NODES.put(PermissionService.KITS_MANAGE,
			new NodeInfo(Items.CRAFTING_TABLE, "Manage Kits", "Create, edit, give and set kit rank access."));
		NODES.put(PermissionService.POTIONS_MANAGE,
			new NodeInfo(Items.BREWING_STAND, "Manage Potions", "Save, delete and set potion preset rank access."));
		NODES.put(PermissionService.FILL_MANAGE,
			new NodeInfo(Items.DROPPER, "Manage Fill Presets", "Save, delete and set fill preset rank access."));
		NODES.put(PermissionService.ITEMS,
			new NodeInfo(Items.ANVIL, "Item Maker", "Rename, enchant and edit items."));
		NODES.put(PermissionService.VILLAGERS,
			new NodeInfo(Items.EMERALD, "Villagers", "Scene villagers and their trades."));
		NODES.put(PermissionService.TEAMS,
			new NodeInfo(scriptify.ui.DyedItems.banner(DyeColor.WHITE), "Teams", "Colours, prefixes and team rules."));
		NODES.put(PermissionService.BOTS,
			new NodeInfo(Items.IRON_GOLEM_SPAWN_EGG, "Bots", "Create, direct and configure Scriptify bots."));
	}

	/** A one-click bundle. The three ways a crew usually splits. */
	private record Role(String name, Item icon, String description, List<String> nodes) {
	}

	private static final Role[] ROLES = {
		new Role("Director", Items.NETHER_STAR, "Everything. The person running the shoot.",
			PermissionService.ALL_NODES),
		new Role("Crew", Items.SPYGLASS, "Scene tools, no admin: deaths, kits, inventory, player state.",
			List.of(PermissionService.GUI_OPEN, PermissionService.DEATH, PermissionService.PLAYER,
				PermissionService.INVENTORY, PermissionService.KITS, PermissionService.KITS_MANAGE,
				PermissionService.POTIONS_MANAGE, PermissionService.FILL_MANAGE, PermissionService.ITEMS)),
		new Role("Cast", Items.LEATHER_CHESTPLATE, "Can open the menu and nothing else.",
			List.of(PermissionService.GUI_OPEN)),
	};

	private PermissionsMenu() {
	}

	/** The node grid for one player. */
	public static void openFor(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		PermissionService permissions = context.permissions();
		Set<String> granted = permissions.granted(target.getUUID());
		boolean operator = isOperator(context, target);

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal("» " + target.getGameProfile().name() + " «")
			.withStyle(ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.playerHead(target, ChatFormatting.GOLD,
			permissions.isManaged()
				? "Scriptify is managing permissions."
				: "LuckPerms is installed and decides access.",
			permissions.isManaged()
				? "Click a node to grant or revoke it."
				: "Edits here are stored but will not take effect.",
			"",
			operator
				? "This player is an operator, so they"
				: granted.size() + " of " + NODES.size() + " nodes granted.",
			operator ? "already pass every check regardless." : "").build());

		int cell = 0;
		for (Map.Entry<String, NodeInfo> entry : NODES.entrySet()) {
			if (cell >= NODE_SLOTS.length) {
				break;
			}
			String node = entry.getKey();
			NodeInfo info = entry.getValue();
			boolean on = granted.contains(node);
			gui.setSlot(NODE_SLOTS[cell++], Menus.toggle(info.icon(), info.title(), on,
					info.description(),
					"",
					node,
					on ? "Click to revoke." : "Click to grant.")
				.setCallback((slot, type, action, ignored) -> {
					if (on) {
						permissions.revoke(target.getUUID(), node);
					} else {
						permissions.grant(target.getUUID(), node);
					}
					Menus.notifyToggle(viewer, (on ? "Revoked " : "Granted ") + info.title(), !on);
					openFor(context, viewer, target);
				}).build());
		}

		// 28-30, clear of the rank button at 31, which used to cover the third role.
		int roleSlot = 28;
		for (Role role : ROLES) {
			gui.setSlot(roleSlot++, Menus.button(role.icon(), role.name(), ChatFormatting.AQUA,
					role.description(),
					"",
					"Replaces their current nodes.")
				.setCallback((slot, type, action, ignored) -> {
					applyRole(permissions, target, role);
					Menus.notify(viewer, target.getGameProfile().name() + " is now " + role.name() + ".");
					Sfx.success(viewer);
					openFor(context, viewer, target);
				}).build());
		}

		gui.setSlot(33, Menus.button(Items.BARRIER, "Revoke All", ChatFormatting.RED,
				"Removes every node from this player.",
				"",
				"Operator status is not affected.")
			.setCallback((slot, type, action, ignored) -> {
				for (String node : PermissionService.ALL_NODES) {
					permissions.revoke(target.getUUID(), node);
				}
				Menus.notify(viewer, "Revoked every node.");
				openFor(context, viewer, target);
			}).build());

		gui.setSlot(31, Menus.button(Items.BOOKSHELF, "Rank: "
					+ permissions.ranks().of(target.getUUID()).label(),
				ChatFormatting.GOLD,
				"A rank is what they are allowed to do.",
				"Everything above is an exception on top.",
				"",
				"Click to move them to another rank.")
			.setCallback((slot, type, action, ignored) ->
				AccessMenu.chooseRank(context, viewer, target)).build());

		gui.setSlot(48, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> AccessMenu.players(context, viewer)).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/** Sets exactly the role's nodes, clearing anything else. */
	private static void applyRole(PermissionService permissions, ServerPlayer target, Role role) {
		for (String node : PermissionService.ALL_NODES) {
			if (role.nodes().contains(node)) {
				permissions.grant(target.getUUID(), node);
			} else {
				permissions.revoke(target.getUUID(), node);
			}
		}
	}

	private static boolean isOperator(ScriptifyContext context, ServerPlayer player) {
		return context.server().getPlayerList().isOp(player.nameAndId());
	}
}
