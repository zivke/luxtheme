# Lux Theme

A small Android app that switches the system theme between light and dark
from the ambient light sensor, with a debounce time so brief changes in light
don't flip it.

Stock Android only applies a scheduled theme change after the screen has
turned off. This app applies it right away, which suits devices whose screen
is always on.

**Requires root** (for example [Magisk](https://github.com/topjohnwu/Magisk)).
Android does not let ordinary apps change the system theme, so the app runs
`cmd uimode night yes|no` through `su`.

Written for LineageOS 18.1 (Android 11). It should run on Android 10 and
later; it will not install on Android 9 or older.

## How it behaves

- **Dark:** when the reading stays below the threshold for the whole debounce time.
- **Light:** when the reading stays at or above the threshold for the whole debounce time.
- **Debounce:** the countdown is cancelled when the reading goes back to the other side for 3
  seconds (a quarter of the debounce time if that is shorter). Shorter blips are ignored.
- **Restarts:** if Android kills the monitor, a running countdown carries on when it restarts.
- **Manual changes:** a theme you set by hand stays until the light level next crosses the threshold.
- **Defaults:** 10 lux and 300 seconds. Both are editable in the app.

A foreground service with a permanent notification does the watching and is
restarted after a reboot.

The bottom of the screen lists recent events: countdowns started and
cancelled (with the light reading that caused it), switches, failures and
restarts of the monitor. See [Troubleshooting](#troubleshooting) for how to
read it.

## Setup

1. Install `luxtheme.apk` and open Lux Theme.
2. Tap **Dark now** or **Light now** and grant superuser access when asked.
   The theme should change immediately.
3. Use the live lux reading at the top to choose a threshold, enter it and the
   debounce time, then tap **Save**.
4. Turn on **Switch theme automatically**.

**About** in the menu (⋮ at the top right) shows the installed version, the
licence and a link back to this repository.

## Limits

- It replaces any dark-theme schedule set in Settings, because the command
  puts the theme in manual on/off mode.
- The sensor is not read while the device is asleep. When it wakes, the
  countdown is checked against the time that really passed, with the first new
  reading deciding: a countdown that is over switches at once if the light
  level still agrees, otherwise it starts over.
- If the root command fails, the error is shown in the app and in the
  notification, and the switch is retried after the next debounce period
  (at least 60 seconds).
- Some manufacturer battery savers stop background services. Exempt the app
  from battery optimisation if that happens (see Troubleshooting).

## Troubleshooting

If the theme does not switch when you expect it to, open the app and read the
**Recent events** list at the bottom of the screen, newest line first. The
same lines go to logcat under the tag `LuxTheme`.

| Line in the list | What it means | What to do |
|---|---|---|
| `Monitor started (..., the previous run was killed)` | Android stopped the monitor and restarted it. A running countdown carries on, but nothing is measured while it is stopped. | If it appears often, exempt the app from battery optimisation (below). |
| `countdown to dark cancelled, the light level stayed on the light side` (or the reverse) | The reading crossed back over the threshold for 3 seconds or more. The lux value at the start of the line is the reading that did it. | If the readings hover around the threshold, move the threshold further from the usual light level. |
| `... N brief interruption(s) ignored` | Short blips on the other side that did not cancel the countdown. | Nothing. This is the debounce working. |
| `Could not switch to dark: ...` (or light) | The root command failed. It is retried after the next debounce period. | Tap **Dark now** or **Light now** and grant superuser access permanently in your root manager. |
| `CRASH ...` | The app crashed. | Please report it with the line. |
| No new lines at all while the light changes | The monitor is not running or gets no readings. | Check that **Switch theme automatically** is on and the notification is showing. |

To exempt the app from battery optimisation:

- **Android 10 and 11 (LineageOS 17 and 18):** Settings → Apps & notifications
  → Lux Theme → Advanced → Battery → Battery optimisation → All apps → Lux
  Theme → Don't optimise.
- **Android 12 and later:** Settings → Apps → Lux Theme → Battery → Unrestricted.

To test a change of settings quickly, set the debounce time to something like
20 seconds, cover the sensor, and watch the status line under the switch
count down.

## Build

No Gradle. You need a JDK (11 or newer; tested with 21), Python 3 and the Android build tools.

With an Android SDK that has platform `android-30` and any `build-tools`
version installed:

```sh
ANDROID_HOME=/path/to/sdk ./build.sh
```

Without an SDK, put these files in one directory and point `TOOLS` at it:

| File | One place to get it |
|---|---|
| `aapt2` | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool), `prebuilt/linux/aapt2` |
| `android-30.jar` | [Sable/android-platforms](https://github.com/Sable/android-platforms), `android-30/android.jar` |
| `d8.jar`, `d8deps/*.jar`, `apksig.jar` | [facebook/buck](https://github.com/facebook/buck), `third-party/java` (`d8`, its dependencies, `aosp/apksig.jar`) |

```sh
TOOLS=/path/to/dir ./build.sh
```

Either way the result is `luxtheme.apk` in the repository root.

### Signing key

The APK is signed with your own key (JKS or PKCS12), always the same one, so
every build installs over the previous one. Name it and its password in a
`.env` file in the repository root (in `.gitignore`):

```sh
KEYSTORE=/path/to/<your-key>.jks
KEY_PASS=<password>
```

`KEY_ALIAS` is only needed if the keystore holds more than one key. Values are
taken literally; the same variables in the environment win over `.env`. The build stops if the key is missing and never creates
one. Keys are in `.gitignore` on purpose: whoever has the key can sign updates
to your installed app. Back it up somewhere private.

### Test

The debounce logic has no Android dependencies and runs on a desktop JVM:

```sh
make test
```

## Development

The repository includes a VS Code devcontainer with the JDK, the Android SDK
and adb, so nothing has to be installed on the host. Open the folder in VS
Code, choose *Reopen in Container*, then:

```sh
make build                           # signed luxtheme.apk
make test                            # debounce logic on the local JVM
make connect DEVICE_IP=192.168.1.50  # adb over the network
make run                             # install on the device and open the app
```

`make help` lists every target. [.devcontainer/README.md](.devcontainer/README.md)
covers the signing key, connecting the device and the Claude Code setup.
Changes are recorded in [changelog.md](changelog.md).

## Layout

| Path | Contents |
|---|---|
| `src/app/luxtheme/` | App code: settings screen, monitor service, boot receiver, debounce logic, root call, event log |
| `res/` | Layout, strings, icons |
| `tools/` | Packaging and signing helpers used by `build.sh` |
| `test/` | JVM test for the debounce logic |
| `build.sh`, `Makefile` | The build itself, and shortcuts for build, test, install and run |
| `.devcontainer/`, `.vscode/`, `.claude/`, `CLAUDE.md` | Development environment and editor/assistant configuration |

## Disclaimer

This software is provided "as is", without warranty of any kind. You use it
entirely at your own risk. The author is not responsible for any damage, data
loss, malfunctioning or bricked devices, voided warranties or any other
consequence of installing or using it, or of rooting a device in order to use
it. See [LICENSE](LICENSE) for the full terms.

## Licence

[0BSD](LICENSE): use it for anything, no attribution needed.

## Donations

If you have found this software to be useful, please consider donating to one of the following:
- Patreon http://patreon.com/zivke
- PayPal https://paypal.me/zivke85
- SOL DnoF5EHbbs75y7fhDBkhQwhugHz22bRLmF7ytBE4R1Hq
- ETH 0x2668f3aFEEd471A813a5e38abb72DD2477E393d5
- BTC bc1qs8tsm76p8m4ptzgun9u2jerwmhxln44nj62tt8
