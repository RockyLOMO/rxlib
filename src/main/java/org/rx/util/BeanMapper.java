package org.rx.util;

import net.sf.cglib.beans.BeanCopier;
import org.rx.common.*;
import org.rx.validator.ValidateUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.rx.common.Contract.require;

/**
 * JAVA Bean操作类 Created by wangxiaoming on 2017/7/25.
 */
public class BeanMapper {
    public class Flags {
        public static final int SkipNull         = 1;
        public static final int TrimString       = 1 << 1;
        public static final int ValidateBean     = 1 << 2;
        public static final int NonCheckAllMatch = 1 << 3;
    }

    private static class MapConfig {
        public final BeanCopier     copier;
        public volatile boolean     isCheck;
        public Set<String>          ignoreMethods;
        public Func<String, String> methodMatcher;
        public BiConsumer           postProcessor;

        public MapConfig(BeanCopier copier) {
            this.copier = copier;
        }
    }

    private static class CacheItem {
        public final UUID         key;
        public final List<Method> setters;
        public final List<Method> getters;

        public CacheItem(UUID key, List<Method> setters, List<Method> getters) {
            this.key = key;
            this.setters = setters;
            this.getters = getters;
        }
    }

    private static Map<UUID, MapConfig>        config      = new ConcurrentHashMap<>();
    private static WeakCache<Class, CacheItem> methodCache = new WeakCache<>();

    private MapConfig getConfig(Class from, Class to) {
        require(from, to);

        UUID k = App.hash(from.getName() + to.getName());
        MapConfig mapConfig = config.get(k);
        if (mapConfig == null) {
            config.put(k, mapConfig = new MapConfig(BeanCopier.create(from, to, true)));
        }
        return mapConfig;
    }

    private CacheItem getMethods(Class to) {
        return methodCache.getOrAdd(to, () -> {
            List<Method> setters = Arrays.stream(to.getMethods())
                    .filter(p -> p.getName().startsWith("set") && p.getParameterCount() == 1)
                    .collect(Collectors.toList());
            List<Method> getters = Arrays.stream(to.getMethods())
                    .filter(p -> !"getClass".equals(p.getName())
                            && (p.getName().startsWith("get") || p.getName().startsWith("is"))
                            && p.getParameterCount() == 0)
                    .collect(Collectors.toList());
            List<Method> s2 = setters.stream().filter(
                    ps -> getters.stream().anyMatch(pg -> ps.getName().substring(3).equals(pg.getName().substring(3))))
                    .collect(Collectors.toList());
            List<Method> g2 = getters.stream().filter(
                    pg -> s2.stream().anyMatch(ps -> pg.getName().substring(3).equals(ps.getName().substring(3))))
                    .collect(Collectors.toList());
            return new CacheItem(genKey(to, toMethodNames(s2)), s2, g2);
        }, true);
    }

    private Set<String> toMethodNames(List<Method> methods) {
        return methods.stream().map(Method::getName).collect(Collectors.toSet());
    }

    private UUID genKey(Class to, Set<String> methodNames) {
        StringBuilder k = new StringBuilder(to.getName());
        methodNames.stream().forEachOrdered(k::append);
        App.logInfo("genKey %s..", k.toString());
        return App.hash(k.toString());
    }

    public synchronized BeanMapper setConfig(Class from, Class to, Func<String, String> methodMatcher,
                                             BiConsumer postProcessor, String... ignoreMethods) {
        MapConfig config = getConfig(from, to);
        config.methodMatcher = methodMatcher;
        config.postProcessor = postProcessor;
        config.ignoreMethods = new HashSet<>(Arrays.asList(ignoreMethods));
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
        final CacheItem tmc = getMethods(to);
        Set<String> targetMethods = new HashSet<>();
        config.copier.copy(source, target, (sourceValue, targetMethodType, methodName) -> {
            String mName = methodName.toString();
            targetMethods.add(mName);
            if (checkSkip(sourceValue, mName, checkFlag(flags, Flags.SkipNull), config)) {
                String fn = mName.substring(3);
                Method gm = tmc.getters.stream().filter(p -> p.getName().endsWith(fn)).findFirst().get();
                return invoke(gm, target);
            }
            if (checkFlag(flags, Flags.TrimString) && sourceValue instanceof String) {
                sourceValue = ((String) sourceValue).trim();
            }
            return App.changeType(sourceValue, targetMethodType);
        });
        Set<String> copiedNames = targetMethods;
        if (config.ignoreMethods != null) {
            copiedNames.addAll(config.ignoreMethods);
        }
        Set<String> allNames = toMethodNames(tmc.setters),
                missedNames = new NQuery<>(allNames).except(copiedNames).toSet();
        if (config.methodMatcher != null) {
            final CacheItem fmc = getMethods(from);
            for (String missedName : missedNames) {
                Method fm;
                String fromName = config.methodMatcher.invoke(missedName);
                if (fromName == null || (fm = fmc.getters.stream().filter(p -> p.getName().equals(fromName)).findFirst()
                        .orElse(null)) == null) {
                    throw new BeanMapException(String.format("Not fund %s in %s..", fromName, from.getSimpleName()),
                            allNames, missedNames);
                }
                Object sourceValue = invoke(fm, source);
                if (checkFlag(flags, Flags.SkipNull) && sourceValue == null) {
                    continue;
                }
                if (checkFlag(flags, Flags.TrimString) && sourceValue instanceof String) {
                    sourceValue = ((String) sourceValue).trim();
                }
                Method tm = tmc.setters.stream().filter(p -> p.getName().equals(missedName)).findFirst().get();
                invoke(tm, target, sourceValue);
                copiedNames.add(missedName);
                missedNames.remove(missedName);
            }
        }
        if (!checkFlag(flags, Flags.NonCheckAllMatch) && !config.isCheck) {
            synchronized (config) {
                UUID k = genKey(to, copiedNames);
                App.logInfo("check %s %s", k, tmc.key);
                if (!k.equals(tmc.key)) {
                    throw new BeanMapException(String.format("Map %s to %s missed method %s..", from.getSimpleName(),
                            to.getSimpleName(), String.join(", ", missedNames)), allNames, missedNames);
                }
                config.isCheck = true;
            }
        }
        if (checkFlag(flags, Flags.ValidateBean)) {
            ValidateUtil.validateBean(target);
        }
        if (config.postProcessor != null) {
            config.postProcessor.accept(source, target);
        }
        return target;
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
