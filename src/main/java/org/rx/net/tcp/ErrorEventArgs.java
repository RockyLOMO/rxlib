package org.rx.net.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class ErrorEventArgs extends NEventArgs<Throwable> {
    private final ITcpClient client;

    public ErrorEventArgs(ITcpClient client, Throwable value) {
        super(value);
        this.client = client;
    }
}
