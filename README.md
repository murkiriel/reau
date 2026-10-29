# REau

Português (pt-BR): [README.pt-br.md](README.pt-br.md) · Español: [README.es.md](README.es.md)

Android app that brings the **phone's own Android Auto** to the motorcycle's TFT dash, over the dash's Wi-Fi, with the
phone screen off. REau draws no map of its own: it repeats on the dash what the navigation app (Waze, Google Maps)
draws in Android Auto.

> **Compatibility:** works with the **Tripper Dash**. REau is an independent project, with no connection to Royal
> Enfield, and is neither endorsed nor maintained by it.

## How it works

```
Navigation app ──▶ the phone's Android Auto ──(H.264 video over 127.0.0.1)──▶ REau
                                                                              │ decodes, crops, scales down
                                                                              ▼
                                                           526×300 encoder ──RTP──▶ motorcycle dash
```

REau presents itself to Android Auto as a car's head unit, using the head unit server that Android Auto itself offers
in developer mode. It receives the video, fits the picture to the dash's screen and sends it over the dash's Wi-Fi.

## What it does

- Shows the Android Auto screen on the dash, with an adjustable fit (one for the navigation apps and one for the other screens,
  the apps menu included).
- Sends the dash the maneuver, the distance, the arrival time and the time left on the route.
- Also sends the music that is playing (when the joystick page has a music control), calls and the weather, for the dash's cards.
- Uses the handlebar joystick to control Android Auto (map, menu, music, calls and Gemini, which comes on and works once you allow the microphone): while ringing, left answers and right or
  center decline; during a call, center hangs up and up and down change the volume.
- An info ring over the picture, on the right of the dash (on by default, with a switch): rain, wind, storms, UV, air quality, the sun, the day's temperatures and the phone's battery, signal and GPS, one at a time. It needs the location and the internet for the weather.
- Speaks Portuguese, English and Spanish: it follows the phone's language, and the settings have a switch.
- Adjusts the video quality by the distance to the next maneuver and by speed.
- Starts from a long press on REau's icon ("Turn on") or from a Quick Settings tile, so it can be switched on at home before the helmet.
- Optional: shows Android Auto in a browser on the local network, protected by a PIN.
- Keeps a diagnostic log in the app's internal storage (on by default, with a switch in the settings), to analyze on the
  computer (`scripts/registro/`); nothing leaves the phone unless you share it. Recording the video and the sessions is optional
  (Settings > Debug): off by default in the app you install to ride and on in the debug build; the recordings stay on the phone, go by the
  same days and size cap as the log, and hold the map, the route and Android Auto's messages.

## Requirements

- A phone with Android 10 or newer and **Android Auto** installed.
- Android Auto's developer mode on, with the "head unit server". In the full edition, REau opens that screen and turns the server on by
  itself when the app's accessibility is active; in the Lite, REau opens that screen too (once for each time you turn it on, with REau on screen) and you turn it on by hand.
- On Android 13 or newer, to turn on the accessibility service (full edition) you first have to allow it in Settings › Apps › REau ›
  ⋮ › "Allow restricted settings": Android asks for this of any app installed from outside the Play Store, the published APK
  included.
- A compatible dash, with its Wi-Fi in range.

## Two editions

- **Full** (`com.reau.app`): the usual one. It uses an Android accessibility service to turn Android Auto's server on by itself
  and to know from Android Auto which screen is showing (to switch between the navigation fit and the others' fit).
- **Lite** (`com.reau.app.lite`, "REau Lite", **the recommended one**): **no** accessibility service. With Android Auto 17.4 or newer you turn the head unit server on by hand (it turns off when the session ends, so you turn it on again every time); with 17.3 or older, REau asks Android Auto to connect and there is nothing to turn on.
  It tells which screen is showing from the picture itself, to switch the fit.

**Which one to use: the Lite is the recommended edition.** It asks for no Accessibility service, so it never gets broad control of
the phone and does not clash with apps that refuse to run while Accessibility is on (banking apps, for example). The price is one
manual step each time: you turn on Android Auto's head unit server yourself. Choose the full edition only if you want REau to do
that step by itself and to learn the screen from Android Auto instead of from the picture.

Both install side by side on the same phone, but they must not run at the same time (they compete for the dash).

## Build

With JDK 21 and the Android SDK installed:

```
./gradlew assembleRelease               # both APKs: app/build/outputs/apk/completa/release/ and .../lite/release/
./gradlew assembleCompletaRelease       # the full edition only (com.reau.app); assembleLiteRelease: the Lite only
./gradlew :app:testDebugUnitTest        # unit tests
```

The instrumented tests (`app/src/androidTest`) run on an emulator, against a fake Android Auto and a fake dash.

An APK is published on the project's Releases page; to build your own instead, Gradle has to find the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`), and, because the repository does not carry the project's signing key, `assembleRelease` signs with Android's debug key only when you ask for it with `-PreauDebugSigned=true`. The official Releases are signed with the project's key, kept outside the repository; without the environment variables `REAU_KEYSTORE` (the absolute path of the key file), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` and `REAU_KEY_PASSWORD`, packaging a release stops with an error.

## Privacy

REau has no account, no ads and no data collection. The **"REau and your data"** screen, inside the app, says what
stays on the phone and what leaves it, and why. What leaves the phone goes to the dash and, only with the optional network
server on, to other devices on your local network; the only trips to the internet are the weather (Open-Meteo, for the dash or the info ring: on by default, but it asks nothing until you allow the location, and it has a switch) and the “Check for updates” button in Settings, which asks GitHub only when you tap it.

## Safety

Do not handle the phone while riding. Use the handlebar joystick, and only when the motorcycle is in a safe condition.

## Changelog

Every notable change is listed in [CHANGELOG.md](CHANGELOG.md) (in Portuguese: [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md); in Spanish: [CHANGELOG.es.md](CHANGELOG.es.md)).

## Terms of use

The app asks you to accept the [terms of use](TERMS_OF_USE.md) the first time it opens (in Portuguese: [TERMS_OF_USE.pt-br.md](TERMS_OF_USE.pt-br.md); in Spanish: [TERMS_OF_USE.es.md](TERMS_OF_USE.es.md)); they sit at the end of the **"REau and your data"** screen, next to the credits.

## Contributing and security

REau is a spare-time project with a single maintainer. How to report a bug, run the tests and send a change is in [CONTRIBUTING.md](CONTRIBUTING.md) (in Portuguese: [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md); in Spanish: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)). A security problem is not reported in a public issue: see [SECURITY.md](SECURITY.md).

## License and credits

REau is free software under the **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). It incorporates code
from other open projects; the [NOTICE](NOTICE) lists each one, with its license and what was used, and the app shows
the same credits on the **Credits** screen, at the end of **"REau and your data"**, where the address of REau's own
source code is also given (the AGPL asks that the app's users can get it).
