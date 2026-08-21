# Working on this app

An Android telemetry viewer for FrSky, CRSF, Ghost, LTM and MAVLink links. It
draws a flight on a map or over 3D terrain as it happens, records it, and replays
the recording afterwards.

This is the `juricabi` fork. **`main`** is the branch everything is built from and
the fork's default. `upstream` is RomanLut's repo, `jauler` the original.

## Build

JDK **17 or later**, the Android SDK with platform 35, **NDK 28.2.13676358**
(pinned in `app/build.gradle` for the native moving-lines layer) and CMake —
both installable with `sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`.

```sh
JAVA_HOME=<a JDK 17+> ANDROID_HOME=<the SDK> ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(`gradlew.bat` on Windows; the same lines otherwise.)

The unit tests are CI's gate: `./gradlew :app:testDebugUnitTest` — JUnit
and Robolectric over the decoders, the geo and altitude maths, the bus,
the replay hold and the permission funnel (192 at last count). Green
before every push.

Package ids: release `juricabi.com.telemetry`, debug
`juricabi.com.telemetry.debug`. Both install side by side, so the debug build can
be tried without losing the settings of the one being flown with.

**Moving to another machine:** the clone builds nothing until two private
files are carried over by hand — `keystore.properties` (repo root) and
`app/release.keystore`. They are gitignored on purpose and must never travel
through git; every build, debug included, signs with them, so the build stops
at configuration while they are missing. Copy them, and the rest is the
toolchain above. The field-test rig (wireless adb serials, the MEGA staging
folder for APKs and synced logs) is machine-local by nature and gets set up
fresh. Claude's accumulated project memory is also machine-local — it lives
under `~/.claude/projects/<escaped-repo-path>/memory/` and is keyed by the
repo's absolute path, so copy that folder into the matching directory on the
new machine or the assistant starts without its history.

**Do not edit the Gradle files to work around a machine that is missing
something.** Fix the machine.

## What the toolchain will not accept

- **targetSdk 28** against **compileSdk 35**, minSdk 23. The gap is deliberate:
  compiling against 35 is what the newer AndroidX libraries need, targeting it
  would drag in scoped storage for the logs, a foreground service type for
  `DataService`, and the notification permission. Do not raise targetSdk without
  doing that work — and it is not owed while this is an APK rather than a store
  listing.
- **There are no coroutines anywhere in this app**; threads and `Handler`s are
  the idiom. Kotlin is 2.0 now, so the language itself is no longer the
  constraint it was — the house style is.
- **R8 is pinned to `fullMode=false`** in `gradle.properties`, which is how AGP 4
  ran it. The release build is minified with no tests behind it. Turning full
  mode on is a deliberate, separately verified change.
- Views come from `findViewById`. `kotlin-android-extensions` is gone with
  Kotlin 2.0 — do not reintroduce synthetics, and there is no view binding
  either.
- `app/proguard-rules.pro` names `juricabi.com.telemetry.api` and
  `...proto.fr24` explicitly. Anything reached by reflection or by protobuf
  needs a rule, and its absence only shows in a release build.

## Releases

CI (`.github/workflows/build-apk.yml`) builds on every push and publishes on a
`v*` tag, attaching `telemetry-<version>.apk`.

1. bump `versionCode` and `versionName` in `app/build.gradle`
2. commit, push, then push the tag
3. write the notes afterwards with `gh release edit --notes-file` — CI leaves the
   body empty

A locally built **release** APK cannot be installed over CI's: the signing keys
differ and Android refuses it. New work reaches a phone either as the debug build
or by letting CI publish. To add commits to a release already out, move the tag
and let CI rebuild — same number, same notes, asset replaced.

Screenshots for release notes live at `docs/*.png` and are linked by
commit-pinned raw URL. Redact anything identifying — coordinates, place names,
Wi-Fi SSIDs — before publishing an image.

## Flying without a radio

`tools/simflight.py` sends the frames a Crossfire link carries, over UDP:

```sh
python tools/simflight.py --host <the phone> --port 8888 \
    --lat <..> --lon <..> --style acro --minutes 20
```

In the app: **Connect → Network → TBS Crossfire / Tracer (UDP)**, same port. The
`acro` style throws the model about; `eight` is a lazy circuit. It exercises the
map, the track, the horizon, the battery and rate readouts, and the 3D view with
the model at its true attitude.

The rig has switches for the awkward cases: `--tcp` for the TCP road, with
`--kick-file <path>` to drop the phone once when that file appears (the real
reconnect drill); `--no-name` to withhold DEVICE_INFO like a Bluetooth mirror
does (the link the manual rate-system override exists for); `--mute-file
<path>` to send nothing while that file exists (sensor greying, and the silent
resume a UDP link makes). `--protocol mavlink-hl` with `--wait-enable` plays an
ArduPilot high-latency port.

## How this gets built

These are the instructions that come up again and again here. They are not
style preferences; ignoring them has cost real work.

**Do not overengineer.** Elegant and robust, in that order, and small. A guard
against a case that cannot happen is not robustness — it is noise that the next
reader has to disprove. When a change turns out to be defending against a design
this code passed through and left behind, delete it.

**Be methodical, and do not introduce regressions.** Before changing anything
shared, find every road that reaches it: live and replay, 2D and 3D, connected
and not. Most bugs here have been state that outlived what it belonged to.

**Evidence before theory.** Instrument, reproduce, read the log, then fix. Fixes
aimed at a symptom get reverted; fixes aimed at a measured cause survive. When
several changes have piled up without curing the thing, go back to the last
commit that was known good and start again from what the log says.

**Say so when something is not logical.** A request that contradicts something
already built is worth a sentence before it is worth an implementation.

**One place for one thing.** No menu that opens a menu; no setting that lives in
two screens. If a control belongs where the thing it changes is being watched,
put it there rather than in the settings list.

**Nothing half-built.** A feature that shows an empty box when it has no data is
worse than one that is not shown at all — if there is no clock recorded, do not
draw a clock. Say why a control is unavailable instead of letting it do nothing.

**New switches default to off.** Existing behaviour does not change under
someone who has not asked for it.

**One word for one thing**, in the UI and in the code alike. Two names for the
same idea is a bug report waiting to be written.

**Back-compatibility is not owed** unless it is asked for. Do not invent
migrations for stored values or file formats on your own initiative.

**Verify on the phone, and let the person testing do the tapping.** Build,
install the debug build, say what to look at. Never drive the UI with
`adb shell input` — a stray tap goes wherever it lands, including into things
that are none of this app's business. Reading the device is fine and encouraged:
`run-as … cat shared_prefs/settings.xml` has settled more than one "is this
broken?" without a single tap.

**Audit before a release**, as separate passes rather than one vague look:
parity, simplicity, robustness, correctness. Then fix what was found and say what
was left, and why.

**Release in place.** Adding to something already published means moving the tag
and letting CI rebuild it — same number, same notes. Older releases stay up.

## Where things live

`CONTEXT.md` names every module; start there. The short of it: tiles,
their icons and their greying are TelemetryPanel's; video is VideoPane
and the sources; choosing a link is ConnectFlow, while connecting and
the reconnect policy stay in MapsActivity; the log list and its dialogs
are LogManager's; replay resume/autostart authority is ReplayHold; the
overlays both views draw are FlightOverlays. The bus forwards
structurally — a new `DataDecoder.Listener` callback is added to
ForwardingListener AND MulticastListener, and the compiler holds the
door until both have it. A new telemetry value touches a decoder and the
panel, nothing else.

## Before changing how a flight is drawn

**One flight, two views.** The map and the 3D view are two ways of watching the
same flight, and every control on one belongs to the other: following, chase,
model type, colours, the clock, the operator's arrow. A change to either that
cannot be made in both is a change that will be reported as a bug.

**Live and replay take different roads to the same place.** Live data arrives a
fix at a time; a replay hands over whole stretches from `LogPlayer.seek`. Both
end at `onGPSData`, and the replay's stretches are gathered and drawn once, when
the seek ends — see `drawGathered`. Anything that draws per fix will be run
tens of thousands of times by a replay if it is put on the wrong road.

**Which thread.** Every seek runs on the UI thread, including the ones a running
replay causes, so nothing in that path needs locking. The renderer runs on the GL
thread and reads volatile fields the UI thread writes. Terrain loads on its own
threads and publishes tiles by posting. `DataService` owns the only location
listener there is, because it outlives the screen and the recording needs a
position while the screen is away.

**Comments say why, not what.** The ones already here record what went wrong and
what it cost. Keep that — a comment explaining a line that could be read from the
line is noise, and one explaining why a line is not the obvious thing is the most
valuable text in the file.

## Agent skills

### Issue tracker

Issues live as local markdown under `.scratch/<feature>/` in this repo. See
`docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, each label string equal to its name. See
`docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root, ADRs in `docs/adr/`. See
`docs/agents/domain.md`.
