package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AppRenamesTest {
    @Test public void spacesAroundANameAreTrimmed() {
        assertEquals("Plex", AppRenames.normalize("  Plex "));
    }

    @Test public void aBlankNameIsEmpty() {
        assertEquals("", AppRenames.normalize("   "));
        assertEquals("", AppRenames.normalize(null));
    }

    @Test public void aLongNameIsCutWithoutATrailingSpace() {
        String longName = "a".repeat(AppRenames.MAX_LENGTH - 1) + " bcd";
        assertEquals("a".repeat(AppRenames.MAX_LENGTH - 1), AppRenames.normalize(longName));
    }
}
