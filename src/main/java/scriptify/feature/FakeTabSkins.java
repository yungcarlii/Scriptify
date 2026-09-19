package scriptify.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import scriptify.api.ScriptifyContext;
import scriptify.skin.MojangNameSource;
import scriptify.skin.SkinRegistry;

/**
 * The faces the fake tab list wears.
 *
 * <p>A padded tab list with no skins is a column of identical Steves, which reads as a bug rather
 * than as a busy server - the heads are the first thing anybody looks at, before a single name is
 * read. This keeps a modest supply of real, signed textures to hand them out from.
 *
 * <p>A supply rather than one face each, deliberately. Five hundred distinct skins is five hundred
 * Mojang lookups, and there is no version of that which is not either a long stall or a burst of
 * traffic that gets the server rate-limited. Sixty-odd faces spread over a large roster repeat, and
 * nobody notices: the heads are eight pixels wide and nobody scrolls a tab list comparing them. At
 * the sizes people actually film with - fifteen, forty - the pool covers the roster outright.
 *
 * <p><b>Skins of players who are actually connected are not used.</b> They are free and local and
 * it is tempting, but a real player seeing their own face on a stranger three rows down is the one
 * thing that gives the whole illusion away, and on a small SMP with four people online their faces
 * would be most of the tab list. The pool is real accounts nobody here is wearing.
 *
 * <p>Everything fetched is written to disk, so the wait happens once ever rather than once per
 * restart. Lookups are paced rather than fired off together, because filling a pool is background
 * work with nobody waiting on it and there is no reason to make it look like an attack.
 */
public final class FakeTabSkins {
	private static final String MODULE = "skins";
	private static final String FILE = "faketab-skins.json";

	/**
	 * How many distinct faces to collect, for a roster of a given size.
	 *
	 * <p>One each, near enough, rather than a fixed pool. A flat sixty-four was the first version and
	 * it was wrong at the top end: five hundred players wearing sixty-four faces is every face eight
	 * times over, which is exactly the sort of repetition a tab list is scanned for. Scaling with the
	 * roster means the small sizes stay cheap - fifteen players do not need three hundred faces - while
	 * a big one gets close to one apiece.
	 *
	 * <p>The floor is above the smallest rosters on purpose: churn keeps swapping entries out, and a
	 * pool no bigger than the roster would keep handing back the face that just left.
	 *
	 * <p>The ceiling is where the cost stops being worth it. Three hundred faces is roughly five
	 * hundred Mojang lookups on a fresh install - a few minutes of background trickle, once ever,
	 * since it is all written to disk.
	 */
	private static final int MIN_FACES = 64;
	private static final int MAX_FACES = 300;

	private static int targetFor(int rosterSize) {
		return Math.max(MIN_FACES, Math.min(MAX_FACES, rosterSize));
	}

	/** Enough faces to be worth repainting the roster for, rather than waiting for the full set. */
	private static final int FIRST_USABLE = 8;

	/** Pause between lookups while filling. Background work; there is nobody waiting on it. */
	private static final long FETCH_GAP_MILLIS = 400;

	/**
	 * Lookups to spend per face still wanted, before giving up on this fill.
	 *
	 * <p>Plenty of real accounts have never set a skin, so a lookup is not a face - about three in five
	 * are, in practice. Four apiece leaves room for that without letting a bad run - a rate limit, a
	 * streak of blanks - grind all evening. A fill that runs out simply stops; the next one picks up
	 * where it left off, because everything found so far is already on disk.
	 */
	private static final int LOOKUPS_PER_FACE = 4;

	/**
	 * Consecutive lookups that produce nothing before the fill gives up.
	 *
	 * <p>A run that is simply unlucky recovers long before this: even at the worst measured hit rate,
	 * roughly one lookup in seven turns into a face, so forty misses in a row is not luck. It is
	 * Mojang refusing - sustained lookups get throttled, and a throttled request fails the same silent
	 * way a skinless account does. Without this the fill kept its pace through all of it: one measured
	 * run spent seven minutes to collect what the first minute had already shown it would not get.
	 *
	 * <p>Stopping loses nothing. Everything found so far is on disk, and the next fill continues from
	 * there rather than starting again.
	 */
	private static final int MAX_BARREN_LOOKUPS = 40;

	private final ScriptifyContext context;
	private final List<Property> faces = new CopyOnWriteArrayList<>();
	/** Texture values already held, so a repeated account does not become a repeated face. */
	private final Set<String> known = new HashSet<>();
	private final Random random = new Random();
	private final AtomicBoolean filling = new AtomicBoolean();
	private MojangNameSource names;
	/** How many faces the current roster wants. Read from the fill thread, set on the server thread. */
	private volatile int target = MIN_FACES;

	/**
	 * One daemon thread of its own, not the skin manager's.
	 *
	 * <p>Sharing would queue a fill of sixty faces ahead of whatever an operator types next, and a
	 * random-skin command waiting half a minute behind background work is a worse trade than one
	 * extra idle thread.
	 */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Scriptify Fake Tab Skins");
		thread.setDaemon(true);
		return thread;
	});

	public FakeTabSkins(ScriptifyContext context) {
		this.context = context;
		load();
	}

	public int size() {
		return this.faces.size();
	}

	/** A face, or empty while the pool is still cold. Never blocks. */
	public Optional<Property> draw() {
		if (this.faces.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(this.faces.get(this.random.nextInt(this.faces.size())));
	}

	/**
	 * Tops the pool up in the background.
	 *
	 * <p>The callback fires when a fill actually found something, so the roster can be dealt the new
	 * faces rather than waiting for churn to rotate each entry onto one. On a cold pool it fires once
	 * early as well, as soon as there is anything usable, so nobody is looking at a screen of Steves
	 * for the minutes a full fill takes.
	 *
	 * <p>It cannot fire on a pool that is already big enough, because there is no fill: raising the
	 * size to something the pool does not cover is the only thing that starts one, and that is a setup
	 * action rather than something that happens mid-shot.
	 *
	 * @param rosterSize how many entries want a face; decides how many are worth collecting.
	 * @param onFacesReady run on the server thread when there are new faces worth repainting with.
	 */
	public void warm(int rosterSize, Runnable onFacesReady) {
		this.target = targetFor(rosterSize);
		boolean cold = this.faces.isEmpty();
		harvestLocal();
		if (cold && !this.faces.isEmpty()) {
			// The identity pool covered it outright; no lookup needed.
			onFacesReady.run();
			cold = false;
		}
		if (this.faces.size() >= this.target) {
			return;
		}
		fill(onFacesReady, cold);
	}

	/**
	 * Takes whatever the bot identity pool already holds.
	 *
	 * <p>Free, instant, and already curated by whoever filled that file. Usually empty, in which case
	 * this costs nothing.
	 */
	private void harvestLocal() {
		for (scriptify.bots.npc.IdentityPool.Entry entry : scriptify.bots.npc.IdentityPool.all()) {
			add(new Property(SkinRegistry.TEXTURES, entry.value, entry.signature));
		}
	}

	private void fill(Runnable onFacesReady, boolean cold) {
		if (!this.filling.compareAndSet(false, true)) {
			return;
		}
		this.executor.execute(() -> {
			int found = 0;
			try {
				if (this.names == null) {
					this.names = new MojangNameSource(this.context.server(), this.context.logger());
				}
				boolean announced = !cold;
				int barren = 0;
				int budget = Math.max(0, this.target - this.faces.size()) * LOOKUPS_PER_FACE;
				for (int lookup = 0; lookup < budget && this.faces.size() < this.target; lookup++) {
					Optional<MojangNameSource.Account> account = this.names.random();
					if (account.isEmpty()) {
						break;
					}
					Optional<Property> texture = fetchSigned(account.get().id());
					if (texture.isPresent() && add(texture.get())) {
						found++;
						barren = 0;
					} else if (++barren >= MAX_BARREN_LOOKUPS) {
						this.context.logger().info(
							"Fake tab skins: stopping this fill after {} lookups with nothing to show; "
								+ "{} face(s) held, and the next one will carry on from there.",
							barren, this.faces.size());
						break;
					}
					if (!announced && onFacesReady != null && this.faces.size() >= FIRST_USABLE) {
						announced = true;
						this.context.server().execute(onFacesReady);
					}
					Thread.sleep(FETCH_GAP_MILLIS);
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException exception) {
				this.context.logger().warn("Fake tab skin lookup stopped: {}", exception.getMessage());
			} finally {
				this.filling.set(false);
			}
			if (found > 0) {
				int added = found;
				int total = this.faces.size();
				// Saved on the server thread, which is where every other Scriptify file is written.
				this.context.server().execute(() -> {
					save();
					this.context.logger().info("Fake tab skins: {} new face(s), {} held.", added, total);
					if (onFacesReady != null) {
						// Deal the new faces out. On a cold pool the early repaint could only draw from
						// the handful that existed when it ran; on a roster that has just been enlarged
						// past what the pool covered, the entries were built before these arrived. Either
						// way the faces are here and nothing else would put them on anybody.
						onFacesReady.run();
					}
				});
			}
		});
	}

	/** Signed texture for an account id, or empty when it has no skin or the lookup fails. */
	private Optional<Property> fetchSigned(UUID id) {
		try {
			ProfileResult result = this.context.server().services().sessionService().fetchProfile(id, true);
			if (result == null) {
				return Optional.empty();
			}
			for (Property property : result.profile().properties().get(SkinRegistry.TEXTURES)) {
				// Unsigned is no use here: a client handed a texture value with no matching signature
				// falls back to the default skin, which is the thing this pool exists to avoid.
				if (property.value() != null && !property.value().isBlank()
					&& property.signature() != null && !property.signature().isBlank()) {
					return Optional.of(property);
				}
			}
			return Optional.empty();
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	/** @return {@code true} when this face was new. */
	private boolean add(Property texture) {
		if (texture == null || texture.value() == null || texture.value().isBlank()
			|| texture.signature() == null || texture.signature().isBlank()) {
			return false;
		}
		synchronized (this.known) {
			if (!this.known.add(texture.value())) {
				return false;
			}
		}
		this.faces.add(texture);
		return true;
	}

	private void load() {
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, FakeTabSkins::defaults);
		if (!root.has("faces") || !root.get("faces").isJsonArray()) {
			return;
		}
		for (JsonElement element : root.getAsJsonArray("faces")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject face = element.getAsJsonObject();
			if (face.has("value") && face.has("signature")) {
				add(new Property(SkinRegistry.TEXTURES,
					face.get("value").getAsString(), face.get("signature").getAsString()));
			}
		}
	}

	private void save() {
		JsonObject root = defaults();
		JsonArray array = root.getAsJsonArray("faces");
		for (Property texture : this.faces) {
			JsonObject face = new JsonObject();
			face.addProperty("value", texture.value());
			face.addProperty("signature", texture.signature());
			array.add(face);
		}
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.add("faces", new JsonArray());
		return root;
	}
}
