package org.rx.core;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CacheExpirations {
    public static final CacheExpirations NON_EXPIRE = CacheExpirations.builder().build();

    private final int absoluteExpiration;
    private final int slidingExpiration;
}
