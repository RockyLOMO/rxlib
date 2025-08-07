package org.rx.crawler;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class BrowserAsyncRequest implements Serializable, Comparable<BrowserAsyncRequest> {
    private final long asyncId;
    private final int priority;
    private final String cookieRegion;

    @Override
    public int compareTo(@NonNull BrowserAsyncRequest o) {
        return Integer.compare(priority, o.priority);
    }
}
