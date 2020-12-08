package org.rx.net.rpc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class RpcServerEventArgs<T extends Serializable> extends NEventArgs<T> {
    private static final long serialVersionUID = 6292438476559047718L;
    private final RpcServerClient client;

    public RpcServerEventArgs(RpcServerClient client, T value) {
        super(value);
        this.client = client;
    }
}
