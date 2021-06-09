package org.rx.net.socks;

import lombok.Getter;
import lombok.NonNull;
import org.rx.core.EventArgs;
import org.rx.net.socks.upstream.Upstream;

import java.net.SocketAddress;

import static org.rx.core.App.eq;

@Getter
public class ReconnectingEventArgs extends EventArgs {
    private SocketAddress remoteAddress;
    private Upstream upstream;
    private boolean changed;
    private int failCount;

    public ReconnectingEventArgs(@NonNull SocketAddress remoteAddress, Upstream upstream) {
        this.remoteAddress = remoteAddress;
        this.upstream = upstream;
    }

    public void setRemoteAddress(@NonNull SocketAddress remoteAddress) {
        changed = changed || !eq(this.remoteAddress, remoteAddress);
        this.remoteAddress = remoteAddress;
    }

    public void setUpstream(@NonNull Upstream upstream) {
        changed = changed || !eq(this.upstream, upstream);
        this.upstream = upstream;
    }

    public void reset() {
        changed = false;
        failCount++;
    }
}
