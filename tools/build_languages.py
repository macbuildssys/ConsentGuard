# Builds one JSON file per language in app/src/main/assets/lang from the word
# lists in tools/langdata/<code>.py. Each data file defines PACK, a dict with
# the keys listed in KEYS, and CONJ, the words that mean "and" in that
# language. Phrases are written plainly, with accents and capitals if you
# like. This script folds them exactly the way ConsentText.normalize does in
# the app, removes duplicates, and also writes a variant with "&" in place of
# the word for "and", because the app turns "&" into the English word "and"
# before it compares anything.
#
# Phrases starting with "=" must equal the whole label. Anything else matches
# when the label contains it.
#
# Run:  python3 tools/build_languages.py
import importlib.util
import json
import os
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/lang")
DATA = os.path.join(ROOT, "tools/langdata")

KEYS = [
    "reject", "necessary", "manage", "confirm", "fallbackReject",
    "rejectButton", "expandSection",
    "anchor", "bareAnchor", "strongAnchor", "neverClick", "acceptGuard",
    "acceptWords", "switchEssential", "switchNonEssential", "switchKeepOn",
    "adsPurpose", "statsPurpose", "topLevelWords", "cookieContext",
    "sensitiveScreen",
]

FOLD = {0x0671: "ا", 0x0649: "ي", 0x0199: "k", 0x0257: "d", 0x0253: "b", 0x01B4: "y"}
APOS = set("'’‘`ʼ")


def norm(text):
    # Must match ConsentText.normalize in ConsentRules.kt exactly.
    if any(ord(c) > 0x7F for c in text):
        d = unicodedata.normalize("NFD", text)
        out = []
        for c in d:
            o = ord(c)
            if 0x0300 <= o <= 0x036F or 0x064B <= o <= 0x065F or o == 0x0640:
                continue
            out.append(FOLD.get(o, c))
        text = unicodedata.normalize("NFC", "".join(out))
    res = []
    last_space = True
    for ch in text:
        if ch in APOS:
            continue
        if ch == "&":
            if not last_space:
                res.append(" ")
            res.append("and ")
            last_space = True
        elif ch.isalnum():
            res.append(ch.lower())
            last_space = False
        else:
            if not last_space:
                res.append(" ")
                last_space = True
    return "".join(res).strip()


def variants(phrase, conj):
    # The phrase itself, plus a form with "&" instead of each "and" word.
    out = [phrase]
    body = phrase[1:] if phrase.startswith("=") else phrase
    prefix = "=" if phrase.startswith("=") else ""
    words = body.split(" ")
    for c in conj:
        if c in words:
            out.append(prefix + " ".join("&" if w == c else w for w in words))
    return out


def clean(items, conj, keep_star=False):
    seen, out = set(), []
    for raw in items:
        for v in variants(raw.strip(), conj):
            star = keep_star and v.endswith("*")
            exact = v.startswith("=")
            body = norm(v[1:] if exact else v.rstrip("*") if star else v)
            if not body:
                continue
            key = ("=" if exact else "") + body + ("*" if star else "")
            if key not in seen:
                seen.add(key)
                out.append(key)
    return out


def load(path):
    spec = importlib.util.spec_from_file_location("langdata", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name in sorted(os.listdir(DATA)):
        if not name.endswith(".py") or name.startswith("_"):
            continue
        mod = load(os.path.join(DATA, name))
        conj = [norm(c) for c in getattr(mod, "CONJ", [])]
        meta = mod.META
        pack = {
            "language": meta["code"],
            "name": meta["name"],
            "status": meta["status"],
            "enabled": meta.get("enabled", True),
        }
        for key in KEYS:
            items = mod.PACK.get(key, [])
            pack[key] = clean(items, conj, keep_star=(key == "acceptWords"))
        path = os.path.join(OUT, meta["code"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(pack, f, ensure_ascii=False, indent=1)
            f.write("\n")
        n = sum(len(pack[k]) for k in KEYS)
        total += n
        print("%-3s %-12s %-9s %5d phrases" % (meta["code"], meta["name"], meta["status"], n))
    print("total", total)


if __name__ == "__main__":
    sys.exit(main())
