package org.rx.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.rx.core.exception.InvalidException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.NON_WARNING;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Container {
    @Getter
    private static final Container instance = new Container();
    private final Map<String, Object> holder = new ConcurrentHashMap<>();

    public <T> T get(Class<T> type) {
        return get(type.getName());
    }

    @SuppressWarnings(NON_WARNING)
    public <T> T get(String name) {
        T instance = (T) holder.get(name);
        if (instance == null) {
            throw new InvalidException("%s not registered", name);
        }
        return instance;
    }

    public <T> void register(Class<T> type, T instance) {
        register(type.getName(), instance);
    }

    public <T> void register(String name, T instance) {
        if (instance == null) {
            throw new InvalidException("%s instance can not be null", name);
        }

        holder.put(name, instance);
    }

    public void unregister(String name) {
        holder.remove(name);
    }
}
