package org.rx.socks.tcp;

import lombok.Getter;
import org.rx.core.NEventArgs;

import java.io.Serializable;

@Getter
public class PackEventArgs<T> extends NEventArgs<Serializable> {
    private final SessionClient<T> client;

    public PackEventArgs(SessionClient<T> client, Serializable pack) {
        super(pack);
        this.client = client;
    }
}
