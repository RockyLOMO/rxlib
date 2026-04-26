package org.rx.net.transport.hybrid;

import org.rx.core.Tasks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class HybridSequenceWindow {
    private final Set<Long> seen = ConcurrentHashMap.newKeySet();
    private final int ttlMillis;

    HybridSequenceWindow(int ttlMillis) {
        this.ttlMillis = Math.max(1000, ttlMillis);
    }

    boolean mark(long seq) {
        if (!seen.add(seq)) {
            return false;
        }
        Tasks.setTimeout(() -> seen.remove(seq), ttlMillis);
        return true;
    }

    void clear() {
        seen.clear();
    }
}
