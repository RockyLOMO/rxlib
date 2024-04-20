package org.rx.crawler;

public interface BrowserPoolListener extends AutoCloseable {
    int nextIdleId(BrowserType type);
}
