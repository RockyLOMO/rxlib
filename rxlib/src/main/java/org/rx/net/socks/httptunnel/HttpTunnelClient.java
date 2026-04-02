package org.rx.net.socks.httptunnel;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.*;

/**
 * HTTP 隧道客户端
 * <p>
 * 本地监听 SOCKS5 端口，完成 SOCKS5 握手后，
 * 将 TCP/UDP 数据封装为 HTTP POST 请求发送到远程 HttpTunnelServer。
 * </p>
 * <pre>
 * 使用示例:
 * HttpTunnelConfig config = new HttpTunnelConfig();
 * config.setListenPort(1080);
 * config.setTunnelUrl("http://remote-server:8080/tunnel");
 * HttpTunnelClient client = new HttpTunnelClient(config);
 * // 然后配置浏览器或应用使用 SOCKS5 代理 127.0.0.1:1080
 * </pre>
 */
@Slf4j
public class HttpTunnelClient extends Disposable {

    static class TunnelConnection {
        final int connId;
        final UnresolvedEndpoint destination;
        volatile Channel inbound;
        volatile boolean active;
        volatile java.util.concurrent.Future<?> pollFuture;

        TunnelConnection(int connId, UnresolvedEndpoint destination) {
            this.connId = connId;
            this.destination = destination;
        }
    }

    @Getter
    final HttpTunnelConfig config;
    final ServerBootstrap serverBootstrap;
    final Channel serverChannel;
    final OkHttpClient httpClient;
    final java.util.concurrent.ExecutorService clientExecutor = java.util.concurrent.Executors.newCachedThreadPool();
    final ConcurrentHashMap<Integer, TunnelConnection> connections = new ConcurrentHashMap<>();
    final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    public HttpTunnelClient(@NonNull HttpTunnelConfig config) {
        this.config = config;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(config.getPollTimeoutSeconds() + 5, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        // 自建 SOCKS5 服务端，不使用 SocksProxyServer (避免其 connect 到真实远程目标)
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(Socks5ServerEncoder.DEFAULT)
                    .addLast(new Socks5InitialRequestDecoder())
                    .addLast(new Socks5InitialHandler())
                    .addLast(new Socks5CommandRequestDecoder())
                    .addLast(new Socks5CommandHandler());
        });
        serverChannel = serverBootstrap.bind(Sockets.newAnyEndpoint(config.getListenPort())).channel();

        log.info("HttpTunnel client started, SOCKS5 port={}, tunnel={}", config.getListenPort(), config.getTunnelUrl());
    }

    @Override
    protected void dispose() {
        Sockets.closeOnFlushed(serverChannel);
        Sockets.closeBootstrap(serverBootstrap);
        for (TunnelConnection conn : connections.values()) {
            conn.active = false;
            if (conn.pollFuture != null) {
                conn.pollFuture.cancel(true);
            }
        }
        connections.clear();
        clientExecutor.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ---- SOCKS5 握手 ----

    /**
     * 处理 SOCKS5 初始握手 (无认证)
     */
    class Socks5InitialHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) {
            ctx.pipeline().remove(this);
            if (msg.decoderResult().isFailure()) {
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        }
    }

    /**
     * 处理 SOCKS5 CONNECT/UDP_ASSOCIATE 命令
     */
    class Socks5CommandHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5CommandRequest msg) {
            Channel inbound = ctx.channel();
            ctx.pipeline().remove(Socks5CommandRequestDecoder.class);
            ctx.pipeline().remove(this);

            UnresolvedEndpoint dstEp = new UnresolvedEndpoint(msg.dstAddr(), msg.dstPort());

            if (msg.type() == Socks5CommandType.CONNECT) {
                handleConnect(ctx, inbound, msg.dstAddrType(), dstEp);
            } else if (msg.type() == Socks5CommandType.UDP_ASSOCIATE) {
                handleUdpAssociate(ctx, inbound, msg.dstAddrType(), dstEp);
            } else {
                ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType()))
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /**
     * TCP CONNECT: 通过 HTTP 隧道建连
     */
    private void handleConnect(ChannelHandlerContext ctx, Channel inbound,
                               Socks5AddressType dstAddrType, UnresolvedEndpoint dstEp) {
        int connId = HttpTunnelProtocol.nextConnId();
        log.info("HttpTunnel CONNECT connId={} dst={}", connId, dstEp);

        // 异步通过 HTTP 发送 CONNECT 请求
        clientExecutor.execute(() -> {
            ByteBuf connectBuf = HttpTunnelProtocol.encodeConnect(connId, dstEp);
            byte[] connectData = toBytes(connectBuf);
            connectBuf.release();

            byte[] response = postTunnel(connectData);
            if (response == null || response.length < 4) {
                log.error("HttpTunnel CONNECT connId={} dst={} failed", connId, dstEp);
                inbound.eventLoop().execute(() ->
                        inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType))
                                .addListener(ChannelFutureListener.CLOSE));
                return;
            }

            // 连接成功
            TunnelConnection conn = new TunnelConnection(connId, dstEp);
            conn.active = true;
            conn.inbound = inbound;
            connections.put(connId, conn);

            // 回复 SOCKS5 客户端连接成功
            inbound.eventLoop().execute(() -> {
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, dstAddrType))
                        .addListener((ChannelFutureListener) f -> {
                            if (!f.isSuccess()) {
                                Sockets.closeOnFlushed(inbound);
                                return;
                            }
                            // 切换到数据转发模式
                            inbound.pipeline().addLast(new TcpRelayHandler(connId));
                            // 启动长轮询拉取反向数据
                            startPollLoop(conn);
                            log.info("HttpTunnel CONNECT connId={} dst={} ready", connId, dstEp);
                        });
            });
        });
    }

    /**
     * UDP ASSOCIATE: 返回绑定地址
     */
    private void handleUdpAssociate(ChannelHandlerContext ctx, Channel inbound,
                                    Socks5AddressType dstAddrType, UnresolvedEndpoint dstEp) {
        InetSocketAddress bindEp = Sockets.getLocalAddress(inbound);
        // 简单实现: 返回绑定地址，UDP 数据通过额外协议处理
        ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                Socks5CommandStatus.SUCCESS, dstAddrType, bindEp.getHostString(), bindEp.getPort()));
        log.info("HttpTunnel UDP_ASSOCIATE from {} dst={}", inbound.remoteAddress(), dstEp);
    }

    // ---- 数据转发 ----

    /**
     * SOCKS5 握手完成后，处理 TCP 数据: 读到数据就通过 HTTP 发送到 server
     */
    class TcpRelayHandler extends ChannelInboundHandlerAdapter {
        final int connId;

        TcpRelayHandler(int connId) {
            this.connId = connId;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);

                // 异步通过 HTTP 发送数据
                ByteBuf fwdBuf = HttpTunnelProtocol.encodeForward(connId, data);
                byte[] fwdData = toBytes(fwdBuf);
                fwdBuf.release();
                clientExecutor.execute(() -> postTunnel(fwdData));

                log.debug("HttpTunnel FORWARD connId={} {}bytes", connId, data.length);
            } finally {
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("HttpTunnel inbound connId={} inactive", connId);
            TunnelConnection conn = connections.remove(connId);
            if (conn != null) {
                conn.active = false;
                ByteBuf closeBuf = HttpTunnelProtocol.encodeClose(connId);
                byte[] closeData = toBytes(closeBuf);
                closeBuf.release();
                clientExecutor.execute(() -> postTunnel(closeData));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("HttpTunnel relay connId={} error", connId, cause);
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    /**
     * 长轮询: 定期从 server 拉取目标返回的数据，写回 SOCKS5 客户端
     */
    void startPollLoop(TunnelConnection conn) {
        conn.pollFuture = clientExecutor.submit(() -> {
            while (conn.active && !Thread.currentThread().isInterrupted()) {
                try {
                    ByteBuf pollBuf = Bytes.heapBuffer();
                    pollBuf.writeInt(conn.connId);
                    byte[] pollData = toBytes(pollBuf);
                    pollBuf.release();

                    byte[] response = postPoll(pollData);
                    if (response != null && response.length > 0
                            && conn.inbound != null && conn.inbound.isActive()) {
                        ByteBuf resBuf = Unpooled.wrappedBuffer(response);
                        while (resBuf.readableBytes() > 4) {
                            int dataLen = resBuf.readInt();
                            if (resBuf.readableBytes() < dataLen) {
                                break;
                            }
                            byte[] chunkData = new byte[dataLen];
                            resBuf.readBytes(chunkData);
                            conn.inbound.writeAndFlush(Unpooled.wrappedBuffer(chunkData));
                            log.debug("HttpTunnel poll connId={} received {}bytes", conn.connId, dataLen);
                        }
                        resBuf.release();
                    }

                    if (response == null || response.length == 0) {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("HttpTunnel poll connId={} error: {}", conn.connId, e.getMessage());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    // ---- HTTP 通信 ----

    byte[] postTunnel(byte[] data) {
        return doPost(config.getTunnelUrl(), data);
    }

    byte[] postPoll(byte[] data) {
        String pollUrl = config.getTunnelUrl();
        if (!pollUrl.endsWith("/")) {
            pollUrl += "/poll";
        } else {
            pollUrl += "poll";
        }
        return doPost(pollUrl, data);
    }

    byte[] doPost(String url, byte[] data) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(OCTET_STREAM, data));
            if (config.getToken() != null) {
                builder.addHeader("X-Tunnel-Token", config.getToken());
            }
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                ResponseBody body = response.body();
                if (body != null) {
                    return body.bytes();
                }
            }
        } catch (Exception e) {
            log.debug("HttpTunnel POST {} error: {}", url, e.getMessage());
        }
        return null;
    }

    static byte[] toBytes(ByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
