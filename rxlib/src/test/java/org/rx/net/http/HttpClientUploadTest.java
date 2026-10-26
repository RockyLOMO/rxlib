package org.rx.net.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.io.DuplexStream;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
public class HttpClientUploadTest {
    private static HttpClientConfig config() {
        return new HttpClientConfig().setCookieJar(null).setEnableLog(false)
                .setMaxConnectionsPerHost(1).setReadWriteTimeoutMillis(5000)
                .setUploadChunkBytes(4096).setUploadFlushBytes(8192).setUploadFlushChunks(2);
    }

    @Test
    public void knownMultipartHasExactLengthUnicodeEmptyAndMultipleFiles() throws Exception {
        byte[] data = new byte[20001];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        ProbeStream file = new ProbeStream("文件.bin", data, data.length);
        ProbeStream empty = new ProbeStream("empty.txt", new byte[0], 0);
        Map<String, Object> forms = Collections.<String, Object>singletonMap("姓名", "乐之");
        Map<String, DuplexStream> files = new LinkedHashMap<>();
        files.put("file", file);
        files.put("empty", empty);
        HttpClient.MultipartContent body = new HttpClient.MultipartContent(forms, files);
        assertTrue(body.streaming());
        try (WireServer server = new WireServer(); HttpClient client = new HttpClient(config())) {
            try (HttpClient.Response response = client.execute(HttpClient.request(HttpMethod.POST, server.url("/body")).body(body))) {
                assertEquals(200, response.code());
            }
            Captured wire = server.completed.poll(5, TimeUnit.SECONDS);
            assertNotNull(wire);
            assertFalse(wire.chunked);
            assertEquals(body.contentLength(), wire.bytes.size());
            assertEquals(body.contentLength(), Long.parseLong(wire.headers.get(HttpHeaderNames.CONTENT_LENGTH)));
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            utf8(expected, "--" + body.boundary + "\r\nContent-Disposition: form-data; name=\"姓名\"\r\n\r\n乐之\r\n");
            utf8(expected, "--" + body.boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"文件.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            expected.write(data);
            utf8(expected, "\r\n--" + body.boundary + "\r\nContent-Disposition: form-data; name=\"empty\"; filename=\"empty.txt\"\r\nContent-Type: text/plain\r\n\r\n\r\n--" + body.boundary + "--\r\n");
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()),
                    MessageDigest.getInstance("SHA-256").digest(wire.bytes.toByteArray()));
            assertEquals(4096, file.maxRead);
            assertTrue(file.isClosed());
            assertTrue(empty.isClosed());
        }
    }

    @Test
    public void unknownMultipartUsesChunkedAndConfiguredReads() throws Exception {
        ProbeStream file = new ProbeStream("unknown.bin", new byte[17001], -1);
        HttpClient.MultipartContent body = multipart(file);
        try (WireServer server = new WireServer(); HttpClient client = new HttpClient(config())) {
            try (HttpClient.Response response = client.execute(HttpClient.request(HttpMethod.POST, server.url("/body")).body(body))) {
                assertEquals(200, response.code());
            }
            Captured wire = server.completed.poll(5, TimeUnit.SECONDS);
            assertNotNull(wire);
            assertTrue(wire.chunked);
            assertFalse(wire.headers.contains(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals(4096, file.maxRead);
            assertTrue(wire.bytes.size() > 17001);
        }
    }

    @Test
    public void changedLengthFailsAndNextRequestUsesFreshConnection() throws Exception {
        for (long reported : new long[]{1, 50000}) {
            try (WireServer server = new WireServer(); HttpClient client = new HttpClient(config())) {
                ProbeStream file = new ProbeStream("changed.bin", new byte[16000], reported);
                CompletableFuture<HttpClient.Response> future = client.executeAsync(
                        HttpClient.request(HttpMethod.POST, server.url("/body")).body(multipart(file)));
                assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
                Captured first = server.started.poll(5, TimeUnit.SECONDS);
                assertNotNull(first);
                try (HttpClient.Response response = client.get(server.url("/ping"))) {
                    assertEquals(200, response.code());
                }
                Captured next = server.started.poll(5, TimeUnit.SECONDS);
                assertNotNull(next);
                assertNotEquals(first.channelId, next.channelId);
                assertTrue(file.isClosed());
            }
        }
    }

    @Test
    public void failedReadReleasesAllocatedChunk() throws Exception {
        ProbeStream file = new ProbeStream("broken.bin", new byte[1], -1);
        file.failRead = true;
        try (WireServer server = new WireServer(); HttpClient client = new HttpClient(config())) {
            CompletableFuture<HttpClient.Response> future = client.executeAsync(
                    HttpClient.request(HttpMethod.POST, server.url("/body")).body(multipart(file)));
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertNotNull(file.lastBuffer);
            assertEquals(0, file.lastBuffer.refCnt());
            assertTrue(file.isClosed());
        }
    }

    @Test
    public void cancelledUploadStopsReadingAndDoesNotReuseConnection() throws Exception {
        stoppedUpload(false);
    }

    @Test
    public void earlyResponseDoesNotReuseUploadingConnection() throws Exception {
        stoppedUpload(true);
    }

    private void stoppedUpload(boolean early) throws Exception {
        ProbeStream file = new ProbeStream("slow.bin", new byte[1024 * 1024], -1);
        file.gate = new CountDownLatch(1);
        try (WireServer server = new WireServer(); HttpClient client = new HttpClient(config())) {
            CompletableFuture<HttpClient.Response> future = client.executeAsync(
                    HttpClient.request(HttpMethod.POST, server.url(early ? "/early" : "/body")).body(multipart(file)));
            Captured first = server.started.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            if (early) {
                try (HttpClient.Response response = future.get(5, TimeUnit.SECONDS)) {
                    assertEquals(413, response.code());
                }
            } else {
                assertTrue(file.entered.await(5, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
            }
            file.gate.countDown();
            try (HttpClient.Response response = client.get(server.url("/ping"))) {
                assertEquals(200, response.code());
            }
            Captured next = server.started.poll(5, TimeUnit.SECONDS);
            assertNotNull(next);
            assertNotEquals(first.channelId, next.channelId);
            assertTrue(file.reads <= 1, "upload must stop after cancellation or early response");
        } finally {
            file.gate.countDown();
        }
    }

    private static HttpClient.MultipartContent multipart(ProbeStream stream) {
        return new HttpClient.MultipartContent(Collections.emptyMap(),
                Collections.<String, DuplexStream>singletonMap("file", stream));
    }

    private static void utf8(ByteArrayOutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ProbeStream extends DuplexStream {
        final String name;
        final byte[] data;
        final long reportedLength;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate;
        volatile ByteBuf lastBuffer;
        volatile int reads;
        volatile int maxRead;
        boolean failRead;
        int position;

        ProbeStream(String name, byte[] data, long reportedLength) {
            this.name = name;
            this.data = data;
            this.reportedLength = reportedLength;
        }

        @Override public String getName() { return name; }
        @Override public long getLength() { return reportedLength; }
        @Override public void setPosition(long position) { this.position = (int) position; }
        @Override public int read() { return position == data.length ? -1 : data[position++] & 255; }
        @Override protected void dispose() { }

        @Override public int read(ByteBuf dst, int length) {
            lastBuffer = dst;
            maxRead = Math.max(maxRead, length);
            reads++;
            entered.countDown();
            if (gate != null) {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("read gate timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (failRead) {
                throw new IllegalStateException("injected read failure");
            }
            int count = Math.min(length, data.length - position);
            if (count == 0) {
                return -1;
            }
            dst.writeBytes(data, position, count);
            position += count;
            return count;
        }
    }

    private static final class Captured {
        final String channelId;
        final HttpHeaders headers;
        final boolean chunked;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Captured(Channel channel, HttpRequest request) {
            channelId = channel.id().asLongText();
            headers = new DefaultHttpHeaders().set(request.headers());
            chunked = HttpUtil.isTransferEncodingChunked(request);
        }
    }

    private static final class WireServer implements AutoCloseable {
        final EventLoopGroup group = new NioEventLoopGroup(1);
        final BlockingQueue<Captured> started = new LinkedBlockingQueue<>();
        final BlockingQueue<Captured> completed = new LinkedBlockingQueue<>();
        final Channel channel;

        WireServer() throws Exception {
            channel = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new SimpleChannelInboundHandler<HttpObject>() {
                                Captured current;
                                boolean early;
                                @Override protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
                                    if (msg instanceof HttpRequest) {
                                        HttpRequest request = (HttpRequest) msg;
                                        current = new Captured(ctx.channel(), request);
                                        started.add(current);
                                        early = request.uri().equals("/early");
                                        if (early) {
                                            reply(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                                        }
                                    }
                                    if (msg instanceof HttpContent && current != null) {
                                        ByteBuf bytes = ((HttpContent) msg).content();
                                        byte[] part = new byte[bytes.readableBytes()];
                                        bytes.readBytes(part);
                                        current.bytes.write(part, 0, part.length);
                                        if (msg instanceof LastHttpContent) {
                                            completed.add(current);
                                            current = null;
                                            if (!early) {
                                                reply(ctx, HttpResponseStatus.OK);
                                            }
                                        }
                                    }
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    ctx.close();
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
        }

        String url(String path) {
            return "http://127.0.0.1:" + ((InetSocketAddress) channel.localAddress()).getPort() + path;
        }

        static void reply(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            HttpUtil.setContentLength(response, 0);
            HttpUtil.setKeepAlive(response, true);
            ctx.writeAndFlush(response);
        }

        @Override public void close() {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }
}
