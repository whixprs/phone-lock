# Phone Lock

A small Android app with two menus:

- **App Lock** – pick apps that need your fingerprint every time they open. A black lock screen covers the app until you unlock it (falls back to your device PIN on Android 11+ if the fingerprint fails).
- **Hide in Recents** – pick apps that show a solid black card on the recent-apps screen, like the camera app (needs root + LSPosed, see below).

The Phone Lock settings screen is itself protected by your fingerprint once any app is selected, so nobody can just switch the locks off.

## Download

- **APK:** [PhoneLock.apk](https://github.com/whixprs/phone-lock/releases/latest/download/PhoneLock.apk) (latest build)
- **Source code:** [zip](https://github.com/whixprs/phone-lock/archive/refs/heads/main.zip) – or pick any version on the [Releases page](https://github.com/whixprs/phone-lock/releases), which has the APK plus source zip/tar.gz for every build.

A new release is published automatically every time `main` is updated.

## Install

1. Download the APK (link above, or build it yourself below).
2. Open it on the phone and allow installing from that source.
3. Open **Phone Lock** and tap **Enable**, then turn on **Phone Lock guard** in Accessibility settings.
   - Android 13+: if the switch is greyed out ("Restricted setting"), go to *Settings › Apps › Phone Lock*, tap **⋮ › Allow restricted settings**, then try again.
4. Pick your apps in each tab.

## Hide in Recents (root + LSPosed)

Android only lets an app black out its own recents card (that's what the camera does), so this part is an LSPosed module built into the same APK. It hooks Android's window manager so every window of a hidden app is treated as secure, which gives it the same black card.

1. Install the APK, open **LSPosed › Modules**, and turn on **Phone Lock**.
2. In its scope, tick **System Framework** and **Phone Lock** (these are pre-selected as recommended).
3. Reboot.
4. In Phone Lock's **Hide in Recents** tab, switch apps on. The tab says "Root module active" when it's working. Close and reopen an app once after switching it on.

Screenshots and screen recordings of hidden apps come out black too. Without root, nothing is drawn over recents – the tab just explains that the module is needed.

## Build

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (platform 35). Every build is signed with the same committed key (`app/debug.keystore`), so new builds install over old ones. That key is public – don't reuse it for anything else.

## How it works and its limits

Android has no public API for one app to lock another, so the lock uses an accessibility service:

- **Lock:** when a locked app's activity comes to the front, the lock screen is launched on top. The app re-locks as soon as you switch to a different app or turn the screen off. There can be a split-second flash of the app before the lock screen appears.
- **Recents:** handled by the LSPosed module, not the accessibility service (see above).
- Many phones (Samsung, Xiaomi, OnePlus, Pixel with Private Space) also have a built-in "hide content in recents" option you can combine with this.

The service only checks which app is in front. Nothing is saved or sent anywhere, and the app has no internet permission.
