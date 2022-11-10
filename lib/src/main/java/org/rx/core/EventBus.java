package org.rx.core;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.rx.annotation.Subscribe;
import org.rx.bean.Tuple;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public class EventBus implements EventTarget<EventBus> {
    public static final EventBus DEFAULT = new EventBus();
    public final Delegate<EventBus, NEventArgs<?>> onDeadEvent = Delegate.create();
    final Map<Class<?>, Set<Tuple<Object, Method>>> subscribers = new ConcurrentHashMap<>();

    public <T> void register(@NonNull T subscriber) {
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber).entrySet()) {
            Class<?> eventType = entry.getKey();
            Set<Tuple<Object, Method>> eventMethodsInListener = entry.getValue();
            subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArraySet<>()).addAll(eventMethodsInListener);
        }
    }

    public <T> void unregister(@NonNull T subscriber) {
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber).entrySet()) {
            Class<?> eventType = entry.getKey();
            Collection<Tuple<Object, Method>> listenerMethodsForType = entry.getValue();

            Set<Tuple<Object, Method>> currentSubscribers = subscribers.get(eventType);
            if (currentSubscribers == null || !currentSubscribers.removeAll(listenerMethodsForType)) {
                throw new InvalidException("missing event subscriber for an annotated method. Is {} registered?", subscriber);
            }
        }
    }

    Map<Class<?>, Set<Tuple<Object, Method>>> findAllSubscribers(Object listener) {
        Map<Class<?>, Set<Tuple<Object, Method>>> methodsInListener = new HashMap<>();
        for (Method method : Linq.from(Reflects.getMethodMap(listener.getClass()).values()).selectMany(p -> p)
                .where(p -> p.isAnnotationPresent(Subscribe.class) && !p.isSynthetic())) {
            if (method.getParameterCount() != 1) {
                throw new InvalidException("Subscriber method %s has @Subscribe annotation must have exactly 1 parameter.", method);
            }
            Class<?> eventType = method.getParameterTypes()[0];
            methodsInListener.computeIfAbsent(eventType, k -> new HashSet<>()).add(Tuple.of(listener, method));
        }
        return methodsInListener;
    }

    public <T> void post(@NonNull T event) {
        Class<?> type = event.getClass();
        List<Class<?>> eventTypes = ClassUtils.getAllSuperclasses(type);
        eventTypes.add(type);

        Linq<Tuple<Object, Method>> eventSubscribers = Linq.from(eventTypes).selectMany(p -> subscribers.getOrDefault(p, Collections.emptySet()));
        if (!eventSubscribers.any()) {
            TraceHandler.INSTANCE.saveMetric(Constants.MetricName.DEAD_EVENT.name(),
                    String.format("The event '%s' had no subscribers", event));
            raiseEvent(onDeadEvent, new NEventArgs<>(event));
            return;
        }

        Extends.eachQuietly(eventSubscribers, p -> Reflects.invokeMethod(p.right, p.left, event));
    }
}
