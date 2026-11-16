# Cursor Keep-Alive

[![Build](https://github.com/murslinshehzad-code/cursor-keep-alive/actions/workflows/build.yml/badge.svg)](https://github.com/murslinshehzad-code/cursor-keep-alive/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-no%20dependencies-7F52FF)

**Keep your laptop awake from your phone.** Cursor Keep-Alive holds a
[Chrome Remote Desktop](https://remotedesktop.google.com/) session open in the background and sends tiny,
random touch swipes into it, so your laptop sees real mouse movement and never goes idle. Nothing to install
or run on the laptop other than Chrome Remote Desktop itself.

> **Not affiliated with Google.** "Chrome Remote Desktop" is a trademark of Google LLC. See
> [Responsible use](#responsible-use).

## Features

- **No visible window.** The Chrome Remote Desktop web page runs in an almost transparent overlay inside a
  foreground service, so you can use any other app while it works. Closing something else never ends the
  session.
- **Natural movement.** Swipes use a random direction, length, speed and starting point, at random gaps
  between 1 and 30 seconds (short gaps are more likely than long ones). There is always activity at least
  every 30 seconds.
- **Go dark.** Blacks out the screen at the lowest brightness while keeping the display on, so the phone can
  sit in your pocket. Double-tap or long-press the black screen to exit.
- **Self-healing.** Detects a dropped session and reconnects, recovers from a crashed page, and tells you
  when your Google sign-in has expired.
- **Pause, resume, stop.** From the app or from the notification, which also shows live status.
- **Timing presets and settings.** Frequent, Normal and Relaxed presets, plus advanced swipe length and
  speed settings.
- **Light and dark theme**, following your phone.
- **No tracking, no ads, no dependencies.** Plain Android framework code only.

## How it works

1. You sign in to Google and open your laptop once, inside the app (**Connect to laptop**). The app saves the
   session link.
2. On **Start**, a foreground service opens that session in a small, almost transparent `WebView` overlay
   window. Android treats the page as visible, so the connection stays alive while other apps are in front.
3. At random intervals the service finds the remote-screen canvas on the page and injects a short out-and-back
   touch swipe into it. Chrome Remote Desktop sends this to the laptop as mouse movement.

The swipes are real Android touch events delivered to the page, not scripted DOM events. In testing, scripted
events were ignored by the page while real touch events worked.

## Requirements

- An Android 8.0+ (API 26) phone. Developed and tested on a Samsung Galaxy A26 5G (Android 16). Other devices
  may behave differently.
- A laptop or desktop with [Chrome Remote Desktop](https://remotedesktop.google.com/access) set up and online.
- A Google account that can access that computer.

## Install

There is no store listing. Build it yourself, or download the APK from the
[Releases](https://github.com/murslinshehzad-code/cursor-keep-alive/releases) page and install it
(you will need to allow installing from unknown sources). Release APKs are debug-signed.

### Build from source

You need JDK 17 and the Android SDK (platform 35 and build-tools 35.0.0).

```bash
git clone https://github.com/murslinshehzad-code/cursor-keep-alive.git
cd cursor-keep-alive
echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. To install it over USB with
[`adb`](https://developer.android.com/tools/adb) (turn on USB debugging first):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Using it

**One-time setup**

1. Open the app and tap **Connect**. Sign in to Google, tap your laptop, and wait until you see its screen.
   If Chrome Remote Desktop asks for your PIN, tick "remember on this device". Tap **Use this laptop**.
2. Tap **Allow** next to *Display over other apps* and turn it on.
3. Tap **Allow** next to *Runs in the background* so Android does not stop the service.
4. Allow notifications when asked, so you can pause or stop from the notification shade.

**Every day**

1. Make sure the laptop is on and online.
2. Open the app and tap **Start**. After a few seconds it shows *Laptop connected*.
3. Optional: tap **Go dark** and put the phone in your pocket.
4. Tap **Stop** (or **Stop everything**) when you are done.

## Settings

| Setting | Meaning |
|---|---|
| Presets | Frequent (1-10 s), Normal (1-20 s), Relaxed (5-30 s) |
| Shortest / longest gap | Random time between swipes, 1-30 s |
| Keep the screen on | Holds the display on while running |
| Stop when closed from recents | Stops everything if you swipe the app away |
| Swipe length and duration | Advanced; defaults are 60-115 px and 250-450 ms |

## Limitations

- **The screen must stay on.** With the display off, Android freezes the page's input handling and the laptop
  ignores the swipes. The connection itself survives. Do not press the power button; use **Go dark**. Keeping
  the phone on a charger is wise for long sessions.
- **Unofficial.** The app automates Google's web page. A change on Google's side could break it.
- **Needs sign-in once.** If Google signs you out, the app alerts you and you must connect again.
- The laptop must be on and online. The app cannot wake a sleeping or switched-off computer.
- Only tested on one phone model. Reports from other devices are welcome.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Load the Chrome Remote Desktop web page |
| `SYSTEM_ALERT_WINDOW` | Host the page in an invisible window and draw the black Go dark screen |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the loop running in the background |
| `POST_NOTIFICATIONS` | Show the status notification with Pause and Stop |
| `WAKE_LOCK`, `ACCESS_WIFI_STATE` | Keep the CPU and Wi-Fi awake while running |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask Android not to stop the service |

## Privacy

The app has no analytics, no ads and no server of its own. It only talks to Google (the sign-in and the
Chrome Remote Desktop web page). Your Google sign-in cookies stay in the app's own web view storage on your
phone, and the saved laptop link is stored in the app's private preferences. Nothing is sent anywhere else.

## Responsible use

Use this only on computers you own or are authorised to control. Some employers monitor activity or forbid
keeping a machine awake artificially. Do not use this app to hide inactivity from monitoring or to break
workplace policy. You are responsible for how you use it. The software is provided without warranty (see the
licence).

## Project layout

```
app/src/main/java/com/example/cursorkeepalive/
  MainActivity.kt                 Main screen: status, controls, setup checklist, settings
  SessionActivity.kt              One-time sign-in and laptop selection
  KeepAliveForegroundService.kt   Background session, random swipes, Go dark, notification
  Prefs.kt                        Settings storage and validation
```

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). To report a security problem, see [SECURITY.md](SECURITY.md).

## License

Copyright (C) 2026 murslinshehzad-code.

This program is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be useful, but
**without any warranty**.
