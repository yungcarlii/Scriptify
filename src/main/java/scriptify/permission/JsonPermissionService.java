package scriptify.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import scriptify.storage.DataStore;

public final class JsonPermissionService implements PermissionService {
	private static final String MODULE = "permissions";
	private final DataStore storage;
	private final Map<UUID, Set<String>> playerNodes = new HashMap<>();
	private Object luckPermsApi;

	public JsonPermissionService(DataStore storage) {
		this.storage = storage;
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
		return nodes != null && (nodes.contains(node) || nodes.contains(ADMIN));
	}

	@Override
	public void reload() {
		this.luckPermsApi = loadLuckPermsApi();
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

	private JsonObject defaultPermissions() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("players", new JsonObject());
		return root;
	}

	private static Object loadLuckPermsApi() {
		try {
			Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
			return provider.getMethod("get").invoke(null);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	private Boolean checkLuckPerms(ServerPlayer player, String node) {
		Object api = this.luckPermsApi;
		if (api == null) {
			return null;
		}
		try {
			Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
			Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUUID());
			if (user == null) {
				return null;
			}
			Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
			Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
			Object result = permissionData.getClass().getMethod("checkPermission", String.class).invoke(permissionData, node);
			Object adminResult = permissionData.getClass().getMethod("checkPermission", String.class).invoke(permissionData, ADMIN);
			boolean allowed = (boolean) result.getClass().getMethod("asBoolean").invoke(result);
			boolean admin = (boolean) adminResult.getClass().getMethod("asBoolean").invoke(adminResult);
			return allowed || admin;
		} catch (ReflectiveOperationException | LinkageError exception) {
			return null;
		}
	}
}
