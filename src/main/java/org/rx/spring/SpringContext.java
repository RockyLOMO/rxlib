package org.rx.spring;

import lombok.Setter;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.util.function.TripleFunc;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * BeanPostProcessor 注册bean时变更
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpringContext implements InitializingBean, ApplicationContextAware {
    private static ApplicationContext applicationContext;

    public static boolean isInitiated() {
        return applicationContext != null;
    }

    public static ApplicationContext getApplicationContext() {
        Objects.requireNonNull(applicationContext, "applicationContext");
        return applicationContext;
    }

    //类名(首字母小写)
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
        return NQuery.of(Strings.split(yamlArray, "\n")).select(p -> {
            if (p.startsWith("- ")) {
                return p.substring(2);
            }
            return p;
        }).toArray();
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        SpringContext.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {

    }

    @Setter
    static TripleFunc<Throwable, String, Object> controllerExceptionHandler;

    public static void metrics(String key, Object param) {
        LogInterceptor.metrics.get().put(key, param);
    }
}
