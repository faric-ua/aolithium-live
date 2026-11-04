#!/usr/bin/env python3
"""Інструкція користувача (T7): docs/guide/guide.html → android/app/src/main/assets/guide/index.html.

Джерело — один файл-фрагмент (без <html>/<head>/<body>), обидві мови поруч (class="uk" / class="en"):
так той самий файл можна опублікувати артефактом. Для застосунку скрипт додає <!doctype html> і <head>
(інакше WebView у «режимі сумісності») і підставляє версію з android/app/build.gradle.kts замість __VERSION__.
Схема як у Track Catcher (scripts/build_guide.py там).

  python3 scripts/build_guide.py                 # перезібрати сторінку в застосунку
  python3 scripts/build_guide.py --fragment OUT  # фрагмент з версією і картинками поруч (для артефакту)
  python3 scripts/build_guide.py --share OUT     # один HTML-файл, щоб поділитися (картинки всередині, без особистого)
"""
import base64
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "docs/guide/guide.html"
OUT = ROOT / "android/app/src/main/assets/guide/index.html"
GRADLE = ROOT / "android/app/build.gradle.kts"
BODY_START = '<svg width="0" height="0"'


def version() -> str:
    m = re.search(r'versionName\s*=\s*"([^"]+)"', GRADLE.read_text(encoding="utf-8"))
    return m.group(1) if m else "?"


def fragment() -> str:
    return SRC.read_text(encoding="utf-8").replace("__VERSION__", version())


def page() -> str:
    text = fragment()
    cut = text.index(BODY_START)
    head, body = text[:cut].rstrip(), text[cut:].rstrip()
    head = head.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', "")
    return (
        "<!doctype html>\n<!-- Зібрано scripts/build_guide.py з docs/guide/guide.html — редагувати там. -->\n"
        '<html lang="uk">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
        f"{head}\n</head>\n<body>\n{body}\n</body>\n</html>\n"
    )


# ---------- «Поділитися» (faric 04.11 20:39: «help-файл хлопцям — щоб працював, з картинками, без особистого») ----------

IMG = ROOT / "android/app/src/main/assets/guide/img"

#: Що в інструкції — про телефони й родину faric. Для чужих людей — загальними словами. Кожна заміна мусить знайтись
#: (інакше текст змінився — скрипт падає, щоб нічого не проскочило мовчки).
SHARE_REPLACE = [
    ("<b>Android 12 і новіші</b> (A26)", "<b>Android 12 і новіші</b>"),
    ("<b>Android 12 and newer</b> (A26)", "<b>Android 12 and newer</b>"),
    ("<b>Android 8–11</b> (Huawei Mate 10 Lite, M30s)", "<b>Android 8–11</b>"),
    ("Для другого сторожа (наприклад, у мами) — свій бот.", "Для другого сторожа (наприклад, у родичів) — свій бот."),
    ("For a second guard (for example, at mom's) — its own bot.", "For a second guard (for example, at a relative's) — its own bot."),
    ("можна звести свого сторожа, мамин, братів —", "можна звести сторожів усієї родини —"),
    ("your guard, mom's and your brother's can share", "the whole family's guards can share"),
    (" Звірка з застосунком Logic Power на батареї в мами — ще попереду; якщо щось не так —", " Якщо щось не так —"),
    (" The comparison with the Logic Power app on the battery at mom's is still ahead; if something is wrong —", " If something is wrong —"),
    ("помилка 133 на A26", "помилка 133"),
    ("error 133 on the A26", "error 133"),
    (" · github.com/faric-ua/aolithium-live</p>", "</p>"),
    ("меню Termux, телефон без Termux, Google Диск", "файл APK, Google Диск"),
    ("Termux menu, phone without Termux, Google Drive", "APK file, Google Drive"),
    ("Без Termux і без браузера: головна, унизу", "Головна, унизу"),
    ("No Termux and no browser: home screen,", "Home screen,"),
    ("→ «Поділитися» → надішли Claude.", "→ «Поділитися» → надішли автору застосунку."),
    ('→ "Share" → send it to Claude.', '→ "Share" → send it to the app\'s author.'),
    ("Його пересилають Claude —", "Його пересилають автору застосунку —"),
    ("Forward it to Claude —", "Forward it to the app's author —"),
    ("«Журнал зв'язку» → «Поділитися» → Claude.", "«Журнал зв'язку» → «Поділитися» → автору застосунку."),
    ('"Connection log" → "Share" → Claude.', '"Connection log" → "Share" → the app\'s author.'),
]

#: Розділ «Встановити»: у faric — Termux-меню на його телефонах; іншим — просто файл APK.
SHARE_INSTALL = (
    '''  <div class="panel stack-s">
    <h3 class="uk">Перший раз — файл APK</h3><h3 class="en">First time — the APK file</h3>
    <ol class="steps uk">
      <li><span>Файл <b>Aolithium-Live-….apk</b> дає автор — посиланням або в месенджері.</span></li>
      <li><span>Відкрий його. Android спитає, чи можна ставити застосунки з цього джерела (браузер чи месенджер), — <b>Дозволити</b>.</span></li>
      <li><span><b>Встановити</b>. Далі — «Перший запуск».</span></li>
    </ol>
    <ol class="steps en">
      <li><span>The <b>Aolithium-Live-….apk</b> file comes from the author — as a link or in a messenger.</span></li>
      <li><span>Open it. Android asks whether apps may be installed from this source (browser or messenger) — <b>Allow</b>.</span></li>
      <li><span><b>Install</b>. Then — "First start".</span></li>
    </ol>
  </div>
'''
)

#: Що не може лишитись у файлі для чужих (перевірка після замін). Слова — з межами, щоб «moment» не був «mom».
SHARE_FORBIDDEN = [r"A26", r"M30s", r"Huawei", r"Mate 10", r"\b[Мм]ам[аиіоу]\b", r"\bмамин", r"\bmom\b", r"faric", r"Termux",
                   r"AOLITHIUM LIVE", r"Claude", r"Павлівк", r"A4:C1", r"C8:47", r"kopaev"]


def share_page() -> str:
    """Один файл: картинки всередині (data:), без коментарів автора, без особистого, без пунктів меню Termux."""
    text = page()
    text = re.sub(r"<!--.*?-->\n?", "", text, flags=re.S)
    text = re.sub(r"[ \t]*//[^\n]*faric[^\n]*\n", "", text)  # коментарі в скрипті з датами розмов
    text = re.sub(r"<style>.*?</style>", lambda m: re.sub(r"/\*.*?\*/\n?", "", m.group(0), flags=re.S), text, flags=re.S)
    for old, new in SHARE_REPLACE:
        if old not in text:
            raise SystemExit(f"--share: не знайшов «{old}» — інструкція змінилась, онови SHARE_REPLACE")
        text = text.replace(old, new)
    m = re.search(r'  <div class="panel stack-s">\n    <h3 class="uk">Телефон з Termux.*?(?=  <div class="panel stack-s">\n    <h3 class="uk">З Google Диска)',
                  text, flags=re.S)
    if not m:
        raise SystemExit("--share: не знайшов розділ «Встановити» (Termux)")
    text = text[:m.start()] + SHARE_INSTALL + text[m.end():]

    def inline(mm: re.Match) -> str:
        f = IMG / mm.group(1)
        if not f.is_file():
            raise SystemExit(f"--share: нема картинки {f}")
        return 'src="data:image/webp;base64,' + base64.b64encode(f.read_bytes()).decode("ascii") + '"'

    text = re.sub(r'src="img/([^"]+\.webp)"', inline, text)
    left = [w for w in SHARE_FORBIDDEN if re.search(w, re.sub(r'data:image/[^"]+', "", text))]
    if left:
        raise SystemExit(f"--share: лишилось особисте: {left}")
    return text


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "--share":
        Path(argv[1]).write_text(share_page(), encoding="utf-8")
        print(f"для поширення → {argv[1]} ({Path(argv[1]).stat().st_size // 1024} КБ, версія {version()})")
        return 0
    if len(argv) == 2 and argv[0] == "--fragment":
        Path(argv[1]).write_text(fragment(), encoding="utf-8")
        print(f"фрагмент → {argv[1]}")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(page(), encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {OUT.stat().st_size // 1024} КБ, версія {version()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
