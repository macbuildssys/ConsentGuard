# Writes mock consent banners to testpages/ so ConsentGuard can be tried on a
# phone without visiting real sites. These are simple pages written for this
# project, not copies of any real banner. Each page shows a line at the top
# that says what happened (PASS or FAIL), so nothing needs to be logged.
#
# Run:   python3 tools/make_testpages.py
# Serve: cd testpages && python3 -m http.server 8000
# Phone: connect over USB, run   adb reverse tcp:8000 tcp:8000
#        then open http://localhost:8000 in Chrome or Firefox on the phone.
#        To try translation, use Chrome's Translate or Firefox's Translations on
#        the English pages, and the other pages as they are.
import os

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "testpages")

L = {
    "en": dict(dir="ltr", lead="We use cookies and similar technologies to personalise content and measure visits.", accept="Accept all", reject="Reject all", manage="Manage options", save="Save choices", pay="Subscribe for 4.99 a month", essential="Strictly necessary (always active)", stats="Analytics cookies", ads="Marketing cookies", pers="Personalised ads"),
    "de": dict(dir="ltr", lead="Wir verwenden Cookies, um Inhalte zu personalisieren und Besuche zu messen.", accept="Alle akzeptieren", reject="Alle ablehnen", manage="Einstellungen verwalten", save="Auswahl speichern", pay="Jetzt abonnieren für 4,99 im Monat", essential="Unbedingt erforderlich (immer aktiv)", stats="Statistik Cookies", ads="Marketing Cookies", pers="Personalisierte Werbung"),
    "fr": dict(dir="ltr", lead="Nous utilisons des cookies pour personnaliser le contenu et mesurer les visites.", accept="Tout accepter", reject="Tout refuser", manage="Paramétrer les cookies", save="Enregistrer mes choix", pay="S'abonner pour 4,99 par mois", essential="Strictement nécessaires (toujours actifs)", stats="Cookies statistiques", ads="Cookies publicitaires", pers="Publicités personnalisées"),
    "es": dict(dir="ltr", lead="Utilizamos cookies para personalizar el contenido y medir las visitas.", accept="Aceptar todo", reject="Rechazar todo", manage="Configurar cookies", save="Guardar preferencias", pay="Suscribirse por 4,99 al mes", essential="Estrictamente necesarias (siempre activas)", stats="Cookies analíticas", ads="Cookies publicitarias", pers="Anuncios personalizados"),
    "sw": dict(dir="ltr", lead="Tunatumia vidakuzi kubinafsisha maudhui na kupima ziara.", accept="Kubali zote", reject="Kataa zote", manage="Dhibiti chaguo", save="Hifadhi mapendeleo", pay="Jisajili kwa 4.99 kwa mwezi", essential="Muhimu kabisa (inatumika kila wakati)", stats="Vidakuzi vya takwimu", ads="Vidakuzi vya matangazo", pers="Matangazo yaliyobinafsishwa"),
    "zu": dict(dir="ltr", lead="Sisebenzisa amakhukhi ukwenza okuqukethwe ngokwezifiso nokulinganisa ukuvakasha.", accept="Yamukela konke", reject="Yenqaba konke", manage="Lawula izinketho", save="Londoloza izintandokazi", pay="Bhalisela ngo 4.99 ngenyanga", essential="Okudingekayo kakhulu (njalo kuyasebenza)", stats="Amakhukhi izibalo", ads="Amakhukhi okukhangisa", pers="Izikhangiso ezenziwe ngokwezifiso"),
    "af": dict(dir="ltr", lead="Ons gebruik koekies om inhoud te verpersoonlik en besoeke te meet.", accept="Aanvaar alles", reject="Weier alles", manage="Bestuur opsies", save="Stoor voorkeure", pay="Teken in vir 4.99 per maand", essential="Streng noodsaaklik (altyd aktief)", stats="Statistiese koekies", ads="Bemarking koekies", pers="Gepersonaliseerde advertensies"),
    "ar": dict(dir="rtl", lead="نحن نستخدم ملفات تعريف الارتباط لتخصيص المحتوى وقياس الزيارات.", accept="قبول الكل", reject="رفض الكل", manage="إدارة الخيارات", save="حفظ التفضيلات", pay="اشترك مقابل 4.99 في الشهر", essential="ضرورية للغاية (نشطة دائما)", stats="ملفات تعريف الارتباط الإحصائية", ads="ملفات تعريف الارتباط الإعلانية", pers="الإعلانات المخصصة"),
}

SCENARIOS = {
    "simple": "Test page. Expect a green PASS bar without touching anything.",
    "manage": "Two step test page. Expect a green PASS bar without touching anything.",
    "wall": "Test page. This one must stay on WAITING. It must never turn green.",
    "late": "Slow test page. The box appears after three seconds. Expect a green PASS bar.",
    "sneaky": "Test page with a tricky layout. Expect a green PASS bar without touching anything.",
}

PAGE = """<!doctype html>
<html lang="{code}" dir="{dir}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Test {scenario} {code}</title>
<style>
body{{font:16px/1.5 sans-serif;margin:0;padding:12px 12px 280px}}
#verdict{{position:sticky;top:0;background:#222;color:#fff;padding:8px 12px;margin:-12px -12px 12px;font-weight:bold}}
#verdict.pass{{background:#1b7a2b}} #verdict.fail{{background:#b3261e}}
#banner{{position:fixed;left:0;right:0;bottom:0;background:#fff;border-top:3px solid #444;padding:14px;box-shadow:0 -4px 14px #0004;max-height:70vh;overflow:auto}}
button{{font-size:16px;padding:10px 14px;margin:4px 4px 4px 0}}
.big{{background:#0a58ca;color:#fff;border:0;font-size:18px;padding:14px 20px}}
a.small{{font-size:11px;color:#999;margin-inline-start:8px}}
label{{display:block;margin:10px 0}}
</style></head><body>
<div id="verdict">WAITING</div>
<h1>{scenario} test ({code})</h1>
<p>{note}</p>
<div id="banner" role="dialog" aria-modal="true" {hidden}>
{body}
</div>
<script>
var v=document.getElementById('verdict'), b=document.getElementById('banner');
function done(ok,msg){{v.textContent=(ok?'PASS ':'FAIL ')+msg;v.className=ok?'pass':'fail';b.hidden=true;}}
function rejected(){{done(true,'rejected');}}
function accepted(){{done(false,'accepted');}}
function save(){{
  var on=[].filter.call(document.querySelectorAll('#panel input'),function(i){{return i.checked&&!i.disabled;}});
  var ess=document.querySelector('#panel input[disabled]');
  if(on.length){{done(false,on.length+' optional switch(es) still on');}}
  else if(!ess||!ess.checked){{done(false,'required switch was turned off');}}
  else{{done(true,'all optional off, required kept');}}
}}
function openPanel(){{document.getElementById('main').hidden=true;document.getElementById('panel').hidden=false;}}
{late}
</script></body></html>"""


def body(sc, t):
    pay = '<button type="button" onclick="v.textContent=\'FAIL pay pressed\'">%s</button>' % t["pay"]
    if sc in ("simple", "late"):
        return '<div id="main"><p>%s</p><button class="big" type="button" onclick="accepted()">%s</button><button type="button" onclick="rejected()">%s</button></div>' % (t["lead"], t["accept"], t["reject"])
    if sc == "sneaky":
        return '<div id="main"><p>%s</p><button class="big" type="button" onclick="accepted()">%s</button><a class="small" href="#" role="button" onclick="rejected();return false">%s</a></div>' % (t["lead"], t["accept"], t["reject"])
    if sc == "wall":
        return '<div id="main"><p>%s</p><button class="big" type="button" onclick="accepted()">%s</button>%s</div>' % (t["lead"], t["accept"], pay)
    panel = ('<div id="panel" hidden><label><input type="checkbox" role="switch" checked disabled> %s</label>'
             '<label><input type="checkbox" role="switch" checked> %s</label>'
             '<label><input type="checkbox" role="switch" checked> %s</label>'
             '<label><input type="checkbox" role="switch" checked> %s</label>'
             '<button type="button" onclick="save()">%s</button></div>') % (t["essential"], t["stats"], t["ads"], t["pers"], t["save"])
    return ('<div id="main"><p>%s</p><button class="big" type="button" onclick="accepted()">%s</button>'
            '<button type="button" onclick="openPanel()">%s</button></div>%s') % (t["lead"], t["accept"], t["manage"], panel)


def main():
    os.makedirs(OUT, exist_ok=True)
    links = []
    for sc, note in SCENARIOS.items():
        for code, t in L.items():
            hidden = "hidden" if sc == "late" else ""
            late = "setTimeout(function(){b.hidden=false;},3000);" if sc == "late" else ""
            html = PAGE.format(code=code, dir=t["dir"], scenario=sc, note=note, body=body(sc, t), hidden=hidden, late=late)
            name = "%s_%s.html" % (sc, code)
            with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
                f.write(html)
            links.append((sc, code, name))
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Test pages</title><body style="font:16px/1.6 sans-serif;padding:12px"><h1>Test pages</h1>')
        for sc, note in SCENARIOS.items():
            f.write("<h2>%s</h2><p>%s</p><p>" % (sc, note))
            f.write(" ".join('<a href="%s">%s</a>' % (n, c) for s, c, n in links if s == sc))
            f.write("</p>")
    print("wrote", len(links), "pages to", OUT)


if __name__ == "__main__":
    main()
