package org.rx.socks.tcp;

import java.util.function.BiConsumer;

public interface RemoteEvent<TSender, TArgs> extends BiConsumer<TSender, TArgs> {
    String getName();

    String getEndpoint();
}
