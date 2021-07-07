package org.rx.core;

import lombok.Builder;

import java.io.Serializable;

@Builder
public class CacheExpirations implements Serializable {
    private static final long serialVersionUID = -310473355461448304L;
    public static final CacheExpirations NON_EXPIRE = CacheExpirations.builder().build();

    private final int absoluteExpiration;
    private final int slidingExpiration;
}
