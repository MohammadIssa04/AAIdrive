package com.musik.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Learns what you like when: songs you skip, play to the end, play again and like – and whether you were in the car (Bluetooth
 * to a car). Sent to the Issa server (/api/feedback), which tunes your radio, mixes and search ranking to the time of day and the car.
 * Turn it off with the "learn_taste" preference.
 */
public final class Feedback {
    private static final Pattern CAR = Pattern.compile("(?i)bmw|mini|car|audi|volvo|vw|volkswagen|mercedes|benz|toyota|ford|skoda|seat|cupra|"
            + "kia|hyundai|tesla|nissan|honda|mazda|peugeot|renault|opel|porsche|lexus|polestar|citroen|fiat|jeep|subaru|suzuki|mitsubishi|"
            + "idrive|carplay|android auto|uconnect|sync|mmi|comand|sensus");
    private static final List<JSONObject> pending = new ArrayList<JSONObject>();

    private static String curId;
    private static JSONObject curSong;
    private static long curDur, listened, maxPos, lastPos, lastTick, lastSeen;
    private static boolean curCar;
    private static long carCheckedAt;
    private static boolean carCached;
    private static long nextFlush;
    private static volatile boolean sending;

    private Feedback() {
    }

    /** Called from the car player's quarter-second ticker (CarLyrics) – looks once a second. */
    public static void tick(CarHost player) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 1000) return;
        long gap = lastTick == 0 ? 0 : now - lastTick;
        lastTick = now;
        try {
            Context ctx = app();
            if (ctx == null || !ctx.getSharedPreferences("musik", 0).getBoolean("learn_taste", true)) return;
            MediaItem item = player.item();
            String id = item == null ? null : item.mediaId;
            long pos = player.position();
            boolean playing = player.playing();
            if (id == null || id.length() == 0) return;
            if (!id.equals(curId)) {
                finish();
                curId = id;
                curSong = song(item, id);
                curDur = duration(player, item);
                curCar = inCar(ctx);
                listened = 0;
                maxPos = pos;
                lastPos = pos;
            } else {
                if (playing) listened += Math.min(gap, 2500);
                if (curDur <= 0) curDur = duration(player, item);
                if (playing && lastPos > 30000 && pos < 5000) { // back to the start of the same song: you wanted it again
                    emit("replay", curSong, curId, listened, curDur, curCar);
                    listened = 0;
                    maxPos = 0;
                }
                if (playing && !curCar) curCar = inCar(ctx);
                maxPos = Math.max(maxPos, pos);
                lastPos = pos;
            }
            lastSeen = now;
            if (now >= nextFlush && !pending.isEmpty()) flush(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** Called when the app builds a music-server request: star / unstar = like / unlike. */
    public static void api(String ep, Map<String, String> params) {
        try {
            if (!"star".equals(ep) && !"unstar".equals(ep) || params == null) return;
            String id = params.get("id");
            if (id == null || id.length() == 0) return;
            Context ctx = app();
            if (ctx == null || !ctx.getSharedPreferences("musik", 0).getBoolean("learn_taste", true)) return;
            JSONObject sg = id.equals(curId) ? curSong : null;
            emit("star".equals(ep) ? "like" : "unlike", sg, id, 0, 0, inCar(ctx));
        } catch (Throwable ignored) {
        }
    }

    /** A button in the car / on the lock screen: "More like this" = you want this kind of music right now. */
    public static void command(String action) {
        try {
            if (!"issa.MORE_LIKE_THIS".equals(action) || curId == null) return;
            Context ctx = app();
            if (ctx == null || !ctx.getSharedPreferences("musik", 0).getBoolean("learn_taste", true)) return;
            emit("more", curSong, curId, listened, curDur, inCar(ctx));
        } catch (Throwable ignored) {
        }
    }

    /** The song that was playing has changed: skipped, partly heard or played to the end? */
    private static void finish() {
        if (curId == null) return;
        if (System.currentTimeMillis() - lastSeen > 15000) return; // the app was asleep – no idea how much was heard
        long d = curDur;
        String kind = null;
        if (listened < 3000) kind = null; // tapping through the queue – not a judgement
        else if (d > 60000 && listened < 30000) kind = "skip";
        else if (d > 0 && (listened >= d * 3 / 4 || (d - maxPos < 15000 && listened >= d / 2))) kind = "complete";
        else if (d > 0 && listened < d / 2) kind = "partial";
        else if (d > 0) kind = "complete";
        if (kind != null) emit(kind, curSong, curId, listened, d, curCar);
    }

    private static void emit(String kind, JSONObject song, String id, long heard, long dur, boolean car) {
        try {
            JSONObject e = new JSONObject();
            e.put("kind", kind);
            e.put("songId", id);
            if (song != null) e.put("song", song);
            e.put("listened", heard);
            e.put("duration", dur);
            e.put("car", car);
            e.put("ts", System.currentTimeMillis());
            synchronized (pending) {
                pending.add(e);
                while (pending.size() > 300) pending.remove(0);
            }
            if (nextFlush == 0 || "like".equals(kind) || "unlike".equals(kind) || "more".equals(kind)) nextFlush = System.currentTimeMillis() + 3000;
        } catch (Throwable ignored) {
        }
    }

    private static void flush(Context ctx) {
        if (sending) return;
        final SharedPreferences prefs = ctx.getSharedPreferences("musik", 0);
        final String user = prefs.getString("user", null), pass = prefs.getString("pass", null);
        if (user == null || pass == null) return;
        String social = prefs.getString("social", null);
        if (social == null || social.trim().length() == 0) social = defaultSocial(prefs.getString("base", "http://192.168.0.14:5274"));
        final String url = trim(social) + "/api/feedback";
        final List<JSONObject> batch;
        synchronized (pending) {
            batch = new ArrayList<JSONObject>(pending);
        }
        sending = true;
        nextFlush = System.currentTimeMillis() + 30000;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    JSONArray arr = new JSONArray();
                    for (JSONObject e : batch) arr.put(e);
                    byte[] body = new JSONObject().put("events", arr).toString().getBytes("UTF-8");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("X-User", user);
                    c.setRequestProperty("X-Pass", pass);
                    OutputStream out = c.getOutputStream();
                    out.write(body);
                    out.close();
                    int code = c.getResponseCode();
                    if (code == 200 || code == 400 || code == 404) { // sent (or an old server that doesn't know it – don't keep trying)
                        synchronized (pending) {
                            pending.removeAll(batch);
                        }
                        nextFlush = System.currentTimeMillis() + (code == 404 ? 30 * 60000 : 20000);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                    sending = false;
                }
            }
        }, "taste-feedback").start();
    }

    /** Bluetooth audio to a car right now? (the car's Bluetooth name: "BMW 12345", "My Car" …) */
    static boolean inCar(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - carCheckedAt < 15000) return carCached;
        carCheckedAt = now;
        boolean car = false;
        try {
            Object am = ctx.getSystemService(Context.AUDIO_SERVICE);
            Object[] devs = (Object[]) am.getClass().getMethod("getDevices", int.class).invoke(am, 2);
            for (Object d : devs) {
                int type = (Integer) d.getClass().getMethod("getType").invoke(d);
                if (type != 8) continue; // TYPE_BLUETOOTH_A2DP
                Object name = d.getClass().getMethod("getProductName").invoke(d);
                if (name != null && CAR.matcher(name.toString()).find()) car = true;
            }
        } catch (Throwable ignored) {
        }
        carCached = car;
        return car;
    }

    private static JSONObject song(MediaItem item, String id) {
        try {
            MediaMetadata md = item.mediaMetadata;
            JSONObject o = new JSONObject();
            o.put("id", id);
            CharSequence t = md.title, a = md.artist, al = md.albumTitle;
            if (t != null) o.put("title", t.toString());
            if (a != null) o.put("artist", a.toString());
            if (al != null) o.put("album", al.toString());
            Bundle ex = md.extras;
            if (ex != null) {
                Bundle b = ex;
                for (String k : new String[]{"albumId", "artistId", "coverArt", "genre"}) {
                    String v = b.getString(k);
                    if (v != null) o.put(k, v);
                }
                int dur = b.getInt("duration", 0);
                if (dur > 0) o.put("duration", dur);
            }
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    private static long duration(CarHost player, MediaItem item) {
        try {
            long d = player.duration();
            if (d > 0) return d;
        } catch (Throwable ignored) {
        }
        try {
            Bundle ex = item.mediaMetadata.extras;
            if (ex != null) return ex.getInt("duration", 0) * 1000L;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String trim(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String defaultSocial(String base) {
        Uri u = Uri.parse(base.trim());
        String scheme = u.getScheme() == null ? "http" : u.getScheme();
        String host = u.getHost() == null ? "192.168.0.14" : u.getHost();
        return scheme + "://" + host + ":5280";
    }

    private static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }
}
