package com.crb.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pengelola Jadwal Matches & Klasemen (Table) Sepak Bola + MotoGP dari ESPN API:
 * Mengambil data real-time eksklusif dari ESPN (site.api.espn.com) lengkap dengan
 * URL Icon Logo Klub untuk ditampilkan di pojok kanan bawah dan dialog Table/Matches.
 */
public final class FootballScheduleManager {

    public static final String FILTER_ALL = "ALL";
    public static final String FILTER_LIVE = "LIVE";
    public static final String FILTER_TOP5 = "TOP5";
    public static final String FILTER_INDONESIA = "INDONESIA";
    public static final String FILTER_ASIA = "ASIA";
    public static final String FILTER_UCL = "UCL";
    public static final String FILTER_UEFA = "UEFA";
    public static final String FILTER_MOTOGP = "MOTOGP";

    public static final String[] STANDINGS_LEAGUE_NAMES = new String[]{
            "England - Premier League",
            "Spain - LaLiga",
            "Italy - Serie A",
            "Germany - Bundesliga",
            "France - Ligue 1",
            "Indonesia - BRI Liga 1",
            "UEFA Champions League",
            "UEFA Europa League"
    };

    public static final String[] STANDINGS_LEAGUE_SLUGS = new String[]{
            "eng.1",
            "esp.1",
            "ita.1",
            "ger.1",
            "fra.1",
            "idn.1",
            "uefa.champions",
            "uefa.europa"
    };

    public static final class FootballMatch {
        public final String leagueName;
        public final String leagueSlug;
        public final String leagueGroup;   // TOP5, INDONESIA, ASIA, UCL, UEFA, MOTOGP
        public final String matchCategory; // LIVE, UPCOMING, RESULT
        public final String homeTeam;
        public final String awayTeam;
        public final String homeLogoUrl;
        public final String awayLogoUrl;
        public final String homeScore;
        public final String awayScore;
        public final String statusText;
        public final long kickoffEpochMillis;
        public final String kickoffWibFormatted;
        public final String statusBadge;

        public FootballMatch(
                String leagueName,
                String leagueSlug,
                String leagueGroup,
                String matchCategory,
                String homeTeam,
                String awayTeam,
                String homeLogoUrl,
                String awayLogoUrl,
                String homeScore,
                String awayScore,
                String statusText,
                long kickoffEpochMillis,
                String kickoffWibFormatted,
                String statusBadge) {
            this.leagueName = leagueName;
            this.leagueSlug = leagueSlug;
            this.leagueGroup = leagueGroup;
            this.matchCategory = matchCategory;
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.homeLogoUrl = homeLogoUrl != null ? homeLogoUrl : "";
            this.awayLogoUrl = awayLogoUrl != null ? awayLogoUrl : "";
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.statusText = statusText;
            this.kickoffEpochMillis = kickoffEpochMillis;
            this.kickoffWibFormatted = kickoffWibFormatted;
            this.statusBadge = statusBadge;
        }

        public String getDisplayHeadline() {
            if (awayTeam == null || awayTeam.trim().isEmpty()) {
                return homeTeam;
            }
            if ("LIVE".equals(matchCategory) || "RESULT".equals(matchCategory)) {
                return homeTeam + "  " + homeScore + " - " + awayScore + "  " + awayTeam;
            }
            return homeTeam + " vs " + awayTeam;
        }
    }

    public static final class StandingRow {
        public final int rank;
        public final String teamName;
        public final String logoUrl;
        public final String played;
        public final String won;
        public final String drawn;
        public final String lost;
        public final String gd;
        public final String points;

        public StandingRow(
                int rank, String teamName, String logoUrl,
                String played, String won, String drawn, String lost,
                String gd, String points) {
            this.rank = rank;
            this.teamName = teamName;
            this.logoUrl = logoUrl != null ? logoUrl : "";
            this.played = played;
            this.won = won;
            this.drawn = drawn;
            this.lost = lost;
            this.gd = gd;
            this.points = points;
        }
    }

    private final List<FootballMatch> matches = new ArrayList<>();
    private int currentIndex = 0;
    private String activeFilter = FILTER_ALL;
    private boolean hasOnlineSyncSucceeded = false;
    private String lastSyncTimeWib = "-";

    public FootballScheduleManager() {
        loadFallbackMatches();
    }

    public synchronized void setFilterSlug(String filter) {
        this.activeFilter = (filter == null || filter.isEmpty()) ? FILTER_ALL : filter;
        this.currentIndex = 0;
    }

    public synchronized String getFilterSlug() {
        return activeFilter;
    }

    public synchronized boolean isOnlineSyncActive() {
        return hasOnlineSyncSucceeded;
    }

    public synchronized String getLastSyncTimeWib() {
        return lastSyncTimeWib;
    }

    public synchronized List<FootballMatch> getMatches() {
        if (matches.isEmpty()) {
            loadFallbackMatches();
        }
        List<FootballMatch> filtered = new ArrayList<>();
        for (FootballMatch m : matches) {
            if (FILTER_ALL.equalsIgnoreCase(activeFilter)) {
                filtered.add(m);
            } else if (FILTER_LIVE.equalsIgnoreCase(activeFilter) && "LIVE".equalsIgnoreCase(m.matchCategory)) {
                filtered.add(m);
            } else if (activeFilter.equalsIgnoreCase(m.leagueGroup)) {
                filtered.add(m);
            }
        }
        if (filtered.isEmpty()) {
            return new ArrayList<>(matches);
        }
        return filtered;
    }

    public synchronized int countByGroup(String group) {
        int count = 0;
        for (FootballMatch m : matches) {
            if (group.equalsIgnoreCase(m.leagueGroup)) {
                count++;
            }
        }
        return count;
    }

    public synchronized int countLive() {
        int count = 0;
        for (FootballMatch m : matches) {
            if ("LIVE".equalsIgnoreCase(m.matchCategory)) {
                count++;
            }
        }
        return count;
    }

    public synchronized FootballMatch getNextRotatedMatch() {
        List<FootballMatch> activeList = getMatches();
        if (activeList.isEmpty()) return null;
        if (currentIndex >= activeList.size()) {
            currentIndex = 0;
        }
        FootballMatch match = activeList.get(currentIndex);
        currentIndex = (currentIndex + 1) % activeList.size();
        return match;
    }

    public synchronized FootballMatch peekNextMatch() {
        List<FootballMatch> activeList = getMatches();
        if (activeList.isEmpty()) return null;
        int idx = currentIndex % activeList.size();
        return activeList.get(idx);
    }

    public synchronized int getCurrentDisplayIndex() {
        List<FootballMatch> activeList = getMatches();
        if (activeList.isEmpty()) return 0;
        return (currentIndex == 0 ? activeList.size() : currentIndex);
    }

    public synchronized int getActiveMatchCount() {
        return getMatches().size();
    }

    /**
     * Sinkronisasi eksklusif dari ESPN Scoreboard API untuk:
     * 5 Liga Top Eropa, Liga Utama Indonesia, Kompetisi Asia, Liga Champions, Liga UEFA & MotoGP.
     */
    public void refreshOnlineSchedulesSync(List<String> ignoredConfig) {
        List<FootballMatch> fetched = new ArrayList<>();

        fetchEspnSoccerScoreboard("eng.1", "England - Premier League", FILTER_TOP5, fetched);
        fetchEspnSoccerScoreboard("esp.1", "Spain - LaLiga", FILTER_TOP5, fetched);
        fetchEspnSoccerScoreboard("ita.1", "Italy - Serie A", FILTER_TOP5, fetched);
        fetchEspnSoccerScoreboard("ger.1", "Germany - Bundesliga", FILTER_TOP5, fetched);
        fetchEspnSoccerScoreboard("fra.1", "France - Ligue 1", FILTER_TOP5, fetched);
        fetchEspnSoccerScoreboard("idn.1", "Indonesia - BRI Liga 1", FILTER_INDONESIA, fetched);
        fetchEspnSoccerScoreboard("afc.champions", "AFC Champions League", FILTER_ASIA, fetched);
        fetchEspnSoccerScoreboard("uefa.champions", "UEFA Champions League", FILTER_UCL, fetched);
        fetchEspnSoccerScoreboard("uefa.europa", "UEFA Europa League", FILTER_UEFA, fetched);
        fetchEspnMotoGpScoreboard(fetched);

        if (!fetched.isEmpty()) {
            Collections.sort(fetched, new Comparator<FootballMatch>() {
                @Override
                public int compare(FootballMatch a, FootballMatch b) {
                    int catA = categoryPriority(a.matchCategory);
                    int catB = categoryPriority(b.matchCategory);
                    if (catA != catB) return Integer.compare(catA, catB);
                    if ("RESULT".equalsIgnoreCase(a.matchCategory)) {
                        return Long.compare(b.kickoffEpochMillis, a.kickoffEpochMillis);
                    }
                    return Long.compare(a.kickoffEpochMillis, b.kickoffEpochMillis);
                }
            });

            Calendar nowWib = Calendar.getInstance(TimeZone.getTimeZone("GMT+7"));
            String syncStamp = String.format(Locale.US, "%02d:%02d",
                    nowWib.get(Calendar.HOUR_OF_DAY), nowWib.get(Calendar.MINUTE));

            synchronized (this) {
                matches.clear();
                matches.addAll(fetched);
                currentIndex = 0;
                hasOnlineSyncSucceeded = true;
                lastSyncTimeWib = syncStamp;
            }
        }
    }

    private int categoryPriority(String cat) {
        if ("LIVE".equalsIgnoreCase(cat)) return 0;
        if ("UPCOMING".equalsIgnoreCase(cat)) return 1;
        return 2;
    }

    private void fetchEspnSoccerScoreboard(String leagueSlug, String displayLeague, String group, List<FootballMatch> out) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/" + leagueSlug + "/scoreboard");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "CrbLauncher/3.8");

            if (conn.getResponseCode() != 200) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray events = root.optJSONArray("events");
            if (events == null) return;

            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat wibFmt = new SimpleDateFormat("dd/MM HH:mm 'WIB'", Locale.US);
            wibFmt.setTimeZone(TimeZone.getTimeZone("GMT+7"));
            SimpleDateFormat wibShort = new SimpleDateFormat("HH:mm 'WIB'", Locale.US);
            wibShort.setTimeZone(TimeZone.getTimeZone("GMT+7"));

            for (int i = 0; i < Math.min(events.length(), 10); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;
                JSONArray comps = ev.optJSONArray("competitions");
                if (comps == null || comps.length() == 0) continue;
                JSONObject comp = comps.optJSONObject(0);
                JSONArray competitors = comp.optJSONArray("competitors");
                if (competitors == null || competitors.length() < 2) continue;

                String home = "Home";
                String away = "Away";
                String homeLogo = "";
                String awayLogo = "";
                String sHome = "0";
                String sAway = "0";

                for (int c = 0; c < competitors.length(); c++) {
                    JSONObject teamObj = competitors.optJSONObject(c);
                    String side = teamObj.optString("homeAway", "");
                    JSONObject t = teamObj.optJSONObject("team");
                    String tName = sanitizeAscii(t != null ? t.optString("shortDisplayName", t.optString("displayName", "Team")) : "Team");
                    String tLogo = "";
                    if (t != null) {
                        tLogo = sanitizeAscii(t.optString("logo", ""));
                        if (tLogo.isEmpty()) {
                            JSONArray logos = t.optJSONArray("logos");
                            if (logos != null && logos.length() > 0) {
                                JSONObject l0 = logos.optJSONObject(0);
                                if (l0 != null) tLogo = sanitizeAscii(l0.optString("href", ""));
                            }
                        }
                    }
                    String sc = sanitizeAscii(teamObj.optString("score", "0"));
                    if ("home".equalsIgnoreCase(side)) {
                        home = tName;
                        homeLogo = tLogo;
                        sHome = sc;
                    } else {
                        away = tName;
                        awayLogo = tLogo;
                        sAway = sc;
                    }
                }

                JSONObject statusObj = ev.optJSONObject("status");
                JSONObject statusType = statusObj != null ? statusObj.optJSONObject("type") : null;
                String state = statusType != null ? statusType.optString("state", "pre") : "pre";
                String shortDetail = sanitizeAscii(statusType != null ? statusType.optString("shortDetail", "") : "");

                long epochMs = System.currentTimeMillis();
                String wibLong = "WIB";
                String wibTime = "WIB";
                try {
                    String dateStr = ev.optString("date", "");
                    Date d = isoFmt.parse(dateStr);
                    if (d != null) {
                        epochMs = d.getTime();
                        wibLong = wibFmt.format(d);
                        wibTime = wibShort.format(d);
                    }
                } catch (Exception ignored) {
                }

                if ("in".equalsIgnoreCase(state)) {
                    addUniqueMatch(out, new FootballMatch(
                            displayLeague, leagueSlug, group, "LIVE",
                            home, away, homeLogo, awayLogo, sHome, sAway, shortDetail,
                            epochMs, "[LIVE " + shortDetail + "] Skor: " + sHome + " - " + sAway + " | ESPN",
                            "LIVE " + shortDetail
                    ));
                } else if ("post".equalsIgnoreCase(state)) {
                    addUniqueMatch(out, new FootballMatch(
                            displayLeague, leagueSlug, group, "RESULT",
                            home, away, homeLogo, awayLogo, sHome, sAway, "FT",
                            epochMs, "[HASIL FT] Skor: " + sHome + " - " + sAway + " | " + wibLong + " | ESPN",
                            "HASIL FT"
                    ));
                } else {
                    addUniqueMatch(out, new FootballMatch(
                            displayLeague, leagueSlug, group, "UPCOMING",
                            home, away, homeLogo, awayLogo, "-", "-", wibTime,
                            epochMs, "[JADWAL] Kickoff " + wibLong + " | ESPN",
                            "JADWAL " + wibTime
                    ));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void fetchEspnMotoGpScoreboard(List<FootballMatch> out) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://site.api.espn.com/apis/site/v2/sports/racing/motogp/scoreboard");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "CrbLauncher/3.8");

            if (conn.getResponseCode() != 200) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray events = root.optJSONArray("events");
            if (events == null) return;

            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat wibFmt = new SimpleDateFormat("dd/MM HH:mm 'WIB'", Locale.US);
            wibFmt.setTimeZone(TimeZone.getTimeZone("GMT+7"));

            String motoLogo = "https://a.espncdn.com/i/teamlogos/leagues/500/motogp.png";
            for (int i = 0; i < Math.min(events.length(), 4); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;
                String evName = sanitizeAscii(ev.optString("name", "MotoGP Grand Prix"));
                long epochMs = System.currentTimeMillis();
                String wibLong = "WIB";
                try {
                    Date d = isoFmt.parse(ev.optString("date", ""));
                    if (d != null) {
                        epochMs = d.getTime();
                        wibLong = wibFmt.format(d);
                    }
                } catch (Exception ignored) {
                }

                addUniqueMatch(out, new FootballMatch(
                        "Motorsport - MotoGP", "motogp", FILTER_MOTOGP, "UPCOMING",
                        evName, "", motoLogo, "", "-", "-", wibLong,
                        epochMs, "[MOTOGP] " + wibLong + " | ESPN",
                        "MOTOGP"
                ));
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Mengambil Klasemen (Table) resmi dari ESPN Standings API untuk liga pilihan.
     */
    public List<StandingRow> fetchLeagueStandingsSync(String leagueSlug) {
        List<StandingRow> rows = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://site.api.espn.com/apis/v2/sports/soccer/" + leagueSlug + "/standings");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "CrbLauncher/3.8");

            if (conn.getResponseCode() != 200) return rows;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray children = root.optJSONArray("children");
            if (children == null || children.length() == 0) return rows;

            for (int c = 0; c < children.length(); c++) {
                JSONObject child = children.optJSONObject(c);
                if (child == null) continue;
                JSONObject standings = child.optJSONObject("standings");
                if (standings == null) continue;
                JSONArray entries = standings.optJSONArray("entries");
                if (entries == null) continue;

                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) continue;
                    JSONObject team = entry.optJSONObject("team");
                    String teamName = sanitizeAscii(team != null ? team.optString("shortDisplayName", team.optString("displayName", "Club")) : "Club");
                    String logoUrl = "";
                    if (team != null) {
                        JSONArray logos = team.optJSONArray("logos");
                        if (logos != null && logos.length() > 0) {
                            JSONObject l0 = logos.optJSONObject(0);
                            if (l0 != null) logoUrl = sanitizeAscii(l0.optString("href", ""));
                        }
                    }

                    int rank = rows.size() + 1;
                    String gp = "0";
                    String w = "0";
                    String d = "0";
                    String l = "0";
                    String gd = "0";
                    String pts = "0";

                    JSONArray stats = entry.optJSONArray("stats");
                    if (stats != null) {
                        for (int s = 0; s < stats.length(); s++) {
                            JSONObject st = stats.optJSONObject(s);
                            if (st == null) continue;
                            String name = st.optString("name", "");
                            String val = sanitizeAscii(st.optString("displayValue", st.optString("value", "0")));
                            if ("rank".equalsIgnoreCase(name)) {
                                try {
                                    rank = (int) Double.parseDouble(val);
                                } catch (Exception ignored) {
                                }
                            } else if ("gamesPlayed".equalsIgnoreCase(name)) {
                                gp = val;
                            } else if ("wins".equalsIgnoreCase(name)) {
                                w = val;
                            } else if ("ties".equalsIgnoreCase(name)) {
                                d = val;
                            } else if ("losses".equalsIgnoreCase(name)) {
                                l = val;
                            } else if ("pointDifferential".equalsIgnoreCase(name)) {
                                gd = val;
                            } else if ("points".equalsIgnoreCase(name)) {
                                pts = val;
                            }
                        }
                    }

                    rows.add(new StandingRow(rank, teamName, logoUrl, gp, w, d, l, gd, pts));
                }
                if (!rows.isEmpty()) break;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return rows;
    }

    private void addUniqueMatch(List<FootballMatch> list, FootballMatch candidate) {
        if (candidate.homeTeam.isEmpty()) return;
        String key = (candidate.homeTeam + "_vs_" + candidate.awayTeam).toLowerCase(Locale.US);
        for (int i = 0; i < list.size(); i++) {
            FootballMatch existing = list.get(i);
            String exKey = (existing.homeTeam + "_vs_" + existing.awayTeam).toLowerCase(Locale.US);
            if (key.equals(exKey)) {
                return;
            }
        }
        list.add(candidate);
    }

    private static String sanitizeAscii(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 32 && c < 127) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private void loadFallbackMatches() {
        matches.clear();
        long nowMs = System.currentTimeMillis();

        matches.add(new FootballMatch(
                "England - Premier League", "eng.1", FILTER_TOP5, "UPCOMING",
                "Arsenal", "Liverpool",
                "https://a.espncdn.com/i/teamlogos/soccer/500/359.png",
                "https://a.espncdn.com/i/teamlogos/soccer/500/364.png",
                "-", "-", "23:30 WIB",
                nowMs + 3600000L, "[JADWAL] Hari Ini 23:30 WIB | ESPN", "JADWAL 23:30"
        ));
        matches.add(new FootballMatch(
                "Spain - LaLiga", "esp.1", FILTER_TOP5, "UPCOMING",
                "Real Madrid", "Barcelona",
                "https://a.espncdn.com/i/teamlogos/soccer/500/86.png",
                "https://a.espncdn.com/i/teamlogos/soccer/500/83.png",
                "-", "-", "03:00 WIB",
                nowMs + 7200000L, "[JADWAL] Besok 03:00 WIB | ESPN", "JADWAL 03:00"
        ));
        matches.add(new FootballMatch(
                "UEFA Champions League", "uefa.champions", FILTER_UCL, "UPCOMING",
                "Bayern Munich", "Inter Milan",
                "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
                "https://a.espncdn.com/i/teamlogos/soccer/500/110.png",
                "-", "-", "03:00 WIB",
                nowMs + 10800000L, "[JADWAL] 03:00 WIB | ESPN", "JADWAL 03:00"
        ));
        matches.add(new FootballMatch(
                "Indonesia - BRI Liga 1", "idn.1", FILTER_INDONESIA, "UPCOMING",
                "Persib Bandung", "Persija Jakarta",
                "https://a.espncdn.com/i/teamlogos/soccer/500/20891.png",
                "https://a.espncdn.com/i/teamlogos/soccer/500/20893.png",
                "-", "-", "19:00 WIB",
                nowMs + 14400000L, "[JADWAL] 19:00 WIB | ESPN", "JADWAL 19:00"
        ));
    }
}
