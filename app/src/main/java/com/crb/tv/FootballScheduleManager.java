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

    public static final String[] FAV_EURO_TEAMS = new String[]{
            "Man United", "Liverpool", "Arsenal", "Man City", "Chelsea",
            "Real Madrid", "Barcelona", "Inter Milan", "AC Milan", "Juventus",
            "Bayern Munich", "PSG"
    };

    public static final String[] FAV_EURO_SLUGS = new String[]{
            "eng.1", "eng.1", "eng.1", "eng.1", "eng.1",
            "esp.1", "esp.1", "ita.1", "ita.1", "ita.1",
            "ger.1", "fra.1"
    };

    public static final String[] FAV_INDO_TEAMS = new String[]{
            "Persib", "Persija", "Persebaya", "Bali United", "Dewa United",
            "Madura United", "PSM Makassar", "Borneo FC", "Arema FC", "Persita"
    };

    public static final class FifaRankRow {
        public final int rank;
        public final String country;
        public final String points;
        public final String delta;

        public FifaRankRow(int rank, String country, String points, String delta) {
            this.rank = rank;
            this.country = country;
            this.points = points;
            this.delta = delta;
        }
    }

    private volatile String latestNewsSummary = "";

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
        for (FootballMatch m : fetchIndonesiaLiga1MatchesSync()) {
            addUniqueMatch(fetched, m);
        }
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
        if ("idn.1".equalsIgnoreCase(leagueSlug)) {
            List<StandingRow> indoRows = fetchIndonesiaLiga1StandingsSync();
            if (!indoRows.isEmpty()) {
                return indoRows;
            }
        }
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

    public String getNewsSummaryText() {
        return latestNewsSummary;
    }

    public String fetchFootballNewsSync() {
        String[] newsUrls = new String[]{
                "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/news?limit=5",
                "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/news?limit=4",
                "https://site.api.espn.com/apis/site/v2/sports/soccer/idn.1/news?limit=3"
        };
        List<String> headlines = new ArrayList<>();
        for (String urlStr : newsUrls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray articles = root.optJSONArray("articles");
                    if (articles != null) {
                        for (int i = 0; i < articles.length(); i++) {
                            JSONObject art = articles.optJSONObject(i);
                            if (art == null) continue;
                            String hl = sanitizeAscii(art.optString("headline", ""));
                            if (!hl.isEmpty() && !headlines.contains(hl)) {
                                headlines.add(hl);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (headlines.isEmpty()) {
            latestNewsSummary = "Berita Bola ESPN: Update Jadwal Premier League, Liga Champions & BRI Liga 1 tersedia di Card Bola";
        } else {
            StringBuilder out = new StringBuilder("Berita Bola ESPN: ");
            int limit = Math.min(8, headlines.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) out.append("  *  ");
                out.append(headlines.get(i));
            }
            latestNewsSummary = out.toString();
        }
        return latestNewsSummary;
    }

    public List<FootballMatch> fetchLeagueWindowMatchesSync(String leagueName, String leagueSlug, String group, int daysBack, int daysAhead) {
        if ("idn.1".equalsIgnoreCase(leagueSlug)) {
            List<FootballMatch> indoList = fetchIndonesiaLiga1MatchesSync();
            if (!indoList.isEmpty()) {
                return indoList;
            }
        }
        List<FootballMatch> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("GMT+7"));
            long now = System.currentTimeMillis();
            String start = fmt.format(new Date(now - daysBack * 86400000L));
            String end = fmt.format(new Date(now + daysAhead * 86400000L));
            String urlStr = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
                    + leagueSlug + "/scoreboard?dates=" + start + "-" + end;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(6500);
            conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject root = new JSONObject(sb.toString());
                JSONArray events = root.optJSONArray("events");
                if (events != null) {
                    SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                    isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                    SimpleDateFormat wibFmt = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);
                    wibFmt.setTimeZone(TimeZone.getTimeZone("GMT+7"));
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject ev = events.optJSONObject(i);
                        if (ev == null) continue;
                        JSONArray comps = ev.optJSONArray("competitions");
                        if (comps == null || comps.length() == 0) continue;
                        JSONObject comp = comps.optJSONObject(0);
                        JSONArray competitors = comp.optJSONArray("competitors");
                        if (competitors == null || competitors.length() < 2) continue;

                        String hName = "", aName = "", hLogo = "", aLogo = "", hScore = "-", aScore = "-";
                        for (int c = 0; c < competitors.length(); c++) {
                            JSONObject tmObj = competitors.optJSONObject(c);
                            if (tmObj == null) continue;
                            String ha = tmObj.optString("homeAway", "");
                            JSONObject team = tmObj.optJSONObject("team");
                            String nm = team != null ? sanitizeAscii(team.optString("shortDisplayName", team.optString("displayName", ""))) : "";
                            String lg = team != null ? team.optString("logo", "") : "";
                            String sc = sanitizeAscii(tmObj.optString("score", "-"));
                            if ("home".equalsIgnoreCase(ha)) {
                                hName = nm; hLogo = lg; hScore = sc.isEmpty() ? "-" : sc;
                            } else {
                                aName = nm; aLogo = lg; aScore = sc.isEmpty() ? "-" : sc;
                            }
                        }

                        JSONObject statusObj = ev.optJSONObject("status");
                        JSONObject statusType = statusObj != null ? statusObj.optJSONObject("type") : null;
                        String state = statusType != null ? statusType.optString("state", "pre") : "pre";
                        String shortDetail = statusType != null ? sanitizeAscii(statusType.optString("shortDetail", "")) : "";
                        String cat = "UPCOMING";
                        if ("in".equalsIgnoreCase(state)) cat = "LIVE";
                        else if ("post".equalsIgnoreCase(state)) cat = "RESULT";

                        long epochMs = 0L;
                        String wibStr = shortDetail;
                        String dateIso = ev.optString("date", "");
                        if (dateIso.length() >= 16) {
                            try {
                                Date d = isoFmt.parse(dateIso.substring(0, 16));
                                if (d != null) {
                                    epochMs = d.getTime();
                                    wibStr = wibFmt.format(d) + " WIB";
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        out.add(new FootballMatch(
                                leagueName, leagueSlug, group, cat,
                                hName, aName, hLogo, aLogo, hScore, aScore,
                                shortDetail, epochMs, wibStr,
                                "LIVE".equals(cat) ? "LIVE" : ("RESULT".equals(cat) ? "FT" : wibStr)
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    public List<FootballMatch> filterMatchesByTeam(List<FootballMatch> source, String teamKeyword) {
        List<FootballMatch> out = new ArrayList<>();
        if (source == null || teamKeyword == null) return out;
        String kw = teamKeyword.toLowerCase(Locale.US).trim();
        for (FootballMatch m : source) {
            if (m.homeTeam.toLowerCase(Locale.US).contains(kw)
                    || m.awayTeam.toLowerCase(Locale.US).contains(kw)) {
                out.add(m);
            }
        }
        return out;
    }

    public synchronized List<FootballMatch> getUclMatches() {
        List<FootballMatch> out = new ArrayList<>();
        for (FootballMatch m : matches) {
            if (FILTER_UCL.equals(m.leagueGroup) || "uefa.champions".equals(m.leagueSlug)) {
                out.add(m);
            }
        }
        return out;
    }

    public List<FifaRankRow> fetchFifaRankingsSync(boolean aseanOnly) {
        List<FifaRankRow> worldTop = new ArrayList<>();
        List<FifaRankRow> aseanList = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world/rankings");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject root = new JSONObject(sb.toString());
                JSONArray rankings = root.optJSONArray("rankings");
                if (rankings != null && rankings.length() > 0) {
                    JSONObject r0 = rankings.optJSONObject(0);
                    JSONArray entries = r0 != null ? r0.optJSONArray("ranks") : null;
                    if (entries != null) {
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject e = entries.optJSONObject(i);
                            if (e == null) continue;
                            int rk = e.optInt("current", i + 1);
                            int prev = e.optInt("previous", rk);
                            int diff = prev - rk;
                            String delta = diff > 0 ? ("+" + diff) : (diff < 0 ? String.valueOf(diff) : "-");
                            String pts = String.valueOf((int) Math.round(e.optDouble("points", 0)));
                            JSONObject tm = e.optJSONObject("team");
                            String name = tm != null ? sanitizeAscii(tm.optString("name", tm.optString("nickname", ""))) : "";
                            if (name.isEmpty()) continue;
                            if (rk <= 10) {
                                worldTop.add(new FifaRankRow(rk, name, pts, delta));
                            }
                            String low = name.toLowerCase(Locale.US);
                            if (low.contains("thailand") || low.contains("vietnam")
                                    || low.contains("indonesia") || low.contains("malaysia")
                                    || low.contains("singapore")) {
                                aseanList.add(new FifaRankRow(rk, name, pts, delta));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (worldTop.isEmpty()) {
            worldTop.add(new FifaRankRow(1, "Spain", "2004", "-"));
            worldTop.add(new FifaRankRow(2, "Argentina", "1966", "-"));
            worldTop.add(new FifaRankRow(3, "France", "1951", "-"));
            worldTop.add(new FifaRankRow(4, "England", "1939", "-"));
            worldTop.add(new FifaRankRow(5, "Portugal", "1801", "+1"));
            worldTop.add(new FifaRankRow(6, "Brazil", "1801", "+1"));
            worldTop.add(new FifaRankRow(7, "Morocco", "1791", "-2"));
            worldTop.add(new FifaRankRow(8, "Belgium", "1790", "-"));
            worldTop.add(new FifaRankRow(9, "Netherlands", "1774", "-"));
            worldTop.add(new FifaRankRow(10, "Colombia", "1736", "-"));
        }
        if (aseanList.isEmpty()) {
            aseanList.add(new FifaRankRow(90, "Thailand", "1258", "-"));
            aseanList.add(new FifaRankRow(99, "Vietnam", "1228", "-"));
            aseanList.add(new FifaRankRow(116, "Indonesia", "1167", "-"));
            aseanList.add(new FifaRankRow(133, "Malaysia", "1103", "-"));
            aseanList.add(new FifaRankRow(149, "Singapore", "1057", "-"));
        }
        return aseanOnly ? aseanList : worldTop;
    }

    public List<FootballMatch> fetchIndonesiaLiga1MatchesSync() {
        List<FootballMatch> out = new ArrayList<>();
        String[] urls = new String[]{
                "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=4796",
                "https://www.thesportsdb.com/api/v1/json/3/eventspastleague.php?id=4796"
        };
        SimpleDateFormat srcFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        srcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat wibFmt = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);
        wibFmt.setTimeZone(TimeZone.getTimeZone("GMT+7"));

        for (int uIdx = 0; uIdx < urls.length; uIdx++) {
            boolean isPast = (uIdx == 1);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urls[uIdx]).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6500);
                conn.setReadTimeout(6500);
                conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray events = root.optJSONArray("events");
                    if (events != null) {
                        for (int i = 0; i < events.length(); i++) {
                            JSONObject ev = events.optJSONObject(i);
                            if (ev == null) continue;
                            String hName = sanitizeAscii(ev.optString("strHomeTeam", ""));
                            String aName = sanitizeAscii(ev.optString("strAwayTeam", ""));
                            if (hName.isEmpty() || aName.isEmpty()) continue;
                            String hLogo = ev.optString("strHomeTeamBadge", "");
                            String aLogo = ev.optString("strAwayTeamBadge", "");
                            String hScore = sanitizeAscii(ev.optString("intHomeScore", ""));
                            String aScore = sanitizeAscii(ev.optString("intAwayScore", ""));
                            if (hScore.isEmpty() || "null".equalsIgnoreCase(hScore)) hScore = "-";
                            if (aScore.isEmpty() || "null".equalsIgnoreCase(aScore)) aScore = "-";

                            boolean hasScore = !"-".equals(hScore) && !"-".equals(aScore);
                            String cat = (isPast || hasScore) ? "RESULT" : "UPCOMING";

                            String dateEv = ev.optString("dateEvent", "");
                            String timeEv = ev.optString("strTime", "12:00:00");
                            if (timeEv == null || timeEv.trim().isEmpty() || "null".equalsIgnoreCase(timeEv)) {
                                timeEv = "12:00:00";
                            }
                            if (timeEv.length() == 5) timeEv = timeEv + ":00";

                            long epochMs = 0L;
                            String wibStr = dateEv;
                            try {
                                String dtStr = dateEv + " " + timeEv.substring(0, Math.min(8, timeEv.length()));
                                Date d = srcFmt.parse(dtStr);
                                if (d != null) {
                                    epochMs = d.getTime();
                                    wibStr = wibFmt.format(d) + " WIB";
                                }
                            } catch (Exception ignored) {
                            }

                            addUniqueMatch(out, new FootballMatch(
                                    "Indonesia - Liga 1", "idn.1", FILTER_INDONESIA, cat,
                                    hName, aName, hLogo, aLogo, hScore, aScore,
                                    "RESULT".equals(cat) ? "FT" : wibStr,
                                    epochMs, wibStr,
                                    "RESULT".equals(cat) ? "FT" : wibStr
                            ));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return out;
    }

    public List<StandingRow> fetchIndonesiaLiga1StandingsSync() {
        List<StandingRow> rows = new ArrayList<>();
        String[] urls = new String[]{
                "https://www.thesportsdb.com/api/v1/json/3/lookuptable.php?l=4796&s=2026-2027",
                "https://www.thesportsdb.com/api/v1/json/3/lookuptable.php?l=4796"
        };
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6500);
                conn.setReadTimeout(6500);
                conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray table = root.optJSONArray("table");
                    if (table != null && table.length() > 0) {
                        for (int i = 0; i < table.length(); i++) {
                            JSONObject row = table.optJSONObject(i);
                            if (row == null) continue;
                            int rk = row.optInt("intRank", i + 1);
                            String tm = sanitizeAscii(row.optString("strTeam", ""));
                            if (tm.isEmpty()) continue;
                            String badge = row.optString("strBadge", "");
                            String gp = sanitizeAscii(row.optString("intPlayed", "0"));
                            String w = sanitizeAscii(row.optString("intWin", "0"));
                            String d = sanitizeAscii(row.optString("intDraw", "0"));
                            String l = sanitizeAscii(row.optString("intLoss", "0"));
                            String gd = sanitizeAscii(row.optString("intGoalDifference", "0"));
                            String pts = sanitizeAscii(row.optString("intPoints", "0"));
                            rows.add(new StandingRow(rk, tm, badge, gp, w, d, l, gd, pts));
                        }
                        if (!rows.isEmpty()) {
                            return rows;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // Fallback klasemen Liga 1 Indonesia 2026/27 jika TV offline
        rows.add(new StandingRow(1, "Persija Jakarta", "", "10", "7", "1", "2", "+11", "22"));
        rows.add(new StandingRow(2, "Persita Tangerang", "", "10", "6", "3", "1", "+8", "21"));
        rows.add(new StandingRow(3, "Dewa United FC", "", "10", "6", "2", "2", "+3", "20"));
        rows.add(new StandingRow(4, "Madura United FC", "", "10", "6", "1", "3", "+5", "19"));
        rows.add(new StandingRow(5, "Persib Bandung", "", "10", "6", "1", "3", "+7", "19"));
        rows.add(new StandingRow(6, "Bali United FC", "", "10", "6", "1", "3", "+16", "19"));
        rows.add(new StandingRow(7, "Bhayangkara FC", "", "10", "3", "6", "1", "+2", "15"));
        rows.add(new StandingRow(8, "Persebaya Surabaya", "", "10", "3", "5", "2", "+2", "14"));
        return rows;
    }
}
