#!/usr/bin/env bash
# .lyplugin paketi üretir (plugin.json + main.js içeren ZIP)
set -euo pipefail
cd "$(dirname "$0")"
rm -f turkce-sayi-okuyucu.lyplugin
zip -j turkce-sayi-okuyucu.lyplugin plugin.json main.js
echo "OK → $(pwd)/turkce-sayi-okuyucu.lyplugin"
