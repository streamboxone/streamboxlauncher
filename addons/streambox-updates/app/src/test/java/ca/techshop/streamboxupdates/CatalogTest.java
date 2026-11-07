package ca.techshop.streamboxupdates;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CatalogTest {
    private static final String FILE = "https://streambox.online/updates.txt";
    private static final String HASH = "ab".repeat(32);

    @Test public void aLineIsRead() {
        Catalog.Entry e = Catalog.parse("Clip Box | org.clipbox.player | 42 | 1.4.2 | 1 | https://a.example/c.apk | " + HASH, FILE).entries.get(0);
        assertEquals("Clip Box", e.name);
        assertEquals("org.clipbox.player", e.pkg);
        assertEquals(42, e.versionCode);
        assertEquals("1.4.2", e.versionName);
        assertTrue(e.uninstallFirst);
        assertEquals("https://a.example/c.apk", e.url);
        assertEquals(HASH, e.sha256);
    }

    @Test public void commentsAndBlanksDoNotCount() {
        Catalog c = Catalog.parse("# heading\r\n\r\nA | a.b | 1 | 1 | 0 | a.apk\r\n", FILE);
        assertEquals(1, c.entries.size());
        assertEquals(0, c.badLines);
    }

    @Test public void theChecksumMayBeLeftOut() {
        assertEquals("", Catalog.parse("A | a.b | 1 | 1 | 0 | a.apk", FILE).entries.get(0).sha256);
        assertEquals("", Catalog.parse("A | a.b | 1 | 1 | 0 | a.apk | ", FILE).entries.get(0).sha256);
    }

    @Test public void aBadLineIsCountedNotGuessedAt() {
        for (String bad : new String[]{
                "too | few | fields",
                "A | not a package | 1 | 1 | 0 | a.apk",
                "A | nodots | 1 | 1 | 0 | a.apk",
                "A | a.b | one | 1 | 0 | a.apk",
                "A | a.b | 0 | 1 | 0 | a.apk",
                "A | a.b | 1 | 1 | 2 | a.apk",
                "A | a.b | 1 | 1 | 0 | http://a.example/a.apk",
                "A | a.b | 1 | 1 | 0 | a.apk | shortsum",
                " | a.b | 1 | 1 | 0 | a.apk"}) {
            Catalog c = Catalog.parse(bad, FILE);
            assertEquals(bad, 1, c.badLines);
            assertTrue(bad, c.entries.isEmpty());
        }
    }

    @Test public void aRelativeAddressSitsBesideTheFile() {
        assertEquals("https://streambox.online/apks/a.apk", Catalog.address("apks/a.apk", FILE));
        assertEquals("https://streambox.online/a.apk", Catalog.address("/a.apk", FILE));
    }

    @Test public void onlyHttps() {
        assertEquals("https://a.example/a.apk", Catalog.address("https://a.example/a.apk", FILE));
        assertNull(Catalog.address("http://a.example/a.apk", FILE));
        assertNull(Catalog.address("file:///sdcard/a.apk", FILE));
        assertNull(Catalog.address("a.apk", "http://streambox.online/updates.txt"));
        assertNull(Catalog.address("", FILE));
    }

    @Test public void theStateComesFromTheVersionCode() {
        Catalog.Entry e = Catalog.parse("A | a.b | 5 | 5 | 0 | a.apk", FILE).entries.get(0);
        assertEquals(Catalog.State.NEW, Catalog.state(e, -1));
        assertEquals(Catalog.State.UPDATE, Catalog.state(e, 4));
        assertEquals(Catalog.State.CURRENT, Catalog.state(e, 5));
        assertEquals(Catalog.State.CURRENT, Catalog.state(e, 6));
    }

    @Test public void homeAndThisAppAreNeverUninstalled() {
        assertFalse(Catalog.mayUninstall("com.gathin.tvhome", "me.app"));
        assertFalse(Catalog.mayUninstall("me.app", "me.app"));
        assertTrue(Catalog.mayUninstall("a.b", "me.app"));
    }

    @Test public void aRemoveLineIsRead() {
        Catalog c = Catalog.parse("remove | Old TV | com.old.tv | stopped working\nREMOVE | Other | a.b\nA | a.c | 1 | 1 | 0 | a.apk", FILE);
        assertEquals(0, c.badLines);
        assertEquals(1, c.entries.size());
        assertEquals(2, c.removals.size());
        assertEquals("Old TV", c.removals.get(0).name);
        assertEquals("com.old.tv", c.removals.get(0).pkg);
        assertEquals("stopped working", c.removals.get(0).note);
        assertEquals("", c.removals.get(1).note);
    }

    @Test public void aBadRemoveLineIsCounted() {
        for (String bad : new String[]{
                "remove | Old",
                "remove | Old | nodots",
                "remove |  | a.b",
                "remove | Old | a.b | note | extra",
                "remove | Home | com.gathin.tvhome",
                "remove | Me | ca.techshop.streamboxupdates"}) {
            Catalog c = Catalog.parse(bad, FILE);
            assertEquals(bad, 1, c.badLines);
            assertTrue(bad, c.removals.isEmpty());
        }
    }
}
