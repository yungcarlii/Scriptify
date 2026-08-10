package scriptify.storage;

import com.google.gson.JsonObject;
import java.nio.file.Path;

public record StoredJson(Path path, JsonObject object) {
}
