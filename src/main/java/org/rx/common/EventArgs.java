package org.rx.common;

import com.google.common.eventbus.EventBus;

import java.io.Serializable;
import java.util.function.BiConsumer;

public class EventArgs implements Serializable {
    public static final EventBus eventBus = new EventBus();
    public static final EventArgs empty = new EventArgs();

    protected EventArgs() {
    }

    public static <TThis, TArgs extends EventArgs> void raiseEvent(BiConsumer<TThis, TArgs> event, TThis sender, TArgs args) {
        if (event == null) {
            return;
        }
        event.accept(sender, args);
    }
}
