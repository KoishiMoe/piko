/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.gallery;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.patches.nativeFeatures.downloader.NativeDownloader;

/**
 * Downloads, stitches, and saves multi-image posts at original alignment into {@code Pictures/Twitter}.
 */
final class StitchImageSaver {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_DIMENSION = 32_768;
    private static final long MIN_PIXEL_BUDGET = 16_000_000L;
    private static final long MAX_PIXEL_BUDGET = 64_000_000L;
    private static final String PICTURES_TWITTER_REL_PATH = Environment.DIRECTORY_PICTURES + "/Twitter";

    private static final Pattern QUERY_NAME_PATTERN = Pattern.compile("([?&]name=)[^&]+");
    private static final Pattern LEGACY_SUFFIX_PATTERN = Pattern.compile(":(orig|large|medium|small)$");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piko-stitch-saver");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    interface Callback {
        void onComplete(boolean success, @NonNull String message);
    }

    private static final class TileInfo {
        final File file;
        final int width;
        final int height;
        final long alignedWidth;
        final long alignedHeight;
        final boolean isJpeg;

        TileInfo(File file, int width, int height, long alignedWidth, long alignedHeight, boolean isJpeg) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.alignedWidth = alignedWidth;
            this.alignedHeight = alignedHeight;
            this.isJpeg = isJpeg;
        }
    }

    private static final class StitchLayout {
        final int outWidth;
        final int outHeight;
        final double scale;
        final List<Rect> tileRects;

        StitchLayout(int outWidth, int outHeight, double scale, List<Rect> tileRects) {
            this.outWidth = outWidth;
            this.outHeight = outHeight;
            this.scale = scale;
            this.tileRects = tileRects;
        }
    }

    private static final class RenderResult {
        final Bitmap bitmap;
        final double scale;
        final boolean hasAlpha;

        RenderResult(Bitmap bitmap, double scale, boolean hasAlpha) {
            this.bitmap = bitmap;
            this.scale = scale;
            this.hasAlpha = hasAlpha;
        }
    }

    private StitchImageSaver() {
    }

    static void saveAsync(
            @NonNull Context context,
            @NonNull List<String> rawUrls,
            boolean horizontal,
            boolean reversed,
            boolean useOriginalQuality,
            @Nullable String baseFileName,
            @NonNull Callback callback) {

        Context appContext = context.getApplicationContext();
        ArrayList<String> orderedUrls = new ArrayList<>(rawUrls);
        if (reversed) {
            Collections.reverse(orderedUrls);
        }

        EXECUTOR.execute(() -> {
            File tempDir = new File(appContext.getCacheDir(), "piko_stitch_" + System.currentTimeMillis());
            try {
                if (!tempDir.exists() && !tempDir.mkdirs()) {
                    throw new IOException("Cannot create temp directory");
                }

                String quality = useOriginalQuality ? "orig" : "large";
                List<TileInfo> tiles = downloadAndProbeTiles(tempDir, orderedUrls, quality, horizontal);

                boolean allJpeg = true;
                for (TileInfo tile : tiles) {
                    if (!tile.isJpeg) {
                        allJpeg = false;
                        break;
                    }
                }

                double initialScale = computeInitialScale(appContext, tiles, horizontal);
                RenderResult rendered = renderWithOomFallback(tiles, horizontal, initialScale);
                Bitmap output = rendered.bitmap;

                String savedName;
                try {
                    boolean encodeJpeg = allJpeg && !rendered.hasAlpha;
                    String fileStem = buildFileStem(baseFileName, horizontal, reversed, useOriginalQuality);
                    savedName = writeBitmapToPictures(appContext, output, fileStem, encodeJpeg);
                } finally {
                    output.recycle();
                }

                StringBuilder msg = new StringBuilder(
                        String.format(
                                Locale.getDefault(),
                                GalleryHelper.safeString("piko_stitch_save_success", "Saved to Pictures/Twitter/%s"),
                                savedName));
                if (rendered.scale < 0.999) {
                    int pct = Math.max(1, (int) Math.round(rendered.scale * 100.0));
                    msg.append('\n').append(
                            String.format(
                                    Locale.getDefault(),
                                    GalleryHelper.safeString("piko_stitch_save_downscaled", "Image exceeded memory limit and was scaled to %d%%"),
                                    pct));
                }

                String finalMsg = msg.toString();
                MAIN_HANDLER.post(() -> callback.onComplete(true, finalMsg));
            } catch (Throwable t) {
                PikoUtils.logger(t);
                String reason = t.getMessage();
                if (reason == null || reason.isEmpty()) {
                    reason = t.getClass().getSimpleName();
                }
                String errMsg = String.format(
                        Locale.getDefault(),
                        GalleryHelper.safeString("piko_stitch_save_failed", "Failed to save stitched image: %s"),
                        reason);
                MAIN_HANDLER.post(() -> callback.onComplete(false, errMsg));
            } finally {
                deleteDirectory(tempDir);
            }
        });
    }

    private static List<TileInfo> downloadAndProbeTiles(
            File tempDir,
            List<String> orderedUrls,
            String quality,
            boolean horizontal) throws IOException {

        ArrayList<File> files = new ArrayList<>(orderedUrls.size());
        ArrayList<BitmapFactory.Options> boundsList = new ArrayList<>(orderedUrls.size());
        int maxWidth = 1;
        int maxHeight = 1;

        for (int i = 0; i < orderedUrls.size(); i++) {
            String rawUrl = orderedUrls.get(i);
            File tileFile = new File(tempDir, "tile_" + i + ".img");
            downloadTileWithFallback(rawUrl, quality, tileFile);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tileFile.getAbsolutePath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                throw new IOException("Invalid image dimensions for tile " + (i + 1));
            }

            if (opts.outWidth > maxWidth) {
                maxWidth = opts.outWidth;
            }
            if (opts.outHeight > maxHeight) {
                maxHeight = opts.outHeight;
            }
            files.add(tileFile);
            boundsList.add(opts);
        }

        ArrayList<TileInfo> tiles = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            BitmapFactory.Options opts = boundsList.get(i);
            int w = opts.outWidth;
            int h = opts.outHeight;
            long alignedW = horizontal ? Math.max(1L, Math.round((double) w * maxHeight / h)) : maxWidth;
            long alignedH = horizontal ? maxHeight : Math.max(1L, Math.round((double) h * maxWidth / w));
            boolean isJpeg = "image/jpeg".equalsIgnoreCase(opts.outMimeType)
                    || "image/jpg".equalsIgnoreCase(opts.outMimeType);
            tiles.add(new TileInfo(files.get(i), w, h, alignedW, alignedH, isJpeg));
        }
        return tiles;
    }

    private static void downloadTileWithFallback(String rawUrl, String quality, File dest) throws IOException {
        ArrayList<String> candidates = new ArrayList<>(3);
        candidates.add(formatImageUrl(rawUrl, quality));
        if ("orig".equals(quality)) {
            String fallbackLarge = formatImageUrl(rawUrl, "4096x4096");
            if (!candidates.contains(fallbackLarge)) {
                candidates.add(fallbackLarge);
            }
        }
        if (!candidates.contains(rawUrl)) {
            candidates.add(rawUrl);
        }

        IOException lastError = null;
        for (String url : candidates) {
            try {
                downloadUrlToFile(url, dest);
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("Failed to download image");
    }

    private static void downloadUrlToFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
                out.flush();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static String formatImageUrl(String rawUrl, String quality) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return rawUrl;
        }
        Matcher queryMatcher = QUERY_NAME_PATTERN.matcher(rawUrl);
        if (queryMatcher.find()) {
            return queryMatcher.replaceFirst("$1" + quality);
        }
        Matcher suffixMatcher = LEGACY_SUFFIX_PATTERN.matcher(rawUrl);
        if (suffixMatcher.find()) {
            return suffixMatcher.replaceFirst(":" + quality);
        }
        return rawUrl;
    }

    private static double computeInitialScale(Context context, List<TileInfo> tiles, boolean horizontal) {
        long rawTotalWidth = 0;
        long rawTotalHeight = 0;
        if (horizontal) {
            for (TileInfo tile : tiles) {
                rawTotalWidth += tile.alignedWidth;
                if (tile.alignedHeight > rawTotalHeight) {
                    rawTotalHeight = tile.alignedHeight;
                }
            }
        } else {
            for (TileInfo tile : tiles) {
                if (tile.alignedWidth > rawTotalWidth) {
                    rawTotalWidth = tile.alignedWidth;
                }
                rawTotalHeight += tile.alignedHeight;
            }
        }

        long maxPixels = resolvePixelBudget(context);
        double scale = 1.0;
        if (rawTotalWidth > MAX_OUTPUT_DIMENSION) {
            scale = Math.min(scale, (double) MAX_OUTPUT_DIMENSION / rawTotalWidth);
        }
        if (rawTotalHeight > MAX_OUTPUT_DIMENSION) {
            scale = Math.min(scale, (double) MAX_OUTPUT_DIMENSION / rawTotalHeight);
        }
        if (rawTotalWidth > 0 && rawTotalHeight > 0 && rawTotalWidth * rawTotalHeight > maxPixels) {
            scale = Math.min(scale, Math.sqrt((double) maxPixels / (rawTotalWidth * rawTotalHeight)));
        }
        return scale;
    }

    private static long resolvePixelBudget(Context context) {
        long availMem = Runtime.getRuntime().maxMemory();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.availMem > 0) {
                    availMem = mi.availMem;
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.min(MAX_PIXEL_BUDGET, Math.max(MIN_PIXEL_BUDGET, availMem / 16L));
    }

    private static StitchLayout buildLayout(List<TileInfo> tiles, boolean horizontal, double scale) {
        ArrayList<Rect> rects = new ArrayList<>(tiles.size());
        if (horizontal) {
            int outH = Math.max(1, (int) Math.round(tiles.get(0).alignedHeight * scale));
            int curX = 0;
            for (TileInfo tile : tiles) {
                int w = Math.max(1, (int) Math.round(tile.alignedWidth * scale));
                rects.add(new Rect(curX, 0, curX + w, outH));
                curX += w;
            }
            return new StitchLayout(Math.max(1, curX), outH, scale, rects);
        } else {
            int outW = Math.max(1, (int) Math.round(tiles.get(0).alignedWidth * scale));
            int curY = 0;
            for (TileInfo tile : tiles) {
                int h = Math.max(1, (int) Math.round(tile.alignedHeight * scale));
                rects.add(new Rect(0, curY, outW, curY + h));
                curY += h;
            }
            return new StitchLayout(outW, Math.max(1, curY), scale, rects);
        }
    }

    private static RenderResult renderWithOomFallback(
            List<TileInfo> tiles,
            boolean horizontal,
            double startScale) {

        double scale = startScale;
        OutOfMemoryError lastOom = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            StitchLayout layout = buildLayout(tiles, horizontal, scale);
            Bitmap output = null;
            try {
                output = Bitmap.createBitmap(layout.outWidth, layout.outHeight, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(output);
                Paint scaledPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                boolean hasAlpha = false;

                for (int i = 0; i < tiles.size(); i++) {
                    TileInfo tile = tiles.get(i);
                    Rect dst = layout.tileRects.get(i);
                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decodeOpts.inSampleSize = calculateInSampleSize(
                            tile.width, tile.height, dst.width(), dst.height());

                    Bitmap tileBitmap = BitmapFactory.decodeFile(tile.file.getAbsolutePath(), decodeOpts);
                    if (tileBitmap == null) {
                        throw new IOException("Failed to decode tile " + (i + 1));
                    }
                    try {
                        if (tileBitmap.hasAlpha()) {
                            hasAlpha = true;
                        }
                        if (tileBitmap.getWidth() == dst.width() && tileBitmap.getHeight() == dst.height()) {
                            canvas.drawBitmap(tileBitmap, dst.left, dst.top, null);
                        } else {
                            canvas.drawBitmap(tileBitmap, null, dst, scaledPaint);
                        }
                    } finally {
                        tileBitmap.recycle();
                    }
                }
                return new RenderResult(output, layout.scale, hasAlpha);
            } catch (OutOfMemoryError oom) {
                if (output != null && !output.isRecycled()) {
                    output.recycle();
                }
                lastOom = oom;
                scale *= 0.72;
            } catch (IOException e) {
                if (output != null && !output.isRecycled()) {
                    output.recycle();
                }
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        throw lastOom != null ? lastOom : new OutOfMemoryError("Unable to allocate stitched bitmap");
    }

    private static int calculateInSampleSize(int srcW, int srcH, int reqW, int reqH) {
        int inSampleSize = 1;
        if (srcH > reqH || srcW > reqW) {
            int halfH = srcH / 2;
            int halfW = srcW / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static String buildFileStem(
            @Nullable String baseFileName,
            boolean horizontal,
            boolean reversed,
            boolean useOriginalQuality) {

        String base;
        if (Pref.nativeDownloaderFileNameType() == 5 || baseFileName == null || baseFileName.isEmpty()) {
            base = NativeDownloader.generateTimestampName();
        } else {
            base = baseFileName;
        }

        StringBuilder sb = new StringBuilder(base);
        sb.append(horizontal ? "_stitch_h" : "_stitch_v");
        if (reversed) {
            sb.append("_rev");
        }
        if (!useOriginalQuality) {
            sb.append("_large");
        }
        String sanitized = Utils.sanitizeFilename(sb.toString());
        return sanitized.isEmpty() ? NativeDownloader.generateTimestampName() + "_stitch" : sanitized;
    }

    private static String writeBitmapToPictures(
            Context context,
            Bitmap bitmap,
            String fileStem,
            boolean encodeJpeg) throws IOException {

        Bitmap.CompressFormat format = encodeJpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
        int compressQuality = encodeJpeg ? 98 : 100;
        String ext = encodeJpeg ? "jpg" : "png";
        String mimeType = encodeJpeg ? "image/jpeg" : "image/png";
        String targetName = fileStem + "." + ext;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, targetName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, PICTURES_TWITTER_REL_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert returned null");
            }

            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null || !bitmap.compress(format, compressQuality, out)) {
                        throw new IOException("Failed to compress stitched image");
                    }
                    out.flush();
                }

                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
                return queryDisplayName(resolver, uri, targetName);
            } catch (Throwable t) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
                if (t instanceof IOException) {
                    throw (IOException) t;
                }
                throw new IOException(t.getMessage(), t);
            }
        }

        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Twitter");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create Pictures/Twitter directory");
        }

        File outFile = new File(dir, targetName);
        int index = 2;
        while (outFile.exists()) {
            outFile = new File(dir, fileStem + "_" + index + "." + ext);
            index++;
        }

        try (FileOutputStream out = new FileOutputStream(outFile)) {
            if (!bitmap.compress(format, compressQuality, out)) {
                throw new IOException("Failed to compress stitched image");
            }
            out.flush();
        }
        MediaScannerConnection.scanFile(context, new String[]{outFile.getAbsolutePath()}, new String[]{mimeType}, null);
        return outFile.getName();
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }
}
