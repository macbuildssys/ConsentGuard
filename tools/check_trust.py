"""Fails if the promises made in docs/PRIVACY.md stop being true in the source.

Checks that the safe zone check comes before any screen is read, that logging
is limited to debug builds, and that the manifest has no network permission.
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
kt = root / "app/src/main/kotlin/dev/macbuildssys/consentguard"
service = (kt / "ConsentBlockerService.kt").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
problems = []

# 1. The safe zone check is before any reading of the screen in scanAndActInner.
m = re.search(r"private fun scanAndActInner\(.*?\n    \}\n", service, re.S)
body = m.group(0) if m else ""
safe = body.find("isSafeZone(packageName)")
reads = [body.find(w) for w in ("judgeConsentScreen(", "getChild(", "findAccessibilityNodeInfosByText(", ".text")]
reads = [r for r in reads if r >= 0]
if safe < 0:
    problems.append("scanAndActInner does not call isSafeZone")
elif reads and min(reads) < safe:
    problems.append("the screen is read before the safe zone check")

# 2. Log calls only inside debugLog.
for i, line in enumerate(service.splitlines(), 1):
    if re.search(r"\bLog\.[dviwe]\(", line):
        ctx = "\n".join(service.splitlines()[max(0, i - 6):i])
        if "BuildConfig.DEBUG" not in ctx and "fun debugLog" not in ctx:
            problems.append(f"log call outside debugLog at line {i}")

# 3. No network permission.
if re.search(r'uses-permission[^>]*INTERNET', manifest) and 'tools:node="remove"' not in manifest:
    problems.append("INTERNET permission present")

print(len(problems), "problems")
for p in problems:
    print(" ", p)
sys.exit(1 if problems else 0)
