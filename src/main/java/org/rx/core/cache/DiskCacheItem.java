package org.rx.core.cache;

import lombok.RequiredArgsConstructor;
import org.rx.bean.DateTime;

import java.io.Serializable;

@RequiredArgsConstructor
class DiskCacheItem<TV> implements Serializable {
    private static final long serialVersionUID = -7742074465897857966L;
    final TV value;
    final int slidingSeconds;
    DateTime expire;
}
