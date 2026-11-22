package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.net.Http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Cover art: memory LRU (bounded by bytes) + disk cache + sampled decoding. */
public final class Images {

    private static final String TAG = "Images";
    private static final long DISK_LIMIT = 40L * 1024 * 1024;
    private static Images instance;

    private final File dir;
    private final android.content.Context context;
    private final float density;
    private float factor = 1f;
    private long factorAt;
    /** Thumbnail URLs YouTube answered 404 for (not every video has every size). */
    private final java.util.Set<String> missing = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long memoryLimit;
    private long memoryUsed;
    private final LinkedHashMap<String, Bitmap> memory = new LinkedHashMap<String, Bitmap>(64, 0.75f, true);
    /** LIFO: the images just scrolled into view load first. */
    private final ExecutorService pool;

    private Images(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
        dir = new File(context.getCacheDir(), "img");
        dir.mkdirs();
        long max = Runtime.getRuntime().maxMemory();
        memoryLimit = Math.max(2L * 1024 * 1024, max / 8);
        pool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>() {
            @Override
            public boolean offer(Runnable r) {
                return super.offerFirst(r);
            }
        });
    }

    public static synchronized Images get(Context context) {
        if (instance == null) instance = new Images(context.getApplicationContext());
        return instance;
    }

    /**
     * Pixels to request for an image shown {@code dp} wide, by the quality chosen for the
     * current network (Wi-Fi or mobile data), at most {@code max}.
     */
    public int px(float dp, int max) {
        long now = System.currentTimeMillis();
        if (now - factorAt > 3000 || factorAt == 0) {
            factorAt = now;
            com.ytmlegacy.android.data.Prefs prefs = com.ytmlegacy.android.data.Prefs.get(context);
            int q = onWifi() ? prefs.imageQualityWifi() : prefs.imageQualityMobile();
            factor = com.ytmlegacy.android.data.Prefs.IMAGE_FACTORS[Math.max(0, Math.min(3, q))];
        }
        return Math.max(48, Math.min(max, (int) (dp * density * factor)));
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n == null || n.getType() != android.net.ConnectivityManager.TYPE_MOBILE;
    }

    /** Smaller stand-ins for a YouTube video thumbnail that may not exist in this size. */
    private static String smallerVideoThumb(String url) {
        if (url.endsWith("/maxresdefault.jpg")) return url.replace("/maxresdefault.jpg", "/sddefault.jpg");
        if (url.endsWith("/sddefault.jpg")) return url.replace("/sddefault.jpg", "/hqdefault.jpg");
        return null;
    }

    /** The URL of the image at about {@code px} pixels. */
    public static String sized(String url, int px) {
        if (url == null || url.length() == 0) return "";
        if (url.startsWith("file://")) return url;
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            int eq = url.lastIndexOf('=');
            int slash = url.lastIndexOf('/');
            String base = eq > slash ? url.substring(0, eq) : url;
            // "-rj" asks for JPEG: old Android cannot decode WebP.
            return base + "=w" + px + "-h" + px + "-l90-rj";
        }
        if (url.contains("i.ytimg.com/vi")) {
            int q = url.indexOf('?');
            String base = q < 0 ? url : url.substring(0, q);
            base = base.replace("/vi_webp/", "/vi/").replace(".webp", ".jpg");
            int last = base.lastIndexOf('/');
            String file = px <= 200 ? "mqdefault.jpg" : px <= 480 ? "hqdefault.jpg"
                    : px <= 640 ? "sddefault.jpg" : "maxresdefault.jpg";
            return last > 0 ? base.substring(0, last + 1) + file : base;
        }
        return url;
    }

    public void load(final ImageView view, String url, final int px, final int placeholder) {
        final String sized = sized(url, px);
        view.setTag(R.id.tag_image_url, sized);
        Bitmap cached = sized.length() == 0 ? null : fromMemory(key(sized, px));
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(placeholder);
        if (sized.length() == 0) return;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                if (!sized.equals(view.getTag(R.id.tag_image_url))) return; // recycled meanwhile
                final Bitmap b = loadSync(sized, px);
                if (b == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sized.equals(view.getTag(R.id.tag_image_url))) view.setImageBitmap(b);
                    }
                });
            }
        });
    }

    /** Blocking load (background threads only). {@code url} may be unsized. */
    public Bitmap loadSync(String url, int px) {
        String sized = url.contains("=w") || url.contains("default.jpg") ? url : sized(url, px);
        if (sized.length() == 0) return null;
        String key = key(sized, px);
        Bitmap b = fromMemory(key);
        if (b != null) return b;
        if (sized.startsWith("file://")) {
            File local = new File(sized.substring(7));
            if (!local.exists()) return null;
            try {
                b = decode(local, px, false);
            } catch (OutOfMemoryError e) {
                clearMemory();
                return null;
            }
            if (b != null) toMemory(key, b);
            return b;
        }
        File file = new File(dir, hash(sized));
        try {
            if (!file.exists()) {
                String smallerFirst = missing.contains(sized) ? smallerVideoThumb(sized) : null;
                if (smallerFirst != null) return loadSync(smallerFirst, px);
                byte[] data;
                try {
                    data = Http.get(sized, null, 8000, 15000, 4 * 1024 * 1024);
                } catch (Http.StatusException e) {
                    // Not every video has its large thumbnails: step down a size.
                    String smaller = e.status == 404 ? smallerVideoThumb(sized) : null;
                    if (smaller == null) throw e;
                    missing.add(sized);
                    Bitmap fallback = loadSync(smaller, px);
                    if (fallback != null) toMemory(key, fallback);
                    return fallback;
                }
                File tmp = new File(file.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                tmp.renameTo(file);
                trimDisk();
            } else {
                file.setLastModified(System.currentTimeMillis());
            }
            b = decode(file, px, true);
            if (b != null && letterboxed(sized)) b = cropBars(b);
            if (b != null) toMemory(key, b);
            return b;
        } catch (IOException e) {
            Log.i(TAG, "image failed: " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            clearMemory();
            return null;
        }
    }

    private static Bitmap decode(File file, int px, boolean deleteBroken) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) {
            if (deleteBroken) file.delete();
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = px <= 400 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
        if (b == null && deleteBroken) file.delete();
        return b;
    }

    /** YouTube's 4:3 video thumbnails carry black bars around a 16:9 picture. */
    private static boolean letterboxed(String url) {
        return url.contains("ytimg.com") && (url.contains("hqdefault") || url.contains("sddefault")
                || url.endsWith("/0.jpg"));
    }

    private static Bitmap cropBars(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (h == 0 || Math.abs(w * 3 - h * 4) > w / 20) return b; // not 4:3
        int bar = h / 8;
        Bitmap cropped = Bitmap.createBitmap(b, 0, bar, w, h - 2 * bar);
        if (cropped != b) b.recycle();
        return cropped;
    }

    private static String key(String url, int px) {
        return px + "|" + url;
    }

    private synchronized Bitmap fromMemory(String key) {
        return memory.get(key);
    }

    private synchronized void toMemory(String key, Bitmap b) {
        Bitmap old = memory.put(key, b);
        if (old != null) memoryUsed -= bytes(old);
        memoryUsed += bytes(b);
        java.util.Iterator<Map.Entry<String, Bitmap>> it = memory.entrySet().iterator();
        while (memoryUsed > memoryLimit && it.hasNext()) {
            Map.Entry<String, Bitmap> e = it.next();
            if (e.getValue() == b) continue;
            memoryUsed -= bytes(e.getValue());
            it.remove();
        }
    }

    public synchronized void clearMemory() {
        memory.clear();
        memoryUsed = 0;
        factorAt = 0; // re-read the quality setting
    }

    public void clearDisk() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        clearMemory();
    }

    public long diskBytes() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    private static long bytes(Bitmap b) {
        return (long) b.getRowBytes() * b.getHeight();
    }

    private int writes;

    private void trimDisk() {
        if (++writes % 20 != 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (File f : files) {
            if (total <= DISK_LIMIT * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
