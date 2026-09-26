#!/usr/bin/env bash
# Renders docs/assets/mockups.html (README illustration) to docs/assets/hero.png with headless Chromium.
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME=${CHROME:-$(ls -d ~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome 2>/dev/null | sort -V | tail -1)}
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=1.25 \
  --window-size=1440,960 --virtual-time-budget=3000 \
  --screenshot="$PWD/docs/assets/hero.png" "file://$PWD/docs/assets/mockups.html" 2>/dev/null
echo "docs/assets/hero.png"
