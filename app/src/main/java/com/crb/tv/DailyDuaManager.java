package com.crb.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pengelola Addon Doa Sehari-hari & Lafadz Al-Qur'an dengan Suara (Crb Launcher):
 * - Memuat koleksi lengkap Doa Sehari-hari dan Lafadz/Ayat Al-Qur'an Pilihan (Arab, Latin, Arti)
 * - Memutar rekaman suara Qari Al-Qur'an (Syaikh Mishary Rashid Alafasy) secara streaming/cache offline
 *   untuk ayat & doa Al-Qur'an, serta suara vokal TextToSpeech untuk doa sehari-hari.
 */
public final class DailyDuaManager {

    public static final String CAT_DOA = "DOA";
    public static final String CAT_QURAN = "QURAN";

    public static final class DuaItem {
        public final String id;
        public final String category; // DOA or QURAN
        public final String title;
        public final String arabicText;
        public final String latinText;
        public final String indonesianMeaning;
        public final String sourceRef;
        public final String audioUrl; // Optional MP3 URL (e.g. EveryAyah / Islamic Network Qari)

        public DuaItem(
                String id,
                String category,
                String title,
                String arabicText,
                String latinText,
                String indonesianMeaning,
                String sourceRef,
                String audioUrl) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.arabicText = arabicText;
            this.latinText = latinText;
            this.indonesianMeaning = indonesianMeaning;
            this.sourceRef = sourceRef;
            this.audioUrl = audioUrl != null ? audioUrl : "";
        }
    }

    public interface OnAudioStateListener {
        void onAudioStatus(String statusMessage, boolean playing);
    }

    private final List<DuaItem> allItems = new ArrayList<>();
    private MediaPlayer mediaPlayer;
    private TextToSpeech ttsEngine;
    private volatile boolean isPlaying = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int rotateIndex = 0;

    public DailyDuaManager() {
        loadCollection();
    }

    public List<DuaItem> getItemsByCategory(String category) {
        List<DuaItem> out = new ArrayList<>();
        for (DuaItem item : allItems) {
            if (category == null || category.equalsIgnoreCase(item.category)) {
                out.add(item);
            }
        }
        return out;
    }

    public List<DuaItem> getAllItems() {
        return allItems;
    }

    public synchronized DuaItem getNextRotatedItem() {
        if (allItems.isEmpty()) return null;
        DuaItem item = allItems.get(rotateIndex % allItems.size());
        rotateIndex = (rotateIndex + 1) % allItems.size();
        return item;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public synchronized void stopAudio() {
        isPlaying = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        if (ttsEngine != null) {
            try {
                ttsEngine.stop();
                ttsEngine.shutdown();
            } catch (Exception ignored) {
            }
            ttsEngine = null;
        }
    }

    public synchronized void playDuaAudio(
            final Context context,
            final DuaItem item,
            final OnAudioStateListener listener) {
        stopAudio();
        if (item == null) return;
        isPlaying = true;
        ensureAudioVolume(context);

        if (!item.audioUrl.isEmpty()) {
            notifyStatus(listener, "Memutar suara Qari (" + item.title + ")...", true);
            File cacheFile = getCachedAudioFile(context, item.id);
            if (cacheFile.exists() && cacheFile.length() > 8000) {
                if (playLocalMp3(cacheFile, listener)) {
                    return;
                }
            }
            streamAndCacheMp3(context, item, cacheFile, listener);
            return;
        }

        // Untuk Doa Sehari-hari non-MP3, bacakan lafadz & artinya dengan suara vokal TTS
        notifyStatus(listener, "Membacakan " + item.title + "...", true);
        speakDuaWithTts(context, item, listener);
    }

    private void ensureAudioVolume(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (cur <= 1 && max > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(2, max / 2), 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private File getCachedAudioFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), "doa_audio");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "doa_" + id + ".mp3");
    }

    private boolean playLocalMp3(File file, final OnAudioStateListener listener) {
        FileInputStream fis = null;
        try {
            mediaPlayer = new MediaPlayer();
            configureMediaPlayer(mediaPlayer);
            fis = new FileInputStream(file);
            mediaPlayer.setDataSource(fis.getFD());
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopAudio();
                    notifyStatus(listener, "Selesai diputar", false);
                }
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
            return true;
        } catch (Exception e) {
            if (mediaPlayer != null) {
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            return false;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void streamAndCacheMp3(
            final Context context,
            final DuaItem item,
            final File cacheFile,
            final OnAudioStateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                BufferedInputStream bis = null;
                FileOutputStream fos = null;
                File tmp = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
                boolean downloaded = false;
                try {
                    URL url = new URL(item.audioUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "CrbLauncher/3.9");
                    if (conn.getResponseCode() == 200) {
                        bis = new BufferedInputStream(conn.getInputStream());
                        fos = new FileOutputStream(tmp);
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = bis.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                        fos.flush();
                        fos.close();
                        fos = null;
                        if (tmp.exists() && tmp.length() > 8000) {
                            if (cacheFile.exists()) cacheFile.delete();
                            downloaded = tmp.renameTo(cacheFile);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (bis != null) bis.close(); } catch (Exception ignored) {}
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    if (tmp.exists()) tmp.delete();
                    if (conn != null) conn.disconnect();
                }

                final boolean ok = downloaded && cacheFile.exists();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isPlaying) return;
                        if (ok && playLocalMp3(cacheFile, listener)) {
                            notifyStatus(listener, "Memutar Murottal (" + item.title + ")", true);
                        } else {
                            // Fallback ke suara vokal TTS jika TV sedang offline
                            speakDuaWithTts(context, item, listener);
                        }
                    }
                });
            }
        }, "DuaAudioDownloader").start();
    }

    private void speakDuaWithTts(
            final Context context,
            final DuaItem item,
            final OnAudioStateListener listener) {
        if (!isPlaying) return;
        try {
            ttsEngine = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (!isPlaying || ttsEngine == null) return;
                    if (status == TextToSpeech.SUCCESS) {
                        int res = ttsEngine.setLanguage(new Locale("id", "ID"));
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            ttsEngine.setLanguage(Locale.US);
                        }
                        ttsEngine.setSpeechRate(0.86f);
                        String textToRead = item.title + ". "
                                + item.latinText + ". Artinya: " + item.indonesianMeaning;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ttsEngine.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "dua_tts_" + item.id);
                        } else {
                            ttsEngine.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null);
                        }
                        notifyStatus(listener, "Membacakan suara: " + item.title, true);
                    } else {
                        stopAudio();
                        notifyStatus(listener, "Audio TTS tidak tersedia di TV ini", false);
                    }
                }
            });
        } catch (Exception e) {
            stopAudio();
            notifyStatus(listener, "Gagal memutar suara: " + e.getMessage(), false);
        }
    }

    private void configureMediaPlayer(MediaPlayer mp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build());
        } else {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

    private void notifyStatus(final OnAudioStateListener listener, final String msg, final boolean playing) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onAudioStatus(msg, playing);
            }
        });
    }

    private void loadCollection() {
        allItems.clear();

        allItems.add(new DuaItem(
                "doa_sapu_jagat", CAT_DOA,
                "1. Doa Kebaikan Dunia & Akhirat (Sapu Jagat)",
                "\u0631\u064E\u0628\u064E\u0651\u0646\u064E\u0627 \u0622\u062A\u0650\u0646\u064E\u0627 \u0641\u0650\u064A \u0627\u0644\u062F\u064F\u0651\u0646\u0652\u064A\u064E\u0627 \u062D\u064E\u0633\u064E\u0646\u064E\u0629\u064B \u0648\u064E\u0641\u0650\u064A \u0627\u0644\u0652\u0622\u062E\u0650\u0631\u064E\u0629\u0650 \u062D\u064E\u0633\u064E\u0646\u064E\u0629\u064B \u0648\u064E\u0642\u0650\u0646\u064E\u0627 \u0639\u064E\u0630\u064E\u0627\u0628\u064E \u0627\u0644\u0646\u064E\u0651\u0627\u0631\u0650",
                "Rabbana atina fid-dunya hasanatan wa fil-akhirati hasanatan wa qina 'adzaban-nar.",
                "Ya Tuhan kami, berilah kami kebaikan di dunia dan kebaikan di akhirat, dan lindungilah kami dari azab neraka.",
                "QS. Al-Baqarah: 201 (Audio Qari Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/208.mp3"
        ));

        allItems.add(new DuaItem(
                "doa_orang_tua", CAT_DOA,
                "2. Doa Untuk Kedua Orang Tua",
                "\u0631\u064E\u0628\u0650\u0651 \u0627\u063A\u0652\u0641\u0650\u0631\u0652 \u0644\u0650\u064A \u0648\u064E\u0644\u0650\u0648\u064E\u0627\u0644\u0650\u062F\u064E\u064A\u064E\u0651 \u0648\u064E\u0627\u0631\u0652\u062D\u064E\u0645\u0652\u0647\u064F\u0645\u064E\u0627 \u0643\u064E\u0645\u064E\u0627 \u0631\u064E\u0628\u064E\u0651\u064A\u064E\u0627\u0646\u0650\u064A \u0635\u064E\u063A\u0650\u064A\u0631\u064B\u0627",
                "Rabbighfir li waliwalidayya warhamhuma kama rabbayani shaghira.",
                "Ya Tuhanku, ampunilah dosaku dan dosa kedua orang tuaku, serta sayangilah mereka sebagaimana mereka menyayangiku di waktu kecil.",
                "QS. Al-Isra: 24 & Doa Harian",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_sebelum_makan", CAT_DOA,
                "3. Doa Sebelum Makan",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0628\u064E\u0627\u0631\u0650\u0643\u0652 \u0644\u064E\u0646\u064E\u0627 \u0641\u0650\u064A\u0645\u064E\u0627 \u0631\u064E\u0632\u064E\u0642\u0652\u062A\u064E\u0646\u064E\u0627 \u0648\u064E\u0642\u0650\u0646\u064E\u0627 \u0639\u064E\u0630\u064E\u0627\u0628\u064E \u0627\u0644\u0646\u064E\u0651\u0627\u0631\u0650",
                "Allahumma barik lana fima razaqtana wa qina 'adzaban-nar.",
                "Ya Allah, berkahilah rezeki yang Engkau karuniakan kepada kami dan peliharalah kami dari siksa api neraka.",
                "HR. Malik & Ibnu Sunni",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_sesudah_makan", CAT_DOA,
                "4. Doa Sesudah Makan",
                "\u0627\u0644\u0652\u062D\u064E\u0645\u0652\u062F\u064F \u0644\u0650\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u064E\u0651\u0630\u0650\u064A \u0623\u064E\u0637\u0652\u0639\u064E\u0645\u064E\u0646\u064E\u0627 \u0648\u064E\u0633\u064E\u0642\u064E\u0627\u0646\u064E\u0627 \u0648\u064E\u062C\u064E\u0639\u064E\u0644\u064E\u0646\u064E\u0627 \u0645\u0650\u0646\u064E \u0627\u0644\u0652\u0645\u064F\u0633\u0652\u0644\u0650\u0645\u0650\u064A\u0646\u064E",
                "Alhamdulillahilladzi ath'amana wa saqana wa ja'alana minal muslimin.",
                "Segala puji bagi Allah yang telah memberi kami makan dan minum serta menjadikan kami termasuk golongan orang-orang muslim.",
                "HR. Abu Dawud & Tirmidzi",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_sebelum_tidur", CAT_DOA,
                "5. Doa Sebelum Tidur",
                "\u0628\u0650\u0627\u0633\u0652\u0645\u0650\u0643\u064E \u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0623\u064E\u062D\u0652\u064A\u064E\u0627 \u0648\u064E\u0628\u0650\u0627\u0633\u0652\u0645\u0650\u0643\u064E \u0623\u064E\u0645\u064F\u0648\u062A\u064F",
                "Bismika Allahumma ahya wa bismika amut.",
                "Dengan menyebut nama-Mu ya Allah, aku hidup dan dengan menyebut nama-Mu aku mati.",
                "HR. Bukhari",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_bangun_tidur", CAT_DOA,
                "6. Doa Bangun Tidur",
                "\u0627\u0644\u0652\u062D\u064E\u0645\u0652\u062F\u064F \u0644\u0650\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u064E\u0651\u0630\u0650\u064A \u0623\u064E\u062D\u0652\u064A\u064E\u0627\u0646\u064E\u0627 \u0628\u064E\u0639\u0652\u062F\u064E \u0645\u064E\u0627 \u0623\u064E\u0645\u064E\u0627\u062A\u064E\u0646\u064E\u0627 \u0648\u064E\u0625\u0650\u0644\u064E\u064A\u0652\u0647\u0650 \u0627\u0644\u0646\u064F\u0651\u0634\u064F\u0648\u0631\u064F",
                "Alhamdulillahilladzi ahyana ba'da ma amatana wa ilaihin-nusyur.",
                "Segala puji bagi Allah yang telah menghidupkan kami kembali setelah mematikan kami, dan hanya kepada-Nya kami dibangkitkan.",
                "HR. Bukhari",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_masuk_wc", CAT_DOA,
                "7. Doa Masuk Kamar Mandi / WC",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0625\u0650\u0646\u0650\u0651\u064A \u0623\u064E\u0639\u064F\u0648\u0630\u064F \u0628\u0650\u0643\u064E \u0645\u0650\u0646\u064E \u0627\u0644\u0652\u062E\u064F\u0628\u064F\u062B\u0650 \u0648\u064E\u0627\u0644\u0652\u062E\u064E\u0628\u064E\u0627\u0626\u0650\u062B\u0650",
                "Allahumma inni a'udzu bika minal-khubutsi wal-khaba'its.",
                "Ya Allah, sesungguhnya aku berlindung kepada-Mu dari godaan setan laki-laki dan setan perempuan.",
                "HR. Bukhari & Muslim",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_keluar_wc", CAT_DOA,
                "8. Doa Keluar Kamar Mandi / WC",
                "\u063A\u064F\u0641\u0652\u0631\u064E\u0627\u0646\u064E\u0643\u064E \u0627\u0644\u0652\u062D\u064E\u0645\u0652\u062F\u064F \u0644\u0650\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u064E\u0651\u0630\u0650\u064A \u0623\u064E\u0630\u0652\u0647\u064E\u0628\u064E \u0639\u064E\u0646\u0650\u0651\u064A \u0627\u0644\u0652\u0623\u064E\u0630\u064E\u0649 \u0648\u064E\u0639\u064E\u0627\u0641\u064E\u0627\u0646\u0650\u064A",
                "Ghufranaka, alhamdulillahilladzi adzhaba 'annil-adza wa 'afani.",
                "Aku memohon ampunan-Mu. Segala puji bagi Allah yang telah menghilangkan kotoran dariku dan menyehatkanku.",
                "HR. Abu Dawud & Ibnu Majah",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_bercermin", CAT_DOA,
                "9. Doa Ketika Bercermin",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0643\u064E\u0645\u064E\u0627 \u062D\u064E\u0633\u064E\u0651\u0646\u0652\u062A\u064E \u062E\u064E\u0644\u0652\u0642\u0650\u064A \u0641\u064E\u062D\u064E\u0633\u0650\u0651\u0646\u0652 \u062E\u064F\u0644\u064F\u0642\u0650\u064A",
                "Allahumma kama hassanta khalqi fahassin khuluqi.",
                "Ya Allah, sebagaimana Engkau telah memperindah rupaku, maka perindahlah pula akhlakku.",
                "HR. Ahmad",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_berpakaian", CAT_DOA,
                "10. Doa Memakai Pakaian",
                "\u0627\u0644\u0652\u062D\u064E\u0645\u0652\u062F\u064F \u0644\u0650\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u064E\u0651\u0630\u0650\u064A \u0643\u064E\u0633\u064E\u0627\u0646\u0650\u064A \u0647\u064E\u0630\u064E\u0627 \u0648\u064E\u0631\u064E\u0632\u064E\u0642\u064E\u0646\u0650\u064A\u0647\u0650 \u0645\u0650\u0646\u0652 \u063A\u064E\u064A\u0652\u0631\u0650 \u062D\u064E\u0648\u0652\u0644\u064D \u0645\u0650\u0646\u0650\u0651\u064A \u0648\u064E\u0644\u064E\u0627 \u0642\u064F\u0648\u064E\u0651\u0629\u064D",
                "Alhamdulillahilladzi kasani hadza wa razaqanihi min ghairi haulin minni wa la quwwah.",
                "Segala puji bagi Allah yang telah memakaikan pakaian ini kepadaku dan mengaruniakannya tanpa daya dan kekuatan dariku.",
                "HR. Abu Dawud & Tirmidzi",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_masuk_rumah", CAT_DOA,
                "11. Doa Masuk Rumah",
                "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u0648\u064E\u0644\u064E\u062C\u0652\u0646\u064E\u0627 \u0648\u064E\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u062E\u064E\u0631\u064E\u062C\u0652\u0646\u064E\u0627 \u0648\u064E\u0639\u064E\u0644\u064E\u0649 \u0631\u064E\u0628\u0650\u0651\u0646\u064E\u0627 \u062A\u064E\u0648\u064E\u0643\u064E\u0651\u0644\u0652\u0646\u064E\u0627",
                "Bismillahi walajna wa bismillahi kharajna wa 'ala rabbina tawakkalna.",
                "Dengan nama Allah kami masuk, dengan nama Allah kami keluar, dan kepada Tuhan kami, kami bertawakal.",
                "HR. Abu Dawud",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_keluar_rumah", CAT_DOA,
                "12. Doa Keluar Rumah",
                "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u062A\u064E\u0648\u064E\u0643\u064E\u0651\u0644\u0652\u062A\u064F \u0639\u064E\u0644\u064E\u0649 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u0644\u064E\u0627 \u062D\u064E\u0648\u0652\u0644\u064E \u0648\u064E\u0644\u064E\u0627 \u0642\u064F\u0648\u064E\u0651\u0629\u064E \u0625\u0650\u0644\u064E\u0651\u0627 \u0628\u0650\u0627\u0644\u0644\u064E\u0651\u0647\u0650",
                "Bismillahi tawakkaltu 'alallahi la haula wa la quwwata illa billah.",
                "Dengan menyebut nama Allah, aku bertawakal kepada Allah; tiada daya dan kekuatan melainkan dengan pertolongan Allah.",
                "HR. Abu Dawud & Tirmidzi",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_naik_kendaraan", CAT_DOA,
                "13. Doa Naik Kendaraan / Bepergian",
                "\u0633\u064F\u0628\u0652\u062D\u064E\u0627\u0646\u064E \u0627\u0644\u064E\u0651\u0630\u0650\u064A \u0633\u064E\u062E\u064E\u0651\u0631\u064E \u0644\u064E\u0646\u064E\u0627 \u0647\u064E\u0630\u064E\u0627 \u0648\u064E\u0645\u064E\u0627 \u0643\u064F\u0646\u064E\u0651\u0627 \u0644\u064E\u0647\u064F \u0645\u064F\u0642\u0652\u0631\u0650\u0646\u0650\u064A\u0646\u064E \u0648\u064E\u0625\u0650\u0646\u064E\u0651\u0627 \u0625\u0650\u0644\u064E\u0649 \u0631\u064E\u0628\u0650\u0651\u0646\u064E\u0627 \u0644\u064E\u0645\u064F\u0646\u0652\u0642\u064E\u0644\u0650\u0628\u064F\u0648\u0646\u064E",
                "Subhanalladzi sakhkhara lana hadza wa ma kunna lahu muqrinin wa inna ila rabbina lamunqalibun.",
                "Mahasuci Allah yang telah menundukkan kendaraan ini bagi kami, padahal kami sebelumnya tidak mampu menguasainya, dan kepada Tuhan kami pasti akan kembali.",
                "QS. Az-Zukhruf: 13-14",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_setelah_wudhu", CAT_DOA,
                "14. Doa Setelah Wudhu",
                "\u0623\u064E\u0634\u0652\u0647\u064E\u062F\u064F \u0623\u064E\u0646\u0652 \u0644\u064E\u0627 \u0625\u0650\u0644\u064E\u0647\u064E \u0625\u0650\u0644\u064E\u0651\u0627 \u0627\u0644\u0644\u064E\u0651\u0647\u064F \u0648\u064E\u062D\u0652\u062F\u064E\u0647\u064F \u0644\u064E\u0627 \u0634\u064E\u0631\u0650\u064A\u0643\u064E \u0644\u064E\u0647\u064F \u0648\u064E\u0623\u064E\u0634\u0652\u0647\u064E\u062F\u064F \u0623\u064E\u0646\u064E\u0651 \u0645\u064F\u062D\u064E\u0645\u064E\u0651\u062F\u064B\u0627 \u0639\u064E\u0628\u0652\u062F\u064F\u0647\u064F \u0648\u064E\u0631\u064E\u0633\u064F\u0648\u0644\u064F\u0647\u064F",
                "Asyhadu alla ilaha illallahu wahdahu la syarika lah, wa asyhadu anna Muhammadan 'abduhu wa rasuluh.",
                "Aku bersaksi tiada Tuhan selain Allah Yang Maha Esa tanpa sekutu bagi-Nya, dan aku bersaksi bahwa Nabi Muhammad adalah hamba dan utusan-Nya.",
                "HR. Muslim",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_masuk_masjid", CAT_DOA,
                "15. Doa Masuk Masjid",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0627\u0641\u0652\u062A\u064E\u062D\u0652 \u0644\u0650\u064A \u0623\u064E\u0628\u0652\u0648\u064E\u0627\u0628\u064E \u0631\u064E\u062D\u0652\u0645\u064E\u062A\u0650\u0643\u064E",
                "Allahummaf-tah li abwaba rahmatik.",
                "Ya Allah, bukakanlah bagiku pintu-pintu rahmat-Mu.",
                "HR. Muslim",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_keluar_masjid", CAT_DOA,
                "16. Doa Keluar Masjid",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0625\u0650\u0646\u0650\u0651\u064A \u0623\u064E\u0633\u0652\u0623\u064E\u0644\u064F\u0643\u064E \u0645\u0650\u0646\u0652 \u0641\u064E\u0636\u0652\u0644\u0650\u0643\u064E",
                "Allahumma inni as'aluka min fadhlik.",
                "Ya Allah, sesungguhnya aku memohon kepada-Mu sebagian dari karunia-Mu.",
                "HR. Muslim",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_sebelum_belajar", CAT_DOA,
                "17. Doa Sebelum Belajar & Mohon Ilmu",
                "\u0631\u064E\u0628\u0650\u0651 \u0632\u0650\u062F\u0652\u0646\u0650\u064A \u0639\u0650\u0644\u0652\u0645\u064B\u0627 \u0648\u064E\u0627\u0631\u0652\u0632\u064F\u0642\u0652\u0646\u0650\u064A \u0641\u064E\u0647\u0652\u0645\u064B\u0627",
                "Rabbi zidni 'ilman warzuqni fahma.",
                "Ya Tuhanku, tambahkanlah kepadaku ilmu pengetahuan dan berilah aku karunia pemahaman yang baik.",
                "QS. Taha: 114 & Doa Belajar",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/2462.mp3"
        ));

        allItems.add(new DuaItem(
                "doa_kelapangan_hati", CAT_DOA,
                "18. Doa Mohon Kelapangan Hati & Kelancaran Urusan",
                "\u0631\u064E\u0628\u0650\u0651 \u0627\u0634\u0652\u0631\u064E\u062D\u0652 \u0644\u0650\u064A \u0635\u064E\u062F\u0652\u0631\u0650\u064A \u0648\u064E\u064A\u064E\u0633\u0650\u0651\u0631\u0652 \u0644\u0650\u064A \u0623\u064E\u0645\u0652\u0631\u0650\u064A",
                "Rabbisyrah li shadri wa yassir li amri.",
                "Ya Tuhanku, lapangkanlah dadaku dan mudahkanlah untukku segala urusanku.",
                "QS. Taha: 25-26",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_hujan", CAT_DOA,
                "19. Doa Ketika Turun Hujan",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0635\u064E\u064A\u0650\u0651\u0628\u064B\u0627 \u0646\u064E\u0627\u0641\u0650\u0639\u064B\u0627",
                "Allahumma shayyiban nafi'a.",
                "Ya Allah, turunkanlah hujan yang lebat lagi bermanfaat bagi kami.",
                "HR. Bukhari",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_menjenguk_sakit", CAT_DOA,
                "20. Doa Kesembuhan / Menjenguk Orang Sakit",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0631\u064E\u0628\u064E\u0651 \u0627\u0644\u0646\u064E\u0651\u0627\u0633\u0650 \u0623\u064E\u0630\u0652\u0647\u0650\u0628\u0650 \u0627\u0644\u0652\u0628\u064E\u0623\u0652\u0633\u064E \u0627\u0634\u0652\u0641\u0650 \u0623\u064E\u0646\u0652\u062A\u064E \u0627\u0644\u0634\u064E\u0651\u0627\u0641\u0650\u064A",
                "Allahumma rabban-nasi adzhibil-ba'sa isyfi antasy-syafi.",
                "Ya Allah Tuhan seluruh manusia, hilangkanlah penyakit ini dan sembuhkanlah, Engkaulah Yang Maha Menyembuhkan.",
                "HR. Bukhari & Muslim",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_tolak_bala", CAT_DOA,
                "21. Doa Perlindungan Dari Penyakit & Musibah",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0625\u0650\u0646\u0650\u0651\u064A \u0623\u064E\u0639\u064F\u0648\u0630\u064F \u0628\u0650\u0643\u064E \u0645\u0650\u0646\u064E \u0627\u0644\u0652\u0628\u064E\u0631\u064E\u0635\u0650 \u0648\u064E\u0627\u0644\u0652\u062C\u064F\u0646\u064F\u0648\u0646\u0650 \u0648\u064E\u0627\u0644\u0652\u062C\u064F\u0630\u064E\u0627\u0645\u0650 \u0648\u064E\u0645\u0650\u0646\u0652 \u0633\u064E\u064A\u0650\u0651\u0626\u0650 \u0627\u0644\u0652\u0623\u064E\u0633\u0652\u0642\u064E\u0627\u0645\u0650",
                "Allahumma inni a'udzu bika minal-barashi wal-jununi wal-judzami wa min sayyi'il-asqam.",
                "Ya Allah, aku berlindung kepada-Mu dari penyakit kulit, gila, kusta, dan segala penyakit buruk lainnya.",
                "HR. Abu Dawud & Ahmad",
                ""
        ));

        allItems.add(new DuaItem(
                "doa_penutup_majelis", CAT_DOA,
                "22. Doa Penutup Majelis (Kafaratul Majelis)",
                "\u0633\u064F\u0628\u0652\u062D\u064E\u0627\u0646\u064E\u0643\u064E \u0627\u0644\u0644\u064E\u0651\u0647\u064F\u0645\u064E\u0651 \u0648\u064E\u0628\u0650\u062D\u064E\u0645\u0652\u062F\u0650\u0643\u064E \u0623\u064E\u0634\u0652\u0647\u064E\u062F\u064F \u0623\u064E\u0646\u0652 \u0644\u064E\u0627 \u0625\u0650\u0644\u064E\u0647\u064E \u0625\u0650\u0644\u064E\u0651\u0627 \u0623\u064E\u0646\u0652\u062A\u064E \u0623\u064E\u0633\u0652\u062A\u064E\u063A\u0652\u0641\u0650\u0631\u064F\u0643\u064E \u0648\u064E\u0623\u064E\u062A\u064F\u0648\u0628\u064F \u0625\u0650\u0644\u064E\u064A\u0652\u0643\u064E",
                "Subhanakallahumma wa bihamdika asyhadu alla ilaha illa anta astaghfiruka wa atubu ilaik.",
                "Mahasuci Engkau ya Allah dan segala puji bagi-Mu, aku bersaksi tiada Tuhan selain Engkau, aku memohon ampun dan bertaubat kepada-Mu.",
                "HR. Tirmidzi & Abu Dawud",
                ""
        ));

        allItems.add(new DuaItem(
                "quran_fatihah_1_2", CAT_QURAN,
                "1. QS. Al-Fatihah: 1-2 (Pembuka Kebaikan)",
                "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0652\u0645\u064E\u0670\u0646\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0650\u064A\u0645\u0650 \u0661 \u0627\u0644\u0652\u062D\u064E\u0645\u0652\u062F\u064F \u0644\u0650\u0644\u064E\u0651\u0647\u0650 \u0631\u064E\u0628\u0650\u0651 \u0627\u0644\u0652\u0639\u064E\u0627\u0644\u064E\u0645\u0650\u064A\u0646\u064E",
                "Bismillahir-rahmanir-rahim. Alhamdu lillahi rabbil-'alamin.",
                "Dengan nama Allah Yang Maha Pengasih lagi Maha Penyayang. Segala puji bagi Allah, Tuhan seluruh alam.",
                "QS. Al-Fatihah: 1-2 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/1.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_ayat_kursi", CAT_QURAN,
                "2. Ayat Kursi (QS. Al-Baqarah: 255)",
                "\u0627\u0644\u0644\u064E\u0651\u0647\u064F \u0644\u064E\u0627 \u0625\u0650\u0644\u064E\u0670\u0647\u064E \u0625\u0650\u0644\u064E\u0651\u0627 \u0647\u064F\u0648\u064E \u0627\u0644\u0652\u062D\u064E\u064A\u064F\u0651 \u0627\u0644\u0652\u0642\u064E\u064A\u064F\u0651\u0648\u0645\u064F \u06DA \u0644\u064E\u0627 \u062A\u064E\u0623\u0652\u062E\u064F\u0630\u064F\u0647\u064F \u0633\u0650\u0646\u064E\u0629\u064C \u0648\u064E\u0644\u064E\u0627 \u0646\u064E\u0648\u0652\u0645\u064C",
                "Allahu la ilaha illa huwal-hayyul-qayyum, la ta'khudzuhu sinatuw wa la naum...",
                "Allah, tidak ada Tuhan selain Dia Yang Maha Hidup lagi terus-menerus mengurus makhluk-Nya. Tidak mengantuk dan tidak tidur...",
                "QS. Al-Baqarah: 255 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/262.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_doa_sapu_jagat", CAT_QURAN,
                "3. Doa Sapu Jagat (QS. Al-Baqarah: 201)",
                "\u0631\u064E\u0628\u064E\u0651\u0646\u064E\u0627 \u0622\u062A\u0650\u0646\u064E\u0627 \u0641\u0650\u064A \u0627\u0644\u062F\u064F\u0651\u0646\u0652\u064A\u064E\u0627 \u062D\u064E\u0633\u064E\u0646\u064E\u0629\u064B \u0648\u064E\u0641\u0650\u064A \u0627\u0644\u0652\u0622\u062E\u0650\u0631\u064E\u0629\u0650 \u062D\u064E\u0633\u064E\u0646\u064E\u0629\u064B \u0648\u064E\u0642\u0650\u0646\u064E\u0627 \u0639\u064E\u0630\u064E\u0627\u0628\u064E \u0627\u0644\u0646\u064E\u0651\u0627\u0631\u0650",
                "Rabbana atina fid-dunya hasanatan wa fil-akhirati hasanatan wa qina 'adzaban-nar.",
                "Ya Tuhan kami, berilah kami kebaikan di dunia dan kebaikan di akhirat, dan peliharalah kami dari azab neraka.",
                "QS. Al-Baqarah: 201 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/208.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_baqarah_286", CAT_QURAN,
                "4. Doa Keringanan Beban (QS. Al-Baqarah: 286)",
                "\u0644\u064E\u0627 \u064A\u064F\u0643\u064E\u0644\u0650\u0651\u0641\u064F \u0627\u0644\u0644\u064E\u0651\u0647\u064F \u0646\u064E\u0641\u0652\u0633\u064B\u0627 \u0625\u0650\u0644\u064E\u0651\u0627 \u0648\u064F\u0633\u0652\u0639\u064E\u0647\u064E\u0627",
                "La yukallifullahu nafsan illa wus'aha...",
                "Allah tidak membebani seseorang melainkan sesuai dengan kesanggupannya...",
                "QS. Al-Baqarah: 286 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/293.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_ali_imran_8", CAT_QURAN,
                "5. Doa Keteguhan Hati (QS. Ali Imran: 8)",
                "\u0631\u064E\u0628\u064E\u0651\u0646\u064E\u0627 \u0644\u064E\u0627 \u062A\u064F\u0632\u0650\u063A\u0652 \u0642\u064F\u0644\u064F\u0648\u0628\u064E\u0646\u064E\u0627 \u0628\u064E\u0639\u0652\u062F\u064E \u0625\u0650\u0630\u0652 \u0647\u064E\u062F\u064E\u064A\u0652\u062A\u064E\u0646\u064E\u0627 \u0648\u064E\u0647\u064E\u0628\u0652 \u0644\u064E\u0646\u064E\u0627 \u0645\u0650\u0646\u0652 \u0644\u064E\u062F\u064F\u0646\u0652\u0643\u064E \u0631\u064E\u062D\u0652\u0645\u064E\u0629\u064B",
                "Rabbana la tuzigh qulubana ba'da idz hadaitana wa hab lana min ladunka rahmah.",
                "Ya Tuhan kami, janganlah Engkau condongkan hati kami kepada kesesatan setelah Engkau beri petunjuk, dan karuniakanlah rahmat dari sisi-Mu.",
                "QS. Ali Imran: 8 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/301.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_ibrahim_40", CAT_QURAN,
                "6. Doa Menjaga Sholat (QS. Ibrahim: 40)",
                "\u0631\u064E\u0628\u0650\u0651 \u0627\u062C\u0652\u0639\u064E\u0644\u0652\u0646\u0650\u064A \u0645\u064F\u0642\u0650\u064A\u0645\u064E \u0627\u0644\u0635\u064E\u0651\u0644\u064E\u0627\u0629\u0650 \u0648\u064E\u0645\u0650\u0646\u0652 \u0630\u064F\u0631\u0650\u0651\u064A\u064E\u0651\u062A\u0650\u064A \u06DA \u0631\u064E\u0628\u064E\u0651\u0646\u064E\u0627 \u0648\u064E\u062A\u064E\u0642\u064E\u0628\u064E\u0651\u0644\u0652 \u062F\u064F\u0639\u064E\u0627\u0621\u0650",
                "Rabbij'alni muqimash-shalati wa min dzurriyyati, rabbana wa taqabbal du'a.",
                "Ya Tuhanku, jadikanlah aku dan anak cucuku orang yang tetap melaksanakan sholat, ya Tuhan kami perkenankanlah doaku.",
                "QS. Ibrahim: 40 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/1790.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_al_insyirah_5_6", CAT_QURAN,
                "7. Kemudahan Setelah Kesulitan (QS. Al-Insyirah: 5-6)",
                "\u0641\u064E\u0625\u0650\u0646\u064E\u0651 \u0645\u064E\u0639\u064E \u0627\u0644\u0652\u0639\u064F\u0633\u0652\u0631\u0650 \u064A\u064F\u0633\u0652\u0631\u064B\u0627 \u0665 \u0625\u0650\u0646\u064E\u0651 \u0645\u064E\u0639\u064E \u0627\u0644\u0652\u0639\u064F\u0633\u0652\u0631\u0650 \u064A\u064F\u0633\u0652\u0631\u064B\u0627",
                "Fa inna ma'al-'usri yusra. Inna ma'al-'usri yusra.",
                "Maka sesungguhnya bersama kesulitan ada kemudahan. Sesungguhnya bersama kesulitan itu ada kemudahan.",
                "QS. Al-Insyirah: 5-6 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/6095.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_al_ikhlas", CAT_QURAN,
                "8. QS. Al-Ikhlas: 1 (Tauhid Kemurnian Allah)",
                "\u0642\u064F\u0644\u0652 \u0647\u064F\u0648\u064E \u0627\u0644\u0644\u064E\u0651\u0647\u064F \u0623\u064E\u062D\u064E\u062F\u064C",
                "Qul huwallahu ahad.",
                "Katakanlah (Muhammad): Dialah Allah, Yang Maha Esa.",
                "QS. Al-Ikhlas: 1 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/6222.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_al_falaq", CAT_QURAN,
                "9. QS. Al-Falaq: 1 (Perlindungan dari Keburukan)",
                "\u0642\u064F\u0644\u0652 \u0623\u064E\u0639\u064F\u0648\u0630\u064F \u0628\u0650\u0631\u064E\u0628\u0650\u0651 \u0627\u0644\u0652\u0641\u064E\u0644\u064E\u0642\u0650",
                "Qul a'udzu birabbil-falaq.",
                "Katakanlah: Aku berlindung kepada Tuhan yang menguasai subuh (fajar).",
                "QS. Al-Falaq: 1 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/6226.mp3"
        ));

        allItems.add(new DuaItem(
                "quran_an_nas", CAT_QURAN,
                "10. QS. An-Nas: 1 (Perlindungan dari Bisikan Jahat)",
                "\u0642\u064F\u0644\u0652 \u0623\u064E\u0639\u064F\u0648\u0630\u064F \u0628\u0650\u0631\u064E\u0628\u0650\u0651 \u0627\u0644\u0646\u064E\u0651\u0627\u0633\u0650",
                "Qul a'udzu birabbin-nas.",
                "Katakanlah: Aku berlindung kepada Tuhannya manusia.",
                "QS. An-Nas: 1 (Qari Mishary Alafasy)",
                "https://cdn.islamic.network/quran/audio/128/ar.alafasy/6231.mp3"
        ));

    }
}
