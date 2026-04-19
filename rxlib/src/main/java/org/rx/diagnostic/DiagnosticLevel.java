package org.rx.diagnostic;

public enum DiagnosticLevel {
    OFF,
    LIGHT,
    DIAG,
    FORENSIC;

    public boolean atLeast(DiagnosticLevel level) {
        return ordinal() >= level.ordinal();
    }
}

