package scriptify.permission;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public interface PermissionService {
	String ADMIN = "scriptify.admin";
	String GUI_OPEN = "scriptify.gui.open";
	String NICK = "scriptify.nick";
	String SKIN = "scriptify.skin";
	String DEATH = "scriptify.ondeath";
	String INVENTORY = "scriptify.inventory";
	String KITS = "scriptify.kits";
	/** Create, edit, give and configure access to shared kits. */
	String KITS_MANAGE = "scriptify.kits.manage";
	/** Save, delete and configure access to potion presets. */
	String POTIONS_MANAGE = "scriptify.potions.manage";
	/** Save, delete and configure access to random-fill presets. */
	String FILL_MANAGE = "scriptify.fill.manage";
	String PLAYER = "scriptify.player";
	String ITEMS = "scriptify.items";
	String VILLAGERS = "scriptify.villagers";
	String TEAMS = "scriptify.teams";
	String BOTS = "scriptify.bots";

	/** Every node Scriptify checks, in the order the permission menu should list them. */
	List<String> ALL_NODES = List.of(
		ADMIN, GUI_OPEN, NICK, SKIN, DEATH, INVENTORY, KITS, KITS_MANAGE, POTIONS_MANAGE, FILL_MANAGE,
		PLAYER, ITEMS, VILLAGERS, TEAMS, BOTS);

	boolean has(CommandSourceStack source, String node);

	boolean has(ServerPlayer player, String node);

	void reload();

	// ---------------------------------------------------------------- editing

	/**
	 * Grants a node to a player in Scriptify's own store.
	 *
	 * <p>Has no effect on LuckPerms when that is in charge — see {@link #isManaged()}.
	 */
	/** The rank store backing this service, so menus and commands can edit ranks. */
	RankService ranks();

	void grant(UUID player, String node);

	void revoke(UUID player, String node);

	/** Nodes granted directly to this player in Scriptify's store, ignoring op status and LuckPerms. */
	Set<String> granted(UUID player);

	/**
	 * Whether Scriptify's own store is the authority.
	 *
	 * <p>False when LuckPerms is installed, in which case editing here would be misleading — the answer
	 * comes from LuckPerms and any local grant would be ignored.
	 */
	boolean isManaged();
}
