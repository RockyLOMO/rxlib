package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.FlagsEnum;
import org.rx.bean.NEnum;
import org.rx.util.function.TripleAction;

import java.util.EventListener;
import java.util.concurrent.*;

import static org.rx.core.Constants.NON_UNCHECKED;

public interface EventTarget<TSender extends EventTarget<TSender>> extends EventListener {
    @RequiredArgsConstructor
    enum EventFlags implements NEnum<EventFlags> {
        NONE(0),
        DYNAMIC_ATTACH(1),
        QUIETLY(1 << 1);

        @Getter
        final int value;
    }

    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags();
    }

    @NonNull
    default ThreadPool asyncScheduler() {
        return Tasks.nextPool();
    }

    default <TArgs extends EventArgs> void attachEvent(String eventName, TripleAction<TSender, TArgs> event) {
        attachEvent(eventName, event, true);
    }

    default <TArgs extends EventArgs> void attachEvent(@NonNull String eventName, TripleAction<TSender, TArgs> event, boolean combine) {
        Delegate<TSender, TArgs> d = Delegate.wrap(this, eventName);
        if (combine) {
            d.combine(event);
            return;
        }
        d.replace(event);
    }

    default <TArgs extends EventArgs> void detachEvent(@NonNull String eventName, TripleAction<TSender, TArgs> event) {
        Delegate<TSender, TArgs> d = Delegate.wrap(this, eventName);
        d.remove(event);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(@NonNull String eventName, TArgs args) {
        Delegate<TSender, TArgs> d = Delegate.wrap(this, eventName);
        d.invoke((TSender) this, args);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(Delegate<TSender, TArgs> event, @NonNull TArgs args) {
        if (event.isEmpty()) {
            return;
        }
        event.invoke((TSender) this, args);
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(String eventName, TArgs args) {
        return asyncScheduler().runAsync(() -> raiseEvent(eventName, args));
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(Delegate<TSender, TArgs> event, TArgs args) {
        if (event.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncScheduler().runAsync(() -> raiseEvent(event, args));
    }
}
