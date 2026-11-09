#!/usr/bin/env bash
# Aggiorna la copia di Mia AI integrata nel launcher (app/src/main/assets/mia-ai).
# Uso: ./scripts/update-mia-ai.sh [cartella di mia-ai-app]   (default: clona da GitHub)
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${1:-}"
if [ -z "$SRC" ]; then
  SRC="$(mktemp -d)/mia-ai-app"
  git clone --depth 1 https://github.com/micmax1000-afk/mia-ai-app "$SRC"
fi
DEST=app/src/main/assets/mia-ai
rm -rf "$DEST" && mkdir -p "$DEST"
cp -r "$SRC"/index.html "$SRC"/manifest.webmanifest "$SRC"/css "$SRC"/js "$SRC"/icons "$DEST"/
cp "$SRC"/resources/android-res/mipmap-xxxhdpi/ic_launcher.png "$DEST"/icon.png
echo "Mia AI aggiornata in $DEST"
