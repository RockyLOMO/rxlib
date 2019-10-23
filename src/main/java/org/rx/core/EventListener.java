package org.rx.core;

import com.google.common.eventbus.EventBus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.require;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EventListener {
    @Getter
    private static final EventListener instance = new EventListener();
    private static Lazy<EventBus> busLazy = new Lazy<>(EventBus::new);

    public static void register(Object object) {
        busLazy.getValue().register(object);
    }

    public static void post(Object event) {
        busLazy.getValue().post(event);
    }

    private final Map<Object, Map<String, BiConsumer>> host = Collections.synchronizedMap(new WeakHashMap<>());

    public void attach(Object target, String eventName, BiConsumer event) {
        require(target, eventName);

        Map<String, BiConsumer> eventMap = host.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
        if (event == null) {
            eventMap.remove(eventName);
            return;
        }
        eventMap.put(eventName, event);
    }

    public void raise(Object target, String eventName, EventArgs args) {
        require(target, eventName, args);

        Map<String, BiConsumer> eventMap = host.get(target);
        if (eventMap == null) {
            return;
        }
        BiConsumer event = eventMap.get(eventName);
        if (event != null) {
            event.accept(target, args);
        }
    }
}
