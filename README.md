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


- ConsentGuard is an Android accessibility service that monitors the screen
for cookie-consent and tracking-consent prompts and automatically selects
the option to reject them.

- It works in Chrome, embedded WebViews, and some native consent dialogues.
Android accessibility services can inspect the accessibility node hierarchy
exposed by web content and native Android views, although the information
available may vary between applications.

- ConsentGuard requires no internet permission and collects no data. The application is designed specifically
to handle cookie-consent and tracking-consent prompts. ConsentGuard can identify and switch off multiple consent
options automatically. I created ConsentGuard to spare myself the tedious ritual of hunting through the endless settings so generously provided by loving and caring advertising and analytics providers, such as the one below:

<img src="docs/screenshot-toggles.jpg" width="280"
alt="A consent screen containing numerous individual toggle switches, one of which is switched off" />


## One-time toolchain setup

These instructions are for Ubuntu systems without Android Studio.

Install the basic development tools:

```
sudo apt install openjdk-17-jdk unzip adb fastboot
```

Ubuntu does not provide a standalone `sdkmanager` package. Instead, it
provides Google Android command-line tools through installer packages.
Install the highest version offered by `apt`. You can view the available
packages with:

```
apt search google-android-cmdline
```

For example:

```
sudo apt install google-android-cmdline-tools-13.0-installer
```

This downloads Google's official command-line tools archive during
installation and extracts it to `/usr/lib/android-sdk`. Because that
directory is owned by `root`, copy it to your home directory so that
subsequent commands do not require `sudo`:

```
sudo cp -r /usr/lib/android-sdk ~/Android/Sdk

sudo chown -R (whoami) ~/Android/Sdk
```

The `chown` command above uses Fish shell syntax. If you are using Bash,
run this instead:

```
sudo chown -R "$USER" "$HOME/Android/Sdk"
```

### Configure the Android SDK

Add the following lines to `~/.config/fish/config.fish`. Adjust the
command-line tools version if you installed a different version:

```
set -gx ANDROID_HOME $HOME/Android/Sdk

set -gx PATH $ANDROID_HOME/cmdline-tools/13.0/bin $ANDROID_HOME/platform-tools $PATH
```

Reload the Fish configuration or open a new terminal:

```
source ~/.config/fish/config.fish
```

Then accept the Android SDK licences and install the required platform and
build tools:

```
sdkmanager --licenses

sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## Install Gradle

SDKMAN keeps Gradle independent of Ubuntu's package repositories, which may
provide an outdated version:

```
curl -s "https://get.sdkman.io" | bash

sdk install gradle 8.7
```

After installing SDKMAN, you may need to open a new terminal or load its
configuration before running the `sdk` command.

## JDK compatibility issue

If a later build fails with an error similar to:

```
jlink executable ... does not exist
```

Gradle may be using a different JDK from the one installed above. This
commonly occurs when JDK 21, or a Java runtime rather than a full JDK, has
been selected as the system default.

First, check the installed Java versions:

```
ls /usr/lib/jvm/
```

Then confirm that JDK 17 includes the `jlink` executable:

```
ls /usr/lib/jvm/java-17-openjdk-amd64/bin/jlink
```

If the file exists, configure this project to use JDK 17 explicitly:

```
echo "org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64" >> gradle.properties
```

This setting applies to all builds in this project, including debug and
release builds, because it is stored in the project's
`gradle.properties` file.

## Development environment

In Visual Studio Code, the Kotlin extension (`fwcd.kotlin`) provides syntax
highlighting and basic code completion. Android Studio is not required for
this project.

The project contains Kotlin source code and two small XML files; it does not
require a layout editor.

## Building

From the project root, generate the Gradle Wrapper:

```
gradle wrapper --gradle-version 8.7
```

This command only needs to be run once. After the Gradle Wrapper files have
been added to the repository, subsequent builds can use `./gradlew` without
requiring a system-wide Gradle installation.

Build the debug version:

```
./gradlew assembleDebug
```

The generated APK is located at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Installing and enabling ConsentGuard

Enable Developer Options and USB debugging on your Android device. Connect
the device to your computer and confirm that it is recognised:

```
adb devices
```

Install the debug build:

```
./gradlew installDebug
```

Launch ConsentGuard once. The application displays whether the accessibility
service is enabled and provides a link to the Accessibility settings.

### Android 13 and later

Applications installed outside Google Play may be subject to restricted
settings by default. This can prevent ConsentGuard from appearing in the
list of available accessibility services.

**ConsentGuard > OPEN ACCESSIBILITY SETTINGS > Installed apps > ConsentGuard > Toggle it On**

If ConsentGuard does not appear under **Settings > Accessibility**, open:

**Settings > Apps > ConsentGuard**

Tap the three-dot menu, select **Allow restricted settings**, and then return
to the Accessibility settings and enable the service.

The exact wording and location of this option may vary between Android
versions and device manufacturers.

## Testing

To monitor the service in real time, run:

```
adb logcat -s ConsentBlocker
```

You can then generate consent prompts for the service to process:

- Open Chrome and visit several news or shopping websites serving users in
  regions covered by the GDPR. Many use consent platforms such as OneTrust,
  Cookiebot, or Didomi, which are covered by the generic text-matching
  rules.
- Open an application that embeds a website in a WebView, such as a news
  application that displays articles through a mobile website. Check whether
  the same consent banner is detected there.
- Test both simple accept-or-reject banners and detailed preference panels
  containing multiple individual switches.

Behaviour may vary between websites and applications because each one
exposes different information through Android's accessibility framework.

## Preparing a release build

Verbose diagnostic messages, including calls to `debugLog` that replace the
previous `Log.d` calls throughout `ConsentBlockerService.kt`, are compiled
out of release builds. These messages are controlled by `BuildConfig.DEBUG`,
which is `false` in release builds.

`Log.i` messages, which record actual clicks, and `Log.w` messages, which
record significant problems, remain enabled in both build types. These
messages are infrequent and are also written to the in-app history where
appropriate.

This functionality requires the following configuration in
`app/build.gradle.kts`:

```
buildFeatures {
    buildConfig = true
}
```

Recent versions of the Android Gradle Plugin do not generate
`BuildConfig` by default.

Debug builds installed with `installDebug` are suitable for testing on a
development device. A release build is signed and can be distributed
without requiring the development machine to remain connected. It is the
appropriate format for sharing or attaching to a GitHub release.

### Generate a release keystore

The keystore should be kept secure. If it is lost, future versions cannot
replace an existing installation unless users first uninstall the
application.

Generate a keystore from the project root:

```
keytool -genkeypair -v \
  -keystore consentguard-release.jks \
  -alias consentguard \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Create `keystore.properties` in the project root. This file should already
be listed in `.gitignore` and must never be committed to the repository.

Because the keystore command above creates the keystore in the project root,
use this configuration:

```
storeFile=consentguard-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=consentguard
keyPassword=YOUR_KEYSTORE_PASSWORD
```

Replace `YOUR_KEYSTORE_PASSWORD` with the password selected when the
keystore was created. Do not include angle brackets.

If the keystore is stored one directory above the project root instead,
use:

```
storeFile=../consentguard-release.jks
```

Build the release version:

```
./gradlew assembleRelease
```

The generated release APK is located at:

```
app/build/outputs/apk/release/app-release.apk
```

Distributed under the MIT License. See [LICENSE](LICENSE).
