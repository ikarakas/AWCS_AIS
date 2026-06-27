package gws;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Producer, serial variant. Reads NMEA sentences line-by-line from a serial
 * device (or any character stream / FIFO) and offers them to the queue.
 *
 * Pure JDK: on Unix a serial port is a character device that can be read as a
 * stream, so no native serial library is needed. To simulate a serial port on
 * macOS/Linux for development, create a virtual pair with socat:
 *
 *     socat -d -d pty,raw,echo=0 pty,raw,echo=0
 *
 * point serial.device at one PTY and write NMEA into the other. A named pipe
 * (mkfifo) works too as a zero-dependency approximation.
 *
 * A live port has no EOF; if the stream closes (writer disconnects) this
 * reopens after a short pause, mirroring a serial reconnect.
 */
final class SerialSource implements Source {

    private static final Logger LOG = Logger.getLogger(SerialSource.class.getName());

    private final Path device;
    private final FreshnessQueue queue;
    private volatile boolean running = true;

    SerialSource(Path device, FreshnessQueue queue) {
        this.device = device;
        this.queue = queue;
    }

    @Override
    public void run() {
        while (running) {
            try (BufferedReader r = Files.newBufferedReader(device)) {
                LOG.info("[source] reading serial " + device);
                String line;
                while (running && (line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    queue.offer(new AisMessage(line, System.currentTimeMillis()));
                }
            } catch (IOException e) {
                if (running) LOG.warning("[source] serial " + device + ": " + e.getMessage());
            }
            // Stream closed (writer gone); pause briefly, then reopen.
            if (running) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOG.info("[source] stopped");
    }

    @Override
    public void stop() { running = false; }
}
