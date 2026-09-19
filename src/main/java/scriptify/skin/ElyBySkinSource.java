package scriptify.skin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * A third place to ask for a skin, when Mojang has not answered.
 *
 * <p>Ely.by is an alternative skin service with its own accounts, and it publishes them through an
 * endpoint that is deliberately shaped like Mojang's session server - same JSON, same
 * {@code properties} array, same {@code textures} entry. That is what makes it usable as a second
 * opinion rather than a second integration: {@link MojangDirect#texturesOf} parses both.
 *
 * <p><b>Why bother.</b> Two reasons, and neither is that Mojang is unreliable in normal use. The
 * first is throttling: sustained profile lookups get rate-limited, and a throttled request fails the
 * same silent way a missing account does, so a run of them looks exactly like "nobody has a skin".
 * The second is that the two services have different populations - a name that is nobody on Mojang
 * can be somebody here, which is the entire reason people use it.
 *
 * <p>Taken from SkinRestorer (MIT, Lionarius), which reaches for the same service for the same
 * reason. Over HTTPS rather than the plain HTTP it uses - the payload is public and signed either
 * way, but there is no reason to ask for it in clear, and the host serves both.
 *
 * <p><b>The signature is Ely.by's, not Mojang's.</b> It cannot be otherwise: only Mojang can sign for
 * a Mojang account. A vanilla client does not verify another player's texture, which is why this
 * works at all and why Scriptify has been able to apply unsigned NameMC textures for as long as it
 * has - but it is the reason this is asked last rather than first. A genuine Mojang answer is always
 * preferred when there is one.
 *
 * <p>Blocking. Called from the skin executor, never the server thread.
 */
public final class ElyBySkinSource {
	/**
	 * Returns 200 with a profile, or 204 with nothing at all for a name it does not know - not a 404,
	 * which is worth knowing because a status check that only allows 200 treats both correctly.
	 */
	private static final String TEXTURES = "https://skinsystem.ely.by/textures/signed/";

	private ElyBySkinSource() {
	}

	/** Looks a name up and returns its texture, or empty when this service has never heard of it. */
	public static Optional<MojangDirect.Texture> fetchTexture(String name, Logger logger) {
		try {
			String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
			return MojangDirect.texturesOf(MojangDirect.get(TEXTURES + encoded + "?unsigned=false"));
		} catch (Exception exception) {
			logger.warn("Ely.by lookup for '{}' failed as well.", name);
			return Optional.empty();
		}
	}
}
