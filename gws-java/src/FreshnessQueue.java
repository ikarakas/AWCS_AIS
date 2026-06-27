package gws;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, freshness-enforcing queue between ingest and send.
 *
 * Two independent guards, per the chosen "Capacity + TTL" policy:
 *
 *   1. Capacity  - when full, the OLDEST message is dropped to make room.
 *                  This bounds memory and bounds worst-case backlog.
 *   2. TTL       - at dequeue time, any message older than ttlMillis is
 *                  discarded instead of sent. This bounds how long a report
 *                  may age in the queue: a consumer never receives a stale
 *                  position report.
 *
 * Design note: for real-time position data, freshness beats completeness.
 * Dropping an old AIS report is preferable to delivering a track that has
 * already moved miles from where the report says it is.
 *
 * Concurrency: built on java.util.concurrent primitives rather than the
 * intrinsic monitor. A single ReentrantLock guards the deque; a Condition
 * replaces wait()/notify(); counters are AtomicLong so observers never
 * contend on the lock. The "drop oldest when full" overflow policy is why
 * this is a custom class and not a stock BlockingQueue (those block the
 * producer on full instead of shedding the oldest entry).
 */
final class FreshnessQueue {

    private final ArrayDeque<AisMessage> dq = new ArrayDeque<>();
    private final int capacity;
    private final long ttlMillis;   // <= 0 disables the TTL guard

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    // Lightweight counters for observability. Atomic so size()/counter reads
    // do not have to take the lock.
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();

    FreshnessQueue(int capacity, long ttlMillis) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
    }

    /** Producer side. Never blocks; drops oldest when full. */
    void offer(AisMessage m) {
        lock.lock();
        try {
            while (dq.size() >= capacity) {
                dq.pollFirst();
                droppedFull.incrementAndGet();
            }
            dq.addLast(m);
            accepted.incrementAndGet();
            notEmpty.signal();   // one item added => wake one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Consumer side. Blocks until a FRESH message is available.
     * Stale messages (older than the TTL) are silently skipped and counted.
     */
    AisMessage take() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                while (dq.isEmpty()) {
                    notEmpty.await();
                }
                AisMessage m = dq.pollFirst();
                if (ttlMillis > 0 && (System.currentTimeMillis() - m.ingestMillis) > ttlMillis) {
                    droppedStale.incrementAndGet();
                    continue; // discard stale, try the next one
                }
                return m;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Like {@link #take()} but returns null if no fresh message becomes
     * available within timeoutMillis. Lets a consumer wake periodically (e.g.
     * to emit a heartbeat) instead of blocking indefinitely.
     */
    AisMessage poll(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long remaining = timeoutMillis * 1_000_000L; // ms -> ns
            while (true) {
                while (dq.isEmpty()) {
                    if (remaining <= 0L) return null;
                    remaining = notEmpty.awaitNanos(remaining);
                }
                AisMessage m = dq.pollFirst();
                if (ttlMillis > 0 && (System.currentTimeMillis() - m.ingestMillis) > ttlMillis) {
                    droppedStale.incrementAndGet();
                    continue; // discard stale, keep waiting within the budget
                }
                return m;
            }
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return dq.size();
        } finally {
            lock.unlock();
        }
    }

    long droppedFull()  { return droppedFull.get(); }
    long droppedStale() { return droppedStale.get(); }
    long accepted()     { return accepted.get(); }
}
