package org.rx.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.common.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class PackEventArgs<T> extends NEventArgs<SessionPack> {
    private final T client;

    public PackEventArgs(T client, SessionPack pack) {
        super(pack);
        this.client = client;
    }
}
