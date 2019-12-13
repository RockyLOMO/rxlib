package org.rx.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.beans.NEnum;

@RequiredArgsConstructor
public enum BeanMapFlag implements NEnum<BeanMapFlag> {
    None(0),
    SkipNull(1),
    ValidateBean(1 << 1),
    LogOnAllMapFail(1 << 2),
    ThrowOnAllMapFail(1 << 3);

    @Getter
    private final int value;
}
