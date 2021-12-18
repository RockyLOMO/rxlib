package org.rx.core;

import com.google.common.eventbus.EventBus;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.rx.core.App.tryAs;
import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Delegate<TSender extends EventTarget<TSender>, TArgs extends EventArgs> implements TripleAction<TSender, TArgs> {
    static {
        Container.register(EventBus.class, new EventBus());
    }

    public static void register(Object eventListener) {
        Container.get(EventBus.class).register(eventListener);
    }

    public static void post(Object eventObject) {
        Container.get(EventBus.class).post(eventObject);
    }

    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create() {
        return create((TripleAction<TSender, TArgs>[]) null);
    }

    @SafeVarargs
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create(TripleAction<TSender, TArgs>... delegates) {
        return new Delegate<TSender, TArgs>().combine(delegates);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> wrap(@NonNull EventTarget<TSender> target, @NonNull String fnName) {
        Delegate<TSender, TArgs> d;
        Field field = Reflects.getFieldMap(target.getClass()).get(fnName);
        if (field != null) {
            d = (Delegate<TSender, TArgs>) field.get(target);
            if (d == null) {
                throw new InvalidException("Event %s not defined", fnName);
            }
        } else {
            if (!target.eventFlags().has(EventTarget.EventFlags.DYNAMIC_ATTACH)) {
                throw new InvalidException("Event %s not defined", fnName);
            }
            d = Container.<EventTarget<TSender>, Map<String, Delegate<TSender, TArgs>>>weakMap().computeIfAbsent(target, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(fnName, k -> new Delegate<>());
        }
        return d;
    }

    final Set<TripleAction<TSender, TArgs>> invocations = new CopyOnWriteArraySet<>();
    TripleAction<TSender, TArgs> headInvocation;
    TripleAction<TSender, TArgs> tailInvocation;

    public boolean isEmpty() {
        return invocations.isEmpty() && headInvocation == null && tailInvocation == null;
    }

    public Delegate<TSender, TArgs> head(TripleAction<TSender, TArgs> delegate) {
        headInvocation = delegate;
        return this;
    }

    public Delegate<TSender, TArgs> tail(TripleAction<TSender, TArgs> delegate) {
        tailInvocation = delegate;
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

    public synchronized Delegate<TSender, TArgs> tryClose() {
        App.tryClose(headInvocation);
        for (TripleAction<TSender, TArgs> invocation : invocations) {
            App.tryClose(invocation);
        }
        App.tryClose(tailInvocation);
        return purge();
    }

    public synchronized Delegate<TSender, TArgs> purge() {
        invocations.clear();
        headInvocation = tailInvocation = null;
        return this;
    }

    @Override
    public void invoke(@NonNull TSender target, @NonNull TArgs args) throws Throwable {
        if (!innerInvoke(headInvocation, target, args)) {
            return;
        }
        for (TripleAction<TSender, TArgs> delegate : invocations) {
            if (!innerInvoke(delegate, target, args)) {
                break;
            }
        }
        innerInvoke(tailInvocation, target, args);
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
            if (!target.eventFlags().has(EventTarget.EventFlags.QUIETLY)) {
                throw e;
            }
            log.warn("innerRaise", e);
        }
        return true;
    }
}
