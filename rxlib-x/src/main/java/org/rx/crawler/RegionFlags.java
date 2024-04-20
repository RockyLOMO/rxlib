package org.rx.crawler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum RegionFlags implements NEnum<RegionFlags> {
    NONE(0),
    DOMAIN_TOP(1),
    HTTP_ONLY(1 << 1),
    ALL(DOMAIN_TOP.value | HTTP_ONLY.value);

    private final int value;
}
