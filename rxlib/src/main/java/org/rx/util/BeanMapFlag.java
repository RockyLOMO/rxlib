package org.rx.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
public enum BeanMapFlag implements NEnum<BeanMapFlag> {
    NONE(0),
    SKIP_NULL(1),
    VALIDATE_BEAN(1 << 1),
    LOG_ON_MISS_MAPPING(1 << 2),
    THROW_ON_MISS_MAPPING(1 << 3);

    @Getter
    private final int value;
}
