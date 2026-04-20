package org.rx.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class DiagnosticMetric {
    private final long timestampMillis;
    private final String name;
    private final double value;
    private final String tags;
    private final String incidentId;
}
