package scriptify.feature;

import java.util.List;
import org.junit.jupiter.api.Test;
import scriptify.feature.Announcement.Page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A message breaks into titles the way it would be read: whole words, a short title, the rest underneath. */
class AnnouncementTest {
	@Test
	void shortMessageIsJustTheTitle() {
		assertEquals(List.of(new Page("Day 7", "")), Announcement.pages("  Day   7 "));
	}

	@Test
	void overflowGoesToTheSubtitle() {
		assertEquals(List.of(new Page("The border closes at", "midnight tonight")),
			Announcement.pages("The border closes at midnight tonight"));
	}

	@Test
	void longMessageIsPagedWithoutCuttingWords() {
		String message = "The village has stood for a hundred years but tonight the walls will fall "
			+ "and nobody who stays behind will see the morning";
		List<Page> pages = Announcement.pages(message);
		assertEquals(2, pages.size());
		StringBuilder rejoined = new StringBuilder();
		for (Page page : pages) {
			assertTrue(page.title().length() <= Announcement.TITLE_WIDTH, page.title());
			assertTrue(page.subtitle().length() <= Announcement.SUBTITLE_WIDTH, page.subtitle());
			rejoined.append(' ').append(page.title()).append(' ').append(page.subtitle());
		}
		assertEquals(message, rejoined.toString().trim().replaceAll("\\s+", " "));
	}

	@Test
	void aWordTooLongForTheLineGetsItWhole() {
		assertEquals(List.of(new Page("Supercalifragilisticexpialidocious", "indeed")),
			Announcement.pages("Supercalifragilisticexpialidocious indeed"));
	}

	@Test
	void colourCodesTakeNoRoomAndCarryOverTheWrap() {
		assertEquals(List.of(new Page("&c&lThe border closes at", "&c&lmidnight &rtonight")),
			Announcement.pages("&c&lThe border closes at midnight &rtonight"));
	}
}
