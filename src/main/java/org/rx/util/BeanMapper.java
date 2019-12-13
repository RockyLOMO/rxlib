package org.rx.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.beans.BeanCopier;
import net.sf.cglib.core.Converter;
import org.rx.annotation.Mapping;
import org.rx.beans.FlagsEnum;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.util.validator.ValidateUtil;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.*;

/**
 * map from multi sources https://yq.aliyun.com/articles/14958
 */
@Slf4j
public class BeanMapper {
    @RequiredArgsConstructor
    private static class MapConfig {
        private final BeanCopier copier;
        private final List<Mapping> mappings = new Vector<>();
    }

    @Getter
    private static final BeanMapper instance = new BeanMapper();

    public static String genCode(Class entity) {
        require(entity);

        String var = entity.getSimpleName();
        if (var.length() > 1) {
            var = var.substring(0, 1).toLowerCase() + var.substring(1);
        }
        StringBuilder code = new StringBuilder();
        for (java.lang.reflect.Method method : entity.getMethods()) {
            String name = method.getName();
            if (name.startsWith("set") && method.getParameterCount() == 1) {
                code.appendLine("%s.%s(null);", var, name);
            }
        }
        return code.toString();
    }

    private final Map<String, MapConfig> config = new ConcurrentHashMap<>();
    @Getter
    @Setter
    private FlagsEnum<BeanMapFlag> flags;

    private MapConfig getConfig(Class from, Class to) {
        require(from, to);

        return config.computeIfAbsent(cacheKey(from.getName(), to.getName()), k -> new MapConfig(BeanCopier.create(from, to, true)));
    }

    public BeanMapper setMappings(Class from, Class to, List<Mapping> mappings) {
        MapConfig config = getConfig(from, to);
        config.mappings.clear();
        config.mappings.addAll(mappings);
        return this;
    }

    public <T> T map(Object source, Class<T> targetType) {
        return map(source, Reflects.newInstance(targetType));
    }

    public <T> T map(Object source, T target) {
        return map(source, target, null);
    }

    public <T> T map(Object source, T target, FlagsEnum<BeanMapFlag> flags) {
        require(source, target);
        if (flags == null) {
            flags = BeanMapFlag.LogOnMatchFail.add();
        }

        boolean skipNull = flags.has(BeanMapFlag.SkipNull);
        Class from = source.getClass(), to = target.getClass();
        MapConfig config = getConfig(from, to);
        NQuery<Mapping> mappings = NQuery.of(config.mappings);
        final Reflects.PropertyCacheNode toProperties = Reflects.getProperties(to);
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

        final Reflects.PropertyCacheNode fromProperties = Reflects.getProperties(from);
        for (Mapping mapping : mappings.where(p -> !copiedNames.contains(p.target()))) {
            copiedNames.add(mapping.target());
            Object sourceValue = Reflects.invokeMethod(fromProperties.getters.first(p -> eq(mapping.source(), Reflects.propertyName(p.getName()))), source);
            Method targetMethod = toProperties.setters.first(p -> eq(Reflects.propertyName(p.getName()), mapping.target()));
            Class targetType = targetMethod.getParameterTypes()[0];
            sourceValue = processMapping(mapping, sourceValue, targetType, mapping.target(), target, skipNull, toProperties);
            Reflects.invokeMethod(targetMethod, target, App.changeType(sourceValue, targetType));
        }

        boolean logOnFail = flags.has(BeanMapFlag.LogOnMatchFail), throwOnFail = flags.has(BeanMapFlag.ThrowOnMatchFail);
        if (logOnFail || throwOnFail) {
            NQuery<String> missedProperties = toProperties.setters.select(p -> Reflects.propertyName(p.getName())).except(copiedNames);
            String failMsg = String.format("Map %s to %s missed properties\n\t%s", from.getSimpleName(), to.getSimpleName(), String.join(", ", missedProperties));
            if (throwOnFail) {
                throw new BeanMapException(failMsg, missedProperties.toSet());
            }
            log.warn(failMsg);
        }

        if (flags.has(BeanMapFlag.ValidateBean)) {
            ValidateUtil.validateBean(target);
        }
        return target;
    }

    private Object processMapping(Mapping mapping, Object sourceValue, Class targetType, String propertyName, Object target, boolean skipNull, Reflects.PropertyCacheNode toProperties) {
        if (mapping.ignore()
                || (sourceValue == null && (skipNull || eq(mapping.nullValueMappingStrategy(), NullValueMappingStrategy.Ignore)))) {
            return Reflects.invokeMethod(toProperties.getters.first(p -> eq(Reflects.propertyName(p.getName()), propertyName)), target);
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
        if (sourceValue == null && eq(mapping.nullValueMappingStrategy(), NullValueMappingStrategy.SetToDefault)) {
            sourceValue = mapping.defaultValue();
        }
        if (mapping.converter() != Converter.class) {
            sourceValue = Reflects.newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
        }
        return sourceValue;
    }
}
