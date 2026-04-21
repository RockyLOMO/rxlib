package org.rx.diagnostic;

import lombok.Getter;

@Getter
public final class DiagnosticMetric {
    private final long timestampMillis;
    private final String name;
    private final double value;
    private final String tags;
    private final String incidentId;
    private final long stackHash;

    public DiagnosticMetric(long timestampMillis, String name, double value, String tags, String incidentId) {
        this(timestampMillis, name, value, tags, incidentId, 0L);
    }

    public DiagnosticMetric(long timestampMillis, String name, double value, String tags, String incidentId, long stackHash) {
        this.timestampMillis = timestampMillis;
        this.name = name;
        this.value = value;
        this.tags = tags;
        this.incidentId = incidentId;
        this.stackHash = stackHash;
    }
}
