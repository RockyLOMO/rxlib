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
    TRANSFER(3),
    PRIORITY(4);

    final int value;
}
