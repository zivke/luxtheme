package app.luxtheme;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String ENABLED = "enabled";
    static final String THRESHOLD = "threshold_lux";
    static final String DEBOUNCE = "debounce_s";
    /** Version 1.0.0 stored the debounce time in minutes under this key. */
    private static final String DEBOUNCE_MINUTES = "debounce_min";

    static final float DEFAULT_THRESHOLD = 10f;
    static final int DEFAULT_DEBOUNCE = 300;

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static float threshold(SharedPreferences prefs) {
        return prefs.getFloat(THRESHOLD, DEFAULT_THRESHOLD);
    }

    static int debounceSeconds(SharedPreferences prefs) {
        if (!prefs.contains(DEBOUNCE) && prefs.contains(DEBOUNCE_MINUTES)) {
            return prefs.getInt(DEBOUNCE_MINUTES, 5) * 60;
        }
        return prefs.getInt(DEBOUNCE, DEFAULT_DEBOUNCE);
    }

    static long debounceMs(SharedPreferences prefs) {
        return debounceSeconds(prefs) * 1000L;
    }

    private Prefs() {}
}
