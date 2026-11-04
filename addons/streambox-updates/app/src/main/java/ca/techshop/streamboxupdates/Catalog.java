package ca.techshop.streamboxupdates;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text file of apps. One app per line, separated by |, and # starts a comment:
 *
 *   name | package | versionCode | versionName | uninstall first (0 or 1) | https address | sha256 (may be left out)
 *
 * An address that doesn't start with https:// is taken relative to the file itself.
 */
final class Catalog {
    static final class Entry {
        final String name;
        final String pkg;
        final long versionCode;
        final String versionName;
        final boolean uninstallFirst;
        final String url;
        final String sha256;

        Entry(String name, String pkg, long versionCode, String versionName, boolean uninstallFirst, String url, String sha256) {
            this.name = name;
            this.pkg = pkg;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.uninstallFirst = uninstallFirst;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    final List<Entry> entries = new ArrayList<>();
    /** Lines that were not comments and could not be read: shown, so a typo in the file doesn't just lose an app. */
    int badLines;

    static Catalog parse(String text, String fileUrl) {
        Catalog catalog = new Catalog();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Entry entry = entry(line, fileUrl);
            if (entry == null) catalog.badLines++;
            else catalog.entries.add(entry);
        }
        return catalog;
    }

    private static Entry entry(String line, String fileUrl) {
        String[] f = line.split("\\|", -1);
        if (f.length < 6 || f.length > 7) return null;
        String name = f[0].trim();
        String pkg = f[1].trim();
        String flag = f[4].trim();
        String sha256 = f.length == 7 ? f[6].trim().toLowerCase(Locale.ROOT) : "";
        long versionCode;
        try {
            versionCode = Long.parseLong(f[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String url = address(f[5].trim(), fileUrl);
        if (name.isEmpty() || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || versionCode < 1
                || !(flag.equals("0") || flag.equals("1")) || url == null
                || !(sha256.isEmpty() || sha256.matches("[0-9a-f]{64}"))) return null;
        return new Entry(name, pkg, versionCode, f[3].trim(), flag.equals("1"), url, sha256);
    }

    /** HTTPS only: what comes from here gets installed. */
    static String address(String given, String fileUrl) {
        if (given.isEmpty() || given.contains(" ")) return null;
        if (given.contains("://")) return given.toLowerCase(Locale.ROOT).startsWith("https://") ? given : null;
        int slash = fileUrl.lastIndexOf('/');
        if (slash < "https://".length() || !fileUrl.toLowerCase(Locale.ROOT).startsWith("https://")) return null;
        return fileUrl.substring(0, slash + 1) + (given.startsWith("/") ? given.substring(1) : given);
    }

    enum State { UPDATE, NEW, CURRENT }

    /** installedVersion is -1 when the app isn't on this box. */
    static State state(Entry entry, long installedVersion) {
        if (installedVersion < 0) return State.NEW;
        return installedVersion < entry.versionCode ? State.UPDATE : State.CURRENT;
    }

    /** Taking Home or this app off the box part way through would leave nothing to finish the job with. */
    static boolean mayUninstall(String pkg, String self) {
        return !pkg.equals(self) && !pkg.equals("com.gathin.tvhome");
    }

    /** "1.4 GB" from bytes. */
    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.getDefault(), "%.1f GB", bytes / (double) (1L << 30));
        return Math.round(bytes / (double) (1L << 20)) + " MB";
    }

    private Catalog() {
    }
}
