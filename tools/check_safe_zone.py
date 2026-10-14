# Checks the bank and payment lists in SafeZoneWords.kt, the same way the app
# uses them. It fails when:
#   * the name of an ordinary app (browser, messenger, mail, maps and so on)
#     would be treated as a bank or money app, or
#   * a bank or money app name from the sample list would be missed.
# Run:  python3 tools/check_safe_zone.py
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/kotlin/dev/macbuildssys/consentguard/SafeZoneWords.kt")
text = open(SRC, encoding="utf-8").read()


def words(name):
    start = text.index("val " + name)
    end = text.index("\n    )", start)
    return re.findall(r'"([^"]+)"', text[start:end])


BROWSER = words("BROWSER_WORDS")
PKG = words("PACKAGE_WORDS")
MSG_PKG = words("MESSAGING_PACKAGES")
MSG_LABELS = words("MESSAGE_WORDS")
EXACT = words("PACKAGE_EXACT")
PARTS = words("BANK_PARTS")
MONEY = words("MONEY_WORDS")
MESSAGE = words("MESSAGE_WORDS")
BRANDS = words("BRANDS")


def fold(t):
    d = unicodedata.normalize("NFD", t.lower())
    out, last = [], True
    for ch in d:
        if unicodedata.category(ch) == "Mn":
            continue
        if ch.isalnum():
            out.append(ch)
            last = False
        elif not last:
            out.append(" ")
            last = True
    return "".join(out).strip()


def label_hit(label):
    f = fold(label)
    if not f or f == "mail com" or f.startswith("mail com ") or f.endswith(" mail com"):
        return False
    if any(p in f for p in PARTS):
        return True
    pad = " " + f + " "
    return any(" " + w + " " in pad for w in MONEY + MESSAGE + BRANDS)


def pkg_hit(pkg):
    low = pkg.lower()
    if any(b in low for b in BROWSER):
        return False
    if any(low == e or low.startswith(e + ".") for e in EXACT):
        return True
    return any(w in low for w in PKG)


def messaging_hit(pkg, label):
    f = fold(label)
    if f == "mail com" or f.startswith("mail com ") or f.endswith(" mail com") or "mail.com" in pkg.lower():
        return False
    low = pkg.lower()
    if any(low == e or low.startswith(e + ".") for e in MSG_PKG):
        return True
    pad = " " + f + " "
    return any(" " + w + " " in pad for w in MSG_LABELS)


ORDINARY_PACKAGES = """
com.android.chrome org.mozilla.firefox org.mozilla.fenix com.brave.browser com.opera.browser com.microsoft.emmx
com.sec.android.app.sbrowser com.duckduckgo.mobile.android 
com.google.android.youtube com.google.android.apps.maps com.android.vending 
com.facebook.katana com.instagram.android com.spotify.music com.netflix.mediaclient
com.android.settings com.android.systemui com.sec.android.app.launcher 
com.linkedin.android com.discord com.android.camera2 org.videolan.vlc org.wikipedia
""".split()

ORDINARY_LABELS = """
Chrome|Firefox|Brave|Opera|Samsung Internet|Tor Browser|Settings|Camera|Maps
YouTube|Photos|Clock|Calculator|Spotify|Files|Phone|Contacts|Calendar|Notes|LinkedIn|Instagram|Facebook
Netflix|Play Store|Weather|Wikipedia|VLC|Translate|Drive|Docs|Keep Notes|Reddit|Pinterest|Uber|Bolt|Amazon Shopping
Jumia|Booking.com|Airbnb|Duolingo|Kindle|Pocket|Podcasts|Radio|Compass|Torch|Recorder|Gallery|Music
""".replace("\n", "|").split("|")

SHOULD_MATCH = """
M-PESA|Gmail|GMX Mail|WhatsApp|WhatsApp Business|Signal|Telegram|Messages|Outlook|Thunderbird|K-9 Mail|Proton Mail|Messenger|My Airtel|NALA|LemFi|Wise|Sendwave|Taptap Send|Remitly|WorldRemit|Samsung Pay|Samsung Wallet|Google Wallet|PayPal
Cash App|Venmo|Revolut|Monzo|N26|Klarna|Equity Mobile|KCB Mobile|Absa Banking|Capitec Banking|FNB Banking|CRDB SimBanking
Kuda|OPay|PalmPay|Moniepoint|bKash|Paytm|PhonePe|BHIM|GPay|HDFC Bank|Telebirr|CBE Birr|Fawry|Alipay|WeChat Pay|GCash
Nubank|Itaú|PicPay|Mercado Pago|Sparkasse|Volksbank|ING Banking|DKB|Commerzbank|Chase|Wells Fargo|Citi Mobile|Bank of Kigali
Tigo Pesa|Mixx by Yas|MTN MoMo|Ecocash|Flutterwave|Paystack|Chipper Cash|Barclays|Lloyds|Halifax|Santander|BBVA|Bizum|Twint
银行|はやしの銀行|بنك مصر|Сбербанк|Kaspi.kz|Halyk|Vietcombank|Techcombank|ACLEDA|ABA Mobile|Maybank2u|Touch n Go eWallet
""".replace("\n", "|").split("|")

SHOULD_SKIP_MESSAGING = [
    ("com.whatsapp", "WhatsApp"), ("org.thoughtcrime.securesms", "Signal"), ("org.telegram.messenger", "Telegram"),
    ("com.google.android.gm", "Gmail"), ("com.microsoft.office.outlook", "Outlook"), ("x.y", "Proton Mail"),
    ("x.y", "GMX Mail"), ("x.y", "Yahoo Mail"), ("x.y", "Messages"), ("x.y", "Email"), ("x.y", "Samsung Email"),
    ("x.y", "Thunderbird"), ("x.y", "WhatsApp Business"), ("x.y", "Messenger"),
]
for pkg, lab in SHOULD_SKIP_MESSAGING:
    if not messaging_hit(pkg, lab):
        print("messaging app not skipped:", lab)
        sys.exit(1)
for pkg, lab in [("com.mail.android.x", "mail.com"), ("x.y", "Mail.com Mail")]:
    if messaging_hit(pkg, lab) or label_hit(lab):
        print("mail.com must not be skipped:", lab)
        sys.exit(1)

bad = 0
for pkg in ORDINARY_PACKAGES:
    if pkg_hit(pkg):
        bad += 1
        print("ordinary package would be skipped:", pkg)
for lab in ORDINARY_LABELS:
    if lab and (label_hit(lab) or messaging_hit("x.y", lab)):
        bad += 1
        print("ordinary app name would be skipped:", lab)
missed = [x for x in SHOULD_MATCH if x and not label_hit(x)]
for x in missed:
    print("not recognised:", x)
print(len(PKG), "package words,", len(EXACT), "package names,", len(PARTS), "bank parts,",
      len(MONEY), "money words,", len(BRANDS), "brands,", bad, "problem(s),", len(missed), "missed")
sys.exit(1 if (bad or missed) else 0)
