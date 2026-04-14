package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.Getter;
import lombok.Setter;
import org.rx.core.Strings;
import org.rx.util.function.Action;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Socks5WarmupHandler extends ChannelDuplexHandler {
    enum State {
        INIT,
        AUTHING,
        READY,
        CONNECTING,
        CONSUMED,
        FAILED
    }

    private static final Socks5InitialRequest INIT_REQUEST_NO_AUTH =
            new DefaultSocks5InitialRequest(Collections.singletonList(Socks5AuthMethod.NO_AUTH));
    private static final Socks5InitialRequest INIT_REQUEST_PASSWORD =
            new DefaultSocks5InitialRequest(Arrays.asList(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD));

    private final SocketAddress proxyAddress;
    private final String username;
    private final String password;
    private final int connectTimeoutMillis;
    private final CompletableFuture<Channel> readyFuture = new CompletableFuture<>();
    private volatile State state = State.INIT;
    @Getter
    private volatile long readyAtMillis;
    private volatile String decoderName;
    private volatile String encoderName;
    private volatile ChannelHandlerContext ctx;
    private volatile Channel channel;
    private volatile ChannelPromise connectPromise;
    @Setter
    private Action connectedCallback;
    private volatile ScheduledFuture<?> authTimeout;
    private volatile ScheduledFuture<?> commandTimeout;

    public Socks5WarmupHandler(SocketAddress proxyAddress, String username, String password, int connectTimeoutMillis) {
        this.proxyAddress = proxyAddress;
        this.username = Strings.isEmpty(username) ? null : username;
        this.password = Strings.isEmpty(password) ? null : password;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public CompletableFuture<Channel> readyFuture() {
        return readyFuture;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.channel = ctx.channel();
        ChannelPipeline p = ctx.pipeline();
        String name = ctx.name();

        Socks5InitialResponseDecoder decoder = new Socks5InitialResponseDecoder();
        p.addBefore(name, null, decoder);
        decoderName = p.context(decoder).name();
        encoderName = decoderName + ".encoder";
        p.addBefore(name, encoderName, Socks5ClientEncoder.DEFAULT);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        state = State.AUTHING;
        scheduleAuthTimeout(ctx);
        ctx.writeAndFlush(newInitialMessage());
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        fail(new ProxyConnectException(exceptionMessage("channel inactive")));
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        fail(cause);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean handled = false;
        try {
            if (msg instanceof Socks5InitialResponse) {
                handled = true;
                handleInitial(ctx, (Socks5InitialResponse) msg);
                return;
            }
            if (msg instanceof Socks5PasswordAuthResponse) {
                handled = true;
                handlePassword(ctx, (Socks5PasswordAuthResponse) msg);
                return;
            }
            if (msg instanceof Socks5CommandResponse) {
                handled = true;
                handleCommand(ctx, (Socks5CommandResponse) msg);
                return;
            }
        } finally {
            if (handled) {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }
        ctx.fireChannelRead(msg);
    }

    public ChannelFuture connect(UnresolvedEndpoint destination) {
        if (ctx == null) {
            throw new IllegalStateException("handler not initialized");
        }
        if (state != State.READY) {
            throw new IllegalStateException("warm channel not ready: " + state);
        }
        state = State.CONNECTING;
        connectPromise = ctx.newPromise();
        scheduleCommandTimeout();
        ctx.writeAndFlush(new DefaultSocks5CommandRequest(
                Socks5CommandType.CONNECT, Socks5AddressType.DOMAIN, destination.getHost(), destination.getPort()));
        return connectPromise;
    }

    private Object newInitialMessage() {
        return socksAuthMethod() == Socks5AuthMethod.PASSWORD ? INIT_REQUEST_PASSWORD : INIT_REQUEST_NO_AUTH;
    }

    private void handleInitial(ChannelHandlerContext ctx, Socks5InitialResponse res) {
        Socks5AuthMethod authMethod = socksAuthMethod();
        Socks5AuthMethod resAuthMethod = res.authMethod();
        if (resAuthMethod != Socks5AuthMethod.NO_AUTH && resAuthMethod != authMethod) {
            fail(new ProxyConnectException(exceptionMessage("unexpected authMethod: " + resAuthMethod)));
            return;
        }
        if (resAuthMethod == Socks5AuthMethod.NO_AUTH) {
            markReady(ctx);
            return;
        }
        ctx.pipeline().replace(decoderName, decoderName, new Socks5PasswordAuthResponseDecoder());
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest(
                username != null ? username : Strings.EMPTY, password != null ? password : Strings.EMPTY));
    }

    private void handlePassword(ChannelHandlerContext ctx, Socks5PasswordAuthResponse res) {
        if (res.status() != Socks5PasswordAuthStatus.SUCCESS) {
            fail(new ProxyConnectException(exceptionMessage("authStatus: " + res.status())));
            return;
        }
        markReady(ctx);
    }

    private void markReady(ChannelHandlerContext ctx) {
        cancel(authTimeout);
        ctx.pipeline().replace(decoderName, decoderName, new Socks5CommandResponseDecoder());
        state = State.READY;
        readyAtMillis = System.currentTimeMillis();
        readyFuture.complete(ctx.channel());
    }

    private void handleCommand(ChannelHandlerContext ctx, Socks5CommandResponse res) throws Exception {
        cancel(commandTimeout);
        if (res.status() != Socks5CommandStatus.SUCCESS) {
            fail(new ProxyConnectException(exceptionMessage("status: " + res.status())));
            return;
        }
        state = State.CONSUMED;
        if (connectedCallback != null) {
            try {
                connectedCallback.invoke();
            } catch (Throwable e) {
                fail(e);
                return;
            }
        }
        removeCodec(ctx);
        ctx.pipeline().remove(this);
        if (connectPromise != null) {
            connectPromise.trySuccess();
        }
    }

    private void removeCodec(ChannelHandlerContext ctx) {
        ChannelPipeline p = ctx.pipeline();
        if (encoderName != null && p.context(encoderName) != null) {
            p.remove(encoderName);
        }
        if (decoderName != null && p.context(decoderName) != null) {
            p.remove(decoderName);
        }
    }

    private void scheduleAuthTimeout(ChannelHandlerContext ctx) {
        if (connectTimeoutMillis <= 0) {
            return;
        }
        authTimeout = ctx.executor().schedule(() -> fail(new ProxyConnectException(exceptionMessage("auth timeout"))),
                connectTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleCommandTimeout() {
        if (connectTimeoutMillis <= 0) {
            return;
        }
        commandTimeout = ctx.executor().schedule(() -> fail(new ProxyConnectException(exceptionMessage("command timeout"))),
                connectTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void fail(Throwable cause) {
        if (state == State.FAILED || state == State.CONSUMED) {
            return;
        }
        state = State.FAILED;
        cancel(authTimeout);
        cancel(commandTimeout);
        readyFuture.completeExceptionally(cause);
        if (connectPromise != null) {
            connectPromise.tryFailure(cause);
        }
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    private String exceptionMessage(String detail) {
        return "socks5 warmup -> " + proxyAddress + ", " + detail;
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private Socks5AuthMethod socksAuthMethod() {
        return username == null && password == null ? Socks5AuthMethod.NO_AUTH : Socks5AuthMethod.PASSWORD;
    }
}
