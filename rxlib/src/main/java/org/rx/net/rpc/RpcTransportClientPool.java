package org.rx.net.rpc;

import org.rx.net.transport.core.TransportClient;

import java.util.function.Consumer;

public interface RpcTransportClientPool extends AutoCloseable {
    TransportClient borrowClient();

    TransportClient returnClient(TransportClient client);

    default boolean isHealthy() {
        return true;
    }

    default void forEachClient(Consumer<TransportClient> consumer) {
    }

    @Override
    default void close() throws Exception {
    }
}
