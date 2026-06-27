#!/usr/bin/env python3
"""
Generate a realistic stream of AIS AIVDM Type 1 (Class A position report)
sentences for replay testing of the GWS -> receiver path.

Output is time-ordered: a fleet of vessels each report on a fixed interval,
staggered in phase so the aggregate rate is steady. Played back at
`replay.rate` sentences/sec, the file represents `--duration` seconds of
real time.

Each sentence is a valid single-fragment AIVDM:
    !AIVDM,1,1,,A,<28-char 6-bit payload>,0*<checksum>

Vessels move between reports (great-circle-ish flat approximation), so
latitude/longitude actually change over the run.
"""
import argparse
import math


def u(val, nbits):
    """Unsigned field -> bit string."""
    return format(val & ((1 << nbits) - 1), '0{}b'.format(nbits))


def s(val, nbits):
    """Signed (two's complement) field -> bit string."""
    if val < 0:
        val = (1 << nbits) + val
    return format(val & ((1 << nbits) - 1), '0{}b'.format(nbits))


def sixbit_armor(bits):
    """6-bit ASCII armor per ITU-R M.1371 / NMEA AIVDM."""
    pad = (-len(bits)) % 6
    bits += '0' * pad
    out = []
    for i in range(0, len(bits), 6):
        v = int(bits[i:i + 6], 2)
        out.append(chr(v + 48 if v < 40 else v + 56))
    return ''.join(out)


def nmea_checksum(body):
    c = 0
    for ch in body:
        c ^= ord(ch)
    return '{:02X}'.format(c)


def make_type1(mmsi, lat, lon, sog_kn, cog_deg, hdg_deg, status=0, ts=60):
    bits = ''
    bits += u(1, 6)                                    # message type 1
    bits += u(0, 2)                                    # repeat indicator
    bits += u(mmsi, 30)                                # MMSI
    bits += u(status, 4)                               # nav status
    bits += s(-128, 8)                                 # ROT = not available
    bits += u(min(int(round(sog_kn * 10)), 1022), 10)  # SOG, 0.1 kn
    bits += u(0, 1)                                    # position accuracy
    bits += s(int(round(lon * 600000)), 28)            # longitude, 1/10000 min
    bits += s(int(round(lat * 600000)), 27)            # latitude,  1/10000 min
    bits += u(min(int(round(cog_deg * 10)), 3599), 12) # COG, 0.1 deg
    bits += u(min(int(round(hdg_deg)), 511), 9)        # true heading
    bits += u(ts, 6)                                   # timestamp (sec of UTC)
    bits += u(0, 2)                                    # maneuver indicator
    bits += u(0, 3)                                    # spare
    bits += u(0, 1)                                    # RAIM
    bits += u(0, 19)                                   # radio status
    payload = sixbit_armor(bits)                       # 168 bits -> 28 chars
    body = 'AIVDM,1,1,,A,{},0'.format(payload)
    return '!{}*{}'.format(body, nmea_checksum(body))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--vessels', type=int, default=300)
    ap.add_argument('--duration', type=int, default=3600, help='seconds')
    ap.add_argument('--interval', type=int, default=10,
                    help='per-vessel report interval (s)')
    ap.add_argument('--out', required=True)
    args = ap.parse_args()

    # Seed a fleet in a bounding box (a busy strait), deterministic layout.
    fleet = []
    for i in range(args.vessels):
        lat = 35.0 + (i % 50) * 0.01           # ~35.00 .. 35.49 N
        lon = 23.0 + (i // 50) * 0.05          # ~23.00 .. 23.30 E
        sog = 2.0 + (i % 25)                   # 2 .. 26 knots
        cog = (i * 37) % 360
        fleet.append({'mmsi': 200000000 + i, 'lat': lat, 'lon': lon,
                      'sog': sog, 'cog': float(cog)})

    n = 0
    with open(args.out, 'w') as f:
        f.write('# Synthetic AIS feed: {} vessels, {}s @ ~{}/s, '
                'AIVDM Type 1\n'.format(args.vessels, args.duration,
                                        args.vessels // args.interval))
        for t in range(args.duration):
            phase = t % args.interval
            for i, v in enumerate(fleet):
                if i % args.interval != phase:
                    continue
                # advance position by one report interval of travel
                dist_nm = v['sog'] * args.interval / 3600.0
                dlat = dist_nm / 60.0 * math.cos(math.radians(v['cog']))
                dlon = (dist_nm / 60.0 * math.sin(math.radians(v['cog']))
                        / max(math.cos(math.radians(v['lat'])), 1e-6))
                v['lat'] += dlat
                v['lon'] += dlon
                f.write(make_type1(v['mmsi'], v['lat'], v['lon'],
                                   v['sog'], v['cog'], v['cog'],
                                   ts=t % 60) + '\n')
                n += 1
    print('wrote {} sentences to {}'.format(n, args.out))


if __name__ == '__main__':
    main()
