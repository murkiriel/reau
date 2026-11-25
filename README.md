# REau

Português (pt-BR): [README.pt-br.md](README.pt-br.md) · Español: [README.es.md](README.es.md)

Android app that brings the **phone's own Android Auto** to the motorcycle's TFT dash, over the dash's Wi-Fi, with the
phone screen off. REau draws no map of its own: it repeats on the dash what the navigation app (Waze, Google Maps)
draws in Android Auto.

> **Compatibility:** works with the **Tripper Dash**; it was verified only with the dash's firmware 13.10, and on
> another firmware nothing is guaranteed. REau is an independent project, not affiliated with or endorsed by Royal
> Enfield, Google, Waze or Meta. Royal Enfield, Tripper, Android Auto and Waze belong to their owners.

## How it works

```
Navigation app ──▶ the phone's Android Auto ──(H.264 video over 127.0.0.1)──▶ REau
                                                                              │ decodes, crops, scales down
                                                                              ▼
                                                           528×304 encoder ──RTP──▶ motorcycle dash
```

REau presents itself to Android Auto as a car's head unit, using the head unit server that Android Auto itself offers
in developer mode. It receives the video, fits the picture to the dash's screen and sends it over the dash's Wi-Fi.

## What it does

- Shows the Android Auto screen on the dash, with an adjustable fit (one for the navigation apps and one for the other screens,
  the apps menu included).
- Sends the dash the maneuver, the distance, the arrival time and the time left on the route.
- Also sends the music that is playing (when the joystick page has a music control), calls and the weather, for the dash's cards.
- Uses the handlebar joystick to control Android Auto (map, menu, music, calls and Gemini, which comes on and works once you allow the microphone): while ringing, left answers and right or
  center decline; during a call, center hangs up, right raises the volume and left lowers it, and up and down do nothing (with the call notice on, the default). Its taps can also be "Volume up" and "Volume down", "Switch map and music", "Open map" and "Open music" (none is set by default), and the dash's title shows the name of each action for 1.5 s ("Action name on the dash", on by default); not yet tried on the bike.
- An info ring (on by default, with a switch): rain, wind, storms, UV, air quality, the sun, the day's temperatures and the phone's battery, signal and GPS, one at a time (every card at first; you switch off the ones you don't want). By default it is written in the title of the dash's gold band ("Info in the title (instead of the ring)"); with that switched off, it is drawn over the picture, on the right of the dash. It needs the location and the internet for the weather; the weather screen also shows the forecast, with the rain in millimeters (not yet tried on the bike).
- Music with Spotify: with Spotify in front and playing, the joystick's left, right and center are previous, next and play/pause, and the dash's gold band shows the song, the artist and the three buttons (not yet tried on the bike).
- A fit assistant (Android Auto screen > "Fit assistant"; it opens by itself the first time): it helps a new phone check that the maneuver arrow and the speed show whole on the dash. It was not tried on other phones yet.
- Notices for the rider on the phone and on the dash (Wi-Fi off, phone hot, dash Wi-Fi hung, dash not responding, another app using the dash connection, waiting for Android Auto) and a Tips page; the notices for a dash that stops responding and for another app on the dash connection were not yet tried on the bike. Spoken alerts in the headset are optional.
- A volume notice at the top of the dash's video (on by default, with a switch): a small pill with the music or call volume whenever it changes, from the phone, a Bluetooth intercom or the joystick, in place of the volume in the title; after a tap of "Volume up" or "Volume down" on the joystick, for 5 s it takes only those two actions.
- A call notice at the top of the dash's video (on by default, with a switch): a pill with who is calling, the joystick's hints and the time of the call, in place of the dash's own call screen, which covers almost all of the video; with it on, during a call right and left are the call's volume and up and down do nothing.
- Speaks Portuguese, English and Spanish: it follows the phone's language, and the settings have a switch.
- For left-hand traffic, "Driving side" (Automatic, Right-hand or Left-hand) tells the dash which way to turn where Android Auto does not say (not yet tried on the bike); and "Announce as a motorcycle" (on by default) tells Android Auto that REau is a motorcycle's head unit, so the first time it may ask for the phone's permissions again.
- Adjusts the video quality by the distance to the next maneuver and by speed, at the pace you choose: Original (the factory one, 4 and 2 fps, the default) or REau (6 and 3 fps, smoother). The pace is chosen in Settings > Video profiles > "Video pace". The picture is a fixed 528×304 at 400 kbps at most, with a keyframe every second, and REau never goes above 6 fps: in tests on the bike, above that the dash began to fail.
- Starts from a long press on REau's icon ("Turn on") or from a Quick Settings tile, so it can be switched on at home before the helmet (only with the phone unlocked).
- Optional: shows Android Auto in a browser on the local network, protected by a PIN.
- Keeps a diagnostic log in the app's internal storage (on by default, with a switch in the settings), to analyze on the
  computer (`scripts/registro/`); nothing leaves the phone unless you share it. Recording the video and the sessions is optional
  (Settings > Debug): off by default in the app you install to ride and on in the debug build; the recordings stay on the phone, go by the
  same days and size cap as the log, and hold the map, the route and Android Auto's messages.

## Requirements

- A phone with Android 10 or newer and **Android Auto** installed.
- Android Auto's developer mode on, with the "head unit server". REau opens that screen (once for each time you turn it on, with REau on screen) and you turn the server on by hand: with Android Auto 17.4 or newer it turns off when the session ends, so you turn it on again every time; with 17.3 or older, REau asks Android Auto to connect and there is nothing to turn on.
- A compatible dash, with its Wi-Fi in range.

## Installing and updating

Install the published APK, `REau-0.3.1.apk` (application id `com.reau.app`, name "REau"). It is a single app: REau tells which Android Auto screen is showing (map, menu, apps) from the picture itself, with a small neural network that runs on the phone.

Coming from the old REau Lite (package `com.reau.app.lite`)? REau 0.3.0 is a new app for you: redo the settings and the pairing with the dash, then uninstall the old Lite. Coming from the earlier REau (package `com.reau.app`)? Just update over it: settings and pairing stay. Two installations together compete for Android Auto's single server, which takes one session at a time.

The first time, "Turn on" asks for the permissions REau still needs, and the projection starts whatever the answer; the **Permissions** screen lists them all, with a button to ask for the missing ones and another that opens REau's permissions in Android. Once a day, with the internet on, REau asks GitHub for the latest version number and shows one quiet notification per new version (Settings > Updates > "Notify when there is an update", on by default; nothing is downloaded or installed; not yet tried on the bike). To check the APK you downloaded, compare the SHA-256 fingerprint of its signing certificate with the project's: the command and the fingerprint are on the site's install page.

A test (debug) build and the official one cannot replace each other: to change from one to the other, uninstall the first (after sharing its logs in Settings > Debug, because uninstalling erases the app's data). The step by step, with the permissions to allow and the FAQ, is on the site: <https://murkiriel.github.io/reau/en/>.

Tip: Obtainium (a free third-party app) installs and updates REau straight from the repository's Releases: under "Add app", paste `https://github.com/Murkiriel/reau`; only published versions count, not test builds, and REau changes nothing because of it.

## Build

With JDK 21 and the Android SDK installed:

```
./gradlew assembleRelease               # the APK: app/build/outputs/apk/release/
./gradlew assembleDebug                 # the debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # unit tests
```

The instrumented tests (`app/src/androidTest`) run on an emulator, against a fake Android Auto and a fake dash.

An APK is published on the project's Releases page; to build your own instead, Gradle has to find the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`), and, because the repository does not carry the project's signing key, `assembleRelease` signs with Android's debug key only when you ask for it with `-PreauDebugSigned=true`. The official Releases are signed with the project's key, kept outside the repository; without the environment variables `REAU_KEYSTORE` (the absolute path of the key file), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` and `REAU_KEY_PASSWORD`, packaging a release stops with an error.

## Privacy

REau has no account, no ads and no data collection. The **"REau and your data"** screen, inside the app, says what
stays on the phone and what leaves it, and why. What leaves the phone goes to the dash and, only with the optional network
server on, to other devices on your local network; the only trips to the internet are the weather (Open-Meteo, for the dash and for the info ring, both on by default: nothing is asked until you allow the location, and each has a switch) and the update check in Settings (the “Check now” button and, once a day by itself, the “Notify when there is an update” switch, on by default), which asks GitHub only for the latest version number. The location permission also lets Android give REau the list of Wi-Fi networks, to see the dash's, while the app is behind another one (Android Auto, Waze) or the screen is off; REau reads no position for that, and Android may show the location icon during the projection.

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
