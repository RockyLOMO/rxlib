package org.rx.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.annotation.Mapping;
import org.rx.bean.FlagsEnum;
import org.rx.bean.Tuple;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.springframework.cglib.beans.BeanCopier;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.*;
import static org.rx.core.Sys.proxy;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BeanMapper {
    @RequiredArgsConstructor
    private static class MapConfig {
        private final BeanCopier copier;
        private final ConcurrentHashMap<Method, Mapping[]> mappings = new ConcurrentHashMap<>();
        private FlagsEnum<BeanMapFlag> flags;
    }

    public static final BeanMapper DEFAULT = new BeanMapper();
    static final Mapping[] empty = new Mapping[0];

    public static Map<String, Object> convertFromObjectString(String str, boolean root) {
        String startFlag = root ? "(" : "{", endFlag = root ? ")" : "}";
        int s = Strings.indexOf(str, startFlag);
        if (s == -1) {
            return Collections.emptyMap();
        }
        int e = Strings.lastIndexOf(str, endFlag);
        if (e == -1) {
            return Collections.emptyMap();
        }
        return Linq.from(Strings.split(str.substring(s + 1, e), ", ")).select(p -> {
            int i = Strings.indexOf(p, "=");
            if (i == -1) {
                throw new InvalidException("Parse error {}", p);
            }
            String k = p.substring(0, i);
            String v = p.substring(i + 1);
            return Tuple.of(k, Strings.startsWith(v, "{") ? convertFromObjectString(v, false) : v);
        }).toMap(p -> p.left, p -> p.right);
    }

    private final Map<Integer, MapConfig> config = new ConcurrentHashMap<>();
    private final FlagsEnum<BeanMapFlag> flags = BeanMapFlag.LOG_ON_MISS_MAPPING.flags();

    @ErrorCode
    public <T> T define(@NonNull Class<T> type) {
        require(type, type.isInterface());

        return proxy(type, (m, p) -> {
            if (Reflects.OBJECT_METHODS.contains(m)) {
                return p.fastInvokeSuper();
            }
            Object[] args = p.arguments;
            Object target = null;
            if (m.isDefault()) {
                target = Reflects.invokeDefaultMethod(m, p.getProxyObject(), args);
            }

            boolean noreturn = m.getReturnType() == void.class;
            if (args.length >= 2) {
                MapConfig config = setMappings(args[0].getClass(), noreturn ? args[1].getClass() : m.getReturnType(), type, p.getProxyObject(), m);
                map(args[0], ifNull(target, args[1]), config.flags, m);
                return noreturn ? null : args[1];
            }
            if (args.length == 1) {
                if (noreturn) {
                    return null;
                }
                MapConfig config = setMappings(args[0].getClass(), m.getReturnType(), type, p.getProxyObject(), m);
                if (target == null) {
                    target = Reflects.newInstance(m.getReturnType());
                }
                return map(args[0], target, config.flags, m);
            }
            throw new ApplicationException(values(m.getName()));
        });
    }

    private MapConfig setMappings(Class<?> from, Class<?> to, Class<?> type, Object instance, Method method) {
        MapConfig config = getConfig(from, to);
        config.mappings.computeIfAbsent(method, k -> {
            try {
                Method defMethod = type.getDeclaredMethod("getFlags");
                if (defMethod.isDefault()) {
                    config.flags = Reflects.invokeDefaultMethod(defMethod, instance);
                }
            } catch (Exception e) {
                log.warn("BeanMapper.setMappings {}", e.toString());
            }
            return method.getAnnotationsByType(Mapping.class);
        });
        return config;
    }

    private MapConfig getConfig(Class<?> from, Class<?> to) {
        return config.computeIfAbsent(Objects.hash(from, to), k -> new MapConfig(BeanCopier.create(from, to, true)));
    }

    public <T> T map(Object source, Class<T> targetType) {
        return map(source, (T) Reflects.newInstance(targetType));
    }

    public <T> T map(Object source, T target) {
        return map(source, target, null);
    }

    public <T> T map(Object source, T target, FlagsEnum<BeanMapFlag> flags) {
        return map(source, target, flags, null);
    }

    private <T> T map(@NonNull Object source, @NonNull T target, FlagsEnum<BeanMapFlag> flags, Method method) {
        if (flags == null) {
            flags = this.flags;
        }
        boolean skipNull = flags.has(BeanMapFlag.SKIP_NULL);
        Class<?> from = source.getClass(), to = target.getClass();
        final Linq<Reflects.PropertyNode> toProperties = Reflects.getProperties(to);
        TreeSet<String> copiedNames = new TreeSet<>();

        Map<Object, Object> sourceMap = as(source, Map.class);
        if (sourceMap != null) {
            for (Map.Entry<Object, Object> entry : sourceMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String propertyName = entry.getKey().toString();
                Reflects.PropertyNode propertyNode = toProperties.firstOrDefault(p -> eq(p.propertyName, propertyName));
                if (propertyNode == null) {
                    continue;
                }
                copiedNames.add(propertyName);
                Method targetMethod = propertyNode.setter;
                Class<?> targetType = targetMethod.getParameterTypes()[0];
                Reflects.invokeMethod(targetMethod, target, Reflects.changeType(entry.getValue(), targetType));
            }
        } else {
            MapConfig config = getConfig(from, to);
            Linq<Mapping> mappings = Linq.from(method != null ? config.mappings.getOrDefault(method, empty) : empty);
            config.copier.copy(source, target, (sourceValue, targetType, methodName) -> {
                String propertyName = Reflects.propertyName(methodName.toString());
                copiedNames.add(propertyName);
                for (Mapping mapping : mappings) {
                    if (!Strings.hashEquals(mapping.target(), propertyName)) {
                        continue;
                    }
                    sourceValue = processMapping(mapping, sourceValue, targetType, propertyName, source, target, skipNull, toProperties);
                    break;
                }
//                Mapping mapping = mappings.firstOrDefault(p -> eq(p.target(), propertyName));
//                if (mapping != null) {
//                    sourceValue = processMapping(mapping, sourceValue, targetType, propertyName, source, target, skipNull, toProperties);
//                }
                return Reflects.changeType(sourceValue, targetType);
            });

            for (Mapping mapping : mappings.where(p -> !copiedNames.contains(p.target()))) {
                copiedNames.add(mapping.target());
                Reflects.PropertyNode propertyNode = toProperties.firstOrDefault(p -> eq(p.propertyName, mapping.target()));
                if (propertyNode == null) {
                    log.warn("Target property {} not found", mapping.target());
                    continue;
                }
                Method targetMethod = propertyNode.setter;
                Class<?> targetType = targetMethod.getParameterTypes()[0];
                Object sourceValue = processMapping(mapping, null, targetType, mapping.target(), source, target, skipNull, toProperties);
                Reflects.invokeMethod(targetMethod, target, Reflects.changeType(sourceValue, targetType));
            }
        }

        boolean logOnFail = flags.has(BeanMapFlag.LOG_ON_MISS_MAPPING), throwOnFail = flags.has(BeanMapFlag.THROW_ON_MISS_MAPPING);
        if (logOnFail || throwOnFail) {
            Linq<String> missedProperties = toProperties.select(p -> p.propertyName).except(copiedNames);
            if (missedProperties.any()) {
                String failMsg = String.format("Map %s to %s missed properties: %s", from.getSimpleName(), to.getSimpleName(), String.join(", ", missedProperties));
                if (throwOnFail) {
                    throw new BeanMapException(failMsg, missedProperties.toSet());
                }
                log.warn(failMsg);
            }
        }

        if (flags.has(BeanMapFlag.VALIDATE_BEAN)) {
            Validator.validateBean(target);
        }
        return target;
    }

    private Object processMapping(Mapping mapping, Object sourceValue, Class<?> targetType, String propertyName, Object source, Object target, boolean skipNull, Linq<Reflects.PropertyNode> toProperties) {
        if (mapping.ignore()
                || (sourceValue == null && (skipNull || eq(mapping.nullValueStrategy(), BeanMapNullValueStrategy.Ignore)))) {
            return Reflects.invokeMethod(toProperties.first(p -> eq(p.propertyName, propertyName)).getter, target);
        }
        if (sourceValue instanceof String) {
            String val = (String) sourceValue;
            if (mapping.trim()) {
                val = val.trim();
            }
            if (!Strings.isEmpty(mapping.format())) {
                val = String.format(mapping.format(), val);
            }
            sourceValue = val;
        }
        if (sourceValue == null && eq(mapping.nullValueStrategy(), BeanMapNullValueStrategy.SetToDefault)) {
            sourceValue = mapping.defaultValue();
        }
        if (mapping.converter() != BeanMapConverter.class) {
            sourceValue = Reflects.<BeanMapConverter>newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
        }
        if (!Strings.isEmpty(mapping.source())) {
            Reflects.PropertyNode propertyNode = Reflects.getProperties(source.getClass()).firstOrDefault(p -> eq(mapping.source(), p.propertyName));
            if (propertyNode != null) {
                sourceValue = Reflects.invokeMethod(propertyNode.getter, source);
            }
        }
        return sourceValue;
    }
}
