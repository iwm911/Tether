#!/usr/bin/env bash
# Renders the README illustrations (docs/assets/<name>.html → <name>.png) with headless Chromium.
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME=${CHROME:-$(ls -d ~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome 2>/dev/null | sort -V | tail -1)}
render() { # name width height
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=1.25 \
    --window-size="$2,$3" --virtual-time-budget=3000 \
    --screenshot="$PWD/docs/assets/$1.png" "file://$PWD/docs/assets/$1.html" 2>/dev/null
  echo "docs/assets/$1.png"
}
render hero 1440 960
render how-it-works 1200 300
