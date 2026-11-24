package com.crb.tv;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Pengelola Addon AdGuard Home & DNS untuk Crb Launcher (com.crb.tv):
 * - Memantau status & statistik pemblokiran iklan dari server AdGuard Home (REST API /control/status & /control/stats)
 * - Mengaktifkan / menonaktifkan Proteksi AdGuard Home langsung dari remote TV
 * - Menyediakan preset DNS & Private DNS (DoT) (AdGuard DNS, AdGuard Family, Cloudflare, Google, Custom)
 * - Menguji latensi DNS (ping ms) serta menerapkan Private DNS Android bila diizinkan sistem.
 */
public final class AdGuardDnsManager {

    public static final String[] DNS_PRESET_NAMES = new String[]{
            "AdGuard DNS Default (Blokir Iklan & Tracker)",
            "AdGuard DNS Family (Blokir Iklan + Konten Dewasa)",
            "AdGuard DNS Unfiltered (Tanpa Filter)",
            "Cloudflare DNS (1.1.1.1 - Cepat)",
            "Google Public DNS (8.8.8.8)",
            "Custom Server / AdGuard Home Lokal"
    };

    public static final String[] DNS_PRESET_DOT_HOSTS = new String[]{
            "dns.adguard-dns.com",
            "family.adguard-dns.com",
            "unfiltered.adguard-dns.com",
            "1dot1dot1dot1.cloudflare-dns.com",
            "dns.google",
            ""
    };

    public static final String[] DNS_PRESET_IPS = new String[]{
            "94.140.14.14 / 94.140.15.15",
            "94.140.14.15 / 94.140.15.16",
            "94.140.14.140 / 94.140.14.141",
            "1.1.1.1 / 1.0.0.1",
            "8.8.8.8 / 8.8.4.4",
            "IP Server Lokal / Custom"
    };

    public static final class AdGuardStatus {
        public final boolean connected;
        public final boolean protectionEnabled;
        public final String version;
        public final String dnsAddresses;
        public final long totalQueries;
        public final long blockedFiltering;
        public final String errorMessage;

        public AdGuardStatus(
                boolean connected,
                boolean protectionEnabled,
                String version,
                String dnsAddresses,
                long totalQueries,
                long blockedFiltering,
                String errorMessage) {
            this.connected = connected;
            this.protectionEnabled = protectionEnabled;
            this.version = version;
            this.dnsAddresses = dnsAddresses;
            this.totalQueries = totalQueries;
            this.blockedFiltering = blockedFiltering;
            this.errorMessage = errorMessage;
        }
    }

    public static String normalizeServerUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return "http://192.168.1.2:3000";
        }
        String u = rawUrl.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static AdGuardStatus fetchAdGuardStatusSync(String rawUrl, String username, String password) {
        String baseUrl = normalizeServerUrl(rawUrl);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/control/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            applyBasicAuth(conn, username, password);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                return new AdGuardStatus(false, false, "-", "-", 0, 0,
                        "Login AdGuard Home ditolak (HTTP " + code + "). Periksa Username/Password.");
            }
            if (code != 200) {
                return new AdGuardStatus(false, false, "-", "-", 0, 0,
                        "AdGuard Home merespons HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject statusJson = new JSONObject(sb.toString());
            boolean prot = statusJson.optBoolean("protection_enabled", false);
            String ver = statusJson.optString("version", "-");
            String dnsPort = String.valueOf(statusJson.optInt("dns_port", 53));

            long queries = 0;
            long blocked = 0;
            try {
                long[] stats = fetchAdGuardStatsInternal(baseUrl, username, password);
                queries = stats[0];
                blocked = stats[1];
            } catch (Exception ignored) {
            }

            return new AdGuardStatus(true, prot, ver, "Port " + dnsPort, queries, blocked, null);
        } catch (Exception e) {
            return new AdGuardStatus(false, false, "-", "-", 0, 0,
                    "Tidak dapat terhubung ke " + baseUrl + " (" + e.getMessage() + ")");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long[] fetchAdGuardStatsInternal(String baseUrl, String username, String password) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/control/stats");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            applyBasicAuth(conn, username, password);

            if (conn.getResponseCode() != 200) {
                return new long[]{0L, 0L};
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject obj = new JSONObject(sb.toString());
            long total = obj.optLong("num_dns_queries", 0L);
            long blocked = obj.optLong("num_blocked_filtering", 0L);
            return new long[]{total, blocked};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean setAdGuardProtectionSync(String rawUrl, String username, String password, boolean enabled) {
        String baseUrl = normalizeServerUrl(rawUrl);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/control/protection");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("Content-Type", "application/json");
            applyBasicAuth(conn, username, password);

            String payload = "{\"enabled\":" + (enabled ? "true" : "false") + "}";
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String testDnsConnectivitySync(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.trim().isEmpty()) {
            return "Host/IP DNS kosong";
        }
        String target = hostOrIp.trim().split("/")[0].trim();
        long start = System.currentTimeMillis();
        try {
            InetAddress addr = InetAddress.getByName(target);
            long resolveMs = System.currentTimeMillis() - start;

            // Uji koneksi ke port DoT (853) atau DNS (53) atau HTTPS (443)
            int[] ports = new int[]{853, 53, 443};
            for (int port : ports) {
                Socket sock = new Socket();
                try {
                    long s2 = System.currentTimeMillis();
                    sock.connect(new InetSocketAddress(addr, port), 2500);
                    long pingMs = System.currentTimeMillis() - s2;
                    sock.close();
                    return "ONLINE (" + addr.getHostAddress() + ":" + port + ") | Latensi: " + Math.max(1, pingMs) + " ms";
                } catch (Exception ignored) {
                    try { sock.close(); } catch (Exception ignored2) {}
                }
            }
            return "Terjangkau (" + addr.getHostAddress() + ") | Resolve: " + resolveMs + " ms";
        } catch (Exception e) {
            return "Gagal terhubung ke " + target + " (" + e.getMessage() + ")";
        }
    }

    public static boolean tryApplyAndroidPrivateDns(Context context, String dotHostname) {
        if (context == null || dotHostname == null || dotHostname.trim().isEmpty()) return false;
        try {
            Settings.Global.putString(context.getContentResolver(), "private_dns_mode", "hostname");
            Settings.Global.putString(context.getContentResolver(), "private_dns_specifier", dotHostname.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void applyBasicAuth(HttpURLConnection conn, String username, String password) {
        if (username != null && !username.trim().isEmpty()) {
            String creds = username.trim() + ":" + (password != null ? password : "");
            String encoded = Base64.encodeToString(creds.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }
    }
}