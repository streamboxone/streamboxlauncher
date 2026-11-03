package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AppCategoriesTest {
    /** Slots as text: a tile's index, a heading's title in quotes, a blank as a dot. */
    private static String show(List<AppCategories.Slot> slots) {
        List<String> out = new ArrayList<>();
        for (AppCategories.Slot s : slots) {
            out.add(s.tile >= 0 ? String.valueOf(s.tile) : s.title.isEmpty() ? "." : "'" + s.title + "'");
        }
        return String.join(" ", out);
    }

    @Test public void aNameIsOneTrimmedLine() {
        assertEquals("Live TV", AppCategories.normalize("  Live \n  TV "));
        assertEquals("", AppCategories.normalize(null));
        assertEquals(AppCategories.MAX_LENGTH, AppCategories.normalize("a".repeat(99)).length());
    }

    @Test public void aNameIsTakenWhateverItsCapitals() {
        List<String> names = Arrays.asList("Movies", "Sports");
        assertTrue(AppCategories.taken(names, "movies", null));
        assertFalse(AppCategories.taken(names, "movies", "Movies"));
        assertFalse(AppCategories.taken(names, "Games", null));
    }

    @Test public void aCategoryMovesOnePlaceAndStopsAtTheEnds() {
        List<String> names = Arrays.asList("A", "B", "C");
        assertEquals(Arrays.asList("B", "A", "C"), AppCategories.moved(names, "A", 1));
        assertEquals(names, AppCategories.moved(names, "A", -1));
        assertEquals(names, AppCategories.moved(names, "C", 1));
        assertEquals(names, AppCategories.moved(names, "gone", 1));
    }

    @Test public void noHeadingsUntilATileIsInACategory() {
        assertNull(AppCategories.arrange(Arrays.asList(null, "gone"), Arrays.asList("Movies"), 3, "Other"));
    }

    @Test public void sectionsAreWholeRowsInTheOwnersOrder() {
        List<AppCategories.Slot> slots = AppCategories.arrange(Arrays.asList("B", null, "A", "B"),
                Arrays.asList("A", "B"), 3, "Other");
        assertEquals("'A' . . 2 . . 'B' . . 0 3 . 'Other' . . 1", show(slots));
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).title != null && !slots.get(i).title.isEmpty()) assertEquals(0, i % 3);
        }
    }

    @Test public void anEmptyCategoryAndAnEmptyOtherAreLeftOut() {
        assertEquals("'B' . 0 1", show(AppCategories.arrange(Arrays.asList("B", "B"),
                Arrays.asList("A", "B", "C"), 2, "Other")));
    }

    private static final boolean[] GRID = {
            true, true, true,      // dock
            false, false, false,   // heading
            true, false, false,    // a short section
            false, false, false,   // heading
            true, true, true,
            true, true};

    @Test public void upAndDownStepOverHeadingsToTheClosestTile() {
        assertEquals(6, AppCategories.vertical(GRID, 3, 2, true));
        assertEquals(12, AppCategories.vertical(GRID, 3, 6, true));
        assertEquals(0, AppCategories.vertical(GRID, 3, 6, false));
        assertEquals(6, AppCategories.vertical(GRID, 3, 14, false));
    }

    @Test public void theShortLastRowAndTheEndsAreHandled() {
        assertEquals(16, AppCategories.vertical(GRID, 3, 14, true));
        assertEquals(-1, AppCategories.vertical(GRID, 3, 16, true));
        assertEquals(-1, AppCategories.vertical(GRID, 3, 1, false));
    }
}
