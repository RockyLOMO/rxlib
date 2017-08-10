package org.rx.util;

import com.alibaba.fastjson.JSON;
import net.sf.cglib.beans.BeanCopier;
import net.sf.cglib.beans.BeanMap;
import net.sf.cglib.reflect.FastMethod;
import org.apache.commons.codec.digest.Crypt;
import org.rx.common.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JAVA Bean操作类 Created by za-wangxiaoming on 2017/7/25.
 */
public class BeanMapper {
    private static class Node {
        public BeanCopier            copier;
        public volatile boolean      isCheck;
        public Set<String>           ignoreMethods;
        public Func1<String, String> methodMatcher;
    }

    private static Map<UUID, Node>                       config      = new ConcurrentHashMap<>();
    private static Map<Class, Tuple<UUID, List<Method>>> methodCache = new ConcurrentHashMap<>();

    private Node getConfig(Class from, Class to) {
        UUID k = App.hash(from.getName() + to.getName());
        Node node = config.get(k);
        if (node == null) {
            config.put(k, node = new Node());
            node.copier = BeanCopier.create(from, to, true);
        }
        return node;
    }

    private Tuple<UUID, List<Method>> getMethods(Class to) {
        Tuple<UUID, List<Method>> result = methodCache.get(to);
        if (result == null) {
            List<Method> setters = Arrays.stream(to.getMethods())
                    .filter(p -> p.getName().startsWith("set") && p.getParameterCount() == 1)
                    .collect(Collectors.toList());
            List<Method> getters = Arrays.stream(to.getMethods()).filter(
                    p -> !"getClass".equals(p.getName()) && p.getName().startsWith("get") && p.getParameterCount() == 0)
                    .collect(Collectors.toList());
            List<Method> methods = setters.stream()
                    .filter(p21 -> getters.stream()
                            .anyMatch(p22 -> p21.getName().substring(3).equals(p22.getName().substring(3))))
                    .collect(Collectors.toList());
            methodCache.put(to, result = new Tuple<>(genKey(to, toMethodNames(methods)), methods));
        }
        return result;
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

    public synchronized void setConfig(Class from, Class to, Func1<String, String> methodMatcher,
                                       String... ignoreMethods) {
        Node config = getConfig(from, to);
        config.methodMatcher = methodMatcher;
        config.ignoreMethods = new HashSet<>(Arrays.asList(ignoreMethods));
    }

    public <T> T map(Object source, Class<T> targetType) {
        try {
            return map(source, targetType.newInstance());
        } catch (ReflectiveOperationException ex) {
            throw new BeanMapException(ex);
        }
    }

    public <T> T map(Object source, T target) {
        Class from = source.getClass(), to = target.getClass();
        Node config = getConfig(from, to);
        Set<String> targetMethods = new HashSet<>();
        config.copier.copy(source, target, (sourceValue, targetMethodType, methodName) -> {
            targetMethods.add(methodName.toString());
            return App.changeType(sourceValue, targetMethodType);
        });
        final Tuple<UUID, List<Method>> tmc = getMethods(to);
        Set<String> copiedNames = targetMethods;
        if (config.ignoreMethods != null) {
            copiedNames.addAll(config.ignoreMethods);
        }
        Set<String> allNames = toMethodNames(tmc.Item2),
                missedNames = new NQuery<>(allNames).except(copiedNames).toSet();
        if (config.methodMatcher != null) {
            final Tuple<UUID, List<Method>> fmc = getMethods(from);
            for (String missedName : missedNames) {
                try {
                    Object val = null;
                    String fromName = config.methodMatcher.invoke(missedName);
                    if (fromName != null) {
                        Method fm = fmc.Item2.stream().filter(p -> p.getName().equals(fromName)).findFirst()
                                .orElse(null);
                        if (fm != null) {
                            fm.setAccessible(true);
                            val = fm.invoke(source);
                        }
                    }
                    if (val == null) {
                        throw new BeanMapException(String.format("Not fund %s in %s..", fromName, from.getSimpleName()),
                                allNames, missedNames);
                    }
                    Method tm = tmc.Item2.stream().filter(p -> p.getName().equals(missedName)).findFirst().get();
                    tm.setAccessible(true);
                    tm.invoke(target, val);
                    copiedNames.add(missedName);
                    missedNames.remove(missedName);
                } catch (ReflectiveOperationException ex) {
                    throw new BeanMapException(ex);
                }
            }
        }
        if (!config.isCheck) {
            synchronized (config) {
                UUID k = genKey(to, copiedNames);
                App.logInfo("check %s %s", k, tmc.Item1);
                if (!k.equals(tmc.Item1)) {
                    throw new BeanMapException(String.format("Map %s to %s missed method %s..", from.getSimpleName(),
                            to.getSimpleName(), String.join(", ", missedNames)), allNames, missedNames);
                }
                config.isCheck = true;
            }
        }
        return target;
    }
}
