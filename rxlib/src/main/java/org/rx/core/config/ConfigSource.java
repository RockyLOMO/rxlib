package org.rx.core.config;

import org.rx.bean.FlagsEnum;
import org.rx.core.Constants;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.exception.InvalidException;

public abstract class ConfigSource<TConfig> extends Disposable implements EventPublisher<ConfigSource<TConfig>> {
    public final Delegate<ConfigSource<TConfig>, ConfigChangedEventArgs<TConfig>> onChanged = Delegate.create();

    private final String sourceId;

    protected ConfigSource(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) {
            throw new InvalidException("sourceId is empty");
        }
        this.sourceId = sourceId;
    }

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return Constants.EVENT_ALL_FLAG;
    }

    public String getSourceId() {
        return sourceId;
    }

    public abstract TConfig current();

    public abstract long version();

    public abstract ConfigSource<TConfig> start();

    public TConfig reload() {
        return current();
    }
}
