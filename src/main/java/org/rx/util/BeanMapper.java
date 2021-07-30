package org.rx.util;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.beans.BeanCopier;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.rx.annotation.Mapping;
import org.rx.bean.FlagsEnum;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BeanMapper {
    @RequiredArgsConstructor
    private static class MapConfig {
        private final BeanCopier copier;
        private final ConcurrentHashMap<Method, Mapping[]> mappings = new ConcurrentHashMap<>();
        private FlagsEnum<BeanMapFlag> flags;
    }

    @Getter
    private static final BeanMapper instance = new BeanMapper();
    private static final Mapping[] empty = new Mapping[0];

    private final Map<String, MapConfig> config = new ConcurrentHashMap<>();
    private final FlagsEnum<BeanMapFlag> flags = BeanMapFlag.LogOnNotAllMapped.flags();

    @SuppressWarnings(NON_WARNING)
    public <T> T define(@NonNull Class<T> type) {
        require(type, type.isInterface());

        return (T) Enhancer.create(type, (MethodInterceptor) (o, method, args, methodProxy) -> {
            if (Reflects.OBJECT_METHODS.contains(method)) {
                return methodProxy.invokeSuper(o, args);
            }
            Object target = null;
            if (method.isDefault()) {
                target = Reflects.invokeDefaultMethod(method, o, args);
            }

            boolean noreturn = method.getReturnType() == void.class;
            if (args.length >= 2) {
                MapConfig config = setMappings(args[0].getClass(), noreturn ? args[1].getClass() : method.getReturnType(), type, o, method);
                map(args[0], isNull(target, args[1]), config.flags, method);
                return noreturn ? null : args[1];
            }
            if (args.length == 1) {
                if (noreturn) {
                    return null;
                }
                MapConfig config = setMappings(args[0].getClass(), method.getReturnType(), type, o, method);
                if (target == null) {
                    target = Reflects.newInstance(method.getReturnType());
                }
                return map(args[0], target, config.flags, method);
            }
            throw new InvalidException("Error Define Method %s", method.getName());
        });
    }

    private MapConfig setMappings(Class from, Class to, Class<?> type, Object o, Method method) {
        MapConfig config = getConfig(from, to);
        config.mappings.computeIfAbsent(method, k -> {
            try {
                Method defMethod = type.getDeclaredMethod("getFlags");
                if (defMethod.isDefault()) {
                    config.flags = Reflects.invokeDefaultMethod(defMethod, o);
                }
            } catch (Exception e) {
                log.debug("{} Read flags fail {}", type, e.getMessage());
            }
            return method.getAnnotationsByType(Mapping.class);
        });
        return config;
    }

    private MapConfig getConfig(Class from, Class to) {
        return config.computeIfAbsent(cacheKey(from.getName(), to.getName()), k -> new MapConfig(BeanCopier.create(from, to, true)));
    }

    public <T> T map(Object source, Class<T> targetType) {
        return map(source, Reflects.newInstance(targetType));
    }

    public <T> T map(Object source, T target) {
        return map(source, target, null);
    }

    public <T> T map(Object source, T target, FlagsEnum<BeanMapFlag> flags) {
        return map(source, target, flags, null);
    }

    @SuppressWarnings(NON_WARNING)
    private <T> T map(@NonNull Object source, @NonNull T target, FlagsEnum<BeanMapFlag> flags, Method method) {
        if (flags == null) {
            flags = this.flags;
        }
        boolean skipNull = flags.has(BeanMapFlag.SkipNull);
        Class from = source.getClass(), to = target.getClass();
        final NQuery<Reflects.PropertyNode> toProperties = Reflects.getProperties(to);
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
                Class targetType = targetMethod.getParameterTypes()[0];
                Reflects.invokeMethod(targetMethod, target, Reflects.changeType(entry.getValue(), targetType));
            }
        } else {
            MapConfig config = getConfig(from, to);
            NQuery<Mapping> mappings = NQuery.of(method != null ? config.mappings.getOrDefault(method, empty) : empty);
            config.copier.copy(source, target, (sourceValue, targetType, methodName) -> {
                String propertyName = Reflects.propertyName(methodName.toString());
                copiedNames.add(propertyName);
                Mapping mapping = mappings.firstOrDefault(p -> eq(p.target(), propertyName));
                if (mapping != null) {
                    sourceValue = processMapping(mapping, sourceValue, targetType, propertyName, source, target, skipNull, toProperties);
                }
                return Reflects.changeType(sourceValue, targetType);
            });

            for (Mapping mapping : mappings.where(p -> !copiedNames.contains(p.target()))) {
                copiedNames.add(mapping.target());
                Object sourceValue = null;
                Reflects.PropertyNode propertyNode = toProperties.firstOrDefault(p -> eq(p.propertyName, mapping.target()));
                if (propertyNode == null) {
                    log.warn("Target property {} not found", mapping.target());
                    continue;
                }
                Method targetMethod = propertyNode.setter;
                Class targetType = targetMethod.getParameterTypes()[0];
                sourceValue = processMapping(mapping, sourceValue, targetType, mapping.target(), source, target, skipNull, toProperties);
                Reflects.invokeMethod(targetMethod, target, Reflects.changeType(sourceValue, targetType));
            }
        }

        boolean logOnFail = flags.has(BeanMapFlag.LogOnNotAllMapped), throwOnFail = flags.has(BeanMapFlag.ThrowOnNotAllMapped);
        if (logOnFail || throwOnFail) {
            NQuery<String> missedProperties = toProperties.select(p -> p.propertyName).except(copiedNames);
            if (missedProperties.any()) {
                String failMsg = String.format("Map %s to %s missed properties: %s", from.getSimpleName(), to.getSimpleName(), String.join(", ", missedProperties));
                if (throwOnFail) {
                    throw new BeanMapException(failMsg, missedProperties.toSet());
                }
                log.warn(failMsg);
            }
        }

        if (flags.has(BeanMapFlag.ValidateBean)) {
            Validator.validateBean(target);
        }
        return target;
    }

    @SuppressWarnings(NON_WARNING)
    private Object processMapping(Mapping mapping, Object sourceValue, Class targetType, String propertyName, Object source, Object target, boolean skipNull, NQuery<Reflects.PropertyNode> toProperties) {
        if (mapping.ignore()
                || (sourceValue == null && (skipNull || eq(mapping.nullValueStrategy(), BeanMapNullValueStrategy.Ignore)))) {
            return Reflects.invokeMethod(toProperties.first(p -> eq(p.propertyName, propertyName)).getter, target);
        }
        if (sourceValue instanceof String) {
            String val = (String) sourceValue;
            if (mapping.trim()) {
                val = val.trim();
            }
            if (!Strings.isNullOrEmpty(mapping.format())) {
                val = String.format(mapping.format(), val);
            }
            sourceValue = val;
        }
        if (sourceValue == null && eq(mapping.nullValueStrategy(), BeanMapNullValueStrategy.SetToDefault)) {
            sourceValue = mapping.defaultValue();
        }
        if (mapping.converter() != BeanMapConverter.class) {
            sourceValue = Reflects.newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
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
