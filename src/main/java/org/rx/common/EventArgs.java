package org.rx.common;

import com.google.common.eventbus.EventBus;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

public class EventArgs implements Serializable {
    public static final EventBus bus = new EventBus();
    public static final EventArgs empty = new EventArgs();

    @Getter
    @Setter
    private boolean cancel;

    protected EventArgs() {
    }
}
