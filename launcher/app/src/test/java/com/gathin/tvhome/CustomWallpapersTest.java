package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CustomWallpapersTest {
    @Test public void anAddedPictureIsCustomAndPassesTheCollectionRules() {
        String name = CustomWallpapers.fileName(1234L);
        assertTrue(CustomWallpapers.isCustom(name));
        assertTrue(ThemeFiles.collectionImage(name));
    }

    @Test public void aThemesPicturesAreNotCustom() {
        assertFalse(CustomWallpapers.isCustom("dusk.jpg"));
        assertFalse(CustomWallpapers.isCustom("custom_1.jpg.tmp"));
        assertFalse(CustomWallpapers.isCustom(null));
    }

    @Test public void fileManagersArePickersAndThePhotoPickerIsNot() {
        assertTrue(CustomWallpapers.usablePicker("com.alphainventor.filemanager"));
        assertFalse(CustomWallpapers.usablePicker("com.android.providers.media.module"));
        assertFalse(CustomWallpapers.usablePicker("com.android.tv.frameworkpackagestubs"));
    }

    @Test public void onlyKeepsTheAddedPictures() {
        File added = new File(CustomWallpapers.fileName(1L));
        List<File> custom = CustomWallpapers.only(Arrays.asList(new File("dusk.jpg"), added));
        assertEquals(1, custom.size());
        assertEquals(added, custom.get(0));
    }
}
