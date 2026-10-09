#!/bin/sh
# Builds luxtheme.apk without Gradle or Android Studio.
#
#   ANDROID_HOME=/path/to/sdk ./build.sh   Android SDK with platform android-30 and any build-tools
#   TOOLS=/path/to/dir ./build.sh          loose files: aapt2, android-30.jar, d8.jar, d8deps/*.jar, apksig.jar
#
# Signing key: KEYSTORE (path to a JKS or PKCS12 file) and KEY_PASS (its
# password), normally in .env; the environment overrides .env. KEY_ALIAS is
# only needed if the keystore holds more than one key.
set -eu
cd "$(dirname "$0")"

if [ -n "${TOOLS:-}" ]; then
    AAPT2="$TOOLS/aapt2"
    ANDROID_JAR="$TOOLS/android-30.jar"
    D8_CP="$TOOLS/d8.jar"
    for jar in "$TOOLS"/d8deps/*.jar; do
        if [ -f "$jar" ]; then D8_CP="$D8_CP:$jar"; fi
    done
    APKSIG_JAR="$TOOLS/apksig.jar"
else
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$SDK" ]; then
        echo "Set ANDROID_HOME to an Android SDK, or TOOLS (see README.md)." >&2
        exit 1
    fi
    # Newest installed build-tools unless BUILD_TOOLS names a directory.
    BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -n 1)}"
    BUILD_TOOLS="${BUILD_TOOLS%/}"
    AAPT2="$BUILD_TOOLS/aapt2"
    ANDROID_JAR="$SDK/platforms/android-30/android.jar"
    D8_CP="$BUILD_TOOLS/lib/d8.jar"
    APKSIG_JAR="$BUILD_TOOLS/lib/apksigner.jar"
fi
for f in "$AAPT2" "$ANDROID_JAR" "${D8_CP%%:*}" "$APKSIG_JAR"; do
    if [ ! -f "$f" ]; then
        echo "Missing: $f" >&2
        exit 1
    fi
done

# .env holds plain NAME=value lines. Values are taken literally, no shell
# expansion, so a password may contain $ or spaces; one pair of quotes is removed.
if [ -f .env ]; then
    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        name="${line%%=*}"
        value="${line#*=}"
        case "$name" in KEYSTORE|KEY_ALIAS|KEY_PASS) ;; *) continue ;; esac
        case "$value" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
        esac
        # Only the three names above get here, so eval sees no file content.
        if eval "[ -z \"\${$name:-}\" ]"; then
            export "$name=$value"
        fi
    done < .env
fi

# The one key the app is signed with. It is never created here: an APK signed
# with any other key will not install over the app.
KEY_ALIAS="${KEY_ALIAS:-}"
if [ -z "${KEYSTORE:-}" ] || [ -z "${KEY_PASS:-}" ]; then
    echo "Set KEYSTORE and KEY_PASS in .env (see README.md, Signing key)." >&2
    exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
    echo "Missing signing key: $KEYSTORE" >&2
    exit 1
fi

rm -rf build && mkdir -p build/gen build/classes build/dex build/tools

"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/base.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --java build/gen build/res.zip

# --release 8 supplies the lambda bootstrap classes that android.jar leaves out.
javac -nowarn -Xlint:-options --release 8 -classpath "$ANDROID_JAR" \
    -d build/classes $(find src build/gen -name '*.java')

java -cp "$D8_CP" com.android.tools.r8.D8 --release --min-api 29 \
    --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')

python3 tools/package.py build/base.apk build/dex/classes.dex build/unsigned.apk

javac -nowarn -cp "$APKSIG_JAR" -d build/tools tools/Sign.java
java -cp "$APKSIG_JAR:build/tools" Sign "$KEYSTORE" "$KEY_ALIAS" "$KEY_PASS" \
    build/unsigned.apk luxtheme.apk
ls -l luxtheme.apk
