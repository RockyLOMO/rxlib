package org.rx.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class ThreadStateSample {
    private final long timestampMillis;
    private final long threadId;
    private final String threadName;
    private final String threadState;
    private final long blockedMillis;
    private final long waitedMillis;
    private final long stateDurationMillis;
    private final String lockName;
    private final long lockOwnerId;
    private final String lockOwnerName;
    private final long stackHash;
    private final String stackTrace;
}
