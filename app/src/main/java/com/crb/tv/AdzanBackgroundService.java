package com.crb.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Layanan Latar Belakang (Foreground Service) Pemantau Waktu Sholat Kota Cirebon:
 * Memastikan suara Adzan tetap berbunyi otomatis saat waktu sholat tiba
 * meskipun pengguna sedang membuka aplikasi lain (YouTube, Netflix, TV, dll).
 */
public final class AdzanBackgroundService extends Service {

    public static final String ACTION_START = "com.crb.tv.action.START_ADZAN_WATCH";
    public static final String ACTION_STOP_SOUND = "com.crb.tv.action.STOP_ADZAN_SOUND";

    private static final String CHANNEL_ID = "crb_adzan_service_channel";
    private static final int NOTIF_ID = 7701;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AdzanAudioPlayer audioPlayer;
    private PowerManager.WakeLock wakeLock;

    private final Runnable checkTickRunnable = new Runnable() {
        @Override
        public void run() {
            checkPrayerTimeAndTrigger();
            handler.postDelayed(this, 15000L);
        }
    };

    public static void ensureStarted(Context context) {
        if (context == null) return;
        Config cfg = Config.load(context);
        Intent intent = new Intent(context, AdzanBackgroundService.class);
        intent.setAction(ACTION_START);
        if (!cfg.enableAdzanSound) {
            try {
                context.stopService(intent);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioPlayer = new AdzanAudioPlayer();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildStatusNotification("Pemantau Jadwal Sholat Kota Cirebon Aktif"));
        handler.removeCallbacks(checkTickRunnable);
        handler.postDelayed(checkTickRunnable, 5000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SOUND.equals(intent.getAction())) {
            if (audioPlayer != null) {
                audioPlayer.stop();
            }
            releaseWakeLock();
            updateNotification("Pemantau Jadwal Sholat Kota Cirebon Aktif");
            return START_STICKY;
        }

        Config cfg = Config.load(this);
        if (!cfg.enableAdzanSound) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void checkPrayerTimeAndTrigger() {
        Config cfg = Config.load(this);
        if (!cfg.enableAdzanSound) {
            stopSelf();
            return;
        }

        TimeZone wib = TimeZone.getTimeZone("GMT+7");
        Calendar now = Calendar.getInstance(wib);
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        int day = now.get(Calendar.DAY_OF_MONTH);

        PrayerTimesCalculator.DailyPrayerSchedule pt = PrayerTimesCalculator.calculateKemenag(
                cfg.prayerCityName,
                year, month, day,
                cfg.prayerLatitude,
                cfg.prayerLongitude,
                cfg.prayerTimezoneOffset,
                cfg.prayerIhtiyatMinutes
        );

        String hhmm = String.format(Locale.US, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        String ymd = String.format(Locale.US, "%04d%02d%02d", year, month, day);

        String matchedPrayer = null;
        if (hhmm.equals(pt.getSubuh())) matchedPrayer = "Subuh";
        else if (hhmm.equals(pt.getDzuhur())) matchedPrayer = "Dzuhur";
        else if (hhmm.equals(pt.getAshar())) matchedPrayer = "Ashar";
        else if (hhmm.equals(pt.getMaghrib())) matchedPrayer = "Maghrib";
        else if (hhmm.equals(pt.getIsya())) matchedPrayer = "Isya";

        if (matchedPrayer == null) return;

        String triggerKey = ymd + "_" + matchedPrayer + "_" + hhmm;
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        String lastKey = prefs.getString("pref_last_adzan_trigger_key", "");
        if (triggerKey.equals(lastKey)) {
            return;
        }
        prefs.edit().putString("pref_last_adzan_trigger_key", triggerKey).apply();

        playAdzanInBackground(cfg, matchedPrayer, hhmm);
    }

    private void playAdzanInBackground(Config cfg, final String prayerName, String hhmm) {
        acquireWakeLock();
        updateNotification("ADZAN " + prayerName.toUpperCase(Locale.US) + " (" + hhmm + " WIB) - Kota Cirebon");

        audioPlayer.playAdzan(
                this,
                prayerName,
                cfg.customAdzanPath,
                cfg.adzanMuadzinIndex,
                new AdzanAudioPlayer.OnAdzanListener() {
                    @Override
                    public void onLafadzChanged(String arabicTranslit, String indonesianMeaning) {
                    }

                    @Override
                    public void onAdzanFinished() {
                        releaseWakeLock();
                        updateNotification("Pemantau Jadwal Sholat Kota Cirebon Aktif");
                    }
                }
        );
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrbLauncher:AdzanWakeLock");
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(4 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Pengingat Adzan Crb Launcher",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildStatusNotification(text));
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildStatusNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Intent stopIntent = new Intent(this, AdzanBackgroundService.class);
        stopIntent.setAction(ACTION_STOP_SOUND);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        nb.setContentTitle("Crb Launcher - Jadwal Sholat Cirebon")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentPi)
                .setOngoing(true);

        if (audioPlayer != null && audioPlayer.isPlaying()) {
            nb.addAction(0, "Matikan Suara Adzan", stopPi);
        }
        return nb.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        releaseWakeLock();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
