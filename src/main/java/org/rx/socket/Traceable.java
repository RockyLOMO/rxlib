package org.rx.socket;

import org.rx.Disposable;
import org.rx.Logger;
import org.rx.bean.DateTime;

import static org.rx.Contract.isNull;

public abstract class Traceable extends Disposable {
    private Logger tracer;

    public Logger getTracer() {
        return tracer;
    }

    public synchronized void setTracer(Logger tracer) {
        this.tracer = isNull(tracer, new Logger());
    }
}
