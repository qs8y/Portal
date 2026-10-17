# Portal

Portal is an Android app for browsing the websites you add, with a few tools built around video: an in-app player, saved states that remember the exact second, and a "continue watching" shelf.

It ships with **no websites**. On first launch you add the ones you want.

## Features

- Tabs, history, favorites and saved states (page snapshot plus video position)
- Built-in player with speed control, double-tap seek, mini player, floating window, and notification / lock-screen controls
- Downloads with queue, retry and Wi-Fi-only option
- Per-site settings for dark pages, custom scripts and DNS
- Ad and popup blocker with an updatable blocklist
- App lock (fingerprint, face or PIN), incognito mode, custom DNS-over-HTTPS
- Three app styles: Clean, Expressive and Dark
- Share a link to Portal to open it, or add its website

## Install

1. Open the [latest release](https://github.com/qs8y/Portal/releases/latest).
2. Download the `.apk` file and install it. Android will ask you to allow installs from your browser or file manager.

Portal checks this repository for new releases and tells you when one is available. You can change or clear the update source in **Settings > General > Update source**.

## Build

Requirements: JDK 17, Android SDK 33, Gradle 7.4.2 (the wrapper handles it). It also builds on a phone with AndroidIDE.

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/`.

### Signing

An update only installs over an older version if both are signed with the same key.

1. Create a key once and keep it private:
   `keytool -genkeypair -v -keystore portal.jks -keyalg RSA -keysize 2048 -validity 10000 -alias portal`
2. Copy `keystore.properties.example` to `keystore.properties` and fill in your values.
3. Build as usual. If `keystore.properties` is missing, the build is unsigned or uses the default debug key.

Never commit `portal.jks` or `keystore.properties`. The `.gitignore` already excludes them.

## Publishing a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (for example `1.3`).
2. Build and sign the APK.
3. On GitHub, create a release with the tag `v1.3` and attach the APK.

The app compares the tag with its own version to decide whether to offer an update.

## Notes

Portal is a browser for websites you choose. It includes no content, and you are responsible for what you open and for following each website's terms.
