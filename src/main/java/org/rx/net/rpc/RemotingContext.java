package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.Container;
import org.rx.util.function.Func;

import java.util.Objects;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RemotingContext {
    static final FastThreadLocal<RemotingContext> tl = new FastThreadLocal<>();

    public static RemotingContext context() {
        RemotingContext ctx = tl.getIfExists();
        Objects.requireNonNull(ctx, "No context");
        return ctx;
    }

    @SneakyThrows
    static <T> T invoke(Func<T> fn, RpcServerClient rc) {
        tl.set(new RemotingContext(rc));
        try {
            return fn.invoke();
        } finally {
            tl.remove();
        }
    }

    final RpcServerClient client;

    public <T> T attr() {
        return Container.<RpcServerClient, T>weakMap().get(client);
    }

    public <T> T attr(T val) {
        return Container.<RpcServerClient, T>weakMap().put(client, val);
    }
}
