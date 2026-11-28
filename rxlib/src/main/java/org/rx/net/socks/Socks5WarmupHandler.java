package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.Getter;
import lombok.Setter;
import org.rx.core.Strings;
import org.rx.util.function.Action;
import java.net.InetSocketAddress;

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
    private static final Socks5InitialRequest INIT_REQUEST_RSS_LIGHTWEIGHT =
            new DefaultSocks5InitialRequest(Arrays.asList(
                    Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD,
                    org.rx.net.transport.core.TransportCoreCarrier.METHOD_RSS_LIGHTWEIGHT));

    private final SocketAddress proxyAddress;
    private final String username;
    private final String password;
    private final int connectTimeoutMillis;
    private final CompletableFuture<Channel> readyFuture = new CompletableFuture<>();
    @Getter @Setter private boolean adaptiveTcp;
    @Getter @Setter private int adaptiveProfile;
    @Getter @Setter private boolean udpHop = true;
    @Getter @Setter private InetSocketAddress adaptiveUdpAddress;
    @Getter @Setter private int adaptiveUdpMtu;
    @Getter @Setter private org.rx.net.support.UpstreamSupport upstreamSupport;
    private volatile State state = State.INIT;
    @Getter
    private volatile long readyAtMillis;
    private volatile String decoderName;
    private volatile String encoderName;
    private boolean lwOffered;
    private boolean lwAuthPassed;
    private org.rx.net.transport.core.TransportCoreCarrier.LightweightClientState lwState;
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

    public String username() {
        return username;
    }

    public String password() {
        return password;
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
            if (msg instanceof org.rx.net.transport.core.TransportCoreCarrier.LightweightAck) {
                handled = true;
                handleLightweightAck(ctx, (org.rx.net.transport.core.TransportCoreCarrier.LightweightAck) msg);
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

    public ChannelFuture connect(InetSocketAddress destination) {
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
                Socks5CommandType.CONNECT, UdpManager.addressType(destination.getHostString()), destination.getHostString(), destination.getPort()));
        return connectPromise;
    }

    private Object newInitialMessage() {
        boolean hasPassword = socksAuthMethod() == Socks5AuthMethod.PASSWORD;
        lwOffered = org.rx.net.transport.core.TransportCoreCarrier.shouldOfferLightweight(
                channel, adaptiveTcp, hasPassword);
        if (lwOffered) {
            return INIT_REQUEST_RSS_LIGHTWEIGHT;
        }
        return hasPassword ? INIT_REQUEST_PASSWORD : INIT_REQUEST_NO_AUTH;
    }

    private void handleInitial(ChannelHandlerContext ctx, Socks5InitialResponse res) {
        Socks5AuthMethod authMethod = socksAuthMethod();
        Socks5AuthMethod resAuthMethod = res.authMethod();
        boolean lwSelected = lwOffered && org.rx.net.transport.core.TransportCoreCarrier.METHOD_RSS_LIGHTWEIGHT.equals(resAuthMethod);
        if (!lwSelected && resAuthMethod != Socks5AuthMethod.NO_AUTH && resAuthMethod != authMethod) {
            fail(new ProxyConnectException(exceptionMessage("unexpected authMethod: " + resAuthMethod)));
            return;
        }
        if (lwSelected) {
            try {
                lwState = org.rx.net.transport.core.TransportCoreCarrier.beginLightweightClient(
                        ctx.channel(), password, adaptiveTcp, adaptiveProfile, udpHop, adaptiveUdpAddress, adaptiveUdpMtu);
                ctx.pipeline().replace(decoderName, decoderName, new Socks5PasswordAuthResponseDecoder());
                ctx.pipeline().addAfter(decoderName, org.rx.net.transport.core.TransportCoreCarrier.LIGHTWEIGHT_ACK_DECODER,
                        new org.rx.net.transport.core.TransportCoreCarrier.LightweightAckDecoder());
                ctx.write(new DefaultSocks5PasswordAuthRequest(
                        username != null ? username : Strings.EMPTY, password != null ? password : Strings.EMPTY));
                ctx.writeAndFlush(org.rx.net.transport.core.TransportCoreCarrier.encodeLightweightClientExt(ctx.alloc(), lwState));
            } catch (Throwable e) {
                fail(e);
            }
            return;
        }
        if (resAuthMethod == Socks5AuthMethod.NO_AUTH) {
            if (lwOffered && adaptiveTcp) {
                fail(new ProxyConnectException(exceptionMessage("unauthenticated adaptive TCP fallback rejected")));
                return;
            }
            markReady(ctx);
            return;
        }
        if (lwOffered && adaptiveTcp) {
            org.rx.diagnostic.DiagnosticMetrics.record("rss.tcp.adaptive.negotiationFallback", 1,
                    adaptiveProfile == 1 ? "profile=game" : adaptiveProfile == 2 ? "profile=weak" : "profile=daily");
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
        if (lwState != null) {
            lwAuthPassed = true;
            return;
        }
        markReady(ctx);
    }

    private void handleLightweightAck(ChannelHandlerContext ctx, org.rx.net.transport.core.TransportCoreCarrier.LightweightAck ack) {
        if (lwState == null || !lwAuthPassed) {
            fail(new ProxyConnectException(exceptionMessage("unexpected RSS lightweight ack")));
            return;
        }
        org.rx.net.transport.core.TransportCoreCarrier.LightweightClientState st = lwState;
        lwState = null;
        try {
            org.rx.net.transport.core.TransportCoreCarrier.completeLightweightClient(ctx, ack, st, upstreamSupport);
            markReady(ctx);
        } catch (Throwable e) {
            fail(e);
        }
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
