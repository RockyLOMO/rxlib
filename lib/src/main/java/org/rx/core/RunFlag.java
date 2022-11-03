package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum RunFlag implements NEnum<RunFlag> {
    NONE(0),
    SINGLE(1),
    SYNCHRONIZED(1 << 1),
    TRANSFER(1 << 2),
    PRIORITY(1 << 3),
    INHERIT_FAST_THREAD_LOCALS(1 << 4),
    THREAD_TRACE(1 << 5);

    final int value;
}
