package com.etm.etsenerjitakip;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;
    private static final int STORAGE_PERMISSION_REQUEST = 1003;
    private static final String NOTIFICATION_CHANNEL_ID = "ets_reactive_alerts";
    private static final String APP_USER_AGENT = "ETS-Enerji-Takip/2.0.3";
    private static final String ETS_HOST = "ets.etm.com.tr";
    private static final String EXTRA_COMPANY_ID = "ets_company_id";

    private WebView webView;
    private WebView scraperView;
    private WebView portalView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingDownloadName;
    private String pendingDownloadBase64;
    private String pendingNotificationCompanyId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.net.CookieManager nativeCookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
    private final java.util.concurrent.ExecutorService httpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private String scrapeTargetUrl;
    private String scrapeLandingUrl;
    private boolean scrapeTargetStarted;
    private int scrapeAttempt;
    private int scrapeGeneration;

    private String reportTargetUrl;
    private int reportAttempt;
    private int reportGeneration;

    private boolean websiteLoginInProgress;
    private String websiteLoginBase;
    private String websiteLoginUser;
    private String websiteLoginPass;
    private boolean websiteLoginRemember;
    private int websiteLoginAttempt;
    private int websiteLoginGeneration;

    private final class AndroidHttpBridge {
        @JavascriptInterface
        public String request(String method, String url, String body, String contentType) {
            HttpURLConnection connection = null;
            try {
                URL current = new URL(url);
                if (!isTrustedSite(current)) throw new SecurityException("Only the ETS server is allowed");
                int redirects = 0;
                String requestMethod = method == null ? "GET" : method.toUpperCase();
                String requestBody = body == null ? "" : body;

                while (true) {
                    connection = (HttpURLConnection) current.openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(30000);
                    connection.setRequestMethod(requestMethod);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 " + APP_USER_AGENT);
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                    connection.setRequestProperty("Pragma", "no-cache");
                    String lowerPath = current.getPath() == null ? "" : current.getPath().toLowerCase();
                    if (lowerPath.contains("aylik") || lowerPath.contains("rapor") || lowerPath.contains("detay")) {
                        connection.setRequestProperty("Referer", current.getProtocol() + "://" + current.getAuthority() + "/admin/ozet.aspx");
                    } else if (lowerPath.startsWith("/admin/") && !"/admin/".equalsIgnoreCase(current.getPath())) {
                        connection.setRequestProperty("Referer", current.getProtocol() + "://" + current.getAuthority() + "/admin/");
                    }

                    Map<String, List<String>> cookieHeaders = nativeCookies.get(current.toURI(), java.util.Collections.emptyMap());
                    for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
                        if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                            connection.setRequestProperty(entry.getKey(), join(entry.getValue(), "; "));
                        }
                    }

                    if (!requestBody.isEmpty() && !"GET".equals(requestMethod)) {
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", contentType == null || contentType.isEmpty()
                                ? "application/x-www-form-urlencoded" : contentType);
                        byte[] bytes = requestBody.getBytes(Charset.forName("UTF-8"));
                        connection.setFixedLengthStreamingMode(bytes.length);
                        connection.getOutputStream().write(bytes);
                    }

                    int status = connection.getResponseCode();
                    nativeCookies.put(current.toURI(), connection.getHeaderFields());

                    if (status >= 300 && status < 400 && connection.getHeaderField("Location") != null && redirects < 8) {
                        current = new URL(current, connection.getHeaderField("Location"));
                        if (!isTrustedSite(current)) throw new SecurityException("Redirect outside ETS blocked");
                        redirects++;
                        if (status == 301 || status == 302 || status == 303) {
                            requestMethod = "GET";
                            requestBody = "";
                        }
                        connection.disconnect();
                        continue;
                    }

                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String charset = "UTF-8";
                    String ct = connection.getContentType();
                    if (ct != null) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern
                                .compile("charset=([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(ct);
                        if (matcher.find()) charset = matcher.group(1).trim().replace("\"", "");
                    }

                    StringBuilder text = new StringBuilder();
                    if (stream != null) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName(charset)));
                        String line;
                        while ((line = reader.readLine()) != null) text.append(line).append('\n');
                        reader.close();
                    }

                    syncNativeCookiesToWebView(current);

                    JSONObject result = new JSONObject();
                    result.put("status", status);
                    result.put("url", current.toString());
                    result.put("text", text.toString());
                    return result.toString();
                }
            } catch (Exception e) {
                try {
                    JSONObject result = new JSONObject();
                    result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    return result.toString();
                } catch (Exception ignored) {
                    return "{\"error\":\"Network error\"}";
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        @JavascriptInterface
        public void requestAsync(int id, String method, String url, String body, String contentType) {
            httpExecutor.execute(() -> {
                String result = request(method, url, body, contentType);
                String encoded = JSONObject.quote(result);
                runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript("window.onNativeHttpResult(" + id + "," + encoded + ")", null); });
            });
        }

        @JavascriptInterface
        public void clearSessionAsync() {
            httpExecutor.execute(() -> {
                nativeCookies.getCookieStore().removeAll();
                runOnUiThread(() -> CookieManager.getInstance().removeAllCookies(done -> {
                    CookieManager.getInstance().flush();
                    if (scraperView != null) scraperView.loadUrl("about:blank");
                    if (portalView != null) { portalView.loadUrl("about:blank"); portalView.setVisibility(android.view.View.GONE); }
                    if (webView != null) { webView.clearCache(true); webView.evaluateJavascript("window.onNativeSessionCleared()", null); }
                }));
            });
        }

        @JavascriptInterface
        public void loginWithWebView(String baseUrl, String username, String password, boolean remember) {
            runOnUiThread(() -> startWebsiteLogin(baseUrl, username, password, remember));
        }

        @JavascriptInterface
        public void loadRenderedCompanyPage(String targetUrl) {
            runOnUiThread(() -> startRenderedCompanyPage(targetUrl));
        }

        @JavascriptInterface
        public void loadRenderedReportPage(String targetUrl) {
            runOnUiThread(() -> startRenderedReportPage(targetUrl));
        }


        @JavascriptInterface
        public void showAlertNotification(String title, String message, int notificationId, String companyId) {
            runOnUiThread(() -> showPhoneNotification(title, message, notificationId, companyId));
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(MainActivity.this::requestNotificationPermissionNative);
        }

        @JavascriptInterface
        public void handlePdf(String fileName, String base64Data, String action) {
            if ("download".equalsIgnoreCase(action)
                    && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadName = fileName;
                pendingDownloadBase64 = base64Data;
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST));
                return;
            }
            new Thread(() -> handlePdfFile(fileName, base64Data, action), "ets-pdf").start();
        }

        @JavascriptInterface
        public void openUserPortal() {
            runOnUiThread(() -> {
                if (portalView == null) return;
                portalView.setVisibility(android.view.View.VISIBLE);
                portalView.bringToFront();
                portalView.loadUrl("http://" + ETS_HOST + ":82/Gruplar.aspx?_ets_refresh=" + System.currentTimeMillis());
            });
        }

        @JavascriptInterface
        public void closeUserPortal() {
            runOnUiThread(() -> { if (portalView != null) portalView.setVisibility(android.view.View.GONE); });
        }

        @JavascriptInterface
        public void clearSession() {
            nativeCookies.getCookieStore().removeAll();
            runOnUiThread(() -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                if (scraperView != null) scraperView.loadUrl("about:blank");
                if (portalView != null) { portalView.loadUrl("about:blank"); portalView.setVisibility(android.view.View.GONE); }
                if (webView != null) webView.clearCache(true);
            });
        }

        @JavascriptInterface
        public void clearNativeCache() {
            runOnUiThread(() -> {
                if (webView != null) webView.clearCache(true);
                deleteRecursively(new File(getCacheDir(), "pdf_reports"));
                Toast.makeText(MainActivity.this, "Uygulama önbelleği temizlendi", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "ETS Reaktif Enerji Uyarıları",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("İndüktif ve kapasitif limit uyarıları");
            channel.enableVibration(true);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionNative() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void showPhoneNotification(String title, String message, int notificationId, String companyId) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionNative();
            return;
        }
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (companyId != null && !companyId.trim().isEmpty()) {
            launchIntent.putExtra(EXTRA_COMPANY_ID, companyId.trim());
        }
        int requestCode = notificationId == 0 ? 2201 : notificationId;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, requestCode, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title == null ? "ETS Enerji Takip" : title)
                .setContentText(message == null ? "Reaktif enerji uyarısı" : message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message == null ? "Reaktif enerji uyarısı" : message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        try {
            NotificationManagerCompat.from(this).notify(notificationId == 0 ? 2201 : notificationId, builder.build());
        } catch (SecurityException ignored) {
            requestNotificationPermissionNative();
        }
    }

    private static boolean isTrustedSite(URL url) {
        String protocol = url.getProtocol();
        return ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                && ETS_HOST.equalsIgnoreCase(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 82 || url.getPort() == 443 || url.getPort() == 80);
    }

    private static String safePdfName(String raw) {
        String name = raw == null ? "ETS_Report.pdf" : raw.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!name.toLowerCase().endsWith(".pdf")) name += ".pdf";
        return name;
    }

    private void handlePdfFile(String rawName, String base64Data, String action) {
        try {
            final String fileName = safePdfName(rawName);
            final byte[] bytes = Base64.decode(base64Data == null ? "" : base64Data, Base64.DEFAULT);
            if (bytes.length == 0) throw new IllegalArgumentException("PDF data is empty");
            if ("download".equalsIgnoreCase(action)) {
                final String savedAt = savePdfToDownloads(fileName, bytes);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "PDF kaydedildi: " + savedAt, Toast.LENGTH_LONG).show());
            } else {
                File dir = new File(getCacheDir(), "pdf_reports");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("PDF cache folder could not be created");
                File file = new File(dir, fileName);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                runOnUiThread(() -> openPdfUri(uri));
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "PDF işlemi başarısız: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private String savePdfToDownloads(String fileName, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ETS Enerji Takip");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Downloads entry could not be created");
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("PDF output stream is unavailable");
                output.write(bytes);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return "Downloads/ETS Enerji Takip/" + fileName;
        }
        File root;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } else {
            root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (root == null) throw new IllegalStateException("External downloads folder is unavailable");
        File dir = new File(root, "ETS Enerji Takip");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Download folder could not be created");
        File file = new File(dir, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return file.getAbsolutePath();
    }

    private void openPdfUri(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No PDF viewer is installed", Toast.LENGTH_LONG).show();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private void syncNativeCookiesToWebView(URL current) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable sync = () -> {
            try {
                CookieManager manager = CookieManager.getInstance();
                manager.setAcceptCookie(true);
                String cookieUrl = current.getProtocol() + "://" + current.getAuthority();
                for (HttpCookie cookie : nativeCookies.getCookieStore().getCookies()) {
                    StringBuilder value = new StringBuilder(cookie.getName()).append('=').append(cookie.getValue());
                    if (cookie.getPath() != null && !cookie.getPath().isEmpty()) value.append("; Path=").append(cookie.getPath());
                    if (cookie.getDomain() != null && !cookie.getDomain().isEmpty()) value.append("; Domain=").append(cookie.getDomain());
                    if (cookie.getSecure()) value.append("; Secure");
                    manager.setCookie(cookieUrl, value.toString());
                }
                manager.flush();
            } finally {
                latch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) sync.run();
        else {
            runOnUiThread(sync);
            latch.await(5, TimeUnit.SECONDS);
        }
    }

    private void captureNotificationTarget(Intent intent) {
        if (intent == null) return;
        String companyId = intent.getStringExtra(EXTRA_COMPANY_ID);
        if (companyId != null && !companyId.trim().isEmpty()) {
            pendingNotificationCompanyId = companyId.trim();
        }
    }

    private void dispatchPendingNotificationTarget() {
        if (webView == null || pendingNotificationCompanyId == null || pendingNotificationCompanyId.isEmpty()) return;
        final String companyId = pendingNotificationCompanyId;
        final String script = "window.openCompanyFromNotification && window.openCompanyFromNotification(" + JSONObject.quote(companyId) + ");";
        webView.evaluateJavascript(script, value -> {
            if ("true".equalsIgnoreCase(String.valueOf(value))) {
                pendingNotificationCompanyId = null;
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureNotificationTarget(intent);
        mainHandler.postDelayed(this::dispatchPendingNotificationTarget, 180);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView view, boolean localApp) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " " + APP_USER_AGENT);
        if (!localApp) {
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureNotificationTarget(getIntent());
        CookieHandler.setDefault(nativeCookies);
        createNotificationChannel();

        FrameLayout root = new FrameLayout(this);
        scraperView = new WebView(this);
        webView = new WebView(this);
        portalView = new WebView(this);
        scraperView.setBackgroundColor(Color.WHITE);
        webView.setBackgroundColor(Color.rgb(213, 31, 38));
        root.addView(scraperView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(portalView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        portalView.setVisibility(android.view.View.GONE);
        setContentView(root);

        configureWebView(webView, true);
        configureWebView(scraperView, false);
        configureWebView(portalView, false);
        scraperView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        portalView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        cookies.setAcceptThirdPartyCookies(scraperView, false);
        cookies.setAcceptThirdPartyCookies(portalView, false);
        portalView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !ETS_HOST.equalsIgnoreCase(uri.getHost()) || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
            }
        });
        webView.addJavascriptInterface(new AndroidHttpBridge(), "AndroidHttp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file:///android_asset/index.html")) {
                    mainHandler.postDelayed(() -> view.setBackgroundColor(Color.WHITE), 2200);
                    mainHandler.postDelayed(MainActivity.this::dispatchPendingNotificationTarget, 2400);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                if (host.equals("wa.me") || host.endsWith("whatsapp.com")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "WhatsApp açılamadı", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                if ("file".equalsIgnoreCase(scheme)) return !"/android_asset/index.html".equals(uri.getPath());
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return true;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        scraperView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (websiteLoginInProgress) {
                    handleWebsiteLoginPage(view, url);
                    return;
                }

                if (reportTargetUrl != null) {
                    scheduleReportPoll(reportGeneration, 700);
                    return;
                }

                if (scrapeTargetUrl == null) return;
                String lower = url == null ? "" : url.toLowerCase();
                if (!scrapeTargetStarted && lower.contains("/admin/") && !lower.contains("ozet.aspx")
                        && !lower.contains("girisyap.aspx")) {
                    scrapeTargetStarted = true;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Referer", scrapeLandingUrl);
                    mainHandler.postDelayed(() -> scraperView.loadUrl(scrapeTargetUrl, headers), 350);
                    return;
                }
                scheduleScrapePoll(scrapeGeneration, 600);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        scraperView.setWebChromeClient(new WebChromeClient());

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, "Dosya indirilemedi", Toast.LENGTH_SHORT).show();
            }
        });

        if (savedInstanceState == null) webView.loadUrl("file:///android_asset/index.html");
        else webView.restoreState(savedInstanceState);
    }

    private void startWebsiteLogin(String baseUrl, String username, String password, boolean remember) {
        try {
            URL base = new URL(baseUrl);
            if (!isTrustedSite(base)) throw new SecurityException("Only the ETS server is allowed");
            websiteLoginGeneration++;
            websiteLoginAttempt = 0;
            websiteLoginInProgress = true;
            websiteLoginBase = base.getProtocol() + "://" + base.getAuthority();
            websiteLoginUser = username == null ? "" : username;
            websiteLoginPass = password == null ? "" : password;
            websiteLoginRemember = remember;
            scrapeTargetUrl = null;
            reportTargetUrl = null;
            scraperView.stopLoading();
            scraperView.clearHistory();
            scraperView.clearCache(true);
            String loginUrl = websiteLoginBase + "/User/GirisYap.aspx?_ets_refresh=" + System.currentTimeMillis();
            scraperView.loadUrl(loginUrl);
        } catch (Exception e) {
            deliverWebsiteLoginError("Giriş sayfası açılamadı: " + e.getMessage());
        }
    }

    private void handleWebsiteLoginPage(WebView view, String url) {
        if (!websiteLoginInProgress) return;
        String lower = url == null ? "" : url.toLowerCase();
        if (!lower.contains("girisyap.aspx")) {
            try {
                URL current = new URL(url);
                importWebViewCookies(current);
                String path = current.getPath() == null ? "" : current.getPath().toLowerCase();
                if (path.startsWith("/admin") || path.equals("/default.aspx") || path.equals("/gruplar.aspx")) {
                    deliverWebsiteLoginSuccess(current.toString(), path.startsWith("/admin") ? "admin" : "user");
                    return;
                }
            } catch (Exception e) {
                deliverWebsiteLoginError("Oturum bilgisi alınamadı: " + e.getMessage());
                return;
            }
        }

        if (!lower.contains("girisyap.aspx")) return;
        websiteLoginAttempt++;
        if (websiteLoginAttempt == 1) {
            String user = JSONObject.quote(websiteLoginUser);
            String pass = JSONObject.quote(websiteLoginPass);
            String remember = websiteLoginRemember ? "true" : "false";
            String script = "(function(){"
                    + "const by=(sels)=>{for(const s of sels){const e=document.querySelector(s);if(e)return e}return null};"
                    + "const u=by(['input[name$=\\\"TbKullaniciAdi\\\"]','input[id$=\\\"TbKullaniciAdi\\\"]','input[type=text]']);"
                    + "const p=by(['input[name$=\\\"TbParola\\\"]','input[id$=\\\"TbParola\\\"]','input[type=password]']);"
                    + "const c=by(['input[name$=\\\"Cb1\\\"]','input[id$=\\\"Cb1\\\"]','input[type=checkbox]']);"
                    + "if(!u||!p)return JSON.stringify({ok:false,error:'Giriş alanları bulunamadı'});"
                    + "u.focus();u.value=" + user + ";u.dispatchEvent(new Event('input',{bubbles:true}));u.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "p.focus();p.value=" + pass + ";p.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "if(c){c.checked=" + remember + ";c.dispatchEvent(new Event('change',{bubbles:true}))}"
                    + "let b=[...document.querySelectorAll('input[type=submit],button,a')].find(e=>/giriş|giris/i.test((e.value||e.textContent||'').trim()));"
                    + "if(b){b.click();return JSON.stringify({ok:true,method:'click'})}"
                    + "const f=p.form||u.form||document.querySelector('form');if(f){f.submit();return JSON.stringify({ok:true,method:'submit'})}"
                    + "return JSON.stringify({ok:false,error:'Giriş düğmesi bulunamadı'});"
                    + "})()";
            view.evaluateJavascript(script, value -> {
                try {
                    Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
                    String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                    JSONObject result = new JSONObject(json);
                    if (!result.optBoolean("ok", false)) deliverWebsiteLoginError(result.optString("error", "Giriş formu çalıştırılamadı"));
                } catch (Exception e) {
                    deliverWebsiteLoginError("Giriş formu çalıştırılamadı: " + e.getMessage());
                }
            });
            return;
        }

        final int generation = websiteLoginGeneration;
        view.evaluateJavascript("(function(){return String(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim()})()", value -> {
            if (!websiteLoginInProgress || generation != websiteLoginGeneration) return;
            String text = String.valueOf(value == null ? "" : value).toLowerCase();
            if (text.contains("yanlış") || text.contains("yanlis") || text.contains("hatalı") || text.contains("hatali")) {
                deliverWebsiteLoginError("Kullanıcı adı veya parola yanlış.");
            } else if (websiteLoginAttempt >= 3) {
                deliverWebsiteLoginError("Giriş doğrulanamadı. Kullanıcı adı ve parolayı kontrol edin.");
            }
        });
    }

    private void importWebViewCookies(URL current) throws Exception {
        CookieManager manager = CookieManager.getInstance();
        String origin = current.getProtocol() + "://" + current.getAuthority();
        String raw = manager.getCookie(origin);
        if (raw == null || raw.trim().isEmpty()) return;
        URI uri = new URI(origin + "/");
        for (String part : raw.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            HttpCookie cookie = new HttpCookie(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            cookie.setDomain(current.getHost());
            cookie.setPath("/");
            nativeCookies.getCookieStore().add(uri, cookie);
        }
    }

    private void deliverWebsiteLoginSuccess(String url, String role) {
        websiteLoginInProgress = false;
        try {
            JSONObject info = new JSONObject();
            info.put("ok", true);
            info.put("url", url);
            info.put("role", role);
            webView.evaluateJavascript("window.onNativeWebsiteLogin&&window.onNativeWebsiteLogin(" + JSONObject.quote(info.toString()) + ");", null);
        } catch (Exception e) {
            deliverWebsiteLoginError("Giriş sonucu iletilemedi: " + e.getMessage());
        }
    }

    private void deliverWebsiteLoginError(String message) {
        websiteLoginInProgress = false;
        try {
            JSONObject info = new JSONObject();
            info.put("ok", false);
            info.put("error", message == null ? "Giriş başarısız" : message);
            webView.evaluateJavascript("window.onNativeWebsiteLogin&&window.onNativeWebsiteLogin(" + JSONObject.quote(info.toString()) + ");", null);
        } catch (Exception ignored) {
        }
    }

    private void startRenderedCompanyPage(String targetUrl) {
        try {
            URL target = new URL(targetUrl);
            scrapeGeneration++;
            scrapeAttempt = 0;
            scrapeTargetStarted = false;
            scrapeTargetUrl = target.toString();
            scrapeLandingUrl = target.getProtocol() + "://" + target.getAuthority() + "/admin/";
            scraperView.stopLoading();
            scraperView.clearHistory();
            scraperView.loadUrl(scrapeLandingUrl);
        } catch (Exception e) {
            deliverScrapeError("Invalid company page URL: " + e.getMessage());
        }
    }

    private void startRenderedReportPage(String targetUrl) {
        try {
            URL target = new URL(targetUrl);
            scrapeTargetUrl = null;
            reportGeneration++;
            reportAttempt = 0;
            reportTargetUrl = target.toString();
            scraperView.stopLoading();
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", target.getProtocol() + "://" + target.getAuthority() + "/admin/ozet.aspx");
            scraperView.loadUrl(reportTargetUrl, headers);
        } catch (Exception e) {
            deliverReportError("Invalid report URL: " + e.getMessage());
        }
    }

    private void scheduleReportPoll(int generation, long delayMs) {
        mainHandler.postDelayed(() -> pollRenderedReportPage(generation), delayMs);
    }

    private void pollRenderedReportPage(int generation) {
        if (generation != reportGeneration || reportTargetUrl == null || scraperView == null) return;
        reportAttempt++;
        final String script = "(function(){"
                + "const clean=s=>String(s||'').replace(/\\s+/g,' ').trim();"
                + "const login=!!document.querySelector('input[type=password]')&&!!document.querySelector('input[name*=\"Kullanici\"],input[id*=\"Kullanici\"],input[name*=\"TbKullaniciAdi\"],input[id*=\"TbKullaniciAdi\"]');"
                + "const bodyText=clean(document.body&&document.body.innerText);"
                + "const denied=/Yetkiniz olmayan|yetkiniz yok|erişim yetkiniz/i.test(bodyText);"
                + "const placeholderDots=/\\.{6,}/.test(bodyText);"
                + "let html='';try{html=btoa(unescape(encodeURIComponent(document.documentElement.outerHTML)))}catch(e){}"
                + "return JSON.stringify({url:location.href,title:document.title||'',readyState:document.readyState,login:login,denied:denied,totalRows:document.querySelectorAll('tr').length,totalTables:document.querySelectorAll('table').length,bodyLength:(document.body&&document.body.innerText||'').length,placeholderDots:placeholderDots,htmlBase64:html});"
                + "})()";

        scraperView.evaluateJavascript(script, value -> {
            if (generation != reportGeneration || reportTargetUrl == null) return;
            try {
                Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
                String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                JSONObject info = new JSONObject(json);
                boolean login = info.optBoolean("login", false);
                boolean denied = info.optBoolean("denied", false);
                boolean complete = "complete".equalsIgnoreCase(info.optString("readyState", ""));
                boolean placeholderDots = info.optBoolean("placeholderDots", false);
                int rows = info.optInt("totalRows", 0);
                int bodyLength = info.optInt("bodyLength", 0);
                if (login || denied || (complete && (rows > 0 || bodyLength > 300) && reportAttempt >= 5 && (!placeholderDots || reportAttempt >= 12))
                        || reportAttempt >= 18) {
                    info.put("status", 200);
                    info.put("attempts", reportAttempt);
                    deliverReport(info);
                } else {
                    scheduleReportPoll(generation, 700);
                }
            } catch (Exception e) {
                if (reportAttempt >= 18) deliverReportError("Rendered report page could not be read: " + e.getMessage());
                else scheduleReportPoll(generation, 700);
            }
        });
    }

    private void deliverReport(JSONObject info) {
        reportTargetUrl = null;
        final String payload = info.toString();
        webView.evaluateJavascript(
                "window.onNativeReportPage&&window.onNativeReportPage(" + JSONObject.quote(payload) + ");",
                null);
    }

    private void deliverReportError(String message) {
        reportTargetUrl = null;
        try {
            JSONObject info = new JSONObject();
            info.put("error", message);
            final String payload = info.toString();
            webView.evaluateJavascript(
                    "window.onNativeReportPage&&window.onNativeReportPage(" + JSONObject.quote(payload) + ");",
                    null);
        } catch (Exception ignored) {
        }
    }

    private void scheduleScrapePoll(int generation, long delayMs) {
        mainHandler.postDelayed(() -> pollRenderedCompanyPage(generation), delayMs);
    }

    private void pollRenderedCompanyPage(int generation) {
        if (generation != scrapeGeneration || scrapeTargetUrl == null || scraperView == null) return;
        scrapeAttempt++;
        final String script = "(function(){"
                + "const clean=s=>String(s||'').replace(/\\s+/g,' ').trim();"
                + "const keyRe=/^(FirmaId|FirmaID|firmaid|Kimin|kimin|Firma|firma|fid|id|SayacId|sayacid|SirketId|sirketid|MusteriId|musteriid|AboneId|aboneid|no)$/i;"
                + "function hrefOf(raw){if(!raw||/^javascript:|^#$/i.test(String(raw).trim()))return '';try{return new URL(String(raw),location.href).href}catch(e){return ''}}"
                + "function idOf(raw){if(!raw)return '';try{const u=new URL(String(raw),location.href);for(const [k,v] of u.searchParams){if(keyRe.test(k)&&/^\\d{1,12}$/.test(v))return v}}catch(e){}const m=String(raw).match(/(?:FirmaId|FirmaID|firmaid|Kimin|kimin|fid|SayacId|SirketId|MusteriId|AboneId|firma|id|no)\\D{0,20}(\\d{1,12})/i)||String(raw).match(/__doPostBack\\([^)]*?['\"](\\d{1,12})['\"]/i);return m?m[1]:''}"
                + "const records=[],seen=new Set();"
                + "for(const row of document.querySelectorAll('tr')){const cells=[...row.querySelectorAll(':scope > td')];if(!cells.length)continue;const first=cells[0];const links=[...first.querySelectorAll('a,[href],[onclick],[data-href],[data-url]')];const companyLink=links.find(el=>{const t=clean(el.innerText||el.textContent);return t&&!/^(\\[?PDF\\]?|\\[?Ayarlar\\]?|PDF|Ayarlar)$/i.test(t)})||null;let name=clean(companyLink&&(companyLink.innerText||companyLink.textContent)||first.innerText).replace(/\\[?PDF\\]?/ig,'').replace(/\\[?Ayarlar\\]?/ig,'').replace(/^\\s*[|•-]+|[|•-]+\\s*$/g,'').trim();if(!name||/^(FIRMA|ŞİRKET|SIRKET)$/i.test(name))continue;const primary=companyLink?[companyLink.getAttribute('href'),companyLink.getAttribute('data-href'),companyLink.getAttribute('data-url'),companyLink.getAttribute('onclick')].filter(Boolean):[];const all=[...primary];for(const el of row.querySelectorAll('[href],[onclick],[data-href],[data-url],[data-id],[data-firmaid],[data-kimin],[value]')){all.push(el.getAttribute('href'),el.getAttribute('onclick'),el.getAttribute('data-href'),el.getAttribute('data-url'),el.getAttribute('data-id'),el.getAttribute('data-firmaid'),el.getAttribute('data-kimin'),el.getAttribute('value'))}let id='';for(const raw of all.filter(Boolean)){id=idOf(raw);if(id)break}if(!id)continue;let reportHref='';for(const raw of primary){reportHref=hrefOf(raw);if(reportHref)break}if(!reportHref){for(const el of links){const t=clean(el.innerText||el.textContent);if(/PDF|Ayarlar/i.test(t))continue;reportHref=hrefOf(el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-url'));if(reportHref)break}}if(!reportHref)reportHref=location.origin+'/Aylik_Liste.aspx?FirmaId='+encodeURIComponent(id)+'&ftext='+encodeURIComponent(name);let settingsHref='';for(const el of row.querySelectorAll('a[href],[data-href],[data-url]')){const t=clean(el.innerText||el.textContent),raw=el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-url');if(/Ayarlar/i.test(t)||/IsletmeAyarlari\\.aspx/i.test(String(raw||''))){settingsHref=hrefOf(raw);if(settingsHref)break}}if(!settingsHref)settingsHref=location.origin+'/admin/IsletmeAyarlari.aspx?id='+encodeURIComponent(id);if(!seen.has(id)){seen.add(id);records.push({id:id,name:name,href:reportHref,settingsHref:settingsHref});}}"
                + "const login=!!document.querySelector('input[type=password]')&&!!document.querySelector('input[name*=\"Kullanici\"],input[id*=\"Kullanici\"],input[name*=\"TbKullaniciAdi\"],input[id*=\"TbKullaniciAdi\"]');"
                + "let html='';try{html=btoa(unescape(encodeURIComponent(document.documentElement.outerHTML)))}catch(e){}"
                + "return JSON.stringify({url:location.href,title:document.title||'',readyState:document.readyState,records:records,recordCount:records.length,totalRows:document.querySelectorAll('tr').length,login:login,htmlBase64:html});"
                + "})()";

        scraperView.evaluateJavascript(script, value -> {
            if (generation != scrapeGeneration || scrapeTargetUrl == null) return;
            try {
                Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
                String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                JSONObject info = new JSONObject(json);
                int records = info.optInt("recordCount", 0);
                boolean login = info.optBoolean("login", false);
                boolean complete = "complete".equalsIgnoreCase(info.optString("readyState", ""));
                if (records > 0 || login || (complete && scrapeAttempt >= 20) || scrapeAttempt >= 25) {
                    info.put("status", 200);
                    info.put("attempts", scrapeAttempt);
                    deliverScrape(info);
                } else {
                    scheduleScrapePoll(generation, 750);
                }
            } catch (Exception e) {
                if (scrapeAttempt >= 25) deliverScrapeError("Rendered page could not be read: " + e.getMessage());
                else scheduleScrapePoll(generation, 750);
            }
        });
    }

    private void deliverScrape(JSONObject info) {
        scrapeTargetUrl = null;
        final String payload = info.toString();
        webView.evaluateJavascript(
                "window.onNativeCompanyPage&&window.onNativeCompanyPage(" + JSONObject.quote(payload) + ");",
                null);
    }

    private void deliverScrapeError(String message) {
        scrapeTargetUrl = null;
        try {
            JSONObject info = new JSONObject();
            info.put("error", message);
            final String payload = info.toString();
            webView.evaluateJavascript(
                    "window.onNativeCompanyPage&&window.onNativeCompanyPage(" + JSONObject.quote(payload) + ");",
                    null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && webView != null) {
            webView.evaluateJavascript("window.scanAlerts&&scanAlerts(false);", null);
        } else if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingDownloadBase64 != null) {
                final String name = pendingDownloadName;
                final String data = pendingDownloadBase64;
                pendingDownloadName = null;
                pendingDownloadBase64 = null;
                new Thread(() -> handlePdfFile(name, data, "download"), "ets-pdf").start();
            } else {
                pendingDownloadName = null;
                pendingDownloadBase64 = null;
                Toast.makeText(this, "Storage permission is required to save the PDF in Downloads", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (portalView != null && portalView.getVisibility() == android.view.View.VISIBLE) {
            if (portalView.canGoBack()) portalView.goBack();
            else portalView.setVisibility(android.view.View.GONE);
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (portalView != null) { portalView.loadUrl("about:blank"); portalView.destroy(); }
        if (scraperView != null) {
            scraperView.loadUrl("about:blank");
            scraperView.stopLoading();
            scraperView.destroy();
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
