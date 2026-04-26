package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.net.transport.hybrid.HybridServer;
import org.rx.net.transport.hybrid.HybridSession;
import org.rx.util.function.Func;

import static org.rx.core.Extends.require;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RemotingContext {
    static final FastThreadLocal<RemotingContext> CTX = new FastThreadLocal<RemotingContext>();

    public static RemotingContext context() {
        RemotingContext ctx = CTX.getIfExists();
        require(ctx);
        return ctx;
    }

    @SneakyThrows
    static <T> T invoke(Func<T> fn, HybridServer rs, HybridSession rc) {
        CTX.set(new RemotingContext(rs, rc));
        try {
            return fn.invoke();
        } finally {
            CTX.remove();
        }
    }

    final HybridServer server;
    final HybridSession client;
}
