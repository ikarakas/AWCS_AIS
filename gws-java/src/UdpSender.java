package gws;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Consumer. Drains the FreshnessQueue and emits each sentence as one UDP
 * datagram (raw NMEA passthrough). Switchable unicast / multicast.
 *
 * UDP send() never blocks waiting on a slow consumer, so latency cannot
 * accumulate on the sender. Load is shed deterministically by the queue
 * (capacity + TTL).
 */
final class UdpSender implements Sender {

    private static final Logger LOG = Logger.getLogger(UdpSender.class.getName());

    private final FreshnessQueue queue;
    private final DatagramSocket socket;
    private final InetAddress dest;
    private final int port;
    private volatile boolean running = true;
    private long sent = 0;

    UdpSender(FreshnessQueue queue, GwsConfig cfg) throws IOException {
        this.queue = queue;
        this.dest = InetAddress.getByName(cfg.host); // unicast IP or multicast group
        this.port = cfg.port;

        if (cfg.multicast) {
            MulticastSocket ms = new MulticastSocket();
            ms.setTimeToLive(cfg.multicastTtl);
            if (cfg.iface != null && !cfg.iface.isEmpty()) {
                ms.setInterface(InetAddress.getByName(cfg.iface));
            }
            this.socket = ms;
        } else {
            this.socket = new DatagramSocket();
        }
    }

    @Override
    public void run() {
        try {
            while (running) {
                AisMessage m = queue.take();
                byte[] b = m.sentence.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(b, b.length, dest, port));
                sent++;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running) LOG.severe("[sender] error: " + e.getMessage());
        }
        LOG.info("[sender] stopped");
    }

    @Override
    public long sent() { return sent; }

    @Override
    public void stop() {
        running = false;
        socket.close();
    }
}
