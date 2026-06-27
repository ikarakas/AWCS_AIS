package gws;

import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * GWS entry point. Wires source -> bounded queue -> UDP sender and logs
 * periodic stats (queue depth and drop counters) so backlog is observable
 * rather than hidden.
 *
 * Threading is delegated to java.util.concurrent: an ExecutorService runs the
 * producer and consumer, and a ScheduledExecutorService drives the stats
 * heartbeat instead of a hand-rolled while(true)/sleep loop.
 *
 * Usage: java -cp out gws.GwsMain [path/to/gws.properties]
 */
public final class GwsMain {

    private static final Logger LOG = Logger.getLogger(GwsMain.class.getName());

    public static void main(String[] args) throws Exception {
        // Compact single-line log format (default JUL is two lines per record).
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$-6s %5$s%n");

        String cfgPath = args.length > 0 ? args[0] : "gws.properties";
        GwsConfig cfg = GwsConfig.load(cfgPath);

        FreshnessQueue queue = new FreshnessQueue(cfg.queueCapacity, cfg.queueTtlMillis);

        Source source = "serial".equals(cfg.sourceType)
                ? new SerialSource(Paths.get(cfg.serialDevice), queue)
                : new NmeaFileSource(Paths.get(cfg.replayFile), queue,
                                     cfg.replayRate, cfg.replayLoop);
        Sender sender = "tcp".equals(cfg.transport)
                ? new TcpSender(queue, cfg)
                : new UdpSender(queue, cfg);

        // Worker pool replaces hand-managed Thread objects. Its (non-daemon)
        // threads keep the JVM alive while the pipeline runs.
        ExecutorService workers = Executors.newFixedThreadPool(2, namedFactory("gws-worker"));
        workers.submit(sender);
        workers.submit(source);

        // Periodic stats on a scheduler instead of a while(true)/sleep loop.
        // Growing depth => consumer too slow. droppedStale > 0 => TTL is
        // shedding old data (working as intended under overload).
        ScheduledExecutorService stats = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("gws-stats"));
        stats.scheduleAtFixedRate(
                () -> LOG.info(String.format(
                        "[stats] depth=%d accepted=%d sent=%d droppedFull=%d droppedStale=%d",
                        queue.size(), queue.accepted(), sender.sent(),
                        queue.droppedFull(), queue.droppedStale())),
                cfg.statsIntervalMillis, cfg.statsIntervalMillis, TimeUnit.MILLISECONDS);

        String transportLabel = "tcp".equals(cfg.transport)
                ? "TCP-SERVER" : (cfg.multicast ? "UDP/MULTICAST" : "UDP/UNICAST");
        String dest = "tcp".equals(cfg.transport)
                ? ("listen :" + cfg.port) : (cfg.host + ":" + cfg.port);
        String src = "serial".equals(cfg.sourceType)
                ? ("serial " + cfg.serialDevice)
                : (cfg.replayFile + " @" + cfg.replayRate + "/s");
        LOG.info(String.format(
                "GWS up: %s -> %s  cap=%d ttl=%dms src=%s",
                transportLabel, dest, cfg.queueCapacity, cfg.queueTtlMillis, src));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            source.stop();
            sender.stop();
            stats.shutdownNow();
            workers.shutdownNow();
        }, "gws-shutdown"));
    }

    /** Names pool threads so logs and stack dumps stay readable. */
    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> new Thread(r, prefix + "-" + n.getAndIncrement());
    }

    /** Like {@link #namedFactory} but produces a single daemon thread. */
    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
