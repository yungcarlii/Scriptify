package scriptify.storage;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public interface DataStore {
	Path root();

	JsonObject loadObject(String module, String fileName, Supplier<JsonObject> defaults);

	void saveObject(String module, String fileName, JsonObject value);

	List<StoredJson> loadObjects(String module, String directory);
}
