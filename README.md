# Android Telemetry Viewer 2.5.3

Live and recorded RC telemetry on a smooth 2D map or real 3D terrain, with live
video beside it. This is [juricabi's fork](https://github.com/juricabi/android-taranis-smartport-telemetry).

**Download:** [GitHub Releases](https://github.com/juricabi/android-taranis-smartport-telemetry/releases)
· **New in 2.5.3:** the live picture records, and takes the whole screen.

<p align="center">
  <img src="docs/flight-3d.jpg" width="360" alt="3D terrain flight view">
</p>

## Features

**Telemetry**
- FrSky S.PORT, CRSF (Crossfire, Tracer, ExpressLRS), Ghost, LTM and MAVLink
  1/2, detected automatically. ArduPilot passthrough over CRSF and MAVLink
  High Latency carry full telemetry over plain ELRS or satellite/LoRa links.
- Tiles for battery, cells, current, satellites, speeds, distances, altitude,
  climb, throttle, RC channels, RSSI/LQ/SNR, antennas, power, rate and
  protocol, plus an artificial horizon — arranged in Sensor display settings,
  greyed when stale.

**Connections**
- Bluetooth, BLE, USB serial over OTG, TCP client or server, UDP and the
  Crossfire Wi-Fi WebSocket, with presets and a subnet **Find**.
- Streams ride whichever network reaches the module — goggle Wi-Fi, hotspot,
  USB-ethernet — while the maps keep to mobile data.
- A dropped link is retried for a minute and continues the same flight and
  log; USB resumes when the cable returns. Connection changes are spoken.

**Map and 3D**
- 2D: OpenStreetMap, OpenTopoMap, satellite, satellite with streets — no API
  key. 3D: real terrain with the model at its true attitude.
- One flight, two views: tracking, chase, model, colours and clock behave the
  same in both.
- Map and ground are cached as they load — about 3 GB of 3D terrain on disk —
  so a field flown before opens from storage.
- Heights drawn right whether the firmware sends sea level or height above
  launch ([how](#how-altitude-works)).
- **Find my quad**: where the model was last seen, how far and which way from
  you, as a plus code, a route in your maps app or a message to share.
- Flight plans from CSV (one `latitude,longitude` per line); nearby aircraft
  from FlightRadar24 with spoken warnings ([opt-in](#nearby-aircraft)).

**Recording and replay**
- Every flight is logged, raw (`.tlm`) and as OpenTX CSV — with the screen
  off, too — and replays at 3× slower to 10× faster with your recorded
  position, heading and clock.
- An altitude profile against the terrain marks the minimum clearance.
- A log manager: flights by day, multi-select, rename, delete, and export or
  import as one zip.

**Video and trackers**
- Live video beside the map from USB (UVC) receivers and cameras, RTSP, MJPEG
  or raw RTP; recorded crash-safe, full screen on demand
  ([details](#live-video)).
- The drone's GPS as the phone's own location, so a tracker app broadcasts
  the drone ([opt-in](#drone-gps-as-phone-location)).

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

Detection latches on the first valid match; the High Latency preset pins
MAVLink 2 rather than wait ten seconds for a second frame. Ghost carries no
attitude, flight mode, vario or airspeed, so those stay empty; for a Ghost
telemetry mirror use EdgeTX with [#7610](https://github.com/EdgeTX/edgetx/pull/7610)
and set the serial port to **Telemetry mirror** at 115200 baud. Over a link
that carries no device name, such as a Bluetooth mirror, long-press the rate
tile to set ExpressLRS, Crossfire or Tracer by hand.

## Connecting

1. Pair or plug in the telemetry device, or join its Wi-Fi.
2. Tap **Connect** and choose Bluetooth, BLE, USB or Network; for a network
   stream, pick the matching preset and port.
3. Pick 2D or 3D with the map-type button; tracking follows the model, chase
   its heading too.

Network presets cover the ExpressLRS backpack, TBS Crossfire / Tracer (TCP,
UDP or the Wi-Fi WebSocket), MAVLink routers, MAVLink High Latency and
serial-to-Wi-Fi bridges. The High Latency preset listens on UDP and sends
`MAV_CMD_CONTROL_HIGH_LATENCY` to the modem until the stream answers — an
autopilot boots with it off — and stretches sensor timeouts to its five-second
cadence.

Telemetry and video are pinned to the network that routes to their target;
internet traffic never is. So set this app to **mobile data preferred**
(Android's per-app network setting): the module's or goggles' Wi-Fi carries
the streams while the maps load over mobile. Dial modules by IP — **Find**
scans the subnet and fills the address in.

## Hardware

A radio's built-in Bluetooth, a USB-C telemetry mirror or a network-capable TX
module needs nothing extra. A raw FrSky S.PORT pin needs an inverter and an
HC-05/HC-06/HM-10-class serial module at **57600 baud**; pair classic
Bluetooth modules in Android first.

<p align="center">
  <img src="connection.jpg" width="480" alt="Connection example">
</p>

## Live video

Pick a source under **Settings → Video** and a video button appears in the top
bar: the picture opens beside the map, left of it in landscape and above it in
portrait. The address says what the stream is:

- **`rtsp://…`** — H.264: IP cameras, mediamtx/go2rtc relays, IP Webcam, and
  **Orqa FPV.Connect** on the goggles' own Wi-Fi at
  `rtsp://192.168.1.1:5004/orqabroadcast`. Video rides TCP by default; the
  **RTSP over UDP** switch is quicker and what some goggles need, at the cost
  of a smeared frame when a packet drops — UDP for a clean link, TCP for a
  weak one.
- **`http://…`** — MJPEG: ESP32-CAM, mjpg-streamer, IP Webcam's `/video`.
- **`udp://5600`** — raw RTP, H.264 or H.265, pushed at this phone's IP by
  **OpenIPC / wfb-ng** and QGroundControl-style senders. No buffer at all.
- **USB (UVC)** — analog OTG receivers (ROTG02 and kin) and action cameras
  in webcam mode, including UVC 1.5 ones such as the DJI Osmo Action that
  stock Android UVC libraries refuse.

The picture carries its own **record**, **sound**, **quarter-turn**
(remembered) and **expand** buttons; expand gives it the whole screen until
Back. The seam drags, the picture is letterboxed rather than cropped, and the
flight overlays keep to the map's half. A stream that cannot connect keeps its
pane, says why and retries; one that stalls rejoins itself.

**Recording** writes to `Movies/Telemetry/`, named like the flight logs. RTSP
and raw RTP are saved exactly as they arrive; USB and MJPEG go through the
phone's hardware H.264 encoder. The files are MPEG transport streams (`.ts`,
for VLC, mpv or ffmpeg), readable to the last byte, so a crash or a flat
battery keeps everything up to the last second. Rotations and dropouts
continue the same file. Picture only, as the camera sent it; a recording
stops itself when storage runs low.

To try `udp://` with no hardware, send a test pattern from a PC on the same
Wi-Fi, or from Termux on the phone to `127.0.0.1`:

```sh
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -pix_fmt yuv420p \
    -c:v libx264 -preset ultrafast -tune zerolatency \
    -x264opts keyint=30:repeat-headers=1 -f rtp rtp://<phone>:5600
```

For H.265 use `-c:v libx265 -x265-params keyint=30:repeat-headers=1`. Keep
`-pix_fmt yuv420p`: without it ffmpeg sends 4:4:4, which no phone hardware
decodes.

## How altitude works

Flight controllers report height one of two ways. ArduPilot, current
Betaflight and any S.Port or MAVLink link send metres above sea level; iNav
over CRSF, and older Betaflight once armed, send metres above the launch
point. iNav over LTM is made absolute from the home altitude in its origin
frame.

The app tells them apart with one rule: **a flight cannot fly below the
ground.** Heights that dive well below the terrain are launch-relative, and
the flight is lifted by the deepest burial — for a normal take-off, the ground
under the launch. One wild reading cannot move it, the answer only grows more
certain, and it is shared by the 3D view, the altitude profile, the MSL
readout and the mock location. Heights sent while disarmed are left out of the
drawing, a replay draws exactly as the flight did, and firmware that says sea
level is believed to the metre.

A barometric altitude is preferred where the protocol has one. With only CRSF
GPS altitude, the normal and GPS altitude fields may show the same value —
they share one source.

## Drone GPS as phone location

**Settings → Mock location** republishes the drone's GPS — position, altitude
and speed, at the link's rate — as this phone's own location while telemetry
is connected, so a tracker app on the phone (Overland, PureTrack) broadcasts
the drone. Pick this app once under Developer options → Select mock location
app; the settings row walks you through it. The phone's own GPS returns on
disconnect. Altitude goes out as ellipsoid height (and as MSL on Android 14+)
— the same sea-level height the 3D view draws — and waits for arming.

## Nearby aircraft

FlightRadar24 traffic is off by default (**Settings → FlightRadar24 Nearby
Aircraft**). Warnings are measured from the model while it is drawn — a
dropped link keeps it, since it may still be flying — and from the phone
otherwise, so they keep working at the field between packs. A replay shows no
traffic: those would be today's aircraft over another day's flight.

## Known limits

- Android 6 or newer, ARMv7/ARM64. `targetSdk 28` is deliberate for a
  sideloaded APK; a Play Store release needs scoped-storage, notification and
  foreground-service work first.
- Map caching is opportunistic; there is no offline-area downloader yet.
- RTSP video is about a third of a second glass to glass — not a sub-150 ms
  FPV feed. Raw RTP (`udp://`) has no buffer; its latency is the link's own.
- H.265 over RTSP does not play (the player library's H.265 RTP reader is
  unfinished upstream); push it as raw `udp://` instead.
- Video is recorded only while the picture is on screen.
- `.local`/mDNS names are not resolved; modules are dialled by IP.

## Development

### Building

JDK 17 or later (21 is what it is built with), the Android SDK with platform
35, NDK 28.2.13676358 and CMake —
`sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`. Gradle 8.11.1, AGP 8.10.1,
Kotlin 2.2.10, MapLibre 13.4.1; ARM64 and ARMv7 APKs.

Copy `example_local.properties` to `local.properties` with your SDK path, and
`example_keystore.properties` to `keystore.properties` with your signing key —
every build, debug included, signs with it.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

APKs land in `app/build/outputs/apk/{debug,release}/`. The debug build
(`juricabi.com.telemetry.debug`) installs beside the release
(`juricabi.com.telemetry`). A `v*` tag makes CI
(`.github/workflows/build-apk.yml`) build and attach the signed release APK.

### Simulator

`tools/simflight.py` flies a CRSF flight over UDP, no radio needed:

```sh
python tools/simflight.py --host <phone-ip> --port 8888 \
  --lat <latitude> --lon <longitude> --ground <metres-msl> \
  --style eight --minutes 20
```

Connect with **Network → TBS Crossfire / Tracer (UDP)** on port 8888.
`--style acro` throws the model about, `--above-launch` sends launch-relative
heights, `--passthrough` weaves in ArduPilot passthrough, and
`--protocol mavlink-hl --wait-enable` plays an ArduPilot high-latency port
(use the **MAVLink High Latency (UDP)** preset with the PC's address).
`--help` lists the rest.

### Code map

| Area | Main files |
|---|---|
| Screen and frame loop | `ui/MapsActivity.kt` |
| 2D map/camera | `maps/maplibre/MapLibreMapWrapper.kt` |
| Synchronized 2D moving scene | `maps/maplibre/MapLibreMovingLines.kt`, `cpp/moving_lines.cpp` |
| 3D view | `ui/Terrain3DView.kt`, `gl/TerrainRenderer.kt`, `gl/TerrainScene.kt` |
| Connection ownership | `service/DataService.kt`, `protocol/pollers/` |
| Protocols | `protocol/`, `protocol/decoder/` |
| Replay and logs | `protocol/pollers/LogPlayer.kt`, `logger/` |
| Live video and its recording | `ui/VideoPane.kt`, `video/` |

[`CONTEXT.md`](CONTEXT.md) names every module and [`CLAUDE.md`](CLAUDE.md)
holds the working rules. A new telemetry value touches a decoder and
`TelemetryPanel`, plus a decoder regression test; a new `DataDecoder.Listener`
callback goes into both `ForwardingListener` and `MulticastListener`. Moving
2D geometry stays on the shared custom render scene — never a GeoJSON source
updated at frame rate.

## Lineage

- [CrazyDude1994](https://github.com/CrazyDude1994/android-taranis-smartport-telemetry): original app
- [RomanLut](https://github.com/RomanLut/android-taranis-smartport-telemetry): sensors, protocols, exports and earlier map work
- [Jauler](https://github.com/Jauler/android-taranis-smartport-telemetry): FlightRadar24 and layout work
- This fork: Ghost, network telemetry, MapLibre 2D, 3D terrain, replay/altitude work and current reliability fixes

[Privacy policy](privacy_policy.md) · [Code of conduct](CODE_OF_CONDUCT.md) ·
Use at your own risk.
