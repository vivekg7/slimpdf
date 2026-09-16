#!/usr/bin/env bash
#
# Build the release APK and archive it into local/ under a name that says exactly what
# it is. Run at will; nothing in the build calls this.
#
#   ./scripts/archive-apk.sh              build, verify, archive
#   ./scripts/archive-apk.sh --force      allow replacing an existing archive
#   ./scripts/archive-apk.sh --no-build   use the APK already on disk
#   ./scripts/archive-apk.sh --no-mapping archive the APK without its R8 mapping
#
# Each archive is three files sharing one prefix: the APK, a .sha256, and the R8
# mapping.txt that turns an obfuscated stack trace from that build back into names.
# The mapping lives in app/build/ and a clean wipes it, so it is worth nothing unless it
# is kept with the APK it belongs to.
#
# A clean checkout produces "slimpdf-v1.0.apk". A dirty working tree produces
# "slimpdf-v1.0-dirty-g1a2b3c4.apk", so work in progress can never take the name of a
# release. If the target already exists the script refuses to overwrite it, which is the
# case that actually bites: same version, different commit.

set -euo pipefail

# Print the header comment block, so the usage text cannot drift from the docs above it.
usage() { awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; }

FORCE=0
BUILD=1
MAPPING=1
for arg in "$@"; do
    case "$arg" in
        --force)    FORCE=1 ;;
        --no-build) BUILD=0 ;;
        --no-mapping) MAPPING=0 ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

NAME=slimpdf
APK=app/build/outputs/apk/release/app-release.apk
MAP=app/build/outputs/mapping/release/mapping.txt
DEST=local

# The SDK tools need a JDK, and it is often not on PATH on a machine that only has
# Android Studio.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

sha256_of() {
    if command -v shasum >/dev/null; then shasum -a 256 "$1" | awk '{print $1}'
    else sha256sum "$1" | awk '{print $1}'; fi
}

# BSD and GNU disagree on both of these, and "date -r" means something else entirely
# under coreutils.
mtime_of() {
    date -r "$1" '+%Y-%m-%d %H:%M' 2>/dev/null \
        || stat -c '%y' "$1" 2>/dev/null | cut -d. -f1 \
        || echo "unknown"
}

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TOOLS=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
[ -n "$TOOLS" ] || { echo "error: no Android build-tools found under $SDK" >&2; exit 1; }

if [ "$BUILD" = 1 ]; then
    ./gradlew :app:assembleRelease -q
fi
[ -f "$APK" ] || { echo "error: $APK not found (drop --no-build?)" >&2; exit 1; }

# Version comes from the built APK, never from build.gradle.kts, so the filename cannot
# disagree with the manifest that actually shipped.
BADGING=$("$TOOLS/aapt" dump badging "$APK" | head -1)
VNAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
VCODE=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING")
[ -n "$VNAME" ] && [ -n "$VCODE" ] || { echo "error: could not read version from APK" >&2; exit 1; }

# Checked before anything is copied. A release build here comes out unsigned whenever
# local/keystore.properties is absent, and an unsigned APK carrying the release name is
# the same mislabelled artefact the rest of this script exists to prevent. Reading the
# fingerprint doubles as the check: no fingerprint, nothing to archive.
SIGNER=$("$TOOLS/apksigner" verify --print-certs "$APK" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' || true)
[ -n "$SIGNER" ] || {
    echo "error: $APK carries no signature." >&2
    echo "       Add local/keystore.properties (see README) and rebuild." >&2
    exit 1
}

# Also checked before the copy. release is built with isMinifyEnabled, so an archive
# without the mapping is an APK whose crashes can never be read again -- and the file
# only exists under app/build/, which any clean throws away. Pass --no-mapping if
# minification has been turned off and there is genuinely nothing to keep.
if [ "$MAPPING" = 1 ] && [ ! -f "$MAP" ]; then
    echo "error: $MAP not found." >&2
    echo "       It is written by the R8 step, so a build is needed (drop --no-build)," >&2
    echo "       or pass --no-mapping if release no longer minifies." >&2
    exit 1
fi

SUFFIX=""
if ! git diff --quiet HEAD 2>/dev/null || [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    SUFFIX="-dirty-g$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "warning: working tree is dirty; archiving as a work-in-progress build"
fi

TARGET="$DEST/${NAME}-v${VNAME}${SUFFIX}.apk"
mkdir -p "$DEST"

if [ -e "$TARGET" ] && [ "$FORCE" != 1 ]; then
    echo "error: $TARGET already exists." >&2
    echo "       Bump versionCode/versionName, or pass --force to replace it." >&2
    echo "       Existing: $(sha256_of "$TARGET" | cut -c1-16)…  $(mtime_of "$TARGET")" >&2
    exit 1
fi

cp "$APK" "$TARGET"
sha256_of "$TARGET" > "$TARGET.sha256"
# Named off $TARGET rather than the version, so every file belonging to one archive
# shares a prefix: "rm local/slimpdf-v1.0.apk*" takes the whole release and leaves no
# orphan mapping behind to be matched against the wrong APK later.
if [ "$MAPPING" = 1 ]; then
    cp "$MAP" "$TARGET.mapping.txt"
elif [ -e "$TARGET.mapping.txt" ]; then
    # --force just replaced the APK. A mapping left over from the build before it would
    # now describe an APK that is gone, which is worse than having no mapping at all.
    rm "$TARGET.mapping.txt"
    STALE=1
fi

echo "archived: $TARGET  ($(wc -c < "$TARGET" | tr -d ' ') bytes)"
echo "version:  $VNAME ($VCODE)"
echo "sha256:   $(cat "$TARGET.sha256")"
echo "signer:   $SIGNER"
if [ "$MAPPING" = 1 ]; then
    echo "mapping:  $TARGET.mapping.txt  ($(wc -c < "$TARGET.mapping.txt" | tr -d ' ') bytes)"
elif [ "${STALE:-0}" = 1 ]; then
    echo "mapping:  (skipped; removed the stale one left by the previous archive)"
else
    echo "mapping:  (skipped)"
fi
