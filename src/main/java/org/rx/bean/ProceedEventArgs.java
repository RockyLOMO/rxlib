package org.rx.bean;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.App;
import org.rx.core.EventArgs;
import org.rx.util.function.Func;

import java.util.Set;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class ProceedEventArgs extends EventArgs {
    private static final long serialVersionUID = -2969747570419733673L;
    static final FastThreadLocal<UUID> traceIdLocal = new FastThreadLocal<UUID>() {
        @Override
        protected UUID initialValue() throws Exception {
            return App.combId();
        }
    };

    private final Class<?> declaringType;
    private final Object[] parameters;
    private final boolean isVoid;

    private Object returnValue;
    private long elapsedMillis = -1;
    @Setter
    private Throwable error;
    @Setter
    private LogStrategy logStrategy;
    @Setter
    private Set<String> logTypeWhitelist;

    public UUID getTraceId() {
        return traceIdLocal.get();
    }

    public <T> T proceed(@NonNull Func<T> proceed) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            T retVal = proceed.invoke();
            returnValue = retVal;
            return retVal;
        } finally {
            elapsedMillis = System.currentTimeMillis() - start;
        }
    }
}
