# ConsentGuard

```
 ██████╗ ██████╗ ███╗   ██╗███████╗███████╗███╗   ██╗████████╗
██╔════╝██╔═══██╗████╗  ██║██╔════╝██╔════╝████╗  ██║╚══██╔══╝
██║     ██║   ██║██╔██╗ ██║███████╗█████╗  ██╔██╗ ██║   ██║
██║     ██║   ██║██║╚██╗██║╚════██║██╔══╝  ██║╚██╗██║   ██║
╚██████╗╚██████╔╝██║ ╚████║███████║███████╗██║ ╚████║   ██║
 ╚═════╝ ╚═════╝ ╚═╝  ╚═══╝╚══════╝╚══════╝╚═╝  ╚═══╝   ╚═╝

 ██████╗ ██╗   ██╗ █████╗ ██████╗ ██████╗
██╔════╝ ██║   ██║██╔══██╗██╔══██╗██╔══██╗
██║  ███╗██║   ██║███████║██████╔╝██║  ██║
██║   ██║██║   ██║██╔══██║██╔══██╗██║  ██║
╚██████╔╝╚██████╔╝██║  ██║██║  ██║██████╔╝
 ╚═════╝  ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝
```

## What it does

ConsentGuard is an Android accessibility service that watches the screen for cookie and tracking consent prompts and automatically rejects them.

- It works in Chrome, embedded WebViews, and some native consent dialogues. What it can see varies between apps and websites.
- On a simple banner it taps the reject option, or the necessary only option.
- On a banner with a long list of individual switches, it switches off the optional ones one by one, then saves if the site asks for that. Switches marked as required or essential are left on.
- It reads banners in 17 languages at once, including German, French, Spanish, Kiswahili and several other African languages, so it also works on pages translated by the browser. See [languages](docs/LANGUAGES.md).
- It never accepts. On a screen that offers only accept or pay, it does nothing.
- It requires no internet permission and collects no data. It keeps nothing except your settings.

I created ConsentGuard to spare myself the tedious ritual of hunting through the endless settings so generously provided by loving and caring advertising and analytics providers, such as the one below:

<img src="docs/screenshot-toggles.jpg" width="280"
alt="A consent screen containing numerous individual toggle switches, one of which is switched off" />

## Download

1. Download `ConsentGuard.apk` from the [Releases page](https://github.com/macbuildssys/ConsentGuard/releases).
2. Tap **Install**. Google Play Protect may warn you because the app is not from Google Play. Choose **More details**, then **Install anyway**, if you trust the source.
3. Open **ConsentGuard**. It shows whether the service is switched on.

### Install on Android 13 and later

Android can block accessibility services in apps installed from outside Google Play. This is called a restricted setting. If ConsentGuard is greyed out in the accessibility list, or Android shows a "Restricted setting" message when you try to switch it on, allow it like this:

1. Open the **Settings** app.
2. Tap **Apps**.
3. Tap **ConsentGuard**. If you cannot find it, tap **See all apps** or **App info** first.
4. Tap the three dot menu (**More**) and choose **Allow restricted settings**.
5. Follow the on screen instructions.
6. Go back to **Settings > Accessibility > Installed apps > ConsentGuard** and turn it on.

If the **Allow restricted settings** option is not there, first try turning the service on once. Android then shows the restricted setting message, and the option appears in the app's menu afterwards.

The exact wording and location can vary between Android versions and device makers. Google describes the steps here: [Restricted settings on Android](https://support.google.com/android/answer/12623953).

## Using it

Once the service is on, there is nothing more to do. Browse as usual and ConsentGuard handles prompts as they appear.

- If ConsentGuard is not sure that every optional switch is off, it does not press Save or Confirm and leaves the panel as it is, so you can finish it yourself.
- **Quick settings tile**: add the ConsentGuard tile to pause it for 5 minutes, and tap again to resume.
- **Apps to skip**: in the rules screen you can add any app by its name, as shown on your home screen, and ConsentGuard will leave it alone. Nobody has to type a package name. Adding or removing an app is saved at once.
- **Banking and payment apps are never monitored.** Bank, wallet, money transfer, email and chat apps (Gmail, WhatsApp, Signal, Telegram and similar) are skipped before anything on their screen is read. ConsentGuard recognises them by the name the app shows on your home screen (any name with "bank" in many languages, and brands like M-Pesa, Nala, LemFi, Wise, Sendwave, Samsung Pay and banks from about 90 countries). **See protected apps on this phone** in the rules screen shows exactly which of your apps are covered. If one is missing, add it by name. Browsers are never skipped, because that is where banners are, but screens that look like payment or sign in forms are left alone. See [docs/PRIVACY.md](docs/PRIVACY.md).
- It does not block ads or close popups. It only deals with cookie consent panels. For ad blocking, use uBlock Origin in Firefox, the Brave or DuckDuckGo browser, or set Private DNS in Android settings to `dns.adguard-dns.com`.
- To update, install the newer `ConsentGuard.apk` over the old one, then check that the service is still switched on.
- To stop it, switch it off under **Settings > Accessibility > Installed apps > ConsentGuard**, or uninstall the app.

## Checking the build

- `python3 tools/check_no_network.py` confirms the app asks for no network permission.
- `python3 tools/check_languages.py` checks the language packs for clashes.
- `python3 tools/check_safe_zone.py` checks the bank and payment list.
- The Gradle build leaves out the dependency info block, so building the same source gives the same APK.

## Best effort

ConsentGuard works on a best effort basis. It can miss some banners and switches, and it cannot promise that a site stops tracking you or that any law is met. Read the full [best effort statement](docs/BEST_EFFORT.md).

## License

Distributed under the MIT License. See [LICENSE](LICENSE).
