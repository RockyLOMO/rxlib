package org.rx.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

//EventObject
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventArgs implements Serializable {
    private static final long serialVersionUID = 8965443362204763240L;
    public static final EventArgs EMPTY = new EventArgs();

    @Getter
    @Setter
    private boolean cancel;
}
