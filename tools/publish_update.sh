#!/usr/bin/env bash
# Publishes a Tether build as a GitHub release for the app's built-in updates.
#
#   tools/publish_update.sh [release|beta|debug] [--notes "What's new"] [--code N --name X.Y.Z]
#
# Builds the APK and creates a GitHub release on the current commit with two assets: the APK and a
# manifest (update.json; update-debug.json for debug).
#   release  stable, tag vX.Y.Z — every installed Tether is offered it.
#   beta     release-signed, --name X.Y.Z-beta.N, published as a pre-release tagged vX.Y.Z-beta.N —
#            offered only to apps with Settings › Beta updates on (beta builds have it on).
#   debug    debug-signed pre-release vX.Y.Z-debug.N for debug builds.
# Stable and beta share one versionCode sequence: each new one must be higher than every published
# stable and beta release. Every installed Tether sees the update on its next check
# (app launch, or Settings → Check for updates), downloads it from GitHub and installs it after the
# usual Android confirmation. Needs the gh CLI, logged in with push access, and HEAD pushed.
set -euo pipefail
cd "$(dirname "$0")/.."
VARIANT=release; NOTES=""; CODE=""; NAME=""
while [ $# -gt 0 ]; do
  case "$1" in
    release|beta|debug) VARIANT=$1 ;;
    --notes) NOTES=$2; shift ;;
    --code) CODE=$2; shift ;;
    --name) NAME=$2; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
# Without keystore.properties release builds fall back to the debug key, which installed apps reject.
BUILD=$VARIANT; [ "$VARIANT" = beta ] && BUILD=release
[ "$VARIANT" = beta ] && [[ "$NAME" != *-beta* ]] && { echo "beta needs --name X.Y.Z-beta.N" >&2; exit 2; }
[ "$VARIANT" = release ] && [[ "$NAME" == *-beta* ]] && { echo "a -beta name is a beta: use the beta variant" >&2; exit 2; }
[ "$BUILD" = release ] && [ ! -f keystore.properties ] && { echo "keystore.properties missing — release must be signed with the release key" >&2; exit 1; }
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
PROPS=()
[ -n "$CODE" ] && PROPS+=("-PtetherVersionCode=$CODE")
[ -n "$NAME" ] && PROPS+=("-PtetherVersionName=$NAME")
TASK="assemble${BUILD^}"
./gradlew ":app:$TASK" "${PROPS[@]}" --console=plain -q
APK="app/build/outputs/apk/$BUILD/app-$BUILD.apk"
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
BADGE=$("$AAPT" dump badging "$APK" | sed -n 1p)
VC=$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$BADGE")
VN=$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$BADGE")
# Installed apps only offer a version whose code is higher than theirs: refuse one that isn't higher
# than every published stable and beta release's (one sequence), which nobody could receive.
if [ "$BUILD" = release ]; then
  LATEST=$(gh api "repos/{owner}/{repo}/releases?per_page=50" \
      --jq '.[] | select(.draft | not) | .assets[] | select(.name == "update.json") | .browser_download_url' 2>/dev/null \
    | python3 -c 'import json,sys,urllib.request as u; c=[json.load(u.urlopen(l.strip(), timeout=20))["versionCode"] for l in sys.stdin if l.strip()]; print(max(c) if c else "")' 2>/dev/null || true)
  [ -n "$LATEST" ] && [ "$VC" -le "$LATEST" ] && { echo "versionCode $VC is not higher than the latest stable/beta release ($LATEST) — pass --code N with N > $LATEST" >&2; exit 1; }
fi
[ -z "$NOTES" ] && NOTES=$(git log -1 --pretty=%s 2>/dev/null || echo "")
TAG="v$VN"; PRE=(); [ "$VARIANT" = beta ] && PRE=(--prerelease)
[ "$VARIANT" = debug ] && { TAG="v${VN%-debug}-debug.$VC"; PRE=(--prerelease); }
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
