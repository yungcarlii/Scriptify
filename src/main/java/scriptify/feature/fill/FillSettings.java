package scriptify.feature.fill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a fill should produce: which categories to draw from, how much of each stack, and how much of
 * the container to leave empty.
 *
 * <p>Leaving gaps matters more than it sounds — a container filled to every slot reads as generated,
 * while a half-full chest with scattered stacks reads as looted.
 */
public final class FillSettings {
	private final Set<String> categories = new LinkedHashSet<>();
	/**
	 * Explicitly chosen item ids, mixed into the pool alongside the categories.
	 *
	 * <p>Deliberate picks bypass the survival filter — choosing an item by hand is a decision.
	 */
	private final Set<String> items = new LinkedHashSet<>();
	private int minCount = 1;
	private int maxCount = 16;
	/** Percentage of the container's slots that receive an item. */
	private int density = 50;

	private String icon = "minecraft:book";

	public FillSettings() {
		// A sensible starting point rather than an empty selection that fills nothing.
		this.categories.add("blocks");
		this.categories.add("food");
		this.categories.add("valuables");
	}

	public Set<String> categories() {
		return Set.copyOf(this.categories);
	}

	public boolean isEnabled(String categoryId) {
		return this.categories.contains(categoryId);
	}

	public void toggle(String categoryId) {
		if (!this.categories.remove(categoryId)) {
			this.categories.add(categoryId);
		}
	}

	public void setEnabled(String categoryId, boolean enabled) {
		if (enabled) {
			this.categories.add(categoryId);
		} else {
			this.categories.remove(categoryId);
		}
	}

	public void clearCategories() {
		this.categories.clear();
	}

	public Set<String> items() {
		return Set.copyOf(this.items);
	}

	public void addItem(String itemId) {
		this.items.add(itemId);
	}

	public void removeItem(String itemId) {
		this.items.remove(itemId);
	}

	public void clearItems() {
		this.items.clear();
	}

	/**
	 * The item shown for this preset in the browser.
	 *
	 * <p>A shelf of identical books tells you nothing about which preset is which. Stored as an id so
	 * a preset written by hand can set one too.
	 */
	public String icon() {
		return this.icon;
	}

	public void setIcon(String itemId) {
		this.icon = itemId == null || itemId.isBlank() ? "minecraft:book" : itemId;
	}

	public int minCount() {
		return this.minCount;
	}

	public int maxCount() {
		return this.maxCount;
	}

	/** Keeps the two ends of the range from crossing over. */
	public void setMinCount(int value) {
		this.minCount = Math.max(1, Math.min(64, value));
		this.maxCount = Math.max(this.minCount, this.maxCount);
	}

	public void setMaxCount(int value) {
		this.maxCount = Math.max(1, Math.min(64, value));
		this.minCount = Math.min(this.minCount, this.maxCount);
	}

	public int density() {
		return this.density;
	}

	public void setDensity(int percent) {
		this.density = Math.max(5, Math.min(100, percent));
	}

	public FillSettings copy() {
		FillSettings copy = new FillSettings();
		copy.icon = this.icon;
		copy.categories.clear();
		copy.categories.addAll(this.categories);
		copy.items.clear();
		copy.items.addAll(this.items);
		copy.minCount = this.minCount;
		copy.maxCount = this.maxCount;
		copy.density = this.density;
		return copy;
	}

	// ---------------------------------------------------------------- json

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		JsonArray array = new JsonArray();
		this.categories.forEach(array::add);
		object.add("categories", array);
		JsonArray itemArray = new JsonArray();
		this.items.forEach(itemArray::add);
		object.add("items", itemArray);
		object.addProperty("minCount", this.minCount);
		object.addProperty("maxCount", this.maxCount);
		object.addProperty("density", this.density);
		object.addProperty("icon", this.icon);
		return object;
	}

	public static FillSettings fromJson(JsonObject object) {
		FillSettings settings = new FillSettings();
		settings.categories.clear();
		if (object.has("categories") && object.get("categories").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("categories")) {
				String id = element.getAsString();
				// Drop categories that no longer exist rather than failing the whole preset.
				if (FillCategory.byId(id) != null) {
					settings.categories.add(id);
				}
			}
		}
		if (object.has("items") && object.get("items").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("items")) {
				settings.items.add(element.getAsString());
			}
		}
		settings.setMinCount(object.has("minCount") ? object.get("minCount").getAsInt() : 1);
		settings.setMaxCount(object.has("maxCount") ? object.get("maxCount").getAsInt() : 16);
		settings.setDensity(object.has("density") ? object.get("density").getAsInt() : 50);
		settings.setIcon(object.has("icon") ? object.get("icon").getAsString() : null);
		return settings;
	}
}
