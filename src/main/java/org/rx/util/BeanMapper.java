package org.rx.util;

import net.sf.cglib.beans.BeanCopier;
import org.rx.App;

import java.lang.StringBuilder;

import org.rx.bean.Const;
import org.rx.validator.ValidateUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import org.rx.NQuery;
import org.rx.cache.WeakCache;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.rx.Contract.isNull;
import static org.rx.Contract.require;

/**
 * map from multi sources
 */
public class BeanMapper {
    public class Flags {
        public static final int SkipNull      = 1;
        public static final int TrimString    = 1 << 1;
        public static final int ValidateBean  = 1 << 2;
        public static final int NonCheckMatch = 1 << 3;
    }

    private static class MapConfig {
        public final BeanCopier         copier;
        public volatile boolean         isCheck;
        public Set<String>              ignoreMethods;
        public Function<String, String> methodMatcher;
        public BiConsumer               postProcessor;

        public MapConfig(BeanCopier copier) {
            this.copier = copier;
        }
    }

    private static class CacheItem {
        public final List<Method> setters;
        public final List<Method> getters;

        public CacheItem(List<Method> setters, List<Method> getters) {
            this.setters = setters;
            this.getters = getters;
        }
    }

    private static final String                      Get         = "get", GetBool = "is", Set = "set";
    private static final WeakCache<Class, CacheItem> methodCache = new WeakCache<>();
    private static BeanMapper                        instance;

    public static BeanMapper getInstance() {
        if (instance == null) {
            synchronized (methodCache) {
                if (instance == null) {
                    instance = new BeanMapper();
                }
            }
        }
        return instance;
    }

    @SuppressWarnings(Const.AllWarnings)
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
            List<Method> setters = Arrays.stream(tType.getMethods())
                    .filter(p -> p.getName().startsWith(Set) && p.getParameterCount() == 1)
                    .collect(Collectors.toList());
            List<Method> getters = Arrays.stream(tType.getMethods())
                    .filter(p -> !"getClass".equals(p.getName())
                            && (p.getName().startsWith(Get) || p.getName().startsWith(GetBool))
                            && p.getParameterCount() == 0)
                    .collect(Collectors.toList());
            List<Method> s2 = setters.stream()
                    .filter(ps -> getters.stream().anyMatch(pg -> exEquals(pg.getName(), ps.getName())))
                    .collect(Collectors.toList());
            List<Method> g2 = getters.stream()
                    .filter(pg -> s2.stream().anyMatch(ps -> exEquals(pg.getName(), ps.getName())))
                    .collect(Collectors.toList());
            return new CacheItem(s2, g2);
        }, true);
    }

    private static Set<String> toMethodNames(List<Method> methods) {
        return methods.stream().map(Method::getName).collect(Collectors.toSet());
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

    public BeanMapper setConfig(Class from, Class to, Function<String, String> methodMatcher, String... ignoreMethods) {
        MapConfig config = getConfig(from, to);
        synchronized (config) {
            config.methodMatcher = methodMatcher;
            config.ignoreMethods = Arrays.stream(ignoreMethods)
                    .map(p -> p.startsWith(Set) ? p : Set + App.toTitleCase(p)).collect(Collectors.toSet());
        }
        return this;
    }

    public BeanMapper setConfig(Class from, Class to, Function<String, String> methodMatcher, BiConsumer postProcessor,
                                String... ignoreMethods) {
        MapConfig config = getConfig(from, to);
        synchronized (config) {
            config.methodMatcher = methodMatcher;
            config.postProcessor = postProcessor;
            config.ignoreMethods = Arrays.stream(ignoreMethods)
                    .map(p -> p.startsWith(Set) ? p : Set + App.toTitleCase(p)).collect(Collectors.toSet());
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

        try {
            return map(source, targetType.newInstance(), 0);
        } catch (ReflectiveOperationException ex) {
            throw new BeanMapException(ex);
        }
    }

    public <T> T map(Object source, T target, int flags) {
        require(source, target);

        Class from = source.getClass(), to = target.getClass();
        MapConfig config = getConfig(from, to);
        boolean skipNull = checkFlag(flags, Flags.SkipNull), trimString = checkFlag(flags, Flags.TrimString),
                nonCheckMatch = checkFlag(flags, Flags.NonCheckMatch);
        final CacheItem tmc = getMethods(to);
        TreeSet<String> targetMethods = new TreeSet<>();
        config.copier.copy(source, target, (sourceValue, targetMethodType, methodName) -> {
            String setterName = methodName.toString();
            targetMethods.add(setterName);
            if (checkSkip(sourceValue, setterName, skipNull, config)) {
                Method gm = tmc.getters.stream().filter(p -> exEquals(p.getName(), setterName)).findFirst().get();
                return invoke(gm, target);
            }
            if (trimString && sourceValue instanceof String) {
                sourceValue = ((String) sourceValue).trim();
            }
            return App.changeType(sourceValue, targetMethodType);
        });
        TreeSet<String> copiedNames = targetMethods;
        if (config.ignoreMethods != null) {
            copiedNames.addAll(config.ignoreMethods);
        }
        Set<String> allNames = toMethodNames(tmc.setters),
                missedNames = NQuery.of(allNames).except(copiedNames).toSet();
        if (config.methodMatcher != null) {
            final CacheItem fmc = getMethods(from);
            //避免missedNames.remove引发异常
            for (String missedName : new ArrayList<>(missedNames)) {
                Method fm;
                String fromName = isNull(
                        config.methodMatcher.apply(missedName.substring(3, 4).toLowerCase() + missedName.substring(4)),
                        "");
                if (fromName.length() == 0 || (fm = fmc.getters.stream().filter(p -> gmEquals(p.getName(), fromName))
                        .findFirst().orElse(null)) == null) {
                    Method tm;
                    if (nonCheckMatch || ((tm = tmc.getters.stream().filter(p -> exEquals(p.getName(), missedName))
                            .findFirst().orElse(null)) != null && invoke(tm, target) != null)) {
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
                Method tm = tmc.setters.stream().filter(p -> p.getName().equals(missedName)).findFirst().get();
                invoke(tm, target, sourceValue);
            }
        }
        if (config.postProcessor != null) {
            config.postProcessor.accept(source, target);
        }
        if (!nonCheckMatch && !config.isCheck) {
            synchronized (config) {
                for (String missedName : missedNames) {
                    Method tm;
                    if ((tm = tmc.getters.stream().filter(p -> exEquals(p.getName(), missedName)).findFirst()
                            .orElse(null)) == null) {
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
            content = App.toTitleCase(content);
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

    private boolean checkSkip(Object sourceValue, String methodName, boolean skipNull, MapConfig config) {
        return (skipNull && sourceValue == null)
                || (config.ignoreMethods != null && config.ignoreMethods.contains(methodName));
    }

    private boolean checkFlag(int flags, int value) {
        return (flags & value) == value;
    }
}
