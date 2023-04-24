package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class RefCounter<T> extends AbstractReferenceCounter {
    public final T ref;

//    @Setter
//    CachePolicy cachePolicy;
}
