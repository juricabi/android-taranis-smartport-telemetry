#!/usr/bin/env python3
# Capture RTP datagrams to a file: 4-byte big-endian length, then the datagram.
import socket, struct, sys, time

port = int(sys.argv[1]); out = sys.argv[2]; seconds = float(sys.argv[3])
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", port))
s.settimeout(0.5)
end = time.time() + seconds
n = 0
with open(out, "wb") as f:
    while time.time() < end:
        try:
            data, _ = s.recvfrom(65536)
        except socket.timeout:
            continue
        f.write(struct.pack(">I", len(data)))
        f.write(data)
        n += 1
print(f"captured {n} datagrams to {out}")
