package org.rx.socks;

import org.rx.common.Disposable;
import org.rx.common.Logger;

import static org.rx.common.Contract.isNull;

public abstract class Traceable extends Disposable {
    private Logger tracer;

    public Logger getTracer() {
        return tracer;
    }

    public synchronized void setTracer(Logger tracer) {
        this.tracer = isNull(tracer, new Logger());
    }
}
