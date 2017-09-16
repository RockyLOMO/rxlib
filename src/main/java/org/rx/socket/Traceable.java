package org.rx.socket;

import org.rx.DateTime;

import static org.rx.Contract.isNull;

public abstract class Traceable {
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
