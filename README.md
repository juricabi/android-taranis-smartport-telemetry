# Android Telemetry Viewer 2.5.1

Live and recorded RC telemetry on a smooth 2D map or real 3D terrain.

This is [juricabi's fork](https://github.com/juricabi/android-taranis-smartport-telemetry).
It supports FrSky S.PORT, CRSF/Tracer/ExpressLRS, Ghost, LTM and MAVLink 1/2 over
Bluetooth, BLE, USB serial and network connections.

**Download:** [GitHub Releases](https://github.com/juricabi/android-taranis-smartport-telemetry/releases)

<p align="center">
  <img src="docs/flight-3d.jpg" width="360" alt="3D terrain flight view">
</p>

## Current state

Version 2.5.1: live video beside the map — USB goggles and receivers,
RTSP, MJPEG, and raw RTP pushed at the phone, each flown until it
behaved — and the drone's GPS republished as the phone's own position
for tracker apps. On the 2.4 ground and map, with the flight ending when
you say so and one camera across both views.

- **Every stream finds its own way out**, so one phone setting covers a
  flying day: telemetry and video ride whichever network reaches the
  module — the goggles' Wi-Fi, this phone's hotspot, a USB-ethernet
  adapter — while the maps keep loading over mobile data. RTSP carries
  its video over TCP or UDP by a switch beside the address, because a
  goggle that stalls on its own keep-alive needs one and a weak link
  needs the other.
- **Nothing is drawn from a position the receiver has not fixed yet.**
  A receiver still hunting satellites forwards where it last was, which
  could be a continent away; both views wait, and open on you instead.
- **Live video in half the screen**, the flight in the other: a USB (UVC)
  receiver or goggles over OTG — UVC 1.5 action cameras included — or a
  network stream: RTSP, MJPEG, or raw RTP at a port (H.264/H.265). The
  seam drags and is remembered per orientation; latency is treated as the
  point — 150 ms RTSP buffers, newest-frame MJPEG, no buffer at all on
  the raw path. A stream that cannot connect keeps its pane, says why,
  and retries; refusals name the reason. See **Live video** below.
- **The drone's GPS as the phone's position**, opt-in: a tracker app on
  the phone broadcasts the drone instead of the pilot while telemetry is
  connected. See below.
- 3D terrain is a quadtree of web-mercator tiles — the Google Earth and
  Cesium design — metre-sharp under the model, coarse where it can afford
  to be, with morphed levels and dissolving pictures.
- The nearest missing ground loads first and shows the moment it is
  finished; shaded relief gives the mountains' shape in the first second.
- The world follows the flight: a flight far from the phone re-anchors
  the 3D world to itself.
- Switching views parks the 3D world instead of destroying it; switching
  back takes seconds. About three gigabytes of ground persist on disk.
- The 2D map loads everywhere again: the location arrows are ordinary map
  symbols now, whose old native layer stopped tiles on some devices.
- Memory-stable by audit: every queue and cache bounded, the heap idles
  near a third of what it used to hold.
- A flight ends when you say so: Disconnect clears it — a link that drops
  keeps everything for walking to a downed model — and nothing is thrown
  away when nothing was recorded. The camera stays where you left it; only
  a flight far from where you stand rebuilds the 3D world back at you.
- The bottom row names the protocol the link speaks — FrSky, CRSF, GHST,
  LTM, MAV v1, MAV v2, MAV HL, and CRSF+AP when an autopilot is talking
  over the link. Off by default, in Sensor display settings.
- ArduPilot passthrough over CRSF/ExpressLRS and MAVLink High Latency
  carry full telemetry over plain ELRS or satellite/LoRa-class links.
- Every protocol decoder is covered by regression tests, longitude
  included: the maths crosses the 180th meridian and is held up by tests
  that fail on the old arithmetic.
- The 3D ground no longer hangs a dark wall while it loads: that shelf was
  a coarse tile's skirt, drawn down edges where the finer ground below had
  already risen to meet it. Skirts now hang only where a crack can open.
- One camera contract across both views, live or replayed: a flight is
  framed the moment it appears in every mode, a replay opens in the mode
  last chosen, the mode is remembered between runs, and with no mode
  keeping up zoom reaches the ground and pan keeps pace. A dropped link's
  reconnect lets go the moment you connect or open a replay.

## Supported telemetry

| Protocol | Main data |
|---|---|
| FrSky S.PORT | GPS, altitude, vario, airspeed, battery, current and sensors |
| CRSF / Crossfire / Tracer / ExpressLRS | GPS, attitude, flight mode, battery, RC and link statistics |
| Ghost (GHST) | GPS, battery and Ghost link statistics/profile |
| LTM | GPS, attitude, status and battery |
| MAVLink 1 and 2 | GPS, global position, attitude, battery, radio, flight mode and status text |
| ArduPilot passthrough over CRSF | flight mode and armed state, GPS status, battery, home distance and direction, velocity, attitude, throttle and status texts (`RC_OPTIONS += 256`, receiver serial on protocol 23) |
| MAVLink High Latency | HIGH_LATENCY2 — position, altitude, heading, speeds, throttle, battery, mode and armed state, one 42-byte message per five seconds (`SERIALn_PROTOCOL = 43`) |

Protocol detection is automatic and latches after the first valid match. The
High Latency preset pins MAVLink 2 instead, since detection would spend ten
seconds waiting for a second frame. Ghost
does not provide attitude, flight mode, vario or airspeed; those fields stay
empty by design.

For Ghost telemetry mirror use EdgeTX with the fix from
[#7610](https://github.com/EdgeTX/edgetx/pull/7610), set the serial port to
**Telemetry mirror**, and use 115200 baud.

## Connections

- Classic Bluetooth serial modules
- BLE telemetry modules
- USB VCP/serial with an OTG connection
- TCP client or server
- UDP listener/broadcast
- TBS Crossfire Wi-Fi WebSocket (`/ws`)

Network presets cover ExpressLRS backpack, Crossfire/Tracer, MAVLink routers,
serial-to-Wi-Fi bridges, localhost and MAVLink High Latency. The High Latency
preset listens over UDP and sends `MAV_CMD_CONTROL_HIGH_LATENCY` to the typed
modem address until the stream answers, since an autopilot boots with that
stream off; sensor timeouts stretch to match the five-second cadence.

Telemetry and video streams are pinned to whichever network actually routes
to their target — Wi-Fi, this phone's own hotspot, a USB-ethernet adapter —
so a module's access point without internet does not lose to mobile data.
Internet traffic is never captured by the pin and keeps to the phone's
preferred network. In practice that means one phone setting covers
everything: set this app to **mobile data preferred** (Android's per-app
allowed-networks setting) and the goggle or VRX link still carries its
streams while the map tiles stay online over mobile. The pin works on a
numeric local address (`192.168.1.1`, the module's own IP); it does not
resolve `.local` / mDNS names, so dial modules by IP — the **Find** button
in the network dialog scans the subnet and fills the address in for you.

## Maps, 3D and replay

Map types: OpenStreetMap, OpenTopoMap, satellite, satellite with streets, and 3D
terrain. No API key is required. Tiles are cached as they are used; the 3D
ground keeps about three gigabytes of imagery and heights on disk, so a
revisited region loads from storage rather than the network.

Tracking follows position; chase also follows heading. Both use the same eased
model state in 2D and 3D. Manual gestures remain available while tracking.

Recordings replay at real time or a chosen speed, 3× slower to 10× faster. When
CSV companion data exists, replay restores the original phone position,
accuracy, heading and clock.
The altitude profile compares the flight with terrain and marks minimum clearance.

Altitude notes:

- A dedicated barometric altitude is preferred when the protocol supplies one.
- With only CRSF GPS altitude, the normal and GPS altitude fields may show the
  same value; they are receiving the same valid source.
- The app decides per flight whether reported height is MSL or above launch and
  applies one shared terrain reference to 2D, 3D and the altitude profile.

Flight plans are CSV files containing one `latitude,longitude` pair per line.
They persist, can be toggled individually, and appear in both map views.

Nearby aircraft come from FlightRadar24 and are off by default. Traffic is
measured from whatever there is to be near, and the warning says which:

- **While the model is drawn** — from the model. That is the same test the
  marker itself uses: a place, and a fix to believe it by. A dropped link
  keeps the model on screen, so warnings go on from where it was last seen —
  it may still be airborne.
- **Whenever it is not** — from the phone. Standing at the field between
  packs, or with the receiver still hunting for satellites, is when an
  aircraft crossing overhead matters most, and the warnings used to stop
  exactly there.
- **A replay is open** — nothing at all. Those are today's aircraft over a
  place the replay is not, so the sky is left out of it.

## Using the app

1. Pair or attach the telemetry device, or join its Wi-Fi network.
2. Tap **Connect** and choose Bluetooth, BLE, USB or Network.
3. For a network stream, select the matching preset and port.
4. Use the map-type button for 2D or 3D; use tracking or chase as required.

### Live video

Pick a source under **Settings → Video** — a USB (UVC) receiver or goggles
plugged in over OTG, or a network stream — and a video button appears in the
top bar. It splits the pane in half — picture beside the map, left of it in
landscape and above it in portrait — the flight overlays keep to the map's
half, and the picture carries its own sound, fill and quarter-turn buttons.
The network address says what the stream is:

- **`rtsp://…`** — RTSP, H.264 through the phone's hardware decoder.
  IP cameras, mediamtx/go2rtc relays, the IP Webcam app's RTSP mode.
  **Orqa FPV.Connect** broadcasts here too: join the goggles' own WiFi
  and enter `rtsp://192.168.1.1:5004/orqabroadcast`. The video rides TCP
  by default, which never tears a frame on a lossy link; the **RTSP over
  UDP** switch beside the address is lower latency and keeps the video
  off the control channel, which some goggles need — theirs stall on
  their own keep-alive otherwise — at the cost of a smeared frame when a
  packet drops. A clean direct link wants UDP, a weak one TCP. H.265
  over RTSP does not play — the player library's H.265 RTP reader is
  unfinished upstream — send H.265 as a raw `udp://` push instead,
  where it works.
- **`http://…`** — MJPEG, the compatible end: ESP32-CAM, mjpg-streamer,
  the IP Webcam app's `/video` path.
- **`udp://5600`** — a raw pushed stream: RTP with H.264 or H.265 aimed
  at this phone's address, the way **OpenIPC / wfb-ng** ground stations
  and other QGroundControl-style senders work. There is nothing to dial —
  the app listens on the port, reads the codec off the stream itself and
  renders every frame the moment it decodes, with no buffer in the way.
  Point the sender at the phone's IP, port 5600 (or whichever port the
  address names).
- **USB (UVC)** — analog OTG receivers (ROTG02 and kin), DJI, Walksnail
  and Orqa goggles in their webcam modes, action cameras as webcams —
  including UVC 1.5 devices such as the DJI Osmo Action series, which
  stock Android UVC libraries refuse.

Every path above is field-tested. To try the `udp://` path with no
hardware at all, ffmpeg can play the sender — from a PC on the same WiFi,
or from Termux on the phone itself with `127.0.0.1`:

```sh
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -pix_fmt yuv420p \
    -c:v libx264 -preset ultrafast -tune zerolatency \
    -x264opts keyint=30:repeat-headers=1 -f rtp rtp://<phone>:5600
```

For H.265 swap in `-c:v libx265` with
`-x265-params keyint=30:repeat-headers=1`. The `-pix_fmt yuv420p` is not
decoration: left out, ffmpeg encodes the RGB test pattern as 4:4:4, which
no phone hardware decodes — the app refuses such a stream by name rather
than failing on a bare error code.

The picture wears its own controls on its top-right: the speaker where the
stream could carry sound, **fill** to crop the picture over the whole half
instead of letterboxing it, and a **quarter-turn** per tap for a camera
mounted sideways — fill and turn are remembered across sessions. The flight
overlays — horizon, clock, compass, the button column, the seek bar — keep
to the map's half and scale with it, so nothing ever stands over the
picture.

The reliability work sits where field flying found the holes: RTSP rides
TCP from the first frame, because a UDP first contact smeared the opening
second of every new address; an unreachable network stream keeps its pane
and retries every two seconds instead of folding; a stalled or starved
stream rejoins itself; a picture drifting
behind the camera's clock jumps back to the live edge; MJPEG decodes only
as many pixels as the pane can show and reuses its frame memory, so the
collector never fights the decoder; and every source starts on a fresh
surface, so switching between MJPEG and RTSP mid-session cannot poison the
hardware decoder. RTSP starts as picture alone so it is on screen at once;
the speaker button joins the stream's sound, and a stream whose advertised
audio never arrives drops back to picture by itself.

### Drone GPS as phone location

**Settings → Mock location** republishes the decoded drone GPS as this
phone's own position while telemetry is connected, so a tracker app on the
phone (Overland, PureTrack) broadcasts the drone instead of the phone —
position, altitude and speed, at the rate the link delivers them. The app
must be picked once under Developer options → Select mock location app;
the settings row walks through it and the service notification says when the
drone's position is live. The phone's own GPS is back the moment telemetry
disconnects or the switch is turned off.

The published altitude speaks both of Android's languages: the raw field
carries height above the WGS84 ellipsoid — lifted from sea level with the
system's own geoid model, so it matches what a real GPS fix reports — and
on Android 14+ the MSL field carries the drone's number outright. It is
only as absolute as the firmware makes it: ArduPilot and current
Betaflight send true sea-level altitude, but iNav over CRSF — and the
Betaflight releases still flying everywhere, once armed — put height
above the launch point in the same field; over MAVLink or S.Port the
absolute one comes through. The 3D view detects the difference against
the terrain under the flight; the published phone position carries what
the link said.

### How altitude works

A flight controller reports height in one of two languages. ArduPilot,
current Betaflight, and every link over S.Port or MAVLink say metres
above the sea — a number that stands on its own. iNav over CRSF, and
older Betaflight once armed, say metres above the launch point — a
number that means nothing until you know how high the launch was. iNav
over LTM never carries an absolute height directly — but its origin
frame broadcasts where home is and how high it stands, and home's own
altitude turns the relative heights into absolute ones, mid-air joins
included, straight off the wire.

The app tells the two apart with one rule: **a flight cannot fly below
the ground.** It compares the reported heights against the terrain under
the flight; if they dive well below it, they are measured from the
launch, and the flight is raised by the deepest such burial — for a
normal take-off that is exactly the ground under the launch point. One
wild reading cannot move the flight (a lift needs at least two distinct
reports behind it), the answer can only grow more certain during a
flight, never flip back, and it is worked out once and shared by
everything: the 3D view, the altitude profile, the "above MSL" readout,
and the position handed to tracker apps.

Heights sent while disarmed are left out of the drawing — the craft is
standing on ground the terrain already shows, and old Betaflight changes
what its numbers mean at the moment of arming. A replay carries
everything the answer needs, so a recording always draws exactly as the
flight did. A deliberate disconnect starts the question fresh; a
reconnect after a dropped link continues the flight, its log, and its
answer as one. And where nothing proves the heights are launch-relative,
they pass through untouched — a firmware that says sea level is believed
to the metre.

### Simulator

`tools/simflight.py` sends a realistic CRSF flight over UDP:

```sh
python tools/simflight.py --host <phone-ip> --port 8888 \
  --lat <latitude> --lon <longitude> --ground <metres-msl> \
  --style eight --minutes 20
```

In the app choose **Network → TBS Crossfire / Tracer (UDP)** on port 8888.
Use `--style acro` for faster turns, climbs and dives, or `--above-launch` to
exercise relative-altitude handling. `--passthrough` weaves ArduPilot
passthrough frames into the CRSF stream. `--protocol mavlink-hl` sends
HIGH_LATENCY2 instead; add `--wait-enable` and it behaves like a real
autopilot port — silent until the app's enable command arrives, streaming to
whoever asked, stopping when asked to (use the **MAVLink High Latency (UDP)**
preset with the PC's address).

## Building and testing

Current toolchain:

- JDK 21
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.2.10
- compileSdk 35, minSdk 23, targetSdk 28
- NDK 28.2.13676358
- MapLibre 13.4.1
- ARM64 and ARMv7 APKs

Create `local.properties` with the Android SDK path and `keystore.properties`
from `example_keystore.properties`. The Android Gradle plugin installs the NDK
version declared by the app when it is available through the SDK manager.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Debug package: `juricabi.com.telemetry.debug`. Release package:
`juricabi.com.telemetry`. They install side by side.

A `v*` tag triggers `.github/workflows/build-apk.yml`, which builds and attaches
the signed release APK. The release build is the normal distributable artifact.

## Development map

| Area | Main files |
|---|---|
| Screen and frame loop | `ui/MapsActivity.kt` |
| 2D map/camera | `maps/maplibre/MapLibreMapWrapper.kt` |
| Synchronized 2D moving scene | `maps/maplibre/MapLibreMovingLines.kt`, `cpp/moving_lines.cpp` |
| 3D view | `ui/Terrain3DView.kt`, `gl/TerrainRenderer.kt`, `gl/TerrainScene.kt` |
| Connection ownership | `service/DataService.kt`, `protocol/pollers/` |
| Protocols | `protocol/`, `protocol/decoder/` |
| Replay and logs | `protocol/pollers/LogPlayer.kt`, `logger/` |

When adding a sensor, carry it through the complete path: protocol constant and
parser, decoder listener, service forwarding, timeout state, screen view,
logger/replay, preferences/layouts, then a decoder regression test. Keep moving
2D geometry on the shared custom render scene; do not update a GeoJSON source at
frame rate.

## Hardware notes

Built-in radio Bluetooth, telemetry mirror over USB-C, or a network-capable TX
module needs no extra inverter.

For a raw FrSky S.PORT pin use an inverter and an HC-05/HC-06/HM-10-class serial
module at **57600 baud**. Classic Bluetooth modules must be paired in Android
first. Ghost telemetry mirror uses **115200 baud**.

![Connection example](connection.jpg)

## Known limits

- Android only; Android 6 or newer on ARMv7/ARM64.
- `targetSdk 28` is intentional for this sideloaded APK. A Play Store release
  needs a separate scoped-storage, notification and foreground-service migration.
- Map caching is opportunistic; there is no offline-area downloader yet.
- Live video (Settings → Video) is a USB UVC receiver or goggles over OTG, or
  a network stream — RTSP at roughly half-a-second ground-station latency, or
  MJPEG over HTTP; it is not a sub-150 ms FPV feed.
- Network transports and RTSP recovery have been flown against real hardware;
  Bluetooth reconnection has not been made to happen in the air yet — a
  flight's worth of link held without one — so it rests on the bench and the
  simulator, which test the network and decoder path.
- `.local` / mDNS names are not resolved: modules are dialled by IP, which the
  network dialog's **Find** button will search out.

## Lineage

- [CrazyDude1994](https://github.com/CrazyDude1994/android-taranis-smartport-telemetry): original app
- [RomanLut](https://github.com/RomanLut/android-taranis-smartport-telemetry): sensors, protocols, exports and earlier map work
- [Jauler](https://github.com/Jauler/android-taranis-smartport-telemetry): FlightRadar24 and layout work
- This fork: Ghost, network telemetry, MapLibre 2D, 3D terrain, replay/altitude work and current reliability fixes

Also see the [privacy policy](privacy_policy.md) and
[code of conduct](CODE_OF_CONDUCT.md). Use at your own risk.
