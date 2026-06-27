package gws;

/**
 * A producer that ingests NMEA sentences and offers them to the FreshnessQueue.
 * Implemented by {@link NmeaFileSource} (replay) and {@link SerialSource}
 * (live device), selected at startup by {@link GwsMain}.
 */
interface Source extends Runnable {

    /** Stop ingesting. */
    void stop();
}
