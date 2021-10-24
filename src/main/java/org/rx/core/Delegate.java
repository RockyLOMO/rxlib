package org.rx.core;

import com.google.common.eventbus.EventBus;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.rx.core.App.tryAs;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Delegate<TSender extends EventTarget<TSender>, TArgs extends EventArgs> implements TripleAction<TSender, TArgs> {
    public static void register(Object eventListener) {
        Container.getInstance().getOrRegister(EventBus.class).register(eventListener);
    }

    public static void post(Object eventObject) {
        Container.getInstance().getOrRegister(EventBus.class).post(eventObject);
    }

    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create() {
        return create((TripleAction<TSender, TArgs>[]) null);
    }

    @SafeVarargs
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> create(TripleAction<TSender, TArgs>... delegates) {
        return new Delegate<TSender, TArgs>().combine(delegates);
    }

    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> wrap(TripleAction<TSender, TArgs> fn) {
        if (fn instanceof Delegate) {
            return (Delegate<TSender, TArgs>) fn;
        }
        Delegate<TSender, TArgs> delegate = new Delegate<>();
        delegate.invocationList.add(fn);
        return delegate;
    }

    @SneakyThrows
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> Delegate<TSender, TArgs> wrap(@NonNull EventTarget<TSender> target, @NonNull String fnName) {
        Delegate<TSender, TArgs> d;
        Field field = Reflects.getFields(target.getClass()).firstOrDefault(p -> p.getName().equals(fnName));
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

    @Getter
    private final Set<TripleAction<TSender, TArgs>> invocationList = new CopyOnWriteArraySet<>();

    @SafeVarargs
    public final Delegate<TSender, TArgs> set(TripleAction<TSender, TArgs>... delegates) {
        invocationList.clear();
        return combine(delegates);
    }

    public void reset() {
        invocationList.clear();
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
            if (!tryAs(delegate, Delegate.class, d -> invocationList.addAll(d.invocationList))) {
                invocationList.add(delegate);
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
            if (!tryAs(delegate, Delegate.class, d -> invocationList.removeAll(d.invocationList))) {
                invocationList.remove(delegate);
            }
        }
        return this;
    }

    @Override
    public void invoke(@NonNull TSender target, @NonNull TArgs args) throws Throwable {
        FlagsEnum<EventTarget.EventFlags> flags = target.eventFlags();
        if (flags.has(EventTarget.EventFlags.THREAD_SAFE)) {
            synchronized (target) {
                innerRaise(target, args, flags);
            }
            return;
        }
        innerRaise(target, args, flags);
    }

    private void innerRaise(TSender target, TArgs args, FlagsEnum<EventTarget.EventFlags> flags) throws Throwable {
        for (TripleAction<TSender, TArgs> delegate : invocationList) {
            try {
                delegate.invoke(target, args);
                if (args.isCancel()) {
                    return;
                }
            } catch (Throwable e) {
                if (!flags.has(EventTarget.EventFlags.QUIETLY)) {
                    throw e;
                }
                log.warn("innerRaise", e);
            }
        }
    }
}
