package com.tivtelevision.live;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * Shows the TIV Television phone app (index.html) on phones and tablets, and the
 * TV app (tv.html) on Android TV. Both pages are bundled inside the APK; only the
 * live stream comes from the internet.
 */
public class MainActivity extends Activity {

    /** Pages are served from this address so they behave like a normal https website. */
    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/web/";

    private static final long STARTUP_WAIT_MS = 3500;          // max wait for the link page at launch
    private static final long REFRESH_EVERY_MS = 2 * 60 * 1000; // re-check the link page while open

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private boolean isTv;
    private volatile JSONObject config;
    private boolean pageLoaded;
    private boolean resumed;
    private volatile long lastPageRefresh;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = detectTv();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (isTv) immersive(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " TIVTelevisionApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "TIVApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        config = StreamConfig.cached(this);
        refreshConfig();
        main.postDelayed(this::loadPage, STARTUP_WAIT_MS);
    }

    private boolean detectTv() {
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature("android.software.leanback_only");
    }

    private void loadPage() {
        if (pageLoaded || isFinishing()) return;
        pageLoaded = true;
        main.removeCallbacksAndMessages(null);
        web.loadUrl(BASE + (isTv ? "tv.html" : "index.html"));
        web.requestFocus();
        scheduleRefresh();
    }

    /** Reads the link page in the background and applies any new stream link. */
    private void refreshConfig() {
        new Thread(() -> {
            JSONObject fresh = StreamConfig.fetch();
            if (fresh == null) return;
            StreamConfig.save(this, fresh);
            main.post(() -> {
                String oldStream = config.optString("stream");
                config = fresh;
                if (!pageLoaded) {
                    loadPage();
                } else if (!fresh.optString("stream").equals(oldStream)) {
                    web.evaluateJavascript("window.tivSwitchStream&&window.tivSwitchStream("
                            + JSONObject.quote(fresh.optString("stream")) + ")", null);
                }
            });
        }, "tiv-config").start();
    }

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            if (resumed) refreshConfig();
            main.postDelayed(this, REFRESH_EVERY_MS);
        }
    };

    private void scheduleRefresh() {
        main.removeCallbacks(periodic);
        main.postDelayed(periodic, REFRESH_EVERY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        if (isTv || customView != null) immersive(true);
        if (pageLoaded) refreshConfig();
    }

    @Override
    protected void onPause() {
        resumed = false;
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Make sure the remote's arrow keys always reach the page.
        if (isTv && web != null && !web.hasFocus() && customView == null) web.requestFocus();
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (!pageLoaded) {
            super.onBackPressed();
            return;
        }
        // Let the page close its own panels first (TV info bar / programme guide).
        web.evaluateJavascript("(window.tivBack&&window.tivBack())?1:0", result -> {
            if (!"1".equals(result)) finish();
        });
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        if (!isTv) {
            immersive(false);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        web.requestFocus();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {}
    }

    /** Serves the bundled pages, and opens outside links (WhatsApp, YouTube, phone) in their own apps. */
    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || !path.startsWith("/web/") || path.contains("..")) return null;
            try {
                InputStream in = getAssets().open(path.substring(1));
                return new WebResourceResponse(mime(path), "UTF-8", in);
            } catch (Exception e) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            openExternal(u);
            return true;
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u = Uri.parse(url);
            if (HOST.equals(u.getHost())) return false;
            openExternal(u);
            return true;
        }
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Full-screen video on phones (the player's own full-screen button). */
    private class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            immersive(true);
            if (!isTv) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    /** Functions the pages can call as window.TIVApp.* */
    private class Bridge {
        @JavascriptInterface
        public String config() {
            return config.toString();
        }

        @JavascriptInterface
        public boolean isTv() {
            return isTv;
        }

        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(i, "Share TIV Television"));
                } catch (ActivityNotFoundException ignored) {}
            });
        }

        /** The page lost the stream: check whether the link changed (at most every 20 seconds). */
        @JavascriptInterface
        public void refresh() {
            long now = System.currentTimeMillis();
            if (now - lastPageRefresh < 20000) return;
            lastPageRefresh = now;
            main.post(MainActivity.this::refreshConfig);
        }
    }
}
