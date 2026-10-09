package app.luxtheme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that watches the light sensor and switches the system
 * theme once the light level has stayed on one side of the threshold for the
 * debounce period. Everything except the su call runs on the main thread.
 *
 * A running countdown is saved, so it carries on if Android kills and
 * restarts the process instead of starting again from the full time.
 */
public class LuxService extends Service
        implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String CHANNEL = "monitor";
    private static final int NOTIFICATION_ID = 1;
    /** After a failed switch, wait at least this long before trying again. */
    private static final long RETRY_MIN_MS = 60_000;
    /** Readings on the old side shorter than this do not cancel a countdown. */
    private static final long GRACE_MS = 3_000;

    // Keys in the "state" preferences file (separate from the settings, whose
    // change listener would otherwise fire on every save).
    private static final String S_ALIVE = "alive";
    private static final String S_BOOT = "boot";
    private static final String S_STABLE = "stable";
    private static final String S_CANDIDATE = "candidate";
    private static final String S_SINCE = "since";
    private static final String S_INTERRUPTED = "interrupted";

    /** The running service, for the settings screen. Main thread only. */
    static LuxService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Debouncer debouncer = new Debouncer();
    private final Runnable timer = this::onTimer;

    private SharedPreferences prefs;
    private SharedPreferences state;
    private SensorManager sensors;
    private boolean hasSensor;
    private boolean destroyed;
    private boolean lastFailed;
    private float lastLux = Float.NaN;
    private int blips;
    private int bootCount;
    private String savedSignature = "";
    private String lastEvent = "";
    private String notificationText = "Watching the light level";

    static void start(Context context) {
        context.startForegroundService(new Intent(context, LuxService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, LuxService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        EventLog.init(this);
        prefs = Prefs.get(this);
        state = getSharedPreferences("state", MODE_PRIVATE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, buildNotification(notificationText));

        restoreOrSeed();

        sensors = getSystemService(SensorManager.class);
        Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        hasSensor = light != null
                && sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        if (!hasSensor) {
            setEvent("No light sensor on this device");
        }
        prefs.registerOnSharedPreferenceChangeListener(this);
        // A restored countdown normally carries on at the first reading. This is the
        // fallback if the sensor stays silent; it waits a moment so the reading can come first.
        if (debouncer.pending() != null) {
            handler.postDelayed(timer, Math.max(2_000,
                    debouncer.nextCheckIn(SystemClock.elapsedRealtime(), debounceMs(), graceMs())));
        }
    }

    /**
     * Continues a countdown left behind by a process that was killed, or else
     * starts from the theme that is active now, so nothing happens while it
     * already matches the light level.
     */
    private void restoreOrSeed() {
        long now = SystemClock.elapsedRealtime();
        bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        boolean killed = state.getBoolean(S_ALIVE, false);
        boolean sameBoot = state.getInt(S_BOOT, -1) == bootCount;
        Boolean candidate = decode(state.getInt(S_CANDIDATE, -1));
        long since = state.getLong(S_SINCE, -1);

        String origin = !killed ? "" : sameBoot ? ", the previous run was killed" : ", after a reboot";
        String detail;
        // The saved times are only comparable within one boot of the device.
        if (killed && sameBoot && candidate != null && since >= 0 && since <= now) {
            long interrupted = state.getLong(S_INTERRUPTED, -1);
            debouncer.restore(decode(state.getInt(S_STABLE, -1)), candidate, since,
                    interrupted <= now ? interrupted : -1);
            detail = "continuing the countdown to " + name(candidate)
                    + " (" + (now - since) / 1000 + " s so far)";
        } else {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean themeDark = night == Configuration.UI_MODE_NIGHT_YES;
            debouncer.setStable(themeDark);
            detail = "theme is " + name(themeDark);
        }
        EventLog.add("Monitor started (pid " + Process.myPid() + origin + "): " + detail);
        state.edit().putBoolean(S_ALIVE, true).putInt(S_BOOT, bootCount).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered, also when already running.
        startForeground(NOTIFICATION_ID, buildNotification(notificationText));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        sensors.unregisterListener(this);
        handler.removeCallbacks(timer);
        io.shutdown();
        // A deliberate stop: the next start must not pick up this countdown.
        state.edit().clear().apply();
        EventLog.add("Monitor stopped");
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean first = Float.isNaN(lastLux);
        lastLux = event.values[0];
        if (first) {
            boolean dark = lastLux < Prefs.threshold(prefs);
            EventLog.add("First reading " + lux() + ", " + name(dark) + " side of "
                    + MainActivity.trim(Prefs.threshold(prefs)) + " lx");
        }
        evaluate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        // A new threshold can change which side the last reading is on.
        if (!Float.isNaN(lastLux)) {
            evaluate();
        }
    }

    private long debounceMs() {
        long configured = Prefs.debounceMs(prefs);
        return lastFailed ? Math.max(configured, RETRY_MIN_MS) : configured;
    }

    /** Never more than a quarter of the debounce time, so a zero debounce stays immediate. */
    private long graceMs() {
        return Math.min(GRACE_MS, debounceMs() / 4);
    }

    private void evaluate() {
        boolean dark = lastLux < Prefs.threshold(prefs);
        long now = SystemClock.elapsedRealtime();
        Boolean before = debouncer.pending();
        boolean wasInterrupted = debouncer.interrupted();
        act(debouncer.update(dark, now, debounceMs(), graceMs()), before, wasInterrupted, now);
    }

    private void onTimer() {
        long now = SystemClock.elapsedRealtime();
        Boolean before = debouncer.pending();
        boolean wasInterrupted = debouncer.interrupted();
        act(debouncer.check(now, debounceMs(), graceMs()), before, wasInterrupted, now);
    }

    /** Records what changed, then applies a decision or re-arms the timer. */
    private void act(Boolean decision, Boolean before, boolean wasInterrupted, long now) {
        handler.removeCallbacks(timer);
        if (destroyed) {
            return;
        }
        logChange(decision, before, wasInterrupted);
        saveState();
        if (decision != null) {
            apply(decision);
            return;
        }
        // The sensor reports only changes, so a steady level needs the timer to finish the wait.
        long next = debouncer.nextCheckIn(now, debounceMs(), graceMs());
        if (next >= 0) {
            handler.postDelayed(timer, next);
        }
    }

    private void logChange(Boolean decision, Boolean before, boolean wasInterrupted) {
        Boolean after = debouncer.pending();
        if (decision != null) {
            EventLog.add("Debounce time reached at " + lux() + ": switching to " + name(decision)
                    + ignored(blips));
            blips = 0;
        } else if (after != null && !after.equals(before)) {
            blips = 0;
            EventLog.add(lux() + ": countdown to " + name(after) + " started ("
                    + debounceMs() / 1000 + " s)");
        } else if (after == null && before != null) {
            // The interruption that ended the countdown is not one that was ignored.
            EventLog.add(lux() + ": countdown to " + name(before) + " cancelled, the light level stayed on the "
                    + name(!before) + " side" + ignored(blips - 1));
            blips = 0;
        } else if (after != null && !wasInterrupted && debouncer.interrupted()) {
            blips++;
        }
    }

    private static String ignored(int count) {
        return count <= 0 ? "" : ", " + count + " brief interruption(s) ignored";
    }

    private void saveState() {
        String signature = debouncer.stable() + "/" + debouncer.pending() + "/"
                + debouncer.pendingSince() + "/" + debouncer.interruptedSince();
        if (signature.equals(savedSignature)) {
            return;
        }
        savedSignature = signature;
        state.edit()
                .putBoolean(S_ALIVE, true)
                .putInt(S_BOOT, bootCount)
                .putInt(S_STABLE, encode(debouncer.stable()))
                .putInt(S_CANDIDATE, encode(debouncer.pending()))
                .putLong(S_SINCE, debouncer.pendingSince())
                .putLong(S_INTERRUPTED, debouncer.interruptedSince())
                .apply();
    }

    private void apply(boolean dark) {
        io.execute(() -> {
            String error = Root.setNight(dark);
            handler.post(() -> onApplied(dark, error));
        });
    }

    private void onApplied(boolean dark, String error) {
        if (destroyed) {
            return;
        }
        lastFailed = error != null;
        if (error == null) {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
            setEvent("Switched to " + name(dark) + " at " + time);
            EventLog.add("Switched to " + name(dark));
        } else {
            setEvent("Could not switch: " + error);
            EventLog.add("Could not switch to " + name(dark) + ": " + error);
            // Forget the side so the same switch is attempted again after the next wait.
            debouncer.reset();
            if (!Float.isNaN(lastLux)) {
                evaluate();
            }
        }
    }

    private void setEvent(String text) {
        lastEvent = text;
        notificationText = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    /** One-line status for the settings screen. */
    String status() {
        if (!hasSensor) {
            return lastEvent;
        }
        if (Float.isNaN(lastLux)) {
            return "Waiting for the light sensor…";
        }
        long remaining = debouncer.remaining(SystemClock.elapsedRealtime(), debounceMs());
        if (remaining >= 0) {
            long seconds = (remaining + 999) / 1000;
            String countdown = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
            String prefix = lastFailed ? lastEvent + "\n" : "";
            String suffix = debouncer.interrupted() ? " (cancelling unless the light level returns)" : "";
            return prefix + "Switching to " + name(debouncer.pending()) + " in " + countdown + suffix;
        }
        return lastEvent.isEmpty() ? "Watching the light level" : lastEvent;
    }

    private String lux() {
        return Float.isNaN(lastLux) ? "no reading yet" : MainActivity.trim(lastLux) + " lx";
    }

    private static String name(boolean dark) {
        return dark ? "dark" : "light";
    }

    private static int encode(Boolean side) {
        return side == null ? -1 : side ? 1 : 0;
    }

    private static Boolean decode(int value) {
        return value < 0 ? null : value == 1;
    }
}
