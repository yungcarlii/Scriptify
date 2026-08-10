package scriptify.nick;

import java.util.Locale;
import java.util.Random;

/**
 * Generates plausible Minecraft usernames for {@code /randomnick}.
 *
 * <p>Names are built combinatorially from themed word pools in several of the styles real accounts
 * actually use — CamelCase compounds, underscore handles, leetspeak, birth-year suffixes and the
 * classic {@code xX_..._Xx} wrap. That yields well over a hundred thousand distinct results, so a
 * scene can be cast repeatedly without visible repeats.
 *
 * <p>Everything is generated locally and offline on purpose: NameMC publishes no API, and scraping it
 * once per command would be rate-limited and would stall the server thread on a network round trip.
 */
public final class NameGenerator {
	private static final String[] ADJECTIVES = {
		"Swift", "Silent", "Frozen", "Burning", "Shadow", "Crimson", "Golden", "Savage", "Toxic", "Rapid",
		"Broken", "Hollow", "Cursed", "Divine", "Feral", "Grim", "Lucky", "Midnight", "Neon", "Phantom",
		"Rogue", "Sacred", "Twisted", "Vicious", "Wicked", "Arctic", "Blazing", "Cosmic", "Dark", "Elite",
		"Fatal", "Ghost", "Hyper", "Iron", "Jaded", "Lunar", "Mystic", "Noble", "Omega", "Prime",
		"Quiet", "Royal", "Solar", "Turbo", "Ultra", "Void", "Wild", "Zero", "Alpha", "Brave",
	};

	private static final String[] NOUNS = {
		"Blade", "Wolf", "Raven", "Storm", "Fang", "Reaper", "Hunter", "Knight", "Phoenix", "Viper",
		"Dragon", "Falcon", "Ghost", "Hawk", "Jester", "King", "Lion", "Mage", "Ninja", "Oracle",
		"Panther", "Queen", "Ranger", "Samurai", "Titan", "Vandal", "Warden", "Yeti", "Bandit", "Cobra",
		"Demon", "Eagle", "Fury", "Golem", "Havoc", "Inferno", "Jaguar", "Krait", "Legend", "Monarch",
		"Nomad", "Outlaw", "Pilot", "Rebel", "Slayer", "Tiger", "Viking", "Wraith", "Zealot", "Sniper",
	};

	private static final String[] GAMER_WORDS = {
		"craft", "mine", "pvp", "clutch", "carry", "gg", "noob", "pro", "mlg", "yeet",
		"sus", "gamer", "sweat", "clip", "rush", "godly", "cracked", "goated", "fr", "peak",
	};

	private static final String[] SHORT_TAGS = {
		"xd", "yt", "ttv", "og", "mc", "hd", "lol", "uwu", "zz", "qq",
	};

	private final Random random;

	public NameGenerator() {
		this(new Random());
	}

	public NameGenerator(Random random) {
		this.random = random;
	}

	/**
	 * @return a username of at most 16 characters made only of {@code [A-Za-z0-9_]}, which is what a
	 *         profile name is allowed to contain.
	 */
	public String generate() {
		String candidate = switch (this.random.nextInt(9)) {
			case 0 -> adjective() + noun();
			case 1 -> adjective() + noun() + twoDigits();
			case 2 -> adjective() + "_" + noun();
			case 3 -> noun() + "_" + year();
			case 4 -> leet(adjective() + noun());
			case 5 -> "xX_" + noun() + "_Xx";
			case 6 -> gamerWord() + noun() + twoDigits();
			case 7 -> adjective() + noun() + tag();
			default -> "The" + adjective() + noun();
		};
		return trim(candidate);
	}

	private String adjective() {
		return ADJECTIVES[this.random.nextInt(ADJECTIVES.length)];
	}

	private String noun() {
		return NOUNS[this.random.nextInt(NOUNS.length)];
	}

	private String gamerWord() {
		String word = GAMER_WORDS[this.random.nextInt(GAMER_WORDS.length)];
		return Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	private String tag() {
		return SHORT_TAGS[this.random.nextInt(SHORT_TAGS.length)].toUpperCase(Locale.ROOT);
	}

	private String twoDigits() {
		return String.valueOf(10 + this.random.nextInt(90));
	}

	private String year() {
		return String.valueOf(2000 + this.random.nextInt(15));
	}

	private static String leet(String input) {
		return input.replace('o', '0').replace('i', '1').replace('e', '3').replace('a', '4');
	}

	/**
	 * Truncates from the right at the 16-character profile-name limit. Trailing underscores are dropped
	 * so a cut name never reads as unfinished.
	 */
	private static String trim(String name) {
		String clean = name.replaceAll("[^A-Za-z0-9_]", "");
		if (clean.length() > 16) {
			clean = clean.substring(0, 16);
		}
		while (clean.endsWith("_") && clean.length() > 1) {
			clean = clean.substring(0, clean.length() - 1);
		}
		return clean.isBlank() ? "Player" + (10 + new Random().nextInt(90)) : clean;
	}
}
