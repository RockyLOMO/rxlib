package org.rx.core;

import lombok.SneakyThrows;

import java.lang.reflect.Field;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.require;

public interface EventTarget<TSender extends EventTarget<TSender>> {
    default boolean dynamicAttach() {
        return false;
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
        require(eventName);

        try {
            Field field = this.getClass().getField(eventName);
            field.set(this, event);
        } catch (NoSuchFieldException e) {
            if (!dynamicAttach()) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            EventListener.instance.attach(this, eventName, event);
        }
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(String eventName, TArgs args) {
        require(eventName);

        try {
            Field field = this.getClass().getField(eventName);
            BiConsumer<TSender, TArgs> event = (BiConsumer<TSender, TArgs>) field.get(this);
            raiseEvent(event, args);
        } catch (NoSuchFieldException e) {
            if (!dynamicAttach()) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            EventListener.instance.raise(this, eventName, args);
        }
    }

    default <TArgs extends EventArgs> void raiseEvent(BiConsumer<TSender, TArgs> event, TArgs args) {
        require(args);

        if (event == null) {
            return;
        }
        event.accept((TSender) this, args);
    }
}
