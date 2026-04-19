package org.rx.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class ThreadCpuSample {
    private final long timestampMillis;
    private final long threadId;
    private final String threadName;
    private final String threadState;
    private final long cpuDeltaNanos;
    private final long stackHash;
    private final String stackTrace;
}
