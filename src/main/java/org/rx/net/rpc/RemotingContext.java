package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.function.Func;

import java.util.Objects;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RemotingContext {
    static final FastThreadLocal<RemotingContext> TL = new FastThreadLocal<>();

    public static RemotingContext context() {
        RemotingContext ctx = TL.getIfExists();
        Objects.requireNonNull(ctx, "No context");
        return ctx;
    }

    @SneakyThrows
    static <T> T invoke(Func<T> fn, RpcServer rs, RpcClientMeta rc) {
        TL.set(new RemotingContext(rs, rc));
        try {
            return fn.invoke();
        } finally {
            TL.remove();
        }
    }

    final RpcServer server;
    final RpcClientMeta client;
}
