package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.FlagsEnum;
import org.rx.beans.NEnum;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

public interface EventTarget<TSender extends EventTarget<TSender>> {
    @Slf4j
    class Delegate<TSender extends EventTarget<TSender>, TArgs extends EventArgs> implements BiConsumer<TSender, TArgs> {
        @Getter
        private final List<BiConsumer<TSender, TArgs>> invocationList = new CopyOnWriteArrayList<>();

        @Override
        public void accept(TSender target, TArgs args) {
            require(target, args);

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
        ThreadSafe(1),
        Quietly(1 << 1),
        DynamicAttach(1 << 2);

        @Getter
        private final int value;
    }

    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.None.add();
    }

    default <TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
        attachEvent(eventName, event, true);
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event, boolean combine) {
        require(eventName);

        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            EventListener.getInstance().attach(this, eventName, event, combine);
            return;
        }
        field.set(this, combine ? combine((BiConsumer<TSender, TArgs>) field.get(this), event) : event);
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void detachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
        require(eventName);

        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            EventListener.getInstance().detach(this, eventName, event);
            return;
        }
        field.set(this, remove((BiConsumer<TSender, TArgs>) field.get(this), event));
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(String eventName, TArgs args) {
        require(eventName);

        Field field = Reflects.getFields(this.getClass()).firstOrDefault(p -> p.getName().equals(eventName));
        if (field == null) {
            if (!eventFlags().has(EventFlags.DynamicAttach)) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            EventListener.getInstance().raise(this, eventName, args);
            return;
        }
        raiseEvent((BiConsumer<TSender, TArgs>) field.get(this), args);
    }

    default <TArgs extends EventArgs> void raiseEvent(BiConsumer<TSender, TArgs> event, TArgs args) {
        require(args);

        if (event == null) {
            return;
        }
        event.accept((TSender) this, args);
    }
}
