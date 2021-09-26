package org.rx.core;

import com.google.common.eventbus.EventBus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EventHost {
    @Getter
    private static final EventHost instance = new EventHost();

    public static void register(Object object) {
        Container.getInstance().getOrRegister(EventBus.class).register(object);
    }

    public static void post(Object event) {
        Container.getInstance().getOrRegister(EventBus.class).post(event);
    }

    //ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才会回收
    private final Map<EventTarget<?>, Map<String, BiConsumer>> weakMap = Collections.synchronizedMap(new WeakHashMap<>());

    public void attach(EventTarget<?> target, String method, BiConsumer methodImpl) {
        attach(target, method, methodImpl, true);
    }

    @SuppressWarnings(NON_WARNING)
    public void attach(EventTarget target, String method, BiConsumer methodImpl, boolean combine) {
        Map<String, BiConsumer> map = getMap(target);
        map.put(method, combine ? combine(map.get(method), methodImpl) : methodImpl);
    }

    @SuppressWarnings(NON_WARNING)
    public void detach(@NonNull EventTarget target, @NonNull String method, @NonNull BiConsumer methodImpl) {
        Map<String, BiConsumer> map = getMap(target);
        map.put(method, remove(map.get(method), methodImpl));
    }

    private Map<String, BiConsumer> getMap(EventTarget<?> target) {
        return weakMap.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
    }

    @SuppressWarnings(NON_WARNING)
    public void raise(@NonNull EventTarget target, @NonNull String method, @NonNull EventArgs args) {
        BiConsumer a = getMap(target).get(method);
        if (a == null) {
            log.warn("Raise {}.{} fail, event not defined", target, method);
            return;
        }
        a.accept(target, args);
    }
}
