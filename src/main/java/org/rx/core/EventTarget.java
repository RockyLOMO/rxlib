package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.bean.NEnum;
import org.rx.core.exception.InvalidException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

public interface EventTarget<TSender extends EventTarget<TSender>> {
    @Slf4j
    class Delegate<TSender extends EventTarget<TSender>, TArgs extends EventArgs> implements BiConsumer<TSender, TArgs> {
        @Getter
        private final List<BiConsumer<TSender, TArgs>> invocationList = new CopyOnWriteArrayList<>();

        @SuppressWarnings(NON_WARNING)
        @Override
        public void accept(@NonNull TSender target, @NonNull TArgs args) {
            FlagsEnum<EventFlags> flags = target.eventFlags();
            if (flags.has(EventTarget.EventFlags.ThreadSafe)) {
                synchronized (target) {
                    innerRaise(target, args, flags);
                }
                return;
            }
            innerRaise(target, args, flags);
        }

        private void innerRaise(TSender target, TArgs args, FlagsEnum<EventTarget.EventFlags> flags) {
            for (BiConsumer<TSender, TArgs> biConsumer : invocationList) {
                try {
                    biConsumer.accept(target, args);
                } catch (Exception e) {
                    if (!flags.has(EventTarget.EventFlags.Quietly)) {
                        throw e;
                    }
                    log.warn("innerRaise", e);
                }
            }
        }
    }

    @RequiredArgsConstructor
    enum EventFlags implements NEnum<EventFlags> {
        None(0),
        DynamicAttach(1),
        ThreadSafe(1 << 1),
        Quietly(1 << 2);

        @Getter
        private final int value;
    }

    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.flags();
    }

    default <TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
        attachEvent(eventName, event, true);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    default <TArgs extends EventArgs> void attachEvent(@NonNull String eventName, BiConsumer<TSender, TArgs> event, boolean combine) {
        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidException("Event %s not defined", eventName);
            }
            EventListener.getInstance().attach(this, eventName, event, combine);
            return;
        }
        field.set(this, combine ? combine((BiConsumer<TSender, TArgs>) field.get(this), event) : event);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    default <TArgs extends EventArgs> void detachEvent(@NonNull String eventName, BiConsumer<TSender, TArgs> event) {
        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidException("Event %s not defined", eventName);
            }
            EventListener.getInstance().detach(this, eventName, event);
            return;
        }
        field.set(this, remove((BiConsumer<TSender, TArgs>) field.get(this), event));
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(@NonNull String eventName, TArgs args) {
        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidException("Event %s not defined", eventName);
            }
            EventListener.getInstance().raise(this, eventName, args);
            return;
        }
        raiseEvent((BiConsumer<TSender, TArgs>) field.get(this), args);
    }

    @SuppressWarnings(NON_WARNING)
    default <TArgs extends EventArgs> void raiseEvent(BiConsumer<TSender, TArgs> event, @NonNull TArgs args) {
        if (event == null) {
            return;
        }
        event.accept((TSender) this, args);
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(String eventName, TArgs args) {
        return Tasks.run(() -> raiseEvent(eventName, args));
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(BiConsumer<TSender, TArgs> event, TArgs args) {
        return Tasks.run(() -> raiseEvent(event, args));
    }
}
