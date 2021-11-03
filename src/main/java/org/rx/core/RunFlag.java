package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum RunFlag implements NEnum<RunFlag> {
    CONCURRENT(0),
    SYNCHRONIZED(1),
    SINGLE(2),
    OVERRIDE(3),
    TRANSFER(4),
    PRIORITY(5);

    final int value;
}
