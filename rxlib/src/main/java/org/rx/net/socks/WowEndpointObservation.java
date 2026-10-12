package org.rx.net.socks;

import com.alibaba.fastjson2.annotation.JSONField;
import java.io.Serializable;

/** Fury transports the fields; textual diagnostics must never print the credentials. */
public final class WowEndpointObservation implements Serializable {
    private static final long serialVersionUID = 1L;
    public String clientId;
    @JSONField(serialize = false)
    public String writerKey;
    @JSONField(serialize = false)
    public String rpcToken;
    public String readyIp;
    public boolean ready;

    public WowEndpointObservation() { }

    public WowEndpointObservation(String clientId, String writerKey, String rpcToken, String readyIp, boolean ready) {
        this.clientId = clientId; this.writerKey = writerKey; this.rpcToken = rpcToken;
        this.readyIp = readyIp; this.ready = ready;
    }

    @Override public String toString() { return "WowEndpointObservation[credentials redacted]"; }
}
