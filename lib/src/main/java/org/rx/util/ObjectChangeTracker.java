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
    @RequiredArgsConstructor
    public static class ChangedValue {
        final Object oldValue;
        final Object newValue;
    }

    public static TreeMap<String, ChangedValue> compareValueMap(@NonNull Map<String, Object> oldValueMap, @NonNull Map<String, Object> newValueMap) {
        TreeMap<String, ChangedValue> root = new TreeMap<>();
        Set<String> allKeys = new HashSet<>(oldValueMap.keySet());
        allKeys.addAll(newValueMap.keySet());
        for (String name : allKeys) {
            Object oldVal = oldValueMap.get(name);
            Object newVal = newValueMap.get(name);
            check(root, null, name, oldVal, newVal);
        }
        return root;
    }

    static void check(Map<String, ChangedValue> root, String parentName, String name, Object oldObj, Object newObj) {
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
                check(root, n, String.format("[%s]", i), oldVal, newVal);
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
                check(root, n, k, oldVal, newVal);
            }
            return;
        }
//        if (!Reflects.isBasicType(oldType) && !Reflects.isBasicType(newType)) {
//
//        }

        if (!eq(oldObj, newObj)) {
            root.put(n, new ChangedValue(oldObj, newObj));
        }
    }

    //region getValueMap
    public static <T> Map<String, Object> getSnapshotMap(@NonNull T subscriber, boolean concatName) {
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
        if (name.startsWith("[")) {
            return parentName + name;
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
        subscribers.put(target, getSnapshotMap(target, false));
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
