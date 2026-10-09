#!/usr/bin/env bash
set -euo pipefail

# Named volumes mount root-owned the first time.
sudo chown -R vscode:vscode /home/vscode/.claude /home/vscode/.android \
                            /home/vscode/.android-keys

# --- Claude Code -------------------------------------------------------------
# Native install, no Node.js: the launcher at ~/.local/bin/claude symlinks into
# ~/.local/share/claude/versions/. Claude Code then checks for updates at startup
# and periodically while running, installing in the background — so this only has
# to establish a current baseline after a rebuild.
#
# The channel passed here becomes the default for those auto-updates:
#   latest — every release as it ships (default)
#   stable — roughly a week behind, skips releases with major regressions
if command -v claude >/dev/null 2>&1; then
  claude update || true
else
  curl -fsSL https://claude.ai/install.sh | bash -s "${CLAUDE_CHANNEL:-latest}"
fi
claude --version || true

# --- signing key -------------------------------------------------------------
# build.sh signs with the key that KEYSTORE in .env names and refuses to build
# without it: Android will not install an APK over one signed with a different
# key, so a key is never generated here.
ENV_FILE="$(dirname "$0")/../.env"
if [ -z "${KEYSTORE:-}" ] && [ -f "$ENV_FILE" ]; then
  KEYSTORE="$(sed -n 's/^KEYSTORE=//p' "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed "s/^[\"']//; s/[\"']\$//")"
fi
chmod 700 "$HOME/.android-keys"
if [ -z "${KEYSTORE:-}" ]; then
  cat <<'WARN'

!! No KEYSTORE in .env yet, so `make build` will fail. Create .env in the
!! repository root with the key's path and password:
!!
!!   KEYSTORE=/home/vscode/.android-keys/<your-key>.jks
!!   KEY_PASS=<password>

WARN
elif [ -f "$KEYSTORE" ]; then
  chmod 600 "$KEYSTORE"
  echo ">> Signing key: $KEYSTORE"
else
  cat <<'WARN'

!! The signing key named in .env is not in the android-keys volume yet, so
!! `make build` will fail. Copy the key that signed the installed APK into it:
!!
!!   docker run --rm -v android-keys:/k -v "$HOME/<dir-with-your-key>":/src:ro \
!!     alpine sh -c 'cp /src/<your-key>.jks /k/ && chown 1000:1000 /k/<your-key>.jks'
!!
!! Then back that key up somewhere durable.

WARN
fi

# --- Android SDK -------------------------------------------------------------
echo ">> Android SDK: $ANDROID_HOME"
ls "$ANDROID_HOME/build-tools" "$ANDROID_HOME/platforms" | sed 's/^/     /'
adb version | head -n 1

cat <<'EOF'
>> Next:
     make build                          # luxtheme.apk
     make test                           # debounce logic, plain JVM
     make connect DEVICE_IP=<device ip>  # adb over the network, once per session
     make run                            # install on the device and open the app

   Then: claude   (browser OAuth, once per devcontainer)
EOF
