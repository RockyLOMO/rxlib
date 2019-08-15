package org.rx.util;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.rx.common.Contract.require;

@Component
public final class SpringContextUtil implements InitializingBean, ApplicationContextAware {
    /**
     * applicationContext.xml SpringContextHolder
     */
    private static ApplicationContext applicationContext;

    @Override
    public void afterPropertiesSet() {

    }

    public void setApplicationContext(ApplicationContext applicationContext) {
//        if (applicationContext == null) {
//            return;
//        }
        SpringContextUtil.applicationContext = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        require(applicationContext);
        return applicationContext;
    }

    /**
     * 类名(首字母小写)
     *
     * @param name
     * @param <T>
     * @return
     */
    public static <T> T getBean(String name) {
        return (T) getApplicationContext().getBean(name);
    }

    public static <T> T getBean(Class<T> clazz) {
        Map<String, T> beanMaps = getApplicationContext().getBeansOfType(clazz);
        return beanMaps != null && !beanMaps.isEmpty() ? beanMaps.values().iterator().next() : null;
    }
}
