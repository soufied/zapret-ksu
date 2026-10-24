#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT/app"
OUT_DIR="$ROOT/dist"
GRADLE_VERSION="${GRADLE_VERSION:-8.10.2}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
UBER_JAR_SYSTEM=/usr/share/java/uber-apk-signer/uber-apk-signer.jar

usage() {
    cat <<'EOF'
usage: build-app.sh [--release]

  (default)   debug APK, copied to dist/
  --release   signed release APK: asks for a keystore, or signs with the
              uber-apk-signer debug key when none is given

environment: ANDROID_HOME, JAVA_HOME, GRADLE_VERSION, KEYSTORE, KS_ALIAS,
             KS_PASS, KEY_PASS, UBER_SIGNER
EOF
}

MODE=debug
for arg in "$@"; do
    case "$arg" in
        --release) MODE=release ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 1 ;;
    esac
done

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

java_major() {
    "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1
}

say "Looking for a JDK 17-23 for Gradle"
GRADLE_JDK=""
for cand in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/*; do
    [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
    major="$(java_major "$cand")"
    if [ -n "$major" ] && [ "$major" -ge 17 ] && [ "$major" -le 23 ]; then
        GRADLE_JDK="$cand"
        break
    fi
done
[ -n "$GRADLE_JDK" ] || die "no JDK 17-23 found. Gradle 8.10 cannot run on JDK 27. Install one with: sudo pacman -S jdk21-openjdk"
export JAVA_HOME="$GRADLE_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
echo "    using $JAVA_HOME (Java $(java_major "$JAVA_HOME"))"

say "Checking Android SDK at $ANDROID_HOME"
[ -d "$ANDROID_HOME/platforms" ] || die "SDK not found at $ANDROID_HOME. Set ANDROID_HOME or install the SDK (see README)."
export ANDROID_HOME
if [ ! -f "$APP_DIR/local.properties" ]; then
    echo "sdk.dir=$ANDROID_HOME" > "$APP_DIR/local.properties"
    echo "    wrote app/local.properties"
fi

cd "$APP_DIR"
if [ ! -x ./gradlew ]; then
    say "Generating Gradle wrapper $GRADLE_VERSION"
    command -v gradle >/dev/null 2>&1 || die "gradle not found. Install it with: sudo pacman -S gradle"
    gradle wrapper --gradle-version "$GRADLE_VERSION"
fi

if [ "$MODE" = release ]; then TASK=assembleRelease; else TASK=assembleDebug; fi
say "Building ($TASK)"
./gradlew "$TASK"

APK_DIR="$APP_DIR/app/build/outputs/apk/$MODE"
BUILT="$(find "$APK_DIR" -maxdepth 1 -name '*.apk' | head -n 1 || true)"
[ -n "$BUILT" ] || die "no APK found in $APK_DIR"
echo "    built: $BUILT"

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$APP_DIR/app/build.gradle.kts" | head -n 1)"
mkdir -p "$OUT_DIR"
FINAL="$OUT_DIR/zapret-ksu-${VERSION:-dev}-$MODE.apk"

if [ "$MODE" = debug ]; then
    cp -f "$BUILT" "$FINAL"
    say "Done: $FINAL"
    exit 0
fi

sign_with_keystore() {
    local ks="${KEYSTORE:-}" alias="${KS_ALIAS:-}"

    if [ -z "$ks" ]; then
        read -r -p "Path to keystore (empty = debug key via uber-apk-signer): " ks
    fi
    if [ -z "$ks" ]; then
        return 1
    fi
    ks="${ks/#\~/$HOME}"
    [ -f "$ks" ] || die "keystore not found: $ks"

    if [ -z "$alias" ]; then
        read -r -p "Key alias: " alias
    fi
    [ -n "$alias" ] || die "key alias is required"

    if [ -z "${KS_PASS:-}" ]; then
        read -r -s -p "Keystore password: " KS_PASS; echo
    fi
    if [ -z "${KEY_PASS:-}" ]; then
        read -r -s -p "Key password (Enter = same as keystore): " KEY_PASS; echo
        KEY_PASS="${KEY_PASS:-$KS_PASS}"
    fi

    local bt apksigner
    bt="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1 || true)"
    apksigner="${bt}apksigner"
    [ -x "$apksigner" ] || die "apksigner not found in $ANDROID_HOME/build-tools"

    say "Signing with $ks (alias: $alias)"
    export KS_PASS KEY_PASS
    "$apksigner" sign \
        --ks "$ks" \
        --ks-key-alias "$alias" \
        --ks-pass env:KS_PASS \
        --key-pass env:KEY_PASS \
        --out "$FINAL" \
        "$BUILT"
    unset KS_PASS KEY_PASS
    "$apksigner" verify --verbose "$FINAL" >/dev/null || die "signature verification failed"
    return 0
}

sign_with_uber() {
    local jar="${UBER_SIGNER:-}"
    if [ -n "$jar" ] && [ -f "$jar" ]; then
        SIGN_CMD=(java -jar "$jar")
    elif [ -f "$UBER_JAR_SYSTEM" ]; then
        SIGN_CMD=(java -jar "$UBER_JAR_SYSTEM")
    elif command -v uber-apk-signer >/dev/null 2>&1; then
        SIGN_CMD=(uber-apk-signer)
    else
        die "uber-apk-signer not found. Install it or set UBER_SIGNER=/path/to/uber-apk-signer.jar"
    fi
    say "Signing with uber-apk-signer (debug key)"
    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    "${SIGN_CMD[@]}" --allowResign -a "$BUILT" -o "$tmp" >/dev/null
    local signed
    signed="$(find "$tmp" -maxdepth 1 -name '*.apk' | head -n 1 || true)"
    [ -n "$signed" ] || die "uber-apk-signer failed"
    mv -f "$signed" "$FINAL"
}

if ! sign_with_keystore; then
    sign_with_uber
fi

say "Done: $FINAL"
