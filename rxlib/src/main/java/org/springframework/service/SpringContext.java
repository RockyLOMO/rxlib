package org.springframework.service;

import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.util.function.QuadraFunc;
import org.rx.util.function.TripleFunc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

import static org.rx.core.Extends.require;

/**
 * BeanPostProcessor 注册bean时变更
 * AopUtils
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpringContext implements ApplicationContextAware {
    public static TripleFunc<HttpServletRequest, HttpServletResponse, Object> preHandle;
    public static QuadraFunc<HttpServletRequest, HttpServletResponse, Object, Object> postHandle;
    public static TripleFunc<Throwable, String, Object> exceptionHandle;
    private static ApplicationContext applicationContext;

    public static boolean isInitiated() {
        return applicationContext != null;
    }

    public static ApplicationContext getApplicationContext() {
        require(applicationContext);
        return applicationContext;
    }

    public static <T> T getBean(String name) {
        return (T) getApplicationContext().getBean(name);
    }

    public static <T> T getBean(Class<T> clazz) {
        return getBean(clazz, true);
    }

    public static <T> T getBean(Class<T> clazz, boolean throwOnEmpty) {
        Map<String, T> beanMaps = getApplicationContext().getBeansOfType(clazz);
        if (beanMaps.isEmpty()) {
            if (throwOnEmpty) {
                throw new InvalidException("Bean {} not registered", clazz);
            }
            return null;
        }
        return beanMaps.values().iterator().next();
    }

    public static String[] fromYamlArray(String yamlArray) {
        if (Strings.isEmpty(yamlArray)) {
            return new String[0];
        }
        return Linq.from(Strings.split(yamlArray, "\n")).select(p -> {
            if (p.startsWith("- ")) {
                return p.substring(2);
            }
            return p;
        }).toArray();
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        SpringContext.applicationContext = applicationContext;
    }
}
