package org.rx.bean;

import com.google.common.base.Stopwatch;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.App;
import org.rx.core.EventArgs;
import org.rx.util.function.Func;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Getter
@RequiredArgsConstructor
public class ProceedEventArgs extends EventArgs {
    private final Class<?> declaringType;
    private final Object[] parameters;
    private final boolean isVoid;
    private UUID traceId;

    private Object returnValue;
    private long elapsedMillis = -1;
    @Setter
    private Throwable error;
    @Setter
    private LogStrategy logStrategy;
    @Setter
    private List<String> logTypeWhitelist;

    public UUID getTraceId() {
        if (traceId == null) {
            traceId = App.combId();
        }
        return traceId;
    }

    public <T> T proceed(@NonNull Func<T> proceed) throws Throwable {
        Stopwatch watcher = Stopwatch.createStarted();
        T retVal = proceed.invoke();
        elapsedMillis = watcher.elapsed(TimeUnit.MILLISECONDS);
        returnValue = retVal;
        return retVal;
    }
}
