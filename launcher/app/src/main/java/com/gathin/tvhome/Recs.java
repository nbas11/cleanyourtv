package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows other apps publish to the TV provider (preview channels and Continue watching), and the owner's choices
 * about which of them Home shows.
 *
 * Reading needs READ_TV_LISTINGS. Without the privileged ACCESS_ALL_EPG_DATA the provider only hands back rows an app
 * marked searchable, so some apps show nothing here; docs/watch-next.md has the details. The provider refuses a query
 * with a selection, so everything is read whole and filtered here.
 */
final class Recs {
    static final String TAG = "TvRecs";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Key of the pseudo channel that gathers every app's Continue watching rows. */
    static final String WATCH_NEXT = "*watch-next";
    /** Cards kept per row, and rows kept on Home. */
    static final int MAX_PROGRAMS = 24, MAX_ROWS = 10;

    private static final String PREFS = "recommendations";
    private static final String KEY_ON = "on";
    private static final String KEY_OFF = "channels_off";
    private static final String INITIALIZE = "android.media.tv.action.INITIALIZE_PROGRAMS";
    /** Finished episodes linger in Continue watching; a row this far through is not worth resuming. */
    private static final double WATCHED = 0.95;

    private Recs() { }

    static final class Program {
        final long id;
        /** The app that published it: its intent is kept inside this package when it names no target of its own. */
        final String pkg;
        final String title;
        final String poster;
        final String intentUri;
        /** Width over height of the artwork. */
        final float aspect;
        /** The app's own ordering weight, or for Continue watching the time it was last engaged with. */
        final long weight;

        Program(long id, String pkg, String title, String poster, String intentUri, float aspect, long weight) {
            this.id = id;
            this.pkg = pkg;
            this.title = title;
            this.poster = poster;
            this.intentUri = intentUri;
            this.aspect = aspect;
            this.weight = weight;
        }
    }

    static final class Channel {
        /** Package and channel name: stays the same when an app deletes and recreates its channel. */
        final String key;
        /** Key plus the provider's channel id, for a stable item id when two channels share a name. */
        final String uniqueKey;
        final String pkg;
        final String app;
        final String name;
        final List<Program> programs = new ArrayList<>();

        Channel(String key, String uniqueKey, String pkg, String app, String name) {
            this.key = key;
            this.uniqueKey = uniqueKey;
            this.pkg = pkg;
            this.app = app;
            this.name = name;
        }

        boolean isWatchNext() {
            return WATCH_NEXT.equals(key);
        }

        String heading() {
            return app.isEmpty() ? name : app + " \u00B7 " + name;
        }

        /** Changes whenever the row's cards do, so Home can skip a rebuild that would change nothing. */
        String signature() {
            StringBuilder sb = new StringBuilder(uniqueKey).append('|').append(name);
            for (Program p : programs) sb.append('|').append(p.id).append(':').append(p.title).append(':').append(p.poster);
            return sb.toString();
        }
    }

    // ---- the owner's choices ----------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The master switch; the config.xml default until the owner flips it. */
    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, ctx.getResources().getBoolean(R.bool.recommendations));
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
    }

    /** Rows are on unless the owner switched them off, so a channel an app adds later shows up. */
    static boolean channelOn(Context ctx, String key) {
        return !prefs(ctx).getStringSet(KEY_OFF, Collections.<String>emptySet()).contains(key);
    }

    static void setChannelOn(Context ctx, String key, boolean on) {
        Set<String> off = new HashSet<>(prefs(ctx).getStringSet(KEY_OFF, Collections.<String>emptySet()));
        if (on) off.remove(key); else off.add(key);
        prefs(ctx).edit().putStringSet(KEY_OFF, off).apply();
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The channels Home shows: switched on, with something in them, Continue watching first. */
    static List<Channel> visible(Context ctx, List<Channel> all) {
        List<Channel> shown = new ArrayList<>();
        for (Channel c : all) {
            if (!c.programs.isEmpty() && channelOn(ctx, c.key)) shown.add(c);
            if (shown.size() >= MAX_ROWS) break;
        }
        return shown;
    }

    // ---- reading the provider ---------------------------------------------------------------------

    /** Every channel the provider shows this app, Continue watching first. Blocking: call it off the main thread. */
    static List<Channel> load(Context ctx) {
        List<Channel> out = new ArrayList<>();
        if (!hasPermission(ctx)) return out;
        PackageManager pm = ctx.getPackageManager();
        try {
            Map<Long, Channel> byId = readChannels(ctx, pm);
            readPreviewPrograms(ctx, byId);
            Channel watchNext = readWatchNext(ctx);
            for (Channel c : byId.values()) {
                sortPrograms(c);
                out.add(c);
            }
            Collections.sort(out, new Comparator<Channel>() {
                @Override public int compare(Channel a, Channel b) {
                    int byApp = a.app.compareToIgnoreCase(b.app);
                    return byApp != 0 ? byApp : a.uniqueKey.compareTo(b.uniqueKey);
                }
            });
            if (watchNext != null) out.add(0, watchNext);
        } catch (SecurityException e) {
            Log.w(TAG, "the TV provider refused the read: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, "reading the TV provider failed", e);
        }
        int cards = 0;
        for (Channel c : out) {
            cards += c.programs.size();
            Log.d(TAG, "  " + c.key + " (" + c.programs.size() + " cards)");
        }
        Log.i(TAG, "found " + out.size() + " channels, " + cards + " cards");
        return out;
    }

    private static Map<Long, Channel> readChannels(Context ctx, PackageManager pm) {
        Map<Long, Channel> byId = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        String[] projection = {"_id", "package_name", "display_name", "type"};
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, projection, null, null, null)) {
            if (c == null) return byId;
            while (c.moveToNext()) {
                // Live channels (tuner, IPTV) belong to the Live TV app, not to Home's rows.
                if (!TvContract.Channels.TYPE_PREVIEW.equals(c.getString(3))) continue;
                long id = c.getLong(0);
                String pkg = c.getString(1);
                String name = clean(c.getString(2));
                if (pkg == null || name.isEmpty()) continue;
                String key = pkg + "/" + name;
                byId.put(id, new Channel(key, key + "#" + id, pkg, label(pm, labels, pkg), name));
            }
        }
        return byId;
    }

    private static void readPreviewPrograms(Context ctx, Map<Long, Channel> byId) {
        String[] projection = {"_id", "channel_id", "title", "poster_art_uri", "thumbnail_uri", "intent_uri",
                "poster_art_aspect_ratio", "weight"};
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, projection,
                null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Channel channel = byId.get(c.getLong(1));
                if (channel == null) continue;
                String title = clean(c.getString(2));
                String poster = firstNonEmpty(c.getString(3), c.getString(4));
                String intent = c.getString(5);
                if (intent == null || intent.isEmpty() || (title.isEmpty() && poster.isEmpty())) continue;
                channel.programs.add(new Program(c.getLong(0), channel.pkg, title, poster, intent,
                        aspect(c.getInt(6)), c.getLong(7)));
            }
        }
    }

    /** The system's own Continue watching row: every app's Watch Next entries, newest first, finished ones left out. */
    private static Channel readWatchNext(Context ctx) {
        String[] projection = {"_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri",
                "poster_art_aspect_ratio", "last_engagement_time_utc_millis", "last_playback_position_millis",
                "duration_millis"};
        Channel channel = new Channel(WATCH_NEXT, WATCH_NEXT, "", "", "Continue watching");
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection,
                null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String pkg = c.getString(1);
                String title = clean(c.getString(2));
                String poster = firstNonEmpty(c.getString(3), c.getString(4));
                String intent = c.getString(5);
                if (pkg == null || intent == null || intent.isEmpty() || (title.isEmpty() && poster.isEmpty())) continue;
                long position = c.getLong(8), duration = c.getLong(9);
                if (duration > 0 && position >= duration * WATCHED) continue;
                channel.programs.add(new Program(c.getLong(0), pkg, title, poster, intent,
                        aspect(c.getInt(6)), c.getLong(7)));
            }
        }
        if (channel.programs.isEmpty()) return null;
        sortPrograms(channel);
        return channel;
    }

    /** Heaviest (or most recently engaged) first; ties keep the order the app published them in. */
    private static void sortPrograms(Channel c) {
        Collections.sort(c.programs, new Comparator<Program>() {
            @Override public int compare(Program a, Program b) {
                return Long.compare(b.weight, a.weight);
            }
        });
        while (c.programs.size() > MAX_PROGRAMS) c.programs.remove(c.programs.size() - 1);
    }

    /** TvContract's poster_art_aspect_ratio codes as width over height. */
    static float aspect(int code) {
        switch (code) {
            case 1: return 3f / 2f;      // ASPECT_RATIO_3_2
            case 2: return 4f / 3f;      // ASPECT_RATIO_4_3
            case 3: return 1f;           // ASPECT_RATIO_1_1
            case 4: return 2f / 3f;      // ASPECT_RATIO_2_3
            case 5: return 1f / 1.441f;  // ASPECT_RATIO_MOVIE_POSTER
            default: return 16f / 9f;    // ASPECT_RATIO_16_9, and apps that set none
        }
    }

    private static String label(PackageManager pm, Map<String, String> cache, String pkg) {
        String known = cache.get(pkg);
        if (known != null) return known;
        String label;
        try {
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;
        }
        label = Cards.cleanLabel(label, pkg);
        cache.put(pkg, label);
        return label;
    }

    /** Text from another app, drawn on Home: no control or bidi-override characters (as Cards.cleanLabel does for labels). */
    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    // ---- asking apps to publish -------------------------------------------------------------------

    /**
     * Sends the broadcast the stock Home sends after an app is installed, so apps that publish their first channel
     * from it do so without being opened. Returns how many apps were asked.
     */
    static int requestPrograms(Context ctx) {
        int sent = 0;
        try {
            for (ResolveInfo ri : ctx.getPackageManager().queryBroadcastReceivers(new Intent(INITIALIZE), 0)) {
                if (ri.activityInfo == null) continue;
                Intent ask = new Intent(INITIALIZE)
                        .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    ctx.sendBroadcast(ask);
                    sent++;
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not ask " + ri.activityInfo.packageName, e);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not list the apps that publish channels", e);
        }
        return sent;
    }

    /**
     * True for a URI the card artwork may be fetched from: https, an app's own content or resource URI, or plain
     * http only to a private-network address (a Jellyfin or Plex server at home), never to the internet.
     */
    static boolean artworkScheme(Uri uri) {
        String s = uri.getScheme();
        if ("https".equals(s) || "content".equals(s) || "android.resource".equals(s)) return true;
        return "http".equals(s) && privateAddress(uri.getHost());
    }

    /** A dotted IPv4 literal in 10/8, 172.16/12 or 192.168/16. Names are never resolved here, so none qualifies. */
    static boolean privateAddress(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            for (int k = 0; k < parts[i].length(); k++) if (!Character.isDigit(parts[i].charAt(k))) return false;
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 192 && n[1] == 168);
    }
}
