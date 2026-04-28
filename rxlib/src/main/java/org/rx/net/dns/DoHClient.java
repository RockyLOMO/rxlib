package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DoHClient implements DnsServer.ResolveInterceptor, AutoCloseable {
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    @Getter
    final List<DoHEndpoint> endpoints;
    final SslContext clientSslContext;
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

    public DoHClient(@NonNull Collection<DoHEndpoint> endpoints, SslContext clientSslContext, int maxInFlight) {
        if (CollectionUtils.isEmpty(endpoints)) {
            throw new IllegalArgumentException("Empty DoH endpoints");
        }
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        this.clientSslContext = clientSslContext;
        this.maxInFlight = maxInFlight > 0 ? maxInFlight : DEFAULT_MAX_IN_FLIGHT;
    }

    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        if (closed || inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            rejectedCount.incrementAndGet();
            return null;
        }
        try {
            List<InetAddress> a = query(host, DnsRecordType.A);
            List<InetAddress> aaaa = query(host, DnsRecordType.AAAA);
            if (a == null && aaaa == null) {
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
        DoHEndpoint endpoint = endpoints.get(Math.abs(nextEndpoint.getAndIncrement() % endpoints.size()));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<InetAddress>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        int id = queryId.getAndIncrement() & 0xffff;

        Bootstrap bootstrap = new Bootstrap()
                .group(Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true))
                .channel(Sockets.tcpChannelClass())
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, endpoint.getTimeoutMillis())
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        if (endpoint.isTls()) {
                            SslContext sslContext = clientSslContext != null ? clientSslContext : SslContextBuilder.forClient().build();
                            p.addLast(sslContext.newHandler(ch.alloc(), endpoint.getTlsHost(), endpoint.getAddress().getPort()));
                        }
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(DnsDoHConfig.DEFAULT_MAX_DNS_MESSAGE_BYTES));
                        p.addLast(new ClientHandler(id, result, error, latch));
                    }
                });

        Channel channel = bootstrap.connect(endpoint.getAddress()).syncUninterruptibly().channel();
        ScheduledFuture<?> timeout = channel.eventLoop().schedule(() -> {
            if (latch.getCount() == 0) {
                return;
            }
            timeoutCount.incrementAndGet();
            error.compareAndSet(null, new java.util.concurrent.TimeoutException("DoH query timeout"));
            latch.countDown();
            channel.close();
        }, endpoint.getTimeoutMillis(), TimeUnit.MILLISECONDS);

        ByteBuf body = channel.alloc().buffer(64 + host.length());
        DoHMessageCodec.encodeQuery(body, id, host, type);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, endpoint.getPath(), body);
        request.headers().set(HttpHeaderNames.HOST, endpoint.isTls() ? endpoint.getTlsHost() : endpoint.getAddress().getHostString());
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, DoHServerHandler.CONTENT_TYPE_DNS);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        channel.writeAndFlush(request).addListener(f -> {
            if (!f.isSuccess()) {
                error.compareAndSet(null, f.cause());
                latch.countDown();
                channel.close();
            }
        });

        latch.await(endpoint.getTimeoutMillis() + 100L, TimeUnit.MILLISECONDS);
        timeout.cancel(false);
        channel.close().syncUninterruptibly();
        if (error.get() != null) {
            failureCount.incrementAndGet();
            log.debug("DoH query {} {} fail", host, type, error.get());
            return null;
        }
        successCount.incrementAndGet();
        return result.get();
    }

    @Override
    public void close() {
        closed = true;
    }

    static class ClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        final int id;
        final AtomicReference<List<InetAddress>> result;
        final AtomicReference<Throwable> error;
        final CountDownLatch latch;

        ClientHandler(int id, AtomicReference<List<InetAddress>> result, AtomicReference<Throwable> error, CountDownLatch latch) {
            this.id = id;
            this.result = result;
            this.error = error;
            this.latch = latch;
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
                latch.countDown();
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error.compareAndSet(null, cause);
            latch.countDown();
            ctx.close();
        }
    }
}
