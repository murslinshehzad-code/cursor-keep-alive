# Contributing

Thanks for wanting to help. This is a small project, so the process is simple.

## Reporting bugs and ideas

Open an [issue](../../issues/new/choose) and pick a template. For bugs, please include:

- your phone model and Android version,
- what you expected and what happened,
- the app's status text (for example *Laptop session: connecting*),
- logs if you can: `adb logcat -s KeepAlive`.

Please do **not** paste your Google account details, session links or screenshots of your laptop.

## Building

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). See the README for the steps.

```bash
./gradlew assembleDebug
```

## Making a change

1. Fork the repository and create a branch from `main`.
2. Keep changes focused. One fix or feature per pull request.
3. Follow the existing style: plain Kotlin, Android framework only. **No third-party libraries** unless there
   is a very strong reason.
4. Test on a real device when you touch the background service, the overlay or the swipe code. Emulators do
   not tell you whether Chrome Remote Desktop accepts the swipes.
5. Update the README and `CHANGELOG.md` if behaviour changes.
6. Open a pull request and describe what you changed and how you tested it.

## Licence of contributions

By contributing you agree that your contribution is licensed under the
[GNU General Public License v3.0 or later](LICENSE), the same as the rest of the project.
