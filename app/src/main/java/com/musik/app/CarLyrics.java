package com.musik.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line being sung right now, shown on the car's display (the album row) – iDrive, AAIdrive and plain Bluetooth.
 * Only while the phone plays to a car over Bluetooth.
 * Synced lyrics come from lrclib.net (free, no account). Songs without synced lyrics keep the normal "Next: song" row.
 * Turn it off with the "car_lyrics" preference.
 */
public final class CarLyrics {
    private static final Map<String, long[]> TIMES = Collections.synchronizedMap(new HashMap<String, long[]>());
    private static final Map<String, String[]> TEXTS = Collections.synchronizedMap(new HashMap<String, String[]>());
    private static final Map<String, Long> MISSING = Collections.synchronizedMap(new HashMap<String, Long>()); // no synced lyrics: try again after 30 min
    private static final Set<String> LOADING = Collections.synchronizedSet(new HashSet<String>());
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");
    private static final long EARLY_MS = 350; // show a line a little before it is sung, so you can read it in time

    private static WeakReference<CarHost> car = new WeakReference<CarHost>(null);
    private static Handler handler;
    private static volatile String curKey;
    private static volatile String shown;
    private static String lastT, lastA, lastKey;
    private static long lastRefresh;
    private static boolean wasInCar;

    private CarLyrics() {
    }

    /** Called from the car player's getMediaMetadata(): the line to show now, or null for the normal row. */
    public static String line(CarHost player, CharSequence title, CharSequence artist) {
        try {
            if (player == null) return null;
            startTicker(player); // also drives Feedback (what you skip / like), with or without car lyrics
            // only in the car: on the phone the lyrics are in the app, and changing the metadata every line redraws the
            // notification, the widget and the cover for nothing
            if (title == null || !showNow(player)) {
                curKey = null;
                shown = null;
                return null;
            }
            String t = title.toString(), a = artist == null ? "" : artist.toString();
            if (!t.equals(lastT) || !a.equals(lastA)) { // getMediaMetadata() is asked often – work the key out once per song
                lastKey = key(t, a);
                lastT = t;
                lastA = a;
            }
            String key = lastKey;
            curKey = key;
            if (!TIMES.containsKey(key)) {
                load(key, t, a);
                shown = null;
                return null;
            }
            String s = at(key, position(player));
            shown = s;
            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Lyrics on the car's display right now? Turned on ("car_lyrics") and the phone plays to a car over Bluetooth. */
    private static boolean showNow(CarHost player) {
        try {
            Context ctx = player.context();
            if (ctx == null) return false;
            boolean on = ctx.getSharedPreferences("musik", 0).getBoolean("car_lyrics", true) && Feedback.inCar(ctx);
            wasInCar = on;
            return on;
        } catch (Throwable e) {
            return false;
        }
    }

    private static long position(CarHost player) {
        return player.position();
    }

    private static boolean playing(CarHost player) {
        return player.playing();
    }

    private static void refresh(CarHost player) {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 1000) return; // at most once a second (the next tick tries again)
        lastRefresh = now;
        try {
            player.refresh();
        } catch (Throwable ignored) {
        }
    }

    /** The line at this moment – nothing during a long instrumental part. */
    static String at(String key, long pos) {
        long[] t = TIMES.get(key);
        String[] x = TEXTS.get(key);
        if (t == null || x == null || t.length == 0) return null;
        long p = pos + EARLY_MS;
        int i = -1;
        for (int k = 0; k < t.length && t[k] <= p; k++) i = k;
        if (i < 0) return null;
        String s = x[i].trim();
        if (s.length() == 0) return null;
        long next = i + 1 < t.length ? t[i + 1] : Long.MAX_VALUE;
        if (next - t[i] > 12000 && p - t[i] > 8000) return null; // the line was sung a while ago – back to "Next: song"
        return s;
    }

    /** While lyrics are on the car's display, every quarter second: when the sung line changes, the car gets new metadata.
     *  Otherwise once a second (Feedback looks at what's playing). */
    private static void startTicker(CarHost player) {
        if (car.get() == player && handler != null) return;
        car = new WeakReference<CarHost>(player);
        if (handler != null) return;
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                CarHost p = car.get();
                if (p == null) {
                    handler = null;
                    return;
                }
                Feedback.tick(p);
                boolean active = false;
                try {
                    String key = curKey;
                    Context svc = p.context();
                    if (key != null && svc instanceof Context && !Feedback.inCar((Context) svc)) {
                        refresh(p); // out of the car: back to the normal album row
                    } else if (key != null && TIMES.containsKey(key) && playing(p)) {
                        active = true;
                        String s = at(key, position(p));
                        String old = shown;
                        if (s == null ? old != null : !s.equals(old)) refresh(p);
                    } else if (key == null && !wasInCar && System.currentTimeMillis() - lastRefresh > 15000) {
                        // got into the car in the middle of a song: start the lyrics without waiting for the next song
                        if (svc instanceof Context && ((Context) svc).getSharedPreferences("musik", 0).getBoolean("car_lyrics", true)
                                && Feedback.inCar((Context) svc) && playing(p)) refresh(p);
                    }
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, active ? 250 : 1000); // a quarter second only while lyrics are on the car's display
            }
        }, 250);
    }

    static String plain(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String cleanTitle(String t) {
        String s = t.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "");
        s = s.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|mix|live|mono|stereo|from).*$", "");
        s = s.trim();
        return s.length() == 0 ? t.trim() : s;
    }

    static String mainArtist(String a) {
        String s = a.split("(?i),|&|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sx\\s|\\swith\\s")[0].trim();
        return s.length() == 0 ? a.trim() : s;
    }

    private static String key(String title, String artist) {
        return plain(cleanTitle(title)) + "|" + plain(mainArtist(artist));
    }

    private static void load(final String key, final String title, final String artist) {
        Long miss = MISSING.get(key);
        if (miss != null && System.currentTimeMillis() - miss < 30 * 60 * 1000L) return;
        if (!LOADING.add(key)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String lrc = fetch(cleanTitle(title), mainArtist(artist));
                    if (lrc != null && parse(key, lrc)) {
                        MISSING.remove(key);
                        final CarHost p = car.get();
                        Handler h = handler;
                        if (p != null && h != null) {
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    refresh(p);
                                }
                            });
                        }
                    } else {
                        MISSING.put(key, System.currentTimeMillis());
                    }
                } catch (Throwable e) {
                    MISSING.put(key, System.currentTimeMillis());
                } finally {
                    LOADING.remove(key);
                }
            }
        }, "car-lyrics").start();
    }

    private static String fetch(String title, String artist) throws Exception {
        String q = "track_name=" + URLEncoder.encode(title, "UTF-8") + "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
        String body = get("https://lrclib.net/api/get?" + q);
        if (body != null) {
            String s = new JSONObject(body).optString("syncedLyrics", "");
            if (s.length() > 0 && !"null".equals(s)) return s;
        }
        body = get("https://lrclib.net/api/search?" + q);
        if (body != null) {
            JSONArray arr = new JSONArray(body);
            String want = plain(title);
            String fallback = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String s = o.optString("syncedLyrics", "");
                if (s.length() == 0 || "null".equals(s)) continue;
                if (plain(cleanTitle(o.optString("trackName", ""))).equals(want)) return s;
                if (fallback == null) fallback = s;
            }
            return fallback;
        }
        return null;
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "IssaMusic/4.8 (car lyrics)");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "[01:02.50]text" lines (several time tags on one line are fine) → sorted times + texts. */
    static boolean parse(String key, String lrc) {
        final List<long[]> rows = new ArrayList<long[]>();
        final List<String> texts = new ArrayList<String>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            Matcher m = TAG.matcher(line);
            List<Long> ts = new ArrayList<Long>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                String f = m.group(3);
                if (f != null) {
                    String f3 = (f + "00").substring(0, 3);
                    ms += Long.parseLong(f3);
                }
                ts.add(ms);
                end = m.end();
            }
            if (ts.isEmpty()) continue;
            String text = line.substring(end).trim();
            for (Long t : ts) {
                rows.add(new long[]{t, texts.size()});
                texts.add(text);
            }
        }
        if (rows.isEmpty()) return false;
        Collections.sort(rows, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;
            }
        });
        long[] t = new long[rows.size()];
        String[] x = new String[rows.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = rows.get(i)[0];
            x[i] = texts.get((int) rows.get(i)[1]);
        }
        TEXTS.put(key, x);
        TIMES.put(key, t);
        return true;
    }
}
