package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Field;
import java.util.EventObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.log;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Delegate<TSender extends EventPublisher<TSender>, TEvent> implements TripleAction<TSender, TEvent> {
    public static final class Order {
        public static final int First = Integer.MIN_VALUE;
        public static final int Default = 0;
        public static final int Last = Integer.MAX_VALUE;

        private Order() {
        }
    }

    static final class Invocation<TSender extends EventPublisher<TSender>, TEvent> {
        final int order;
        final TripleAction<TSender, TEvent> delegate;

        Invocation(int order, TripleAction<TSender, TEvent> delegate) {
            this.order = order;
            this.delegate = delegate;
        }
    }

    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> create() {
        return create((TripleAction<TSender, TEvent>[]) null);
    }

    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> create(TripleAction<TSender, TEvent> delegate) {
        return new Delegate<TSender, TEvent>().add(delegate);
    }

    @SafeVarargs
    public static <TSender extends EventPublisher<TSender>, TEvent> Delegate<TSender, TEvent> create(TripleAction<TSender, TEvent>... delegates) {
        return new Delegate<TSender, TEvent>().add(delegates);
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
            d = IOC.<String, Delegate<TSender, TEvent>>weakMap(target).computeIfAbsent(fnName, k -> new Delegate<>());
        }
        return d;
    }

    final List<Invocation<TSender, TEvent>> invocations = new CopyOnWriteArrayList<>();

    public boolean isEmpty() {
        return invocations.isEmpty();
    }

    public final Delegate<TSender, TEvent> replace(TripleAction<TSender, TEvent> delegate) {
        purge();
        return add(delegate);
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> replace(TripleAction<TSender, TEvent>... delegates) {
        purge();
        return add(delegates);
    }

    public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent> delegate) {
        return add(Order.Default, delegate);
    }

    public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent> delegate) {
        if (delegate == null) {
            return this;
        }
        if (!tryAs(delegate, Delegate.class, d -> {
            for (Invocation<TSender, TEvent> invocation : (List<Invocation<TSender, TEvent>>) d.invocations) {
                addInvocation(invocation.order, invocation.delegate);
            }
        })) {
            addInvocation(order, delegate);
        }
        return this;
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent>... delegates) {
        return add(Order.Default, delegates);
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TEvent> delegate : delegates) {
            add(order, delegate);
        }
        return this;
    }

    public final Delegate<TSender, TEvent> remove(TripleAction<TSender, TEvent> delegate) {
        if (delegate == null) {
            return this;
        }
        if (!tryAs(delegate, Delegate.class, d -> {
            for (Invocation<TSender, TEvent> invocation : (List<Invocation<TSender, TEvent>>) d.invocations) {
                removeInvocation(invocation.delegate);
            }
        })) {
            removeInvocation(delegate);
        }
        return this;
    }

    @SafeVarargs
    public final Delegate<TSender, TEvent> remove(TripleAction<TSender, TEvent>... delegates) {
        if (delegates == null) {
            return this;
        }
        for (TripleAction<TSender, TEvent> delegate : delegates) {
            remove(delegate);
        }
        return this;
    }

    public Delegate<TSender, TEvent> purge() {
        return purge(false);
    }

    public Delegate<TSender, TEvent> purge(boolean close) {
        if (close) {
            for (Invocation<TSender, TEvent> invocation : invocations) {
                tryClose(invocation.delegate);
            }
        }
        invocations.clear();
        return this;
    }

    public <T extends EventObject> void invoke(T eventObj) throws Throwable {
        invoke((TSender) eventObj.getSource(), (TEvent) eventObj);
    }

    @Override
    public void invoke(@NonNull TSender target, TEvent event) throws Throwable {
        EventArgs args = as(event, EventArgs.class);
        for (Invocation<TSender, TEvent> invocation : invocations) {
            if (!innerInvoke(invocation.delegate, target, event, args)) {
                break;
            }
        }
    }

    boolean innerInvoke(TripleAction<TSender, TEvent> delegate, TSender target, TEvent event, EventArgs args) throws Throwable {
        if (delegate == null) {
            return true;
        }
        try {
            delegate.invoke(target, event);
            return args == null || !args.isCancel() && !args.isHandled();
        } catch (Throwable e) {
            if (!target.eventFlags().has(EventPublisher.EventFlags.QUIETLY)) {
                throw e;
            }
            log.error("Delegate invoke",  e);
        }
        return true;
    }

    synchronized void addInvocation(int order, TripleAction<TSender, TEvent> delegate) {
        removeInvocation(delegate);
        Invocation<TSender, TEvent> invocation = new Invocation<>(order, delegate);
        int i = 0;
        for (int len = invocations.size(); i < len; i++) {
            if (invocations.get(i).order > order) {
                break;
            }
        }
        invocations.add(i, invocation);
    }

    synchronized void removeInvocation(TripleAction<TSender, TEvent> delegate) {
        for (Invocation<TSender, TEvent> invocation : invocations) {
            if (invocation.delegate.equals(delegate)) {
                invocations.remove(invocation);
                return;
            }
        }
    }
}
