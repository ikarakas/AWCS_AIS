package gws;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/**
 * Producer. Replays NMEA sentences from a file into the queue at a
 * configurable rate. Stands in for the real serial AIS receiver in the
 * prototype (chosen input source: replay from file).
 *
 * The same threading shape as production: this thread does ONLY ingest.
 * It is decoupled from the sender by the queue, so a slow/blocked consumer
 * can never stall ingest (and vice-versa).
 */
final class NmeaFileSource implements Source {

    private static final Logger LOG = Logger.getLogger(NmeaFileSource.class.getName());

    private final Path file;
    private final FreshnessQueue queue;
    private final double rate;     // sentences/sec; <= 0 means as-fast-as-possible
    private final boolean loop;
    private volatile boolean running = true;

    NmeaFileSource(Path file, FreshnessQueue queue, double rate, boolean loop) {
        this.file = file;
        this.queue = queue;
        this.rate = rate;
        this.loop = loop;
    }

    @Override
    public void run() {
        final long intervalNanos = rate > 0 ? (long) (1_000_000_000.0 / rate) : 0L;
        try {
            do {
                try (BufferedReader r = Files.newBufferedReader(file)) {
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        queue.offer(new AisMessage(line, System.currentTimeMillis()));
                        if (intervalNanos > 0) LockSupport.parkNanos(intervalNanos);
                    }
                }
            } while (loop && running);
        } catch (IOException e) {
            LOG.severe("[source] error reading " + file + ": " + e.getMessage());
        }
        LOG.info("[source] stopped");
    }

    @Override
    public void stop() { running = false; }
}
