package org.rx.net.socks;

import lombok.Getter;
import lombok.NonNull;
import org.rx.core.EventArgs;
import org.rx.net.socks.upstream.Upstream;

import static org.rx.core.App.eq;

@Getter
public class ReconnectingEventArgs extends EventArgs {
    private Upstream upstream;
    private boolean changed;
    private int failCount;

    public ReconnectingEventArgs(@NonNull Upstream upstream) {
        this.upstream = upstream;
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
