package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    @Test public void aWebAddressIsHttpsOnly() {
        assertEquals("https://streambox.online/b.zip", SettingsBackup.webAddress(" https://streambox.online/b.zip "));
        assertEquals("https://streambox.online/b.zip", SettingsBackup.webAddress("streambox.online/b.zip"));
        assertNull(SettingsBackup.webAddress("http://streambox.online/b.zip"));
        assertNull(SettingsBackup.webAddress("ftp://streambox.online/b.zip"));
        assertNull(SettingsBackup.webAddress("file:///sdcard/b.zip"));
        assertNull(SettingsBackup.webAddress("https://"));
        assertNull(SettingsBackup.webAddress("https://a b/c"));
        assertNull(SettingsBackup.webAddress("  "));
        assertNull(SettingsBackup.webAddress(null));
    }

    /** One answer on a local port, for download(), which doesn't itself care which scheme it is given. */
    private static String serve(String status, byte[] body) throws IOException {
        ServerSocket server = new ServerSocket(0);
        new Thread(() -> {
            try (ServerSocket s = server; Socket socket = s.accept()) {
                InputStream in = socket.getInputStream();
                int last = 0, c;
                // The request ends at its blank line.
                int run = 0;
                while (run < 4 && (c = in.read()) != -1) {
                    run = (c == '\r' || c == '\n') && (c != last || run > 0) ? run + 1 : 0;
                    last = c;
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 " + status + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
                out.write(body);
                out.flush();
            } catch (IOException ignored) {
            }
        }).start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/b.zip";
    }

    @Test public void aDownloadSavesTheFile() throws IOException {
        File target = new File(folder.newFolder(), "b.zip");
        SettingsBackup.download(serve("200 OK", "zip".getBytes()), target, 100);
        assertArrayEquals("zip".getBytes(), Files.readAllBytes(target.toPath()));
    }

    @Test public void aMissingFileIsNotDownloaded() throws IOException {
        File target = new File(folder.newFolder(), "b.zip");
        String address = serve("404 Not Found", "<html>".getBytes());
        assertThrows(IOException.class, () -> SettingsBackup.download(address, target, 100));
        assertFalse(target.exists());
    }

    @Test public void aFileOverTheLimitStops() throws IOException {
        File target = new File(folder.newFolder(), "b.zip");
        String address = serve("200 OK", new byte[64]);
        assertThrows(IOException.class, () -> SettingsBackup.download(address, target, 10));
    }

    @Test public void aWebPageIsNotABackup() throws IOException {
        File dir = folder.newFolder();
        assertThrows(IOException.class, () -> SettingsBackup.unpack(new ByteArrayInputStream("<html>Not found</html>".getBytes()), dir));
    }

    @Test public void theNameSaysWhatItIs() {
        assertTrue(SettingsBackup.fileName(new Date(0)).startsWith("StreamBox-backup-"));
        assertTrue(SettingsBackup.fileName(new Date(0)).endsWith(".zip"));
    }
}
