package gws;

/**
 * One AIS NMEA sentence plus the wall-clock time it was ingested by the GWS.
 * The ingest time is used only internally, for the TTL (freshness) check.
 * It is NOT placed on the wire (raw NMEA passthrough was the chosen format).
 */
final class AisMessage {
    final String sentence;
    final long ingestMillis;

    AisMessage(String sentence, long ingestMillis) {
        this.sentence = sentence;
        this.ingestMillis = ingestMillis;
    }
}
