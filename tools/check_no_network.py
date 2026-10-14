# Fails when the app could reach the network. Checks the source manifest, and
# when given a built APK or a merged manifest, that one too.
#   python3 tools/check_no_network.py
#   python3 tools/check_no_network.py path/to/AndroidManifest.xml
# The built merged manifest is normally at
#   app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
# (the exact folder name depends on the Android Gradle plugin version).
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FORBIDDEN = ["INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "NFC", "BLUETOOTH"]


def check(path):
    text = open(path, encoding="utf-8").read()
    # Ignore XML comments, and lines marked tools:node="remove", which take the permission out.
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    problems = []
    for m in re.finditer(r"<uses-permission[^>]*>", text):
        tag = m.group(0)
        if 'tools:node="remove"' in tag:
            continue
        for name in FORBIDDEN:
            if "android.permission.%s" % name in tag:
                problems.append("%s asks for %s" % (path, name))
    return problems


# Code that could talk to the network, or hand data to another app to send.
SOURCE_WORDS = ["java.net.", "javax.net.", "okhttp", "HttpURLConnection", "HttpsURLConnection",
                "Socket(", "WebView(", "ACTION_VIEW", "ACTION_SEND", "ACTION_SENDTO", "DownloadManager",
                "Uri.parse(\"http", "retrofit", "volley"]


def check_sources():
    problems = []
    kt = os.path.join(ROOT, "app/src/main")
    for folder, _, files in os.walk(kt):
        for name in files:
            if not name.endswith((".kt", ".java")):
                continue
            path = os.path.join(folder, name)
            text = open(path, encoding="utf-8").read()
            text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
            for line in text.splitlines():
                code = line.split("//")[0]
                for word in SOURCE_WORDS:
                    if word in code:
                        problems.append("%s uses %s" % (path, word))
    return problems


def main():
    paths = sys.argv[1:] or [os.path.join(ROOT, "app/src/main/AndroidManifest.xml")]
    problems = []
    for p in paths:
        problems += check(p)
    problems += check_sources()
    for p in problems:
        print("PROBLEM:", p)
    print("no network permissions found" if not problems else "%d problem(s)" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
