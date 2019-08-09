package org.rx.common;

import org.rx.beans.Tuple;

import java.util.*;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.require;

class EventListener {
    public static final EventListener instance = new EventListener();
    private final Map<Object, Set<Tuple<String, BiConsumer>>> host;

    private EventListener() {
        host = Collections.synchronizedMap(new WeakHashMap<>());
    }

    public void attach(Object target, String eventName, BiConsumer event) {
        require(target, eventName);

        host.computeIfAbsent(target, k -> {
            Set<Tuple<String, BiConsumer>> set = Collections.synchronizedSet(new HashSet<>());
            set.add(Tuple.of(eventName, event));
            return set;
        });
    }

    public void raise(Object target, String eventName, EventArgs args) {
        require(target, eventName, args);

        Set<Tuple<String, BiConsumer>> set = host.get(target);
        if (set == null) {
            return;
        }
        for (Tuple<String, BiConsumer> tuple : set) {
            if (!tuple.left.equals(eventName) || tuple.right == null) {
                continue;
            }
            tuple.right.accept(target, args);
        }
    }
}
