package org.rx.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class DiagnosticIncidentEvent {
    private final String incidentId;
    private final DiagnosticIncidentType type;
    private final DiagnosticLevel level;
    private final long startMillis;
    private final String summary;
    private final String bundlePath;
}
