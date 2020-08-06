package org.rx.net.tcp;

import lombok.Getter;
import org.rx.core.NEventArgs;

import java.io.Serializable;

@Getter
public class PackEventArgs extends NEventArgs<Serializable> {
    private final ITcpClient client;

    public PackEventArgs(ITcpClient client, Serializable pack) {
        super(pack);
        this.client = client;
    }
}
