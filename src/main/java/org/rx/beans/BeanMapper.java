package org.rx.beans;

import lombok.Getter;
import net.sf.cglib.beans.BeanCopier;
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
    public enum Flags implements NEnum<Flags> {
        None(0),
        SkipNull(1),
        TrimString(1 << 1),
        ValidateBean(1 << 2),
        NonCheckMatch(1 << 3);

        @Getter
        private int value;

        Flags(int val) {
            this.value = val;
        }
    }

    private static class MapConfig {
        public final BeanCopier copier;
        public volatile boolean isCheck;
        public Function<String, String> propertyMatcher;
        public BiFunction<String, Tuple<Object, Class>, Object> propertyValueMatcher;

        public MapConfig(BeanCopier copier) {
            this.copier = copier;
        }
    }

    private static class CacheItem {
        public final NQuery<Method> setters;
        public final NQuery<Method> getters;

        public CacheItem(NQuery<Method> setters, NQuery<Method> getters) {
            this.setters = setters;
            this.getters = getters;
        }
    }

    public static final String ignoreProperty = "#ignore";
    private static final String Get = "get", GetBool = "is", Set = "set";
    private static final WeakCache<Class, CacheItem> methodCache = new WeakCache<>();
    private static final Lazy<BeanMapper> instance = new Lazy<>(BeanMapper.class);

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

    public static BeanMapper getInstance() {
        return instance.getValue();
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

    private static CacheItem getMethods(Class to) {
        return methodCache.getOrAdd(to, tType -> {
            NQuery<Method> setters = NQuery.of(tType.getMethods())
                    .where(p -> p.getName().startsWith(Set) && p.getParameterCount() == 1);
            NQuery<Method> getters = NQuery.of(tType.getMethods()).where(p -> !"getClass".equals(p.getName())
                    && (p.getName().startsWith(Get) || p.getName().startsWith(GetBool)) && p.getParameterCount() == 0);
            NQuery<Method> s2 = setters.where(ps -> getters.any(pg -> exEquals(pg.getName(), ps.getName())));
            NQuery<Method> g2 = getters.where(pg -> s2.any(ps -> exEquals(pg.getName(), ps.getName())));
            return new CacheItem(s2, g2);
        }, true);
    }

    private static boolean exEquals(String getterName, String setterName) {
        return getterName.substring(getterName.startsWith(GetBool) ? GetBool.length() : Get.length())
                .equals(setterName.substring(Set.length()));
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

    public <T> T map(Object source, T target, NEnum<Flags> flags) {
        require(source, target);

        Class from = source.getClass(), to = target.getClass();
        MapConfig config = getConfig(from, to);
        boolean skipNull = checkFlag(flags, Flags.SkipNull), trimString = checkFlag(flags, Flags.TrimString),
                nonCheckMatch = checkFlag(flags, Flags.NonCheckMatch);
        final CacheItem tmc = getMethods(to);
        TreeSet<String> targetMethods = new TreeSet<>();
        if (config.propertyMatcher == null) {
            config.copier.copy(source, target, (sourceValue, targetMethodType, methodName) -> {
                String setterName = methodName.toString();
                targetMethods.add(setterName);
                if (checkSkip(sourceValue, setterName, skipNull, config)) {
                    Method gm = tmc.getters.where(p -> exEquals(p.getName(), setterName)).first();
                    return invoke(gm, target);
                }
                if (trimString && sourceValue instanceof String) {
                    sourceValue = ((String) sourceValue).trim();
                }
                if (config.propertyValueMatcher != null) {
                    Method tm = tmc.setters.where(p -> exEquals(p.getName(), setterName)).first();
                    sourceValue = config.propertyValueMatcher.apply(getFieldName(tm.getName()), Tuple.of(sourceValue, tm.getParameterTypes()[0]));
                }
                return App.changeType(sourceValue, targetMethodType);
            });
        }
        TreeSet<String> copiedNames = targetMethods;
        Set<String> allNames = tmc.setters.select(Method::getName).toSet(),
                missedNames = NQuery.of(allNames).except(copiedNames).toSet();
        if (config.propertyMatcher != null) {
            final CacheItem fmc = getMethods(from);
            //避免missedNames.remove引发异常
            for (String missedName : new ArrayList<>(missedNames)) {
                Method fm;
                String fromName = isNull(config.propertyMatcher.apply(getFieldName(missedName)), "");
                if (ignoreProperty.equals(fromName)) {
                    missedNames.remove(missedName);
                    continue;
                }
                if (fromName.length() == 0
                        || (fm = fmc.getters.where(p -> gmEquals(p.getName(), fromName)).firstOrDefault()) == null) {
                    Method tm;
                    if (nonCheckMatch || ((tm = tmc.getters.where(p -> exEquals(p.getName(), missedName))
                            .firstOrDefault()) != null && invoke(tm, target) != null)) {
                        continue;
                    }
                    throw new BeanMapException(String.format("Not fund %s in %s..", fromName, from.getSimpleName()),
                            allNames, missedNames);
                }
                copiedNames.add(missedName);
                missedNames.remove(missedName);
                Object sourceValue = invoke(fm, source);
                if (skipNull && sourceValue == null) {
                    continue;
                }
                if (trimString && sourceValue instanceof String) {
                    sourceValue = ((String) sourceValue).trim();
                }
                Method tm = tmc.setters.where(p -> p.getName().equals(missedName)).first();
                if (config.propertyValueMatcher != null) {
                    sourceValue = config.propertyValueMatcher.apply(getFieldName(tm.getName()), Tuple.of(sourceValue, tm.getParameterTypes()[0]));
                }
                invoke(tm, target, sourceValue);
            }
        }
        if (!nonCheckMatch && !config.isCheck) {
            synchronized (config) {
                for (String missedName : missedNames) {
                    Method tm;
                    if ((tm = tmc.getters.where(p -> exEquals(p.getName(), missedName)).firstOrDefault()) == null) {
                        continue;
                    }
                    if (invoke(tm, target) != null) {
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

    private boolean gmEquals(String getterName, String content) {
        if (content.startsWith(Get)) {
            content = content.substring(Get.length());
        } else if (content.startsWith(GetBool)) {
            content = content.substring(GetBool.length());
        } else {
            content = Strings.toTitleCase(content);
        }
        return getterName.substring(getterName.startsWith(GetBool) ? GetBool.length() : Get.length()).equals(content);
    }

    private Object invoke(Method method, Object obj, Object... args) {
        try {
            method.setAccessible(true);//nonCheck
            return method.invoke(obj, args);
        } catch (ReflectiveOperationException ex) {
            throw new BeanMapException(ex);
        }
    }

    private boolean checkSkip(Object sourceValue, String setterName, boolean skipNull, MapConfig config) {
        return skipNull && sourceValue == null;
    }

    private boolean checkFlag(NEnum<Flags> flags, Flags value) {
        return flags.add().has(value);
    }

    private String getFieldName(String methodName) {
        return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
    }
}
