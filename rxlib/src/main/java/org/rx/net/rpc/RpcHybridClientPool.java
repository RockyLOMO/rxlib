package org.rx.net.rpc;

import org.rx.net.transport.hybrid.HybridClient;

import java.util.function.Consumer;

public interface RpcHybridClientPool extends AutoCloseable {
    HybridClient borrowClient();

    HybridClient returnClient(HybridClient client);

    default boolean isHealthy() {
        return true;
    }

    default void forEachClient(Consumer<HybridClient> consumer) {
    }

    default void close() throws Exception {
    }
}
