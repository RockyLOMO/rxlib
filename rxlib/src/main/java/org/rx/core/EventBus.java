package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.rx.annotation.Metadata;
import org.rx.annotation.Subscribe;
import org.rx.bean.Tuple;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public class EventBus {
    public static final EventBus DEFAULT = new EventBus();
    static final int TOPIC_MAP_INITIAL_CAPACITY = 4;

    static <T> Serializable getTopic(T event) {
        Serializable topic = null;
        Metadata m = event.getClass().getAnnotation(Metadata.class);
        if (m != null) {
            if (m.topicClass() != Object.class) {
                topic = m.topicClass();
            } else if (!m.topic().isEmpty()) {
                topic = m.topic();
            }
        }
        return topic;
    }

    @Getter
    volatile BiAction<Object> onDeadEvent = e -> log.info("The event {} had no subscribers", e);
    //eventType -> topic -> eventMethodsInListener
    final Map<Class<?>, Map<Serializable, Set<Tuple<Object, Method>>>> subscribers = new ConcurrentHashMap<>();

    public void setOnDeadEvent(BiAction<Object> onDeadEvent) {
        if (onDeadEvent == null) {
            onDeadEvent = e -> log.info("The event {} had no subscribers", e);
        }
        this.onDeadEvent = onDeadEvent;
    }

    public <T> void register(@NonNull T subscriber) {
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber).entrySet()) {
            Class<?> eventType = entry.getKey();
            Set<Tuple<Object, Method>> eventMethods = entry.getValue();
            Map<Serializable, Set<Tuple<Object, Method>>> topicMap = subscribers.computeIfAbsent(eventType, k -> new ConcurrentHashMap<>(TOPIC_MAP_INITIAL_CAPACITY));
            for (Map.Entry<Serializable, Set<Tuple<Object, Method>>> subEntry : Linq.from(eventMethods).groupByIntoMap(p -> {
                Subscribe m = p.right.getAnnotation(Subscribe.class);
                if (m.topicClass() != Object.class) {
                    return m.topicClass();
                }
                if (!m.topic().isEmpty()) {
                    return m.topic();
                }
                return m.value();
            }, (p, x) -> x.toSet()).entrySet()) {
                topicMap.computeIfAbsent(subEntry.getKey(), k -> new CopyOnWriteArraySet<>()).addAll(subEntry.getValue());
            }
        }
    }

    public <T> void unregister(T subscriber) {
        unregister(subscriber, null);
    }

    public <T, TT extends Serializable> void unregister(@NonNull T subscriber, TT topic) {
        boolean exist = false;
        for (Map.Entry<Class<?>, Set<Tuple<Object, Method>>> entry : findAllSubscribers(subscriber).entrySet()) {
            Class<?> eventType = entry.getKey();
            Collection<Tuple<Object, Method>> eventMethods = entry.getValue();
            Map<Serializable, Set<Tuple<Object, Method>>> topicMap = subscribers.getOrDefault(eventType, Collections.emptyMap());
            if (topic == null) {
                for (Set<Tuple<Object, Method>> currentSubscribers : topicMap.values()) {
                    if (currentSubscribers.removeAll(eventMethods)) {
                        exist = true;
                    }
                }
            } else {
                Set<Tuple<Object, Method>> currentSubscribers = topicMap.get(topic);
                if (currentSubscribers != null && currentSubscribers.removeAll(eventMethods)) {
                    exist = true;
                }
            }
        }
        if (!exist) {
            throw new InvalidException("missing event subscriber for an annotated method. Is {}[{}] registered?", subscriber, topic);
        }
    }

    Map<Class<?>, Set<Tuple<Object, Method>>> findAllSubscribers(Object listener) {
        Map<Class<?>, Set<Tuple<Object, Method>>> methodsInListener = new HashMap<>();
        for (Method method : Linq.from(Reflects.getMethodMap(listener instanceof Class ? (Class<?>) listener : listener.getClass()).values()).selectMany(p -> p).where(p -> p.isAnnotationPresent(Subscribe.class) && !p.isSynthetic())) {
            if (method.getParameterCount() != 1) {
                throw new InvalidException("Subscriber method {} has @Subscribe annotation must have exactly 1 parameter.", method);
            }
            Class<?> eventType = method.getParameterTypes()[0];
            methodsInListener.computeIfAbsent(eventType, k -> new HashSet<>()).add(Tuple.of(listener, method));
        }
        return methodsInListener;
    }

    public <T> void publish(T event) {
        publish(event, getTopic(event));
    }

    public <T, TT extends Serializable> void publish(@NonNull T event, TT topic) {
        log.debug("publish[{}] {}", topic, event);
        Class<?> type = event.getClass();
        List<Class<?>> eventTypes = ClassUtils.getAllSuperclasses(type);
        eventTypes.add(type);

        Linq<Class<?>> q = Linq.from(eventTypes);
        Set<Tuple<Object, Method>> eventSubscribers = topic == null ? q.selectMany(p -> subscribers.getOrDefault(p, Collections.emptyMap()).values()).selectMany(p -> p).toSet() : q.selectMany(p -> subscribers.getOrDefault(p, Collections.emptyMap()).getOrDefault(topic, Collections.emptySet())).toSet();
        if (eventSubscribers.isEmpty()) {
            TraceHandler.INSTANCE.saveMetric(Constants.MetricName.DEAD_EVENT.name(), String.format("The event %s[%s] had no subscribers", event, topic));
            onDeadEvent.accept(event);
            return;
        }

        Extends.eachQuietly(eventSubscribers, p -> Reflects.invokeMethod(p.right, p.left, event));
    }
}
