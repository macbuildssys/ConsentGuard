package dev.macbuildssys.consentguard

import android.content.Context
import java.text.Normalizer

/* Decides whether an app is a bank, wallet or money transfer app. In those
   apps ConsentGuard does nothing, because a wrong tap there costs more than a
   cookie banner is worth.

   Package names cannot be guessed (Nala is money.nala.remit, Sendwave is
   com.mychime.waveremit.app), so the main check is the name the app shows on
   the home screen. Three kinds of words are used:

   BANK_PARTS     found anywhere in the name, in many languages ("bank",
                  "banque", "banco", "sparkasse", the word for bank in
                  Arabic, Chinese, Korean and more)
   MONEY_WORDS    found as a whole word ("pay", "wallet", "money")
   MESSAGE_WORDS  email and chat apps (mail, WhatsApp, Signal, Telegram)
   BRANDS         names of banks and money apps from around the world, found
                  as a whole word or phrase

   Package names are checked too, but only with general words and a short list
   of full names that were checked against the real app. Browsers are never
   skipped.

   To add one, put it in the right list and run
   python3 tools/check_safe_zone.py. Words are written in lowercase without
   accents. */
object SafeZoneWords {

    val BROWSER_WORDS = listOf(
        "chrome", "firefox", "fenix", "browser", "emmx", "duckduckgo", "vivaldi",
        "brave", "com.opera", "webview", "mozilla", "torproject"
    )

    val PACKAGE_WORDS: List<String> = listOf(
        ".pay.", ".wallet.", "banca", "banco", "bank", "banka", "banque", "brokerage", "fintech",
        "mobile.money", "mobilemoney", "payment", "payments", "raiffeisen", "remit", "sparkasse",
        "volksbank", "wallet"
    )

    val PACKAGE_EXACT: List<String> = listOf(
        "com.google.android.apps.walletnfcrel", "com.mychime.waveremit.app", "com.paypal.android",
        "com.revolut.revolut", "com.samsung.android.spay", "com.squareup.cash", "com.whatsapp", "org.thoughtcrime.securesms", "org.telegram.messenger", "com.google.android.gm", "com.microsoft.office.outlook", "com.google.android.apps.messaging",
        "com.transferwise.android", "com.venmo", "de.number26.android", "money.nala.remit"
    )

    val BANK_PARTS: List<String> = listOf(
        "banca", "banco", "bank", "banka", "bankas", "bankasi", "banki", "banque", "benki",
        "fintech", "microfinance", "neobank", "ngan hang", "pank", "raiffeisen", "sacco",
        "spaarbank", "sparekasse", "sparkasse", "volksbank", "банк", "בנק", "بانك", "بانک", "بنك",
        "बैंक", "ব্যাংক", "ธนาคาร", "ባንክ", "銀行", "银行", "은행"
    )

    val MONEY_WORDS: List<String> = listOf(
        "birr", "brokerage", "cash", "credit", "crypto", "finance", "forex", "invest", "loan",
        "loans", "momo", "money", "mpesa", "pay", "payment", "payments", "pesa", "remit",
        "remittance", "savings", "trading", "transfer", "wallet"
    )

    /* Email and chat apps, found as a whole word in the app name. Nothing
       there needs a consent banner dealt with, and a wrong tap could send or
       delete a message. */
    val MESSAGE_WORDS: List<String> = listOf(
        "mail", "email", "e mail", "gmail", "outlook", "thunderbird", "k 9", "proton mail", "protonmail",
        "tuta", "tutanota", "fastmail", "gmx", "web de", "yahoo mail", "aqua mail", "blue mail", "bluemail",
        "whatsapp", "signal", "telegram", "messenger", "messages", "sms", "mms", "chat", "threema", "session",
        "viber", "line", "wechat", "element", "matrix", "skype", "teams", "slack", "discord"
    )

    val BRANDS: List<String> = listOf(
        "aba", "abanca", "absa", "abyssinia", "acleda", "adcb", "adib", "aeon", "affin", "affirm",
        "africanbank", "afriex", "afterpay", "airtel", "airtel money", "al rajhi", "alat", "alfa",
        "alfalah", "alinma", "alior", "alipay", "ally", "alrajhi", "amazon pay", "ambank",
        "american express", "amex", "amole", "anz", "apple pay", "argenta", "askari",
        "attijariwafa", "awash", "axis", "azampesa", "azimo", "banca", "banca transilvania",
        "bancolombia", "bancoposta", "bandhan", "bank muscat", "bank99", "bankinter", "bankwest",
        "banque misr", "banrisul", "barclays", "barid", "bawag", "bbva", "bca", "bcp", "bcr",
        "bdo", "beem", "belfius", "bendigo", "bhim", "bidv", "bidvest", "binance", "birr", "bizum",
        "bkash", "blik", "bmce", "bmo", "bni", "bnp", "bnp paribas", "boubyan", "boursobank",
        "boursorama", "bper", "bpi", "brac", "bradesco", "brd", "bri", "brimo", "brubank", "bsi",
        "btg", "btg pactual", "bualuang", "bunq", "burgan", "bybit", "c6 bank", "caisse d epargne",
        "caixa", "caixabank", "canara", "capital one", "capitec", "cash app", "cbe", "cbe birr",
        "cbq", "chase", "chime", "chipper", "cibc", "cic", "cih", "cimb", "citi", "citibank",
        "coinbase", "coins ph", "comdirect", "commbank", "commerzbank", "consorsbank", "cowrywise",
        "crdb", "credit agricole", "credit mutuel", "crelan", "dahabshiil", "danamon", "danske",
        "dashen", "daviplata", "davivienda", "dbs", "desjardins", "deutsche", "dfcu", "dhofar",
        "dkb", "doha", "dtb", "dukhan", "dutch bangla", "easy paisa", "easybank", "easypaisa",
        "eazzy", "ecobank", "ecocash", "emirates nbd", "equitel", "equity", "erste", "esewa",
        "etisalat cash", "fairmoney", "fawry", "faysal", "fcmb", "fidelity", "fineco",
        "first direct", "fitbit pay", "flutterwave", "fnb", "freecharge", "galicia", "garmin pay",
        "gazprombank", "gcash", "globus", "google pay", "google wallet", "gopay", "gotyme", "gpay",
        "grab pay", "grabpay", "gtbank", "gtco", "gxs", "halifax", "halopesa", "halyk",
        "handelsbanken", "hbl", "hdbank", "hdfc", "hibret", "hong leong", "hormuud", "hsbc",
        "hype card", "hypovereinsbank", "ibercaja", "icbc", "icici", "idbi", "idfc", "imbank",
        "indusind", "ing", "innbucks", "instapay", "instarem", "inter co", "interac", "interbank",
        "intermex", "intesa", "investec", "itau", "jaiz", "japan post bank", "jazz cash",
        "jazzcash", "jusan", "k plus", "kakao pay", "kakaobank", "kakaopay", "kaspi", "kbank",
        "kbc", "kcb", "keytrade", "kfh", "khalti", "klarna", "kotak", "kraken", "krungsri",
        "krungthai", "kucoin", "kuda", "kutxabank", "landbank", "lcl", "lemfi", "lemon cash",
        "linkaja", "livin", "lloyds", "lydia", "m pesa", "macquarie", "mandiri", "maribank",
        "mashreq", "masraf", "mastercard", "maybank", "maybank2u", "mbank", "mbbank", "mcb",
        "mediobanca", "mediolanum", "meezan", "mercado pago", "metrobank", "mibanco", "millennium",
        "mixx", "mizuho", "mobikwik", "mobile money", "mobilepay", "momo", "monese", "moneygram",
        "moniepoint", "monobank", "monzo", "moov money", "mpesa", "mtn", "mtn mobile money",
        "mtn momo", "mufg", "mukuru", "muscat", "myinvestor", "mymtn", "n26", "nab", "nabil",
        "nagad", "nala", "naranja", "nationwide", "natwest", "naver pay", "nayapay", "nbd", "nbk",
        "ncba", "nedbank", "nequi", "neteller", "niaga", "nickel", "nisp", "nmb", "nordea",
        "nubank", "oberbank", "ocbc", "okx", "onemoney", "opay", "openbank", "orange money",
        "oschadbank", "pactual", "pagbank", "pagseguro", "palmpay", "parallex", "paribas",
        "paylib", "paymaya", "paynow", "payoneer", "paypal", "paypay", "paypay bank", "paysend",
        "paysera", "paystack", "paytm", "pekao", "permata", "pesa", "phonepe", "picpay",
        "piggyvest", "pingan", "pko", "plin", "pnb", "pnc", "polaris", "posb", "postbank",
        "postepay", "postfinance", "privatbank", "promptpay", "providus", "public bank", "qib",
        "qnb", "qonto", "rabobank", "raiffeisen", "raiffeisenbank", "rajhi", "rakbank", "rakuten",
        "razorpay", "rbc", "rbl", "remitly", "resona", "revolut", "rhb", "risevest", "riyad",
        "robinhood", "rosbank", "sabadell", "sacombank", "sadapay", "safaricom", "samsung pay",
        "samsung wallet", "sanpaolo", "santander", "satispay", "sber online", "sberbank",
        "sberbank online", "sbi", "scb", "scb easy", "schwab", "scotiabank", "seabank", "seb",
        "selcom", "sendwave", "sezzle", "shinhan", "shopeepay", "sicoob", "sicredi", "sidian",
        "simplii", "skrill", "smbc", "societe generale", "sofi", "sonali", "sovcombank",
        "st george", "stanbic", "starling", "stc pay", "stripe", "suncorp", "swedbank", "swish",
        "swissquote", "t bank", "tabby", "tamara", "taptap", "taptap send", "targobank", "td",
        "techcombank", "telebirr", "tigo", "tigo pesa", "tikkie", "tinkoff", "tnex", "tonik",
        "touch n go", "tpbank", "transferwise", "truemoney", "truist", "trust bank", "ttb",
        "twint", "tymebank", "uala", "uba", "ubl", "ubs", "unicaja", "unicredit", "union bank",
        "unionbank", "unionpay", "uob", "upay", "urpay", "us bancorp", "varo", "venmo",
        "vietcombank", "vietinbank", "viettel", "viettel money", "vipps", "virgin money",
        "vodafone cash", "vontobel", "vpbank", "vtb", "warba", "wealthsimple", "wechat",
        "wechat pay", "wegagen", "wells fargo", "wema", "western union", "westpac", "wirex",
        "wise", "woori", "worldfirst", "worldremit", "xoom", "yape", "yes bank", "yono", "yucho",
        "zalopay", "zelle", "zemen", "zenith", "zkb"
    )

    /* Lowercase, accents removed, anything that is not a letter or digit
       turned into a space. "M-PESA" becomes "m pesa". */
    fun fold(text: String): String {
        val d = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        var lastSpace = true
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    fun matchesPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (BROWSER_WORDS.any { lower.contains(it) }) return false
        if (PACKAGE_EXACT.any { lower == it || lower.startsWith("$it.") }) return true
        return PACKAGE_WORDS.any { lower.contains(it) }
    }

    fun matchesLabel(label: String): Boolean {
        val f = fold(label)
        if (isMailDotCom("", label)) return false
        if (f.isEmpty()) return false
        if (BANK_PARTS.any { f.contains(it) }) return true
        val padded = " $f "
        if (MONEY_WORDS.any { padded.contains(" $it ") }) return true
        if (MESSAGE_WORDS.any { padded.contains(" $it ") }) return true
        return BRANDS.any { padded.contains(" $it ") }
    }

    /* Email and chat apps are left alone too. The one exception is mail.com,
       whose pages and app do show consent banners. */
    val MESSAGING_PACKAGES: List<String> = listOf(
        "com.whatsapp", "org.thoughtcrime.securesms", "org.telegram", "com.google.android.gm",
        "com.microsoft.office.outlook", "com.fsck.k9", "net.thunderbird.android", "ch.protonmail",
        "com.yahoo.mobile.client.android.mail", "com.samsung.android.email", "com.android.email",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca",
        "com.tutanota", "de.tutao.tutanota", "com.fastmail", "org.signal", "im.vector.app", "com.viber.voip"
    )

    private fun isMailDotCom(packageName: String, label: String): Boolean {
        val f = fold(label)
        return f == "mail com" || f.startsWith("mail com ") || f.endsWith(" mail com") ||
            packageName.lowercase().contains("mail.com")
    }

    fun matchesMessaging(packageName: String, label: String): Boolean {
        if (isMailDotCom(packageName, label)) return false
        val low = packageName.lowercase()
        if (MESSAGING_PACKAGES.any { low == it || low.startsWith("$it.") }) return true
        val padded = " " + fold(label) + " "
        return MESSAGE_WORDS.any { padded.contains(" $it ") }
    }

    private val cache = HashMap<String, Boolean>()

    @Synchronized
    fun isSafeZone(context: Context, packageName: String): Boolean {
        cache[packageName]?.let { return it }
        var safe = matchesPackage(packageName)
        if (!safe && BROWSER_WORDS.none { packageName.lowercase().contains(it) }) {
            val label = try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) {
                ""
            }
            safe = matchesLabel(label)
            if (!safe) safe = matchesMessaging(packageName, label)
        }
        cache[packageName] = safe
        return safe
    }
}
