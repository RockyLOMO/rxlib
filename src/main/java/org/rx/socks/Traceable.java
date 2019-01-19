package org.rx.socks;

import org.rx.common.Disposable;
import org.rx.common.LogWriter;

import static org.rx.common.Contract.isNull;

public abstract class Traceable extends Disposable {
    private LogWriter tracer;

    public LogWriter getTracer() {
        return tracer;
    }

    public synchronized void setTracer(LogWriter tracer) {
        this.tracer = isNull(tracer, new LogWriter());
    }
}
