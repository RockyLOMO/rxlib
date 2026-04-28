package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.net.Sockets;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DoHClient implements DnsServer.ResolveInterceptor, AutoCloseable {
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    @Getter
    final List<DoHEndpoint> endpoints;
    final SslContext clientSslContext;
    final EventLoopGroup eventLoopGroup;
    final List<EndpointState> endpointStates;
    final int maxInFlight;
    final AtomicInteger nextEndpoint = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger queryId = new AtomicInteger(1);
    public final AtomicLong requestCount = new AtomicLong();
    public final AtomicLong successCount = new AtomicLong();
    public final AtomicLong failureCount = new AtomicLong();
    public final AtomicLong timeoutCount = new AtomicLong();
    public final AtomicLong rejectedCount = new AtomicLong();
    volatile boolean closed;

    public DoHClient(@NonNull Collection<DoHEndpoint> endpoints) {
        this(endpoints, null, DEFAULT_MAX_IN_FLIGHT);
    }

    @SneakyThrows
    public DoHClient(@NonNull Collection<DoHEndpoint> endpoints, SslContext clientSslContext, int maxInFlight) {
        if (CollectionUtils.isEmpty(endpoints)) {
            throw new IllegalArgumentException("Empty DoH endpoints");
        }
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        boolean hasTls = false;
        for (DoHEndpoint endpoint : this.endpoints) {
            if (endpoint.isTls()) {
                hasTls = true;
                break;
            }
        }
        this.clientSslContext = clientSslContext != null || !hasTls ? clientSslContext : SslContextBuilder.forClient().build();
        eventLoopGroup = Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true);
        this.maxInFlight = maxInFlight > 0 ? maxInFlight : DEFAULT_MAX_IN_FLIGHT;
        int channelsPerEndpoint = Math.max(1, (this.maxInFlight + this.endpoints.size() - 1) / this.endpoints.size());
        ArrayList<EndpointState> states = new ArrayList<>(this.endpoints.size());
        for (DoHEndpoint endpoint : this.endpoints) {
            states.add(new EndpointState(endpoint, channelsPerEndpoint));
        }
        endpointStates = Collections.unmodifiableList(states);
    }

    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        ensureBlockingCallAllowed();
        if (closed) {
            rejectedCount.incrementAndGet();
            return null;
        }
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            rejectedCount.incrementAndGet();
            return null;
        }
        try {
            List<InetAddress> a = query(host, DnsRecordType.A);
            List<InetAddress> aaaa = query(host, DnsRecordType.AAAA);
            if (a == null || aaaa == null) {
                return null;
            }
            ArrayList<InetAddress> result = new ArrayList<>((a == null ? 0 : a.size()) + (aaaa == null ? 0 : aaaa.size()));
            if (a != null) {
                result.addAll(a);
            }
            if (aaaa != null) {
                result.addAll(aaaa);
            }
            return result;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @SneakyThrows
    private List<InetAddress> query(String host, DnsRecordType type) {
        requestCount.incrementAndGet();
        EndpointState state = endpointStates.get(Math.floorMod(nextEndpoint.getAndIncrement(), endpointStates.size()));
        EndpointState.EndpointChannel slot = state.nextChannel();
        synchronized (slot.lock) {
            DoHEndpoint endpoint = state.endpoint;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<InetAddress>> result = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            int id = queryId.getAndIncrement() & 0xffff;
            Channel channel = null;
            ClientHandler handler = null;
            ScheduledFuture<?> timeout = null;
            try {
                channel = slot.channel();
                handler = new ClientHandler(id, result, error, latch, slot);
                channel.pipeline().addLast(handler);
                Channel queryChannel = channel;
                ClientHandler queryHandler = handler;
                timeout = channel.eventLoop().schedule(() -> {
                    if (markTimeout(queryHandler)) {
                        slot.close(queryChannel);
                    }
                }, endpoint.getTimeoutMillis(), TimeUnit.MILLISECONDS);

                ByteBuf body = channel.alloc().buffer(64 + host.length());
                DoHMessageCodec.encodeQuery(body, id, host, type);
                FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, endpoint.getPath(), body);
                request.headers().set(HttpHeaderNames.HOST, endpoint.isTls() ? endpoint.getTlsHost() : endpoint.getAddress().getHostString());
                request.headers().set(HttpHeaderNames.CONTENT_TYPE, DoHServerHandler.CONTENT_TYPE_DNS);
                request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                channel.writeAndFlush(request).addListener(f -> {
                    if (!f.isSuccess()) {
                        queryHandler.fail(f.cause());
                        slot.close(queryChannel);
                    }
                });

                if (!latch.await(endpoint.getTimeoutMillis() + 100L, TimeUnit.MILLISECONDS)) {
                    if (markTimeout(handler)) {
                        slot.close(channel);
                    }
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
                if (channel != null) {
                    slot.close(channel);
                }
            } finally {
                if (timeout != null) {
                    timeout.cancel(false);
                }
                if (channel != null && handler != null) {
                    removeHandler(channel, handler);
                }
            }
            if (error.get() != null) {
                failureCount.incrementAndGet();
                log.debug("DoH query {} {} fail", host, type, error.get());
                return null;
            }
            successCount.incrementAndGet();
            return result.get();
        }
    }

    @Override
    public void close() {
        closed = true;
        for (EndpointState state : endpointStates) {
            state.close();
        }
    }

    private boolean markTimeout(ClientHandler handler) {
        if (!handler.fail(new TimeoutException("DoH query timeout"))) {
            return false;
        }
        timeoutCount.incrementAndGet();
        return true;
    }

    private void removeHandler(Channel channel, ChannelHandler handler) {
        if (!channel.isOpen()) {
            return;
        }
        Runnable removeTask = () -> {
            try {
                if (channel.pipeline().context(handler) != null) {
                    channel.pipeline().remove(handler);
                }
            } catch (Throwable ignored) {
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            removeTask.run();
        } else {
            channel.eventLoop().submit(removeTask).syncUninterruptibly();
        }
    }

    private void ensureBlockingCallAllowed() {
        Thread current = Thread.currentThread();
        for (EventExecutor executor : eventLoopGroup) {
            if (executor.inEventLoop(current)) {
                throw new IllegalStateException("DoHClient sync resolve cannot be called from Netty EventLoop");
            }
        }
    }

    final class EndpointState {
        final DoHEndpoint endpoint;
        final Bootstrap bootstrap;
        final EndpointChannel[] channels;
        final AtomicInteger nextChannel = new AtomicInteger();

        EndpointState(DoHEndpoint endpoint, int channelCount) {
            this.endpoint = endpoint;
            bootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(Sockets.tcpChannelClass())
                    .resolver(NoopAddressResolverGroup.INSTANCE)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, endpoint.getTimeoutMillis())
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (EndpointState.this.endpoint.isTls()) {
                                p.addLast(clientSslContext.newHandler(ch.alloc(),
                                        EndpointState.this.endpoint.getTlsHost(),
                                        EndpointState.this.endpoint.getAddress().getPort()));
                            }
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(DnsDoHConfig.DEFAULT_MAX_DNS_MESSAGE_BYTES));
                        }
                    });
            channels = new EndpointChannel[channelCount];
            for (int i = 0; i < channelCount; i++) {
                channels[i] = new EndpointChannel();
            }
        }

        EndpointChannel nextChannel() {
            return channels[Math.floorMod(nextChannel.getAndIncrement(), channels.length)];
        }

        void close() {
            for (EndpointChannel channel : channels) {
                channel.close();
            }
        }

        final class EndpointChannel {
            final Object lock = new Object();
            volatile Channel channel;

            Channel channel() {
                Channel ch = channel;
                if (ch != null && ch.isActive()) {
                    return ch;
                }
                close(ch);
                ChannelFuture future = bootstrap.connect(endpoint.getAddress()).syncUninterruptibly();
                channel = future.channel();
                return channel;
            }

            void close() {
                close(channel);
            }

            void close(Channel ch) {
                if (ch == null) {
                    return;
                }
                if (channel == ch) {
                    channel = null;
                }
                ch.close();
            }
        }
    }

    class ClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        final int id;
        final AtomicReference<List<InetAddress>> result;
        final AtomicReference<Throwable> error;
        final CountDownLatch latch;
        final EndpointState.EndpointChannel channelSlot;
        final AtomicBoolean completed = new AtomicBoolean();

        ClientHandler(int id, AtomicReference<List<InetAddress>> result, AtomicReference<Throwable> error,
                      CountDownLatch latch, EndpointState.EndpointChannel channelSlot) {
            this.id = id;
            this.result = result;
            this.error = error;
            this.latch = latch;
            this.channelSlot = channelSlot;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            try {
                if (response.status().code() != HttpResponseStatus.OK.code()) {
                    error.compareAndSet(null, new IllegalStateException("DoH HTTP status " + response.status()));
                    return;
                }
                result.set(DoHMessageCodec.decodeAddresses(response.content().duplicate(), id));
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            } finally {
                complete();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (fail(new IOException("DoH channel inactive"))) {
                channelSlot.close(ctx.channel());
            }
        }

        boolean fail(Throwable cause) {
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            error.compareAndSet(null, cause);
            latch.countDown();
            return true;
        }

        void complete() {
            if (completed.compareAndSet(false, true)) {
                latch.countDown();
            }
        }
    }
}
