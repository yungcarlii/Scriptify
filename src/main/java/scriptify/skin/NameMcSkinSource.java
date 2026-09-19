package scriptify.skin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Pulls random skins from NameMC's public skin listing.
 *
 * <p>NameMC publishes no official API, so this reads the same page a browser would and pulls the skin
 * hashes out of it. Those hashes are Mojang texture hashes — the same ones {@code textures.minecraft.net}
 * serves — which is what makes this usable at all: a vanilla client will only load a skin from Mojang's
 * texture host, so an arbitrary image URL would not work.
 *
 * <p>Because it depends on an undocumented page, every failure path falls back rather than propagating.
 * If NameMC changes its markup, blocks the request or is simply down, random skins quietly revert to
 * borrowing one from another online player.
 */
public final class NameMcSkinSource {
	private static final String RANDOM_SKINS_URL = "https://namemc.com/minecraft-skins/random";
	/** NameMC skin ids are hex texture hashes; anything shorter is not one. */
	private static final Pattern SKIN_LINK = Pattern.compile("/skin/([0-9a-fA-F]{32,64})");
	/** Refresh no more often than this, to stay a polite consumer of someone else's page. */
	private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(5);

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final Logger logger;
	private final Random random = new Random();
	/** Cached hashes, so one fetch supplies many random skins. */
	private final List<String> hashes = new CopyOnWriteArrayList<>();
	private volatile long lastFetchMillis;
	private volatile boolean unavailable;

	public NameMcSkinSource(Logger logger) {
		this.logger = logger;
	}

	/**
	 * A random cached skin, refreshing the cache first when it is empty or stale.
	 *
	 * <p>Blocking: call this off the server thread.
	 *
	 * @return a base64 texture value ready to put on a profile, or empty when NameMC could not be used.
	 */
	public Optional<String> randomTextureValue() {
		if (shouldRefresh()) {
			refresh();
		}
		if (this.hashes.isEmpty()) {
			return Optional.empty();
		}
		String hash = this.hashes.get(this.random.nextInt(this.hashes.size()));
		return Optional.of(encodeTexture(hash));
	}

	private boolean shouldRefresh() {
		if (this.unavailable) {
			return false;
		}
		long elapsed = System.currentTimeMillis() - this.lastFetchMillis;
		return this.hashes.isEmpty() || elapsed > MIN_REFRESH_INTERVAL.toMillis();
	}

	/** Fetches a fresh page of random skins. Failures disable the source rather than throwing. */
	private void refresh() {
		this.lastFetchMillis = System.currentTimeMillis();
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(RANDOM_SKINS_URL))
				.timeout(Duration.ofSeconds(10))
				// Identify honestly; NameMC rejects requests with no user agent at all.
				.header("User-Agent", "Scriptify/1.3.6 (Minecraft server mod)")
				.header("Accept", "text/html")
				.GET()
				.build();
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				disable("NameMC returned HTTP " + response.statusCode());
				return;
			}
			List<String> found = new ArrayList<>();
			Matcher matcher = SKIN_LINK.matcher(response.body());
			while (matcher.find()) {
				String hash = matcher.group(1).toLowerCase();
				if (!found.contains(hash)) {
					found.add(hash);
				}
			}
			if (found.isEmpty()) {
				disable("NameMC's page format changed — no skin hashes found");
				return;
			}
			this.hashes.clear();
			this.hashes.addAll(found);
			this.logger.info("Cached {} random skin(s) from NameMC.", found.size());
		} catch (Exception exception) {
			disable("could not reach NameMC (" + exception.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * Turns off this source for the rest of the session.
	 *
	 * <p>Retrying a blocked or changed endpoint every time somebody runs the command would just add
	 * latency to a call that is going to fall back anyway.
	 */
	private void disable(String reason) {
		this.unavailable = true;
		this.logger.warn("Random skins from NameMC are unavailable: {}. Falling back to online players.", reason);
	}

	/** Lets an operator retry after a transient outage without restarting the server. */
	public void reset() {
		this.unavailable = false;
		this.hashes.clear();
		this.lastFetchMillis = 0L;
	}

	public boolean isAvailable() {
		return !this.unavailable;
	}

	public int cachedCount() {
		return this.hashes.size();
	}

	/**
	 * Wraps a texture hash in the profile-property JSON a client expects, base64 encoded.
	 *
	 * <p>No signature is produced — Mojang only signs textures for the account that owns them. Clients
	 * render unsigned textures fine; they are simply not verifiable.
	 */
	private static String encodeTexture(String hash) {
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + hash + "\"}}}";
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/** Exposed for the command that reports source status. */
	public List<String> cachedHashes() {
		return Collections.unmodifiableList(this.hashes);
	}
}
