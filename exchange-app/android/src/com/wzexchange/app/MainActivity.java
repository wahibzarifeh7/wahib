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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Thin shell around the live web app. Loading the deployed site (rather than a
// bundled copy) keeps the page same-origin with /api, so login, shared data and
// single-session enforcement work exactly as in the browser, and UI updates
// ship with each Netlify deploy instead of needing a new APK.
public class MainActivity extends Activity {
    static final String APP_URL = "https://wzexchange.netlify.app/";
    static final String APP_HOST = "wzexchange.netlify.app";
    static final String OFFLINE_URL = "file:///android_asset/offline.html";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            @Override
            // The String overload is the one in the API 23 SDK this compiles against;
            // newer WebViews route the WebResourceRequest overload to it by default.
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (APP_HOST.equals(uri.getHost()) || url.equals(OFFLINE_URL)) return false;
                if ("wzexchange".equals(uri.getScheme())) {
                    view.loadUrl(APP_URL);
                    return true;
                }
                // Anything else (mailto:, tel:, other sites) opens outside the app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) view.loadUrl(OFFLINE_URL);
            }
        });

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack() && !OFFLINE_URL.equals(web.getUrl())) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
