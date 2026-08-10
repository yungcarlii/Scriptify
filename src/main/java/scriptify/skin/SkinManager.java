package scriptify.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;
import scriptify.nick.NickManager;
import scriptify.nick.PlayerRefresh;

public final class SkinManager {
	private static final String MODULE = "skins";
	private static final String FILE = "skins.json";
	private final ScriptifyContext context;
	private final NickManager nickManager;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Map<UUID, SkinTexture> textures = new HashMap<>();

	public SkinManager(ScriptifyContext context, NickManager nickManager) {
		this.context = context;
		this.nickManager = nickManager;
		load();
	}

	public void load() {
		this.textures.clear();
		SkinRegistry.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, this::defaults);
		JsonObject players = root.has("players") && root.get("players").isJsonObject() ? root.getAsJsonObject("players") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
			try {
				JsonObject object = entry.getValue().getAsJsonObject();
				String value = object.get("value").getAsString();
				String signature = object.has("signature") ? object.get("signature").getAsString() : "";
				UUID uuid = UUID.fromString(entry.getKey());
				SkinTexture texture = new SkinTexture(value, signature);
				this.textures.put(uuid, texture);
				SkinRegistry.put(uuid, texture.toProperty());
			} catch (IllegalArgumentException | IllegalStateException exception) {
				this.context.logger().warn("Ignoring invalid stored skin for '{}'.", entry.getKey());
			}
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonObject players = root.getAsJsonObject("players");
		this.textures.forEach((uuid, texture) -> {
			JsonObject object = new JsonObject();
			object.addProperty("value", texture.value());
			object.addProperty("signature", texture.signature());
			players.add(uuid.toString(), object);
		});
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	public void copySkin(String sourceName, ServerPlayer target) throws IOException, InterruptedException {
		SkinTexture texture = fetchTexture(sourceName).orElseThrow(() -> new IOException("No signed texture returned by Mojang for " + sourceName));
		this.textures.put(target.getUUID(), texture);
		SkinRegistry.put(target.getUUID(), texture.toProperty());
		applyTexture(target, texture);
		save();
	}

	/**
	 * Drops the skin override entirely, leaving the player with no texture (the default Steve/Alex).
	 *
	 * <p>Distinct from {@link #resetSkin}: this removes the texture property, it does not go and fetch
	 * the account's real skin back.
	 */
	public void clearSkin(ServerPlayer player) {
		this.textures.remove(player.getUUID());
		SkinRegistry.remove(player.getUUID());
		SkinRegistry.applyToProfile(player, null);
		PlayerRefresh.refresh(player);
		save();
	}

	/**
	 * Restores the player's own Mojang skin by re-fetching it under their real account name, undoing any
	 * {@code /skin set} that was applied to them.
	 */
	public void resetSkin(ServerPlayer player) throws IOException, InterruptedException {
		this.textures.remove(player.getUUID());
		SkinRegistry.remove(player.getUUID());
		SkinTexture original = fetchTexture(player.getGameProfile().name())
			.orElseThrow(() -> new IOException("No signed texture returned by Mojang for " + player.getGameProfile().name()));
		applyTexture(player, original);
		save();
	}

	private Optional<SkinTexture> fetchTexture(String name) throws IOException, InterruptedException {
		String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
		HttpRequest uuidRequest = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encodedName))
			.timeout(Duration.ofSeconds(8))
			.GET()
			.build();
		HttpResponse<String> uuidResponse = this.http.send(uuidRequest, HttpResponse.BodyHandlers.ofString());
		if (uuidResponse.statusCode() != 200) {
			return Optional.empty();
		}
		JsonObject uuidJson = JsonParser.parseString(uuidResponse.body()).getAsJsonObject();
		String id = uuidJson.get("id").getAsString();
		HttpRequest profileRequest = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
			.timeout(Duration.ofSeconds(8))
			.GET()
			.build();
		HttpResponse<String> profileResponse = this.http.send(profileRequest, HttpResponse.BodyHandlers.ofString());
		if (profileResponse.statusCode() != 200) {
			return Optional.empty();
		}
		JsonObject profileJson = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
		JsonArray properties = profileJson.getAsJsonArray("properties");
		for (JsonElement element : properties) {
			JsonObject property = element.getAsJsonObject();
			if (SkinRegistry.TEXTURES.equals(property.get("name").getAsString())) {
				return Optional.of(new SkinTexture(property.get("value").getAsString(), property.get("signature").getAsString()));
			}
		}
		return Optional.empty();
	}

	/**
	 * Writes the texture onto the player's profile and forces every client to pick it up.
	 *
	 * <p>The signature must be kept with the value: Mojang signs the texture blob, and a client given a
	 * value without its matching signature silently falls back to the default skin — which is the usual
	 * reason a server-side skin change appears to do nothing.
	 */
	private void applyTexture(ServerPlayer player, SkinTexture texture) {
		try {
			// Replace the whole profile rather than editing the property map in place: the map a profile
			// arrives with can be immutable, in which case an in-place edit silently does nothing.
			SkinRegistry.applyToProfile(player, texture.toProperty());
			// A full refresh, including the player's own client — otherwise the wearer keeps seeing their
			// old skin in inventory and third person until they reconnect.
			PlayerRefresh.refresh(player);
			this.nickManager.applyVisuals(player);
		} catch (RuntimeException exception) {
			this.context.logger().error("Failed to apply skin to {}.", player.getGameProfile().name(), exception);
		}
	}

	private JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("players", new JsonObject());
		return root;
	}

	private record SkinTexture(String value, String signature) {
		/**
		 * Mojang signs the texture blob, and a client handed a value without its matching signature falls
		 * back to the default skin — so the signature is kept whenever there is one.
		 */
		private Property toProperty() {
			return this.signature == null || this.signature.isBlank()
				? new Property(SkinRegistry.TEXTURES, this.value)
				: new Property(SkinRegistry.TEXTURES, this.value, this.signature);
		}
	}
}
