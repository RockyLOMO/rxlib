package org.rx.core.config;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.core.Constants;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.core.RunFlag;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleAction;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class ConfigResourceBinding<TConfig, TResource> extends Disposable
        implements EventPublisher<ConfigResourceBinding<TConfig, TResource>> {
    public final Delegate<ConfigResourceBinding<TConfig, TResource>, ConfigReloadEventArgs<TConfig, TResource>> onReloaded = Delegate.create();
    public final Delegate<ConfigResourceBinding<TConfig, TResource>, ConfigReloadEventArgs<TConfig, TResource>> onReloadFailed = Delegate.create();

    private final ConfigSource<TConfig> source;
    private final ConfigResource<TConfig, TResource> resource;
    private final AtomicReference<TConfig> activeConfig = new AtomicReference<>();
    private final AtomicReference<TResource> current = new AtomicReference<>();
    private final AtomicReference<ConfigChangedEventArgs<TConfig>> pendingChange = new AtomicReference<>();
    private final AtomicBoolean reloading = new AtomicBoolean();
    private final TripleAction<ConfigSource<TConfig>, ConfigChangedEventArgs<TConfig>> sourceChangedHandler = this::onSourceChanged;

    private volatile boolean started;

    public ConfigResourceBinding(@NonNull ConfigSource<TConfig> source,
                                 @NonNull ConfigResource<TConfig, TResource> resource) {
        this.source = source;
        this.resource = resource;
    }

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return Constants.EVENT_ALL_FLAG;
    }

    public ConfigSource<TConfig> source() {
        return source;
    }

    public TConfig currentConfig() {
        return activeConfig.get();
    }

    public TResource current() {
        return current.get();
    }

    public synchronized ConfigResourceBinding<TConfig, TResource> start() {
        checkNotClosed();
        if (started) {
            return this;
        }
        try {
            source.start();
            TConfig config = source.current();
            TResource instance = resource.create(config);
            activeConfig.set(config);
            current.set(instance);
            source.onChanged.add(sourceChangedHandler);
            started = true;
            return this;
        } catch (Throwable e) {
            closeSourceQuietly();
            throw InvalidException.sneaky(e);
        }
    }

    @Override
    protected synchronized void dispose() {
        started = false;
        source.onChanged.remove(sourceChangedHandler);
        closeResourceQuietly(current.getAndSet(null));
        closeSourceQuietly();
    }

    private void onSourceChanged(ConfigSource<TConfig> sender, ConfigChangedEventArgs<TConfig> e) {
        if (!started || isClosed()) {
            return;
        }
        pendingChange.set(e);
        scheduleReload();
    }

    private void scheduleReload() {
        if (!reloading.compareAndSet(false, true)) {
            return;
        }
        Tasks.runAsync(this::drainReloads, this, RunFlag.SERIAL.flags());
    }

    private void drainReloads() {
        try {
            while (started && !isClosed()) {
                ConfigChangedEventArgs<TConfig> change = pendingChange.getAndSet(null);
                if (change == null) {
                    return;
                }
                reload(change);
            }
        } finally {
            reloading.set(false);
            if (pendingChange.get() != null && started && !isClosed()) {
                scheduleReload();
            }
        }
    }

    private void reload(ConfigChangedEventArgs<TConfig> change) {
        if (!started || isClosed()) {
            return;
        }
        long beginNanos = System.nanoTime();
        TConfig oldConfig = activeConfig.get();
        TConfig newConfig = change.getNewConfig();
        TResource oldResource = current.get();
        TResource newResource = null;
        boolean restarted = false;
        try {
            if (resource.restartRequired(oldConfig, newConfig, oldResource)) {
                // 大对象默认走重建并原子切换，创建失败不影响旧实例。
                newResource = resource.create(newConfig);
                if (!started || isClosed()) {
                    closeResourceQuietly(newResource);
                    return;
                }
                TResource replaced = current.getAndSet(newResource);
                activeConfig.set(newConfig);
                restarted = true;
                closeResourceQuietly(replaced);
                publishReloaded(change, oldConfig, newConfig, replaced, newResource, true, beginNanos);
                return;
            }

            // 小对象或可变运行时数据可选择原地更新。
            resource.apply(oldConfig, newConfig, oldResource);
            activeConfig.set(newConfig);
            publishReloaded(change, oldConfig, newConfig, oldResource, oldResource, false, beginNanos);
        } catch (Throwable e) {
            if (!restarted) {
                closeResourceQuietly(newResource);
            }
            publishReloadFailed(change, oldConfig, newConfig, oldResource, current.get(), restarted, e, beginNanos);
        }
    }

    private void publishReloaded(ConfigChangedEventArgs<TConfig> change,
                                 TConfig oldConfig, TConfig newConfig,
                                 TResource oldResource, TResource newResource,
                                 boolean restarted, long beginNanos) {
        ConfigReloadEventArgs<TConfig, TResource> args = new ConfigReloadEventArgs<>(
                source.getSourceId(), change.getVersion(), oldConfig, newConfig,
                oldResource, newResource, restarted, true, null, elapsedMillis(beginNanos));
        publishEvent(onReloaded, args);
    }

    private void publishReloadFailed(ConfigChangedEventArgs<TConfig> change,
                                     TConfig oldConfig, TConfig newConfig,
                                     TResource oldResource, TResource newResource,
                                     boolean restarted, Throwable error, long beginNanos) {
        ConfigReloadEventArgs<TConfig, TResource> args = new ConfigReloadEventArgs<>(
                source.getSourceId(), change.getVersion(), oldConfig, newConfig,
                oldResource, newResource, restarted, false, error, elapsedMillis(beginNanos));
        publishEvent(onReloadFailed, args);
    }

    private void closeResourceQuietly(TResource instance) {
        if (instance == null) {
            return;
        }
        try {
            resource.close(instance);
        } catch (Throwable e) {
            log.warn("Config resource close failed {}", source.getSourceId(), e);
        }
    }

    private void closeSourceQuietly() {
        try {
            source.close();
        } catch (Throwable e) {
            log.warn("Config source close failed {}", source.getSourceId(), e);
        }
    }

    private static long elapsedMillis(long beginNanos) {
        return (System.nanoTime() - beginNanos) / Constants.NANO_TO_MILLIS;
    }
}
