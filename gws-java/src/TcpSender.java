package gws;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Consumer, TCP-server variant. Listens for subscriber connections and
 * broadcasts each dequeued sentence to every connected client as a
 * CRLF-terminated line (NMEA stream framing).
 *
 * The bounded FreshnessQueue keeps latency bounded on TCP: a slow/blocked
 * subscriber cannot make latency grow, because the queue sheds load
 * (drop-oldest + TTL) exactly as it does for UDP. The queue is drained
 * continuously; if no client is connected the sentence is simply discarded
 * (freshness over completeness).
 */
final class TcpSender implements Sender {

    private static final Logger LOG = Logger.getLogger(TcpSender.class.getName());

    /** Keepalive line sent when idle; ignored by subscribers (not an AIS sentence). */
    private static final byte[] HEARTBEAT = "$GWSHB\r\n".getBytes(StandardCharsets.US_ASCII);

    private final FreshnessQueue queue;
    private final ServerSocket server;
    private final long heartbeatMillis;   // 0 disables the heartbeat
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private long sent = 0;

    TcpSender(FreshnessQueue queue, GwsConfig cfg) throws IOException {
        this.queue = queue;
        this.heartbeatMillis = cfg.tcpHeartbeatMillis;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(cfg.port));
    }

    @Override
    public void run() {
        // Accept connections on a daemon thread; this thread drains + broadcasts.
        Thread accept = new Thread(this::acceptLoop, "tcp-accept");
        accept.setDaemon(true);
        accept.start();

        try {
            while (running) {
                // Wait at most one heartbeat interval for the next sentence; if
                // none arrives, send a heartbeat so idle subscribers' watchdogs
                // stay fed. heartbeatMillis <= 0 disables (block indefinitely).
                AisMessage m = heartbeatMillis > 0 ? queue.poll(heartbeatMillis)
                                                   : queue.take();
                if (m != null) {
                    byte[] b = (m.sentence + "\r\n").getBytes(StandardCharsets.US_ASCII);
                    if (writeAll(b)) sent++;
                } else {
                    writeAll(HEARTBEAT); // idle: keepalive only, not counted as sent
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        closeAll();
        LOG.info("[sender] stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);  // send each sentence promptly, don't coalesce
                s.setKeepAlive(true);   // OS-level dead-peer detection backstop
                clients.add(s);
                LOG.info("[sender] client connected: " + s.getRemoteSocketAddress()
                        + " (" + clients.size() + " total)");
            } catch (IOException e) {
                if (running) LOG.warning("[sender] accept error: " + e.getMessage());
                return; // server socket closed by stop()
            }
        }
    }

    /**
     * Write one line to every client; drop any that error. Returns true if at
     * least one client received it (no client connected -> false, i.e. dropped,
     * freshness over completeness).
     */
    private boolean writeAll(byte[] b) {
        boolean delivered = false;
        for (Socket s : clients) {
            try {
                OutputStream out = s.getOutputStream();
                out.write(b);
                out.flush();
                delivered = true;
            } catch (IOException e) {
                clients.remove(s);
                closeQuietly(s);
                LOG.info("[sender] client dropped: " + s.getRemoteSocketAddress()
                        + " (" + clients.size() + " left)");
            }
        }
        return delivered;
    }

    @Override
    public long sent() { return sent; }

    @Override
    public void stop() {
        running = false;
        closeQuietly(server);
        closeAll();
    }

    private void closeAll() {
        for (Socket s : clients) closeQuietly(s);
        clients.clear();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { c.close(); } catch (IOException ignore) { /* best effort */ }
    }
}
