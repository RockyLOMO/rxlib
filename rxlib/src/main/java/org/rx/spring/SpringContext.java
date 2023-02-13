package org.rx.spring;

import lombok.Setter;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.util.function.TripleFunc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

import static org.rx.core.Extends.require;

/**
 * BeanPostProcessor 注册bean时变更
 * AopUtils
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpringContext implements ApplicationContextAware {
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
        Map<String, T> beanMaps = getApplicationContext().getBeansOfType(clazz);
        return !beanMaps.isEmpty() ? beanMaps.values().iterator().next() : null;
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

    @Setter
    static TripleFunc<Throwable, String, Object> controllerExceptionHandler;
}
