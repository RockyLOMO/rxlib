package org.rx.net.socks;

import lombok.Getter;
import lombok.NonNull;
import org.rx.core.EventArgs;
import org.rx.net.socks.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;

import static org.rx.core.App.eq;

@Getter
public class ReconnectingEventArgs extends EventArgs {
    private UnresolvedEndpoint destinationEndpoint;
    private Upstream upstream;
    private boolean changed;
    private int failCount;

    public ReconnectingEventArgs(@NonNull UnresolvedEndpoint destinationEndpoint, Upstream upstream) {
        this.destinationEndpoint = destinationEndpoint;
        this.upstream = upstream;
    }

    public void setDestinationEndpoint(@NonNull UnresolvedEndpoint destinationEndpoint) {
        changed = changed || !eq(this.destinationEndpoint, destinationEndpoint);
        this.destinationEndpoint = destinationEndpoint;
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
