package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The owner's layout and settings as one zip file, to keep or to set up another box: Launcher settings, names,
 * categories, remote buttons, weather, order, shortcuts, cards and added pictures. Apps are not in it. A restore reads
 * and checks the whole file before it changes anything, and takes only the entries named here.
 */
final class SettingsBackup {
    static final String MANIFEST = "streambox-backup.json";
    private static final int VERSION = 1;
    private static final String FILES = "files/";
    private static final String WALLPAPERS = ThemeFiles.WALLPAPERS + "/";
    private static final List<String> PREFS = Arrays.asList(Config.SETTINGS_NAME, "app_renames", "app_categories",
            ButtonMappings.PREFS, "weather");
    private static final List<String> APP_FILES = Arrays.asList(Order.FILE_NAME, "library_apps.txt",
            "library_home_removed.txt", Cards.STORE_FILE);
    /** Kept to this box: its own count of settings changes, and the theme it has seen, whose change resets settings. */
    private static final Set<String> LOCAL_KEYS = new HashSet<>(Arrays.asList(Config.SETTINGS_REVISION_KEY, Config.THEME_SIGNATURE_KEY));
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int MAX_ENTRIES = 512;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String WEB_PREFS = "backup_web";
    private static final String WEB_ADDRESS = "address";

    private SettingsBackup() { }

    static String fileName(Date now) {
        return new SimpleDateFormat("'StreamBox-backup-'yyyy-MM-dd-HHmm'.zip'", Locale.ROOT).format(now);
    }

    /** Whether a restore takes this entry. Names are matched whole, so none can reach outside its folder. */
    static boolean accepted(String name) {
        if (MANIFEST.equals(name)) return true;
        if (name.startsWith(FILES)) return APP_FILES.contains(name.substring(FILES.length()));
        if (!name.startsWith(WALLPAPERS)) return false;
        String file = name.substring(WALLPAPERS.length());
        return ThemeFiles.collectionImage(file) || WallpaperCaptions.FILE_NAME.equals(file);
    }

    /** Unpacks a backup into an empty folder. Throws on anything a backup does not hold, or too much of it. */
    static void unpack(InputStream input, File dir) throws IOException {
        long total = 0;
        int entries = 0;
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!accepted(name) || !seen.add(name) || ++entries > MAX_ENTRIES) throw new IOException("Not a backup entry: " + name);
                File target = new File(dir, name);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot unpack " + name);
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Backup too large");
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!seen.contains(MANIFEST)) throw new IOException("No " + MANIFEST);
    }

    // ---- save ------------------------------------------------------------------------------------

    /** Writes a backup to Downloads, off the UI thread. Returns where it went, or null when it could not be written. */
    static String save(Context context) {
        String name = fileName(new Date());
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Downloads needs a storage permission before Android 10; this launcher's own folder does not.
                File dir = Config.dir(context);
                if (dir == null) return null;
                File file = new File(dir, name);
                try (OutputStream output = new FileOutputStream(file)) {
                    write(context, output);
                }
                return file.getPath();
            }
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("No output for " + uri);
                write(context, output);
            } catch (IOException | JSONException | RuntimeException e) {
                resolver.delete(uri, null, null);
                return null;
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    private static void write(Context context, OutputStream output) throws IOException, JSONException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(manifest(context).toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String name : APP_FILES) {
                AtomicFile file = new AtomicFile(new File(context.getFilesDir(), name));
                if (!file.getBaseFile().exists()) continue;
                zip.putNextEntry(new ZipEntry(FILES + name));
                zip.write(file.readFully());
                zip.closeEntry();
            }
            File base = Config.dir(context);
            File[] pictures = base == null ? null : new File(base, ThemeFiles.WALLPAPERS).listFiles();
            if (pictures == null) return;
            for (File picture : pictures) {
                String entry = WALLPAPERS + picture.getName();
                if (!picture.isFile() || !accepted(entry)) continue;
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(picture.toPath(), zip);
                zip.closeEntry();
            }
        }
    }

    private static JSONObject manifest(Context context) throws JSONException {
        JSONObject prefs = new JSONObject();
        for (String name : PREFS) {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> e : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (!LOCAL_KEYS.contains(e.getKey())) values.put(e.getKey(), typed(e.getValue()));
            }
            prefs.put(name, values);
        }
        return new JSONObject().put("version", VERSION).put("prefs", prefs);
    }

    /** JSON alone would lose whether 3 was an int or a long, and SharedPreferences reads each only as what it was. */
    private static JSONObject typed(Object value) throws JSONException {
        JSONObject out = new JSONObject();
        if (value instanceof Set) {
            JSONArray items = new JSONArray();
            for (Object item : (Set<?>) value) items.put(String.valueOf(item));
            return out.put("type", "set").put("value", items);
        }
        String type = value instanceof Boolean ? "boolean" : value instanceof Integer ? "int" : value instanceof Long ? "long"
                : value instanceof Float ? "float" : "string";
        return out.put("type", type).put("value", value instanceof Float ? String.valueOf(value) : value);
    }

    // ---- restore ---------------------------------------------------------------------------------

    /** What was typed as a full HTTPS address (https:// is added when left out), or null when it can't be one. */
    static String webAddress(String typed) {
        if (typed == null) return null;
        String address = typed.trim();
        if (address.isEmpty() || address.matches(".*\\s.*")) return null;
        if (!address.contains("://")) address = "https://" + address;
        try {
            URL url = new URL(address);
            return "https".equalsIgnoreCase(url.getProtocol()) && !url.getHost().isEmpty() ? address : null;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** The last address restored from, or the one this build came with. Kept to this box, so not in a backup. */
    static String lastWebAddress(Context context) {
        return context.getSharedPreferences(WEB_PREFS, Context.MODE_PRIVATE)
                .getString(WEB_ADDRESS, context.getString(R.string.backup_url));
    }

    /** Saves the file at this address into target. Throws on any answer but the file, or more than limit bytes. */
    static void download(String address, File target, long limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("No file at " + address);
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > limit) throw new IOException("File too large at " + address);
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The same as restoring from a file, from an HTTPS address, off the UI thread. A web page or error is refused. */
    static boolean restoreFromWeb(Context context, String typed) {
        String address = webAddress(typed);
        if (address == null) return false;
        File file = new File(context.getCacheDir(), "restore-download.zip");
        try {
            download(address, file, MAX_BYTES);
            boolean restored;
            try (InputStream input = new FileInputStream(file)) {
                restored = restore(context, input);
            }
            if (restored) context.getSharedPreferences(WEB_PREFS, Context.MODE_PRIVATE).edit().putString(WEB_ADDRESS, address).apply();
            return restored;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            file.delete();
        }
    }

    /** Replaces this box's layout and settings with the backup, off the UI thread. False leaves the settings as they were. */
    static boolean restore(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return input != null && restore(context, input);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean restore(Context context, InputStream backup) {
        File stage = new File(context.getCacheDir(), "restore");
        try {
            delete(stage);
            if (!stage.mkdirs()) return false;
            unpack(backup, stage);
            JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(stage, MANIFEST).toPath()), StandardCharsets.UTF_8));
            if (manifest.getInt("version") != VERSION) return false;
            JSONObject prefs = manifest.getJSONObject("prefs");
            // Read every value first: one that is wrong stops the restore before the first change.
            for (String name : PREFS) apply(null, prefs.getJSONObject(name));
            File[] pictures = new File(stage, ThemeFiles.WALLPAPERS).listFiles();
            if (pictures != null) for (File picture : pictures) if (!readable(picture)) return false;
            copyPictures(context, pictures);
            copyFiles(context, stage);
            for (String name : PREFS) restorePrefs(context, name, prefs.getJSONObject(name));
            Weather.forget();
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            return false;
        } finally {
            delete(stage);
        }
    }

    private static boolean readable(File picture) {
        if (!ThemeFiles.collectionImage(picture.getName())) return true;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(picture.getPath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    /** The backup's pictures in, then the pictures added on this box that it doesn't have out. A theme's own stay. */
    private static void copyPictures(Context context, File[] pictures) throws IOException {
        File base = Config.dir(context);
        if (base == null) {
            if (pictures != null && pictures.length > 0) throw new IOException("No storage for pictures");
            return;
        }
        File dir = new File(base, ThemeFiles.WALLPAPERS);
        Set<String> keep = new HashSet<>();
        if (pictures != null) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot make " + dir);
            for (File picture : pictures) {
                // WallpaperRotation only reads picture names, so a half-copied .tmp never reaches Home.
                File partial = new File(dir, picture.getName() + ".tmp");
                try (OutputStream output = new FileOutputStream(partial)) {
                    Files.copy(picture.toPath(), output);
                }
                File target = new File(dir, picture.getName());
                if (!partial.renameTo(target)) {
                    partial.delete();
                    throw new IOException("Cannot write " + target);
                }
                keep.add(picture.getName());
            }
        }
        File[] present = dir.listFiles();
        if (present == null) return;
        for (File picture : present) {
            if (CustomWallpapers.isCustom(picture.getName()) && !keep.contains(picture.getName())) picture.delete();
        }
    }

    private static void copyFiles(Context context, File stage) throws IOException {
        for (String name : APP_FILES) {
            AtomicFile target = new AtomicFile(new File(context.getFilesDir(), name));
            File from = new File(stage, FILES + name);
            if (!from.isFile()) {
                target.delete();
                continue;
            }
            FileOutputStream output = target.startWrite();
            try (FileInputStream input = new FileInputStream(from)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                output.write(bytes.toByteArray());
                target.finishWrite(output);
            } catch (IOException | RuntimeException e) {
                target.failWrite(output);
                throw e;
            }
        }
    }

    private static void restorePrefs(Context context, String name, JSONObject values) throws JSONException, IOException {
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        Map<String, ?> before = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit().clear();
        apply(editor, values);
        for (String key : LOCAL_KEYS) {
            Object kept = before.get(key);
            if (kept instanceof String) editor.putString(key, (String) kept);
        }
        // One more change than this box had seen, so Home rebuilds with what came in.
        if (Config.SETTINGS_NAME.equals(name)) {
            Object revision = before.get(Config.SETTINGS_REVISION_KEY);
            editor.putLong(Config.SETTINGS_REVISION_KEY, (revision instanceof Long ? (Long) revision : 0L) + 1);
        }
        if (!editor.commit()) throw new IOException("Cannot save " + name);
    }

    /** Puts the values in the editor; with none, only checks they can all be read. */
    private static void apply(SharedPreferences.Editor editor, JSONObject values) throws JSONException {
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (LOCAL_KEYS.contains(key)) continue;
            JSONObject item = values.getJSONObject(key);
            String type = item.getString("type");
            switch (type) {
                case "boolean": {
                    boolean v = item.getBoolean("value");
                    if (editor != null) editor.putBoolean(key, v);
                    break;
                }
                case "int": {
                    int v = item.getInt("value");
                    if (editor != null) editor.putInt(key, v);
                    break;
                }
                case "long": {
                    long v = item.getLong("value");
                    if (editor != null) editor.putLong(key, v);
                    break;
                }
                case "float": {
                    float v = Float.parseFloat(item.getString("value"));
                    if (editor != null) editor.putFloat(key, v);
                    break;
                }
                case "string": {
                    String v = item.getString("value");
                    if (editor != null) editor.putString(key, v);
                    break;
                }
                case "set": {
                    JSONArray items = item.getJSONArray("value");
                    Set<String> v = new HashSet<>();
                    for (int i = 0; i < items.length(); i++) v.add(items.getString(i));
                    if (editor != null) editor.putStringSet(key, v);
                    break;
                }
                default:
                    throw new JSONException("Unknown type " + type);
            }
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
