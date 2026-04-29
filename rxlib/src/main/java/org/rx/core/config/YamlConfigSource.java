package org.rx.core.config;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Tasks;
import org.rx.core.TimeoutFlag;
import org.rx.core.YamlConfiguration;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class YamlConfigSource<TConfig> extends ConfigSource<TConfig> {
    private static final class PendingConfig<TConfig> {
        final String sourcePath;
        final TConfig config;
        final long loadMillis;

        PendingConfig(String sourcePath, TConfig config, long loadMillis) {
            this.sourcePath = sourcePath;
            this.config = config;
            this.loadMillis = loadMillis;
        }
    }

    private final String key;
    private final Type configType;
    private final String[] fileNames;
    private final YamlConfiguration yaml;
    private final AtomicReference<TConfig> current = new AtomicReference<>();
    private final AtomicLong version = new AtomicLong();
    private final Object pendingLock = new Object();
    private final TripleAction<YamlConfiguration, YamlConfiguration.ChangedEventArgs> yamlChangedHandler = this::onYamlChanged;

    private volatile ConfigValidator<TConfig> validator;
    private volatile ConfigChangeDetector<TConfig> changeDetector;
    private volatile long debounceMillis;
    private volatile boolean started;
    private volatile TConfig lastValidatedConfig;
    private volatile long lastLoadMillis;
    private volatile PendingConfig<TConfig> pendingConfig;

    public YamlConfigSource(@NonNull String sourceId, @NonNull Type configType, @NonNull String... fileNames) {
        this(sourceId, null, configType, fileNames);
    }

    public YamlConfigSource(@NonNull String sourceId, String key,
                            @NonNull Type configType, @NonNull String... fileNames) {
        super(sourceId);
        if (fileNames.length == 0) {
            throw new InvalidException("fileNames is empty");
        }
        this.key = key;
        this.configType = configType;
        this.fileNames = fileNames.clone();
        yaml = new YamlConfiguration(this.fileNames);
        yaml.setWatchValidator(this::validateYamlSnapshot);
    }

    public YamlConfigSource<TConfig> setValidator(ConfigValidator<TConfig> validator) {
        this.validator = validator;
        TConfig config = current.get();
        if (config != null && !validateConfigQuietly(config)) {
            throw new InvalidException("Invalid config {}", getSourceId());
        }
        return this;
    }

    public YamlConfigSource<TConfig> setChangeDetector(ConfigChangeDetector<TConfig> changeDetector) {
        this.changeDetector = changeDetector;
        return this;
    }

    public YamlConfigSource<TConfig> setWatchRetry(int retryTimes, long retryIntervalMillis) {
        yaml.setWatchRetry(retryTimes, retryIntervalMillis);
        return this;
    }

    public YamlConfigSource<TConfig> setDebounceMillis(long debounceMillis) {
        if (debounceMillis < 0) {
            throw new InvalidException("debounceMillis < 0");
        }
        this.debounceMillis = debounceMillis;
        return this;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    @Override
    public TConfig current() {
        return current.get();
    }

    @Override
    public long version() {
        return version.get();
    }

    @Override
    public synchronized YamlConfigSource<TConfig> start() {
        checkNotClosed();
        if (started) {
            return this;
        }
        TConfig config = loadTypedConfig(yaml);
        current.set(config);
        version.compareAndSet(0, 1);
        yaml.onChanged.add(yamlChangedHandler);
        try {
            yaml.enableWatch();
            started = true;
            return this;
        } catch (Throwable e) {
            yaml.onChanged.remove(yamlChangedHandler);
            yaml.disableWatch();
            throw InvalidException.sneaky(e);
        }
    }

    @Override
    public synchronized TConfig reload() {
        checkNotClosed();
        if (!started) {
            TConfig config = loadTypedConfig(yaml);
            current.set(config);
            version.compareAndSet(0, 1);
            return config;
        }
        yaml.raiseChange(watchFile());
        return current();
    }

    @Override
    protected synchronized void dispose() {
        started = false;
        yaml.onChanged.remove(yamlChangedHandler);
        yaml.disableWatch();
        synchronized (pendingLock) {
            pendingConfig = null;
        }
    }

    private String watchFile() {
        return fileNames[fileNames.length - 1];
    }

    private boolean validateYamlSnapshot(YamlConfiguration conf) {
        long beginNanos = System.nanoTime();
        TConfig config = readConfig(conf);
        if (!validateConfigQuietly(config)) {
            return false;
        }
        lastValidatedConfig = config;
        lastLoadMillis = elapsedMillis(beginNanos);
        return true;
    }

    private TConfig loadTypedConfig(YamlConfiguration conf) {
        long beginNanos = System.nanoTime();
        TConfig config = readConfig(conf);
        if (!validateConfigQuietly(config)) {
            throw new InvalidException("Invalid config {}", getSourceId());
        }
        lastValidatedConfig = config;
        lastLoadMillis = elapsedMillis(beginNanos);
        return config;
    }

    private TConfig readConfig(YamlConfiguration conf) {
        if (key == null || key.length() == 0) {
            return conf.readAs(configType);
        }
        return conf.readAs(key, configType, true);
    }

    private boolean validateConfigQuietly(TConfig config) {
        ConfigValidator<TConfig> v = validator;
        if (v == null) {
            return true;
        }
        try {
            return v.validate(config);
        } catch (Throwable e) {
            log.warn("Config validate failed {}", getSourceId(), e);
            return false;
        }
    }

    private void onYamlChanged(YamlConfiguration sender, YamlConfiguration.ChangedEventArgs e) {
        if (!started || isClosed()) {
            return;
        }
        TConfig config = lastValidatedConfig;
        if (config == null) {
            config = loadTypedConfig(sender);
        }
        long loadMillis = lastLoadMillis;
        long delay = debounceMillis;
        if (delay <= 0) {
            acceptConfig(e.getFilePath(), config, loadMillis);
            return;
        }
        synchronized (pendingLock) {
            pendingConfig = new PendingConfig<>(e.getFilePath(), config, loadMillis);
        }
        Tasks.setTimeout(this::acceptPendingConfig, delay, this, TimeoutFlag.REPLACE.flags());
    }

    private void acceptPendingConfig() {
        PendingConfig<TConfig> pending;
        synchronized (pendingLock) {
            pending = pendingConfig;
            pendingConfig = null;
        }
        if (pending == null || isClosed()) {
            return;
        }
        acceptConfig(pending.sourcePath, pending.config, pending.loadMillis);
    }

    private void acceptConfig(String sourcePath, TConfig newConfig, long loadMillis) {
        if (!started || isClosed()) {
            return;
        }
        TConfig oldConfig = current.get();
        if (!isChanged(oldConfig, newConfig)) {
            return;
        }
        long newVersion = version.incrementAndGet();
        current.set(newConfig);
        publishEvent(onChanged, new ConfigChangedEventArgs<>(getSourceId(), sourcePath,
                newVersion, oldConfig, newConfig, loadMillis));
    }

    private boolean isChanged(TConfig oldConfig, TConfig newConfig) {
        ConfigChangeDetector<TConfig> detector = changeDetector;
        if (detector == null) {
            return true;
        }
        try {
            return detector.changed(oldConfig, newConfig);
        } catch (Throwable e) {
            throw InvalidException.sneaky(e);
        }
    }

    private static long elapsedMillis(long beginNanos) {
        return (System.nanoTime() - beginNanos) / Constants.NANO_TO_MILLIS;
    }
}
