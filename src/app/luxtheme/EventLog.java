package app.luxtheme;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Short history of what the monitor did, shown in the app and kept in a file
 * so it survives the process being killed. Also mirrored to logcat.
 * Call from the main thread only.
 */
final class EventLog {
    private static final String TAG = "LuxTheme";
    private static final int MAX_LINES = 150;
    private static final long FLUSH_DELAY_MS = 1500;

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static File file;
    private static boolean flushScheduled;
    private static int version;

    static void init(Context context) {
        if (file != null) {
            return;
        }
        file = new File(context.getApplicationContext().getFilesDir(), "events.log");
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                append(line);
            }
        } catch (IOException e) {
            // No log yet.
        }
        // A crash would otherwise look the same as being killed: record it before the process dies.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String[] frames = trace.toString().split("\n");
            StringBuilder head = new StringBuilder("CRASH ");
            for (int i = 0; i < Math.min(frames.length, 4); i++) {
                head.append(frames[i].trim()).append(' ');
            }
            synchronized (lines) {
                // Own formatter: this can run on any thread and SimpleDateFormat is not thread-safe.
                append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + "  " + head.toString().trim());
                write(snapshot());
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    static void add(String text) {
        Log.i(TAG, text);
        synchronized (lines) {
            append(stamp.format(new Date()) + "  " + text);
        }
        version++;
        if (file != null && !flushScheduled) {
            flushScheduled = true;
            handler.postDelayed(EventLog::flush, FLUSH_DELAY_MS);
        }
    }

    /** Changes whenever a line is added, so the screen only redraws when needed. */
    static int version() {
        return version;
    }

    /** Newest line first. */
    static String text() {
        StringBuilder out = new StringBuilder();
        synchronized (lines) {
            for (String line : lines) {
                out.insert(0, line + "\n");
            }
        }
        return out.toString();
    }

    private static void append(String line) {
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    private static String snapshot() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void flush() {
        flushScheduled = false;
        if (file == null) {
            return;
        }
        final String content;
        synchronized (lines) {
            content = snapshot();
        }
        io.execute(() -> write(content));
    }

    /** Writes the whole log through a temporary file so a kill mid-write cannot truncate it. */
    private static void write(String content) {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
        }
    }

    private EventLog() {}
}
