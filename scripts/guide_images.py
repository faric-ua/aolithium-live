#!/usr/bin/env python3
"""Знімки з емулятора (workflow «UI screens», гілка diag/ui-screens-out) → картинки інструкції обома мовами.

Запуск: python3 scripts/guide_images.py ШЛЯХ_ДО_ui-screens
Бере g-uk-<назва>.png і g-en-<назва>.png (знімає scripts/ci/ui-screens.sh, демо-дані — без особистого)
і пише WebP у android/app/src/main/assets/guide/img/uk/ та img/en/.
"""
import sys
from pathlib import Path

NAMES = [
    "home", "now-charging", "now-discharging", "until-full", "link-lost", "cells", "outages",
    "charts-days", "board", "info", "alerts", "widget", "jk-cells", "guard", "guard-setup",
]
LANGS = ["uk", "en"]
OUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/guide/img"


def main() -> int:
    from PIL import Image  # лише тут: тест інструкції читає NAMES і без Pillow (у CI його нема)

    src = Path(sys.argv[1])
    total = 0
    for lang in LANGS:
        (OUT / lang).mkdir(parents=True, exist_ok=True)
        for name in NAMES:
            im = Image.open(src / f"g-{lang}-{name}.png").convert("RGB")
            width = 540
            im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
            path = OUT / lang / f"{name}.webp"
            im.save(path, "WEBP", quality=76, method=6)
            total += path.stat().st_size
            print(f"{lang}/{name}.webp: {path.stat().st_size // 1024} КБ")
    print(f"Разом: {total // 1024} КБ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
