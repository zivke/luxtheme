# Devcontainer

VS Code devcontainer for Lux Theme: JDK, Android SDK, adb and Claude Code,
none of it installed on the host.

## Before the first build: your signing key

Android only installs an update over an existing app if both are signed with
the same key. `build.sh` signs with the key that `KEYSTORE` in `.env` names,
kept in the `android-keys` volume (`~/.android-keys`), and stops with an error
if it is missing. Seed the volume with the key that signed the installed APK:

```
docker volume create android-keys
docker run --rm -v android-keys:/k -v "$HOME/<dir-with-your-key>":/src:ro \
  alpine sh -c 'cp /src/<your-key>.jks /k/ && chown 1000:1000 /k/<your-key>.jks'
```

If you skip this, post-create prints a warning and the build fails until the
key is there. The only way out of a key mismatch on the device is
`make uninstall`, which loses the app's settings.

Then create `.env` in the repository root (it is in `.gitignore`):

```sh
KEYSTORE=/home/vscode/.android-keys/<your-key>.jks
KEY_PASS=<password>
```

`KEY_ALIAS` can be left out when the keystore holds a single key. The key password must be the
same as the keystore password.

## What is in the image

Ubuntu 24.04 (`mcr.microsoft.com/devcontainers/base`), OpenJDK 21, Python 3
and an Android SDK in `/opt/android-sdk` with exactly three packages:
`platform-tools` (adb), `platforms;android-30` and `build-tools;34.0.0`. The
versions are `ARG`s at the top of the Dockerfile. Building the image accepts
the Android SDK licence agreements (`sdkmanager --licenses`).

There is no emulator. The app depends on a `su` that ordinary apps may call,
which the stock emulator images do not have, so testing happens on a real
rooted device.

## First run

1. Reopen in container.
2. In the container terminal:

   ```
   make build
   make test
   ```

3. Connect the device (next section), then `make run`.
4. `claude` — browser OAuth once per devcontainer. After that it auto-starts.

## Reaching the device

**Over the network (default).** On LineageOS: Settings → System → Developer
options → enable *Android debugging* and *ADB over network* (the switch shows
the address, and turns itself off again on reboot). Then:

```
make connect DEVICE_IP=192.168.1.50
```

Accept the "Allow USB debugging?" prompt on the device the first time. adb's
key pair lives in the `android-adb` volume, so the authorisation survives
rebuilds. Export `LUXTHEME_DEVICE_IP` on the host to preset the address.

On stock Android 11+ the equivalent is *Wireless debugging*, which uses a
pairing code and its own port: `adb pair <ip>:<port>`, then
`adb connect <ip>:<port>`.

**Over USB.** Uncomment the `runArgs` line in `devcontainer.json`, rebuild,
and run `adb kill-server` on the host so the host's adb releases the device.

## Volumes

| Volume                            | Mount             | Holds                                |
|-----------------------------------|-------------------|--------------------------------------|
| `claude-config-${devcontainerId}` | `~/.claude`       | Claude Code auth, settings, sessions |
| `android-keys`                    | `~/.android-keys` | APK signing keys                     |
| `android-adb`                     | `~/.android`      | adb key pair (device authorisation)  |

The two `android-*` volumes use fixed names on purpose — the signing keys and
the adb identity are yours, so every Android project of yours shares them.
Claude's volume is `${devcontainerId}`-scoped, so sessions stay per project.
The SDK is in the image, not a volume: it needs no login and is a few hundred
MB.

## Claude Code

- **Auto-start**: `.vscode/tasks.json` has a `folderOpen` task running
  `claude --continue`, so opening the project resumes the last session for
  the workspace folder. Sessions are keyed by directory and stored under
  `CLAUDE_CONFIG_DIR`, both of which are stable here. `--continue` exits 1 with
  no prior session, hence the `|| claude` fallback. Drop `runOptions` from the
  task to make it manual.
  This has to be a task, not `postStartCommand` — lifecycle commands run
  without a TTY and the Claude Code interface needs one.
- **Updates** run on three tracks: the built-in background updater, `claude
  update` in `postStartCommand` on every container start, and a fresh install on
  rebuild. `CLAUDE_CHANNEL` (`latest` or `stable`) sets the auto-update channel.
  `claude doctor` shows the last update attempt. Don't set `DISABLE_AUTOUPDATER`.
- **Permissions** are in `.claude/settings.json`: `Bash(*)` allowed, with the
  signing keys and `git push` denied. The container is the real boundary.

## Troubleshooting

- **`adb devices` shows `unauthorized`**: the prompt on the device was missed
  or declined. Toggle *Android debugging* off and on, reconnect, accept it.
- **`make connect` times out**: the container reaches the LAN through Docker's
  default bridge, so the device must be reachable from the host too
  (`ping <ip>` there). Guest/isolated Wi-Fi networks block it.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: the APK is signed with a different
  key than the installed app. See "your signing key" above.
- **Java extension shows errors on `R`**: `build/gen/…/R.java` is generated by
  the build. Run `make build` once, then *Java: Clean Java Language Server
  Workspace* from the palette.
- **Different SDK package versions**: change the `ARG`s in the Dockerfile and
  rebuild, or install extra packages in a running container with `sdkmanager`.
