# Phone Lock

A small Android app with two menus:

- **App Lock** – pick apps that need your fingerprint every time they open. A black lock screen covers the app until you unlock it (falls back to your device PIN on Android 11+ if the fingerprint fails).
- **Hide in Recents** – pick apps whose card on the recent-apps screen gets covered with a black box instead of showing the last thing on screen.

The Phone Lock settings screen is itself protected by your fingerprint once any app is selected, so nobody can just switch the locks off.

## Install

1. Download the APK (from the **Actions** tab › latest *Build APK* run › `PhoneLock-apk`, or build it yourself below).
2. Open it on the phone and allow installing from that source.
3. Open **Phone Lock** and tap **Enable**, then turn on **Phone Lock guard** in Accessibility settings.
   - Android 13+: if the switch is greyed out ("Restricted setting"), go to *Settings › Apps › Phone Lock*, tap **⋮ › Allow restricted settings**, then try again.
4. Pick your apps in each tab.

## Build

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (platform 35). Every build is signed with the same committed key (`app/debug.keystore`), so new builds install over old ones. That key is public – don't reuse it for anything else.

## How it works and its limits

Android has no public API for one app to lock another or to change another app's recents thumbnail, so Phone Lock uses an accessibility service:

- **Lock:** when a locked app's activity comes to the front, the lock screen is launched on top. The app re-locks as soon as you switch to a different app or turn the screen off. There can be a split-second flash of the app before the lock screen appears.
- **Recents:** while the launcher's recents screen is open, the service finds cards labelled with a hidden app's name and draws non-touchable black boxes over them (you can still tap and swipe the cards). This depends on the launcher labelling its cards, which Pixel/stock Android, Samsung One UI and most others do. The live animation while you swipe up from an app, and fast card scrolling, may briefly show the real thumbnail before the box catches up.
- Many phones (Samsung, Xiaomi, OnePlus, Pixel with Private Space) also have a built-in "hide content in recents" option you can combine with this.

The service only reads package/window names and recents card labels. Nothing is saved or sent anywhere, and the app has no internet permission.
