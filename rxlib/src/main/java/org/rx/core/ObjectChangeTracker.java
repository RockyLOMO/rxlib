package org.rx.core;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Metadata;
import org.rx.bean.WeakIdentityMap;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.springframework.cglib.proxy.Enhancer;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.rx.core.Extends.*;

@Slf4j
public class ObjectChangeTracker {
    @RequiredArgsConstructor
    @Getter
    @ToString
    public static class ChangedValue {
        final Object oldValue;
        final Object newValue;

        public <T> T oldValue() {
            return (T) oldValue;
        }

        public <T> T newValue() {
            return (T) newValue;
        }
    }

    //region snapshotMap
    public static TreeMap<String, ChangedValue> compareSnapshotMap(@NonNull Map<String, Object> oldValueMap, @NonNull Map<String, Object> newValueMap) {
        TreeMap<String, ChangedValue> root = new TreeMap<>();
        Set<String> allKeys = new HashSet<>(oldValueMap.keySet());
        allKeys.addAll(newValueMap.keySet());
        for (String name : allKeys) {
            Object oldVal = oldValueMap.get(name);
            Object newVal = newValueMap.get(name);
            compareObj(root, null, name, oldVal, newVal);
        }
        return root;
    }

    static void compareObj(Map<String, ChangedValue> root, String parentName, String name, Object oldObj, Object newObj) {
        String n = concatName(parentName, true, name);
        if (oldObj == null || newObj == null) {
            //判断为null或相同引用
            if (oldObj == newObj) {
                return;
            }
            root.put(n, new ChangedValue(oldObj, newObj));
            return;
        }

        Class<?> oldType = oldObj.getClass();
        Class<?> newType = newObj.getClass();
        Class<?> listClass = List.class;
        if (listClass.isAssignableFrom(oldType) && listClass.isAssignableFrom(newType)) {
            List<?> olds = (List<?>) oldObj;
            List<?> news = (List<?>) newObj;
            int oldSize = olds.size();
            int newSize = news.size();
            int allSize = Math.max(oldSize, newSize);
            for (int i = 0; i < allSize; i++) {
                Object oldVal = i < oldSize ? olds.get(i) : null;
                Object newVal = i < newSize ? news.get(i) : null;
                compareObj(root, n, String.format("[%s]", i), oldVal, newVal);
            }
            return;
        }
        Class<?> mapClass = Map.class;
        if (mapClass.isAssignableFrom(oldType) && mapClass.isAssignableFrom(newType)) {
            Map<String, ?> olds = (Map<String, ?>) oldObj;
            Map<String, ?> news = (Map<String, ?>) newObj;
            Set<String> allKeys = new HashSet<>(olds.keySet());
            allKeys.addAll(news.keySet());
            for (String k : allKeys) {
                Object oldVal = olds.get(k);
                Object newVal = news.get(k);
                compareObj(root, n, k, oldVal, newVal);
            }
            return;
        }

        if (!eq(oldObj, newObj)) {
            root.put(n, new ChangedValue(oldObj, newObj));
        }
    }

    public static <T> Map<String, Object> getSnapshotMap(@NonNull T sourceObj, boolean concatName) {
        Object target = getTarget(sourceObj);
        if (target == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> root = new HashMap<>();
        for (Field field : getFieldMap(target.getClass())) {
            resolve(root, null, concatName, target, field, 0);
        }
        return root;
    }

    @SneakyThrows
    static void resolve(Map<String, Object> parent, String parentName, boolean concatName, Object obj, Field field, int recursionDepth) {
        Metadata m = field.getAnnotation(Metadata.class);
        Object val;
        if ((m != null && m.ignore()) || (val = field.get(obj)) == null) {
            return;
        }

        String name = concatName(parentName, concatName, field.getName());
        Class<?> type = val.getClass();
        if (Linq.tryAsIterableType(type)) {
            parent.put(name, Linq.fromIterable(val).select(p -> resolveValue(name, concatName, p, recursionDepth)).toList());
            return;
        }
        if (Map.class.isAssignableFrom(type)) {
            Map<?, ?> x = (Map<?, ?>) val;
            parent.put(name, Linq.from(x.entrySet()).select(p -> new AbstractMap.SimpleEntry<>(resolveValue(name, concatName, p.getKey(), recursionDepth), resolveValue(name, concatName, p.getValue(), recursionDepth))).toMap());
            return;
        }
        parent.put(name, resolveValue(name, concatName, val, recursionDepth));
    }

    static Object resolveValue(String name, boolean concatName, Object val, int recursionDepth) {
        if (val == null) {
            return null;
        }

        Class<?> type = val.getClass();
        if (!Reflects.isBasicType(type)) {
            if (++recursionDepth > 128) {
                TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_TRACK_OVERFLOW.name(), String.format("%s recursion overflow", type));
                return val;
            }
            log.debug("recursion {} -> {}", type, recursionDepth);
            Map<String, Object> children = new HashMap<>();
            for (Field childField : getFieldMap(type)) {
                resolve(children, name, concatName, val, childField, recursionDepth);
            }
            return children;
        }
        return val;
    }

    static String concatName(String parentName, boolean concatName, String name) {
        if (parentName == null || !concatName) {
            return name;
        }
        if (name.startsWith("[")) {
            return parentName + name;
        }
        return String.format("%s.%s", parentName, name);
    }

    static Linq<Field> getFieldMap(Class<?> type) {
        return Linq.from(Reflects.getFieldMap(type).values()).where(p -> !Modifier.isStatic(p.getModifiers()));
    }

    static Object getTarget(Object sourceObj) {
        Class<?> type = sourceObj.getClass();
        if (Proxy.isProxyClass(type) || Enhancer.isEnhanced(type)) {
            return Sys.targetObject(sourceObj);
        }
        return sourceObj;
    }
    //endregion

    public static final ObjectChangeTracker DEFAULT = new ObjectChangeTracker();
    final Map<Object, Map<String, Object>> sources = new WeakIdentityMap<>();
    final EventBus bus = EventBus.DEFAULT;

    public ObjectChangeTracker() {
        this(30000);
    }

    public ObjectChangeTracker(long publishPeriod) {
        Tasks.schedulePeriod(this::publishAll, publishPeriod);
    }

    public void publishAll() {
//        log.info("Tracker {}", sources.size());
        eachQuietly(sources.entrySet(), p -> publish(p.getKey(), p.getValue(), false));
    }

    public <T> ObjectChangeTracker publish(@NonNull T source, boolean forcePublish) {
        Object target = getTarget(source);
        if (target == null) {
            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
        }
        Map<String, Object> oldMap = sources.get(target);
        if (oldMap == null) {
            throw new InvalidException("Object {} not watched", target);
        }
        publish(target, oldMap, forcePublish);
        return this;
    }

    void publish(Object target, Map<String, Object> oldMap, boolean forcePub) {
        Map<String, Object> newMap = getSnapshotMap(target, false);
        TreeMap<String, ChangedValue> changedValues = compareSnapshotMap(oldMap, newMap);
        if (changedValues.isEmpty() && !forcePub) {
            return;
        }
        log.info("Tracker {} ->\n\t{}\n\t{}\n-> {}", target, oldMap, newMap, Sys.toJsonString(changedValues));
        sources.put(target, newMap);

        Serializable topic = null;
        Metadata m = target.getClass().getAnnotation(Metadata.class);
        if (m != null) {
            if (m.topicClass() != Object.class) {
                topic = m.topicClass();
            } else if (!m.topic().isEmpty()) {
                topic = m.topic();
            }
        }
        bus.publish(new ObjectChangedEvent(target, changedValues), topic);
    }

    public <T> ObjectChangeTracker watch(T source) {
        return watch(source, false);
    }

    public <T> ObjectChangeTracker watch(@NonNull T source, boolean emptySnapshotMap) {
        Object target = getTarget(source);
        if (target == null) {
            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
        }
        sources.put(target, emptySnapshotMap ? Collections.emptyMap() : getSnapshotMap(target, false));
        return this;
    }

    public <T> ObjectChangeTracker unwatch(@NonNull T source) {
        Object target = getTarget(source);
        if (target == null) {
//            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
            return this;
        }
        sources.remove(target);
        return this;
    }

    public <T> ObjectChangeTracker register(@NonNull T subscriber) {
        bus.register(subscriber);
        return this;
    }

    public <T> ObjectChangeTracker unregister(@NonNull T subscriber) {
        bus.unregister(subscriber);
        return this;
    }
}
