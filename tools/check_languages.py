# Checks the language packs together with the English lists, the way the app
# combines them. Fails (exit code 1) when:
#   * a pack file is missing a key or has a malformed phrase,
#   * a label that is meant to ACCEPT would also be matched by a reject,
#     necessary only, manage or confirm phrase of any language (so a tap on an
#     accept button could be mistaken for a choice), or
#   * a reject, necessary only, manage or confirm phrase is itself an accept
#     guard phrase of any language.
# Run:  python3 tools/check_languages.py
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets")
sys.path.insert(0, os.path.join(ROOT, "tools"))
from build_languages import KEYS, norm  # noqa: E402


class Patterns:
    def __init__(self, raw):
        self.subs, self.exacts = [], set()
        for p in raw:
            if p.startswith("="):
                b = norm(p[1:])
                if b:
                    self.exacts.add(b)
            else:
                b = norm(p)
                if b:
                    self.subs.append(b)
        self.padded = [" %s " % s for s in self.subs]

    def matches(self, text):
        t = norm(text)
        return bool(t) and (t in self.exacts or any(s in t for s in self.subs))

    def matches_words(self, text):
        t = norm(text)
        if not t:
            return False
        if t in self.exacts:
            return True
        pt = " %s " % t
        return any(p in pt for p in self.padded)


def main():
    rules = json.load(open(os.path.join(ASSETS, "consent_rules.json"), encoding="utf-8"))
    lex = json.load(open(os.path.join(ASSETS, "consent_lexicon.json"), encoding="utf-8"))
    packs = []
    lang_dir = os.path.join(ASSETS, "lang")
    for name in sorted(os.listdir(lang_dir)):
        if name.endswith(".json"):
            packs.append(json.load(open(os.path.join(lang_dir, name), encoding="utf-8")))

    errors = []
    for p in packs:
        for k in KEYS:
            if k not in p or not isinstance(p[k], list):
                errors.append("%s: missing list %s" % (p.get("language"), k))

    def gather(rule_key, pack_key):
        out = []
        for r in rules:
            out += r.get(rule_key, [])
        for p in packs:
            out += p.get(pack_key, [])
        return out

    choices = {
        "reject": Patterns(gather("rejectTextPatterns", "reject")),
        "necessary": Patterns(gather("necessaryTextPatterns", "necessary")),
        "manage": Patterns(gather("manageTextPatterns", "manage")),
        "confirm": Patterns(gather("confirmTextPatterns", "confirm")),
    }
    accept_raw = lex.get("acceptGuardTextPatterns", []) + [x for p in packs for x in p["acceptGuard"]]
    accept = Patterns(accept_raw)

    # 1. every accept label against every choice list
    for raw in accept_raw:
        label = raw[1:] if raw.startswith("=") else raw
        for kind, pats in choices.items():
            if pats.matches(label):
                errors.append("accept label %r is matched by a %s phrase" % (label, kind))

    # 2. every choice phrase read as a label against the accept guard
    for p in packs:
        for key in ("reject", "necessary", "manage", "confirm", "fallbackReject", "rejectButton"):
            for raw in p[key]:
                label = raw[1:] if raw.startswith("=") else raw
                if accept.matches_words(label):
                    errors.append("%s.%s phrase %r is an accept guard phrase" % (p["language"], key, label))

    for e in errors[:80]:
        print("PROBLEM:", e)
    print("%d problem(s), %d packs checked" % (len(errors), len(packs)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
