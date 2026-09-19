package scriptify.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * A second way to ask Mojang for a skin, used when the server's own session service does not answer.
 *
 * <p>The server talks to Mojang through authlib, which caches, retries and gives up on its own
 * schedule. When it gives up, the player is left wearing somebody else's face and the only way out is
 * a reconnect. This talks to the same public endpoints directly, so a transient failure in one path
 * is not the end of the attempt.
 *
 * <p>Not a replacement for authlib: it is tried second, and only when the first path returned
 * nothing. Both go to Mojang, so this does not help when Mojang itself is down — the stored original
 * skin is what covers that case.
 *
 * <p>Blocking. Called from the skin executor, never the server thread.
 */
public final class MojangDirect {
	/**
	 * Where Mojang is, asked rather than assumed.
	 *
	 * <p>These two hosts were written out by hand, which is wrong twice over: a server started with
	 * {@code -Dminecraft.api.session.host} pointed somewhere else - every offline-mode setup behind an
	 * authentication proxy does exactly that - was still asked the public address, and the answer came
	 * back about a different account or not at all. Authlib already knows where it was told to look, so
	 * ask it. Falling back to the production hosts leaves the common case identical to before.
	 *
	 * <p>Taken from SkinRestorer (MIT, Lionarius), which does the same thing and is the reason its
	 * lookups hold up on setups where a hardcoded host quietly does not.
	 */
	private static final com.mojang.authlib.Environment ENVIRONMENT =
		com.mojang.authlib.EnvironmentParser.getEnvironmentFromProperties()
			.orElseGet(com.mojang.authlib.yggdrasil.YggdrasilEnvironment.PROD::getEnvironment);

	private static final String NAME_TO_ID = ENVIRONMENT.profilesHost() + "/users/profiles/minecraft/";
	private static final String PROFILE = ENVIRONMENT.sessionHost() + "/session/minecraft/profile/";
	private static final Duration TIMEOUT = Duration.ofSeconds(6);

	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/** A signed texture blob and its signature. */
	public record Texture(String value, String signature) {
	}

	private MojangDirect() {
	}

	/** Looks a name up and returns its signed texture, or empty if any step fails. */
	public static Optional<Texture> fetchTexture(String name, Logger logger) {
		try {
			Optional<String> id = fetchId(name);
			if (id.isEmpty()) {
				return Optional.empty();
			}
			return fetchTextureById(id.get());
		} catch (Exception exception) {
			logger.warn("Direct Mojang lookup for '{}' failed too.", name);
			return Optional.empty();
		}
	}

	private static Optional<String> fetchId(String name) throws Exception {
		JsonObject body = get(NAME_TO_ID + name);
		return body != null && body.has("id")
			? Optional.of(body.get("id").getAsString())
			: Optional.empty();
	}

	/**
	 * Reads the textures property off a profile.
	 *
	 * <p>{@code unsigned=false} matters: a client handed a texture value without its matching signature
	 * falls back to the default skin, so an unsigned answer would be worse than none.
	 */
	private static Optional<Texture> fetchTextureById(String id) throws Exception {
		return texturesOf(get(PROFILE + id + "?unsigned=false"));
	}

	/**
	 * Reads the textures property out of a session-server style profile body.
	 *
	 * <p>Package-visible because Ely.by answers in exactly this shape - it is a drop-in for Mojang's
	 * session server, which is the whole reason it is usable as a second opinion - and one parser for
	 * both is better than two that can drift.
	 */
	static Optional<Texture> texturesOf(JsonObject body) {
		if (body == null || !body.has("properties") || !body.get("properties").isJsonArray()) {
			return Optional.empty();
		}
		JsonArray properties = body.getAsJsonArray("properties");
		for (int index = 0; index < properties.size(); index++) {
			JsonObject property = properties.get(index).getAsJsonObject();
			if (!SkinRegistry.TEXTURES.equals(property.get("name").getAsString())) {
				continue;
			}
			String value = property.get("value").getAsString();
			String signature = property.has("signature") ? property.get("signature").getAsString() : "";
			return Optional.of(new Texture(value, signature));
		}
		return Optional.empty();
	}

	/** Shared with {@link ElyBySkinSource}; returns null for anything that is not a 200 with a body. */
	static JsonObject get(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(TIMEOUT)
			.header("User-Agent", "Scriptify (Minecraft server mod)")
			.GET()
			.build();
		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || response.body().isBlank()) {
			return null;
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
