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
public class EventBus implements EventPublisher<EventBus> {
    public static final EventBus DEFAULT = new EventBus();
    static final int TOPIC_MAP_INITIAL_CAPACITY = 4;
    public final Delegate<EventBus, NEventArgs<?>> onDeadEvent = Delegate.create();
    //eventType -> topic -> eventMethodsInListener
    final Map<Class<?>, Map<String, Set<Tuple<Object, Method>>>> subscribers = new ConcurrentHashMap<>();

    public <T> void register(T subscriber) {
        register(subscriber, null);
    }

    public <T> void register(@NonNull T subscriber, String topic) {
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber, topic).entrySet()) {
            if (topic == null) {
                topic = Strings.EMPTY;
            }
            Class<?> eventType = entry.getKey();
            Set<Tuple<Object, Method>> eventMethods = entry.getValue();
            subscribers.computeIfAbsent(eventType, k -> new ConcurrentHashMap<>(TOPIC_MAP_INITIAL_CAPACITY))
                    .computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).addAll(eventMethods);
        }
    }

    public <T> void unregister(T subscriber) {
        unregister(subscriber, null);
    }

    public <T> void unregister(@NonNull T subscriber, String topic) {
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber, topic).entrySet()) {
            Class<?> eventType = entry.getKey();
            Collection<Tuple<Object, Method>> eventMethods = entry.getValue();
            Map<String, Set<Tuple<Object, Method>>> topicMap = subscribers.getOrDefault(eventType, Collections.emptyMap());
            boolean exist = false;
            if (topic == null) {
                for (Set<Tuple<Object, Method>> currentSubscribers : topicMap.values()) {
                    if (currentSubscribers.removeAll(eventMethods)) {
                        exist = true;
                    }
                }
            } else {
                Set<Tuple<Object, Method>> currentSubscribers = topicMap.get(topic);
                exist = currentSubscribers.removeAll(eventMethods);
            }
            if (!exist) {
                throw new InvalidException("missing event subscriber for an annotated method. Is {}[{}] registered?", subscriber, topic);
            }
        }
    }

    Map<Class<?>, Set<Tuple<Object, Method>>> findAllSubscribers(Object listener, String topic) {
        Map<Class<?>, Set<Tuple<Object, Method>>> methodsInListener = new HashMap<>();
        for (Method method : Linq.from(Reflects.getMethodMap(listener instanceof Class ? (Class<?>) listener : listener.getClass()).values()).selectMany(p -> p).where(p -> {
            Subscribe subscribe = p.getAnnotation(Subscribe.class);
            return subscribe != null && (topic == null || Strings.hashEquals(subscribe.value(), topic)) && !p.isSynthetic();
        })) {
            if (method.getParameterCount() != 1) {
                throw new InvalidException("Subscriber method %s has @Subscribe annotation must have exactly 1 parameter.", method);
            }
            Class<?> eventType = method.getParameterTypes()[0];
            methodsInListener.computeIfAbsent(eventType, k -> new HashSet<>()).add(Tuple.of(listener, method));
        }
        return methodsInListener;
    }

    public <T> void publish(T event) {
        publish(event, null);
    }

    public <T> void publish(@NonNull T event, String topic) {
        Class<?> type = event.getClass();
        List<Class<?>> eventTypes = ClassUtils.getAllSuperclasses(type);
        eventTypes.add(type);

        Linq<Class<?>> q = Linq.from(eventTypes);
        Set<Tuple<Object, Method>> eventSubscribers = topic == null
                ? q.selectMany(p -> subscribers.getOrDefault(p, Collections.emptyMap()).values()).selectMany(p -> p).toSet()
                : q.selectMany(p -> subscribers.getOrDefault(p, Collections.emptyMap()).getOrDefault(topic, Collections.emptySet())).toSet();
        if (eventSubscribers.isEmpty()) {
            TraceHandler.INSTANCE.saveMetric(Constants.MetricName.DEAD_EVENT.name(),
                    String.format("The event %s[%s] had no subscribers", event, topic));
            raiseEvent(onDeadEvent, new NEventArgs<>(event));
            return;
        }

        Extends.eachQuietly(eventSubscribers, p -> Reflects.invokeMethod(p.right, p.left, event));
    }
}
