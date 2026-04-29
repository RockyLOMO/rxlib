package org.rx.core.config;

@FunctionalInterface
public interface ConfigValidator<TConfig> {
    boolean validate(TConfig config) throws Throwable;
}
