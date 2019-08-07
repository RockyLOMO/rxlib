package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.common.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class ErrorEventArgs<T> extends NEventArgs<Throwable> {
    private T client;

    public ErrorEventArgs(T client, Throwable value) {
        super(value);
        this.client = client;
    }
}
