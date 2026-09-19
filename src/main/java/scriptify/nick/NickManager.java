package scriptify.nick;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;

public final class NickManager {
	private static final String MODULE = "nicknames";
	private static final String FILE = "nicknames.json";
	private final ScriptifyContext context;
	private final Map<UUID, String> nicknames = new HashMap<>();
	private final NameGenerator generator = new NameGenerator();

	public NickManager(ScriptifyContext context) {
		this.context = context;
	}

	public void load() {
		this.nicknames.clear();
		NickRegistry.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, this::defaults);
		JsonObject values = root.has("players") && root.get("players").isJsonObject() ? root.getAsJsonObject("players") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
			try {
				String nickname = sanitize(entry.getValue().getAsString());
				if (!nickname.isBlank()) {
					UUID uuid = UUID.fromString(entry.getKey());
					this.nicknames.put(uuid, nickname);
					NickRegistry.put(uuid, nickname);
				}
			} catch (IllegalArgumentException exception) {
				this.context.logger().warn("Ignoring invalid nickname entry for '{}'.", entry.getKey());
			}
		}
		for (ServerPlayer player : this.context.server().getPlayerList().getPlayers()) {
			applyVisuals(player);
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonObject values = root.getAsJsonObject("players");
		this.nicknames.forEach((uuid, nickname) -> values.addProperty(uuid.toString(), nickname));
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	public String set(ServerPlayer target, String nickname) {
		String clean = sanitize(nickname);
		if (clean.isBlank()) {
			throw new IllegalArgumentException("Nickname must contain 1-16 visible characters.");
		}
		this.nicknames.put(target.getUUID(), clean);
		NickRegistry.put(target.getUUID(), clean);
		applyVisuals(target);
		save();
		return clean;
	}

	/**
	 * Assigns a locally generated nickname. Instant, but the name is invented rather than real.
	 */
	public String random(ServerPlayer target) {
		return set(target, this.generator.generate());
	}

	public void clear(ServerPlayer target) {
		this.nicknames.remove(target.getUUID());
		NickRegistry.remove(target.getUUID());
		applyVisuals(target);
		save();
	}

	public boolean isNicked(ServerPlayer player) {
		return this.nicknames.containsKey(player.getUUID());
	}

	public String displayName(ServerPlayer player) {
		return this.nicknames.getOrDefault(player.getUUID(), player.getGameProfile().name());
	}

	public Component displayComponent(ServerPlayer player) {
		return Component.literal(displayName(player));
	}

	/**
	 * Pushes the current nickname out to every connected client.
	 *
	 * <p>The tab-list name and every display-name use site are handled by the Scriptify mixins as soon
	 * as {@link NickRegistry} knows the nickname; what still has to be forced is the client-side
	 * snapshot of the player entity, which is where the above-head nametag comes from.
	 */
	public void applyVisuals(ServerPlayer player) {
		if (!isNicked(player)) {
			// Nothing to show. This runs on every login, and a refresh re-tracks the entity through the
			// chunk map — not work worth doing for a player who has no nickname at all.
			return;
		}
		try {
			// resendToOthers rebuilds the player-list entry for every client (including the wearer's own
			// tab list) and re-tracks the entity, which is all a nickname needs.
			PlayerRefresh.resendToOthers(player);
		} catch (RuntimeException exception) {
			this.context.logger().error("Failed to refresh nickname visuals for {}.", player.getGameProfile().name(), exception);
		}
	}

	/**
	 * Nicknames are capped at the vanilla 16-character username limit: the above-head tag is delivered
	 * by spoofing the profile name in the player-info packet, and longer values are not valid there.
	 */
	private static String sanitize(String nickname) {
		String clean = nickname == null ? "" : nickname.replaceAll("[^A-Za-z0-9_]", "").trim();
		if (clean.length() > 16) {
			clean = clean.substring(0, 16);
		}
		return clean;
	}

	private JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("players", new JsonObject());
		return root;
	}
}
