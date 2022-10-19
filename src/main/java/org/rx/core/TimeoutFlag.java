package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum TimeoutFlag implements NEnum<TimeoutFlag> {
    NONE(0),
    SINGLE(1),
    REPLACE(1 << 1),
    PERIOD(1 << 2);

    final int value;
}
