package ca.techshop.streamboxupdates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SplitBundleTest {
    private static final List<String> XAPK = Arrays.asList("com.sesame.consumer.apk", "icon.png",
            "config.armeabi_v7a.apk", "config.hdpi.apk", "manifest.json");

    @Test
    public void anXapkListsItsApks() {
        assertEquals(Arrays.asList("com.sesame.consumer.apk", "config.armeabi_v7a.apk", "config.hdpi.apk"), SplitBundle.apks(XAPK));
        assertEquals("com.sesame.consumer.apk", SplitBundle.base(SplitBundle.apks(XAPK), "com.sesame.consumer"));
        assertFalse(SplitBundle.hasObb(XAPK));
    }

    @Test
    public void aPlainApkIsNotABundle() {
        assertTrue(SplitBundle.apks(Arrays.asList("AndroidManifest.xml", "classes.dex", "assets/inner.apk")).isEmpty());
    }

    @Test
    public void theBaseIsFoundWithoutThePackageName() {
        assertEquals("base.apk", SplitBundle.base(Arrays.asList("split_config.arm64_v8a.apk", "base.apk"), "x.y"));
        assertEquals("app.apk", SplitBundle.base(Arrays.asList("config.hdpi.apk", "app.apk"), "x.y"));
        assertEquals(null, SplitBundle.base(Collections.emptyList(), "x.y"));
    }

    @Test
    public void obbFilesAreSeen() {
        assertTrue(SplitBundle.hasObb(Arrays.asList("a.apk", "Android/obb/a/main.1.a.obb")));
    }
}
