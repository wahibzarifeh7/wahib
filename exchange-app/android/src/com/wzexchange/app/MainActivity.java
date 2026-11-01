package com.wzexchange.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

// One shell, two builds (see build.sh):
//
// - online: loads the deployed site. Staying same-origin with /api means login,
//   shared data and single-session enforcement work exactly as in the browser,
//   and UI updates ship with each Netlify deploy instead of needing a new APK.
//
// - offline: the APK carries the desktop app's localStorage-only page under
//   assets/app/. It is served from a fixed https origin (the same trick as
//   androidx WebViewAssetLoader) rather than file://, so localStorage has a
//   stable home and crypto.subtle (used for password hashing) is available.
public class MainActivity extends Activity {
    static final String ONLINE_URL = "https://wzexchange.netlify.app/";
    static final String ONLINE_HOST = "wzexchange.netlify.app";
    static final String LOCAL_HOST = "appassets.androidplatform.net";
    static final String LOCAL_URL = "https://" + LOCAL_HOST + "/index.html";
    static final String LOCAL_ASSET_DIR = "app/";
    static final String NO_CONNECTION_URL = "file:///android_asset/offline.html";

    private WebView web;
    private boolean offlineBuild;
    private String appHost;
    private String appUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        offlineBuild = hasAsset(LOCAL_ASSET_DIR + "index.html");
        appHost = offlineBuild ? LOCAL_HOST : ONLINE_HOST;
        appUrl = offlineBuild ? LOCAL_URL : ONLINE_URL;

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0F14"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // The String overload is the one in the API 23 SDK this compiles against;
            // newer WebViews route the WebResourceRequest overload to it by default.
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (appHost.equals(uri.getHost()) || url.equals(NO_CONNECTION_URL)) return false;
                if ("wzexchange".equals(uri.getScheme())) {
                    view.loadUrl(appUrl);
                    return true;
                }
                // Anything else (mailto:, tel:, other sites) opens outside the app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                if (!offlineBuild || !LOCAL_HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = getAssets().open(LOCAL_ASSET_DIR + path.substring(1));
                    return new WebResourceResponse(mimeType(path), "UTF-8", in);
                } catch (IOException e) {
                    WebResourceResponse missing = new WebResourceResponse("text/plain", "UTF-8", null);
                    missing.setStatusCodeAndReasonPhrase(404, "Not Found");
                    return missing;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!offlineBuild && req.isForMainFrame()) view.loadUrl(NO_CONNECTION_URL);
            }
        });

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(appUrl);
        }
    }

    private boolean hasAsset(String name) {
        try {
            getAssets().open(name).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack() && !NO_CONNECTION_URL.equals(web.getUrl())) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
