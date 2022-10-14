package org.rx.net.transport;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class TcpServerEventArgs<T extends Serializable> extends NEventArgs<T> {
    private static final long serialVersionUID = 6292438476559047718L;
    private final TcpClient client;

    public TcpServerEventArgs(TcpClient client, T value) {
        super(value);
        this.client = client;
    }
}
