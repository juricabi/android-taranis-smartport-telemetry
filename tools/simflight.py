"""Fly a make-believe quad, in CRSF or MAVLink, at a real place.

Sends the frames a Crossfire link carries to a phone running the telemetry
app: position, attitude, battery, link statistics, flight mode and the
transmitter's own name. Everything the app draws is exercised — the map, the
track, the artificial horizon, the battery readings, the RF rate, and the 3D
terrain view with the model at its true attitude.

    python simflight.py --host <the phone> --port 8888 --lat <..> --lon <..>

The app listens: Connect -> Network -> TBS Crossfire / Tracer (UDP), same port.

Two additions ride on the same flight model:

    --passthrough          weave ArduPilot passthrough words (0x80 frames,
                           appids 0x5001-0x5006 plus status text) into the
                           CRSF stream, the way ArduPilot over ELRS does
    --protocol mavlink-hl  send MAVLink HIGH_LATENCY2 instead of CRSF: one
                           42-byte message every --hl-period seconds, which
                           is the whole of what a satellite link carries

    --dump FILE --seconds N   write the byte stream to a file instead of the
                              network, on a fixed clock — the app's unit
                              tests replay these files through the real
                              parsers, so the stream a phone receives is the
                              stream the tests have proved out
"""
import argparse
import math
import socket
import struct
import time

SYNC = 0xC8

GPS = 0x02
BATTERY = 0x08
LINK = 0x14
ATTITUDE = 0x1E
FLIGHT_MODE = 0x21
DEVICE_INFO = 0x29
# ArduPilot passthrough over CRSF: 0x80 on ELRS and new TBS firmware, 0x7F on
# old TBS firmware; identical payload, little-endian inside a big-endian
# protocol because it is a packed struct copied straight off the autopilot.
AP_CUSTOM = 0x80
AP_SINGLE = 0xF0
AP_TEXT = 0xF1
AP_MULTI = 0xF2


def crc8(data):
    """CRSF uses polynomial 0xD5 over the type byte and the payload."""
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0xD5) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def frame(frame_type, payload):
    body = bytes([frame_type]) + payload
    # length counts the type, the payload and the checksum
    return bytes([SYNC, len(body) + 1]) + body + bytes([crc8(body)])


def gps_frame(lat, lon, speed_kmh, heading_deg, altitude_m, satellites):
    return frame(GPS, struct.pack(
        ">iiHHHB",
        int(round(lat * 1e7)), int(round(lon * 1e7)),
        int(round(speed_kmh * 10)) & 0xFFFF,
        int(round(heading_deg % 360 * 100)) & 0xFFFF,
        int(round(altitude_m)) + 1000, satellites))


def battery_frame(volts, amps, used_mah, remaining_pct):
    # a flat pack is a real state; a negative one is not, and it crashed the
    # packer thirty minutes into a flight
    volts = max(0.0, min(6553.0, volts))
    amps = max(0.0, min(6553.0, amps))
    used = int(round(used_mah)) & 0xFFFFFF
    return frame(BATTERY, struct.pack(">HH", int(round(volts * 10)), int(round(amps * 10)))
                 + bytes([(used >> 16) & 0xFF, (used >> 8) & 0xFF, used & 0xFF,
                          int(round(remaining_pct)) & 0xFF]))


def attitude_frame(pitch_deg, roll_deg, yaw_deg):
    def to_wire(degrees):
        value = int(round(math.radians(degrees) * 10000))
        return max(-32768, min(32767, value))
    return frame(ATTITUDE, struct.pack(">hhh",
                                       to_wire(pitch_deg), to_wire(roll_deg), to_wire(yaw_deg)))


def link_frame(up_rssi, up_lq, up_snr, rf_mode, power_index, down_rssi, down_lq, down_snr):
    return frame(LINK, bytes([
        up_rssi & 0xFF, (up_rssi + 3) & 0xFF, up_lq & 0xFF, up_snr & 0xFF,
        1, rf_mode & 0xFF, power_index & 0xFF,
        down_rssi & 0xFF, down_lq & 0xFF, down_snr & 0xFF]))


def mode_frame(name):
    return frame(FLIGHT_MODE, name.encode("ascii") + b"\x00")


def device_info_frame(name):
    # destination, origin, then the name; the rest is padding the app ignores
    return frame(DEVICE_INFO, bytes([0xEA, 0xC8]) + name.encode("ascii") + b"\x00"
                 + struct.pack(">III", 0, 0, 0) + bytes([0, 0]))


# ------------------------- ArduPilot passthrough words, as the FC packs them

def prep_number(value, mantissa_bits):
    """ArduPilot's mantissa/exponent packing: value * 10^exp, exp in 2 bits at
    the bottom for the (x,2) forms and 1 bit for the (x,1) forms."""
    exp = 0
    limit = (1 << mantissa_bits) - 1
    while value > limit and exp < 3:
        value //= 10
        exp += 1
    return value, exp


def ap_status_word(mode_plus_one, armed, throttle_pct):
    word = mode_plus_one & 0x1F
    if armed:
        word |= 1 << 8
    word |= (int(throttle_pct * 63 / 100) & 0x3F) << 19
    return word


def gps_status_word(satellites, fix3d, alt_msl_m):
    word = min(15, satellites) | ((3 if fix3d else 1) << 4)
    hdop_dm, hdop_exp = prep_number(12, 7)  # a fixed, healthy 1.2
    word |= (hdop_exp << 6) | (hdop_dm << 7)
    dm = abs(int(round(alt_msl_m * 10)))
    mant, exp = prep_number(dm, 7)
    word |= (exp << 22) | (mant << 24)
    if alt_msl_m < 0:
        word |= 1 << 31
    return word


def battery_word(volts, amps, used_mah):
    word = int(round(volts * 10)) & 0x1FF
    da = int(round(amps * 10))
    mant, exp = prep_number(da, 7)
    word |= (exp << 9) | (mant << 10)
    word |= (min(32767, int(round(used_mah))) & 0x7FFF) << 17
    return word


def home_word(distance_m, alt_above_home_m, bearing_deg):
    mant, exp = prep_number(int(round(distance_m)), 10)
    word = (exp & 0x3) | (mant << 2)
    dm = abs(int(round(alt_above_home_m * 10)))
    mant, exp = prep_number(dm, 10)
    word |= (exp << 12) | (mant << 14)
    if alt_above_home_m < 0:
        word |= 1 << 24
    word |= (int(bearing_deg % 360 / 3) & 0x7F) << 25
    return word


def vel_yaw_word(vspeed_ms, hspeed_ms, yaw_deg):
    dm = abs(int(round(vspeed_ms * 10)))
    mant, exp = prep_number(dm, 7)
    word = (exp & 1) | (mant << 1)
    if vspeed_ms < 0:
        word |= 1 << 8
    dm = int(round(hspeed_ms * 10))
    mant, exp = prep_number(dm, 7)
    word |= (exp << 9) | (mant << 10)
    word |= (int(yaw_deg % 360 / 0.2) & 0x7FF) << 17
    return word


def attitude_word(roll_deg, pitch_deg):
    word = (int(round(roll_deg * 5)) + 900) & 0x7FF
    word |= ((int(round(pitch_deg * 5)) + 450) & 0x3FF) << 11
    return word


def ap_single_frame(appid, word):
    return frame(AP_CUSTOM, struct.pack("<BHI", AP_SINGLE, appid, word & 0xFFFFFFFF))


def ap_multi_frame(tuples):
    payload = struct.pack("<BB", AP_MULTI, len(tuples))
    for appid, word in tuples:
        payload += struct.pack("<HI", appid, word & 0xFFFFFFFF)
    return frame(AP_CUSTOM, payload)


def ap_text_frame(severity, text):
    return frame(AP_CUSTOM, struct.pack("<BB", AP_TEXT, severity)
                 + text.encode("ascii")[:49] + b"\x00")


# ------------------------------------------- MAVLink HIGH_LATENCY2 framing

HL2_ID = 235
HL2_CRC_EXTRA = 179


def x25_crc(data, extra):
    crc = 0xFFFF
    for b in data + bytes([extra]):
        tmp = b ^ (crc & 0xFF)
        tmp = (tmp ^ (tmp << 4)) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


def hl2_payload(t_ms, lat, lon, custom_mode, alt_msl_m, heading_deg,
                throttle_pct, airspeed_ms, groundspeed_ms, battery_pct,
                mav_type, armed):
    base_mode = 0x01 | (0x80 if armed else 0)  # custom-mode enabled; armed bit
    return struct.pack(
        "<IiiHhhHHH" + "B" * 12 + "bbb" + "B" + "bb",
        t_ms & 0xFFFFFFFF,
        int(round(lat * 1e7)), int(round(lon * 1e7)),
        custom_mode & 0xFFFF,
        int(round(alt_msl_m)), int(round(alt_msl_m)),   # altitude, target
        0, 0, 0,                                        # distance, wp, failures
        mav_type, 3,                                    # type, ArduPilotMega
        int(heading_deg % 360 / 2) & 0xFF, 0,
        int(throttle_pct) & 0xFF,
        min(255, int(airspeed_ms * 5)), 0,
        min(255, int(groundspeed_ms * 5)),
        0, 0, 0, 0,                                     # wind, eph, epv
        -128, 0,                                        # no thermometer, climb
        int(battery_pct),
        base_mode, 0, 0)


def mav2_frame(seq, payload):
    header = struct.pack("<BBBBBBBBBB", 0xFD, len(payload), 0, 0, seq & 0xFF,
                         1, 1, HL2_ID & 0xFF, (HL2_ID >> 8) & 0xFF,
                         (HL2_ID >> 16) & 0xFF)
    crc = x25_crc(header[1:] + payload, HL2_CRC_EXTRA)
    return header + payload + struct.pack("<H", crc)


def mav1_frame(seq, payload):
    header = struct.pack("<BBBBBB", 0xFE, len(payload), seq & 0xFF, 1, 1, HL2_ID)
    crc = x25_crc(header[1:] + payload, HL2_CRC_EXTRA)
    return header + payload + struct.pack("<H", crc)


COMMAND_LONG_ID = 76
COMMAND_LONG_CRC_EXTRA = 152
MAV_CMD_CONTROL_HIGH_LATENCY = 2600


def parse_control_high_latency(data):
    """True to start the stream, False to stop it, None for anything else.

    Checks the checksum rather than trusting the sender: the point of
    --wait-enable is to prove the app's frame is one an autopilot would
    accept, and a wrong CRC_EXTRA is exactly the mistake that check catches.
    """
    if len(data) >= 12 and data[0] == 0xFD:
        length = data[1]
        msgid = data[7] | (data[8] << 8) | (data[9] << 16)
        head, payload = data[1:10], data[10:10 + length]
        tail = 10 + length
    elif len(data) >= 8 and data[0] == 0xFE:
        length = data[1]
        msgid = data[5]
        head, payload = data[1:6], data[6:6 + length]
        tail = 6 + length
    else:
        return None
    if msgid != COMMAND_LONG_ID or len(data) < tail + 2:
        return None
    crc = x25_crc(bytes(head + payload), COMMAND_LONG_CRC_EXTRA)
    if crc != struct.unpack_from("<H", data, tail)[0]:
        print("ignoring a COMMAND_LONG with a bad checksum")
        return None
    # MAVLink 2 truncates trailing zero bytes off the payload
    payload = payload.ljust(33, b"\x00")
    if struct.unpack_from("<H", payload, 28)[0] != MAV_CMD_CONTROL_HIGH_LATENCY:
        return None
    return struct.unpack_from("<f", payload, 0)[0] >= 0.5


# ------------------------------------------------------------ route surfing

def load_route(path):
    """lat,lon,altitude-MSL per line; # lines are comments."""
    points = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(",")
            points.append((float(parts[0]), float(parts[1]), float(parts[2])))
    if len(points) < 2:
        raise SystemExit("a route needs at least two points")
    return points


def route_legs(points):
    """Each leg with its start distance, so a distance finds its leg."""
    legs = []
    total = 0.0
    for a, b in zip(points, points[1:]):
        mid = math.radians((a[0] + b[0]) / 2)
        dn = (b[0] - a[0]) * 111320.0
        de = (b[1] - a[1]) * 111320.0 * math.cos(mid)
        length = math.hypot(dn, de)
        legs.append((total, length, a, b))
        total += length
    return legs, total


def route_position(legs, total, s):
    """The point s metres along the route, riding it out and back again."""
    k = s % (2 * total)
    if k > total:
        k = 2 * total - k
    for start, length, a, b in legs:
        if k <= start + length or (start, length, a, b) is legs[-1]:
            f = 0.0 if length == 0 else max(0.0, min(1.0, (k - start) / length))
            return (a[0] + (b[0] - a[0]) * f,
                    a[1] + (b[1] - a[1]) * f,
                    a[2] + (b[2] - a[2]) * f)
    return legs[-1][3]


def local_ips():
    """The addresses this PC might be reachable at, for the app to dial."""
    ips = set()
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        ips.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", help="the phone (not needed with --dump)")
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--tcp", action="store_true",
                        help="serve over TCP (the app dials in as a TCP "
                             "client) instead of sending UDP to --host")
    parser.add_argument("--lat", type=float)
    parser.add_argument("--lon", type=float)
    parser.add_argument("--route", help="fly a CSV of lat,lon,altitude points "
                                        "instead of circling --lat/--lon; the "
                                        "flight surfs along it and back again. "
                                        "Try tools/velebit-surf.csv")
    parser.add_argument("--speed", type=float, default=30.0,
                        help="metres per second along a --route")
    parser.add_argument("--ground", type=float, default=120.0,
                        help="height of the field above sea level, metres")
    parser.add_argument("--radius", type=float, default=220.0)
    parser.add_argument("--minutes", type=float, default=8.0)
    parser.add_argument("--style", choices=("eight", "acro"), default="eight",
                        help="eight: a lazy figure of eight. acro: a racer — "
                             "tight turns, dives and climbs, and a roll rate to "
                             "match")
    parser.add_argument("--above-launch", action="store_true",
                        help="report height above the launch point, as iNav "
                             "over CRSF (and old Betaflight) does, instead of "
                             "above sea level")
    parser.add_argument("--passthrough", action="store_true",
                        help="weave ArduPilot passthrough frames into the CRSF "
                             "stream, as ArduPilot over ELRS sends them")
    parser.add_argument("--protocol", choices=("crsf", "mavlink-hl"),
                        default="crsf")
    parser.add_argument("--mavlink-version", type=int, choices=(1, 2), default=2)
    parser.add_argument("--hl-period", type=float, default=5.0,
                        help="seconds between HIGH_LATENCY2 messages; ArduPilot "
                             "sends one per five")
    parser.add_argument("--wait-enable", action="store_true",
                        help="behave as an ArduPilot high-latency port: bind "
                             "the port, stay silent until "
                             "MAV_CMD_CONTROL_HIGH_LATENCY arrives, stop when "
                             "asked to; the stream goes to whoever asked")
    parser.add_argument("--dump", help="write the byte stream to this file "
                                       "instead of the network, on a fixed clock")
    parser.add_argument("--seconds", type=float, default=20.0,
                        help="how much stream to write with --dump")
    args = parser.parse_args()

    if args.wait_enable:
        if args.protocol != "mavlink-hl":
            parser.error("--wait-enable is a high-latency port; "
                         "it needs --protocol mavlink-hl")
        if args.dump is not None:
            parser.error("--wait-enable needs a network to be asked on, "
                         "not --dump")
    if args.tcp and args.wait_enable:
        parser.error("--wait-enable is a UDP high-latency port; not with --tcp")
    if args.tcp and args.dump is not None:
        parser.error("--tcp serves a live socket; not with --dump")
    if (args.dump is None and args.host is None
            and not args.wait_enable and not args.tcp):
        parser.error("--host is required unless --dump or --tcp is given")

    route = None
    route_cache = None
    if args.route:
        route = load_route(args.route)
        route_cache = route_legs(route)
        # launch is the start of the ridge unless said otherwise
        if args.lat is None:
            args.lat, args.lon = route[0][0], route[0][1]
    elif args.lat is None or args.lon is None:
        parser.error("--lat and --lon are required without --route")

    dump = open(args.dump, "wb") if args.dump else None
    sock = None
    server = None
    conn = None
    target = None
    if dump is None and args.tcp:
        # The app dials in (its "TBS Crossfire / Tracer (TCP)" preset is a
        # TCP client), so the sim is the server it connects to. The flight
        # clock starts once it is on the line.
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("", args.port))
        server.listen(1)
        print("TCP: waiting for the phone on port %d ..." % args.port)
        print("     app: Connect -> Network -> 'TBS Crossfire / Tracer "
              "(TCP)', host = this PC, port %d" % args.port)
        for ip in local_ips():
            print("     this PC looks like %s" % ip)
        conn, who = server.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        args.host = who[0]
        print("phone connected from %s:%d" % who)
    elif dump is None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        if args.host is not None:
            target = (args.host, args.port)
        if args.wait_enable:
            # the phone sends the command to this port, and the sender of
            # that command is where the stream goes back to
            sock.bind(("", args.port))
            sock.setblocking(False)

    def send(data):
        nonlocal conn
        if dump is not None:
            dump.write(data)
        elif args.tcp:
            # a TCP peer can drop and dial back in; wait for it and go on
            while True:
                try:
                    conn.sendall(data)
                    return
                except OSError:
                    print("phone dropped; waiting for it to reconnect ...")
                    try:
                        conn.close()
                    except OSError:
                        pass
                    conn, who = server.accept()
                    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    print("phone reconnected from %s:%d" % who)
        else:
            sock.sendto(data, target)

    metres_per_deg_lat = 111320.0
    metres_per_deg_lon = 111320.0 * math.cos(math.radians(args.lat))

    started = 0.0 if dump is not None else time.time()
    ticks = 0
    last = {"gps": 0.0, "battery": 0.0, "link": 0.0, "mode": 0.0, "info": 0.0,
            "pass": 0.0, "multi": 0.0, "text": 0.0, "hl": -1e9}
    previous = None
    used_mah = 0.0
    passthrough_turn = 0
    hl_seq = 0

    enabled = not args.wait_enable
    if dump is None:
        if args.wait_enable:
            print("high-latency port on %d: silent until asked  (ctrl-c to quit)"
                  % args.port)
        elif route is not None:
            print("surfing %s: %.1f km at %.0f m/s -> %s:%d   (ctrl-c to land)"
                  % (args.route, route_cache[1] / 1000.0, args.speed,
                     args.host, args.port))
        else:
            print("flying %s at %.6f, %.6f -> %s:%d   heights %s   (ctrl-c to land)"
                  % (args.style, args.lat, args.lon, args.host, args.port,
                     "above launch" if args.above_launch else "above sea level"))

    while True:
        if dump is not None:
            now = started + ticks * 0.04
            ticks += 1
            if now > args.seconds:
                break
        else:
            now = time.time()
        t = now - started
        if dump is None and t > args.minutes * 60:
            break

        if route is not None:
            # Surfing the ridge: along the route at a soaring pace, close over
            # the crests with a breathing clearance, banking falls out of the
            # frame-to-frame motion like everywhere else.
            legs, total = route_cache
            rlat, rlon, ralt = route_position(legs, total, t * args.speed)
            north = (rlat - args.lat) * metres_per_deg_lat
            east = (rlon - args.lon) * metres_per_deg_lon
            phase = 2 * math.pi * t / 60.0
            climb = (ralt - args.ground) + 70 + 45 * math.sin(2 * math.pi * t / 23.0)
        elif args.style == "acro":
            # A racer rather than a tourist: a lap every eighteen seconds, the
            # track wandering as three turns of different rates beat against
            # each other, and height thrown about far faster than the eight
            # does it — dives to the deck and zooms, which is what exercises
            # the terrain following, the curtain and the pitch of the model.
            phase = 2 * math.pi * t / 18.0
            east = args.radius * (0.75 * math.sin(phase)
                                  + 0.35 * math.sin(2.7 * phase + 0.6))
            north = args.radius * (0.75 * math.cos(1.3 * phase)
                                   + 0.30 * math.sin(3.1 * phase))
            climb = (55 + 45 * math.sin(2 * math.pi * t / 21.0)
                     + 20 * math.sin(2 * math.pi * t / 6.5))
            climb = max(3.0, climb)
        else:
            # a figure of eight, which turns both ways and so exercises roll in
            # both directions; one lap a minute
            phase = 2 * math.pi * t / 60.0
            east = args.radius * math.sin(phase)
            north = args.radius * math.sin(phase) * math.cos(phase)
            climb = 45 + 35 * math.sin(2 * math.pi * t / 95.0)

        lat = args.lat + north / metres_per_deg_lat
        lon = args.lon + east / metres_per_deg_lon
        altitude = args.ground + climb

        # course and bank from where it was a moment ago, so the model leans
        # into its turns the way a real one does
        speed = 0.0
        heading = 0.0
        roll = 0.0 if previous is None else previous["roll"]
        pitch = 0.0
        vspeed = 0.0
        if previous is not None:
            dt = max(0.02, t - previous["t"])
            de = (east - previous["east"]) / dt
            dn = (north - previous["north"]) / dt
            dh = (altitude - previous["alt"]) / dt
            vspeed = dh
            speed = math.hypot(de, dn)
            heading = math.degrees(math.atan2(de, dn)) % 360
            turn = ((heading - previous["heading"] + 540) % 360) - 180
            # the turn rate spikes where the eight crosses itself; ease the bank
            # so it looks like flying rather than twitching
            limit = 80.0 if args.style == "acro" else 55.0
            gain = 2.0 if args.style == "acro" else 1.6
            follow = 0.35 if args.style == "acro" else 0.15
            wanted = max(-limit, min(limit, turn / dt * gain))
            roll = previous["roll"] + (wanted - previous["roll"]) * follow
            nose = 70.0 if args.style == "acro" else 35.0
            pitch = max(-nose, min(nose, math.degrees(math.atan2(dh, max(1.0, speed)))))
        previous = {"t": t, "east": east, "north": north, "alt": altitude,
                    "heading": heading, "roll": roll}

        amps = 18.0 + 12.0 * abs(math.sin(phase * 2))
        used_mah += amps * 1000.0 / 3600.0 * 0.05
        # a fresh pack when the last one is empty, so a long session keeps flying
        if used_mah > 2100:
            used_mah = 0.0
        volts = 25.2 - 4.0 * (used_mah / 2200.0) - amps * 0.02
        remaining = max(0, min(100, 100 - used_mah / 22.0))

        distance = math.hypot(east, north)
        # floored: a route is tens of kilometres and the link, being make
        # believe, holds to the end of it
        up_rssi = int(max(-105, -40 - distance / 12))
        up_lq = int(max(60, 100 - distance / 30))

        if args.wait_enable:
            while True:
                try:
                    data, sender = sock.recvfrom(2048)
                except OSError:
                    # nothing waiting — or Windows reporting a port it could
                    # not deliver to earlier, which changes nothing here
                    break
                want = parse_control_high_latency(data)
                if want is None:
                    continue
                target = sender
                if want != enabled:
                    enabled = want
                    print("stream %s by %s:%d"
                          % ("enabled" if want else "disabled",
                             sender[0], sender[1]))

        if args.protocol == "mavlink-hl":
            # The whole of a high-latency link: one message per period,
            # nothing else. ArduPilot marks the port MAVLink 2, but the
            # message exists in both framings and the app takes either.
            if enabled and now - last["hl"] >= args.hl_period:
                last["hl"] = now
                payload = hl2_payload(
                    int(t * 1000), lat, lon, 5, altitude, heading,
                    38, speed, speed, int(remaining), 2, t > 8)
                framed = mav2_frame(hl_seq, payload) \
                    if args.mavlink_version == 2 else mav1_frame(hl_seq, payload)
                send(framed)
                hl_seq += 1
            if dump is None:
                time.sleep(0.04)
            continue

        if now - last["gps"] >= 0.1:
            last["gps"] = now
            reported = climb if args.above_launch else altitude
            send(gps_frame(lat, lon, speed * 3.6, heading, reported, 14))
        # attitude fastest, since it is what the horizon and the model ride on
        # yaw goes on the wire in radians as a signed 16 bit value, so it has
        # to be given as plus or minus 180: sending 0 to 360 overflowed past
        # about 187 degrees and the heading pinned itself there
        send(attitude_frame(pitch, roll, ((heading + 180) % 360) - 180))
        if now - last["battery"] >= 0.5:
            last["battery"] = now
            send(battery_frame(volts, amps, used_mah, remaining))
        if now - last["link"] >= 0.1:
            last["link"] = now
            send(link_frame(up_rssi, up_lq, 12, 2, 3, up_rssi - 6, up_lq - 4, 9))
        if now - last["mode"] >= 1.0:
            last["mode"] = now
            send(mode_frame("ACRO" if t > 8 else "ACRO*"))
        if now - last["info"] >= 5.0:
            last["info"] = now
            # so the app can tell it is a Crossfire and use its rate table
            send(device_info_frame("XF Micro TX"))

        if args.passthrough:
            # single-packet words at 8Hz as a fast link sends them, each
            # appid taking its turn; a multi frame and a status text at the
            # slower cadences ArduPilot uses
            if now - last["pass"] >= 0.125:
                last["pass"] = now
                words = [
                    (0x5001, ap_status_word(5, t > 8, 38)),
                    (0x5002, gps_status_word(14, True, altitude)),
                    (0x5003, battery_word(volts, amps, used_mah)),
                    (0x5005, vel_yaw_word(vspeed, speed, heading)),
                    (0x5006, attitude_word(roll, pitch)),
                ]
                appid, word = words[passthrough_turn % len(words)]
                passthrough_turn += 1
                send(ap_single_frame(appid, word))
            if now - last["multi"] >= 2.0:
                last["multi"] = now
                bearing = (math.degrees(math.atan2(-east, -north))) % 360
                send(ap_multi_frame([
                    (0x5004, home_word(distance, climb, bearing)),
                    (0x5001, ap_status_word(5, t > 8, 38)),
                    (0x5003, battery_word(volts, amps, used_mah)),
                ]))
            if now - last["text"] >= 5.0:
                last["text"] = now
                send(ap_text_frame(6, "SimFlight passthrough alive"))

        if dump is None:
            time.sleep(0.04)

    if dump is not None:
        dump.close()
        print("wrote %.0f seconds of %s to %s"
              % (args.seconds, args.protocol +
                 ("+passthrough" if args.passthrough else ""), args.dump))
    else:
        print("landed after %.1f minutes" % args.minutes)


main()
