# AIS Relay Prototype — Bounded Queue + UDP/TCP

A working prototype of an AIS relay path: a bounded, freshness-enforcing queue
feeding a switchable UDP/TCP datagram path to a downstream consumer.

```
  Serial AIS RX ──► [ GWS — Java ]                          [ Consumer — Ada ]
   (file replay      source ─► FreshnessQueue ─► UDP or TCP ─────────► receiver
    or serial)                 (capacity + TTL)   (switchable)          (prints sentence,
                                                                         time, source)
```

## Why this design

For real-time position data, **freshness beats completeness**: a stale position
report is worse than none, because the vessel has already moved. Two choices
follow from that principle.

1. **Bounded `FreshnessQueue` (capacity + TTL).** Capacity drops the *oldest*
   message when full (bounds memory and backlog); TTL discards any message that
   has sat in the queue longer than `queue.ttlMillis` at dequeue time (bounds
   how long a report may age inside the GWS). If the consumer falls behind, the
   queue sheds load rather than letting latency grow. See **Core concepts**.
2. **Datagram-style transport (UDP, or TCP).** UDP `send()` never blocks on a
   slow consumer; with TCP the bounded queue does the load-shedding. Either way
   the sender stays current. The trade is lost-delivery for guaranteed-freshness
   — correct for real-time position data.

Queue depth and drop counters are logged every couple of seconds, so backlog
is **observable**.

## Core concepts

**Data flow — who fills and drains the queue**

```
 Source: NmeaFileSource (replay)  OR  SerialSource (live device)
    |  PRODUCER ──► queue.offer(msg)        (stamps ingest time)
    v
 +------------------------------------------+
 |  FreshnessQueue        capacity + TTL    |
 |  ArrayDeque + ReentrantLock / Condition  |
 |  AtomicLong counters                     |
 +------------------------------------------+
    |  CONSUMER ──► queue.take() / poll()    (drops stale here)
    v  Sender: UdpSender (send)  OR  TcpSender (broadcast)   — switchable
 Receiver (Ada)
```

One queue instance (created in `GwsMain`) is shared by both threads. The
producer side (`Source`: `NmeaFileSource` or `SerialSource`) is the only caller
of `offer()`; the consumer side (`Sender`: `UdpSender` or `TcpSender`) drains
via `take()`/`poll()`. `GwsMain` picks the source and transport at startup from
config. The two run on an `ExecutorService`; periodic stats run on a
`ScheduledExecutorService`. Thread-safety uses `java.util.concurrent`
(a `ReentrantLock` + `Condition`, `AtomicLong` counters) — no `synchronized`
/ `wait` / `notify`.

**Queue management (capacity)** — `queue.capacity` (default 1000)

- Bounded deque; `offer()` never blocks.
- When full, the **oldest** message is dropped (`droppedFull`) so the newest
  data always gets in. Bounds memory and worst-case backlog.

**Time & TTL** — `queue.ttlMillis` (milliseconds, default 5000; `<= 0` disables)

- Every message is stamped with its **ingest time** when `offer()` runs.
- At `take()`, if `now - ingest > ttlMillis` the message is discarded
  (`droppedStale`) instead of sent.
- TTL measures **only time spent in the queue** — the clock starts at `offer()`,
  so it does **not** include any delay *before* ingest (e.g. receiver → GWS).

```
 offer()                              take()
   |<------- dwell = now - ingest ------->|
   0 ms                                  age

   age <= TTL (e.g. 5000 ms)  -> send
   age >  TTL                 -> drop  (droppedStale)
```

Under normal load the consumer keeps up, depth stays ~0, and TTL never fires:
it is a backlog safety valve, not a per-message age filter. The two guards are
complementary — capacity bounds *count*, TTL bounds *age*.

**Why the defaults (1000 / 5000)?**

Grounded in real AIS reporting rates (ITU-R M.1371): a Class A vessel underway
reports its position every ~2–10 s (down to ~2 s when fast or maneuvering,
3 min at anchor); Class B reports every ~30 s. So one vessel is ~0.1–0.5 msg/s,
and a busy picture of a few hundred vessels is **tens of messages/sec** in
aggregate (the bundled 300-vessel sample runs at ~30/s).

- **`queue.ttlMillis = 5000`** — on the order of a typical underway report
  interval. It caps relay-added age below the point where a fresh report would
  normally already be due, without being so tight that ordinary jitter sheds
  good data. Tighten toward 2000–3000 for a fresher picture; do not go
  below ~2000 (that approaches even fast-mover report intervals).
- **`queue.capacity = 1000`** — sized from `capacity ≈ peak ingest rate × TTL`.
  Beyond that product, extra entries would exceed the TTL and be dropped as
  stale anyway. At ~30/s steady with bursts allowed (e.g. 200/s × 5 s = 1000),
  this gives generous headroom; memory is ~200 KB (≈200 B/message). Avoid very
  large values — they let backlog and latency grow before either guard engages,
  defeating the bound.

## Layout

```
ais-udp-prototype/
├── Makefile                  # build/run targets (make help)
├── data/                     # NMEA feeds
│   ├── sample_ais.nmea       # small replay sample (AIVDM sentences)
│   └── sim_1h_ais.nmea       # generated by tools/gen_ais.py (if created)
├── tools/
│   ├── gen_ais.py            # generator for large / long synthetic AIS feeds
│   └── serial_sim.sh         # serial-demo orchestration (used by `make serial-demo`)
├── gws-java/                 # GWS sender (Java, JDK only — no deps)
│   ├── gws.properties        # runtime config
│   └── src/*.java            # Source/Sender ifaces, FreshnessQueue, GwsMain, …
└── mission-ada/              # AIS receiver (Ada, GNAT.Sockets)
    ├── mission_receiver.gpr
    └── src/mission_receiver.adb
```

## Make targets

A `Makefile` wraps the common build/run options — `make help` lists them:

| Target | Action |
|---|---|
| `make build` | build the Java GWS + Ada receiver |
| `make recv-udp` / `recv-multicast` / `recv-tcp` | run the receiver in each mode |
| `make gws` / `gws-tcp` / `gws-serial DEVICE=…` | run the GWS (UDP file / TCP server / serial) |
| `make serial-pty` | create a virtual serial pair (macOS/Linux, needs `socat`) |
| `make serial-demo` | full serial → GWS → UDP → receiver demo, end to end |
| `make gen-data` | generate a ~1 h synthetic feed into `data/` |
| `make kill` | stop any running GWS / receiver / socat |

Override config inline, e.g. `make recv-tcp PORT=5000 IDLE=10` or
`make gws-serial DEVICE=/dev/ttys003`. The sections below show the equivalent
manual commands.

## Build

**Java GWS** (JDK 8+):

```bash
cd gws-java
mkdir -p out
javac -d out src/*.java
```

**Ada receiver** (GNAT / gprbuild):

```bash
cd mission-ada
gprbuild -P mission_receiver.gpr        # binary -> bin/mission_receiver
# or, without gprbuild:
cd src && gnatmake mission_receiver.adb
```

## Run — unicast loopback test

Terminal 1 (receiver):

```bash
./mission-ada/bin/mission_receiver 4001
```

Terminal 2 (GWS, with `mode.multicast = false`, `dest.host = 127.0.0.1`):

```bash
cd gws-java
java -cp out gws.GwsMain gws.properties
```

The receiver prints each sentence with a receive timestamp and source address.
The GWS logs periodic `[stats]` lines (via `java.util.logging`).

## Run — multicast test

In `gws.properties` set:

```
mode.multicast = true
dest.host = 239.192.0.1
```

Receiver (join the group; optional 3rd arg = local interface IP):

```bash
./mission-ada/bin/mission_receiver 4001 239.192.0.1
```

Then start the GWS as above. Multiple receivers can join the same group and all
receive the feed.

## Run — TCP test

TCP is selectable as an alternative transport. The GWS acts as a **TCP server**
on `dest.port`; subscribers connect in and receive the live sentence stream
(one CRLF-terminated line each). The bounded `FreshnessQueue` sheds load when a
subscriber is slow, so latency stays bounded on TCP just as it does on UDP.
Sentences sent while no client is connected are dropped (freshness over
completeness).

In `gws.properties` set:

```
mode.transport = tcp
```

Start the GWS first (it must be listening), then connect the receiver:

```bash
cd gws-java && java -cp out gws.GwsMain gws.properties      # listens on dest.port
./mission-ada/bin/mission_receiver tcp 127.0.0.1 4001 [idle_secs]   # connects in
```

The receiver reconnects automatically if the connection drops.

**Inactivity watchdog (TCP only).** A blocking read only notices a *clean*
teardown (the server process exiting sends FIN/RST). It will hang forever on a
silent death — server host crash, cable pull, firewall drop, or a hung server.
So the client runs an idle timer: if no data arrives within `idle_secs`
(default 15, `0` disables) it assumes the link is dead and reconnects. It is
implemented with `SO_RCVTIMEO` (turns the blocking read into a periodic poll) +
a last-data timestamp, with `SO_KEEPALIVE` on both ends as an OS-level backstop.

Set `idle_secs` **above the longest expected quiet period** in the feed, or the
watchdog will reconnect during a legitimate lull. UDP needs none of this
(connectionless — there is no link state to lose).

**Server heartbeat (TCP only).** To keep idle subscribers from tripping their
watchdog during a genuine lull, the GWS sends a keepalive line (`$GWSHB`) after
`tcp.heartbeatMillis` (default 3000, `0` disables) with no AIS traffic. The
receiver treats any line not starting with `!` as a non-sentence: it resets the
idle timer but is not printed or counted. Keep `tcp.heartbeatMillis` **below**
the receiver's `idle_secs` so silence only ever means a truly dead link.

## Serial source (development)

Instead of file replay, the GWS can read a live serial device
(`source.type = serial`). Pure JDK — a serial port is just a character device
read as a stream. On macOS/Linux, simulate one with `socat`:

```bash
socat -d -d pty,raw,echo=0 pty,raw,echo=0      # prints two /dev/ttysNNN paths
```

Point the GWS at one PTY and feed NMEA into the other:

```
source.type   = serial
serial.device = /dev/ttysNNN      # the GWS's end (2nd PTY printed by socat)
```

```bash
# write sentences into the other end (the writer PTY):
cat data/sample_ais.nmea > /dev/ttysMMM
```

The GWS reopens the device if the writer disconnects (serial reconnect).

## Generate a larger feed

`tools/gen_ais.py` writes a time-ordered stream of valid AIVDM Type 1 sentences
(correct 6-bit payload + NMEA checksum, moving vessels) for volume / endurance
testing:

```bash
python3 tools/gen_ais.py --vessels 300 --duration 3600 --interval 10 \
    --out data/sim_1h_ais.nmea     # ~1 h of data, ~30 sentences/s, 108k lines
```

Aggregate rate ≈ `vessels ÷ interval` per second. Point the GWS at it with
`replay.file = ../data/sim_1h_ais.nmea`; played at `replay.rate`, the file
represents `duration` seconds of real time.

## Proving the freshness guard works

Lower the TTL and push the rate up so the consumer can't keep pace:

```
queue.ttlMillis = 200
replay.rate = 2000
```

`droppedStale` (and `droppedFull` under burst) will climb while `depth` stays
bounded — latency is capped by design instead of growing.

## Notes / next steps for production

- `SerialSource` reads a character device directly (pure JDK). For robust
  production serial (baud rate, flow control, hot-plug) consider a native
  library such as jSerialComm; the `Source` interface is unchanged.
- TCP server (`mode.transport = tcp`) currently broadcasts to all subscribers
  and drops when none are connected. For production, consider per-client
  send buffers / backpressure policy and an explicit max-subscriber limit.
- Multi-part AIVDM fragments are passed through verbatim, preserving order from
  a single source thread. If reassembly is required, do it on the Ada side.
- Consider keep-latest-per-MMSI in the queue if the consumer only needs the
  most recent position per vessel — further reduces load under saturation.
- For multicast in the field: confirm IGMP querier, switch IGMP snooping, and
  the correct egress interface on multi-homed hosts.
```
