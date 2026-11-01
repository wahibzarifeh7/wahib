package com.wzexchange.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

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
//   A WebView can't download blobs or open files on its own, so this build also
//   exposes window.WZAndroid.saveFile (system "save to" picker, used for backups
//   and CSV exports) and answers <input type=file> with the system file picker
//   (used to restore a backup).
//
//   Automatic backup: the user picks one file once (normally in Google Drive)
//   through the system "save to" picker; the app keeps a persistable write grant
//   for it and the page rewrites it through autoBackupWrite. The Drive app does
//   the upload, so no Google sign-in or API key is involved here.
public class MainActivity extends Activity {
    static final String ONLINE_URL = "https://wzexchange.netlify.app/";
    static final String ONLINE_HOST = "wzexchange.netlify.app";
    static final String LOCAL_HOST = "appassets.androidplatform.net";
    static final String LOCAL_URL = "https://" + LOCAL_HOST + "/index.html";
    static final String LOCAL_ASSET_DIR = "app/";
    static final String NO_CONNECTION_URL = "file:///android_asset/offline.html";
    static final int REQUEST_SAVE_FILE = 1;
    static final int REQUEST_OPEN_FILE = 2;
    static final int REQUEST_AUTO_TARGET = 3;
    static final String PREFS = "autobackup";

    private WebView web;
    private boolean offlineBuild;
    private String appHost;
    private String appUrl;
    private String pendingSaveText;
    private ValueCallback<Uri[]> pendingFileChooser;
    private final ExecutorService backupWriter = Executors.newSingleThreadExecutor();

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

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (!offlineBuild) return false;
                if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
                pendingFileChooser = callback;
                // "*/*" rather than the page's accept list: file managers label .json
                // files inconsistently, and the page validates whatever is picked.
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                try {
                    startActivityForResult(pick, REQUEST_OPEN_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingFileChooser = null;
                    return false;
                }
                return true;
            }
        });
        if (offlineBuild) web.addJavascriptInterface(new FileBridge(), "WZAndroid");
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

    class FileBridge {
        @JavascriptInterface
        public void saveFile(final String filename, final String mime, final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    pendingSaveText = text;
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(mime)
                            .putExtra(Intent.EXTRA_TITLE, filename);
                    try {
                        startActivityForResult(save, REQUEST_SAVE_FILE);
                    } catch (ActivityNotFoundException e) {
                        pendingSaveText = null;
                        notifySaved(false);
                    }
                }
            });
        }

        @JavascriptInterface
        public void autoBackupChoose(final String filename) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_TITLE, filename)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(pick, REQUEST_AUTO_TARGET);
                    } catch (ActivityNotFoundException e) {
                        callJs("__wzAutoBackupChosen", null, "no_picker");
                    }
                }
            });
        }

        // {"name": file name, "provider": app that stores it} or "" when none is set.
        @JavascriptInterface
        public String autoBackupTarget() {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (p.getString("uri", null) == null) return "";
            try {
                return new JSONObject()
                        .put("name", p.getString("name", ""))
                        .put("provider", p.getString("provider", ""))
                        .toString();
            } catch (org.json.JSONException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void autoBackupClear() {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String uri = p.getString("uri", null);
            if (uri != null) {
                try {
                    getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
            p.edit().clear().apply();
        }

        // Writes in the background; answers window.__wzAutoBackupDone(ok, errorCode).
        @JavascriptInterface
        public void autoBackupWrite(final String text) {
            final String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString("uri", null);
            backupWriter.execute(new Runnable() {
                public void run() {
                    String error = null;
                    if (uri == null) {
                        error = "no_target";
                    } else {
                        try {
                            writeTruncating(Uri.parse(uri), text.getBytes(StandardCharsets.UTF_8));
                        } catch (SecurityException e) {
                            error = "permission";
                        } catch (IOException | RuntimeException e) {
                            error = "io";
                        }
                    }
                    final String err = error;
                    runOnUiThread(new Runnable() {
                        public void run() { callJs("__wzAutoBackupDone", err == null, err); }
                    });
                }
            });
        }
    }

    // Only truncating modes: a plain "w" may leave the tail of a longer previous
    // backup in place, which would corrupt the JSON.
    private void writeTruncating(Uri uri, byte[] bytes) throws IOException {
        OutputStream out;
        try {
            out = getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | java.io.FileNotFoundException e) {
            out = getContentResolver().openOutputStream(uri, "rwt");
        }
        if (out == null) throw new IOException("no stream");
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private String providerLabel(Uri uri) {
        String authority = uri.getAuthority();
        if (authority != null && authority.startsWith("com.google.android.apps.docs")) return "Google Drive";
        ProviderInfo info = authority == null ? null : getPackageManager().resolveContentProvider(authority, 0);
        if (info == null) return "";
        CharSequence label = info.applicationInfo.loadLabel(getPackageManager());
        return label == null ? "" : label.toString();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    // Calls window[fn](args...) with JSON-encoded arguments.
    private void callJs(String fn, Object... args) {
        StringBuilder js = new StringBuilder("window.").append(fn).append(" && window.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) js.append(',');
            Object a = args[i];
            if (a == null) js.append("null");
            else if (a instanceof Boolean) js.append(a.toString());
            else js.append(JSONObject.quote(a.toString()));
        }
        web.evaluateJavascript(js.append(")").toString(), null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Lets the page flush a pending automatic backup before the app is backgrounded.
        if (offlineBuild) web.evaluateJavascript("window.__wzOnPause && window.__wzOnPause()", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OPEN_FILE) {
            if (pendingFileChooser != null) {
                pendingFileChooser.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFileChooser = null;
            }
        } else if (requestCode == REQUEST_SAVE_FILE) {
            String text = pendingSaveText;
            pendingSaveText = null;
            boolean ok = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && text != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (IOException | RuntimeException e) {
                    ok = false;
                }
            }
            notifySaved(ok);
        } else if (requestCode == REQUEST_AUTO_TARGET) {
            Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) {
                callJs("__wzAutoBackupChosen", null, "cancelled");
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                callJs("__wzAutoBackupChosen", null, "not_persistable");
                return;
            }
            String old = getSharedPreferences(PREFS, MODE_PRIVATE).getString("uri", null);
            if (old != null && !old.equals(uri.toString())) new FileBridge().autoBackupClear();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("uri", uri.toString())
                    .putString("name", displayName(uri))
                    .putString("provider", providerLabel(uri))
                    .apply();
            callJs("__wzAutoBackupChosen", new FileBridge().autoBackupTarget(), null);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void notifySaved(boolean ok) {
        web.evaluateJavascript("window.__wzFileSaved && window.__wzFileSaved(" + ok + ")", null);
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
