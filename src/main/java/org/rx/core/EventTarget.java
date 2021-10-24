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

import static org.rx.core.App.*;

public interface EventTarget<TSender extends EventTarget<TSender>> extends EventListener {
    @RequiredArgsConstructor
    enum EventFlags implements NEnum<EventFlags> {
        NONE(0),
        DYNAMIC_ATTACH(1),
        THREAD_SAFE(1 << 1),
        QUIETLY(1 << 2);

        @Getter
        private final int value;
    }

    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags();
    }

    @NonNull
    default TaskScheduler asyncScheduler() {
        return Tasks.pool();
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
        d.single(event);
    }

    default <TArgs extends EventArgs> void detachEvent(@NonNull String eventName, TripleAction<TSender, TArgs> event) {
        Delegate<TSender, TArgs> d = Delegate.wrap(this, eventName);
        d.remove(event);
    }

    default <TArgs extends EventArgs> void raiseEvent(@NonNull String eventName, TArgs args) {
        Delegate<TSender, TArgs> d = Delegate.wrap(this, eventName);
        raiseEvent(d, args);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(TripleAction<TSender, TArgs> event, @NonNull TArgs args) {
        if (event == null) {
            return;
        }
        event.invoke((TSender) this, args);
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(String eventName, TArgs args) {
        return asyncScheduler().run(() -> raiseEvent(eventName, args));
    }

    default <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(TripleAction<TSender, TArgs> event, TArgs args) {
        return asyncScheduler().run(() -> raiseEvent(event, args));
    }
}
