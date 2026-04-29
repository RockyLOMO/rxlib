package org.rx.core.config;

public interface ConfigResource<TConfig, TResource> {
    TResource create(TConfig config) throws Throwable;

    default boolean restartRequired(TConfig oldConfig, TConfig newConfig, TResource current) throws Throwable {
        return true;
    }

    default void apply(TConfig oldConfig, TConfig newConfig, TResource current) throws Throwable {
    }

    default void close(TResource resource) throws Throwable {
        if (resource instanceof AutoCloseable) {
            ((AutoCloseable) resource).close();
        }
    }
}
