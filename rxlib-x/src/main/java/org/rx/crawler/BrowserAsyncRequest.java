package org.rx.crawler;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class BrowserAsyncRequest implements Serializable, Comparable<BrowserAsyncRequest> {
    private final UUID asyncId;
    private final int priority;
    private final String url;

    @Override
    public int compareTo(@NonNull BrowserAsyncRequest o) {
        return Integer.compare(priority, o.priority);
    }
}
