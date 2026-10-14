# Languages

ConsentGuard matches the wording of banners in all the languages below at the same time. It does not try to guess the language of a page. This matters because a page translated by Google Translate, by Firefox Translations or by another tool shows text in a language the site never wrote, and the text can change a moment after the page loads.

## What is included

| Language | Code | Status |
| --- | --- | --- |
| English | en | Reviewed |
| German | de | Draft |
| French | fr | Draft |
| Spanish | es | Draft |
| Italian | it | Draft |
| Dutch | nl | Draft |
| Portuguese | pt | Draft |
| Kiswahili | sw | Draft, needs a native speaker |
| Afrikaans | af | Draft, needs a native speaker |
| Arabic | ar | Draft, needs a native speaker |
| Zulu | zu | Draft, needs a native speaker |
| Xhosa | xh | Draft, needs a native speaker |
| Hausa | ha | Draft, needs a native speaker |
| Somali | so | Draft, needs a native speaker |
| Yoruba | yo | Draft, needs a native speaker |
| Igbo | ig | Draft, needs a native speaker |
| Amharic | am | Draft, needs a native speaker |

Draft means the phrases were written without a native speaker checking them. A wrong or missing phrase makes ConsentGuard do nothing on that banner. It does not make it press the wrong button, because every pack is checked against the accept guard lists of all the other packs (see below).

## How it stays safe across languages

- Accent marks are folded away before matching, so "Paramétrer" and "Parametrer" match the same phrase.
- Short or ambiguous phrases are written as exact phrases (marked with `=` in the pack files), so "no acepto" is never mistaken for "acepto".
- `python3 tools/check_languages.py` fails if any label that should accept could also be read as a reject, necessary only, manage or confirm phrase in any language, or the other way round.
- If a screen looks like a sign in, payment or bank page, ConsentGuard stays out of it, in every language.

## Adding or fixing a language

1. Edit the file for the language in `tools/langdata/`.
2. Run `python3 tools/build_languages.py` to rebuild `app/src/main/assets/lang/`.
3. Run `python3 tools/check_languages.py`. It must print no problems.
4. Try it with the pages from `tools/make_testpages.py`.

## Trying it on a phone

```
python3 tools/make_testpages.py
cd testpages && python3 -m http.server 8000
adb reverse tcp:8000 tcp:8000
```

Open `http://localhost:8000` in Chrome or Firefox on the phone. Each page shows PASS or FAIL at the top. The `wall` pages must never show PASS, because ConsentGuard must do nothing there. To test translation, open an English page and use the browser's translate feature.
