#!/usr/bin/env bash
# Publishes a Tether build as a GitHub release for the app's built-in updates.
#
#   tools/publish_update.sh [release|debug] [--notes "What's new"] [--code N --name X.Y.Z]
#
# Builds the APK and creates a GitHub release (tag vX.Y.Z; debug builds: a pre-release tagged
# vX.Y.Z-debug.N) on the current commit with two assets: the APK and a manifest (update.json for
# release, update-debug.json for debug). Every installed Tether sees the update on its next check
# (app launch, or Settings → Check for updates), downloads it from GitHub and installs it after the
# usual Android confirmation. Needs the gh CLI, logged in with push access, and HEAD pushed.
set -euo pipefail
cd "$(dirname "$0")/.."
VARIANT=release; NOTES=""; CODE=""; NAME=""
while [ $# -gt 0 ]; do
  case "$1" in
    release|debug) VARIANT=$1 ;;
    --notes) NOTES=$2; shift ;;
    --code) CODE=$2; shift ;;
    --name) NAME=$2; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
# Without keystore.properties release builds fall back to the debug key, which installed apps reject.
[ "$VARIANT" = release ] && [ ! -f keystore.properties ] && { echo "keystore.properties missing — release must be signed with the release key" >&2; exit 1; }
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
PROPS=()
[ -n "$CODE" ] && PROPS+=("-PtetherVersionCode=$CODE")
[ -n "$NAME" ] && PROPS+=("-PtetherVersionName=$NAME")
TASK="assemble${VARIANT^}"
./gradlew ":app:$TASK" "${PROPS[@]}" --console=plain -q
APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
BADGE=$("$AAPT" dump badging "$APK" | sed -n 1p)
VC=$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$BADGE")
VN=$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$BADGE")
[ -z "$NOTES" ] && NOTES=$(git log -1 --pretty=%s 2>/dev/null || echo "")
TAG="v$VN"; PRE=(); [ "$VARIANT" = debug ] && { TAG="v${VN%-debug}-debug.$VC"; PRE=(--prerelease); }
git fetch --quiet origin
[ -n "$(git branch -r --contains HEAD)" ] || { echo "push HEAD to GitHub first — the release is tagged on it" >&2; exit 1; }
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
FILE="tether-$VARIANT-$VN-$VC.apk"
cp "$APK" "$OUT/$FILE"
SHA=$(sha256sum "$OUT/$FILE" | cut -d' ' -f1)
SIZE=$(stat -c%s "$OUT/$FILE")
MANIFEST="$OUT/update.json"; [ "$VARIANT" = debug ] && MANIFEST="$OUT/update-debug.json"
python3 - "$MANIFEST" "$VC" "$VN" "$FILE" "$SHA" "$SIZE" "$NOTES" <<'PY'
import json, sys, time
path, vc, vn, apk, sha, size, notes = sys.argv[1:8]
with open(path, "w") as f:
    json.dump({"versionCode": int(vc), "versionName": vn, "apk": apk, "sha256": sha, "size": int(size),
               "notes": notes, "publishedAt": int(time.time() * 1000)}, f, indent=2)
PY
gh release create "$TAG" "$OUT/$FILE" "$MANIFEST" --target "$(git rev-parse HEAD)" \
  --title "Tether $VN" --notes "${NOTES:-Tether $VN}" "${PRE[@]}"
echo "Published Tether $VN ($VC, $VARIANT) as $TAG"
echo "sha256 $SHA"
