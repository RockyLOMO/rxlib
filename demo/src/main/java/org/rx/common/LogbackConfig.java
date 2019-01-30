package org.rx.common;

import ch.qos.logback.ext.spring.LogbackConfigurer;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class LogbackConfig {
    @Bean
    public MethodInvokingFactoryBean logbackConfigurer(Environment environment) {
        String currentProfile = getProfileFromEnvironment(environment);
        MethodInvokingFactoryBean factoryBean = new MethodInvokingFactoryBean();
        factoryBean.setTargetClass(LogbackConfigurer.class);
        factoryBean.setTargetMethod("initLogging");
        factoryBean.setArguments(String.format("classpath:logback-%s.xml", currentProfile));
        return factoryBean;
    }

    private String getProfileFromEnvironment(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (!ArrayUtils.isEmpty(profiles)) {
            return profiles[0];
        }
        profiles = environment.getDefaultProfiles();
        if (!ArrayUtils.isEmpty(profiles)) {
            return profiles[0];
        }
        throw new IllegalStateException("Must specify a spring profile in the environment!");
    }
}
