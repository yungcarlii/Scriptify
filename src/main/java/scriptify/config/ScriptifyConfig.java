package scriptify.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import scriptify.compat.CompatDomain;
import scriptify.compat.ConflictPolicy;

public final class ScriptifyConfig {
	public int schemaVersion = 7;
	public DeathControl deathControl = new DeathControl();
	public Kits kits = new Kits();
	public FakeTab fakeTab = new FakeTab();
	public Compat compat = new Compat();

	public void validate() {
		this.deathControl = this.deathControl == null ? new DeathControl() : this.deathControl;
		this.kits = this.kits == null ? new Kits() : this.kits;
		this.fakeTab = this.fakeTab == null ? new FakeTab() : this.fakeTab;
		this.compat = this.compat == null ? new Compat() : this.compat;
		this.compat.validate();
		this.fakeTab.count = Math.max(0, Math.min(500, this.fakeTab.count));
		// One tick minimum, and capped so a mistyped value cannot leave a player waiting a minute for
		// their kit to finish arriving.
		this.kits.animationIntervalTicks = Math.max(1, Math.min(20, this.kits.animationIntervalTicks));
	}

	/**
	 * What to do when another mod wants a surface Scriptify also wants.
	 *
	 * <p>Written back out in full after the first run, so the file lists every surface with its current
	 * setting rather than making you know the names to add.
	 */
	public static final class Compat {
		/** Look for conflicting mods at all. Turning this off leaves everything to load order. */
		public boolean enabled = true;
		/** Print what was found at startup. This is the part that saves an evening of confusion. */
		public boolean logOnStartup = true;
		/**
		 * Per-surface setting: PRIORITY, YIELD or IGNORE.
		 *
		 * <p>Keyed by surface id — nicknames, skins, tab_list, permissions, death, commands. PRIORITY
		 * everywhere by default; set a surface to YIELD to keep the other mod's behaviour for that one
		 * thing without giving up the rest of Scriptify.
		 */
		public Map<String, String> policies = new LinkedHashMap<>();
		/**
		 * Per-command exceptions, overriding the "commands" surface for one name.
		 *
		 * <p>For example {@code "nick": "YIELD"} hands /nick to a nickname mod while Scriptify keeps
		 * every other command it registers. Written without the leading slash.
		 */
		public Map<String, String> commandOverrides = new LinkedHashMap<>();
		/**
		 * Mod ids to treat as not conflicting.
		 *
		 * <p>For when you have decided a particular overlap is fine and want the warning to stop.
		 */
		public List<String> ignoredMods = new ArrayList<>();

		void validate() {
			this.policies = this.policies == null ? new LinkedHashMap<>() : this.policies;
			this.commandOverrides = this.commandOverrides == null
				? new LinkedHashMap<>() : this.commandOverrides;
			this.ignoredMods = this.ignoredMods == null ? new ArrayList<>() : this.ignoredMods;
			// Fill in anything missing and normalise what is there, so an unreadable value becomes the
			// default in the file instead of failing quietly at the point it matters.
			for (CompatDomain domain : CompatDomain.values()) {
				ConflictPolicy policy = ConflictPolicy.parse(
					this.policies.get(domain.id()), ConflictPolicy.PRIORITY);
				this.policies.put(domain.id(), policy.name());
			}
			this.commandOverrides.replaceAll((command, policy) ->
				ConflictPolicy.parse(policy, ConflictPolicy.PRIORITY).name());
		}

		public ConflictPolicy policyFor(CompatDomain domain) {
			return ConflictPolicy.parse(this.policies.get(domain.id()), ConflictPolicy.PRIORITY);
		}

		/** The setting for one command name, falling back to whatever the commands surface says. */
		public ConflictPolicy policyForCommand(String command) {
			String override = this.commandOverrides.get(command);
			return override != null
				? ConflictPolicy.parse(override, ConflictPolicy.PRIORITY)
				: policyFor(CompatDomain.COMMANDS);
		}
	}

	public static final class FakeTab {
		/** Show players in the tab list who are not connected. */
		public boolean enabled = false;
		/**
		 * Preset size: OFF, SMALL (15), MEDIUM (40), BIG (80), VERY_BIG (200) or MASSIVE (500).
		 *
		 * <p>Ignored when {@link #count} is above zero.
		 */
		public String mode = "MEDIUM";
		/** Exact number of fake players, overriding the preset. Zero means use the preset. */
		public int count = 0;
		/**
		 * Let the roster drift: the odd fake player leaving or arriving, and pings that move.
		 *
		 * <p>On by default because a tab list that is byte-for-byte identical for a whole shot is the
		 * thing an audience notices without being able to say why. Turn it off for a scene that has to
		 * match a screenshot exactly, or where a name is read aloud and must still be there later.
		 */
		public boolean churn = true;
	}

	public static final class Kits {
		/** Deal kit items out one at a time instead of filling the inventory instantly. */
		public boolean animate = true;
		/** Ticks between each item landing. 2 is brisk; raise it for a slower, more cinematic deal. */
		public int animationIntervalTicks = 2;
	}

	public static final class DeathControl {
		public boolean enabled = true;
		/** Broadcast the real death line before the fake disconnect. */
		public boolean showDeathMessage = true;
		public boolean fakeLeaveMessage = true;
		/** Let a held totem pop instead of staging the death. */
		public boolean respectTotems = true;
		/** Bots disconnect when killed instead of being parked in spectator. */
		public boolean disconnectBots = true;
		/**
		 * Scatter a copy of the player's inventory on the ground.
		 *
		 * <p>The player keeps everything — these are duplicates, purely so the scene looks like a real
		 * death from the outside.
		 */
		public boolean dropItems = true;
		/**
		 * Whether those dropped copies can be picked up.
		 *
		 * <p>On by default, because looting the "corpse" is usually the point of the shot. Note that the
		 * items are duplicates, so collecting them does duplicate gear — turn this off if a scene needs
		 * the drops to be scenery only.
		 */
		public boolean dropsCanBePickedUp = true;
		/** Play the vanilla death sound, which the cancelled death would otherwise skip. */
		public boolean playDeathSound = true;
		/** Also drop the player from everyone's tab list, so the fake disconnect holds up to scrutiny. */
		public boolean hideFromPlayerList = true;
	}

}
