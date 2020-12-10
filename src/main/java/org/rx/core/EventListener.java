package org.rx.core;

import com.google.common.eventbus.EventBus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EventListener {
    @Getter
    private static final EventListener instance = new EventListener();

    public static void register(Object object) {
        Container.getInstance().getOrRegister(EventBus.class).register(object);
    }

    public static void post(Object event) {
        Container.getInstance().getOrRegister(EventBus.class).post(event);
    }

    public void attach(EventTarget target, String method, BiConsumer methodImpl) {
        attach(target, method, methodImpl, true);
    }

    @SuppressWarnings(NON_WARNING)
    public void attach(EventTarget target, String method, BiConsumer methodImpl, boolean combine) {
        require(target, method, methodImpl);

        Map<String, BiConsumer> map = getMap(target);
        map.put(method, combine ? combine(map.get(method), methodImpl) : methodImpl);
    }

    @SuppressWarnings(NON_WARNING)
    public void detach(EventTarget target, String method, BiConsumer methodImpl) {
        require(target, method, methodImpl);

        Map<String, BiConsumer> map = getMap(target);
        map.put(method, remove(map.get(method), methodImpl));
    }

    private Map<String, BiConsumer> getMap(EventTarget target) {
        return Cache.getOrSet(target, k -> new ConcurrentHashMap<>(), Cache.WEAK_CACHE);
    }

    @SuppressWarnings(NON_WARNING)
    public void raise(EventTarget target, String method, EventArgs args) {
        require(target, method, args);

        BiConsumer a = getMap(target).get(method);
        if (a == null) {
            log.warn("Raise {}.{} fail, event not defined", target, method);
            return;
        }
        a.accept(target, args);
    }
}
