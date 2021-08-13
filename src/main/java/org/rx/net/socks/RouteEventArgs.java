package org.rx.net.socks;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.NEventArgs;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

import static org.rx.core.App.eq;

@Getter
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RouteEventArgs extends NEventArgs<Upstream> {
    private static final long serialVersionUID = 3693349106737770022L;

    private final InetSocketAddress sourceEndpoint;
    private final UnresolvedEndpoint destinationEndpoint;
    private boolean changed;
    private int failCount;

    @Override
    public void setValue(Upstream value) {
        changed = changed || !eq(super.getValue(), value);
        super.setValue(value);
    }

    public void reset() {
        changed = false;
        failCount++;
    }
}
