#!/usr/bin/env python3
"""T12: кожен український текст для людини — у парі з англійським.

Шукає в коді застосунку (android/app/src/main/java) рядки з кирилицею, які стоять НЕ всередині `tr("…", "…")`
(або `Fmt.plural(n, укр×3, англ×2)`), і кирилицю в англійській половині пари (забули перекласти).
Коментарі не рахуються. Рядок можна свідомо лишити українським — коментар `// uk-only` на тому ж рядку
(напр. назва мови «Українська» на перемикачі).

Запуск: python3 scripts/audit_lang.py [файли…]  — без аргументів перевіряє весь застосунок; код виходу 1, якщо є що виправити.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "android/app/src/main/java"
CYR = re.compile(r"[А-Яа-яІіЇїЄєҐґ]")
# назва виклику → з якого аргументу (від 0) починається англійська частина
PAIRS = {"tr": 1, "plural": 4}


def scan(text):
    """Повертає список (рядок, текст, причина)."""
    out = []
    stack = []  # [назва, номер аргументу]
    i, n = 0, len(text)
    line_of = lambda pos: text.count("\n", 0, pos) + 1
    lines = text.split("\n")

    def allowed_line(pos):
        return "// uk-only" in lines[line_of(pos) - 1]

    def check_literal(start, value):
        if not CYR.search(value) or allowed_line(start):
            return
        frames = [f for f in stack if f[0] in PAIRS]
        if not frames:
            out.append((line_of(start), value, "без англійської: загорни в tr(\"укр\", \"англ\")"))
            return
        f = frames[-1]
        if f[1] >= PAIRS[f[0]]:
            out.append((line_of(start), value, "кирилиця в англійському тексті"))

    def read_string(j, raw):
        """j — позиція після відкривних лапок; повертає (кінець, текст без шаблонів)."""
        buf = []
        while j < n:
            if raw and text.startswith('"""', j):
                return j + 3, "".join(buf)
            c = text[j]
            if not raw and c == "\\":
                buf.append(text[j:j + 2]); j += 2; continue
            if not raw and c == '"':
                return j + 1, "".join(buf)
            if c == "$" and j + 1 < n and text[j + 1] == "{":
                j = code(j + 2, until_brace=True)
                buf.append("…")
                continue
            buf.append(c); j += 1
        return j, "".join(buf)

    def code(j, until_brace=False):
        depth = 0
        while j < n:
            c = text[j]
            if text.startswith("//", j):
                j = text.find("\n", j)
                j = n if j < 0 else j
                continue
            if text.startswith("/*", j):
                k = text.find("*/", j + 2)
                j = n if k < 0 else k + 2
                continue
            if text.startswith('"""', j):
                start = j
                j, val = read_string(j + 3, raw=True)
                check_literal(start, val)
                continue
            if c == '"':
                start = j
                j, val = read_string(j + 1, raw=False)
                check_literal(start, val)
                continue
            if c == "'":
                k = j + 1
                if k < n and text[k] == "\\":
                    k += 1
                k = text.find("'", k + 1)
                j = n if k < 0 else k + 1
                continue
            if c == "(":
                m = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*$", text[max(0, j - 60):j])
                stack.append([m.group(1) if m else "", 0])
            elif c == ")":
                if stack:
                    stack.pop()
            elif c == "," and stack:
                stack[-1][1] += 1
            elif c == "{":
                depth += 1
            elif c == "}":
                if until_brace and depth == 0:
                    return j + 1
                depth -= 1
            j += 1
        return j

    code(0)
    return out


def files(args):
    if args:
        return [pathlib.Path(a) for a in args]
    return sorted(SRC.rglob("*.kt"))


def main(argv):
    total = 0
    for f in files(argv[1:]):
        for ln, val, why in scan(f.read_text(encoding="utf-8")):
            total += 1
            rel = f.relative_to(ROOT) if f.is_absolute() and ROOT in f.parents else f
            print(f"{rel}:{ln}: {why}: «{val[:70]}»")
    print(f"Без перекладу: {total}" if total else "Мова: PASS — усі тексти мають англійську пару")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
