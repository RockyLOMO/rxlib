package org.rx.core;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

public class EventArgs implements Serializable {
    public static final EventArgs Empty = new EventArgs();

    @Getter
    @Setter
    private boolean cancel;

    protected EventArgs() {
    }
}
