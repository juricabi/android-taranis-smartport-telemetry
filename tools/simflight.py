"""Fly a make-believe quad, in CRSF, at a real place.

Sends the frames a Crossfire link carries to a phone running the telemetry
app: position, attitude, battery, link statistics, flight mode and the
transmitter's own name. Everything the app draws is exercised — the map, the
track, the artificial horizon, the battery readings, the RF rate, and the 3D
terrain view with the model at its true attitude.

    python simflight.py --host <the phone> --port 8888 --lat <..> --lon <..>

The app listens: Connect -> Network -> TBS Crossfire / Tracer (UDP), same port.
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True, help="the phone")
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--ground", type=float, default=120.0,
                        help="height of the field above sea level, metres")
    parser.add_argument("--radius", type=float, default=220.0)
    parser.add_argument("--minutes", type=float, default=8.0)
    parser.add_argument("--style", choices=("eight", "acro"), default="eight",
                        help="eight: a lazy figure of eight. acro: a racer — "
                             "tight turns, dives and climbs, and a roll rate to "
                             "match")
    parser.add_argument("--above-launch", action="store_true",
                        help="report height above the launch point, as an armed "
                             "Betaflight does, instead of above sea level")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    target = (args.host, args.port)

    metres_per_deg_lat = 111320.0
    metres_per_deg_lon = 111320.0 * math.cos(math.radians(args.lat))

    started = time.time()
    last = {"gps": 0.0, "battery": 0.0, "link": 0.0, "mode": 0.0, "info": 0.0}
    previous = None
    used_mah = 0.0

    print("flying %s at %.6f, %.6f -> %s:%d   heights %s   (ctrl-c to land)"
          % (args.style, args.lat, args.lon, args.host, args.port,
             "above launch" if args.above_launch else "above sea level"))

    while True:
        now = time.time()
        t = now - started
        if t > args.minutes * 60:
            break

        if args.style == "acro":
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
        if previous is not None:
            dt = max(0.02, t - previous["t"])
            de = (east - previous["east"]) / dt
            dn = (north - previous["north"]) / dt
            dh = (altitude - previous["alt"]) / dt
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
        up_rssi = int(-40 - distance / 12)
        up_lq = int(max(60, 100 - distance / 30))

        if now - last["gps"] >= 0.1:
            last["gps"] = now
            reported = climb if args.above_launch else altitude
            sock.sendto(gps_frame(lat, lon, speed * 3.6, heading, reported, 14), target)
        # attitude fastest, since it is what the horizon and the model ride on
        # yaw goes on the wire in radians as a signed 16 bit value, so it has
        # to be given as plus or minus 180: sending 0 to 360 overflowed past
        # about 187 degrees and the heading pinned itself there
        sock.sendto(attitude_frame(pitch, roll, ((heading + 180) % 360) - 180), target)
        if now - last["battery"] >= 0.5:
            last["battery"] = now
            sock.sendto(battery_frame(volts, amps, used_mah, remaining), target)
        if now - last["link"] >= 0.1:
            last["link"] = now
            sock.sendto(link_frame(up_rssi, up_lq, 12, 2, 3, up_rssi - 6, up_lq - 4, 9), target)
        if now - last["mode"] >= 1.0:
            last["mode"] = now
            sock.sendto(mode_frame("ACRO" if t > 8 else "ACRO*"), target)
        if now - last["info"] >= 5.0:
            last["info"] = now
            # so the app can tell it is a Crossfire and use its rate table
            sock.sendto(device_info_frame("XF Micro TX"), target)

        time.sleep(0.04)

    print("landed after %.1f minutes" % args.minutes)


main()
