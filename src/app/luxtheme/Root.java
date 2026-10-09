package app.luxtheme;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Runs the theme command through su. Blocking: call off the main thread. */
final class Root {
    private static final long TIMEOUT_S = 60;

    /** @return null on success, otherwise a short error message */
    static String setNight(boolean dark) {
        return run("cmd uimode night " + (dark ? "yes" : "no"));
    }

    private static String run(String command) {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return "su not found – is the device rooted?";
        }
        try {
            process.getOutputStream().close();
            // The command prints one short line, so waiting before reading cannot block it.
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                return "su timed out";
            }
            int code = process.exitValue();
            if (code == 0) {
                return null;
            }
            String output = read(process.getInputStream()).trim();
            return output.isEmpty() ? "su exit code " + code : output;
        } catch (IOException e) {
            return String.valueOf(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            process.destroy();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while (out.size() < 4096 && (n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private Root() {}
}
