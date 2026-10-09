package app.luxtheme;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Settings screen: live light level, threshold, debounce time and a root test. */
public class MainActivity extends Activity implements SensorEventListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;

    /** Static so the result survives the activity restart that a theme change causes. */
    private static String testStatus = "";

    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor light;
    private TextView luxView;
    private TextView statusView;
    private TextView testResult;
    private TextView logView;
    private int shownLogVersion = -1;
    private EditText thresholdField;
    private EditText debounceField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        EventLog.init(this);
        prefs = Prefs.get(this);
        sensors = getSystemService(SensorManager.class);
        light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);

        luxView = findViewById(R.id.lux);
        statusView = findViewById(R.id.status);
        testResult = findViewById(R.id.test_result);
        logView = findViewById(R.id.log);
        thresholdField = findViewById(R.id.threshold);
        debounceField = findViewById(R.id.debounce);

        if (savedInstanceState == null) {
            thresholdField.setText(trim(Prefs.threshold(prefs)));
            debounceField.setText(String.valueOf(Prefs.debounceSeconds(prefs)));
        }

        boolean enabled = prefs.getBoolean(Prefs.ENABLED, false);
        Switch enabledSwitch = findViewById(R.id.enabled);
        enabledSwitch.setChecked(enabled);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(Prefs.ENABLED, checked).apply();
            if (checked) {
                LuxService.start(this);
            } else {
                LuxService.stop(this);
            }
        });
        if (enabled) {
            // Covers a service that is not running, e.g. right after an install.
            LuxService.start(this);
        }

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.test_dark).setOnClickListener(v -> test(true));
        findViewById(R.id.test_light).setOnClickListener(v -> test(false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (light != null) {
            sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_UI);
        }
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        luxView.setText(trim(event.values[0]) + " lx");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void tick() {
        LuxService service = LuxService.instance;
        if (light == null) {
            statusView.setText("No light sensor on this device");
        } else if (service == null) {
            statusView.setText("Off");
        } else {
            statusView.setText(service.status());
        }
        testResult.setText(testStatus);
        if (shownLogVersion != EventLog.version()) {
            shownLogVersion = EventLog.version();
            logView.setText(EventLog.text());
        }
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    private void save() {
        float threshold;
        int debounce;
        try {
            threshold = Float.parseFloat(thresholdField.getText().toString().trim());
            debounce = Integer.parseInt(debounceField.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter a number in both fields", Toast.LENGTH_SHORT).show();
            return;
        }
        if (threshold < 0 || Float.isNaN(threshold) || debounce < 0 || debounce > 24 * 3600) {
            Toast.makeText(this, "Value out of range", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putFloat(Prefs.THRESHOLD, threshold)
                .putInt(Prefs.DEBOUNCE, debounce)
                .apply();
        EventLog.add("Settings saved: dark below " + trim(threshold) + " lx, debounce " + debounce + " s");
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void test(boolean dark) {
        testStatus = "Running…";
        testResult.setText(testStatus);
        new Thread(() -> {
            String error = Root.setNight(dark);
            handler.post(() -> {
                testStatus = error == null ? "OK" : "Failed: " + error;
                EventLog.add("Test, " + (dark ? "dark" : "light") + " now: " + testStatus);
            });
        }).start();
    }

    /** 12.0 -> "12", 3.5 -> "3.5", 0.04 -> "0.04" (two decimals below 1 lx, where they matter) */
    static String trim(float value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e7) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.US, Math.abs(value) < 1 ? "%.2f" : "%.1f", value);
        // Rounding can leave zeros: "3.0" -> "3", "0.50" -> "0.5".
        return text.contains(".") ? text.replaceAll("\\.?0+$", "") : text;
    }
}
