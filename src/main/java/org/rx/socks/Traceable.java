package org.rx.socks;

import org.rx.core.Disposable;
import org.rx.core.LogWriter;

import static org.rx.core.Contract.isNull;

public abstract class Traceable extends Disposable {
    private LogWriter tracer;

    public LogWriter getTracer() {
        return tracer;
    }

    public synchronized void setTracer(LogWriter tracer) {
        this.tracer = isNull(tracer, new LogWriter());
    }
}
