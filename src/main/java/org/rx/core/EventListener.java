package org.rx.core;

import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.require;

public class EventListener {
    public static final EventListener instance = new EventListener();
    private final Map<Object, Map<String, BiConsumer>> host;

    private EventListener() {
        host = Collections.synchronizedMap(new WeakHashMap<>());
    }

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
