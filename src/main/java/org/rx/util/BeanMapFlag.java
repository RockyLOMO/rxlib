package org.rx.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
public enum BeanMapFlag implements NEnum<BeanMapFlag> {
    None(0),
    SkipNull(1),
    ValidateBean(1 << 1),
    LogOnNotAllMapped(1 << 2),
    ThrowOnNotAllMapped(1 << 3);

    @Getter
    private final int value;
}
