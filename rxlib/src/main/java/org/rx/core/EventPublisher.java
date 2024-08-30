package org.rx.core;

import lombok.*;
import org.rx.bean.FlagsEnum;
import org.rx.bean.NEnum;
import org.rx.util.function.TripleAction;

import java.util.EventListener;
import java.util.concurrent.CompletableFuture;

import static org.rx.core.Constants.NON_UNCHECKED;

public interface EventPublisher<TSender extends EventPublisher<TSender>> extends EventListener {
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class StaticEventPublisher implements EventPublisher<StaticEventPublisher> {
    }

    @RequiredArgsConstructor
    enum EventFlags implements NEnum<EventFlags> {
        NONE(0),
        DYNAMIC_ATTACH(1),
        QUIETLY(1 << 1);

        @Getter
        final int value;
    }

//    StaticEventPublisher STATIC_EVENT_INSTANCE = new StaticEventPublisher();
    StaticEventPublisher STATIC_QUIETLY_EVENT_INSTANCE = new StaticEventPublisher() {
        @Override
        public FlagsEnum<EventFlags> eventFlags() {
            return Constants.EVENT_ALL_FLAG;
        }
    };

    default FlagsEnum<EventFlags> eventFlags() {
        return Constants.EVENT_DYNAMIC_FLAG;
    }

    @NonNull
    default ThreadPool asyncScheduler() {
        return Tasks.nextPool();
    }

    default <TEvent> void attachEvent(String eventName, TripleAction<TSender, TEvent> eventDelegate) {
        attachEvent(eventName, eventDelegate, true);
    }

    default <TEvent> void attachEvent(@NonNull String eventName, TripleAction<TSender, TEvent> eventDelegate, boolean combine) {
        Delegate<TSender, TEvent> d = Delegate.wrap(this, eventName);
        if (combine) {
            d.combine(eventDelegate);
            return;
        }
        d.replace(eventDelegate);
    }

    default <TEvent> void detachEvent(@NonNull String eventName, TripleAction<TSender, TEvent> eventDelegate) {
        Delegate<TSender, TEvent> d = Delegate.wrap(this, eventName);
        d.remove(eventDelegate);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    default <TEvent> void raiseEvent(@NonNull String eventName, TEvent event) {
        Delegate<TSender, TEvent> d = Delegate.wrap(this, eventName);
        d.invoke((TSender) this, event);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    default <TEvent> void raiseEvent(Delegate<TSender, TEvent> eventDelegate, @NonNull TEvent event) {
        if (eventDelegate.isEmpty()) {
            return;
        }
        eventDelegate.invoke((TSender) this, event);
    }

    default <TEvent> CompletableFuture<Void> raiseEventAsync(String eventName, TEvent event) {
        return asyncScheduler().runAsync(() -> raiseEvent(eventName, event));
    }

    default <TEvent> CompletableFuture<Void> raiseEventAsync(Delegate<TSender, TEvent> eventDelegate, TEvent event) {
        if (eventDelegate.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncScheduler().runAsync(() -> raiseEvent(eventDelegate, event));
    }
}
