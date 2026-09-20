package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.feature.Sfx;
import scriptify.rollback.RollbackService;
import scriptify.rollback.Snapshot;
import scriptify.runtime.ScriptifyRuntime;

/**
 * A player's history, newest first, and what to do with it.
 *
 * <p>The timeline is the top level because the question is always "when" before it is "what": a
 * director knows roughly when the take went wrong and works back from there. Reasons are coloured so
 * the deaths stand out, because those are what people come here for.
 */
public final class RollbackMenu {
	private static final int[] SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final int PER_PAGE = SLOTS.length;

	private RollbackMenu() {
	}

	public static void open(ServerPlayer viewer, UUID subject, String subjectName, int page) {
		RollbackService rollback = ScriptifyRuntime.rollback();
		if (rollback == null) {
			Menus.deny(viewer, "Scriptify is still starting up.");
			return;
		}
		List<Snapshot> all = rollback.list(subject);
		int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
		int current = Math.max(0, Math.min(page, pages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Msg.header(subjectName + " — history", ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		if (all.isEmpty()) {
			gui.setSlot(22, Menus.button(Items.BARRIER, "Nothing recorded yet", ChatFormatting.GRAY,
					"Snapshots are taken on death and",
					"whenever you run /inventoryrollback save.")
				.build());
		}

		for (int cell = 0; cell < PER_PAGE; cell++) {
			int index = current * PER_PAGE + cell;
			if (index >= all.size()) {
				break;
			}
			Snapshot snapshot = all.get(index);
			gui.setSlot(SLOTS[cell], entry(viewer, subject, subjectName, snapshot, current));
		}

		paging(gui, viewer, subject, subjectName, current, pages);

		gui.setSlot(40, Menus.button(Items.CLOCK, "Undo last restore", ChatFormatting.GOLD,
				"Puts you back to just before the",
				"last restore you ran this session.")
			.setCallback((slot, type, action, ignored) -> {
				if (rollback.undo(viewer)) {
					Menus.notify(viewer, "Undid the last restore.");
				} else {
					Menus.deny(viewer, "You have not restored anything this session.");
				}
				open(viewer, subject, subjectName, current);
			}).build());

		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> gui.close()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static eu.pb4.sgui.api.elements.GuiElement entry(ServerPlayer viewer, UUID subject,
			String subjectName, Snapshot snapshot, int page) {
		RollbackService rollback = ScriptifyRuntime.rollback();

		List<String> lore = new ArrayList<>();
		lore.add(ago(snapshot.createdAt()));
		lore.add(reasonLabel(snapshot.reason()));
		if (!snapshot.note().isBlank()) {
			lore.add(snapshot.note());
		}
		lore.add("");
		lore.add(snapshot.itemCount() + " item stack(s)");
		lore.add("Level " + snapshot.experienceLevel()
			+ ", " + Math.round(snapshot.health()) + " health");
		List<String> sections = new ArrayList<>();
		snapshot.populatedSections().forEach(section -> sections.add(section.label));
		lore.add("Has: " + String.join(", ", sections));
		lore.add("");
		lore.add("Left click to restore everything.");
		lore.add("Right click to restore the inventory only.");

		var button = Menus.button(iconFor(snapshot.reason()), snapshot.label(),
			colourFor(snapshot.reason()), lore.toArray(new String[0]));
		if (Snapshot.REASON_DEATH.equals(snapshot.reason())) {
			button.glow();
		}

		return button.setCallback((slot, type, action, ignored) -> {
			// Death drops are left out of a whole restore on purpose: adding the floor items on top of
			// a restored inventory duplicates everything the player was carrying when they died.
			var sectionsToApply = type.isRight
				? EnumSet.of(Snapshot.Section.INVENTORY)
				: EnumSet.complementOf(EnumSet.of(Snapshot.Section.DEATH_DROPS));

			boolean done = viewer.getUUID().equals(subject)
				? rollback.restoreSections(viewer, snapshot.id(), sectionsToApply)
				: rollback.transfer(subject, viewer, snapshot.id(), sectionsToApply);

			if (done) {
				Menus.notify(viewer, "Restored " + snapshot.label()
					+ (type.isRight ? " (inventory only)" : ""));
			} else {
				Menus.deny(viewer, "That snapshot has gone.");
			}
			open(viewer, subject, subjectName, page);
		}).build();
	}

	private static void paging(SimpleGui gui, ServerPlayer viewer, UUID subject, String subjectName,
			int current, int pages) {
		if (current > 0) {
			gui.setSlot(38, Menus.button(Items.ARROW, "Newer", ChatFormatting.WHITE)
				.setCallback((slot, type, action, ignored) -> {
					Sfx.click(viewer);
					open(viewer, subject, subjectName, current - 1);
				}).build());
		}
		if (current < pages - 1) {
			gui.setSlot(42, Menus.button(Items.ARROW, "Older", ChatFormatting.WHITE)
				.setCallback((slot, type, action, ignored) -> {
					Sfx.click(viewer);
					open(viewer, subject, subjectName, current + 1);
				}).build());
		}
	}

	/** Relative time, because "eleven minutes ago" is the question people are actually asking. */
	private static String ago(Instant when) {
		Duration since = Duration.between(when, Instant.now());
		long minutes = since.toMinutes();
		if (minutes < 1) {
			return "moments ago";
		}
		if (minutes < 60) {
			return minutes + " minute(s) ago";
		}
		long hours = since.toHours();
		if (hours < 24) {
			return hours + " hour(s) ago";
		}
		return since.toDays() + " day(s) ago";
	}

	private static String reasonLabel(String reason) {
		return switch (reason) {
			case Snapshot.REASON_DEATH -> "Taken on death";
			case Snapshot.REASON_MANUAL -> "Taken by hand";
			case Snapshot.REASON_BEFORE_RESTORE -> "Taken before a restore";
			default -> "Taken automatically";
		};
	}

	private static ChatFormatting colourFor(String reason) {
		return switch (reason) {
			case Snapshot.REASON_DEATH -> ChatFormatting.RED;
			case Snapshot.REASON_MANUAL -> ChatFormatting.GREEN;
			case Snapshot.REASON_BEFORE_RESTORE -> ChatFormatting.GOLD;
			default -> ChatFormatting.GRAY;
		};
	}

	private static Item iconFor(String reason) {
		return switch (reason) {
			case Snapshot.REASON_DEATH -> Items.SKELETON_SKULL;
			case Snapshot.REASON_MANUAL -> Items.WRITTEN_BOOK;
			case Snapshot.REASON_BEFORE_RESTORE -> Items.CLOCK;
			default -> Items.PAPER;
		};
	}
}
