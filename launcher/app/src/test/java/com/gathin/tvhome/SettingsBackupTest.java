package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SettingsBackupTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static ByteArrayInputStream zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes());
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    @Test public void onlyNamedEntriesAreTaken() {
        assertTrue(SettingsBackup.accepted(SettingsBackup.MANIFEST));
        assertTrue(SettingsBackup.accepted("files/order.txt"));
        assertTrue(SettingsBackup.accepted("files/cards.json"));
        assertTrue(SettingsBackup.accepted("wallpapers/custom_1.jpg"));
        assertTrue(SettingsBackup.accepted("wallpapers/" + WallpaperCaptions.FILE_NAME));
        assertFalse(SettingsBackup.accepted("files/other.txt"));
        assertFalse(SettingsBackup.accepted("config.json"));
        assertFalse(SettingsBackup.accepted("wallpapers/run.sh"));
    }

    @Test public void aPathCannotClimbOut() {
        assertFalse(SettingsBackup.accepted("../order.txt"));
        assertFalse(SettingsBackup.accepted("files/../order.txt"));
        assertFalse(SettingsBackup.accepted("wallpapers/../../x.jpg"));
        assertFalse(SettingsBackup.accepted("wallpapers/sub/x.jpg"));
    }

    @Test public void aBackupUnpacksIntoItsFolders() throws IOException {
        File dir = folder.newFolder();
        SettingsBackup.unpack(zip(SettingsBackup.MANIFEST, "files/order.txt", "wallpapers/a.jpg"), dir);
        assertArrayEquals("files/order.txt".getBytes(), Files.readAllBytes(new File(dir, "files/order.txt").toPath()));
        assertTrue(new File(dir, "wallpapers/a.jpg").isFile());
    }

    @Test public void aFileWithAnythingElseIsRefused() throws IOException {
        File dir = folder.newFolder();
        assertThrows(IOException.class, () -> SettingsBackup.unpack(zip(SettingsBackup.MANIFEST, "../evil.txt"), dir));
        assertFalse(new File(dir.getParentFile(), "evil.txt").exists());
    }

    @Test public void aZipWithNoManifestIsNotABackup() throws IOException {
        File dir = folder.newFolder();
        assertThrows(IOException.class, () -> SettingsBackup.unpack(zip("files/order.txt"), dir));
    }

    @Test public void theNameSaysWhatItIs() {
        assertTrue(SettingsBackup.fileName(new Date(0)).startsWith("StreamBox-backup-"));
        assertTrue(SettingsBackup.fileName(new Date(0)).endsWith(".zip"));
    }
}
