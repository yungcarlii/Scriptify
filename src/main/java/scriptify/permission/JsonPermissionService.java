package scriptify.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scriptify.storage.DataStore;

public final class JsonPermissionService implements PermissionService {
	private static final Logger LOGGER = LoggerFactory.getLogger("scriptify/permissions");

	/** Set once the LuckPerms bridge has failed, so the warning is not repeated per command. */
	private static final AtomicBoolean LUCKPERMS_BROKEN = new AtomicBoolean();

	private static final String MODULE = "permissions";
	private final DataStore storage;
	private final Map<UUID, Set<String>> playerNodes = new HashMap<>();
	private final RankService rankService;
	private Object luckPermsApi;
	private LuckPermsBridge luckPerms;

	public JsonPermissionService(DataStore storage) {
		this.storage = storage;
		this.rankService = new RankService(storage);
	}

	@Override
	public RankService ranks() {
		return this.rankService;
	}

	@Override
	public boolean has(CommandSourceStack source, String node) {
		if (source.getEntity() == null) {
			return true;
		}
		if (source.getEntity() instanceof ServerPlayer player) {
			return has(player, node);
		}
		return false;
	}

	@Override
	public boolean has(ServerPlayer player, String node) {
		if (player.level().getServer().getPlayerList().isOp(player.nameAndId())) {
			return true;
		}
		Boolean luckPerms = checkLuckPerms(player, node);
		if (luckPerms != null) {
			return luckPerms;
		}
		Set<String> nodes = this.playerNodes.get(player.getUUID());
		if (nodes != null && (nodes.contains(node) || nodes.contains(ADMIN))) {
			return true;
		}
		// Ranks are the baseline; a direct grant above is an exception on top of one.
		return this.rankService.grants(player.getUUID(), node);
	}

	@Override
	public void reload() {
		this.luckPerms = LuckPermsBridge.resolve();
		this.luckPermsApi = this.luckPerms == null ? null : loadApi();
		this.rankService.load();
		this.playerNodes.clear();
		JsonObject root = this.storage.loadObject(MODULE, "permissions.json", this::defaultPermissions);
		JsonObject players = root.has("players") && root.get("players").isJsonObject() ? root.getAsJsonObject("players") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
			try {
				UUID uuid = UUID.fromString(entry.getKey());
				Set<String> nodes = new HashSet<>();
				if (entry.getValue().isJsonArray()) {
					JsonArray array = entry.getValue().getAsJsonArray();
					for (JsonElement element : array) {
						nodes.add(element.getAsString());
					}
				}
				this.playerNodes.put(uuid, nodes);
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	@Override
	public void grant(UUID player, String node) {
		this.playerNodes.computeIfAbsent(player, key -> new HashSet<>()).add(node);
		save();
	}

	@Override
	public void revoke(UUID player, String node) {
		Set<String> nodes = this.playerNodes.get(player);
		if (nodes != null && nodes.remove(node)) {
			if (nodes.isEmpty()) {
				this.playerNodes.remove(player);
			}
			save();
		}
	}

	@Override
	public Set<String> granted(UUID player) {
		Set<String> nodes = this.playerNodes.get(player);
		return nodes == null ? Set.of() : Set.copyOf(nodes);
	}

	@Override
	public boolean isManaged() {
		// LuckPerms answers first in has(), so local grants would be ignored while it is installed.
		return this.luckPermsApi == null;
	}

	private void save() {
		JsonObject root = defaultPermissions();
		JsonObject players = root.getAsJsonObject("players");
		this.playerNodes.forEach((uuid, nodes) -> {
			JsonArray array = new JsonArray();
			nodes.forEach(array::add);
			players.add(uuid.toString(), array);
		});
		this.storage.saveObject(MODULE, "permissions.json", root);
	}

	/** Fetches the singleton. Separate from the chain because it is the one static call. */
	private static Object loadApi() {
		try {
			return Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
		} catch (ReflectiveOperationException | LinkageError exception) {
			LOGGER.warn("LuckPerms is installed but has not finished starting, so Scriptify will use its "
				+ "own permissions file for this session.", exception);
			return null;
		}
	}

	private JsonObject defaultPermissions() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("players", new JsonObject());
		return root;
	}

	/**
	 * The LuckPerms call chain, resolved once.
	 *
	 * <p>Reflection by name is otherwise banned in this codebase, because a built jar is remapped to
	 * intermediary and a method looked up by its yarn name is then not there. That does not apply here:
	 * {@code net.luckperms.api} is a third-party API and the remapper never touches it, so these names
	 * are as stable in production as they are in dev.
	 *
	 * <p>Resolved against the published interfaces rather than against {@code object.getClass()}, for
	 * two reasons. It is looked up once instead of on every check - {@code getMethod} allocates and
	 * searches, and this sits on the path Brigadier walks for every command node for every player, so
	 * per-check lookups were thousands of them per login. And a method found on a non-public
	 * implementation class is not callable even when it is visible, which is a failure that would only
	 * ever have appeared on somebody else's server.
	 */
	private record LuckPermsBridge(Method getUserManager, Method getUser, Method getCachedData,
			Method getPermissionData, Method checkPermission, Method asBoolean) {

		/** @return the bridge, or null when LuckPerms is not installed or does not look like itself. */
		static LuckPermsBridge resolve() {
			try {
				Class<?> luckPerms = Class.forName("net.luckperms.api.LuckPerms");
				Class<?> userManager = Class.forName("net.luckperms.api.model.user.UserManager");
				Class<?> user = Class.forName("net.luckperms.api.model.user.User");
				Class<?> cachedData = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
				Class<?> permissionData = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
				Class<?> tristate = Class.forName("net.luckperms.api.util.Tristate");
				return new LuckPermsBridge(
					luckPerms.getMethod("getUserManager"),
					userManager.getMethod("getUser", UUID.class),
					user.getMethod("getCachedData"),
					cachedData.getMethod("getPermissionData"),
					permissionData.getMethod("checkPermission", String.class),
					tristate.getMethod("asBoolean"));
			} catch (ClassNotFoundException absent) {
				// LuckPerms is simply not installed. Not worth a line.
				return null;
			} catch (ReflectiveOperationException | LinkageError exception) {
				LOGGER.warn("LuckPerms is installed but its API does not look the way Scriptify expects, "
					+ "so permissions will come from Scriptify's own file instead. Report this with your "
					+ "LuckPerms version.", exception);
				return null;
			}
		}
	}

	/**
	 * Asks LuckPerms whether this player has the node.
	 *
	 * @return null when LuckPerms has no answer - not installed, the user is not in its cache, or the
	 *         call failed - which means the caller should fall back to Scriptify's own permissions.
	 */
	private Boolean checkLuckPerms(ServerPlayer player, String node) {
		Object api = this.luckPermsApi;
		LuckPermsBridge bridge = this.luckPerms;
		if (api == null || bridge == null) {
			return null;
		}
		try {
			Object userManager = bridge.getUserManager().invoke(api);
			Object user = bridge.getUser().invoke(userManager, player.getUUID());
			if (user == null) {
				// Not loaded into the LuckPerms cache. A real answer, not a failure.
				return null;
			}
			Object permissionData = bridge.getPermissionData()
				.invoke(bridge.getCachedData().invoke(user));
			boolean allowed = (boolean) bridge.asBoolean()
				.invoke(bridge.checkPermission().invoke(permissionData, node));
			if (allowed) {
				return true;
			}
			return (boolean) bridge.asBoolean()
				.invoke(bridge.checkPermission().invoke(permissionData, ADMIN));
		} catch (ReflectiveOperationException | LinkageError exception) {
			// Once, not once per permission check - this runs on the path Brigadier walks for every
			// command node, so an unguarded warn here would be the loudest thing in the log.
			if (LUCKPERMS_BROKEN.compareAndSet(false, true)) {
				LOGGER.warn("Asking LuckPerms about a permission failed, so Scriptify is answering from "
					+ "its own permissions file from here on. This is logged once.", exception);
			}
			return null;
		}
	}
}
