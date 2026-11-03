package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pictures the owner adds from Launcher settings. Each is copied into the wallpapers/ collection at screen size,
 * so a large photo costs no more to show than a theme's picture and works after the source drive is gone.
 */
final class CustomWallpapers {
    private static final String PREFIX = "custom_";
    private static final int JPEG_QUALITY = 90;

    private CustomWallpapers() { }

    /**
     * The apps that can hand over a picture with a remote. Android's photo picker shows no focus for the D-pad and
     * only indexed pictures; the framework stub just says it isn't supported; Projectivy's icon pack answers but
     * picks nothing. The photo picker is still what opens when nothing else is installed.
     */
    static boolean usablePicker(String pkg) {
        return !pkg.startsWith("com.android.providers.media") && !pkg.startsWith("com.android.tv.frameworkpackagestubs")
                && !pkg.startsWith("dev.projectivy.");
    }

    static boolean isCustom(String name) {
        return name != null && name.startsWith(PREFIX) && name.endsWith(".jpg");
    }

    static String fileName(long time) {
        return PREFIX + time + ".jpg";
    }

    static List<File> only(List<File> images) {
        List<File> custom = new ArrayList<>();
        for (File image : images) if (isCustom(image.getName())) custom.add(image);
        return custom;
    }

    /** Copies the picture in, off the UI thread. Returns the new file, or null when it can't be read or is too large. */
    static File add(Context context, Uri uri) {
        File base = Config.dir(context);
        if (base == null) return null;
        File dir = new File(base, ThemeFiles.WALLPAPERS);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, fileName(System.currentTimeMillis()));
        // images() only reads picture names, so a half-written .tmp never reaches Home.
        File partial = new File(dir, target.getName() + ".tmp");
        try {
            Bitmap bitmap = decode(context, uri);
            if (bitmap == null) return null;
            try (FileOutputStream output = new FileOutputStream(partial)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null;
            } finally {
                bitmap.recycle();
            }
            return partial.renameTo(target) ? target : null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            partial.delete();
        }
    }

    private static Bitmap decode(Context context, Uri uri) throws IOException {
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int degrees = rotation(context, uri);
        boolean sideways = degrees == 90 || degrees == 270;
        int width = sideways ? options.outHeight : options.outWidth;
        int height = sideways ? options.outWidth : options.outHeight;
        int sample = WallpaperSize.sampleSize(width, height, screen.widthPixels, screen.heightPixels);
        // A long strip still decodes huge at the sample that covers the screen.
        if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        if (degrees != 0) matrix.postRotate(degrees);
        int shownW = sideways ? bitmap.getHeight() : bitmap.getWidth();
        int shownH = sideways ? bitmap.getWidth() : bitmap.getHeight();
        int[] scale = WallpaperSize.coverScale(shownW, shownH, screen.widthPixels, screen.heightPixels);
        if (scale != null) matrix.postScale((float) scale[1] / scale[0], (float) scale[1] / scale[0]);
        if (matrix.isIdentity()) return bitmap;
        Bitmap fitted = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (fitted != bitmap) bitmap.recycle();
        return fitted;
    }

    /** How far a photo must turn to stand upright; pictures without EXIF don't turn. */
    private static int rotation(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return 0;
            switch (new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
