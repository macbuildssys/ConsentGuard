# Builds consent_rules.json and consent_lexicon.json, English only.
# The four original rules are kept exactly as they are in consent_rules.json.
# Everything new is generated from word lists so the coverage is easy to extend:
# add a word to a list below, run the script, and every phrase built from it appears.
import json
import os
import re
import subprocess

# This file lives in <repo>/tools/, so the repo root is one folder up.
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RULES_PATH = REPO + "/app/src/main/assets/consent_rules.json"
LEXICON_PATH = REPO + "/app/src/main/assets/consent_lexicon.json"


def norm(text):
    # Must match ConsentText.normalize in ConsentRules.kt exactly.
    text = text.lower().replace("&", " and ")
    for ch in ("'", "\u2019", "\u2018", "`"):
        text = text.replace(ch, "")
    return re.sub(r"[\W_]+", " ", text).strip()


def L(block):
    return [p.strip() for p in block.replace("\n", "|").split("|") if p.strip()]


def prune(patterns):
    # Normalizes, removes duplicates, and drops any substring pattern that a shorter
    # substring pattern already covers (same runtime behaviour, fewer comparisons).
    # Exact patterns start with "=" and are dropped only if a substring pattern covers them.
    subs, exacts = [], []
    for raw in patterns:
        if raw.startswith("="):
            exacts.append("=" + norm(raw[1:]))
        else:
            subs.append(norm(raw))
    subs = sorted({s for s in subs if s}, key=lambda s: (len(s), s))
    kept = []
    for s in subs:
        if not any(k in s for k in kept):
            kept.append(s)
    out_exact = []
    seen = set()
    for e in exacts:
        body = e[1:]
        if not body or e in seen:
            continue
        if any(k in body for k in kept):
            continue
        seen.add(e)
        out_exact.append(e)
    return kept + sorted(out_exact)


def combine(leads, tails, joiner=" "):
    return [(a + joiner + b).strip() for a in leads for b in tails]


# Reject wording

REJECT_VERBS = L("""reject|decline|refuse|deny|disallow|disagree with|dont accept|do not accept|dont allow|do not allow|
dont consent|do not consent|i reject|i decline|i refuse|i deny|i disagree with|i do not accept|i dont accept""")

REJECT_LIGHT_VERBS = L("""disable|block|turn off|switch off|opt out of|say no to|no to|object to""")

REJECT_LIGHT_OBJECTS = L("""all|all cookies|everything|non essential|non essential cookies|optional|optional cookies|
additional cookies|unnecessary cookies|tracking|tracking cookies|trackers|marketing|marketing cookies|
analytics|analytics cookies|advertising|advertising cookies|targeting|personalised ads|personalized ads|
third party cookies|non essential trackers|all tracking|all trackers|all marketing|all analytics|
all advertising|all optional|all non essential|all third party cookies""")

REJECT_ALL_OBJECTS = L("""all|everything|all cookies|all optional|all optional cookies|all non essential|
all non essential cookies|all non necessary|all non necessary cookies|all additional|all additional cookies|
all unnecessary|all unnecessary cookies|all tracking|all tracking cookies|all trackers|all third party|
all third party cookies|all marketing|all marketing cookies|all analytics|all advertising|all targeting|
all purposes|all vendors|all partners|all categories|all consent|all consents|all of them|all others|
all non functional|non essential|non essential cookies|non essential trackers|non necessary|
non necessary cookies|non required|non required cookies|nonessential|optional|optional cookies|
optional trackers|additional|additional cookies|unnecessary|unnecessary cookies|unessential|extra cookies|
tracking|tracking cookies|tracking technologies|trackers|marketing|marketing cookies|targeting|
targeting cookies|advertising|advertising cookies|ad cookies|analytics|analytics cookies|
personalised ads|personalized ads|personalisation|personalization|third party cookies|cookies|cookie|
the use of cookies|data collection|data sharing|data processing|consent|non functional|statistics cookies|
preference cookies|social media cookies""")

REJECT_TAILS = L("and close|and continue|and exit|and save|and leave|and proceed|and go on|and browse|and read")

REJECT_INTRANSITIVE = L("""reject|decline|refuse|deny|disagree""")

WITHDRAW_VERBS = L("withdraw|revoke|withdraw my|revoke my|withdraw your|withdraw all|revoke all|cancel my|cancel")
WITHDRAW_OBJECTS = L("consent|consents|all consent|all consents|permission|cookie consent|cookies consent")

CONTINUE_WITHOUT = L("""without accepting|without agreeing|without consenting|without consent|without tracking|
without cookies|without allowing|without personalisation|without personalization|without personalised ads|
without personalized ads|without giving consent|without accepting cookies|without agreeing to cookies|
without any cookies|without optional cookies|without marketing cookies|without third party cookies|
without accepting anything|without accepting all|without allowing cookies|without allowing tracking""")

REJECT_HAND = L("""i disagree|i do not agree|i dont agree|no i do not accept|not agree|do not agree|dont agree|
disagree and close|disagree and continue|disagree to all|disagree with all|disagree with everything|
do not sell|do not share|dont sell my|dont share my|opt out of sale|opt out of the sale|opt out of sharing|
opt out of targeted advertising|opt out of advertising|opt out of all|opt out of non essential|
opt out of marketing|opt out of analytics|opt out of tracking|limit the use of my sensitive|
object all|object to all|objecting to all|object to everything|legitimate interest object all|
no thanks|no thank you|no i decline|no i refuse|no i reject|
turn off all|turn off non essential|turn off all cookies|turn off tracking|switch off all|
disable all|disable all cookies|disable non essential|disable tracking|disable analytics|
withdraw consent|i withdraw consent|do not consent|i do not consent|dont consent|i dont consent|
say no to all|say no to cookies|say no to tracking|i say no|no i do not|no i dont|
dont enable|do not enable|dont turn on|do not turn on""")

REJECT_EXACT = L("""=reject|=reject all|=decline|=decline all|=refuse|=refuse all|=deny|=deny all|=disagree|
=i disagree|=i decline|=i reject|=i refuse|=no thanks|=no thank you|=opt out|=optout|=opt out all|
=block all|=disable all|=turn off all|=dont accept|=do not accept|=dont allow|=do not allow|
=dont agree|=do not agree|=dont consent|=do not consent|
=no i dont accept|=no i do not accept""")

# Necessary only wording
# Substring forms must contain an "only" style word, because a phrase like
# "allow essential and optional cookies" (a real accept everything label) would
# otherwise match "allow essential". Forms without "only" are exact match forms.

NECESSARY_STEMS = L("""necessary|essential|essentials|required|functional|mandatory|technical|
strictly necessary|strictly required|strictly essential|necessary and functional|essential and functional|
required and functional|necessary and essential|necessary and required|functional and necessary""")

# Extra stems that only make sense in exact match forms.
NECESSARY_EXACT_STEMS = L("""necessary|essential|required|functional|mandatory|basic|minimal|technical|strictly necessary""")

NECESSARY_NOUNS = ["", " cookies", " cookie"]

NECESSARY_LEADS = L("""accept|allow|use|save|keep|enable|continue with|proceed with|agree to|permit|select|choose|confirm|
approve|go with|stay with|stick to|browse with|store|i accept|i agree to|i allow|i want|submit""")

NECESSARY_HAND = L("""only the necessary|only the essential|only the essentials|only the required|only the mandatory|
only the basic|only the minimum|only the bare minimum|only what is necessary|only whats necessary|
only what is essential|only whats essential|only what is required|only whats required|
only what you need|only what we need|only what is needed|only whats needed|only those needed|
only those required|only those necessary|only the ones needed|only the ones required|
only the ones necessary|just the basics|just the minimum|just what is necessary|just whats necessary|
just what is essential|just whats essential|bare minimum|the bare minimum|minimum cookies|minimal cookies|
minimum required|minimum necessary|minimum essential|basic cookies only|necessary cookies alone|
essential cookies alone|accept only what|allow only what|use only what|save only what|
reject optional and continue|decline optional and continue""")

NECESSARY_EXACT_EXTRA = L("""=necessary only|=essential only|=required only|=functional only|=only necessary|=only essential|
=only required|=necessary cookies|=essential cookies|=required cookies|=functional cookies|
=necessary cookies only|=essential cookies only|=required cookies only|=strictly necessary cookies|
=strictly necessary|=strictly necessary only|=continue with necessary|=continue with essential|
=continue with required|=continue with necessary cookies|=continue with essential cookies|
=proceed with necessary|=proceed with essential|=use necessary cookies|=use essential cookies|
=use required cookies|=use only necessary cookies|=use necessary cookies only|=accept necessary|
=accept essential|=accept required|=accept necessary cookies|=accept essential cookies|
=accept required cookies|=accept functional cookies|=accept functional|=allow necessary|=allow essential|
=allow required|=allow necessary cookies|=allow essential cookies|=allow required cookies|
=allow functional cookies|=allow functional|=save necessary|=save essential|=necessary and functional|
=essential and functional|=only functional|=functional cookies only""")

# Manage and preferences wording

# Phrases that are specific enough to match on their own, with no verb needed.
MANAGE_BARE = L("""cookie settings|cookie setting|cookies settings|cookie preferences|cookie preference|cookies preferences|
cookie options|cookies options|cookie choices|cookies choices|cookie controls|cookie management|cookie manager|
cookie categories|cookie details|cookies details|cookie selection|cookie configuration|
privacy settings|privacy preferences|privacy options|privacy choices|privacy controls|privacy center|
privacy centre|privacy manager|privacy dashboard|data privacy settings|data privacy preferences|
data privacy choices|data preferences|data settings|data choices|consent settings|consent preferences|
consent options|consent choices|consent management|consent categories|consent details|
tracking preferences|tracking settings|tracking options|tracking choices|tracking controls|
ad preferences|ad settings|ad choices|ads settings|ads preferences|advertising preferences|
advertising settings|advertising choices|marketing preferences|marketing settings|marketing choices|
personalisation settings|personalization settings|personalisation choices|personalization choices|
storage preferences|storage settings|storage options|preference center|preferences center|
preference centre|preferences centre|list of partners|list of vendors|partner list|vendor list""")

# Words that are too general to match alone, so they need one of these verbs in front.
MANAGE_VERBS = L("""manage|customize|customise|personalize|personalise|adjust|configure|review|choose|select|control|change|edit|
set|update|manage your|manage my|customize your|customize my|customise your|customise my|
change your|change my|adjust your|adjust my|edit your|edit my|review your|review my|
choose your|choose my|set your|set my|update your|update my|control your|control my""")

MANAGE_OBJECTS = L("""cookies|cookie|privacy|consent|consents|tracking|purposes|partners|vendors|third parties|
our partners|our vendors|preferences|settings|options|choices|details|cookie consent|
data|ads|ad options|advertising|marketing|personalisation|personalization""")

MANAGE_SHOW_VERBS = L("show|view|see|display|open|expand")
MANAGE_SHOW_OBJECTS = L("""purposes|all purposes|partners|all partners|vendors|all vendors|details|more details|full details|
all details|our partners|our vendors|third parties|cookie details|cookies|cookie list|list of cookies|
purposes and partners|purposes and vendors|vendor details|partner details|purpose details|
special purposes|special features|features|options|preferences|choices|more options|more choices""")

MANAGE_HAND = L("""more options|more choices|more settings|more preferences|more controls|other options|other choices|
options and settings|choices and settings|settings and options|more information and settings|
more info and settings|learn more and customize|learn more and customise|learn more and manage|
learn more and set|learn more and choose|read more and customize|read more and customise|
read more and manage|more and customize|more and customise|customize choices|customise choices|
customize preferences|customise preferences|customize settings|customise settings|customize options|
customise options|customize cookies|customise cookies|customize consent|customise consent|
let me choose|let me decide|let me customize|let me customise|let me pick|let me select|
let me manage|let me set|let me control|let me adjust|let me configure|let me see|
i want to choose|i want to customize|i want to customise|i want to decide|i want to manage|
i want to set|i want to control|i want to adjust|choose for myself|choose for yourself|
choose what to share|choose what i share|choose what you share|choose my cookies|
choose which cookies|choose your cookies|choose cookies|choose categories|
select which cookies|select cookies|select categories|
individual settings|individual privacy settings|individual preferences|individual choices|
individual cookie settings|individual cookie preferences|individual consent|
personal settings|personal preferences|personal choices|advanced settings|advanced options|
advanced preferences|advanced cookie settings|advanced cookie options|advanced privacy settings|
advanced controls|advanced configuration|advanced choices|advanced consent|advanced consent settings|
detailed settings|detailed options|detailed preferences|detailed view|detailed information|
detailed cookie settings|granular settings|granular controls|granular choices|granular consent|
fine tune|fine tuning|tailor your choices|tailor your preferences|tailor your experience|
opt out options|opt out settings|opt out preferences|opt out choices|opt out controls|
your choices|your privacy choices|your privacy options|your privacy settings|your privacy controls|
your cookie choices|your cookie options|your cookie settings|your cookie preferences|
your consent choices|your consent options|your consent settings|your consent preferences|
your data choices|your data options|your data settings|your data preferences|
your ad choices|your ad options|your ad settings|your ad preferences|
your advertising choices|your advertising options|your advertising settings|your advertising preferences|
your tracking choices|your tracking options|your tracking settings|your tracking preferences|
my choices|my privacy choices|my cookie choices|my cookie settings|my cookie preferences|
my consent choices|my consent settings|my consent preferences|my ad choices|my ad settings|
my data choices|my data settings|my privacy settings|my privacy preferences|
view options|view preferences|view choices|see options|see preferences|see choices|
show options|show preferences|show choices|show more options|show more choices|
open preferences|open choices|go to preferences|go to choices|
cookies and privacy settings|cookies and privacy preferences|privacy and cookie settings|
privacy and cookie preferences|privacy and consent settings|privacy and consent preferences|
privacy and data settings|privacy and data preferences|privacy and ad settings|
cookie and privacy settings|cookie and privacy preferences|cookie and consent settings|
cookie and consent preferences|cookie and tracking settings|cookie and tracking preferences|
consent and privacy settings|consent and privacy preferences|consent and cookie settings|
consent and cookie preferences|consent and tracking settings|consent and tracking preferences|
preference center|preferences center|preference centre|preferences centre|
cookie preference center|cookie preferences center|privacy preference center|
privacy preferences center|consent preference center|consent preferences center|
cookie management center|privacy management center|consent management center|
cookie control|cookie controls|privacy control|privacy controls|consent control|consent controls|
tracking control|tracking controls|ad control|ad controls|data control|data controls|
cookie consent options|cookie consent settings|cookie consent preferences|cookie consent choices|
cookie consent details|cookie consent manager|cookie consent management|
cookies consent options|cookies consent settings|cookies consent preferences|
cookies consent choices|cookies consent details|cookies consent manager""")

MANAGE_EXACT = L("""=settings|=preferences|=options|=customize|=customise|=customize settings|=customise settings|=choices|
=manage|=manage options|=manage choices|=manage settings|=manage preferences|=manage cookies|
=details|=configure|=show details|=view details|=more options|=more choices|=personalize|
=personalise|=set preferences|=my choices|=my preferences|=your choices|=your preferences|
=advanced settings|=advanced options|=cookie settings|=cookie preferences|=cookies settings|
=cookie options|=cookie choices|=privacy settings|=privacy preferences|=privacy options|
=privacy choices|=adjust settings|=adjust preferences|=edit settings|=edit preferences|
=review options|=review settings|=review preferences|=let me choose|
=customize choices|=customise choices|=customize preferences|=customise preferences|
=show purposes|=show vendors|=show partners|=view partners|=view vendors|
=view purposes|=see purposes|=see vendors|=see partners|=learn more and customize|
=learn more and customise|=manage consent|=consent settings|=consent preferences|
=consent options|=consent choices|=manage my cookies|=manage my preferences|=manage my choices|
=manage my consent|=manage my settings|=manage privacy|=manage privacy settings|
=manage privacy preferences|=manage tracking|=manage ads|=manage ad preferences|
=ad preferences|=ad settings|=ad choices|=ads settings|=tracking preferences|
=tracking settings|=tracking options|=privacy center|=privacy centre|=preference center|
=preference centre|=preferences center|=preferences centre|=privacy manager|=cookie manager""")

# Confirm and save wording

CONFIRM_VERBS = L("save|confirm|apply|submit|update")
CONFIRM_OBJECTS = L("""preferences|my preferences|your preferences|settings|my settings|your settings|choices|my choices|
your choices|selection|my selection|your selection|selected|my selected|changes|my changes|
your changes|cookie settings|cookie preferences|cookie choices|cookies settings|cookies preferences|
consent|my consent|consent settings|consent preferences|consent choices|privacy settings|
privacy preferences|privacy choices|configuration|my configuration|these settings|these choices|
these preferences|this selection|current settings|current choices|current selection|
and close|and exit|and continue|and proceed|and leave|and apply|and confirm|and done|and finish|
and go|and return|and back|and browse|and read""")

CONFIRM_SELECT_LEADS = L("""accept|allow|agree to|use|continue with|proceed with|go with|permit|approve|enable|apply|choose""")
CONFIRM_SELECT_OBJECTS = L("""selection|selected|my selection|my choices|my settings|my preferences|chosen|the selection|
these settings|these choices|these preferences|selected cookies|selected options|
selected categories|current selection|current settings|current choices|chosen cookies|
chosen options|chosen categories|my selected|your selection|your choices|your settings""")

CONFIRM_HAND = L("""save and accept|save and apply|save and confirm|save and finish|save and go|save and return|
save and back|save and browse|save and read|close and save|done and save|apply and close|
apply and save|apply and continue|apply and exit|confirm and save|confirm and apply|
confirm and close|confirm and continue|confirm and exit|submit and close|submit and continue|
submit and exit|update and close|update and continue|update and exit|
confirm my choices|confirm my selection|confirm my preferences|confirm my settings|
confirm consent choices|confirm cookie choices|confirm privacy choices|confirm privacy settings|
accept my choices|accept my selection|accept my preferences|accept my settings|
accept these settings|accept these choices|accept these preferences|accept selected|accept selection|
allow selected|allow selection|allow my selection|allow my choices|allow chosen|allow the selection|
save selected|save selection|save my selection|save my choices|save my preferences|save my settings|
save my consent|save your choices|save your preferences|save your settings|save these settings|
save these choices|save this selection|save cookie settings|save cookie preferences|
save cookie choices|save consent settings|save privacy settings|save privacy preferences|
save privacy choices|save changes|save configuration|save consent""")

CONFIRM_EXACT = L("""=save|=confirm|=apply|=done|=submit|=update|=finish|=finished|=complete|=save settings|
=save preferences|=save choices|=save selection|=save and close|=save and exit|=save and continue|
=confirm choices|=confirm selection|=confirm preferences|=confirm settings|=confirm my choices|
=apply settings|=apply changes|=submit preferences|=submit choices|=save my preferences|
=save my choices|=save my settings|=save changes|=update preferences|=update settings|
=update choices|=update consent|=allow selection|=allow selected|=accept selection|
=accept selected|=confirm and close|=confirm and continue|=confirm and exit|=save exit|
=save close|=save continue""")

# Fallback wording. Runs last, only when nothing else on the screen matched.
# The single words are matched as substrings of short labels only (see FALLBACK_MAX_LENGTH in
# the service), and the service skips any label that also says accept, agree or allow.
FALLBACK_SUBSTRING = L("reject|decline|refuse|disagree|deny")
FALLBACK_EXACT = L("""=reject|=reject all|=decline|=decline all|=refuse|=refuse all|=deny|=deny all|=disagree|
=i disagree|=no|=no thanks|=no thank you|=opt out|=dont accept|=do not accept|=dont allow|
=do not allow|=nope""")

# Provider wording, copied from what these tools commonly show on their banners.
# These are typical labels, not verified against every live site. Kept in one place so
# a label seen in the field can be added here in one line.
PROVIDER_REJECT = L("""reject all cookies|reject all|deny all|decline all|refuse all|disagree and close|
refuse non essential cookies|decline optional cookies|reject non essential|reject optional|
reject additional cookies|i do not accept|i do not consent|do not consent|continue without accepting|
continue without agreeing|object all|object to all|opt out of all|decline non essential|
reject all non essential cookies|reject all optional cookies|reject all additional cookies|
reject unnecessary cookies|refuse optional cookies|deny non essential cookies|
deny optional cookies|deny additional cookies|do not allow cookies|do not allow all""")

PROVIDER_MANAGE = L("""manage cookies|manage preferences|manage options|manage choices|manage consent|manage settings|
cookies settings|cookie settings|cookie preferences|cookie options|privacy settings|privacy preferences|
privacy choices|your privacy choices|more options|show purposes|manage my cookies|
learn more and customize|learn more and customise|individual privacy settings|let me choose|
choose cookies|set preferences|advanced settings|view preferences|manage services|manage vendors|
manage partners|show details|show vendors|options and settings|settings and options|
your ad choices|manage ad preferences|manage ad choices""")

PROVIDER_CONFIRM = L("""confirm my choices|confirm choices|save and exit|save and close|save preferences|save my preferences|
save settings|save my settings|save selection|save my selection|allow selection|allow selected|
accept selection|accept selected|save and accept|save choices|save my choices|apply settings|
apply choices|apply preferences|submit preferences|submit my preferences|confirm selection|
confirm my selection|update preferences|update my preferences|update consent|save changes|
save and continue|confirm and continue|confirm and close""")


def build_reject():
    out = []
    out += combine(REJECT_VERBS, REJECT_ALL_OBJECTS)
    out += combine(REJECT_LIGHT_VERBS, REJECT_LIGHT_OBJECTS)
    out += combine(REJECT_INTRANSITIVE, REJECT_TAILS)
    out += combine(WITHDRAW_VERBS, WITHDRAW_OBJECTS)
    out += CONTINUE_WITHOUT
    out += REJECT_HAND
    out += REJECT_EXACT
    out += PROVIDER_REJECT
    return out


def build_necessary():
    out = []
    stems = [s + n for s in NECESSARY_STEMS for n in NECESSARY_NOUNS]
    # Substring forms always carry an only style word.
    out += combine(["only", "just", "only the", "just the", "only what is", "only whats"], stems)
    out += [s + " only" for s in stems]
    out += combine([lead + " only" for lead in NECESSARY_LEADS], stems)
    out += combine(["only " + lead for lead in NECESSARY_LEADS], stems)
    out += NECESSARY_HAND
    # Forms without an only word are exact match forms.
    exact_stems = [s + n for s in NECESSARY_EXACT_STEMS for n in NECESSARY_NOUNS]
    exact_leads = L("accept|allow|use|save|keep|enable|continue with|proceed with|agree to|permit|select|choose|confirm|i accept|i agree to|i allow")
    out += ["=" + lead + " " + s for lead in exact_leads for s in exact_stems]
    out += NECESSARY_EXACT_EXTRA
    return out


def build_manage():
    out = []
    out += MANAGE_BARE
    out += combine(MANAGE_VERBS, MANAGE_OBJECTS)
    out += combine(MANAGE_SHOW_VERBS, MANAGE_SHOW_OBJECTS)
    out += MANAGE_HAND
    out += MANAGE_EXACT
    out += PROVIDER_MANAGE
    return out


def build_confirm():
    out = []
    out += combine(CONFIRM_VERBS, CONFIRM_OBJECTS)
    out += combine(CONFIRM_SELECT_LEADS, CONFIRM_SELECT_OBJECTS)
    out += CONFIRM_HAND
    out += CONFIRM_EXACT
    out += PROVIDER_CONFIRM
    return out


def rule(name, reject=(), necessary=(), manage=(), fallback=(), confirm=()):
    return {
        "name": name,
        "rejectResourceIds": [],
        "rejectTextPatterns": prune(list(reject)),
        "necessaryResourceIds": [],
        "necessaryTextPatterns": prune(list(necessary)),
        "manageResourceIds": [],
        "manageTextPatterns": prune(list(manage)),
        "fallbackRejectTextPatterns": prune(list(fallback)),
        "confirmResourceIds": [],
        "confirmTextPatterns": prune(list(confirm)),
    }


# Lexicon

ANCHOR = L("""legitimate interest|list of partners|advertising and content|store and or access information|
cookie policy|cookie consent|gdpr|cookie|cookies|we value your privacy|we use cookies|this site uses cookies|
this website uses cookies|we and our partners|your privacy choices|privacy choices|privacy preferences|
personalised ads|personalized ads|ad measurement|content measurement|audience insights|
consent choices|your consent|manage consent|consent preferences|consent management|
do not sell or share|do not sell my|tracking technologies|data processing purposes|
our partners|our vendors|third party vendors|special purposes|special features|
device identifiers|precise geolocation|transparency and consent framework|iab europe|tcf|
strictly necessary|always active|privacy center|privacy centre|preference center|preference centre|
by continuing to use|by continuing to browse|by using this site|by using this website|
we care about your privacy|your privacy matters|privacy is important to us|
we respect your privacy|about your privacy|your data your choice|your data your choices|
onetrust|cookiebot|didomi|usercentrics|quantcast|sourcepoint|trustarc|iubenda|funding choices|
consentmanager|osano|termly|cookieyes|cookie script|complianz|borlabs|axeptio|klaro|ketch|
transcend|securiti|cookiefirst|cookie information|cookie law|eprivacy|ccpa|cpra|lgpd""")

STRONG_ANCHOR = L("""strictly necessary|necessary cookies|essential cookies|functional cookies|performance cookies|
analytics cookies|marketing cookies|targeting cookies|advertising cookies|statistics cookies|
statistic cookies|preference cookies|preferences cookies|social media cookies|
cookie categories|categories of cookies|cookie preferences|cookie settings|
legitimate interest|list of partners|our partners|our vendors|third party vendors|
store and or access information|always active|always on|cookie consent|
consent preferences|manage consent|privacy preferences|data processing purposes|
special purposes|special features|tracking technologies|personalised ads|personalized ads|
onetrust|cookiebot|didomi|usercentrics|quantcast|sourcepoint|trustarc|iubenda|
consentmanager|osano|termly|cookieyes|cookie script|complianz|borlabs|axeptio|klaro|ketch|
transcend|securiti|cookiefirst|cookie information""")

NEVER_CLICK = L("""pay|paying|payment|payments|paywall|pay or okay|subscribe|subscribed|subscriber|subscription|
subscriptions|purchase|buy|checkout|sign in|sign up|log in|login|register|create account|
create an account|join|membership|upgrade|trial|free trial|start trial|donate|donation|
support us|support our journalism|become a supporter|ad free|ad free version|
remove ads|go ad free|without ads|for only|per month|per year|a month|a year|monthly|yearly""")

ACCEPT_GUARD = L("""accept all|accept everything|allow all|agree to all|agree with all|consent to all|enable all|
turn on all|opt in|opt in to all|accept and continue|accept and close|accept and proceed|
accept and go|accept and read|accept and browse|accept and enter|accept cookies|accept all cookies|
accept the cookies|allow cookies|allow all cookies|agree and continue|agree and close|
agree and proceed|agree and go|agree and read|agree and enter|yes i accept|yes i agree|
yes im happy|yes i am happy|im happy|i am happy|im ok with|i am ok with|sounds good|thats fine|
that is fine|fine by me|continue and accept|close and accept|ok got it|allow essential and optional|
accept essential and optional|allow all and continue|accept recommended|accept recommended settings|
accept default|accept defaults|i consent|consent and continue|consent and close|
personalised ads and content|personalized ads and content|yes to all|
=i accept|=i agree|=accept|=agree|=allow|=consent|=ok|=okay|=yes|=got it|=understood|=continue|
=proceed|=accept cookies|=allow cookies|=enable|=enable cookies|=opt in|=i understand|=okay got it""")

SWITCH_ESSENTIAL = L("""strictly necessary|strictly required|necessary|essential|required|mandatory|always active|always on|
always enabled|always allowed|cannot be disabled|cannot be turned off|cannot be switched off|
cant be disabled|cant be turned off|cant be switched off|technically required|
strictly essential|absolutely necessary|absolutely essential|essentials|necessity|needed|
required for|compulsory|obligatory|technical|technically necessary|technically essential|
site to function|website to function|site to work|website to work|work properly|function properly|
basic function|core function|always present|always required|
cannot be declined|cant be declined|cannot be refused|cant be refused|cannot be rejected|cant be rejected|
cannot be deactivated|cant be deactivated|cannot be unchecked|cant be unchecked|
cannot be changed|cant be changed|cannot be opted out|cant be opted out|no opt out""")

SWITCH_NON_ESSENTIAL = L("""non essential|non necessary|non required|non mandatory|not essential|not necessary|not required|
unnecessary|unessential|nonessential|optional|additional|extra|voluntary""")

SWITCH_KEEP_ON = L("""opt out|do not sell|do not share|dont sell|dont share|do not track|dont track|limit use|limit the use|
limit my|restrict|block|prevent|protect|global privacy control|gpc|remember me|
remember my choice|remember my choices|remember my settings|remember my preferences|
remember my decision|remember this|dont show again|do not show again|dont ask again|
do not ask again|stay signed in|keep me signed in|keep me logged in|stay logged in""")

ADS_PURPOSE = L("""advert|marketing|targeting|personalis|personaliz|ad selection|select basic ads|retarget|behavioural|
behavioral|interest based|profile|profiling|tailored ads|social media|sponsor|ad personalisation|
ad personalization|create a personalised ads profile|create a personalized ads profile""")

STATS_PURPOSE = L("""measure|measurement|audience|analytic|performance|statistic|insight|reporting|metrics|usage data|
site usage|visitor|traffic|improve our|research""")


def build_lexicon():
    lex = {
        "anchorTextPatterns": ANCHOR,
        "strongAnchorTextPatterns": STRONG_ANCHOR,
        "neverClickTextPatterns": NEVER_CLICK,
        "acceptGuardTextPatterns": ACCEPT_GUARD,
        "switchEssentialTextPatterns": SWITCH_ESSENTIAL,
        "switchNonEssentialTextPatterns": SWITCH_NON_ESSENTIAL,
        "switchKeepOnTextPatterns": SWITCH_KEEP_ON,
        "adsPurposeTextPatterns": ADS_PURPOSE,
        "statsPurposeTextPatterns": STATS_PURPOSE,
    }
    return {k: prune(v) for k, v in lex.items()}


def main():
    # Read the rules as they are now and drop any earlier english_wording
    # rule, so running this again never produces a duplicate.
    with open(RULES_PATH) as f:
        original = [r for r in json.load(f) if r.get("name") != "english_wording"]

    english = rule(
        "english_wording",
        reject=build_reject(),
        necessary=build_necessary(),
        manage=build_manage(),
        fallback=FALLBACK_SUBSTRING + FALLBACK_EXACT,
        confirm=build_confirm(),
    )

    rules = list(original) + [english]
    with open(RULES_PATH, "w") as f:
        json.dump(rules, f, ensure_ascii=False, indent=2)
        f.write("\n")

    lexicon = build_lexicon()
    with open(LEXICON_PATH, "w") as f:
        json.dump(lexicon, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("rules written:", len(rules))
    for key, value in english.items():
        if isinstance(value, list) and value:
            print("  english_wording", key, len(value))
    for key, value in lexicon.items():
        print("  lexicon", key, len(value))


if __name__ == "__main__":
    main()
