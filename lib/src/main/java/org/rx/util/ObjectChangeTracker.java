package org.rx.util;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.keyvalue.DefaultMapEntry;
import org.rx.annotation.Ignore;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.springframework.cglib.proxy.Enhancer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.rx.core.Extends.*;

public class ObjectChangeTracker {
    public static Map<String, Tuple<Object, Object>> xx(Map<String, Object> oldMap, Map<String, Object> newMap) {
        if (oldMap == null) {
            oldMap = Collections.emptyMap();
        }
        if (newMap == null) {
            newMap = Collections.emptyMap();
        }

        Map<String, Tuple<Object, Object>> changed = new HashMap<>();
        for (Map.Entry<String, Object> entry : oldMap.entrySet()) {
            String key = entry.getKey();
            Object oldVal = entry.getValue();
            Object newVal = newMap.get(key);
            Object t = ifNull(oldVal, newVal);
            if (t == null) {
                continue;
            }
            Class<?> type = t.getClass();
            if (Linq.tryAsIterableType(type)) {

                for (Object ov : Linq.fromIterable(oldVal)) {
                    for (Object nv : Linq.fromIterable(newVal)) {

                    }
                }
            }
            if (!eq(oldVal, newVal)) {
                changed.put(key, Tuple.of(oldVal, newVal));
            }
        }
        return changed;
    }

    public static Map<String, Tuple<Object, Object>> getChangedMap(@NonNull Map<String, Object> oldValueMap, @NonNull Map<String, Object> newValueMap) {
        Map<String, Tuple<Object, Object>> root = new HashMap<>();
        for (Map.Entry<String, Object> oldEntry : oldValueMap.entrySet()) {
            String name = oldEntry.getKey();
            Object oldVal = oldEntry.getValue();
            Object newVal = newValueMap.get(name);
            check(root, null, name, oldVal, newVal);
        }
        return root;
    }

    static void check(Map<String, Tuple<Object, Object>> root, String parentName, String name, Object oldObj, Object newObj) {
        String n = concatName(parentName, true, name);
        if (!eq(oldObj, newObj)) {
            root.put(n, Tuple.of(oldObj, newObj));
            return;
        }
        if (oldObj == null) {
            return;
        }

        Class<?> type = oldObj.getClass();
        if (List.class.isAssignableFrom(type)) {
            List<?> olds = (List<?>) oldObj;
            List<?> news = (List<?>) newObj;
            for (int i = 0; i < olds.size(); i++) {
                Object oldVal = olds.get(i);
                Object newVal = news.get(i);
                check(root, n, String.format("[%s]", i), oldVal, newVal);
            }
        } else if (Map.class.isAssignableFrom(type)) {
            Map<String, ?> olds = (Map<String, ?>) oldObj;
            Map<String, ?> news = (Map<String, ?>) newObj;
            for (Map.Entry<String, ?> oldEntry : olds.entrySet()) {
                String k = oldEntry.getKey();
                Object oldVal = oldEntry.getValue();
                Object newVal = news.get(k);
                check(root, n, k, oldVal, newVal);
            }
        }
    }

    //region getValueMap
    public static <T> Map<String, Object> getValueMap(@NonNull T subscriber, boolean concatName) {
        Object target = getTarget(subscriber);
        if (target == null) {
            return null;
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
        return String.format("%s.%s", parentName, name);
    }

    static Linq<Field> getFieldMap(Class<?> type) {
        return Linq.from(Reflects.getFieldMap(type).values()).where(p -> !Modifier.isStatic(p.getModifiers()));
    }
    //endregion

    static Object getTarget(Object subscriber) {
        Class<?> type = subscriber.getClass();
        if (Proxy.isProxyClass(type) || Enhancer.isEnhanced(type)) {
            return Sys.targetObject(subscriber);
        }
        return subscriber;
    }

    final EventBus bus = EventBus.DEFAULT;
    final Map<Object, Map<String, Object>> subscribers = Collections.synchronizedMap(new WeakHashMap<>());

    public ObjectChangeTracker() {
        this(30000);
    }

    public ObjectChangeTracker(long checkPeriod) {
        Tasks.schedulePeriod(this::checkChange, checkPeriod);
    }

    public void checkChange() {
//        eachQuietly(Linq.from(subscribers.entrySet()).toList(), p -> {
//            Map<String, Object> oldVals = p.getValue(), newVals = getValueMap(p.getKey());
//
//        });
    }

    public <T> void register(@NonNull T subscriber) {
        Object target = getTarget(subscriber);
        if (target == null) {
            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
        }
        subscribers.put(target, getValueMap(target, false));
    }

    public <T> void unregister(@NonNull T subscriber) {
        Object target = getTarget(subscriber);
        if (target == null) {
//            throw new InvalidException("Proxy object can not be tracked, plz use source object instead");
            return;
        }
        subscribers.remove(target);
    }

    public void post() {

    }
}
