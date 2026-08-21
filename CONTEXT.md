# Domain glossary

The words this codebase uses for its own ideas, one word per idea. Code,
issues and reviews use these terms as written here.

## The flight and its views

- **The flight** — one recording or one live link's worth of positions and
  readings. One flight, two views: the 2D map and the 3D terrain both draw it,
  and every control on one belongs to the other.
- **Live and replay** — the two roads data takes to the same place. Live
  arrives a fix at a time from a poller; a replay hands over whole stretches
  from `LogPlayer.seek`, gathered and drawn once.

## Modules

- **TelemetryPanel** (`ui/TelemetryPanel.kt`) — the telemetry readouts,
  whole: every tile on the two bars, formatting, icons, timeout greying, the
  cell-count question, the CRSF rate system. One adapter at the
  decoder-listener seam; the activity forwards display-only callbacks here.
- **VideoPane** (`ui/VideoPane.kt`) — the live picture and everything that
  owns it: the wish to watch, the stale-events generation, the retry that
  keeps the card standing, the split, the permission choreography.
- **VideoSource** (`video/VideoSource.kt`) — the seam a picture arrives
  through; four adapters (RTSP, MJPEG, UDP/RTP, USB UVC). Rotation crosses
  this seam as a question the source asks back (`turn`); sources never read
  settings.
- **PhoneWatcher** (`service/PhoneWatcher.kt`) — where the phone is and which
  way it faces: sensing, fix arbitration (`worthBelieving`), the background
  compass wake lock. What is written down stays with the service.
- **MockPublisher** (`service/MockPublisher.kt`) — the drone's GPS republished
  as the phone's own position, enriched from what the link has said (`Heard`).
- **PollerChassis** (`protocol/pollers/PollerChassis.kt`) — the pipeline every
  stream transport shares: log → detect → select → pump → commit → one latched
  terminal callback posted to main. The transport is the adapter (open, read,
  close). BluetoothLeDataPoller keeps its own copy: its bytes arrive as a race
  between GATT characteristics, not one stream.
- **The bus** (`decoder/ForwardingListener.kt`, `decoder/MulticastListener.kt`)
  — the structural relays of decoded readings. A relay is never hand-copied:
  ForwardingListener hands everything on through one overridable point (the
  generation guard overrides it once); MulticastListener delivers to ears read
  live. A new `DataDecoder.Listener` callback must be added to both. On the
  screen the stream lands on one multicast: the activity's flight handlers
  and the TelemetryPanel each hear it directly.
- **ReplayHold** (`ui/ReplayHold.kt`) — whether a loaded replay starts,
  decided in one place: the rotation's resume answer, the autostart
  preference behind it, and the hold that keeps playback off bare mesh while
  the 3D ground is fetched.
- **FlightOverlays** (`ui/FlightOverlays.kt`) — the overlays both views draw
  of the same flight (phone arrow, recorded operator, north-up, air traffic),
  said once and fanned to the map and the terrain; each view's dialect is the
  adapter's business. The camera and the model's easing are deliberately not
  here.
- **ConnectFlow** (`ui/ConnectFlow.kt`) — choosing a link, whole: the
  chooser, the Bluetooth/BLE lists and pairing, the USB probe, the network
  dialog with presets and Find. It ends where a device, address or port has
  been chosen; connecting and the reconnect policy stay the activity's.
- **PermissionFunnel** (`ui/PermissionFunnel.kt`) — one permission dialog at
  a time; a request fired under a standing dialog is cancelled unseen, so
  asks take turns and repeats are dropped.
- **TrafficWarnings** (`ui/TrafficWarnings.kt`) — a nearby aircraft said out
  loud, and the speech engine that exists for nothing else.
- **The link's protocol** is the activity's fact (flight decisions hang on
  it — the LTM altitude settle); the TelemetryPanel only renders under it,
  through a provider.

## Idioms

- **The probe pattern** — a recording is asked questions through a decoder of
  its own (`LogPlayer.firstPosition`, `walkWholeLog`), never through the live
  one: the live decoder fires every reading at the screen and moves the replay.
- **The store** — the one `"settings"` SharedPreferences file. Its name and
  the keys watched from outside live only in `PreferenceManager` (`STORE`,
  `KEY_*`).
- **Generation guard** — every start numbers its events and stopping retires
  the number, so a dead source's queued last words never touch the fresh one.
  The video pane and the connection listener each carry one.
