# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app **Lux Theme** (`app.luxtheme`). It switches the system theme
between light and dark from the ambient light sensor:

- dark when the reading stays below a threshold for the whole debounce time,
  light when it stays at or above it for the whole debounce time
- the countdown is cancelled once the reading has been back on the other side
  for the grace time (3 s, or a quarter of the debounce time if shorter);
  shorter blips are ignored
- a running countdown is saved and carries on if the process is killed
- threshold (lux, default 10) and debounce time (seconds, default 300) are
  set in the app
- a foreground service does the watching and restarts after boot
- the switch itself is `su -c "cmd uimode night yes|no"`, so the device must
  be rooted. Ordinary apps cannot change the system theme on Android 10+.

Written for LineageOS 18.1 (Android 11): `minSdkVersion` 29, `targetSdkVersion` 30.

## Build & run

JDK, Android SDK, adb and the signing key are already set up in the
devcontainer; `.env` (gitignored) names the key (`KEYSTORE`) and holds
`KEY_PASS`. Never install, regenerate or read the key. Everything goes
through the `Makefile` (`make help` lists targets).

- `make build` — runs `build.sh`, produces a signed `luxtheme.apk`.
- `make test` — compiles `Debouncer` + `DebouncerTest` and runs them on the
  local JVM. No device, no Android classes.
- `make connect DEVICE_IP=…` — `adb connect` over the network.
- `make run` — build, `adb install -r`, open the app. `make install` stops
  after installing.
- `make logcat`, `make stop`, `make uninstall`, `make clean`.

There is deliberately **no Gradle**. `build.sh` is the whole build:

1. `aapt2 compile` + `aapt2 link` — resources, binary manifest, `build/gen/…/R.java`
2. `javac --release 8 -classpath android.jar` — `--release 8`, not
   `-bootclasspath`, because `android.jar` lacks the lambda bootstrap classes
3. `d8 --min-api 29` — `classes.dex`
4. `tools/package.py` — adds the dex to the aapt2 output, stores
   `resources.arsc` uncompressed on a 4-byte boundary (required for
   `targetSdkVersion` 30) and rewrites the dex header's SHA-1 and Adler-32
   (an old D8 release hashed the wrong range; harmless with newer ones)
5. `tools/Sign.java` — signs with apksig (v1 + v2 + v3) and verifies

`build.sh` finds its tools from `ANDROID_HOME` (devcontainer) or from a loose
`TOOLS` directory (see `README.md`). Keep both paths working.

There is no emulator in the container and no instrumented tests. Anything
beyond `make test` has to be checked on the device, by the user. Say so
rather than claiming a change works.

## Architecture

All app code is in `src/app/luxtheme/`, plain `android.*` APIs, no libraries.

- **`Debouncer.java`** — the decision logic, no Android imports. Holds the
  side the theme was last set to (`stable`), a pending side (`candidate`),
  when it started, and since when the readings have been back on the old side
  (`interruptedSince`). `update(dark, now, debounce, grace)` feeds a reading,
  `check(now, debounce, grace)` is the timer path, both return the side to
  switch to or `null`. An interruption never switches and cancels only after
  `grace`. `nextCheckIn` is when the timer is next needed. `update` first
  drops an interruption that has already lasted `grace`, in case no timer ran
  (the device slept). `restore()` and the getters are for saving a countdown.
- **`LuxService.java`** — foreground service, `START_STICKY`. Registers the
  light sensor, feeds readings to the `Debouncer`, and arms a `Handler` timer
  for the remaining debounce time, because the sensor only reports changes
  and a steady level would otherwise never finish the wait. Time base is
  `SystemClock.elapsedRealtime()`. Seeds `stable` from the current system
  theme on start, so a manual theme change sticks until the light level next
  crosses the threshold. A failed switch puts `stable` back to the old side and
  retries after the next debounce period, at least `RETRY_MIN_MS`. `Handler`
  timers count uptime, which stops in sleep, so `ACTION_SCREEN_ON` re-arms the
  timer from `elapsedRealtime` (`checkAfterSettling`, also used after a
  restore), giving a fresh reading `SETTLE_MS` to arrive first. Everything runs on the
  main thread except the `su` call (single-thread executor). `instance` and
  `status()` are how `MainActivity` reads its state.
  The countdown is written to the `state` preferences file whenever it changes
  (`saveState`) and read back in `restoreOrSeed` when the previous run did not
  end through `onDestroy` and the boot count is unchanged, since the saved
  times are `elapsedRealtime`. `onDestroy` clears it, so a deliberate stop
  starts fresh. `state` is a separate file on purpose: the settings file has a
  change listener that re-evaluates.
- **`EventLog.java`** — the "Recent events" list: an in-memory ring of lines,
  written to `files/events.log` shortly after each change and mirrored to
  logcat (tag `LuxTheme`). It also records uncaught exceptions. Log state
  changes, never individual readings.
- **`Root.java`** — runs the command through `su`, blocking, with a timeout.
  Returns `null` on success or a short error string.
- **`MainActivity.java`** — settings screen: live lux reading (its own sensor
  listener while visible), enable switch, threshold and debounce fields, two
  buttons that run the root command directly, and the event list. A theme change recreates the
  activity, which is why the test result is kept in a static field.
- **`BootReceiver.java`** — starts the service after `BOOT_COMPLETED` and
  `MY_PACKAGE_REPLACED` when the enable switch is on.
- **`Prefs.java`** — `SharedPreferences` keys and defaults. `debounce_s` is in
  seconds; `debounce_min` is the 1.0.0 key, still read once for migration.

`res/` holds one layout (`layout/main.xml`), `values/strings.xml`,
`xml/backup.xml` (keeps the `state` file out of backups), the
adaptive launcher icon and the notification icon (vector drawables).

## Conventions

- No inner (non-static) or anonymous classes: the D8 in build-tools 34 crashes
  on them as compiled by the JDK 21 `javac` (`NullPointerException ...
  String.length()`). Use lambdas or `static` nested classes.
- Keep `Debouncer` free of Android imports so `make test` stays a plain JVM
  test. Add a case to `test/app/luxtheme/DebouncerTest.java` when the
  switching rules change.
- No new dependencies and no Gradle without asking. The point of the project
  is that it builds from a shell script.
- Four-space indent for Java, XML, shell and Python (`.editorconfig`).
- Layout text goes in `res/values/strings.xml`. The status and toast strings
  built in code are the existing exception.
- Raising `targetSdkVersion` is not a one-line change: 31+ restricts starting
  foreground services from the background, 33 needs the notification
  permission, 34 needs a foreground service type.
- For any user-visible change: bump `versionCode` and `versionName` in
  `AndroidManifest.xml`, add an entry to `changelog.md` (Keep a Changelog) and
  update `README.md` if behaviour or setup changed.
- Versions are semver: `versionName` is `MAJOR.MINOR.PATCH`,
  the changelog heading matches, and a release is an annotated tag `vX.Y.Z`.
- Never commit `*.p12` / `*.jks` / `*.keystore` or an APK (`.gitignore` covers
  them).
