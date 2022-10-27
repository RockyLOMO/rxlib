package org.rx.core;

import io.netty.util.Timeout;

import java.util.concurrent.ScheduledFuture;

public interface TimeoutFuture<T> extends Timeout, ScheduledFuture<T> {
}
