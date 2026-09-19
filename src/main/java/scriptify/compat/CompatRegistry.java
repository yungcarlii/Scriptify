package scriptify.compat;

/**
 * Which surfaces Scriptify has stood down from, in a form a mixin can read.
 *
 * <p>Static and dependency-free on purpose. Mixins run inside vanilla classes and cannot reach into
 * the mod's object graph, so anything they need to consult has to sit somewhere like this — the same
 * arrangement {@code NickRegistry} and {@code RankRegistry} use.
 *
 * <p>Stored as a bitmask behind a single volatile int rather than a set. {@link #owns} is called from
 * {@code getDisplayName} and {@code permissions()}, which run often enough that a hash lookup per call
 * would be a real cost for a check that is almost always the same answer.
 *
 * <p>Everything is owned until told otherwise, so a server with no conflicting mods — and any moment
 * before detection has run — behaves exactly as it did before this existed.
 */
public final class CompatRegistry {
	private static volatile int yielded;

	private CompatRegistry() {
	}

	/** Whether Scriptify should still act on this surface. */
	public static boolean owns(CompatDomain domain) {
		return (yielded & (1 << domain.ordinal())) == 0;
	}

	static void yield(CompatDomain domain) {
		yielded |= 1 << domain.ordinal();
	}

	/** Back to owning everything. Used on reload, before detection runs again. */
	public static void clear() {
		yielded = 0;
	}

	/**
	 * Whether invented players are currently being shown in anybody's tab list.
	 *
	 * <p>Read while the command tree is being built for a client, which is why it lives beside the
	 * other mixin-readable state rather than being asked of the service.
	 */
	private static volatile boolean fakeTabActive;

	public static void setFakeTabActive(boolean active) {
		fakeTabActive = active;
	}

	public static boolean fakeTabActive() {
		return fakeTabActive;
	}
}
