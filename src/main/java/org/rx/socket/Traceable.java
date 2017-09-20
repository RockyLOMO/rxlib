package org.rx.socket;

import org.rx.Disposable;
import org.rx.bean.DateTime;

import static org.rx.Contract.isNull;

public abstract class Traceable extends Disposable {
    private Tracer tracer;

    public Tracer getTracer() {
        return tracer;
    }

    public synchronized void setTracer(Tracer tracer) {
        this.tracer = isNull(tracer, new Tracer());
    }

    protected String getTimeString() {
        return new DateTime().toString("yyyy-MM-dd HH:mm:ss.SSS");
    }
}
