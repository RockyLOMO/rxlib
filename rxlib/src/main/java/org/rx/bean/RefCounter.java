package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.CachePolicy;

@RequiredArgsConstructor
@Getter
public class RefCounter<T> extends AbstractReferenceCounter {
    public final T ref;

//    @Setter
//    CachePolicy cachePolicy;
}
