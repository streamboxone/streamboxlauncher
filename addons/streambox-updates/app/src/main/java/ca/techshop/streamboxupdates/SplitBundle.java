package ca.techshop.streamboxupdates;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A split bundle (XAPK from APKPure, or APKS): a zip that holds the app's APKs, all of which go into one install.
 * A plain APK is a zip too, so it is told apart by its AndroidManifest.xml at the top.
 */
final class SplitBundle {
    private SplitBundle() { }

    /** The APKs at the top of a bundle, in zip order; empty for a plain APK or a zip without any. */
    static List<String> apks(List<String> entries) {
        List<String> apks = new ArrayList<>();
        if (entries.contains("AndroidManifest.xml")) return apks;
        for (String name : entries) {
            if (!name.contains("/") && name.toLowerCase(Locale.ROOT).endsWith(".apk")) apks.add(name);
        }
        return apks;
    }

    /** OBB data files go to shared storage, which this app has no permission for, so such a bundle is refused. */
    static boolean hasObb(List<String> entries) {
        for (String name : entries) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".obb")) return true;
        }
        return false;
    }

    /** The base APK: base.apk or the package's own name, else the first that isn't a config or split part. */
    static String base(List<String> apks, String pkg) {
        for (String name : apks) {
            if (name.equals("base.apk") || name.equals(pkg + ".apk")) return name;
        }
        for (String name : apks) {
            if (!name.startsWith("config.") && !name.startsWith("split_")) return name;
        }
        return apks.isEmpty() ? null : apks.get(0);
    }
}
