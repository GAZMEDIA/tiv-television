package com.tivtelevision.live;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the channel settings from the link page on the website, so the stream
 * link (and, optionally, the programme schedule) can change without a new app.
 *
 * The page can be a normal web page containing lines like:
 *
 *   TIV_STREAM = https://example.trycloudflare.com/hls/live.m3u8
 *   TIV_SHARE = https://facebook.com/tivtelevisionkase
 *   TIV_PROGRAMME = 06:00 | Morning Devotion | Prayer and worship | Faith
 *   TIV_PROGRAMME = 07:00 | Tiv News at Seven | The morning news | News
 *
 * or a JSON document: {"stream": "...", "share": "...", "schedule": [{time,title,desc,tag}]}
 *
 * The last good settings are saved on the device and used when the page can't be reached.
 */
final class StreamConfig {

    private static final String PREFS = "tiv_config";
    private static final String KEY_JSON = "config_json";

    private static final Pattern STREAM = Pattern.compile("TIV_STREAM\\s*[=:]\\s*(https?://[^\\s\"'<>]+)");
    private static final Pattern SHARE = Pattern.compile("TIV_SHARE\\s*[=:]\\s*(https?://[^\\s\"'<>]+)");
    private static final Pattern PROGRAMME = Pattern.compile("TIV_PROGRAMME\\s*[=:]\\s*([^\\n]+)");

    private StreamConfig() {}

    /** Settings saved from the last successful read, or the built-in fallback. */
    static JSONObject cached(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_JSON, null);
        if (saved != null) {
            try {
                JSONObject o = new JSONObject(saved);
                if (o.optString("stream").length() > 0) return o;
            } catch (Exception ignored) {}
        }
        JSONObject o = new JSONObject();
        try { o.put("stream", BuildConfig.FALLBACK_STREAM); } catch (Exception ignored) {}
        return o;
    }

    static void save(Context ctx, JSONObject cfg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, cfg.toString()).apply();
    }

    /** Downloads and parses the link page. Returns null on any failure. Call off the main thread. */
    static JSONObject fetch() {
        HttpURLConnection c = null;
        try {
            // Cache-buster so phone networks and the website cache don't serve an old link.
            String url = BuildConfig.CONFIG_URL + (BuildConfig.CONFIG_URL.contains("?") ? "&" : "?")
                    + "t=" + (System.currentTimeMillis() / 1000);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "TIVTelevisionApp/" + BuildConfig.VERSION_NAME);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            String body = read(c.getInputStream(), 512 * 1024);
            return parse(body);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static JSONObject parse(String body) {
        if (body == null) return null;
        String t = body.trim();
        try {
            if (t.startsWith("{")) {
                JSONObject o = new JSONObject(t);
                return o.optString("stream").startsWith("http") ? o : null;
            }
            // Web page: turn line breaks and paragraphs into new lines, drop tags, decode common entities.
            String text = t.replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</h\\d>", "\n")
                    .replaceAll("<[^>]+>", " ")
                    .replace("&amp;", "&").replace("&#038;", "&").replace("&nbsp;", " ")
                    .replace("&#8211;", "-").replace("&#8217;", "'").replace("&rsquo;", "'")
                    .replace("&#039;", "'").replace("&quot;", "\"").replace("&#8220;", "\"").replace("&#8221;", "\"");
            Matcher m = STREAM.matcher(text);
            if (!m.find()) return null;
            JSONObject o = new JSONObject();
            o.put("stream", m.group(1));
            Matcher s = SHARE.matcher(text);
            if (s.find()) o.put("share", s.group(1));
            JSONArray schedule = new JSONArray();
            Matcher p = PROGRAMME.matcher(text);
            while (p.find()) {
                String[] parts = p.group(1).split("\\|");
                if (parts.length < 2) continue;
                String time = parts[0].trim();
                if (!time.matches("\\d{1,2}:\\d{2}")) continue;
                if (time.length() == 4) time = "0" + time;
                JSONObject item = new JSONObject();
                item.put("time", time);
                item.put("title", parts[1].trim());
                item.put("desc", parts.length > 2 ? parts[2].trim() : "");
                item.put("tag", parts.length > 3 ? parts[3].trim() : "");
                schedule.put(item);
            }
            if (schedule.length() > 0) o.put("schedule", schedule);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
