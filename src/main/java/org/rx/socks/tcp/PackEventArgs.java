package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class PackEventArgs<T> extends NEventArgs<Serializable> {
    private final T client;

    public PackEventArgs(T client, Serializable pack) {
        super(pack);
        this.client = client;
    }
}
