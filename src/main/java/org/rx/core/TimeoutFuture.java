package org.rx.core;

import io.netty.util.Timeout;

import java.util.concurrent.Future;

public interface TimeoutFuture extends Timeout, Future<Void> {
}
