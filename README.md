# X Remote

Android remote for the Fujifilm X-T3 (and other X bodies with WiFi remote shooting): live view, a one-screen grid of every setting the camera exposes, tap-to-focus and a shutter button.

## Build and install

1. Open this folder in Android Studio (Ladybug or newer). Let Gradle sync; it downloads AGP, Kotlin and Compose.
2. On the phone: Settings > About phone > Software information, tap Build number 7 times, then enable USB debugging in Developer options.
3. Plug in the phone and press Run. Or build an APK with `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`).

## Using it

1. On the X-T3, put the shutter dial, ISO dial and aperture ring on **A** for anything you want to control from the phone. Settings on a fixed dial show as read-only (dimmed).
2. Start wireless communication on the camera so it broadcasts `FUJIFILM-X-T3-xxxx`.
3. Join that network on the phone. Ignore the "no internet" warning.
4. Open X Remote and tap Connect. The first time, accept the device on the camera with OK.

Screen layout (landscape): settings grid on the left, live view in the middle (tap it to move the focus point), shutter on the right. Tap a tile to get a strip of all values the camera allows for it.

## How it works

Fujifilm has no public API. The app speaks Fuji's reverse-engineered PTP/IP dialect, following the open-source work in fuji-cam-wifi-tool and camlib/Fudge:

| Port  | Use |
|-------|-----|
| 55740 | Commands: registration, then USB-style PTP containers |
| 55741 | Event channel, kept open and logged |
| 55742 | Live view: length-prefixed JPEG frames |

The control grid is not hardcoded. The app asks the camera which properties it has (GetDeviceInfo) and what each one allows (GetDevicePropDesc), so anything the X-T3 exposes over WiFi shows up, including codes it doesn't recognise (listed under "Other" by hex code). Known codes get friendly names and formatting in `ptp/PtpCodes.kt`.

Code layout:

- `ptp/` pure Kotlin protocol layer, no Android dependencies
- `RemoteViewModel.kt` WiFi binding, polling, live view decoding
- `ui/` Compose screens
- `tools/fake_camera.py` a simulated camera for testing without the X-T3

## First run on a real camera

This was written and tested against a simulated camera, not a real X-T3, so expect some handshake or value-format tuning. Everything the app sends and receives is logged: tap **Connection log** (or the terminal icon), then Share, and send the log back for fixes.

Likely spots to need adjustment:

- the handshake order in `FujiCamera.connect()`
- shutter speed and ISO value encodings in `KnownProps`
- focus point grid size (`tapFocus` in the ViewModel)

## Testing without the camera

```
pip install pillow
python3 tools/fake_camera.py
```

In the Android emulator, connect with camera IP `10.0.2.2`. On a real phone, put the phone and PC on the same WiFi and enter the PC's IP.

## Limits

WiFi remote mode only exposes Fuji's remote-shooting controls (exposure, ISO, WB, film simulation, focus, timer and similar). Deep menu items need USB tethering, planned as a later addition reusing the same `ptp/` layer.
