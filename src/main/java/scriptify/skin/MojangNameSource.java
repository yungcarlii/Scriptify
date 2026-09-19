package scriptify.skin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.nick.NameGenerator;

/**
 * A pool of real Minecraft accounts, verified against Mojang.
 *
 * <p>Mojang's API has no "random profile" endpoint — it only answers lookups by name or id. What it
 * does have is a <em>bulk</em> name lookup, which authlib exposes as
 * {@link com.mojang.authlib.GameProfileRepository#findProfilesByNames}. Handing it a batch of invented
 * names and keeping whichever ones come back turns a lookup-only API into a source of random real
 * players: the ones that resolve are, by definition, accounts that exist.
 *
 * <p>That is what makes both random nicknames and random skins work off first-party data — the names
 * are genuine usernames, and their skins can be fetched signed.
 */
public final class MojangNameSource {
	/** Mojang's bulk endpoint takes ten names per request. */
	private static final int BATCH_SIZE = 10;
	/** Give up after this many batches so a bad run cannot spin forever. */
	private static final int MAX_BATCHES = 6;
	/** Refill once the pool runs low rather than waiting for it to empty. */
	private static final int LOW_WATER_MARK = 3;
	/** Keep asking until the pool holds this many, so draws have something to vary between. */
	private static final int TARGET_POOL = 12;
	/**
	 * How many recently handed-out names to refuse to hand out again.
	 *
	 * <p>The pool is refilled from a fresh batch of candidates every time it runs low, and nothing
	 * stopped the same popular short name resolving batch after batch - so a cast of extras could
	 * still end up with two of the same person. Comfortably larger than a scene's worth.
	 */
	private static final int RECENT_MEMORY = 64;

	private final MinecraftServer server;
	private final Logger logger;
	private final NameGenerator generator = new NameGenerator();
	private final Random random = new Random();
	private final List<Account> pool = new CopyOnWriteArrayList<>();
	private final java.util.Deque<String> recent = new java.util.ArrayDeque<>();

	public MojangNameSource(MinecraftServer server, Logger logger) {
		this.server = server;
		this.logger = logger;
	}

	/** A real account: a name that resolved, and the id it resolved to. */
	public record Account(String name, UUID id) {
	}

	/**
	 * A random verified account, refilling the pool first when it is running low.
	 *
	 * <p>Blocking: call this off the server thread.
	 */
	public Optional<Account> random() {
		if (this.pool.size() <= LOW_WATER_MARK) {
			refill();
		}
		if (this.pool.isEmpty()) {
			return Optional.empty();
		}
		// Taken rather than peeked, so repeated calls do not keep returning the same account.
		Account account = null;
		for (int attempt = 0; attempt < this.pool.size(); attempt++) {
			Account candidate = this.pool.remove(this.random.nextInt(this.pool.size()));
			if (!isRecent(candidate.name())) {
				account = candidate;
				break;
			}
			// Seen lately: drop it and try another rather than handing out a repeat.
			if (this.pool.isEmpty()) {
				account = candidate;
				break;
			}
		}
		if (account == null) {
			return Optional.empty();
		}
		remember(account.name());
		return Optional.of(account);
	}

	private boolean isRecent(String name) {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		synchronized (this.recent) {
			return this.recent.contains(key);
		}
	}

	private void remember(String name) {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		synchronized (this.recent) {
			this.recent.addLast(key);
			while (this.recent.size() > RECENT_MEMORY) {
				this.recent.removeFirst();
			}
		}
	}

	/**
	 * Generates candidate names and keeps the ones Mojang recognises.
	 *
	 * <p>Most invented names do not exist, so this asks in batches and stops as soon as it has some
	 * hits rather than trying to fill a quota.
	 */
	private void refill() {
		try {
			var repository = this.server.services().profileRepository();
			for (int batch = 0; batch < MAX_BATCHES; batch++) {
				String[] candidates = new String[BATCH_SIZE];
				for (int index = 0; index < BATCH_SIZE; index++) {
					candidates[index] = this.generator.generateLikelyTaken();
				}
				List<Account> found = new ArrayList<>();
				repository.findProfilesByNames(candidates, new com.mojang.authlib.ProfileLookupCallback() {
					@Override
					public void onProfileLookupSucceeded(String name, UUID id) {
						found.add(new Account(name, id));
					}

					@Override
					public void onProfileLookupFailed(String name, Exception exception) {
						// Expected for most candidates: the name simply is not registered.
					}
				});
				for (Account account : found) {
					if (!isRecent(account.name()) && this.pool.stream()
						.noneMatch(existing -> existing.name().equalsIgnoreCase(account.name()))) {
						this.pool.add(account);
					}
				}
				// Keep going until the pool has some depth. Stopping at the first hit left one or two
				// accounts in it, which drained immediately and refilled from the same handful of common
				// words — the reason repeated rolls kept landing on the same names.
				if (this.pool.size() >= TARGET_POOL) {
					return;
				}
			}
			this.logger.warn("Mojang lookups returned no existing accounts after {} batches.", MAX_BATCHES);
		} catch (Exception exception) {
			this.logger.warn("Could not reach Mojang to find random accounts.", exception);
		}
	}

	public int cachedCount() {
		return this.pool.size();
	}
}
