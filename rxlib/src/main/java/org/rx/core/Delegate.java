package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Field;
import java.util.EventObject;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Delegate<TSender extends EventPublisher<TSender>, TEvent> implements TripleAction<TSender, TEvent> {
    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> create() {
        return create((TripleAction<TSender, TEvent>[]) null);
    }

    @SafeVarargs
    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> create(TripleAction<TSender, TEvent>... delegates) {
        return new Delegate<TSender, TEvent>().combine(delegates);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> wrap(@NonNull EventPublisher<TSender> target, @NonNull String fnName) {
        Delegate<TSender, TEvent> d;
        Field field = Reflects.getFieldMap(target.getClass()).get(fnName);
        if (field != null) {
            d = (Delegate<TSender, TEvent>) field.get(target);
            if (d == null) {
                throw new InvalidException("Event {} not defined", fnName);
            }
        } else {
            if (!target.eventFlags().has(EventPublisher.EventFlags.DYNAMIC_ATTACH)) {
                throw new InvalidException("Event {} not defined", fnName);
            }
            d = IOC.<String, Delegate<TSender, TEvent>>weakMap(target, false).computeIfAbsent(fnName, k -> new Delegate<>());
        }
        return d;
    }

    final Set<TripleAction<TSender, TEvent>> invocations = new CopyOnWriteArraySet<>();
    volatile TripleAction<TSender, TEvent> firstInvocation;
    volatile TripleAction<TSender, TEvent> lastInvocation;

    public boolean isEmpty() {
        return invocations.isEmpty() && firstInvocation == null && lastInvocation == null;
    }

    public Delegate<TSender, TEvent> first(TripleAction<TSender, TEvent> delegate) {
        firstInvocation = delegate;
        return this;
    }

    public Delegate<TSender, TEvent> last(TripleAction<TSender, TEvent> delegate) {
        lastInvocation = delegate;
        return this;
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> replace(TripleAction<TSender, TEvent>... delegates) {
        invocations.clear();
        return combine(delegates);
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> combine(TripleAction<TSender, TEvent>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TEvent> delegate : delegates) {
            if (delegate == null) {
                continue;
            }
            if (!tryAs(delegate, Delegate.class, d -> invocations.addAll(d.invocations))) {
                invocations.add(delegate);
            }
        }
        return this;
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> remove(TripleAction<TSender, TEvent>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TEvent> delegate : delegates) {
            if (delegate == null) {
                continue;
            }
            if (!tryAs(delegate, Delegate.class, d -> invocations.removeAll(d.invocations))) {
                invocations.remove(delegate);
            }
        }
        return this;
    }

    public Delegate<TSender, TEvent> close() {
        tryClose(firstInvocation);
        for (TripleAction<TSender, TEvent> invocation : invocations) {
            tryClose(invocation);
        }
        tryClose(lastInvocation);
        return purge();
    }

    public Delegate<TSender, TEvent> purge() {
        invocations.clear();
        firstInvocation = lastInvocation = null;
        return this;
    }

    public <T extends EventObject> void invoke(T eventObj) throws Throwable {
        invoke((TSender) eventObj.getSource(), (TEvent) eventObj);
    }

    @Override
    public void invoke(@NonNull TSender target, TEvent event) throws Throwable {
        if (!innerInvoke(firstInvocation, target, event)) {
            return;
        }
        for (TripleAction<TSender, TEvent> delegate : invocations) {
            if (!innerInvoke(delegate, target, event)) {
                break;
            }
        }
        innerInvoke(lastInvocation, target, event);
    }

    boolean innerInvoke(TripleAction<TSender, TEvent> delegate, TSender target, TEvent event) throws Throwable {
        if (delegate == null) {
            return true;
        }
        try {
            delegate.invoke(target, event);
            EventArgs args = as(event, EventArgs.class);
            return args == null || !args.isCancel() && !args.isHandled();
        } catch (Throwable e) {
            if (!target.eventFlags().has(EventPublisher.EventFlags.QUIETLY)) {
                throw e;
            }
            TraceHandler.INSTANCE.log(e);
        }
        return true;
    }
}
