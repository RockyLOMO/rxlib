package org.rx.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.sf.cglib.beans.BeanCopier;
import org.rx.beans.FlagsEnum;
import org.rx.beans.NEnum;
import org.rx.beans.Tuple;
import org.rx.core.*;

import org.rx.core.StringBuilder;
import org.rx.util.validator.ValidateUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.rx.core.Contract.isNull;
import static org.rx.core.Contract.require;

/**
 * map from multi sources https://yq.aliyun.com/articles/14958
 */
public class BeanMapper {
    @RequiredArgsConstructor
    private static class MapConfig {
        public final BeanCopier copier;
        public volatile boolean isCheck;
        public Function<String, String> propertyMatcher;
        public BiFunction<String, Tuple<Object, Class>, Object> propertyValueMatcher;
    }

    public static final String IgnoreProperty = "#ignore";
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

    @SuppressWarnings(Contract.AllWarnings)
    public static Function<String, String> match(String... pairs) {
        require(pairs);
        require(pairs, pairs.length % 2 == 0);

        return p -> {
            for (int i = 1; i < pairs.length; i += 2) {
                if (p.equals(pairs[i])) {
                    return pairs[i - 1];
                }
            }
            return p;
        };
    }

    private Map<UUID, MapConfig> config = new ConcurrentHashMap<>();

    private MapConfig getConfig(Class from, Class to) {
        require(from, to);

        UUID k = App.hash(from.getName() + to.getName());
        MapConfig mapConfig = config.get(k);
        if (mapConfig == null) {
            config.put(k, mapConfig = new MapConfig(BeanCopier.create(from, to, true)));
        }
        return mapConfig;
    }

    public BeanMapper setConfig(Class from, Class to, Function<String, String> propertyMatcher, BiFunction<String, Tuple<Object, Class>, Object> propertyValueMatcher) {
        MapConfig config = getConfig(from, to);
        synchronized (config) {
            config.propertyMatcher = propertyMatcher;
            config.propertyValueMatcher = propertyValueMatcher;
        }
        return this;
    }

    public <TF, TT> TT[] mapToArray(Collection<TF> fromSet, Class<TT> toType) {
        require(fromSet, toType);

        List<TT> toSet = new ArrayList<>();
        for (Object o : fromSet) {
            toSet.add(map(o, toType));
        }
        TT[] x = (TT[]) Array.newInstance(toType, toSet.size());
        toSet.toArray(x);
        return x;
    }

    public <T> T map(Object source, Class<T> targetType) {
        require(targetType);

        return map(source, Reflects.newInstance(targetType), Flags.None);
    }

    public <T> T map(Object source, T target, FlagsEnum<BeanMapFlag> flags) {
        require(source, target);
        if (flags == null) {
            flags = BeanMapFlag.None.add();
        }

        Class from = source.getClass(), to = target.getClass();
        MapConfig config = getConfig(from, to);
        boolean skipNull = flags.has(BeanMapFlag.SkipNull), trimString = flags.has(BeanMapFlag.TrimString), nonCheckMatch = flags.has(BeanMapFlag.NonCheckMatch);
        final Reflects.PropertyCacheNode toProperties = Reflects.getProperties(to);
        TreeSet<String> copiedNames = new TreeSet<>();
        if (config.propertyMatcher == null) {
            config.copier.copy(source, target, (sourceValue, targetMethodType, methodName) -> {
                String setterName = methodName.toString();
                copiedNames.add(setterName);
                if (checkSkip(sourceValue, skipNull)) {
                    Method gm = toProperties.getters.where(p -> Reflects.propertyEquals(p.getName(), setterName)).first();
                    return Reflects.invokeMethod(gm, target);
                }
                if (trimString && sourceValue instanceof String) {
                    sourceValue = ((String) sourceValue).trim();
                }
                if (config.propertyValueMatcher != null) {
                    Method tm = toProperties.setters.where(p -> Reflects.propertyEquals(p.getName(), setterName)).first();
                    sourceValue = config.propertyValueMatcher.apply(getFieldName(tm.getName()), Tuple.of(sourceValue, tm.getParameterTypes()[0]));
                }
                return App.changeType(sourceValue, targetMethodType);
            });
        }
        Set<String> allNames = toProperties.setters.select(Method::getName).toSet(), missedNames = NQuery.of(allNames).except(copiedNames).toSet();
        if (config.propertyMatcher != null) {
            final Reflects.PropertyCacheNode fromProperties = Reflects.getProperties(from);
            //避免missedNames.remove引发异常
            for (String missedName : new ArrayList<>(missedNames)) {
                Method fm;
                String fromName = isNull(config.propertyMatcher.apply(getFieldName(missedName)), "");
                if (IgnoreProperty.equals(fromName)) {
                    missedNames.remove(missedName);
                    continue;
                }
                if (fromName.length() == 0
                        || (fm = fromProperties.getters.where(p -> gmEquals(p.getName(), fromName)).firstOrDefault()) == null) {
                    Method tm;
                    if (nonCheckMatch || ((tm = properties.getters.where(p -> exEquals(p.getName(), missedName))
                            .firstOrDefault()) != null && Reflects.invokeMethod(tm, target) != null)) {
                        continue;
                    }
                    throw new BeanMapException(String.format("Not fund %s in %s..", fromName, from.getSimpleName()),
                            allNames, missedNames);
                }
                copiedNames.add(missedName);
                missedNames.remove(missedName);
                Object sourceValue = Reflects.invokeMethod(fm, source);
                if (skipNull && sourceValue == null) {
                    continue;
                }
                if (trimString && sourceValue instanceof String) {
                    sourceValue = ((String) sourceValue).trim();
                }
                Method tm = properties.setters.where(p -> p.getName().equals(missedName)).first();
                if (config.propertyValueMatcher != null) {
                    sourceValue = config.propertyValueMatcher.apply(getFieldName(tm.getName()), Tuple.of(sourceValue, tm.getParameterTypes()[0]));
                }
                Reflects.invokeMethod(tm, target, sourceValue);
            }
        }
        if (!nonCheckMatch && !config.isCheck) {
            synchronized (config) {
                for (String missedName : missedNames) {
                    Method tm;
                    if ((tm = properties.getters.where(p -> exEquals(p.getName(), missedName)).firstOrDefault()) == null) {
                        continue;
                    }
                    if (Reflects.invokeMethod(tm, target) != null) {
                        copiedNames.add(missedName);
                    }
                }
                if (!missedNames.isEmpty()) {
                    throw new BeanMapException(String.format("Map %s to %s missed method %s..", from.getSimpleName(),
                            to.getSimpleName(), String.join(", ", missedNames)), allNames, missedNames);
                }
                config.isCheck = true;
            }
        }
        if (checkFlag(flags, Flags.ValidateBean)) {
            ValidateUtil.validateBean(target);
        }
        return target;
    }

    private boolean checkSkip(Object sourceValue, boolean skipNull) {
        return skipNull && sourceValue == null;
    }

    private boolean checkFlag(NEnum<Flags> flags, Flags value) {
        return flags.add().has(value);
    }

    private String getFieldName(String methodName) {
        return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
    }
}
