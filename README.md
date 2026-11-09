# Xremote

An unofficial Android app for remotely controlling a **Fujifilm X-T3** camera from a phone.

> ⚠️ **This app was designed and written with AI.**
> The code was produced through a long conversation with Claude, Anthropic's AI assistant,
> over about 17 revisions. A human (the repository owner) wrote the requirements, tested
> the builds on a real X-T3, and decided which changes to keep. Read the code with that in
> mind: it has not been professionally audited, and it may contain bugs or things that only
> work on the hardware and firmware it was tested on.

## What it does

Xremote connects to the X-T3 over the camera's own Wi-Fi/Bluetooth remote-control
connection so you can operate the camera from an Android phone instead of using
Fujifilm's official app.

*A detailed feature list will be added once the source code is in this repository and checked.
This README only describes what has been confirmed.*

## Status

- Personal / hobby project, not affiliated with or endorsed by Fujifilm.
- Tested only on a Fujifilm X-T3. Other X-series bodies may or may not work.
- No warranty. Use at your own risk; controlling a camera over an undocumented protocol
  could behave unexpectedly.

## Building

Open the project in Android Studio and run it on a device, or build from the command line:

```bash
./gradlew assembleDebug
```

## Disclaimer

"Fujifilm" and "X-T3" are trademarks of FUJIFILM Corporation. They are used here only to
describe which camera the app works with.
