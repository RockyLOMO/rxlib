package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class PackEventArgs<T> extends NEventArgs<SessionPacket> {
    private final T client;

    public PackEventArgs(T client, SessionPacket pack) {
        super(pack);
        this.client = client;
    }
}
