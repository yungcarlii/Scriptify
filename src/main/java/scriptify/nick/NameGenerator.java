package scriptify.nick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Random;

/**
 * Generates plausible Minecraft usernames for {@code /randomnick}.
 *
 * <p>The shapes here were taken from a sample of real usernames on NameMC's recently-released and
 * popular-profile listings — names like {@code nitronze}, {@code doritoess}, {@code ItsDarkSteve},
 * {@code _Stanik}, {@code qRapid}, {@code crane500}, {@code sorryimbritish}, {@code STEATHH}.
 *
 * <p>The lesson from that sample is that real usernames are mostly <em>not</em> tidy CamelCase
 * compounds. They are lowercase, short, often invented syllable mashes, frequently decorated with a
 * stray underscore, a doubled letter or a number that means nothing. A generator that only produces
 * {@code AdjectiveNoun42} is recognisable at a glance however large its word lists are, because the
 * register is wrong rather than the vocabulary.
 *
 * <p>So the pools feed several registers with weights roughly matching what the sample showed, and a
 * short memory of recent rolls stops a cast clustering on one word.
 *
 * <p>Everything is generated locally and offline on purpose: NameMC publishes no API, and fetching
 * from it once per command would be rate-limited and would stall the server thread on a round trip.
 */
public final class NameGenerator {
	// ---------------------------------------------------------------- invented-word pieces

	/** Syllable openings. The clusters are what make an invented word read as pronounceable. */
	private static final String[] ONSETS = {
		"b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s", "t", "v", "w", "z",
		"br", "cr", "dr", "fr", "gr", "pr", "tr", "st", "sp", "sk", "sh", "ch", "th", "bl", "cl",
		"fl", "gl", "pl", "sl", "sn", "sm", "tw", "vr", "kr", "zr", "qu",
	};

	/**
	 * Syllable middles, overwhelmingly single vowels.
	 *
	 * <p>The vowel pairs used to have equal billing with the plain vowels, and two of them landing in
	 * one short word is what produced {@code Blaequauz}, {@code Choocliem} and {@code Craifroesh} -
	 * which read as Elvish rather than as a handle somebody typed. Real invented usernames are built
	 * from plain vowels: Sythne, Hirhon, Wasov, slofta, Durmaz, kuptrax. The pairs are kept because a
	 * few of them are what stops every name sounding identical, but they are now the exception.
	 */
	private static final String[] NUCLEI = {
		"a", "a", "a", "e", "e", "e", "i", "i", "i", "o", "o", "o", "u", "u", "y", "y",
		"ae", "ea", "ee", "ie", "oo", "ou",
	};

	/** Syllable endings, including the empty one so words do not all end in a consonant. */
	private static final String[] CODAS = {
		"", "", "", "n", "m", "r", "l", "s", "x", "z", "ck", "nd", "nt", "ng", "rd", "rk",
		"rn", "rt", "st", "sk", "sh", "ll", "ss", "zz", "ph", "lex", "nze", "rix", "dex", "sio",
	};

	// ---------------------------------------------------------------- real-word pools

	private static final String[] ADJECTIVES = {
		"Swift", "Silent", "Frozen", "Burning", "Shadow", "Crimson", "Golden", "Savage", "Toxic", "Rapid",
		"Broken", "Hollow", "Cursed", "Divine", "Feral", "Grim", "Lucky", "Midnight", "Neon", "Phantom",
		"Rogue", "Sacred", "Twisted", "Vicious", "Wicked", "Arctic", "Blazing", "Cosmic", "Dark", "Elite",
		"Fatal", "Ghost", "Hyper", "Iron", "Jaded", "Lunar", "Mystic", "Noble", "Omega", "Prime",
		"Quiet", "Royal", "Solar", "Turbo", "Ultra", "Void", "Wild", "Zero", "Alpha", "Brave",
		"Ancient", "Amber", "Astral", "Bitter", "Bloody", "Bold", "Chrome", "Cold", "Copper", "Crystal",
		"Dizzy", "Dusty", "Eager", "Ember", "Empty", "Endless", "Faded", "Fallen", "Fierce", "Flint",
		"Foggy", "Forged", "Gentle", "Gilded", "Glass", "Hazy", "Hidden", "Humble", "Hungry", "Jagged",
		"Keen", "Lazy", "Lonely", "Loud", "Marble", "Molten", "Muted", "Nimble", "Odd", "Pale",
		"Polar", "Quick", "Restless", "Rusty", "Salty", "Scarlet", "Sharp", "Silver", "Sleepy", "Smoky",
		"Solid", "Spare", "Stark", "Steady", "Stone", "Stray", "Sudden", "Tame", "Tidal", "Timber",
		"Tiny", "Velvet", "Weary", "Willow", "Winter", "Woven", "Young", "Bronze", "Cobalt", "Dusk",
	};

	private static final String[] NOUNS = {
		"Blade", "Wolf", "Raven", "Storm", "Fang", "Reaper", "Hunter", "Knight", "Phoenix", "Viper",
		"Dragon", "Falcon", "Ghost", "Hawk", "Jester", "King", "Lion", "Mage", "Ninja", "Oracle",
		"Panther", "Queen", "Ranger", "Samurai", "Titan", "Vandal", "Warden", "Yeti", "Bandit", "Cobra",
		"Demon", "Eagle", "Fury", "Golem", "Havoc", "Inferno", "Jaguar", "Legend", "Monarch", "Nomad",
		"Outlaw", "Pilot", "Rebel", "Slayer", "Tiger", "Viking", "Wraith", "Zealot", "Sniper", "Anchor",
		"Anvil", "Archer", "Ash", "Badger", "Beacon", "Bear", "Bishop", "Boulder", "Brook", "Cabin",
		"Canyon", "Cedar", "Cinder", "Comet", "Compass", "Coyote", "Crane", "Crow", "Dagger", "Dawn",
		"Delta", "Drifter", "Echo", "Fable", "Ferry", "Fisher", "Flare", "Forge", "Fossil", "Fox",
		"Gale", "Glacier", "Grove", "Harbour", "Heron", "Ivy", "Kestrel", "Lantern", "Lark", "Lynx",
		"Magpie", "Mantis", "Marsh", "Meadow", "Mercury", "Mill", "Moth", "Otter", "Owl", "Pike",
		"Quarry", "Quill", "Rapids", "Reef", "Ridge", "Rook", "Sable", "Sage", "Sailor", "Sentry",
		"Sparrow", "Spire", "Stag", "Summit", "Talon", "Tempest", "Thorn", "Torrent", "Tundra", "Wisp",
		"Banana", "Waffle", "Pickle", "Muffin", "Noodle", "Pepper", "Pigeon", "Potato", "Toaster", "Gremlin",
	};

	/**
	 * Words that turn up in real usernames, as opposed to words that turn up in fantasy novels.
	 *
	 * <p>Kept apart from {@link #NOUNS} and {@link #ADJECTIVES} deliberately. Those pools are a
	 * bestiary - Phoenix, Dragon, Warden, Viper, Boulder, Lantern, Panther - and building compounds
	 * from them produced {@code VoidBoulder}, {@code MutedPanther} and {@code SuperArcticViper}, which
	 * are RPG characters rather than people on a server. An eighty name sample from a live server has
	 * not one bare dictionary word in it, and the compounds it does have are modern: DopplerRuby,
	 * FeatherNova, CyberDienst, BioNet, CloudSp4rk. So compounds are built from these instead, and the
	 * fantasy pools are left to {@link #generateLikelyTaken()}, where the point is to guess names that
	 * already exist on Mojang and an ordinary English word is exactly what does that.
	 */
	private static final String[] MODERN_FIRST = {
		"Cyber", "Doppler", "Feather", "Bio", "Cloud", "Neon", "Turbo", "Quantum", "Vortex", "Pixel",
		"Byte", "Laser", "Sonic", "Astro", "Hyper", "Volt", "Flux", "Blitz", "Drift", "Surge",
		"Toxic", "Zen", "Apex", "Nitro", "Fusion", "Echo", "Crypt", "Glitch", "Static", "Chrome",
		"Data", "Servo", "Nano", "Omni", "Proto", "Vector", "Helix", "Orbit", "Photon", "Plasma",
	};

	private static final String[] MODERN_SECOND = {
		"Ruby", "Nova", "Net", "Spark", "Core", "Wave", "Byte", "Link", "Node", "Grid",
		"Beam", "Drive", "Loop", "Sync", "Cell", "Port", "Byte", "Dust", "Flare", "Rift",
		"Bolt", "Dash", "Edge", "Fade", "Haze", "Jolt", "Lux", "Pulse", "Rush", "Zone",
	};

	/**
	 * Real given names, the way they appear on a European server.
	 *
	 * <p>A whole category the generator had almost nothing of, and it is one of the largest on a real
	 * list: josifa67, lukicha19, celik_385, matibezgaci2137, Benienas2, LucaBnb, _Biagii, domin1kx_.
	 * Somebody's actual name with a number after it is one of the commonest usernames there is, and
	 * the old pool of fifty English-and-Scandinavian names could not produce that texture.
	 */
	private static final String[] HUMAN_NAMES = {
		"mateusz", "kacper", "dawid", "piotrek", "tomek", "bartek", "oskar", "filip", "szymon", "kuba",
		"wiktor", "marek", "michal", "pawel", "rafal", "sebastian", "damian", "patryk", "kamil", "julka",
		"luca", "matteo", "alessia", "giulia", "lorenzo", "davide", "simone", "andrea", "marco", "elisa",
		"emre", "burak", "mert", "deniz", "ahmet", "kaan", "yusuf", "berk", "onur", "selin",
		"ivan", "nikola", "milos", "stefan", "andrei", "vlad", "dmitri", "sasha", "artem", "bogdan",
		"tomas", "lukas", "martin", "patrik", "jakub", "matej", "adam", "denis", "erik", "samuel",
		"josifa", "celik", "kikna", "biagii", "hazryq", "benienas", "gigucika", "lukicha", "updose", "wambly",
	};

	/** Given names, because plenty of real accounts are just somebody's name and a number. */
	private static final String[] FIRST_NAMES = {
		"Alex", "Adam", "Amir", "Anna", "Ben", "Caleb", "Chloe", "Dani", "Dave", "Dima",
		"Eli", "Emil", "Erik", "Filip", "Finn", "Greg", "Hana", "Hugo", "Igor", "Ivan",
		"Jake", "Jonas", "Juan", "Kai", "Karol", "Kevin", "Lars", "Leo", "Liam", "Luca",
		"Maks", "Marco", "Mateo", "Milo", "Nico", "Noah", "Oscar", "Pablo", "Piotr", "Rafa",
		"Remi", "Rico", "Ryan", "Sam", "Sasha", "Sean", "Theo", "Tomas", "Viktor", "Yuki",
	};

	/** Sentence-ish openers, the {@code sorryimbritish} register. */
	private static final String[] OPENERS = {
		"its", "itz", "im", "not", "just", "only", "sorry", "iam", "why", "hey",
		"big", "lil", "mr", "mrs", "the", "your", "my", "real", "ok", "no",
	};

	private static final String[] GAMER_WORDS = {
		"craft", "mine", "pvp", "clutch", "carry", "gg", "noob", "pro", "mlg", "yeet",
		"sus", "gamer", "sweat", "clip", "rush", "godly", "cracked", "goated", "peak", "grind",
		"combo", "crit", "dash", "flick", "juke", "meta", "smurf", "tilt", "afk", "lag",
	};

	private static final String[] SHORT_TAGS = {
		"xd", "yt", "ttv", "og", "mc", "hd", "lol", "uwu", "zz", "qq",
		"gg", "tv", "ez", "hq", "fx", "vr", "np", "rp", "rn", "jr",
	};

	// ---------------------------------------------------------------- repetition guard

	/**
	 * How many recent names to remember. Comfortably more than a scene's worth of extras, so a full
	 * cast can be rolled without an exact repeat.
	 */
	private static final int NAME_MEMORY = 96;
	/**
	 * How many recent leading words to remember. Smaller than the name memory on purpose — blocking a
	 * word for too long would visibly starve the pools and push every roll towards the same tail.
	 */
	private static final int WORD_MEMORY = 20;
	/**
	 * Longest invented word to accept.
	 *
	 * <p>Eight, not ten. Every invented name on a real list is short - Sythne, Wasov, slofta, rlxxe,
	 * Gehoe - and the extra two characters were where the syllable masher stopped sounding like a
	 * name and started sounding like a spell.
	 */
	private static final int MAX_INVENTED = 8;
	/** Attempts before accepting a repeat, so an exhausted pool cannot spin forever. */
	private static final int ATTEMPTS = 24;

	private final Random random;
	private final Deque<String> recentNames = new ArrayDeque<>();
	private final Deque<String> recentLeadWords = new ArrayDeque<>();

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
		String fallback = null;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			String candidate = trim(shape());
			// Too long even after the safe trimming: roll again rather than serve half a word.
			if (candidate.length() > MAX_NAME) {
				continue;
			}
			if (fallback == null) {
				fallback = candidate;
			}
			if (isFresh(candidate)) {
				remember(candidate);
				return candidate;
			}
		}
		// Every attempt collided, which means the memory is crowding the pools. Take the first one
		// anyway rather than returning nothing, and still record it so the window moves on.
		if (fallback == null) {
			fallback = trim(capitalise(invented()) + digits());
		}
		remember(fallback);
		return fallback;
	}

	/**
	 * One name in one of the registers the NameMC sample showed.
	 *
	 * <p>Weighted by rolling out of 100 rather than picking a shape uniformly. Uniform selection was
	 * the previous flaw: the tidy CamelCase compounds are a real register but a small one, and giving
	 * them the same share as everything else is what made every roll look related.
	 */
	private String shape() {
		int roll = this.random.nextInt(100);

		// ---- invented letter strings, which is what most of a real list actually is -------------
		// Sythne, Hirhon, Wasov, kuptrax, slofta, Durmaz, DEpanu, Rioftaba, Kashellow, Motahp.
		if (roll < 16) {
			return capitalise(invented());
		}
		if (roll < 28) {
			return invented();
		}
		if (roll < 36) {
			return lower(invented()) + digits();
		}
		if (roll < 40) {
			return capitalise(invented()) + "_";
		}

		// ---- somebody's actual name, which is the other big group ------------------------------
		// josifa67, lukicha19, celik_385, matibezgaci2137, Benienas2, _Biagii.
		if (roll < 50) {
			return human() + digits();
		}
		if (roll < 54) {
			return human() + "_" + digits();
		}
		if (roll < 57) {
			return capitalise(human()) + lower(invented());
		}

		// ---- vowel-dropped handles: Blxqswt, rlxxe, Xyqt_, mystcl_, Flyiqn ---------------------
		if (roll < 65) {
			return squashed();
		}

		// ---- one digit standing in for one letter: Ch1llguy7, Niun1ek, domin1kx_ ---------------
		if (roll < 72) {
			return leetLightly(this.random.nextBoolean()
				? capitalise(invented()) + digits() : capitalise(human()) + digits());
		}

		// ---- compounds, from modern words rather than a bestiary --------------------------------
		// DopplerRuby, FeatherNova, CyberDienst, BioNet, CloudSp4rk. Twelve percent, and never a
		// single bare word on its own - the real sample has not one of those in eighty names.
		if (roll < 80) {
			return pick(MODERN_FIRST) + pick(MODERN_SECOND);
		}
		if (roll < 84) {
			return lower(pick(MODERN_FIRST)) + lower(pick(MODERN_SECOND)) + digits();
		}

		// ---- underscore joining two halves: black_blade_, purp_flame1, GJA_Winter --------------
		if (roll < 88) {
			return lower(pick(MODERN_FIRST)) + "_" + lower(this.random.nextBoolean()
				? pick(MODERN_SECOND) : invented());
		}

		// ---- gamer-culture phrases: LearnHowToMacro, NeverMutStats, ItsFishBtw ------------------
		if (roll < 92) {
			return phrase();
		}
		if (roll < 95) {
			return opener() + capitalise(human());
		}

		// ---- the long tail ----------------------------------------------------------------------
		if (roll < 97) {
			return "_" + capitalise(this.random.nextBoolean() ? invented() : human());
		}
		if (roll < 98) {
			return stretched(lower(this.random.nextBoolean() ? human() : invented()));
		}
		if (roll < 99) {
			return shortHandle(3 + this.random.nextInt(2)).toUpperCase(Locale.ROOT);
		}
		return "000" + lower(invented());
	}

	/** A real given name, as it would be typed into a username. */
	private String human() {
		return pick(HUMAN_NAMES);
	}

	/**
	 * A handle with most of its vowels taken out: {@code Blxqswt}, {@code rlxxe}, {@code Xyqt_}.
	 *
	 * <p>A whole register the generator refused to produce. {@link #invented()} rerolls anything with
	 * a consonant pile-up on the grounds that it does not read as a name - which is true of a name
	 * somebody was given and false of a handle somebody chose. Real lists are full of these.
	 */
	private String squashed() {
		StringBuilder handle = new StringBuilder();
		int length = 4 + this.random.nextInt(4);
		for (int index = 0; index < length; index++) {
			// A vowel now and then, so it stays a handle rather than becoming a keyboard smash.
			boolean vowel = index > 0 && this.random.nextInt(100) < 25;
			handle.append(vowel ? pick(NUCLEI).charAt(0) : SQUASH_CONSONANTS
				.charAt(this.random.nextInt(SQUASH_CONSONANTS.length())));
		}
		String made = handle.toString();
		if (this.random.nextInt(100) < 25) {
			made = made + this.random.nextInt(10);
		}
		return this.random.nextInt(100) < 40 ? capitalise(made) : made;
	}

	/** The letters these handles are actually built from - the awkward end of the alphabet. */
	private static final String SQUASH_CONSONANTS = "bcdfgklmnpqrstvwxyz";

	/**
	 * A short phrase run together: {@code LearnHowToMacro}, {@code NeverMutStats}.
	 *
	 * <p>Trimmed to fit rather than cut: a name sliced at sixteen characters mid-word reads as a bug,
	 * and the old code produced {@code theancientsleepy} and {@code heyrestlesscedar} doing exactly
	 * that. Short pieces are chosen up front so the result fits on its own.
	 */
	private String phrase() {
		String made = pick(PHRASE_STARTS) + pick(MODERN_FIRST) + pick(MODERN_SECOND);
		return made.length() <= 16 ? made : pick(PHRASE_STARTS) + pick(MODERN_SECOND);
	}

	private static final String[] PHRASE_STARTS = {
		"Learn", "Never", "Always", "Just", "Only", "Stop", "Dont", "Why", "How", "Get",
		"Not", "Real", "Pure", "Half", "Ultra", "Super", "Anti", "Pro", "No", "All",
	};

	/** One digit standing in for one letter, which is what real leet on a name list looks like. */
	private String leetLightly(String input) {
		char[] letters = input.toCharArray();
		for (int attempt = 0; attempt < 8; attempt++) {
			int at = this.random.nextInt(letters.length);
			char swapped = switch (Character.toLowerCase(letters[at])) {
				case 'a' -> '4';
				case 'e' -> '3';
				case 'i' -> '1';
				case 'o' -> '0';
				case 's' -> '5';
				default -> 0;
			};
			if (swapped != 0) {
				letters[at] = swapped;
				break;
			}
		}
		return new String(letters);
	}

	/** Stretches the last letter out, the {@code Adzzzzzzzzzzz} habit. */
	private String stretched(String input) {
		if (input.isEmpty()) {
			return input;
		}
		char last = input.charAt(input.length() - 1);
		int extra = 2 + this.random.nextInt(6);
		StringBuilder made = new StringBuilder(input);
		for (int index = 0; index < extra && made.length() < 16; index++) {
			made.append(last);
		}
		return made.toString();
	}

	/**
	 * A candidate for a Mojang lookup, chosen so that the accounts which come back are varied.
	 *
	 * <p><b>This is the shape that decides what a random real nickname looks like</b>, and getting it
	 * wrong is subtle: the survivors of the lookup are the output, so a shape that rarely resolves
	 * contributes nothing however good it looks here. The previous mix leaned on invented syllable
	 * words - and measured against Mojang, those resolve about <b>3%</b> of the time. Almost every
	 * name that made it through was therefore one of the two tidy compound shapes, which is exactly
	 * why every roll read as {@code SomethingSomething38}. The generator was not the problem; the
	 * filter behind it was doing the choosing.
	 *
	 * <p>So the mix is now weighted by measured hit rate against the real endpoint:
	 * <ul>
	 *   <li>a plain word on its own - <b>83%</b>, and the best of them: {@code Cloud}, {@code Maple},
	 *       {@code potato}, {@code Quartz}</li>
	 *   <li>an opener plus a word - <b>90%</b>: {@code ItsKestrel}, {@code ItsGremlin}</li>
	 *   <li>a word with a stray underscore - <b>83%</b>: {@code _Quill}, {@code _Sage}</li>
	 *   <li>four letters - <b>77%</b>: {@code Shwn}, {@code Lank}, the short handles claimed years
	 *       ago</li>
	 *   <li>a word and a couple of digits - <b>67%</b>, kept as a minority rather than the default</li>
	 *   <li>two words joined - <b>30%</b>, rarer but distinctive when it lands</li>
	 * </ul>
	 *
	 * <p>Invented words are gone from this path entirely. They remain in {@link #generate()}, which
	 * hands out a name directly and so is not filtered by anything.
	 *
	 * <p>One further thing the endpoint gives us for free: it answers with the name as it is actually
	 * registered. Ask for {@code cloud} and the reply is {@code Cloud}; ask for {@code potato} and it
	 * stays lowercase. The capitalisation is therefore real rather than something applied here, which
	 * is a good part of why the results stop looking generated.
	 *
	 * <p>No freshness check. These are candidates for a lookup, not names handed to anyone.
	 */
	public String generateLikelyTaken() {
		int roll = this.random.nextInt(100);
		String candidate;
		if (roll < 30) {
			// The richest seam: an ordinary word, in whichever case it was registered.
			candidate = lower(word());
		} else if (roll < 45) {
			candidate = opener() + capitalise(word());
		} else if (roll < 57) {
			candidate = this.random.nextBoolean()
				? "_" + capitalise(word())
				: lower(word()) + "_";
		} else if (roll < 71) {
			candidate = shortHandle(4);
		} else if (roll < 82) {
			candidate = lower(word()) + twoDigits();
		} else if (roll < 89) {
			candidate = lower(word()) + "_" + lower(word());
		} else if (roll < 94) {
			candidate = firstName() + twoDigits();
		} else if (roll < 97) {
			candidate = shortHandle(3);
		} else {
			candidate = gamerWord() + noun();
		}
		return trim(candidate);
	}

	/**
	 * A short letter handle, the kind claimed in the first year and never given up.
	 *
	 * <p>Three and four letter names are effectively all registered - measured at 100% and 77% - so
	 * these are close to a guaranteed hit, and they bring a register nothing built out of words can:
	 * {@code Shwn}, {@code TKRT}, {@code Lank}.
	 */
	private String shortHandle(int length) {
		StringBuilder handle = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			handle.append((char) ('a' + this.random.nextInt(26)));
		}
		return handle.toString();
	}

	// ---------------------------------------------------------------- pieces

	/** Two or three syllables mashed together, lowercase: {@code nitronze}, {@code ordillex}. */
	private String invented() {
		for (int attempt = 0; attempt < 4; attempt++) {
			// Two syllables almost always. Three is where they start to sprawl.
			int syllables = this.random.nextInt(100) < 88 ? 2 : 3;
			StringBuilder word = new StringBuilder();
			for (int index = 0; index < syllables; index++) {
				word.append(pick(ONSETS)).append(pick(NUCLEI));
				// A coda mid-word makes the next syllable hard to say, so usually only the last gets one.
				if (index == syllables - 1 || this.random.nextInt(100) < 20) {
					word.append(pick(CODAS));
				}
			}
			// Real invented handles are short — nitronze, akode, beuhlo. Long vowel-cluster mashes read
			// as keyboard noise rather than as a name somebody chose, so reroll them.
			if (word.length() <= MAX_INVENTED) {
				return word.toString();
			}
		}
		return pick(ONSETS) + pick(NUCLEI) + pick(ONSETS) + pick(NUCLEI);
	}

	/** Stretches a letter, the {@code teaeeee} / {@code sshaheenn} habit. */
	private String doubled(String input) {
		if (input.length() < 3) {
			return input;
		}
		int at = 1 + this.random.nextInt(input.length() - 1);
		return input.substring(0, at) + input.charAt(at - 1) + input.substring(at);
	}

	/** A real word from either pool, for the shapes that do not care which. */
	private String word() {
		return this.random.nextBoolean() ? adjective() : noun();
	}

	private String pick(String[] pool) {
		return pool[this.random.nextInt(pool.length)];
	}

	private String adjective() {
		return pick(ADJECTIVES);
	}

	private String noun() {
		return pick(NOUNS);
	}

	private String firstName() {
		return pick(FIRST_NAMES);
	}

	private String opener() {
		return pick(OPENERS);
	}

	private String letter() {
		return String.valueOf((char) ('a' + this.random.nextInt(26)));
	}

	private String gamerWord() {
		return capitalise(pick(GAMER_WORDS));
	}

	private String tag() {
		return pick(SHORT_TAGS);
	}

	/** One to four digits — real names use far more than the two-digit birth year. */
	private String digits() {
		// Two, three and four digit runs all appear on a real list, and the long ones are what make it
		// look like a real one - matibezgaci2137, Lowkeyeli2285, conquest2867. Only two ever appeared
		// before, which is a large part of why every name read as the same name with a number on it.
		int roll = this.random.nextInt(100);
		int length = roll < 12 ? 1 : roll < 50 ? 2 : roll < 80 ? 3 : 4;
		StringBuilder made = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			made.append(this.random.nextInt(10));
		}
		return made.toString();
	}

	private String twoDigits() {
		return String.valueOf(10 + this.random.nextInt(90));
	}

	private static String capitalise(String input) {
		return input.isEmpty() ? input : Character.toUpperCase(input.charAt(0)) + input.substring(1);
	}

	private static String lower(String input) {
		return input.toLowerCase(Locale.ROOT);
	}

	private static String leet(String input) {
		return input.replace('o', '0').replace('i', '1').replace('e', '3').replace('a', '4');
	}

	// ---------------------------------------------------------------- repetition guard

	/** Rejects an exact repeat, and any name opening on a word used in the last few rolls. */
	private boolean isFresh(String candidate) {
		return !this.recentNames.contains(candidate) && !this.recentLeadWords.contains(leadWord(candidate));
	}

	private void remember(String name) {
		this.recentNames.addLast(name);
		if (this.recentNames.size() > NAME_MEMORY) {
			this.recentNames.removeFirst();
		}
		this.recentLeadWords.addLast(leadWord(name));
		if (this.recentLeadWords.size() > WORD_MEMORY) {
			this.recentLeadWords.removeFirst();
		}
	}

	/**
	 * The first word of a name that carries any meaning, lowercased.
	 *
	 * <p>Splits on underscores, capitals and digits, then skips the fixed decoration some shapes open
	 * with. Keying on "its" or "x" would guard the ornament instead of the word a reader actually
	 * notices repeating.
	 */
	private static String leadWord(String name) {
		for (String token : name.split("_|(?=[A-Z])|(?<=[a-zA-Z])(?=[0-9])")) {
			String candidate = token.toLowerCase(Locale.ROOT);
			if (candidate.length() > 2 && !isOpener(candidate)) {
				return candidate;
			}
		}
		return name.toLowerCase(Locale.ROOT);
	}

	private static boolean isOpener(String candidate) {
		for (String opener : OPENERS) {
			if (opener.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Truncates from the right at the 16-character profile-name limit. Trailing underscores are kept
	 * only when they were the point of the shape, and a cut never leaves a dangling one.
	 */
	private static String trim(String name) {
		String clean = name.replaceAll("[^A-Za-z0-9_]", "");
		if (clean.isEmpty()) {
			return "Player";
		}
		// Sixteen characters is the hard limit a profile name has, and a name chopped at it mid-word
		// reads as broken rather than as somebody's handle - theancientsleepy, heyrestlesscedar. Only
		// trailing digits and underscores are safe to shorten, so anything still too long after that
		// is thrown away by the caller rather than served cut in half.
		while (clean.length() > MAX_NAME
			&& (Character.isDigit(clean.charAt(clean.length() - 1))
				|| clean.charAt(clean.length() - 1) == '_')) {
			clean = clean.substring(0, clean.length() - 1);
		}
		return clean;
	}

	/** A profile name may be at most this long. */
	private static final int MAX_NAME = 16;
}
