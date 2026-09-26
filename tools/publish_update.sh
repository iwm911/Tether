#!/usr/bin/env bash
# Stages a Tether build for the app's built-in updates.
#
#   tools/publish_update.sh [release|debug] [--notes "What's new"] [--code N --name X.Y.Z]
#
# Builds the APK, then copies it into ~/.tether/app/ on THIS machine together with a manifest
# (update.json for release, update-debug.json for debug). Every phone that has this machine
# saved in Tether sees the update on its next check (app launch, or Settings → Check for updates),
# downloads it over SSH and installs it after the usual Android confirmation.
# To serve other machines too, copy ~/.tether/app/ there (e.g. rsync -a ~/.tether/app/ host:.tether/app/).
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
DEST="$HOME/.tether/app"; mkdir -p "$DEST"; chmod 700 "$HOME/.tether" 2>/dev/null || true
FILE="tether-$VARIANT-$VN-$VC.apk"
cp "$APK" "$DEST/$FILE.tmp" && mv "$DEST/$FILE.tmp" "$DEST/$FILE"
SHA=$(sha256sum "$DEST/$FILE" | cut -d' ' -f1)
SIZE=$(stat -c%s "$DEST/$FILE")
MANIFEST="$DEST/update.json"; [ "$VARIANT" = debug ] && MANIFEST="$DEST/update-debug.json"
python3 - "$MANIFEST" "$VC" "$VN" "$FILE" "$SHA" "$SIZE" "$NOTES" <<'PY'
import json, os, sys, time
path, vc, vn, apk, sha, size, notes = sys.argv[1:8]
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump({"versionCode": int(vc), "versionName": vn, "apk": apk, "sha256": sha, "size": int(size),
               "notes": notes, "publishedAt": int(time.time() * 1000)}, f, indent=2)
os.replace(tmp, path)
PY
# keep the 3 newest APKs of this variant
ls -1t "$DEST"/tether-"$VARIANT"-*.apk 2>/dev/null | tail -n +4 | xargs -r rm -f
echo "Published Tether $VN ($VC, $VARIANT) → $DEST/$FILE"
echo "sha256 $SHA"
