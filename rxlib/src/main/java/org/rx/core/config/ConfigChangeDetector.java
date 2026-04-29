package org.rx.core.config;

@FunctionalInterface
public interface ConfigChangeDetector<TConfig> {
    boolean changed(TConfig oldConfig, TConfig newConfig) throws Throwable;
}
