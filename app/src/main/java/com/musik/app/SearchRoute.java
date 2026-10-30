package com.musik.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Searches (search3) go through the Issa server (port 5280) instead of straight to the music server: it fixes typos and other
 * spellings before answering ("darke" → Drake). Only when that server answers – otherwise searches go straight to the music
 * server like before, and it is checked again a minute later.
 */
public final class SearchRoute {
    private static volatile long okUntil = 0, nextCheck = 0;
    private static volatile String checkedFor = null;

    private SearchRoute() {
    }

    /** The root to use for this call: the Issa server for searches when it is up, otherwise the root as it was. */
    public static String root(String root, String ep) {
        try {
            if (!"search3".equals(ep) || root == null) return root;
            Context ctx = app();
            if (ctx == null) return root;
            SharedPreferences prefs = ctx.getSharedPreferences("musik", 0);
            if (!prefs.getBoolean("smart_search", true)) return root;
            String base = trim(prefs.getString("base", "http://192.168.0.14:5274"));
            if (!trim(root).equals(base)) return root; // e.g. the direct Navidrome connection – leave it alone
            String social = prefs.getString("social", null);
            social = trim(social != null && social.trim().length() > 0 ? social : defaultSocial(base));
            if (social.equals(base)) return root;
            long now = System.currentTimeMillis();
            if (social.equals(checkedFor) && now < okUntil) return social;
            if (now >= nextCheck) check(social);
            return root;
        } catch (Throwable e) {
            return root;
        }
    }

    private static String trim(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String defaultSocial(String base) {
        Uri u = Uri.parse(base);
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

    /** Is the Issa server there, and new enough to do the smart search? (/api/smartsearch answers {"smartSearch": 1}) */
    private static void check(final String social) {
        nextCheck = System.currentTimeMillis() + 60000;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                boolean ok = false;
                try {
                    c = (HttpURLConnection) new URL(social + "/api/smartsearch").openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(4000);
                    if (c.getResponseCode() == 200) {
                        java.io.InputStream in = c.getInputStream();
                        byte[] buf = new byte[256];
                        int n = in.read(buf);
                        in.close();
                        ok = n > 0 && new String(buf, 0, n, "UTF-8").contains("smartSearch");
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                checkedFor = social;
                okUntil = ok ? System.currentTimeMillis() + 5 * 60000 : 0;
                nextCheck = System.currentTimeMillis() + (ok ? 5 * 60000 : 60000);
            }
        }, "search-route").start();
    }
}
