package com.gathin.tvhome;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ButtonMappingsTest {
    private static List<String> stored() {
        Map<String, String> stored = new TreeMap<>();
        stored.put("tap:190", "org.smarttube.stable");
        stored.put("hold:190", "com.plexapp.android");
        stored.put("hold:83", "com.tubitv");
        return ButtonMappings.entries(stored);
    }

    @Test public void aTapReadsAsAnAppKey() {
        assertEquals("org.smarttube.stable", RemoteKeys.parseAppKeys(stored(), n -> 0).get(190));
    }

    @Test public void aHoldReadsAsAHoldKeyAndNotATap() {
        assertEquals("com.plexapp.android", RemoteKeys.parseHoldAppKeys(stored(), n -> 0).get(190));
        assertEquals("com.tubitv", RemoteKeys.parseHoldAppKeys(stored(), n -> 0).get(83));
        assertNull(RemoteKeys.parseAppKeys(stored(), n -> 0).get(83));
    }

    @Test public void otherStoredValuesAreLeftOut() {
        assertTrue(ButtonMappings.entries(Collections.singletonMap("other", "x")).isEmpty());
    }

    @Test public void navigationIsNeverCaptured() {
        assertTrue(ButtonMappings.navigation(android.view.KeyEvent.KEYCODE_BACK));
        assertTrue(ButtonMappings.navigation(android.view.KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(ButtonMappings.navigation(190));
        assertFalse(ButtonMappings.navigation(288));
    }
}
