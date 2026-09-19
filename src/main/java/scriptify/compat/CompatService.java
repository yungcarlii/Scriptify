package scriptify.compat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import scriptify.config.ScriptifyConfig;

/**
 * Works out which of Scriptify's surfaces another installed mod also wants, and settles who gets them.
 *
 * <p>Detection runs once at startup. Mods cannot be added to a running server, so re-checking would
 * cost something and find nothing.
 *
 * <p>The reason this exists at all is that the failure it prevents is invisible. Two mods writing the
 * same display name do not error; one of them simply stops appearing to work, with no message anywhere
 * saying why, and which one loses depends on load order. A line at startup naming both mods and the
 * config key that decides between them turns that into a thirty-second fix.
 */
public final class CompatService {
	/** A conflicting mod that is actually installed. */
	public record Detected(String modId, String name, KnownMods.Kind kind, Set<CompatDomain> domains,
			String note) {
	}

	private final List<Detected> detected = new ArrayList<>();
	private final Map<CompatDomain, ConflictPolicy> policies = new EnumMap<>(CompatDomain.class);
	private boolean enabled = true;

	public List<Detected> detected() {
		return List.copyOf(this.detected);
	}

	public ConflictPolicy policy(CompatDomain domain) {
		return this.policies.getOrDefault(domain, ConflictPolicy.PRIORITY);
	}

	public boolean enabled() {
		return this.enabled;
	}

	/** Every mod contesting one surface. */
	public List<Detected> contesting(CompatDomain domain) {
		return this.detected.stream().filter(mod -> mod.domains().contains(domain)).toList();
	}

	public void detect(ScriptifyConfig.Compat settings, Logger logger) {
		this.detected.clear();
		this.policies.clear();
		CompatRegistry.clear();
		this.enabled = settings != null && settings.enabled;
		if (!this.enabled) {
			logger.info("Scriptify mod-conflict detection is off; overlapping mods resolve by load order.");
			return;
		}

		for (CompatDomain domain : CompatDomain.values()) {
			this.policies.put(domain, settings.policyFor(domain));
		}

		Set<String> ignored = new LinkedHashSet<>();
		for (String id : settings.ignoredMods) {
			ignored.add(id.trim().toLowerCase(Locale.ROOT));
		}

		FabricLoader loader = FabricLoader.getInstance();
		for (KnownMods.Known known : KnownMods.all()) {
			for (String modId : known.modIds()) {
				if (!loader.isModLoaded(modId) || ignored.contains(modId.toLowerCase(Locale.ROOT))) {
					continue;
				}
				this.detected.add(new Detected(modId, known.name(), known.kind(), known.domains(),
					known.note()));
				break;
			}
		}

		// Standing down happens per surface, not per mod: yielding nicknames to a nickname mod leaves
		// Scriptify's skins, kits and ranks exactly as they were.
		for (CompatDomain domain : CompatDomain.values()) {
			if (this.policies.get(domain) == ConflictPolicy.YIELD && !contesting(domain).isEmpty()) {
				CompatRegistry.yield(domain);
			}
		}

		if (settings.logOnStartup) {
			log(logger);
		}
	}

	/**
	 * Says what was found, at the volume it deserves.
	 *
	 * <p>Contested surfaces warn, because somebody has to choose and until they do one of the two mods
	 * is quietly not working. Notes go out at info: nothing is broken, and warning about a mod that is
	 * behaving would teach admins that Scriptify's warnings are noise.
	 */
	private void log(Logger logger) {
		List<Detected> contested = this.detected.stream()
			.filter(mod -> mod.kind() == KnownMods.Kind.CONTESTED).toList();
		List<Detected> notes = this.detected.stream()
			.filter(mod -> mod.kind() == KnownMods.Kind.NOTED).toList();

		if (contested.isEmpty()) {
			logger.info("Scriptify found no conflicting mods.");
		} else {
			logger.warn("Scriptify found {} mod(s) overlapping with it:", contested.size());
			for (Detected mod : contested) {
				for (CompatDomain domain : mod.domains()) {
					ConflictPolicy policy = policy(domain);
					logger.warn("  {} wants {} — {}. {}",
						mod.name(), domain.id(), verdict(policy), mod.note());
				}
			}
			logger.warn("Change any of these with compat.policies in the Scriptify config: "
				+ "PRIORITY keeps Scriptify's behaviour, YIELD leaves that one thing to the other mod.");
		}

		for (Detected mod : notes) {
			logger.info("Scriptify alongside {}: {}", mod.name(), mod.note());
		}
	}

	private static String verdict(ConflictPolicy policy) {
		return switch (policy) {
			case PRIORITY -> "Scriptify is taking it";
			case YIELD -> "Scriptify has stood down";
			case IGNORE -> "both are running, so load order decides";
		};
	}
}
