package org.rx.spring;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.rx.core.Contract.require;

/**
 * BeanPostProcessor 注册bean时变更
 */
@Component
public class SpringContext implements InitializingBean, ApplicationContextAware {
    private static ApplicationContext applicationContext;

    public static ApplicationContext getApplicationContext() {
        require(applicationContext);
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

    @Override
    public void afterPropertiesSet() {
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        SpringContext.applicationContext = applicationContext;
    }
}
