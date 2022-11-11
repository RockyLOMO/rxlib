package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.tryAs;
import static org.rx.core.Extends.tryClose;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Delegate<TSender extends EventPublisher<TSender>, TArgs extends EventArgs> implements TripleAction<TSender, TArgs> {
    public static <TSender extends EventPublisher<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create() {
        return create((TripleAction<TSender, TArgs>[]) null);
    }

    @SafeVarargs
    public static <TSender extends EventPublisher<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create(TripleAction<TSender, TArgs>... delegates) {
        return new Delegate<TSender, TArgs>().combine(delegates);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <TSender extends EventPublisher<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> wrap(@NonNull EventPublisher<TSender> target, @NonNull String fnName) {
        Delegate<TSender, TArgs> d;
        Field field = Reflects.getFieldMap(target.getClass()).get(fnName);
        if (field != null) {
            d = (Delegate<TSender, TArgs>) field.get(target);
            if (d == null) {
                throw new InvalidException("Event {} not defined", fnName);
            }
        } else {
            if (!target.eventFlags().has(EventPublisher.EventFlags.DYNAMIC_ATTACH)) {
                throw new InvalidException("Event {} not defined", fnName);
            }
            d = Extends.<String, Delegate<TSender, TArgs>>weakMap(target).computeIfAbsent(fnName, k -> new Delegate<>());
        }
        return d;
    }

    final Set<TripleAction<TSender, TArgs>> invocations = new CopyOnWriteArraySet<>();
    volatile TripleAction<TSender, TArgs> firstInvocation;
    volatile TripleAction<TSender, TArgs> lastInvocation;

    public boolean isEmpty() {
        return invocations.isEmpty() && firstInvocation == null && lastInvocation == null;
    }

    public Delegate<TSender, TArgs> first(TripleAction<TSender, TArgs> delegate) {
        firstInvocation = delegate;
        return this;
    }

    public Delegate<TSender, TArgs> last(TripleAction<TSender, TArgs> delegate) {
        lastInvocation = delegate;
        return this;
    }

    @SafeVarargs
    public final Delegate<TSender, TArgs> replace(TripleAction<TSender, TArgs>... delegates) {
        invocations.clear();
        return combine(delegates);
    }

    @SafeVarargs
    public final Delegate<TSender, TArgs> combine(TripleAction<TSender, TArgs>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TArgs> delegate : delegates) {
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
    public final Delegate<TSender, TArgs> remove(TripleAction<TSender, TArgs>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TArgs> delegate : delegates) {
            if (delegate == null) {
                continue;
            }
            if (!tryAs(delegate, Delegate.class, d -> invocations.removeAll(d.invocations))) {
                invocations.remove(delegate);
            }
        }
        return this;
    }

    public Delegate<TSender, TArgs> close() {
        tryClose(firstInvocation);
        for (TripleAction<TSender, TArgs> invocation : invocations) {
            tryClose(invocation);
        }
        tryClose(lastInvocation);
        return purge();
    }

    public Delegate<TSender, TArgs> purge() {
        invocations.clear();
        firstInvocation = lastInvocation = null;
        return this;
    }

    @Override
    public void invoke(@NonNull TSender target, @NonNull TArgs args) throws Throwable {
        if (!innerInvoke(firstInvocation, target, args)) {
            return;
        }
        for (TripleAction<TSender, TArgs> delegate : invocations) {
            if (!innerInvoke(delegate, target, args)) {
                break;
            }
        }
        innerInvoke(lastInvocation, target, args);
    }

    boolean innerInvoke(TripleAction<TSender, TArgs> delegate, TSender target, TArgs args) throws Throwable {
        if (delegate == null) {
            return true;
        }
        try {
            delegate.invoke(target, args);
            if (args.isCancel()) {
                return false;
            }
        } catch (Throwable e) {
            if (!target.eventFlags().has(EventPublisher.EventFlags.QUIETLY)) {
                throw e;
            }
            TraceHandler.INSTANCE.log(e);
        }
        return true;
    }
}
