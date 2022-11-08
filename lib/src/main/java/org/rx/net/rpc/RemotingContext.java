package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.TcpServer;
import org.rx.util.function.Func;

import java.util.Objects;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RemotingContext {
    static final FastThreadLocal<RemotingContext> CTX = new FastThreadLocal<>();

    public static RemotingContext context() {
        RemotingContext ctx = CTX.getIfExists();
        Objects.requireNonNull(ctx, "No context");
        return ctx;
    }

    @SneakyThrows
    static <T> T invoke(Func<T> fn, TcpServer rs, TcpClient rc) {
        CTX.set(new RemotingContext(rs, rc));
        try {
            return fn.invoke();
        } finally {
            CTX.remove();
        }
    }

    final TcpServer server;
    final TcpClient client;
}
