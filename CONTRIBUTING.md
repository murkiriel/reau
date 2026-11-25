# Contributing to REau

Português (pt-BR): [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md) · Español: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)

REau has a single maintainer and is a spare-time project: there is no support contract and no fixed response time. A
short, clear report or a small, tested change helps the most.

## License

REau is under the **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Anything you contribute is under the
same license. If a change brings in code, data or facts from another project, say where it comes from, so that the
[NOTICE](NOTICE) and the Credits screen (`app/src/main/java/com/reau/app/settings/Credits.kt`) can name it with its license.

## What may and may not be brought in

A contribution is an idea, or code under the AGPL-3.0 or a license that combines with it (Apache-2.0, MIT, BSD and
the like), with its origin named: the project, the file, the commit and the license, in the pull request and in the
header of the copied file. Never paste into an issue, a pull request, a test, a comment or a file:

- anything from the official Royal Enfield app: decompiled or disassembled code, its images, icons, layouts, fonts, texts and
  translations, its keys, certificates and tokens, its firmware or update files, or screenshots of it;
- code, text or images from a project that has no license (no license means all rights reserved) or whose license does
  not combine with the AGPL-3.0 (for example a non-commercial license, or GPL-2.0-only);
- anything from a leak, from a private repository, or from a source that asks not to be copied.

What is welcome from such sources is the idea, said in your own words and written as your own code, and facts that you
observed yourself on your own dash (which byte does what, and on which firmware). A pull request that carries material of
the first two kinds is closed, and the material is taken out of the history. If in doubt, ask in an issue before sending
code.

## Running the tests

With JDK 21 and the Android SDK installed, from this folder:

```
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:detekt :app:lintDebug --console=plain
```

The first line runs the unit tests; the second runs the code analysis (detekt) and the lint. Fix what
they report in the code you wrote, and do not add anything to the detekt baseline. A warning of the Kotlin compiler fails the build too (`allWarningsAsErrors`): fix it, or, for a deprecated call that cannot be avoided, put a `@Suppress` next to it that says why. Some tests read files from the
repository (`docs/`, `NOTICE`, the READMEs, the changelogs, `app/src/main/res/`): Gradle knows they read those files, so
they run again by themselves when a text changes. The instrumented tests (`app/src/androidTest`) need an
emulator, as the README says; `scripts/emu/` has the scripts.
The Python scripts have their own tests (`python3 -B -m unittest discover -p "test_*.py"` inside `scripts/`, `scripts/registro/`,
`scripts/dev/`, `scripts/emu/`, `scripts/painel_falso/` and `bench/`; the last one needs Pillow, pinned in `scripts/requirements.txt`), and the
continuous integration runs them too. It also checks their style: `ruff check .` at the root (rules in `ruff.toml`), `mypy --ignore-missing-imports *.py` inside each scripts folder and `shellcheck -S warning` on the `.sh` files; the versions of ruff and mypy are in `scripts/requirements-estilo.txt`.

The APK is audited too: `python3 -B scripts/apk_audit.py <apk>` compares the permissions, libraries, native libraries and URL hosts that an APK carries with `scripts/apk_audit_baseline.json`, and checks that a release APK has the release shape (not debuggable, no debug classes, only the listed components). The continuous integration builds the release (`-PreauDebugSigned=true`, since the repository has no signing key) and runs it; when something new is meant to enter the APK, look at what it is and accept it with `--gravar`.

Every code change comes with a new or adjusted test. The project has three languages: texts that people read go in English
(the default), Portuguese (pt-BR) and Spanish, in the `strings.xml` files of `values`, `values-pt` and `values-es`, and the docs have one file
per language. The text behind an (i) gives the general idea in at most 3 sentences and 100 words, with no abbreviation that ends in a period
(`InfoTextsTest` checks it in the three languages).

## Fuzzing and the mutation test

The parsers that read bytes from outside (the dash's packets, a browser's WebSocket frames and HTTP head, and Android Auto's messages, video units, video size header and navigation messages) have fuzz targets in
`app/src/test/java/com/reau/app/fuzz/ParserFuzzTargets.kt`, written with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
(Apache-2.0, a test dependency only: it never goes into an APK). In the normal unit-test run each target only replays its seeds, the
files under `app/src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<target>/`, as a regression test. To really fuzz, steered by
the code each input reaches, set the environment variable (20 seconds per target, `JAZZER_MAX_DURATION` changes it):

```
JAZZER_FUZZ=1 ./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTargets*' --console=plain
```

With `JAZZER_MAX_DURATION` set (for example `5m`) only the first target of a run fuzzes for that long and the others just replay their seeds: run one target at a time (`--tests '*ParserFuzzTargets.dashPacket'`). A run leaves its working corpus in `app/.cifuzz-corpus/` and, if an input broke a parser, a `crash-<hash>` file in `app/` (both ignored by
git). After fixing the bug, copy that file into the target's seed folder, so the same input is tried in every run from now on. A new
target is a method with `@FuzzTest` that takes a `ByteArray`, with a few small seeds of its own (never real captures: see "Personal data").
The unit tests run on the JUnit Platform for this (the vintage engine runs the JUnit 4 ones).

`python3 scripts/dev/mutacoes.py` is the mutation test: it changes one decision of the code at a time and checks that a unit test fails.
`--lista` counts the mutations and `--fatia I/N` runs one slice of them (the weekly workflow runs twenty-four, and `mutacao-mensal.yml` runs one of twelve each month, the month's number); a mutation that survives means a test that
does not hold that decision.

## Benchmark, managed devices and the dependency check

**Startup benchmark and baseline profile (`macrobenchmark/`).** This module builds without a device (`./gradlew :macrobenchmark:assemble`) but only RUNS on a phone with USB debugging
or on an emulator, and it measures the `benchmark` build type of the app (the release code, signed with the debug key, so it needs no release key). With a device connected:

```
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.reau.app.macrobenchmark.StartupBenchmark
```

`StartupBenchmark` measures the cold start twice, without a baseline profile and with the one of the app; the numbers are `timeToInitialDisplayMs` in the output. Accept the terms
of use on the device first, or it measures the terms screen. On an emulator add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`
(the numbers of an emulator say nothing about a phone). `BaselineProfileGenerator` (same command, with `class=com.reau.app.macrobenchmark.BaselineProfileGenerator`) records what runs while the app opens and
prints where it left the file on the device. The build does not use that file: the app's profile, `app/src/main/baseline-prof.txt`, is written by hand, and you compare the two and add by hand
what is worth adding. The `androidx.baselineprofile` Gradle plugin is not used: it would add build types and generated profile folders that overlap the hand-written one.

**Devices that Gradle manages.** `pixel6Api34` is declared in `app/build.gradle.kts` (the light `aosp-atd` image, for the instrumented tests) and in `macrobenchmark/build.gradle.kts` (the `aosp` image, which can be rooted).
Declaring them downloads nothing. The first run of one of these downloads a system image of some hundreds of MB and creates the virtual device by itself:

```
./gradlew :app:pixel6Api34DebugAndroidTest
./gradlew :macrobenchmark:pixel6Api34BenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

They need hardware virtualization (KVM). They do not use, change or delete the virtual devices you made yourself.

**A new dependency and the PGP check.** `gradle/verification-metadata.xml` checks every artifact the build downloads: by the PGP signature, against the keys in `gradle/verification-keyring.keys`,
when the publisher signs it, and by the sha256 otherwise (many artifacts of Google's Maven are not signed). A build with a dependency that is not in the file fails with
"Dependency verification failed". The key servers are off (`<key-servers enabled="false"/>`), so no build asks the network for a key; to add a dependency, set
it to `enabled="true"` for this one command and back to `"false"` before the commit (a test checks it). To add it, run, with the network and from the repository's folder:

```
./gradlew --write-verification-metadata pgp,sha256 --export-keys help testDebugUnitTest detekt lintDebug assembleDebug assembleAndroidTest assembleRelease -PreauDebugSigned=true :macrobenchmark:assemble
```

Gradle keeps the sha256 entries already in the file, adds the new ones, and adds a `trusted-key` for each key that signs the new artifacts (and exports it to the keyring). Then READ the diff: who the key belongs to
and for which group it is trusted; that no sha256 entry was removed; and that no `ignored-key` (a key that could not be downloaded) hides an artifact that had a sha256 before, which would leave it
with no check at all. Commit the metadata and the keyring together (`gradle/verification-keyring.gpg`, the binary copy that Gradle also writes, is not kept). Then run the build again with `--offline`.

## The bench panel

`scripts/painel_falso/painel_falso.py` stands in for the bike's dash on a computer, so that a real phone with REau connects and projects with
no bike. It uses only Python's standard library. It listens on the dash's UDP ports (2000 for control, 5000 for video; the answers go to the
phone's 2002), answers the greeting with an RSA-1024 public key made on the spot (the private key stays in memory and is never written),
takes the AES-256 session key, sends the dash's heartbeat, answers each video frame with the dash's notice, puts the RTP video back together
into a `.h264` file and presses the joystick from the standard input (`press left`; `help` lists the commands). What arrives, what is
answered and every protocol error go to the terminal and to a log file, both in `painel_falso_saida/` (`--out` changes it; `.log` and `.h264`
are never versioned). The route's title, the song and the caller's name are written only by their size unless `--show-text` is given: do
not share a log made with it.

The phone finds the computer when the computer is what the app looks for: a Wi-Fi access point with WPA2, a name that begins with `RE_`
(say `RE_BENCH_0001`) and the passphrase the app has for the dash (the factory one, `12345678`, unless you changed it), with the computer
at `192.168.1.1` (mask `255.255.255.0`) and a DHCP server that hands the phone an address in `192.168.1.x`. The app sends its control
packets to `192.168.1.255` and the video to `192.168.1.1`. On Linux with NetworkManager, `scripts/painel_falso/ponto_de_acesso.py` makes
that access point without taking the computer off its own Wi-Fi:

1. `python3 scripts/painel_falso/ponto_de_acesso.py check` only reads, and says whether the access point can go up here and why. The Wi-Fi
   card has to take a station and an access point at once (`iw list` shows `managed` and `AP` in one combination). Most cards that do
   allow a single channel for the two, so the access point goes up on the channel the computer's own network is on, 5 GHz included; that
   channel must not be one where nothing may start transmitting (`no IR`, radar detection), and a computer whose network is already
   `192.168.1.x` is refused. Where it cannot go up, put the computer on the internet by cable or use a USB Wi-Fi adapter, and make the
   access point described above by hand.
2. `sudo python3 scripts/painel_falso/ponto_de_acesso.py up` creates it (`--ssid` and `--password` change the name and the passphrase;
   `--dry-run` writes the commands and runs none). Underneath, it adds a virtual interface of the access-point kind (`reauap0`, with a
   random locally administered address) on the same card, and on it a NetworkManager connection in access-point mode with the "shared"
   IPv4 method, which gives the address and the DHCP server. It never calls `sudo` itself. `down` in the place of `up` takes it all away
   again, also after an `up` that stopped half-way.
3. Do what `up` says at the end: with a VPN on, turn on its "Local network sharing", or what the phone sends never reaches the panel; with
   a firewall on, let UDP in on ports 2000 and 5000 (for `ufw` it writes the command).
4. Start the panel: `python3 scripts/painel_falso/painel_falso.py --ssid RE_BENCH_0001`. With `--ssid`, a session key sent with another
   name is rejected, as the dash checks its own name; without it any name is taken. `--ver` also shows the video live, in an `ffplay`
   window for each session; without `ffplay`, or once the window is closed, the video is still written to the file.
5. On the phone, with the bike off or out of reach, forget the dash in REau (so it looks for one by its name again), pick the access point
   and connect as you would to the bike.

`--selftest` tries the panel against a minimal phone side on the computer's own loopback, with no network, and the tests are in the same
folder. The panel is written from the app's own code and answers only what that code shows the dash answering; anything else the phone sends
is only logged. It proves the phone's side of the link, not that a dash takes it: for that, see the next section. `test_sincronia.py` reads
the ports, the button codes, the packets and the exit sequence from the Kotlin sources, so a change in the app fails there until the panel follows.

## Publishing a version

Only the maintainer publishes a version, and one rule rules it: **the code of the tag is the code of the APK**. A Release APK is built from a
clean tree, at the commit that the tag `v<version>` points to, and nothing changes after the tag: a fix means a new version and a new tag.

1. Raise `appVersionName` and `releaseVersionCode` in `app/build.gradle.kts` (the comment there explains the numbering, and `VersionCodeSchemeTest` checks it).
2. Write the version's section, `## [0.3.1] - YYYY-MM-DD`, at the top of the three changelogs (`CHANGELOG.md`, `CHANGELOG.pt-br.md` and `CHANGELOG.es.md`), with
   the same entries in each: what changed for whoever uses the app, grouped as Added, Changed and Fixed, and with "not yet tried on the bike" on what was not.
3. Commit, then make the tag on that commit: `git tag -a v0.3.1 -m "REau 0.3.1"`. The script never makes the tag.
4. Set the four variables of the release key (the key is never in the repository): `REAU_KEYSTORE` (the absolute path of the file), `REAU_KEY_ALIAS`,
   `REAU_KEYSTORE_PASSWORD` and `REAU_KEY_PASSWORD`. Write the text of the Release (what the changelog section says, for someone who does not know the project) in a file.
5. Run `scripts/release.sh 0.3.1 --publicar --notas NOTES.md`. It stops at the first problem; it does these steps, in this order:

- it checks that the tree is clean and that the tag exists and points to the HEAD;
- it checks that the build's `versionName` is that version and that the section is in the three changelogs, and that the four key variables are set (it names the ones that are missing, never their values);
- it builds with `./gradlew :app:assembleRelease`, runs `apksigner verify` (the v2 signature must verify, and the certificate's SHA-256 must be `63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970`, the one every Release has had, which `ApkAttestationTest` and the install page also hold) and `scripts/apk_audit.py` (nothing new against the baseline);
- it copies the APK as `REau-0.3.1.apk`, with a `SHA256SUMS`, to `build/release-0.3.1/` (`--saida` changes the folder);
- with `--publicar` it creates the Release on GitHub as a DRAFT (`gh release create v0.3.1 --draft --verify-tag`), with those two files; without it, it only prints the command it would run, and `--so-conferir --apk FILE` only runs the checks on an APK that is already built.

What stays with the maintainer, since the script publishes nothing and pushes nothing:

- read the draft on GitHub (title, text and the two files) and publish it;
- download what was published (`gh release download v0.3.1`) and check it: `sha256sum -c SHA256SUMS`, and the certificate with `apksigner verify --print-certs`;
- the build is reproducible, and the CI proves it on every push: the job "Auditoria dos APKs" builds the release twice, the second time from scratch in another copy of the commit, and `scripts/apk_compare.py` fails if the two differ in anything but the signature (it is signed with a random salt, so it changes at every build). To check that a published APK comes from the public code, check out the tag, build it with JDK 21 (`./gradlew :app:assembleRelease -PreauDebugSigned=true`) and run `python3 scripts/apk_compare.py REau-0.3.1.apk app/build/outputs/apk/release/app-release.apk`: the two SHA-256 it prints (of each APK without its signature) must be the same;
- move `main` forward to the tag (`git merge --ff-only v0.3.1`) and push it, so the default branch shows the code that was released.

## What not to change without a test on the bike

The Android Auto protocol and the dash protocol (`aa/` and `dash/protocol/`), the dash handshake and the video path (the
encoder, the compositor, the pacing) only prove themselves on a real dash. The unit tests do not replace that: do not
change them unless you can say what you tried on the dash, with its firmware. It was tested on firmware 13.10; on another
firmware nothing is guaranteed.

## Personal data

Logs, recordings and photos may contain places you went, Wi-Fi names, phone numbers or contact names. Read through them
and cut out anything private before attaching them anywhere, and never put personal data in a test, a log sample or a
screenshot you commit. When in doubt, describe it in words.

A security problem is not reported in a public issue: follow [SECURITY.md](SECURITY.md).

## Reporting a bug

Use the bug report template: it asks for the REau version, the Android Auto version, the dash's firmware and
the phone, and tells where the app shares its logs.
