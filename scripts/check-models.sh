#!/usr/bin/env bash
# Katalogdaki her modelin URL'sine HEAD isteği atar; boyut doğrular.
# Gereksinim: curl, jq, python3
set -euo pipefail
cd "$(dirname "$0")/.."
CATALOG=app/src/main/assets/catalog/models.json

python3 - "$CATALOG" <<'PY'
import json, subprocess, sys

catalog = json.load(open(sys.argv[1]))
models = catalog["models"]
print(f"{len(models)} model denetleniyor…\n")

fail = 0
for m in models:
    url = m["url"]
    try:
        out = subprocess.run(
            ["curl", "-sIL", "--max-time", "30", url],
            capture_output=True, text=True, timeout=40
        ).stdout.lower()
        status = [l.split()[1] for l in out.splitlines() if l.startswith("http/")]
        length = ""
        for line in out.splitlines():
            if line.startswith("content-length:"):
                length = line.split(":", 1)[1].strip()
        ok = status and status[-1] == "200"
        size_ok = ""
        if length:
            expected = m["sizeBytes"]
            actual = int(length)
            size_ok = "boyut ✓" if actual == expected else f"BOYUT UYUŞMAZ: {actual} != {expected}"
            if actual != expected:
                ok = False
        mark = "✓" if ok else "✗"
        print(f"{mark} {m['id']:34} {status[-1] if status else '??':>3}  {length or '-':>12}  {size_ok}")
        if not ok:
            fail += 1
    except Exception as e:
        print(f"✗ {m['id']:34} HATA {e}")
        fail += 1

print()
if fail:
    print(f"{fail} model sorunlu — katalog güncellenmeli")
    sys.exit(1)
print("Tüm URL'ler geçerli ✓")
PY
