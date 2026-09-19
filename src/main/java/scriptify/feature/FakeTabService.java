package scriptify.feature;

import com.google.common.collect.LinkedHashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import scriptify.api.ScriptifyContext;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
import scriptify.mixin.PlayerInfoPacketAccessor;
import scriptify.nick.NameGenerator;
import scriptify.skin.SkinRegistry;

/**
 * Fills the tab list with players who are not there, so a small server reads as a busy one.
 *
 * <p>For establishing shots: a server that is obviously empty undercuts a scene before anyone speaks.
 *
 * <p>Two things stop it reading as a screenshot rather than a server. Every entry wears a real skin
 * (see {@link FakeTabSkins}), because a column of identical Steves is worse than no padding at all.
 * And the roster <em>moves</em>: people leave, people arrive, pings wander. A list that is byte-for-byte
 * identical for the whole shot is the thing an audience notices without being able to say why.
 *
 * <p>How much it moves is scaled to how much of it you can see. A tab list is taken in whole rather
 * than read row by row, so what has to stay constant is the share of it that changes, not the number
 * of rows: five hundred players turn over about ten rows a second, forty turn over one row every
 * fifteen seconds. Both come to roughly the same thing on screen.
 *
 * <p>That is the one place this costs anything. A turnover carries whole profiles, skin included, so
 * five hundred players is on the order of ten kilobytes a second to each person actually connected -
 * fine for the handful of real players a scene has, and worth knowing before setting five hundred on
 * a server with a hundred genuine ones. Everything else is free: the roster is generated once when
 * the size changes, the add-packet is built once and shared, and a moved latency bar is a uuid and a
 * number.
 *
 * <p>Purely a display: these entries exist only in packets. Nothing is added to the player list, so
 * selectors, commands and the server's own player count are untouched.
 *
 * <p>All of this runs on the server thread, which is why the roster can be a plain mutable list.
 */
public final class FakeTabService {
	/** The preset sizes, smallest first. */
	public enum Size {
		OFF(0),
		SMALL(15),
		MEDIUM(40),
		BIG(80),
		VERY_BIG(200),
		MASSIVE(500);

		private final int count;

		Size(int count) {
			this.count = count;
		}

		public int count() {
			return this.count;
		}

		/** A label a menu can show without shouting. */
		public String label() {
			String lower = name().toLowerCase(Locale.ROOT).replace('_', ' ');
			return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
		}

		public static Size parse(String name) {
			for (Size size : values()) {
				if (size.name().equalsIgnoreCase(name)) {
					return size;
				}
			}
			return OFF;
		}
	}

	/**
	 * Hard ceiling on the roster.
	 *
	 * <p>The add-packet is sent in one piece, and a client that cannot decode it disconnects. Five
	 * hundred entries is comfortably inside the limit; leaving the number open to a config typo is not
	 * worth the failure mode.
	 */
	private static final int MAX_FAKES = 500;

	/** Attempts per slot before accepting a repeat, so an exhausted generator cannot spin forever. */
	private static final int NAME_ATTEMPTS = 12;

	/**
	 * Roughly how many ticks of quiet one fake player is worth.
	 *
	 * <p>Divided by the roster size to get the gap between churn events. Five hundred players is an
	 * event about once a second; forty is one every fifteen seconds.
	 */
	private static final int CHURN_TICK_BUDGET = 12000;

	/**
	 * Floor and ceiling on that gap.
	 *
	 * <p>The floor used to be ten seconds, which quietly threw the scaling away: everything above about
	 * a hundred and twenty players hit it, so two hundred and five hundred churned at exactly the same
	 * speed. It is now low enough that the division is what decides.
	 */
	private static final int MIN_CHURN_INTERVAL = 20;
	private static final int MAX_CHURN_INTERVAL = 2400;

	/**
	 * How many entries turn over in one event: one per this many players.
	 *
	 * <p>This is the number the first two versions got wrong, and rate was never going to fix it. An
	 * event moved exactly one row no matter how long the list was, so at five hundred it changed two
	 * tenths of one per cent of what somebody is looking at - and a tab list is looked at whole, not
	 * scanned row by row. Statistically that was a real server's turnover; on screen it was a
	 * photograph. What has to stay roughly constant is the <em>fraction</em> of the list that moves,
	 * not the count, so the batch scales too and the two multiply: five hundred turns over ten rows a
	 * second, forty turns over one row every fifteen.
	 *
	 * <p>Each turnover is a swap - the same number out and in - so the list churns hard while the
	 * population stays where it was asked to be. The wandering is a separate, much smaller thing.
	 */
	private static final int PLAYERS_PER_TURNOVER = 50;

	/** Ceiling on that batch, so no single event reads as a server-wide kick. */
	private static final int MAX_TURNOVER = 16;

	/**
	 * How far the population may wander from the number that was asked for, as a fraction of it.
	 *
	 * <p>Small on purpose, and separate from turnover. Somebody who sets forty wants a server that
	 * looks like forty; they do not want it to have quietly become thirty-one by the time the scene is
	 * shot. Rows changing is what should be visible, not the count moving.
	 */
	private static final float DRIFT_FRACTION = 0.06f;

	/** Absolute bounds on that drift: always at least one, never a visible exodus. */
	private static final int MIN_DRIFT = 1;
	private static final int MAX_DRIFT = 12;

	/**
	 * Latency bars, on their own rhythm.
	 *
	 * <p>Split out from churn because it is the cheapest movement available and the most widely
	 * visible: no roster change, no profiles, just a uuid and a number per entry. Vanilla refreshes
	 * every player's ping every thirty seconds in one go; a tenth of the list every three seconds
	 * comes to the same rate spread out, which reads as bars twitching rather than the whole column
	 * jumping at once.
	 */
	private static final int PING_INTERVAL = 60;
	private static final int PING_FRACTION = 10;
	private static final int MIN_PING_ENTRIES = 3;

	private final MinecraftServer server;
	private final NameGenerator generator = new NameGenerator();
	private final FakeTabSkins skins;
	private final Random random = new Random();

	private final List<GameProfile> roster = new ArrayList<>();
	/** The number that was asked for. The population hovers near it rather than sitting on it. */
	private int target;
	/** Built lazily and reused for every recipient; dropped whenever the roster changes. */
	private ClientboundPlayerInfoUpdatePacket addPacket;
	/** Ticks until the next churn event. */
	private int churnCountdown;
	/** Ticks until the next batch of latency bars moves. */
	private int pingCountdown = PING_INTERVAL;
	/** The last value handed to {@link CompatRegistry}, so a change in it can be acted on. */
	private boolean publishedActive;

	public FakeTabService(ScriptifyContext context) {
		this.server = context.server();
		this.skins = new FakeTabSkins(context);
	}

	public int size() {
		return this.roster.size();
	}

	/** How many distinct faces are available to hand out. Zero until the pool has warmed up. */
	public int faceCount() {
		return this.skins.size();
	}

	/**
	 * How many entries are actually wearing one.
	 *
	 * <p>Worth reporting separately from the pool size: the two differ only while a cold pool is still
	 * filling, which is exactly the moment somebody wonders why the tab list is full of Steves.
	 */
	public int facesWorn() {
		int worn = 0;
		for (GameProfile profile : this.roster) {
			if (profile.properties().containsKey(SkinRegistry.TEXTURES)) {
				worn++;
			}
		}
		return worn;
	}

	public boolean isActive() {
		return !this.roster.isEmpty() && !suppressed();
	}

	/**
	 * Whether a tab list mod has been given the tab list instead.
	 *
	 * <p>The roster is still built and still remembered, so turning the conflict setting back to
	 * PRIORITY brings the fake players back without reconfiguring anything.
	 */
	public boolean suppressed() {
		return !CompatRegistry.owns(CompatDomain.TAB_LIST);
	}

	/**
	 * Rebuilds the roster at a new size and shows it to everyone online.
	 *
	 * @param count how many fake players to show; zero clears them.
	 */
	public void setCount(int count) {
		this.target = Math.max(0, Math.min(MAX_FAKES, count));
		List<UUID> previous = new ArrayList<>(this.roster.size());
		for (GameProfile profile : this.roster) {
			previous.add(profile.id());
		}

		this.roster.clear();
		Set<String> taken = takenNames();
		for (int index = 0; index < this.target; index++) {
			String name = uniqueName(taken);
			if (name == null) {
				continue;
			}
			this.roster.add(profileFor(name));
		}
		this.addPacket = null;
		this.churnCountdown = nextChurnInterval();
		publishActive();

		boolean suppressed = suppressed();
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			if (!previous.isEmpty()) {
				// Removals are sent even while yielded, so standing down clears what was already shown
				// rather than stranding entries a tab list mod will not know to take away.
				player.connection.send(new ClientboundPlayerInfoRemovePacket(previous));
			}
			if (!suppressed && addPacket() != null) {
				player.connection.send(addPacket());
			}
		}

		if (!this.roster.isEmpty()) {
			this.skins.warm(this.roster.size(), this::repaintRoster);
		}
	}

	/**
	 * Says whether invented players are on show, and resends the command tree when that answer changes.
	 *
	 * <p>The resend is the whole point. A client does not ask the server who is online for a
	 * player-shaped argument - it answers from the player list it holds, which is exactly where the
	 * invented names live. {@link scriptify.mixin.CommandSuggestionMixin} deals with that by marking
	 * those arguments server-answered as the tree is built, but <b>the tree is built once, when the
	 * player joins</b>. Flipping this flag afterwards changed nothing for anybody already connected:
	 * switch the roster on mid-scene and everybody online kept a tree that still answered locally, so
	 * {@code /tp } went on offering forty people who are not there until they reconnected. Switching it
	 * off had the mirror problem - they kept asking the server about every player argument for the rest
	 * of the session, which on a remote server is a visible pause before the suggestion box appears.
	 *
	 * <p>Only on a change, not on every call, because changing the size from forty to eighty does not
	 * change how suggestions work and is not worth a command tree each. Bots are skipped: there is
	 * nothing on the other end of that connection to draw a suggestion box.
	 */
	private void publishActive() {
		boolean active = isActive();
		CompatRegistry.setFakeTabActive(active);
		if (active == this.publishedActive) {
			return;
		}
		this.publishedActive = active;
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			if (scriptify.access.FakePlayers.isFake(player)) {
				continue;
			}
			this.server.getCommands().sendCommands(player);
		}
	}

	/**
	 * Shows the current roster to one player.
	 *
	 * <p>Called on join, and again after a player refresh - anything that resends the player list is a
	 * chance for the client to end up without them.
	 */
	public void sendTo(ServerPlayer player) {
		if (!suppressed() && addPacket() != null) {
			player.connection.send(addPacket());
		}
	}

	/**
	 * One tick of drift.
	 *
	 * <p>Cheap enough to call unconditionally: almost every tick this is two decrements and a return.
	 *
	 * <p>Two rhythms rather than one. Pings move on their own clock because they are nearly free and
	 * should be near-constant; entries turning over is the expensive one and is paced by the roster.
	 */
	public void tick() {
		if (this.roster.isEmpty()) {
			return;
		}
		boolean churnDue = --this.churnCountdown <= 0;
		boolean pingDue = --this.pingCountdown <= 0;
		if (!churnDue && !pingDue) {
			return;
		}
		// Rescheduled before the checks below, so turning churn back on does not fire an event instantly.
		if (churnDue) {
			this.churnCountdown = nextChurnInterval();
		}
		if (pingDue) {
			this.pingCountdown = PING_INTERVAL;
		}
		if (suppressed() || !churnEnabled()) {
			return;
		}
		if (churnDue) {
			churnOnce();
		}
		if (pingDue) {
			driftPings();
		}
	}

	private boolean churnEnabled() {
		var config = scriptify.runtime.ScriptifyRuntime.config();
		return config == null || config.get().fakeTab.churn;
	}

	/**
	 * A batch of people leave and a batch arrive, in one packet each.
	 *
	 * <p>One packet each rather than one per person: a turnover of sixteen sent individually is
	 * thirty-two packets that the client applies over as many frames, which arrives as a stutter
	 * instead of an event. Batched, the list simply looks different the next time it is drawn.
	 *
	 * <p>The two counts differ by at most one, and that one is the whole of the population drift. The
	 * rest is a swap, which is what lets the list churn hard without the number at the top wandering.
	 */
	private void churnOnce() {
		int turnover = turnoverSize();
		int leaving = Math.min(turnover, this.roster.size() - 1);
		if (leaving <= 0) {
			return;
		}
		List<UUID> gone = new ArrayList<>(leaving);
		for (int index = 0; index < leaving; index++) {
			gone.add(this.roster.remove(this.random.nextInt(this.roster.size())).id());
		}

		int room = MAX_FAKES - this.roster.size();
		int arriving = Math.max(0, Math.min(room, turnover + netDrift()));
		Set<String> taken = takenNames();
		List<GameProfile> arrived = new ArrayList<>(arriving);
		for (int index = 0; index < arriving; index++) {
			String name = uniqueName(taken);
			if (name == null) {
				break;
			}
			GameProfile profile = profileFor(name);
			this.roster.add(profile);
			arrived.add(profile);
		}

		this.addPacket = null;
		broadcast(new ClientboundPlayerInfoRemovePacket(gone));
		if (!arrived.isEmpty()) {
			broadcast(buildAddPacket(arrived));
		}
	}

	/** How many entries one event turns over, scaled so a long list changes visibly rather than by one. */
	private int turnoverSize() {
		return Math.max(1, Math.min(MAX_TURNOVER, this.roster.size() / PLAYERS_PER_TURNOVER));
	}

	/**
	 * Whether this event should leave the roster one bigger, one smaller, or the same size.
	 *
	 * <p>Leaning back towards the middle, so the population wanders around the number that was asked
	 * for instead of taking a random walk away from it.
	 */
	private int netDrift() {
		int drift = drift();
		int population = this.roster.size();
		if (population > this.target + drift) {
			return -1;
		}
		if (population < this.target - drift) {
			return 1;
		}
		double upwards = 0.5 + (this.target - population) * 0.5 / (drift + 1.0);
		return this.random.nextDouble() < upwards ? 1 : -1;
	}

	/**
	 * Moves a slice of the latency bars.
	 *
	 * <p>The cheapest thing on this list and the one that sells it best: latency bars on a real server
	 * twitch constantly, and a tab list where every bar has been frozen at the same height since the
	 * scene started is uncanny in a way nobody can name. A tenth of the list at a time, so on a long
	 * one there is always something moving somewhere in view.
	 */
	private void driftPings() {
		int howMany = Math.min(this.roster.size(),
			Math.max(MIN_PING_ENTRIES, this.roster.size() / PING_FRACTION));
		Set<Integer> picked = new HashSet<>();
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(howMany);
		while (picked.size() < howMany) {
			int index = this.random.nextInt(this.roster.size());
			if (!picked.add(index)) {
				continue;
			}
			entries.add(entryFor(this.roster.get(index)));
		}
		broadcast(packetOf(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), entries));
	}

	/**
	 * Deals every entry a face from the pool as it now stands.
	 *
	 * <p>A skin only travels on ADD_PLAYER, so an entry cannot be repainted in place - it has to be
	 * removed and sent again. That is a blink in anyone's tab list, which is why this is not something
	 * churn does: it runs at most twice, both times in the first minute after the fake tab is switched
	 * on for the very first time on an install, while the skin pool is filling. From the second time
	 * onwards the pool comes off disk already full and the roster is built wearing faces.
	 *
	 * <p>Ids and names are kept. The same people are still there; they have just been drawn properly.
	 */
	private void repaintRoster() {
		if (this.roster.isEmpty()) {
			return;
		}
		List<UUID> ids = new ArrayList<>(this.roster.size());
		for (int index = 0; index < this.roster.size(); index++) {
			GameProfile profile = this.roster.get(index);
			GameProfile replacement = profileFor(profile.id(), profile.name());
			this.roster.set(index, replacement);
			ids.add(replacement.id());
		}
		this.addPacket = null;
		if (suppressed()) {
			return;
		}
		ClientboundPlayerInfoRemovePacket removals = new ClientboundPlayerInfoRemovePacket(ids);
		ClientboundPlayerInfoUpdatePacket additions = buildAddPacket(this.roster);
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			player.connection.send(removals);
			player.connection.send(additions);
		}
	}

	/**
	 * Names already spoken for.
	 *
	 * <p>Real players are in here as well as fakes. A fake wearing a connected player's name is the
	 * one collision anybody notices, because that person is standing right there.
	 */
	private Set<String> takenNames() {
		Set<String> taken = new HashSet<>();
		for (GameProfile profile : this.roster) {
			taken.add(profile.name().toLowerCase(Locale.ROOT));
		}
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			taken.add(player.getGameProfile().name().toLowerCase(Locale.ROOT));
		}
		return taken;
	}

	/**
	 * A name nothing else is using, or null when the generator will not produce one.
	 *
	 * <p>The generator remembers its last ninety-odd names, which is not enough for a roster of
	 * several hundred - past that it starts handing back names it has already given, and two identical
	 * entries side by side in the tab list is the one thing a real one never shows. Checked here
	 * rather than by widening that memory, because this is the only place a whole population is drawn
	 * at once and the only place the repeat is visible.
	 */
	private String uniqueName(Set<String> taken) {
		for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
			String candidate = this.generator.generate();
			if (taken.add(candidate.toLowerCase(Locale.ROOT))) {
				return candidate;
			}
		}
		return null;
	}

	private GameProfile profileFor(String name) {
		// A random UUID, not one derived from the name: a real account's id would make the entry
		// resolve to a real person, skin and all, which is somebody else's face in your tab list.
		return profileFor(UUID.randomUUID(), name);
	}

	private GameProfile profileFor(UUID id, String name) {
		LinkedHashMultimap<String, Property> properties = LinkedHashMultimap.create();
		this.skins.draw().ifPresent(texture -> properties.put(SkinRegistry.TEXTURES, texture));
		return new GameProfile(id, name, new PropertyMap(properties));
	}

	/** Gap until the next event, scaled to the roster and then jittered so it is not a metronome. */
	private int nextChurnInterval() {
		int base = Math.max(MIN_CHURN_INTERVAL,
			Math.min(MAX_CHURN_INTERVAL, CHURN_TICK_BUDGET / Math.max(4, this.target)));
		return base / 2 + this.random.nextInt(base + 1);
	}

	private int drift() {
		return Math.max(MIN_DRIFT, Math.min(MAX_DRIFT, Math.round(this.target * DRIFT_FRACTION)));
	}

	private void broadcast(Packet<?> packet) {
		if (suppressed()) {
			return;
		}
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			player.connection.send(packet);
		}
	}

	private ClientboundPlayerInfoUpdatePacket addPacket() {
		if (this.addPacket == null && !this.roster.isEmpty()) {
			this.addPacket = buildAddPacket(this.roster);
		}
		return this.addPacket;
	}

	private ClientboundPlayerInfoUpdatePacket.Entry entryFor(GameProfile profile) {
		return new ClientboundPlayerInfoUpdatePacket.Entry(
			profile.id(),
			profile,
			true,
			// Varied ping, because a column of identical latency bars is the giveaway.
			20 + this.random.nextInt(180),
			GameType.SURVIVAL,
			null,
			true,
			0,
			null);
	}

	private ClientboundPlayerInfoUpdatePacket buildAddPacket(List<GameProfile> profiles) {
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(profiles.size());
		for (GameProfile profile : profiles) {
			entries.add(entryFor(profile));
		}
		return packetOf(EnumSet.of(
			ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), entries);
	}

	/**
	 * Builds a player-info packet with entries the constructor will not take.
	 *
	 * <p>The public constructor derives its entries from real {@code ServerPlayer}s, and there are no
	 * real players here, so the list is written in afterwards through an accessor.
	 */
	private ClientboundPlayerInfoUpdatePacket packetOf(
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
		ClientboundPlayerInfoUpdatePacket packet =
			new ClientboundPlayerInfoUpdatePacket(actions, List.of());
		((PlayerInfoPacketAccessor) packet).scriptify$setEntries(entries);
		return packet;
	}
}
