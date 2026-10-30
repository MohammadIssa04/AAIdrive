package com.musik.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Issa Music for Android TV: a full-screen page from your own server (/tv), steered with the remote's arrow keys.
 * All of the look lives on the server, so it updates without reinstalling the app.
 * The page tells this app what is playing (IssaTv.state), which becomes a media session: the TV's now-playing card shows the
 * song, the artist and the cover, and the TV's own play / pause / skip buttons control the page.
 */
public class TvActivity extends Activity {
    private WebView web;
    private boolean showingError;
    private String url, server;
    private MediaSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String shownTitle = "", shownCover = "";
    private Bitmap art;
    private String lastQueue = "";
    private final Map<String, Bitmap> icons = new HashMap<String, Bitmap>();   // covers of the coming songs, for the queue

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        server = serverUrl();
        url = server + "/tv";
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // the music starts without a tap
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "IssaTv");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                if (u != null && u.startsWith("issa-tv://exit")) {
                    finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap icon) {
                if (u != null && u.startsWith("http")) showingError = false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String description, String failingUrl) {
                showingError = true;
                String html = "<html><body style='background:#0b0b12;color:#fff;font-family:sans-serif;padding:8vh 8vw'>"
                        + "<h1 style='font-size:5vh'>Kan inte nå servern</h1>"
                        + "<p style='font-size:3vh;color:#aaa'>Försökte: " + Uri.encode(String.valueOf(failingUrl), ":/.-_?=&") + "</p>"
                        + "<p style='font-size:3vh;color:#aaa'>Kontrollera att TV:n är på samma wifi som servern.</p>"
                        + "<p style='font-size:3.4vh'>Tryck OK för att försöka igen. Tillbaka avslutar.</p></body></html>";
                v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            }
        });
        web.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);
        setupSession();
        web.loadUrl(url);
        web.requestFocus();
    }

    /** The media session: what the TV shows on its now-playing card, and what its play / pause / skip buttons do. */
    @SuppressWarnings("deprecation")
    private void setupSession() {
        session = new MediaSession(this, "IssaTv");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                js("tvMedia('play')");
            }

            @Override
            public void onPause() {
                js("tvMedia('pause')");
            }

            @Override
            public void onSkipToNext() {
                js("tvMedia('next')");
            }

            @Override
            public void onSkipToPrevious() {
                js("tvMedia('prev')");
            }

            @Override
            public void onSeekTo(long pos) {
                js("tvSeek(" + pos + ")");
            }
        });
        session.setActive(true);
        setPlayback(false, 0);
    }

    private void setPlayback(boolean playing, long pos) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setActiveQueueItemId(0)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f).build());
    }

    /** Called by the page (on a background thread). */
    private class Bridge {
        @JavascriptInterface
        public void state(final String title, final String artist, final String album, final String cover, final long duration, final long pos, final boolean playing) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    update(title, artist, album, cover, duration, pos, playing);
                }
            });
        }

        /** The song playing and the ones coming, as JSON [{t: title, a: artist, c: cover address}, ...]. */
        @JavascriptInterface
        public void queue(final String json) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    setQueue(json);
                }
            });
        }
    }

    /**
     * The media queue (the TV's home screen can show what comes next) and the same thing as plain extras:
     * nextTitle / nextArtist. Covers of the next songs are fetched once and kept.
     */
    private void setQueue(String json) {
        if (session == null || json == null) return;
        lastQueue = json;
        buildQueue();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && i < 4; i++) {
                final String c = a.getJSONObject(i).optString("c");
                if (c.length() > 0 && !icons.containsKey(c)) {
                    icons.put(c, null);   // asked for
                    loadIcon(c);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void buildQueue() {
        if (session == null || lastQueue.length() == 0) return;
        try {
            JSONArray a = new JSONArray(lastQueue);
            List<MediaSession.QueueItem> items = new ArrayList<MediaSession.QueueItem>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                MediaDescription.Builder d = new MediaDescription.Builder()
                        .setMediaId(String.valueOf(i))
                        .setTitle(o.optString("t"))
                        .setSubtitle(o.optString("a"));
                Bitmap icon = icons.get(o.optString("c"));
                if (icon != null) d.setIconBitmap(icon);
                items.add(new MediaSession.QueueItem(d.build(), i));
            }
            session.setQueue(items);
            session.setQueueTitle("Nästa låtar");
            Bundle extras = new Bundle();
            if (a.length() > 1) {
                extras.putString("nextTitle", a.getJSONObject(1).optString("t"));
                extras.putString("nextArtist", a.getJSONObject(1).optString("a"));
            }
            session.setExtras(extras);
        } catch (Throwable ignored) {
        }
    }

    private void loadIcon(final String path) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    String full = path.startsWith("http") ? path : server + path;
                    c = (HttpURLConnection) new URL(full).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    String cookie = CookieManager.getInstance().getCookie(server);
                    if (cookie != null) c.setRequestProperty("Cookie", cookie);
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp == null) return;
                    final Bitmap small = Bitmap.createScaledBitmap(bmp, 192, 192, true);   // small enough for the media queue
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            icons.put(path, small);
                            buildQueue();
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "tv-icon").start();
    }

    private void update(String title, String artist, String album, String cover, long duration, long pos, boolean playing) {
        if (session == null) return;
        String c = cover == null ? "" : cover;
        String key = title + "|" + artist + "|" + duration;
        if (!key.equals(shownTitle) || !c.equals(shownCover)) {
            boolean newCover = !c.equals(shownCover);
            if (newCover) {
                shownCover = c;
                art = null;
            }
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(m.build());
            shownTitle = key;
            if (newCover) loadCover(c);
        }
        setPlayback(playing, pos);
    }

    /** The cover for the TV's card: fetched with the page's login cookie. */
    private void loadCover(final String path) {
        if (path.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    String full = path.startsWith("http") ? path : server + path;
                    c = (HttpURLConnection) new URL(full).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    String cookie = CookieManager.getInstance().getCookie(server);
                    if (cookie != null) c.setRequestProperty("Cookie", cookie);
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp == null) return;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!path.equals(shownCover) || session == null) return;
                            art = bmp;
                            MediaMetadata cur = session.getController().getMetadata();
                            MediaMetadata.Builder m = cur == null ? new MediaMetadata.Builder() : new MediaMetadata.Builder(cur);
                            session.setMetadata(m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp).build());
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "tv-cover").start();
    }

    /** The server's address: what the phone app uses, with the social server's port; otherwise the home server. */
    private String serverUrl() {
        try {
            SharedPreferences p = getSharedPreferences("musik", 0);
            String social = p.getString("social", null);
            if (social != null && social.trim().length() > 0) return trim(social);
            String base = p.getString("base", null);
            if (base != null && base.trim().length() > 0) {
                Uri u = Uri.parse(base.trim());
                String scheme = u.getScheme() == null ? "http" : u.getScheme();
                if (u.getHost() != null) return scheme + "://" + u.getHost() + ":5280";
            }
        } catch (Throwable ignored) {
        }
        return "http://192.168.0.14:5280";
    }

    private static String trim(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void js(final String code) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (showingError && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            showingError = false;
            web.loadUrl(url);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                js("tvMedia('playpause')");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                js("tvMedia('next')");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                js("tvMedia('prev')");
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                js("tvMedia('ff')");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                js("tvMedia('rw')");
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        if (showingError) {
            finish();
        } else {
            js("tvBackOrExit()");   // goes back one screen, or leaves the app from the start screen
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();   // keep the login
    }

    /** The page keeps running when the TV's home screen is in front, so the next song can start by itself. */
    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) {
            web.resumeTimers();
            web.dispatchWindowVisibilityChanged(android.view.View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (web != null) {
            web.loadUrl("about:blank");   // stops the music with the app
            web.destroy();
        }
        super.onDestroy();
    }
}
