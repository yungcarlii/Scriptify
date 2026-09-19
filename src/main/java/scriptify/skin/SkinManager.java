package scriptify.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;
import scriptify.nick.NickManager;
import scriptify.nick.PlayerRefresh;

public final class SkinManager {
	private static final String MODULE = "skins";
	private static final String FILE = "skins.json";
	/** How many accounts to try before falling back — most have a skin, a few do not. */
	private static final int RANDOM_SKIN_ATTEMPTS = 5;
	private final ScriptifyContext context;
	private final NickManager nickManager;
	private final Map<UUID, SkinTexture> textures = new HashMap<>();
	/**
	 * Each player's own skin, captured the first time Scriptify overrode it.
	 *
	 * <p>This is what makes putting somebody back the way they were reliable. Fetching their real skin
	 * from Mojang is a network call that can fail, and failing means leaving them wearing a stranger's
	 * face; a copy taken before the override cannot fail at all.
	 *
	 * <p>A blank value is meaningful: it records a player who had no skin of their own, so clearing
	 * returns them to that rather than to whatever a lookup happens to answer.
	 */
	private final Map<UUID, SkinTexture> originals = new HashMap<>();
	private final Random random = new Random();
	/** Names an identity is given. Shared shapes with /randomnick, so the two cannot drift apart. */
	private final scriptify.nick.NameGenerator names = new scriptify.nick.NameGenerator();
	private final NameMcSkinSource nameMc;
	private MojangNameSource mojangNames;
	/**
	 * Skin lookups leave the server thread. A single daemon thread is plenty — these are occasional
	 * operator actions, and serialising them also keeps Scriptify from hammering an external site.
	 */
	private static final ExecutorService SKIN_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Scriptify Skin Lookup");
		thread.setDaemon(true);
		return thread;
	});

	public SkinManager(ScriptifyContext context, NickManager nickManager) {
		this.context = context;
		this.nickManager = nickManager;
		this.nameMc = new NameMcSkinSource(context.logger());
		load();
	}

	public void load() {
		this.textures.clear();
		this.originals.clear();
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

		JsonObject saved = root.has("originals") && root.get("originals").isJsonObject()
			? root.getAsJsonObject("originals") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
			try {
				JsonObject object = entry.getValue().getAsJsonObject();
				this.originals.put(UUID.fromString(entry.getKey()), new SkinTexture(
					object.has("value") ? object.get("value").getAsString() : "",
					object.has("signature") ? object.get("signature").getAsString() : ""));
			} catch (IllegalArgumentException | IllegalStateException exception) {
				this.context.logger().warn("Ignoring invalid original skin for '{}'.", entry.getKey());
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
		JsonObject saved = new JsonObject();
		this.originals.forEach((uuid, texture) -> {
			JsonObject object = new JsonObject();
			object.addProperty("value", texture.value());
			object.addProperty("signature", texture.signature());
			saved.add(uuid.toString(), object);
		});
		root.add("originals", saved);
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	/**
	 * Records what a player looked like before Scriptify touched them.
	 *
	 * <p>Only the first call for a player sticks. Capturing again after an override would save the
	 * borrowed face as if it were theirs, which is exactly the state clearing is meant to undo.
	 */
	private void rememberOriginal(ServerPlayer player) {
		if (this.originals.containsKey(player.getUUID())) {
			return;
		}
		this.originals.put(player.getUUID(), textureOf(player.getGameProfile())
			.orElse(new SkinTexture("", "")));
	}

	/**
	 * Copies another account's skin onto a player.
	 *
	 * <p>Asynchronous because it talks to Mojang: doing this on the server thread froze every player for
	 * the length of the request, which on a slow lookup is seconds.
	 *
	 * @param onResult {@code true} when a signed texture was found and applied.
	 */
	public void copySkinAsync(String sourceName, ServerPlayer target, Consumer<Boolean> onResult) {
		CompletableFuture.supplyAsync(() -> fetchTexture(sourceName), SKIN_EXECUTOR)
			.thenAcceptAsync(texture -> {
				if (texture.isEmpty()) {
					onResult.accept(false);
					return;
				}
				rememberOriginal(target);
				this.textures.put(target.getUUID(), texture.get());
				SkinRegistry.put(target.getUUID(), texture.get().toProperty());
				applyTexture(target, texture.get());
				save();
				onResult.accept(true);
			}, this.context.server());
	}

	/**
	 * Gives the player a random skin, pulled from NameMC's public skin listing.
	 *
	 * <p>The lookup runs off the server thread and the result is applied back on it — an HTTP request on
	 * the main thread would stall every player on the server for as long as it took.
	 *
	 * <p>Falls back to borrowing a skin from another online player when NameMC is unreachable or its
	 * page format has changed, so the command still does something useful rather than failing outright.
	 *
	 * @param onResult receives a description of what was applied, or empty when nothing could be.
	 */
	public void randomSkinAsync(ServerPlayer target, Consumer<Optional<String>> onResult) {
		var server = this.context.server();
		CompletableFuture.supplyAsync(this::findRandomSkin, SKIN_EXECUTOR)
			.thenAcceptAsync(found -> {
				if (found.isPresent()) {
					SkinTexture texture = found.get().texture();
					rememberOriginal(target);
					this.textures.put(target.getUUID(), texture);
					SkinRegistry.put(target.getUUID(), texture.toProperty());
					applyTexture(target, texture);
					save();
					onResult.accept(Optional.of(found.get().description()));
				} else {
					onResult.accept(borrowFromOnlinePlayer(target));
				}
			}, server);
	}

	/** A random skin and a human-readable description of where it came from. */
	private record FoundSkin(SkinTexture texture, String description) {
	}

	/**
	 * Finds a random skin, preferring real accounts from Mojang.
	 *
	 * <p>Mojang first because those textures are signed and come with a real username to report back.
	 * NameMC is the fallback for when Mojang lookups turn up nothing.
	 *
	 * <p>Blocking: runs on the skin executor.
	 */
	private Optional<FoundSkin> findRandomSkin() {
		if (this.mojangNames == null) {
			this.mojangNames = new MojangNameSource(this.context.server(), this.context.logger());
		}
		// Plenty of real accounts have never set a skin and come back with no texture at all, which would
		// hand out a default Steve. Try several before giving up so a random skin is always a real one.
		for (int attempt = 0; attempt < RANDOM_SKIN_ATTEMPTS; attempt++) {
			Optional<MojangNameSource.Account> account = this.mojangNames.random();
			if (account.isEmpty()) {
				break;
			}
			Optional<SkinTexture> texture = fetchTextureById(account.get().id());
			if (texture.isPresent() && !texture.get().value().isBlank()) {
				return Optional.of(new FoundSkin(texture.get(), account.get().name() + "'s skin"));
			}
		}
		// NameMC textures are unsigned: Mojang only signs a texture for the account that owns it.
		// Clients render them fine, they are simply not verifiable.
		return this.nameMc.randomTextureValue()
			.map(value -> new FoundSkin(new SkinTexture(value, ""), "a random NameMC skin"));
	}

	/** Signed texture lookup by account id, skipping the name-resolution step. */
	private Optional<SkinTexture> fetchTextureById(UUID id) {
		try {
			ProfileResult result = this.context.server().services().sessionService().fetchProfile(id, true);
			return result == null ? Optional.empty() : textureOf(result.profile());
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	/**
	 * Takes on a random identity: a generated name, wearing a real account's skin.
	 *
	 * <p><b>The name is no longer taken from the account the skin came from</b>, and that is the
	 * point. Adopting a real account wholesale sounds like the realistic choice and produces the
	 * opposite: the names that can still be found on Mojang are the ones that were claimed in 2011,
	 * so a server full of them reads as Hawk, Dragon, Fable, Jester, King, Titan, Savage, Anvil,
	 * Mantis and Sable. Every one of those is a genuine registered account, and no active player has
	 * been called any of them for a decade - the people playing today are matibezgaci2137 and
	 * lukicha19, because everything short was gone long before they arrived.
	 *
	 * <p>So the two halves are split. The name comes from the local generator, which is modelled on
	 * what a current server's list actually looks like; the skin still comes from a real account,
	 * because a real skin is the half that cannot be invented. Nobody can tell that the skin belongs
	 * to somebody else - there is nothing on screen to compare it against - whereas everybody can
	 * tell that a tab list looks fifteen years old.
	 *
	 * <p>Only accounts that have actually set a skin are used: a name over a default Steve is exactly
	 * the giveaway an identity is meant to avoid.
	 *
	 * @param onResult the name adopted, or empty when no skin could be reached.
	 */
	public void randomIdentityAsync(ServerPlayer target, Consumer<Optional<String>> onResult) {
		CompletableFuture.supplyAsync(() -> {
			if (this.mojangNames == null) {
				this.mojangNames = new MojangNameSource(this.context.server(), this.context.logger());
			}
			for (int attempt = 0; attempt < RANDOM_SKIN_ATTEMPTS; attempt++) {
				Optional<MojangNameSource.Account> account = this.mojangNames.random();
				if (account.isEmpty()) {
					break;
				}
				// Only the id is wanted here. The account's own name is deliberately discarded - see
				// the note above on why a real account's name is the least realistic thing about it.
				Optional<SkinTexture> texture = fetchTextureById(account.get().id());
				if (texture.isPresent() && !texture.get().value().isBlank()) {
					return texture;
				}
			}
			return Optional.<SkinTexture>empty();
		}, SKIN_EXECUTOR).thenAcceptAsync(found -> {
			if (found.isEmpty()) {
				onResult.accept(Optional.empty());
				return;
			}
			SkinTexture texture = found.get();
			String name = this.names.generate();
			rememberOriginal(target);
			this.textures.put(target.getUUID(), texture);
			SkinRegistry.put(target.getUUID(), texture.toProperty());
			this.nickManager.set(target, name);
			applyTexture(target, texture);
			save();
			onResult.accept(Optional.of(name));
		}, this.context.server());
	}

	/**
	 * Copies a live player's texture. Always succeeds when anyone else is online, needs no network, and
	 * is guaranteed to be a real skin.
	 *
	 * @return the name whose skin was applied, or empty when nobody else is online.
	 */
	private Optional<String> borrowFromOnlinePlayer(ServerPlayer target) {
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer other : this.context.server().getPlayerList().getPlayers()) {
			if (other != target && other.getGameProfile().properties().containsKey(SkinRegistry.TEXTURES)) {
				candidates.add(other);
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		ServerPlayer source = candidates.get(this.random.nextInt(candidates.size()));
		Property texture = source.getGameProfile().properties().get(SkinRegistry.TEXTURES).iterator().next();
		SkinTexture copied = new SkinTexture(texture.value(), texture.signature() == null ? "" : texture.signature());
		rememberOriginal(target);
		this.textures.put(target.getUUID(), copied);
		SkinRegistry.put(target.getUUID(), copied.toProperty());
		applyTexture(target, copied);
		save();
		return Optional.of(source.getGameProfile().name() + "'s skin");
	}

	public NameMcSkinSource nameMc() {
		return this.nameMc;
	}

	/**
	 * Removes the override and puts the player's own skin back.
	 *
	 * <p>Restoring rather than stripping: "clear" should undo what Scriptify did, and leaving someone as
	 * a default Steve is not their skin, it is just a different wrong one.
	 *
	 * @param onResult {@code true} once their real skin has been fetched and applied.
	 */
	public void clearSkinAsync(ServerPlayer player, Consumer<Boolean> onResult) {
		this.textures.remove(player.getUUID());
		SkinRegistry.remove(player.getUUID());

		// What they logged in wearing, which is the authoritative answer: it was read from Mojang's own
		// profile at login, before anything of ours touched it, and it is refreshed on every login. It
		// is checked ahead of the stored copy below because that copy is taken at override time, and by
		// then the player may already have been wearing a borrowed face from an earlier session.
		Property loginOriginal = SkinRegistry.original(player.getUUID());
		if (loginOriginal != null) {
			this.originals.remove(player.getUUID());
			if (SkinRegistry.isNoSkin(loginOriginal)) {
				SkinRegistry.applyToProfile(player, null);
				PlayerRefresh.refresh(player);
			} else {
				applyTexture(player, new SkinTexture(loginOriginal.value(),
					loginOriginal.signature() == null ? "" : loginOriginal.signature()));
			}
			save();
			onResult.accept(true);
			return;
		}

		// The copy taken before the first override, if there is one. No network at all, so this works
		// while Mojang is unreachable — which is precisely when being stuck as somebody else is worst.
		SkinTexture original = this.originals.remove(player.getUUID());
		if (original != null) {
			if (original.value().isBlank()) {
				// They had no skin of their own. Strip the override rather than fetching a replacement.
				SkinRegistry.applyToProfile(player, null);
				PlayerRefresh.refresh(player);
			} else {
				applyTexture(player, original);
			}
			save();
			onResult.accept(true);
			return;
		}

		// Nothing stored: a skin set before this version, or applied by something else. Ask for theirs.
		copySkinAsync(player.getGameProfile().name(), player, applied -> {
			// copySkinAsync re-registers an override; clearing should leave nothing stored, so that the
			// login hook does not reapply it later as if it were a Scriptify skin.
			this.textures.remove(player.getUUID());
			this.originals.remove(player.getUUID());
			SkinRegistry.remove(player.getUUID());
			save();
			onResult.accept(applied);
		});
	}


	/**
	 * Looks a skin up through the server's own Mojang session service.
	 *
	 * <p>The server already ships authlib and is already configured to talk to Mojang, so going through
	 * {@code services()} rather than hand-rolling HTTP means this inherits the server's proxy settings,
	 * its name-to-id cache, and its retry behaviour — and returns a **signed** texture, which a
	 * hand-built request only gets right if it remembers {@code ?unsigned=false}.
	 *
	 * <p>Blocking: call this off the server thread.
	 */
	private Optional<SkinTexture> fetchTexture(String name) {
		Optional<SkinTexture> viaServer = fetchViaSessionService(name);
		if (viaServer.isPresent()) {
			return viaServer;
		}
		// Each step below is only reached because the one above produced nothing.
		Optional<SkinTexture> direct = MojangDirect.fetchTexture(name, this.context.logger())
			.map(found -> new SkinTexture(found.value(), found.signature()));
		if (direct.isPresent()) {
			return direct;
		}
		return ElyBySkinSource.fetchTexture(name, this.context.logger())
			.map(found -> new SkinTexture(found.value(), found.signature()));
	}

	/**
	 * The server's own authlib path: its cache, its retries, its idea of where Mojang is.
	 *
	 * <p>First because it is the only one that can answer without a network call at all, and because a
	 * texture it returns is signed by Mojang for the account that owns it.
	 *
	 * <p>An empty answer here is not the end of the lookup any more, and that was the bug. Falling
	 * through used to happen only when this threw - so an authlib call that simply <em>returned
	 * nothing</em>, which is what a throttled or transiently failing lookup does, ended the whole
	 * attempt with a "no such player" that had nothing to do with whether the player existed. Nothing
	 * distinguishes that from a real miss at this layer, so the answer is to keep asking: a name
	 * nobody can find costs two more requests, and a name that was one bad response away from working
	 * now works. That fall-through is the whole of what makes SkinRestorer feel reliable, and it is
	 * the part Scriptify did not have.
	 */
	private Optional<SkinTexture> fetchViaSessionService(String name) {
		try {
			MinecraftServer server = this.context.server();
			// Step one resolves the name to an id, using the cache the server already maintains.
			Optional<GameProfile> resolved = server.services().profileResolver().fetchByName(name);
			if (resolved.isEmpty()) {
				return Optional.empty();
			}
			// Step two asks for the full profile with secure (signed) textures attached.
			ProfileResult result = server.services().sessionService().fetchProfile(resolved.get().id(), true);
			if (result == null) {
				return Optional.empty();
			}
			return textureOf(result.profile());
		} catch (RuntimeException exception) {
			this.context.logger().warn("Mojang lookup failed for '{}', trying the API directly.", name);
			return Optional.empty();
		}
	}

	/** Pulls the signed {@code textures} property out of a profile, if it has one. */
	private static Optional<SkinTexture> textureOf(GameProfile profile) {
		for (Property property : profile.properties().get(SkinRegistry.TEXTURES)) {
			String signature = property.signature();
			return Optional.of(new SkinTexture(property.value(), signature == null ? "" : signature));
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
