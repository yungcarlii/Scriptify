package scriptify.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Safe JSON persistence for files that are updated while the server is running.
 *
 * <p>The data is first written to a unique sibling temporary file and then renamed into place. A
 * server crash or a full disk can therefore leave an old, valid file behind, rather than a
 * half-written replacement. Filesystems that do not support atomic moves still get a normal
 * replacement as a fallback.</p>
 */
public final class JsonFile {
	private JsonFile() {
	}


	/**
	 * How many times to retry the rename before giving up, and how long to wait between tries.
	 *
	 * <p>For Windows. A rename onto an existing file fails there with {@code AccessDeniedException}
	 * while anything else holds a handle on the target, and on a normal desktop something routinely
	 * does for a few milliseconds at a time - a virus scanner reading the file that was just closed,
	 * or the search indexer. Three tries a few milliseconds apart clears it; waiting longer than that
	 * would be the server thread paying for somebody's antivirus.</p>
	 */
	private static final int MOVE_ATTEMPTS = 3;

	private static final long MOVE_RETRY_MS = 20L;

	/**
	 * Renames the finished temporary file over the real one.
	 *
	 * <p>Atomically when the filesystem allows it, because that is what makes a crash during a save
	 * leave the previous file intact rather than a truncated one.</p>
	 *
	 * <p>The fallback used to be reached only for {@link AtomicMoveNotSupportedException}, which is
	 * not the exception Windows actually throws - it throws {@code AccessDeniedException}, a plain
	 * {@link IOException}. So on Windows the first failure went straight to the error handler and the
	 * save was simply lost: every config edit made through the GUI reverted at restart, with one line
	 * in the log at startup and nothing at all at the moment of the edit. Any failed rename now falls
	 * back and then retries.</p>
	 *
	 * @return whether the file is now in place
	 */
	private static boolean moveIntoPlace(Path temporary, Path target, Logger logger,
			String description) {
		IOException last = null;
		for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
			try {
				Files.move(temporary, target,
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return true;
			} catch (IOException atomicFailure) {
				last = atomicFailure;
			}
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
				return true;
			} catch (IOException plainFailure) {
				last = plainFailure;
			}
			if (attempt < MOVE_ATTEMPTS) {
				try {
					Thread.sleep(MOVE_RETRY_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		logger.error("Could not save {} at {} after {} attempts. The previous file is untouched, so "
			+ "the change will be lost at restart. Something is holding the file open - a virus "
			+ "scanner or an editor is the usual cause.", description, target, MOVE_ATTEMPTS, last);
		return false;
	}

	/**
	 * Writes {@code value} as JSON, returning whether the complete replacement succeeded.
	 * Callers should retain their dirty flag when this returns {@code false}.
	 */
	public static boolean write(Gson gson, Path path, Object value, Logger logger, String description) {
		Objects.requireNonNull(gson, "gson");
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(logger, "logger");

		Path target = path.toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			logger.error("Could not save {}: path has no parent: {}.", description, target);
			return false;
		}

		Path temporary = null;
		try {
			Files.createDirectories(parent);
			temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				gson.toJson(value, writer);
			}
			return moveIntoPlace(temporary, target, logger, description);
		} catch (IOException | RuntimeException exception) {
			logger.error("Could not save {} at {}.", description, target, exception);
			return false;
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException cleanupFailure) {
					logger.debug("Could not remove temporary JSON file {}.", temporary, cleanupFailure);
				}
			}
		}
	}
}
