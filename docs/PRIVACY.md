# What ConsentGuard can and cannot see

This page is written so you can check each claim yourself, not just believe it.

## What it does

ConsentGuard is an Android accessibility service. It reads the text on the screen only to find a cookie consent panel. When it finds one it taps Reject, or switches optional choices off. Nothing else.

## What it never does

**It cannot send anything off your phone.**
The app has no INTERNET permission. Android enforces this. Look at `AndroidManifest.xml` (the permission is removed on purpose) or run `python3 tools/check_no_network.py`.

**Banking and payment apps are not monitored.**
The first thing every scan does is check the app name. For banks, wallets, payment, email and chat apps it stops there, before any text is read. See `isSafeZone` in `ConsentBlockerService.kt` and `SafeZoneWords.kt`. In the app, **Apps to skip** then **See protected apps on this phone** lists them on your own phone.

**It keeps no logs.**
Logging only exists in debug builds (`BuildConfig.DEBUG`). A release build writes nothing.

**It keeps no history of what you read or did.**
The only things stored are your skipped app names and the pause time. Older versions' leftovers are deleted on start.

**The APK is the code you can read.**
The build is reproducible. Build it yourself and compare. F-Droid builds from the source.

## How to be sure it has no internet access

On Android an app can only use the network if its manifest declares the INTERNET permission. This is a normal permission: Android grants it automatically at install and never shows a prompt for it, so users cannot see it being given. That is why the manifest is the place to look. An app that does not declare it is refused every connection by the system. So the question comes down to one thing you can look at. Three ways, from easy to thorough:

1. **On your phone.** Open Settings, Apps, ConsentGuard, Permissions. Network access is not shown there because it is a normal permission, so also check Settings, Connections, Data usage, and look at ConsentGuard: it should show 0 bytes however long you use it.
2. **With adb.** `adb shell dumpsys package dev.macbuildssys.consentguard | grep -i internet` prints nothing when the permission is absent. To see the full list of permissions, run `adb shell dumpsys package dev.macbuildssys.consentguard` and read the "requested permissions" part. ConsentGuard asks for none.
3. **From the APK itself.** `aapt dump permissions app-debug.apk` (from the Android build tools) lists the permissions inside the file you install. It should list none. Do this on the APK you downloaded, so it checks the real file and not the source.

For the source, run `python3 tools/check_no_network.py`. It fails if the manifest asks for a network permission, or if the code uses networking classes or opens links or share screens that could pass data to another app.

What this does not cover: a phone with a rooted system or a modified Android could break these rules for any app. That is true for every app on that phone.

## Honest limits

- Android sends accessibility events from every app to every accessibility service that is switched on. That is how Android works. ConsentGuard ignores events from protected apps and reads nothing from them, but Android itself cannot hide the event from it.
- Protected apps are recognised by the name on the home screen. An unusual banking app may be missed. Add it by name under **Apps to skip**.
- In a browser, ConsentGuard must read the page to find a banner. It does nothing on screens that look like payment or sign in forms, but this is wording based and best effort.

## Reporting a problem

If ConsentGuard ever acts inside an app it should not, open an issue on GitHub with the app name (not a screenshot of private data).
