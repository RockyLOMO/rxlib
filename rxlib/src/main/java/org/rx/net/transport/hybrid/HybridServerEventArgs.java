package org.rx.net.transport.hybrid;

import lombok.Getter;
import lombok.ToString;
import org.rx.core.NEventArgs;

@Getter
@ToString(callSuper = true)
public final class HybridServerEventArgs<T> extends NEventArgs<T> {
    private static final long serialVersionUID = -6422036898922402088L;

    private final HybridSession session;

    public HybridServerEventArgs(HybridSession session, T value) {
        super(value);
        this.session = session;
    }
}
