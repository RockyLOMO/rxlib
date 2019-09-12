package org.rx.socks.tcp2;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class PackEventArgs<T> extends NEventArgs<SessionPack> {
    private final T client;

    public PackEventArgs(T client, SessionPack pack) {
        super(pack);
        this.client = client;
    }
}
