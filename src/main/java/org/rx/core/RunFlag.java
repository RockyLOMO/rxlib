package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum RunFlag implements NEnum<RunFlag> {
    DEFAULT(0),
    SYNCHRONIZED(1),
    SINGLE(2),
    PRIORITY(3);

    final int value;
}
