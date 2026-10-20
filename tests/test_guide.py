"""Інструкція користувача (T7, assets/guide): зібрана з джерела, дві мови парами, посилання й картинки на місці."""
import re
import sys
import unittest
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import build_guide  # noqa: E402
import guide_images  # noqa: E402

APP = ROOT / "android/app/src/main/assets/guide/index.html"
KOTLIN = ROOT / "android/app/src/main/java/ua/faric/aolithium"
CYR = re.compile(r"[А-Яа-яІіЇїЄєҐґ]")
VOID = {"img", "meta", "br", "hr", "input", "link", "source"}


class Node:
    def __init__(self, tag, attrs, parent):
        self.tag, self.attrs, self.parent = tag, dict(attrs), parent
        self.children: list = []  # Node або str
        self.classes = set((self.attrs.get("class") or "").split())

    def lang(self):
        """uk / en — за класом або lang= найближчого предка (None — спільне для обох мов)."""
        n = self
        while n is not None:
            if "uk" in n.classes or n.attrs.get("lang") == "uk":
                return "uk"
            if "en" in n.classes:
                return "en"
            n = n.parent
        return None

    def walk(self):
        for c in self.children:
            if isinstance(c, Node):
                yield c
                yield from c.walk()


class Tree(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("#root", [], None)
        self.cur = self.root
        self.texts: list[tuple[Node, str]] = []

    def handle_starttag(self, tag, attrs):
        n = Node(tag, attrs, self.cur)
        self.cur.children.append(n)
        if tag not in VOID:
            self.cur = n

    def handle_startendtag(self, tag, attrs):
        self.cur.children.append(Node(tag, attrs, self.cur))

    def handle_endtag(self, tag):
        n = self.cur
        while n is not None and n.tag != tag:
            n = n.parent
        if n is not None and n.parent is not None:
            self.cur = n.parent

    def handle_data(self, data):
        if data.strip() and self.cur.tag not in ("style", "script", "title"):
            self.cur.children.append(data)
            self.texts.append((self.cur, data))


def parse(html: str) -> Tree:
    t = Tree()
    t.feed(html)
    return t


class GuideTests(unittest.TestCase):
    def setUp(self):
        self.html = APP.read_text(encoding="utf-8")
        self.src = build_guide.SRC.read_text(encoding="utf-8")
        self.tree = parse(self.src)

    def test_built_from_source(self):
        self.assertEqual(build_guide.page(), self.html, "запусти python3 scripts/build_guide.py")

    def test_standards_mode_and_version(self):
        self.assertTrue(self.html.startswith("<!doctype html>"))
        self.assertNotIn("__VERSION__", self.html)
        self.assertIn(f"Aolithium Live {build_guide.version()}", self.html)

    def test_every_tile_has_section_and_links_resolve(self):
        ids = set(re.findall(r'id="([^"]+)"', self.html))
        tiles = re.findall(r'<a class="tile[^"]*" href="#([^"]+)"', self.html)
        self.assertGreaterEqual(len(tiles), 18)
        self.assertEqual(len(tiles), len(set(tiles)))
        for t in tiles:
            self.assertRegex(self.html, rf'<section id="{t}" class="page"')
        sections = re.findall(r'<section id="([^"]+)" class="page"', self.html)
        self.assertEqual(sorted(tiles), sorted(sections), "кожен розділ — плитка на головній")
        for target in re.findall(r'href="#([A-Za-z][\w-]*)"', self.html):
            self.assertIn(target, ids, target)

    def test_sections_opened_from_app_exist(self):
        kotlin = "\n".join(p.read_text(encoding="utf-8") for p in KOTLIN.rglob("*.kt"))
        opened = re.findall(r'openHelp\("([^"]+)"\)', kotlin)
        self.assertIn("home", opened)  # «Ще» → «Інструкція» відкриває зміст
        self.assertIn('<main id="home"', self.html)
        for section in opened:
            if section != "home":
                self.assertRegex(self.html, rf'<section id="{section}" class="page"')

    def test_demo_buttons_cover_every_scene(self):
        demo = (KOTLIN / "core/DemoFrames.kt").read_text(encoding="utf-8")
        body = demo[demo.index("enum class Scene"):demo.index(";", demo.index("enum class Scene"))]
        scenes = re.findall(r"^\s*([A-Z_]+),", body, re.M)
        self.assertGreaterEqual(len(scenes), 5)
        links = re.findall(r'href="app://demo/([A-Z_]+)"', self.html)
        self.assertEqual(sorted(scenes), sorted(links))

    def test_app_icons_match_guide(self):
        # Значки інструкції — ті самі шляхи, що в застосунку (ui/LineIcon.kt), як у Track Catcher (T58).
        kt = (KOTLIN / "ui/LineIcon.kt").read_text(encoding="utf-8")
        sprite = self.html[self.html.index('<svg width="0"'):self.html.index("</svg>", self.html.index('<svg width="0"'))]
        shared = {m.lower().replace("_", "-") for m in re.findall(r"^\s{4}([A-Z_]+)\(", kt, re.M)}
        checked = 0
        for sym in re.finditer(r'<symbol id="i-([^"]+)"[^>]*>(.*?)</symbol>', sprite, re.S):
            if sym.group(1) not in shared:
                continue
            for d in re.findall(r'<path d="([^"]+)"', sym.group(2)):
                self.assertIn(f'"{d}"', kt, f"i-{sym.group(1)}: {d}")
                checked += 1
        self.assertGreaterEqual(checked, 20)

    def test_images_exist_paired_and_offline(self):
        srcs = re.findall(r'src="([^"]+)"', self.html)
        self.assertGreaterEqual(len(srcs), 2 * len(guide_images.NAMES))
        for src in srcs:
            self.assertFalse(src.startswith("http"), src)
            self.assertTrue((APP.parent / src).is_file(), src)
        for name in guide_images.NAMES:
            for lang in guide_images.LANGS:
                self.assertIn(f'src="img/{lang}/{name}.webp"', self.html, f"{lang}/{name} не використано")
        self.assertNotIn("fonts.googleapis", self.html)
        self.assertNotIn("<script src=", self.html)
        self.assertNotIn("<link ", self.html)

    def test_images_reserve_space(self):
        # T36: місце на сторінці відновлюється після перезавантаження — картинки мусять займати місце ще до завантаження.
        for tag in re.findall(r"<img [^>]*>", self.html):
            self.assertRegex(tag, r'width="\d+" height="\d+"', tag)

    def test_app_hooks(self):
        # T35: у застосунку свої «UA / EN» угорі — на сторінці вони сховані, а мову ставить window.guideSetLang.
        self.assertRegex(self.html, r"html\.in-app \.langs\{display:none")
        self.assertIn("window.guideSetLang", self.html)
        kt = (KOTLIN / "ui/GuideScreen.kt").read_text(encoding="utf-8")
        self.assertIn("guideSetLang", kt)
        self.assertIn("fresh=1", kt)

    def test_languages_come_in_pairs(self):
        # Кожен текст українською (class="uk") — і одразу за ним той самий елемент англійською (class="en").
        problems = []
        for node in [self.tree.root, *self.tree.root.walk()]:
            kids = [c for c in node.children if isinstance(c, Node)]
            for i, k in enumerate(kids):
                if "uk" in k.classes:
                    nxt = kids[i + 1] if i + 1 < len(kids) else None
                    if nxt is None or "en" not in nxt.classes or nxt.tag != k.tag:
                        problems.append(f"<{k.tag} class=uk> без пари: {''.join(t for _, t in self._texts(k))[:60]}")
                if "en" in k.classes:
                    prev = kids[i - 1] if i > 0 else None
                    if prev is None or "uk" not in prev.classes:
                        problems.append(f"<{k.tag} class=en> без української перед ним")
        self.assertEqual([], problems)

    def _texts(self, node):
        return [(n, t) for n, t in self.tree.texts if n is node or self._inside(n, node)]

    @staticmethod
    def _inside(n, anc):
        while n is not None:
            if n is anc:
                return True
            n = n.parent
        return False

    def test_english_has_no_ukrainian_and_shared_text_is_neutral(self):
        bad = []
        for node, text in self.tree.texts:
            if node.lang() != "uk" and CYR.search(text):
                bad.append(f"{node.lang() or 'спільне'}: {text.strip()[:70]}")
        for node in self.tree.root.walk():
            if node.tag == "img" and node.lang() != "uk" and CYR.search(node.attrs.get("alt", "")):
                bad.append(f"alt: {node.attrs['alt']}")
        self.assertEqual([], bad)

    def test_both_languages_cover_every_section(self):
        for sec in re.findall(r'(<section id="[^"]+" class="page".*?</section>)', self.src, re.S):
            sid = re.search(r'id="([^"]+)"', sec).group(1)
            uk = len(re.findall(r'class="[^"]*\buk\b', sec))
            self.assertGreater(uk, 0, sid)
            self.assertEqual(uk, len(re.findall(r'class="[^"]*\ben\b', sec)), sid)


if __name__ == "__main__":
    unittest.main()
