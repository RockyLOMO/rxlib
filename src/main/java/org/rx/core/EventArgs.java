package org.rx.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventArgs implements Serializable {
    public static final EventArgs EMPTY = new EventArgs();

    @Getter
    @Setter
    private boolean cancel;
}
