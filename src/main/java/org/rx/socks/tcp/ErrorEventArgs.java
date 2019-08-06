package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.common.NEventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class ErrorEventArgs extends NEventArgs<String> {
    private boolean cancel;

    public ErrorEventArgs(String causeString) {
        super(causeString);
    }
}
