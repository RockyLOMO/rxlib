package org.rx.net.socks.httptunnel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.http.HttpServer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import static org.rx.core.Extends.eq;

/**
 * HTTP 隧道服务端
 * <p>
 * 监听 HTTP URL，接收 HttpTunnelClient 发来的请求，
 * 将 TCP/UDP 数据转发到真实目标 IP:Port。
 * </p>
 * <pre>
 * 使用示例:
 * HttpTunnelConfig config = new HttpTunnelConfig();
 * config.setHttpPort(8080);
 * config.setTunnelPath("/tunnel");
 * HttpTunnelServer server = new HttpTunnelServer(config);
 * // 远程客户端配置 tunnelUrl = "http://this-server-ip:8080/tunnel"
 * </pre>
 */
@Slf4j
public class HttpTunnelServer extends Disposable {

    /**
     * 一个 TCP 连接上下文
     */
    static class TcpConnection {
        final int connId;
        final UnresolvedEndpoint destination;
        volatile Channel outbound;
        volatile ChannelFuture connectFuture;
        /**
         * 前向待发数据 (outbound 未建立完时暂存)
         */
        final Queue<byte[]> pendingForward = new ConcurrentLinkedQueue<>();
        /**
         * 反向数据 (目标服务器返回的，等待 poll 拉取)
         */
        final Queue<byte[]> responseQueue = new ConcurrentLinkedQueue<>();

        TcpConnection(int connId, UnresolvedEndpoint destination) {
            this.connId = connId;
            this.destination = destination;
        }
    }

    final HttpTunnelConfig config;
    final HttpServer httpServer;
    final ConcurrentMap<Integer, TcpConnection> tcpConnections = new ConcurrentHashMap<>();
    /**
     * UDP 反向数据队列: connId -> Queue
     */
    final ConcurrentMap<Integer, Queue<byte[]>> udpResponses = new ConcurrentHashMap<>();

    public HttpTunnelServer(@NonNull HttpTunnelConfig config) {
        this.config = config;
        this.httpServer = new HttpServer(config.getHttpPort(), false);

        String basePath = HttpServer.normalize(config.getTunnelPath());

        // 主数据通道: POST /tunnel
        httpServer.requestMapping(basePath, (req, res) -> {
            if (!checkToken(req)) {
                log.warn("HttpTunnel server auth fail from {}", req.getRemoteEndpoint());
                ByteBuf errBuf = Bytes.directBuffer();
                errBuf.writeBytes("Forbidden".getBytes());
                res.setContent(errBuf);
                return;
            }

            ByteBuf content = req.getContent();
            if (content == null || content.readableBytes() < 5) {
                log.warn("HttpTunnel server invalid request from {}", req.getRemoteEndpoint());
                return;
            }

            byte action = content.readByte();
            int connId = content.readInt();

            switch (action) {
                case HttpTunnelProtocol.ACTION_CONNECT:
                    handleConnect(connId, content, res);
                    break;
                case HttpTunnelProtocol.ACTION_FORWARD:
                    handleForward(connId, content, res);
                    break;
                case HttpTunnelProtocol.ACTION_CLOSE:
                    handleClose(connId, res);
                    break;
                case HttpTunnelProtocol.ACTION_UDP_FORWARD:
                    handleUdpForward(connId, content, res);
                    break;
                default:
                    log.warn("HttpTunnel server unknown action {}", action);
                    returnOk(res);
            }
        });

        // 长轮询通道: POST /tunnel/poll
        httpServer.requestMapping(basePath + "/poll", (req, res) -> {
            if (!checkToken(req)) {
                return;
            }

            ByteBuf content = req.getContent();
            if (content == null || content.readableBytes() < 4) {
                return;
            }

            int connId = content.readInt();
            handlePoll(connId, res);
        });

        log.info("HttpTunnel server started on port {} path {}", config.getHttpPort(), basePath);
    }

    @Override
    protected void dispose() {
        httpServer.close();
        for (TcpConnection conn : tcpConnections.values()) {
            if (conn.outbound != null) {
                Sockets.closeOnFlushed(conn.outbound);
            }
        }
        tcpConnections.clear();
        udpResponses.clear();
    }

    private boolean checkToken(org.rx.net.http.ServerRequest req) {
        if (config.getToken() == null) {
            return true;
        }
        String reqToken = req.getHeaders().get("X-Tunnel-Token");
        return eq(config.getToken(), reqToken);
    }

    // ---- TCP CONNECT ----

    private void handleConnect(int connId, ByteBuf content, org.rx.net.http.ServerResponse res) {
        UnresolvedEndpoint dst = HttpTunnelProtocol.decodeAddress(content);
        log.info("HttpTunnel CONNECT connId={} dst={}", connId, dst);

        TcpConnection conn = new TcpConnection(connId, dst);
        tcpConnections.put(connId, conn);

        // 建立到真实目标的 TCP 连接
        conn.connectFuture = Sockets.bootstrap(null, outbound -> {
            outbound.pipeline().addLast(new TcpBackendHandler(connId));
        }).connect(dst.socketAddress());

        conn.connectFuture.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                conn.outbound = f.channel();
                // 发送 pending data
                byte[] pending;
                while ((pending = conn.pendingForward.poll()) != null) {
                    conn.outbound.writeAndFlush(Unpooled.wrappedBuffer(pending));
                }
                log.info("HttpTunnel CONNECT connId={} dst={} success", connId, dst);
            } else {
                log.error("HttpTunnel CONNECT connId={} dst={} fail", connId, dst, f.cause());
                tcpConnections.remove(connId);
            }
        });

        // 返回 connId 确认
        ByteBuf resBuf = Bytes.directBuffer();
        resBuf.writeInt(connId);
        res.setContent(resBuf);
    }

    // ---- TCP FORWARD ----

    private void handleForward(int connId, ByteBuf content, org.rx.net.http.ServerResponse res) {
        TcpConnection conn = tcpConnections.get(connId);
        if (conn == null) {
            log.warn("HttpTunnel FORWARD connId={} not found", connId);
            returnOk(res);
            return;
        }

        byte[] data = new byte[content.readableBytes()];
        content.readBytes(data);

        if (conn.outbound != null && conn.outbound.isActive()) {
            conn.outbound.writeAndFlush(Unpooled.wrappedBuffer(data));
        } else {
            conn.pendingForward.offer(data);
        }
        returnOk(res);
    }

    // ---- TCP CLOSE ----

    private void handleClose(int connId, org.rx.net.http.ServerResponse res) {
        log.debug("HttpTunnel CLOSE connId={}", connId);
        TcpConnection conn = tcpConnections.remove(connId);
        if (conn != null && conn.outbound != null) {
            Sockets.closeOnFlushed(conn.outbound);
        }
        returnOk(res);
    }

    // ---- UDP FORWARD ----

    private void handleUdpForward(int connId, ByteBuf content, org.rx.net.http.ServerResponse res) {
        UnresolvedEndpoint dst = HttpTunnelProtocol.decodeAddress(content);
        byte[] data = new byte[content.readableBytes()];
        content.readBytes(data);

        log.debug("HttpTunnel UDP_FORWARD connId={} dst={} {}bytes", connId, dst, data.length);

        Queue<byte[]> responseQueue = udpResponses.computeIfAbsent(connId, k -> new ConcurrentLinkedQueue<>());

        // 使用临时 UDP channel 发送
        Sockets.udpBootstrap(config, outbound -> {
            outbound.pipeline().addLast(new UdpBackendHandler(connId));
        }).bind(0).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                Channel ch = f.channel();
                InetSocketAddress dstAddr = dst.socketAddress();
                ch.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(data), dstAddr));
            }
        });

        returnOk(res);
    }

    // ---- POLL ----

    private void handlePoll(int connId, org.rx.net.http.ServerResponse res) {
        ByteBuf resBuf = Bytes.directBuffer();

        // 收集 TCP 反向数据
        TcpConnection tcpConn = tcpConnections.get(connId);
        if (tcpConn != null) {
            byte[] data;
            while ((data = tcpConn.responseQueue.poll()) != null) {
                resBuf.writeInt(data.length);
                resBuf.writeBytes(data);
            }
        }

        // 收集 UDP 反向数据
        Queue<byte[]> udpQueue = udpResponses.get(connId);
        if (udpQueue != null) {
            byte[] data;
            while ((data = udpQueue.poll()) != null) {
                resBuf.writeInt(data.length);
                resBuf.writeBytes(data);
            }
        }

        if (resBuf.readableBytes() > 0) {
            res.setContent(resBuf);
        } else {
            resBuf.release();
        }
    }

    private void returnOk(org.rx.net.http.ServerResponse res) {
        ByteBuf buf = Bytes.directBuffer();
        buf.writeByte(1); // OK
        res.setContent(buf);
    }

    // ---- Backend handlers ----

    /**
     * TCP 后端: 目标服务器返回数据 -> 放入 responseQueue 等待 poll
     */
    class TcpBackendHandler extends ChannelInboundHandlerAdapter {
        final int connId;

        TcpBackendHandler(int connId) {
            this.connId = connId;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);

                TcpConnection conn = tcpConnections.get(connId);
                if (conn != null) {
                    conn.responseQueue.offer(data);
                }
                log.debug("HttpTunnel TCP backend connId={} received {}bytes", connId, data.length);
            } finally {
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("HttpTunnel TCP backend connId={} inactive", connId);
            // 不立即移除，让最后 poll 能取到剩余数据
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("HttpTunnel TCP backend connId={} error", connId, cause);
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    /**
     * UDP 后端: 目标服务器返回数据 -> 放入 responseQueue 等待 poll
     */
    class UdpBackendHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        final int connId;

        UdpBackendHandler(int connId) {
            this.connId = connId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf buf = packet.content();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            Queue<byte[]> responseQueue = udpResponses.computeIfAbsent(connId, k -> new ConcurrentLinkedQueue<>());

            // 编码: senderAddrLen(4) + senderAddr + data
            InetSocketAddress sender = packet.sender();
            ByteBuf addrBuf = Bytes.heapBuffer();
            HttpTunnelProtocol.encodeAddress(addrBuf, new UnresolvedEndpoint(sender));
            byte[] addrBytes = new byte[addrBuf.readableBytes()];
            addrBuf.readBytes(addrBytes);
            addrBuf.release();

            ByteBuf fullBuf = Bytes.heapBuffer();
            fullBuf.writeInt(addrBytes.length);
            fullBuf.writeBytes(addrBytes);
            fullBuf.writeBytes(data);
            byte[] fullData = new byte[fullBuf.readableBytes()];
            fullBuf.readBytes(fullData);
            fullBuf.release();

            responseQueue.offer(fullData);
            log.debug("HttpTunnel UDP backend connId={} from {} received {}bytes", connId, sender, data.length);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("HttpTunnel UDP backend connId={} error", connId, cause);
        }
    }
}
