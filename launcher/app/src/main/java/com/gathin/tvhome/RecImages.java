package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Card artwork for the recommendation rows. Apps publish most of it as https links, so it is fetched here, decoded
 * at about the size it is drawn, and kept in a small memory cache (the launcher targets TVs with 1 GB of RAM).
 * Downloaded files are also kept in the app's cache folder (DISK_BYTES, oldest dropped first), so a restart does not
 * fetch them again.
 */
final class RecImages {
    private RecImages() { }

    interface Callback {
        /** On the main thread; bitmap is null when the picture could not be loaded. */
        void onLoaded(Bitmap bitmap);
    }

    private static final int CACHE_BYTES = 8 * 1024 * 1024;
    /** A poster is a few hundred KB; anything bigger is not artwork for a card. */
    private static final int MAX_DOWNLOAD = 3 * 1024 * 1024;
    private static final int CONNECT_MS = 8000, READ_MS = 10000;
    private static final long DISK_BYTES = 24L * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static Bitmap cached(String uri) {
        return uri == null || uri.isEmpty() ? null : CACHE.get(uri);
    }

    static void clear() {
        CACHE.evictAll();
    }

    /** Loads uri in the background; the handle cancels a load whose card was recycled. */
    static Future<?> load(Context ctx, final String uri, final int width, final Callback callback) {
        final Context app = ctx.getApplicationContext();
        return POOL.submit(new Runnable() {
            @Override public void run() {
                final Bitmap bitmap = fetch(app, uri, width);
                if (bitmap != null) CACHE.put(uri, bitmap);
                if (Thread.currentThread().isInterrupted()) return;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        callback.onLoaded(bitmap);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(Context ctx, String uriString, int width) {
        try {
            Uri uri = Uri.parse(uriString);
            if (!Recs.artworkScheme(uri)) {
                Log.d(Recs.TAG, "artwork skipped, scheme " + uri.getScheme() + " host " + uri.getHost());
                return null;
            }
            byte[] data = read(ctx, uri);
            if (data == null) {
                Log.d(Recs.TAG, "artwork empty or refused: " + uri.getHost());
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                diskFile(ctx, uri).delete();   // a damaged or non-image file must not be served again
                Log.d(Recs.TAG, "artwork not an image I can read: " + uri.getHost() + " " + data.length + " bytes, " + bounds.outMimeType);
                return null;
            }
            int sample = 1;
            // Largest power of two that still leaves at least the width the card draws.
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.d(Recs.TAG, "artwork not loaded (" + Uri.parse(uriString).getHost() + "): " + e);
            return null;
        }
    }

    /** Where a downloaded picture is kept: the cache folder, named by a hash of its address. */
    private static File diskFile(Context ctx, Uri uri) {
        String name;
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(uri.toString().getBytes("UTF-8"))) {
                sb.append(String.format("%02x", b));
            }
            name = sb.toString();
        } catch (Exception e) {   // every JVM has SHA-1 and UTF-8
            name = Integer.toHexString(uri.toString().hashCode());
        }
        return new File(new File(ctx.getCacheDir(), "recs"), name);
    }

    private static byte[] readFile(File file) {
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_DOWNLOAD) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            if (off != data.length) return null;
            file.setLastModified(System.currentTimeMillis());   // the least recently used goes first when pruning
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File file, byte[] data) {
        File dir = file.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return;
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
        prune(dir);
    }

    private static synchronized void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BYTES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }

    private static byte[] read(Context ctx, Uri uri) throws IOException {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                File saved = diskFile(ctx, uri);
                byte[] kept = readFile(saved);
                if (kept != null) return kept;
                connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                connection.setConnectTimeout(CONNECT_MS);
                connection.setReadTimeout(READ_MS);
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.d(Recs.TAG, "artwork HTTP " + code + " from " + uri.getHost());
                    return null;
                }
                in = connection.getInputStream();
            } else {
                in = ctx.getContentResolver().openInputStream(uri);
            }
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_DOWNLOAD) return null;
            }
            byte[] data = out.toByteArray();
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) writeFile(diskFile(ctx, uri), data);
            return data;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (connection != null) connection.disconnect();
        }
    }
}
