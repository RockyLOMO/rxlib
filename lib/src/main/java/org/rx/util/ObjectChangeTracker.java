package org.rx.util;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.collections4.keyvalue.DefaultMapEntry;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.springframework.cglib.proxy.Enhancer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;

import static java.lang.annotation.ElementType.FIELD;
import static org.rx.core.Extends.*;

public class ObjectChangeTracker {
    @Target(FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ignore {
    }

    @Getter
    public static class ObjectChangedEvent extends EventObject {
        private static final long serialVersionUID = -2993269004798534124L;
        final Map<String, ChangedValue> changedValues;

        public ObjectChangedEvent(Object source, Map<String, ChangedValue> changedValues) {
            super(source);
            this.changedValues = changedValues;
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class ChangedValue {
        final Object oldValue;
        final Object newValue;
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

    public static <T> Map<String, Object> getSnapshotMap(@NonNull T subscriber, boolean concatName) {
        Object target = getTarget(subscriber);
        if (target == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> root = new HashMap<>();
        for (Field field : getFieldMap(target.getClass())) {
            resolve(root, null, concatName, target, field);
        }
        return root;
    }

    @SneakyThrows
    static void resolve(Map<String, Object> parent, String parentName, boolean concatName, Object obj, Field field) {
        Object val;
        if (field.isAnnotationPresent(Ignore.class) || (val = field.get(obj)) == null) {
            return;
        }

        String name = concatName(parentName, concatName, field.getName());
        Class<?> type = val.getClass();
        if (Linq.tryAsIterableType(type)) {
            parent.put(name, Linq.fromIterable(val).select(p -> resolveValue(name, concatName, p)).toList());
            return;
        }
        if (Map.class.isAssignableFrom(type)) {
            Map<?, ?> x = (Map<?, ?>) val;
            parent.put(name, Linq.from(x.entrySet()).select(p -> new DefaultMapEntry<>(resolveValue(name, concatName, p.getKey()),
                    resolveValue(name, concatName, p.getValue()))).toMap());
            return;
        }
        parent.put(name, resolveValue(name, concatName, val));
    }

    static Object resolveValue(String name, boolean concatName, Object val) {
        Class<?> type = val.getClass();
        if (!Reflects.isBasicType(type)) {
            Map<String, Object> children = new HashMap<>();
            for (Field childField : getFieldMap(type)) {
                resolve(children, name, concatName, val, childField);
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

    static Object getTarget(Object subscriber) {
        Class<?> type = subscriber.getClass();
        if (Proxy.isProxyClass(type) || Enhancer.isEnhanced(type)) {
            return Sys.targetObject(subscriber);
        }
        return subscriber;
    }
    //endregion

    final Map<Object, Map<String, Object>> sources = Collections.synchronizedMap(new WeakHashMap<>());
    final EventBus bus = EventBus.DEFAULT;

    public ObjectChangeTracker() {
        this(30000);
    }

    public ObjectChangeTracker(long checkPeriod) {
        Tasks.schedulePeriod(this::publishChange, checkPeriod);
    }

    public void publishChange() {
        eachQuietly(Linq.from(sources.entrySet()).toList(), p -> {
            Object obj = p.getKey();
            Map<String, Object> oldMap = p.getValue();
            Map<String, Object> newMap = getSnapshotMap(p, false);
            if (oldMap.isEmpty()) {
                if (!newMap.isEmpty()) {
                    sources.put(obj, newMap);
                }
                return;
            }
            TreeMap<String, ChangedValue> changedValues = compareSnapshotMap(oldMap, newMap);
            if (changedValues.isEmpty()) {
                return;
            }
            bus.publish(new ObjectChangedEvent(obj, changedValues));
        });
    }

    public <T> void watch(@NonNull T source) {
        Object target = getTarget(source);
        if (target == null) {
            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
        }
        sources.put(target, getSnapshotMap(target, false));
    }

    public <T> void unwatch(@NonNull T source) {
        Object target = getTarget(source);
        if (target == null) {
//            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
            return;
        }
        sources.remove(target);
    }

    public <T> void register(@NonNull T subscriber) {
        bus.register(subscriber);
    }

    public <T> void unregister(@NonNull T subscriber) {
        bus.unregister(subscriber);
    }
}
