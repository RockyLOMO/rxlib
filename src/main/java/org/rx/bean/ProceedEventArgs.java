package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.EventArgs;

import java.util.List;

@Getter
@Setter
@RequiredArgsConstructor
public class ProceedEventArgs extends EventArgs {
    private final Class<?> declaringType;
    private final Object[] parameters;
    private LogStrategy logStrategy;
    private List<String> logTypeWhitelist;

    private Object returnValue;
    private long elapsedMillis;
    private Throwable error;
}
