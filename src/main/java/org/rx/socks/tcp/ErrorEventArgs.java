package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class ErrorEventArgs<T> extends NEventArgs<Throwable> {
    private SessionClient<T> client;

    public ErrorEventArgs(SessionClient<T> client, Throwable value) {
        super(value);
        this.client = client;
    }
}
