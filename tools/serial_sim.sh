#!/usr/bin/env bash
#
# Full serial-port demo on macOS/Linux:
#   virtual PTY (socat) -> GWS reading serial -> UDP -> Ada receiver.
# Feeds DATA into the writer end of the pair until interrupted (Ctrl-C).
#
# Driven by the Makefile `serial-demo` target; override via env vars:
#   PORT, DATA, RECV, GEN, GWSDIR
set -u

PORT="${PORT:-4001}"
DATA="${DATA:-data/sample_ais.nmea}"
RECV="${RECV:-mission-ada/bin/mission_receiver}"
GEN="${GEN:-.run}"
GWSDIR="${GWSDIR:-gws-java}"

command -v socat >/dev/null || { echo "socat not found (brew install socat)"; exit 2; }
[ -r "$DATA" ] || { echo "feed file not found: $DATA"; exit 2; }
mkdir -p "$GEN"

SOC="$(mktemp)"
socat -d -d pty,raw,echo=0 pty,raw,echo=0 > "$SOC" 2>&1 &
SOCPID=$!
GWSPID=""; RXPID=""

cleanup() {
    [ -n "$GWSPID" ] && kill "$GWSPID" 2>/dev/null
    [ -n "$RXPID" ]  && kill "$RXPID"  2>/dev/null
    kill "$SOCPID" 2>/dev/null
    pkill -f gws.GwsMain 2>/dev/null
    pkill -f mission_receiver 2>/dev/null
    rm -f "$SOC"
}
trap cleanup EXIT INT TERM

# socat prints the two device paths to stderr; wait for both.
W=""; G=""
for _ in $(seq 1 20); do
    W="$(grep 'PTY is' "$SOC" 2>/dev/null | sed -n 1p | awk '{print $NF}')"
    G="$(grep 'PTY is' "$SOC" 2>/dev/null | sed -n 2p | awk '{print $NF}')"
    [ -n "$W" ] && [ -n "$G" ] && break
    sleep 0.3
done
[ -n "$W" ] && [ -n "$G" ] || { echo "failed to obtain PTY pair:"; cat "$SOC"; exit 1; }
echo "virtual serial: writer=$W  GWS reads=$G"

# Derive a serial config from the base properties.
sed -e 's/^source.type.*/source.type = serial/' \
    -e "s#^serial.device.*#serial.device = $G#" \
    "$GWSDIR/gws.properties" > "$GEN/gws-serial.properties"

"$RECV" "$PORT" &
RXPID=$!
sleep 1
( cd "$GWSDIR" && exec java -cp out gws.GwsMain "../$GEN/gws-serial.properties" ) &
GWSPID=$!
sleep 1

echo "feeding $DATA into the serial port (Ctrl-C to stop) ..."
while true; do
    while IFS= read -r line; do
        case "$line" in \#*|"") continue;; esac
        printf '%s\n' "$line" > "$W"
        sleep 0.1
    done < "$DATA"
done
