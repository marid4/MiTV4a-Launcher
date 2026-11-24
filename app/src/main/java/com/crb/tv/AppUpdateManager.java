package com.crb.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pengelola Cek Update & Update Otomatis dari GitHub Releases untuk Crb Launcher (com.crb.tv).
 */
public final class AppUpdateManager {

    public static final class ReleaseInfo {
        public final String tagName;
        public final String releaseName;
        public final String body;
        public final String apkDownloadUrl;
        public final String apkFileName;
        public final long apkSizeBytes;
        public final long publishedEpochMs;
        public final int remoteVersionCode;
        public final boolean hasUpdate;

        public ReleaseInfo(
                String tagName,
                String releaseName,
                String body,
                String apkDownloadUrl,
                String apkFileName,
                long apkSizeBytes,
                long publishedEpochMs,
                int remoteVersionCode,
                boolean hasUpdate) {
            this.tagName = tagName;
            this.releaseName = releaseName;
            this.body = body;
            this.apkDownloadUrl = apkDownloadUrl;
            this.apkFileName = apkFileName;
            this.apkSizeBytes = apkSizeBytes;
            this.publishedEpochMs = publishedEpochMs;
            this.remoteVersionCode = remoteVersionCode;
            this.hasUpdate = hasUpdate;
        }
    }

    public interface OnCheckListener {
        void onCheckComplete(ReleaseInfo info, String errorMessage);
    }

    public interface OnDownloadListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onCompleted(File apkFile);
        void onError(String errorMessage);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "3.8";
        } catch (Exception e) {
            return "3.8";
        }
    }

    public static long getInstalledLastUpdateTime(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    public void checkLatestReleaseAsync(
            final Context context,
            final String repoSlug,
            final OnCheckListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ReleaseInfo info = null;
                String error = null;
                HttpURLConnection conn = null;
                try {
                    String cleanRepo = sanitizeRepoSlug(repoSlug);
                    URL url = new URL("https://api.github.com/repos/" + cleanRepo + "/releases/latest");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "CrbLauncher-Updater/3.9");

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        error = "GitHub merespons HTTP " + code + " untuk repo " + cleanRepo;
                    } else {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject rel = new JSONObject(sb.toString());
                        String tag = rel.optString("tag_name", "");
                        String title = rel.optString("name", tag);
                        String body = rel.optString("body", "");
                        String publishedAt = rel.optString("published_at", "");
                        long publishedMs = parseIsoTimestampMs(publishedAt);

                        String apkUrl = "";
                        String apkName = "";
                        long apkSize = 0L;
                        long assetUpdatedMs = publishedMs;

                        JSONArray assets = rel.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject a = assets.optJSONObject(i);
                                if (a == null) continue;
                                String name = a.optString("name", "");
                                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                                    String dl = a.optString("browser_download_url", "");
                                    long sz = a.optLong("size", 0L);
                                    long upMs = parseIsoTimestampMs(a.optString("updated_at", publishedAt));
                                    // Prioritaskan debug/release yang cocok atau APK pertama yang ditemukan
                                    if (apkUrl.isEmpty() || name.toLowerCase(Locale.US).contains("debug")) {
                                        apkUrl = dl;
                                        apkName = name;
                                        apkSize = sz;
                                        if (upMs > 0) assetUpdatedMs = upMs;
                                    }
                                }
                            }
                        }

                        int currentCode = getCurrentVersionCode(context);
                        int remoteCode = parseBuildNumberFromTag(tag);
                        long installedTimeMs = getInstalledLastUpdateTime(context);

                        boolean hasUpdate = false;
                        if (!apkUrl.isEmpty()) {
                            if (remoteCode > currentCode) {
                                hasUpdate = true;
                            } else if (remoteCode == 0 && assetUpdatedMs > (installedTimeMs + 120000L)) {
                                // Untuk tag -latest, bandingkan waktu rilis aset dengan waktu instal APK di TV
                                hasUpdate = true;
                            }
                        }

                        info = new ReleaseInfo(
                                tag, title, body, apkUrl, apkName, apkSize,
                                assetUpdatedMs, remoteCode, hasUpdate
                        );
                    }
                } catch (Exception e) {
                    error = "Gagal menghubungi GitHub: " + e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final ReleaseInfo finalInfo = info;
                final String finalErr = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onCheckComplete(finalInfo, finalErr);
                        }
                    }
                });
            }
        }, "GitHubUpdateChecker").start();
    }

    public void downloadApkAsync(
            final Context context,
            final String downloadUrl,
            final OnDownloadListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                BufferedInputStream bis = null;
                FileOutputStream fos = null;
                try {
                    File updateDir = new File(context.getCacheDir(), "updates");
                    if (!updateDir.exists()) updateDir.mkdirs();
                    final File outApk = new File(updateDir, "CrbLauncher-update.apk");
                    File tmpApk = new File(updateDir, "CrbLauncher-update.apk.tmp");
                    if (tmpApk.exists()) tmpApk.delete();

                    URL url = new URL(downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "CrbLauncher-Updater/3.9");

                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String redirectUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (redirectUrl != null && !redirectUrl.isEmpty()) {
                            conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                            conn.setConnectTimeout(12000);
                            conn.setReadTimeout(20000);
                            code = conn.getResponseCode();
                        }
                    }

                    if (code != 200) {
                        throw new RuntimeException("HTTP " + code);
                    }

                    final long totalBytes = conn.getContentLength();
                    bis = new BufferedInputStream(conn.getInputStream());
                    fos = new FileOutputStream(tmpApk);

                    byte[] buf = new byte[16384];
                    long downloaded = 0;
                    int lastPercent = -1;
                    int r;
                    while ((r = bis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                        downloaded += r;
                        if (totalBytes > 0) {
                            int pct = (int) ((downloaded * 100L) / totalBytes);
                            if (pct != lastPercent) {
                                lastPercent = pct;
                                final int fPct = pct;
                                final long fDl = downloaded;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (listener != null) {
                                            listener.onProgress(fPct, fDl, totalBytes);
                                        }
                                    }
                                });
                            }
                        }
                    }
                    fos.flush();
                    fos.close();
                    fos = null;

                    if (outApk.exists()) outApk.delete();
                    if (!tmpApk.renameTo(outApk)) {
                        throw new RuntimeException("Gagal menyimpan file APK update");
                    }

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.onCompleted(outApk);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.onError("Gagal mengunduh update: " + e.getMessage());
                            }
                        }
                    });
                } finally {
                    try { if (bis != null) bis.close(); } catch (Exception ignored) {}
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }
        }, "GitHubApkDownloader").start();
    }

    public static void launchApkInstaller(Context context) {
        File updateDir = new File(context.getCacheDir(), "updates");
        File apkFile = new File(updateDir, "CrbLauncher-update.apk");
        if (!apkFile.exists() || apkFile.length() < 100000L) {
            throw new RuntimeException("File APK update rusak atau belum selesai diunduh");
        }
        Uri apkUri = Uri.parse("content://" + ApkUpdateProvider.AUTHORITY + "/CrbLauncher-update.apk");
        try {
            Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installIntent.setData(apkUri);
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(installIntent);
            return;
        } catch (Exception ignored) {
        }
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(viewIntent);
    }

    private static String sanitizeRepoSlug(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "marid4/CrbLauncher";
        String s = raw.trim();
        if (s.startsWith("https://github.com/")) {
            s = s.substring("https://github.com/".length());
        } else if (s.startsWith("http://github.com/")) {
            s = s.substring("http://github.com/".length());
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.contains("/") ? s : "marid4/CrbLauncher";
    }

    private static int parseBuildNumberFromTag(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        try {
            // Contoh tag: v3.9.25 atau v3.9-25 atau build-25
            String[] parts = tag.split("[.\\-_]");
            if (parts.length > 0) {
                String last = parts[parts.length - 1].replaceAll("[^0-9]", "");
                if (!last.isEmpty()) {
                    return Integer.parseInt(last);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static long parseIsoTimestampMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(iso);
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
