package gws;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/** Startup configuration, loaded from a .properties file. */
final class GwsConfig {

    String transport;        // "udp" (default) or "tcp" (GWS acts as TCP server)
    long tcpHeartbeatMillis; // TCP idle keepalive interval (0 = off)
    boolean multicast;       // false = unicast, true = multicast (UDP only)
    String host;             // unicast destination IP, or multicast group address
    int port;                // UDP destination port, or TCP listen port
    String iface;            // local interface IP for multicast egress (optional)
    int multicastTtl;        // multicast hop limit

    String sourceType;       // "file" (default replay) or "serial"
    String serialDevice;     // device / FIFO path when sourceType = serial

    int queueCapacity;       // max messages held before dropping oldest
    long queueTtlMillis;     // max age before a message is dropped at send time

    String replayFile;       // NMEA source file
    double replayRate;       // sentences/sec (0 = as fast as possible)
    boolean replayLoop;      // loop the file forever

    long statsIntervalMillis;

    static GwsConfig load(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            p.load(in);
        }
        GwsConfig c = new GwsConfig();
        c.transport           = p.getProperty("mode.transport", "udp").trim().toLowerCase();
        c.tcpHeartbeatMillis  = Long.parseLong(p.getProperty("tcp.heartbeatMillis", "3000"));
        c.multicast           = Boolean.parseBoolean(p.getProperty("mode.multicast", "false"));
        c.host                = p.getProperty("dest.host", "127.0.0.1");
        c.port                = Integer.parseInt(p.getProperty("dest.port", "4001"));
        c.iface               = p.getProperty("multicast.interface", "");
        c.multicastTtl        = Integer.parseInt(p.getProperty("multicast.ttl", "1"));
        c.queueCapacity       = Integer.parseInt(p.getProperty("queue.capacity", "1000"));
        c.queueTtlMillis      = Long.parseLong(p.getProperty("queue.ttlMillis", "5000"));
        c.sourceType          = p.getProperty("source.type", "file").trim().toLowerCase();
        c.serialDevice        = p.getProperty("serial.device", "");
        c.replayFile          = p.getProperty("replay.file", "../data/sample_ais.nmea");
        c.replayRate          = Double.parseDouble(p.getProperty("replay.rate", "10"));
        c.replayLoop          = Boolean.parseBoolean(p.getProperty("replay.loop", "true"));
        c.statsIntervalMillis = Long.parseLong(p.getProperty("stats.intervalMillis", "2000"));
        return c;
    }
}
