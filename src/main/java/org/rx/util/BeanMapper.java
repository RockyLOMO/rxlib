package org.rx.util;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.beans.BeanCopier;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.rx.annotation.Mapping;
import org.rx.beans.FlagsEnum;
import org.rx.core.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.*;

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

    @SuppressWarnings(NonWarning)
    public <T> T define(Class<T> type) {
        require(type);
        require(type, type.isInterface());

        return (T) Enhancer.create(type, (MethodInterceptor) (o, method, args, methodProxy) -> {
            if (Reflects.ObjectMethods.contains(method)) {
                return methodProxy.invokeSuper(o, args);
            }
            Object target = null;
            if (method.isDefault()) {
                target = Reflects.invokeDefaultMethod(method, o, args);
            }
            switch (args.length) {
                case 1: {
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    MapConfig config = setMappings(args[0].getClass(), method.getReturnType(), type, o, method);
                    if (target == null) {
                        target = Reflects.newInstance(method.getReturnType());
                    }
                    return map(args[0], target, config.flags, method);
                }
                case 2:
                    MapConfig config = setMappings(args[0].getClass(), args[1].getClass(), type, o, method);
                    map(args[0], args[1], config.flags, method);
                    return method.getReturnType() == void.class ? null : args[1];
            }
            throw new InvalidOperationException("Error Define Method %s", method.getName());
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

    @SuppressWarnings(NonWarning)
    private <T> T map(Object source, T target, FlagsEnum<BeanMapFlag> flags, Method method) {
        require(source, target);
        if (flags == null) {
            flags = this.flags;
        }

        boolean skipNull = flags.has(BeanMapFlag.SkipNull);
        Class from = source.getClass(), to = target.getClass();
        MapConfig config = getConfig(from, to);
        NQuery<Mapping> mappings = NQuery.of(method != null ? config.mappings.getOrDefault(method, empty) : empty);
        final NQuery<Reflects.PropertyNode> toProperties = Reflects.getProperties(to);
        TreeSet<String> copiedNames = new TreeSet<>();
        config.copier.copy(source, target, (sourceValue, targetType, methodName) -> {
            String propertyName = Reflects.propertyName(methodName.toString());
            copiedNames.add(propertyName);
            Mapping mapping = mappings.firstOrDefault(p -> eq(p.target(), propertyName));
            if (mapping != null) {
                sourceValue = processMapping(mapping, sourceValue, targetType, propertyName, target, skipNull, toProperties);
            }
            return App.changeType(sourceValue, targetType);
        });

        final NQuery<Reflects.PropertyNode> fromProperties = Reflects.getProperties(from);
        for (Mapping mapping : mappings.where(p -> !copiedNames.contains(p.target()))) {
            copiedNames.add(mapping.target());
            Object sourceValue = null;
            Reflects.PropertyNode propertyNode = fromProperties.firstOrDefault(p -> eq(mapping.source(), p.propertyName));
            if (propertyNode != null) {
                sourceValue = Reflects.invokeMethod(propertyNode.getter, source);
            }
            Method targetMethod = toProperties.first(p -> eq(p.propertyName, mapping.target())).setter;
            Class targetType = targetMethod.getParameterTypes()[0];
            sourceValue = processMapping(mapping, sourceValue, targetType, mapping.target(), target, skipNull, toProperties);
            Reflects.invokeMethod(targetMethod, target, App.changeType(sourceValue, targetType));
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

    @SuppressWarnings(NonWarning)
    private Object processMapping(Mapping mapping, Object sourceValue, Class targetType, String propertyName, Object target, boolean skipNull, NQuery<Reflects.PropertyNode> toProperties) {
        if (mapping.ignore()
                || (sourceValue == null && (skipNull || eq(mapping.nullValueStrategy(), NullValueMappingStrategy.Ignore)))) {
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
        if (sourceValue == null && eq(mapping.nullValueStrategy(), NullValueMappingStrategy.SetToDefault)) {
            sourceValue = mapping.defaultValue();
        }
        if (mapping.converter() != BeanMapConverter.class) {
            sourceValue = Reflects.newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
        }
        return sourceValue;
    }
}
