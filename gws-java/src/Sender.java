package gws;

/**
 * A transport that drains the FreshnessQueue and emits sentences downstream.
 * Implemented by {@link UdpSender} and {@link TcpSender} so the wiring in
 * {@link GwsMain} can pick a transport at startup without caring which.
 */
interface Sender extends Runnable {

    /** Sentences successfully emitted so far (for the stats line). */
    long sent();

    /** Stop draining and release the socket(s). */
    void stop();
}
