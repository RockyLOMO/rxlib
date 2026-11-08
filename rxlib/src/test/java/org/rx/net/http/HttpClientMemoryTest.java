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
import org.rx.io.HybridStream;
import org.rx.io.MemoryStream;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientMemoryTest {
    static ByteBuf memoryBuffer(HybridStream stream) throws Exception {
        Field field = HybridStream.class.getDeclaredField("memoryStream");
        field.setAccessible(true);
        MemoryStream memory = (MemoryStream) field.get(stream);
        if (memory == null) {
            return null;
        }
        field = MemoryStream.class.getDeclaredField("buffer");
        field.setAccessible(true);
        return (ByteBuf) field.get(memory);
    }

    @Test
    void independentInitialCapacityPreservesBoundaryAndCleanup() throws Exception {
        for (int size : new int[]{0, 1, 1024, 32767, 32768, 32769}) {
            byte[] data = new byte[size];
            Arrays.fill(data, (byte) 'a');
            File temp = null;
            ByteBuf buffer;
            try (HybridStream stream = new HybridStream(32768, false, null, 4096)) {
                buffer = memoryBuffer(stream);
                assertEquals(4096, buffer.capacity());
                stream.write(data);
                assertEquals(size > 32768, stream.isFileBacked());
                for (int i = 0; i < 2; i++) {
                    stream.rewind();
                    byte[] actual = new byte[size];
                    if (size > 0) {
                        assertEquals(size, stream.read(actual));
                    }
                    assertArrayEquals(data, actual);
                }
                if (stream.isFileBacked()) {
                    Field field = HybridStream.class.getDeclaredField("fileStream");
                    field.setAccessible(true);
                    temp = new File(((org.rx.io.FileStream) field.get(stream)).getPath());
                    assertTrue(temp.exists());
                }
            }
            assertEquals(0, buffer.refCnt());
            if (temp != null) {
                assertFalse(temp.exists());
            }
        }
        try (HybridStream legacy = new HybridStream(32768, false);
             HybridStream clamped = new HybridStream(8, false, null, 4096)) {
            assertEquals(32768, memoryBuffer(legacy).capacity());
            assertEquals(8, memoryBuffer(clamped).capacity());
        }
    }

    @Test
    void responseAllocationAndRepeatReadsForKnownAndChunkedLengths() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel server = null;
        try {
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new SimpleChannelInboundHandler<HttpObject>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
                                    if (!(msg instanceof HttpRequest)) {
                                        return;
                                    }
                                    String[] parts = ((HttpRequest) msg).uri().substring(1).split("/");
                                    int size = Integer.parseInt(parts[0]);
                                    boolean chunked = Boolean.parseBoolean(parts[1]);
                                    HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                    if (chunked) {
                                        HttpUtil.setTransferEncodingChunked(head, true);
                                    } else {
                                        HttpUtil.setContentLength(head, size);
                                    }
                                    ctx.write(head);
                                    for (int remaining = size; remaining > 0;) {
                                        int count = Math.min(remaining, 2048);
                                        byte[] bytes = new byte[count];
                                        Arrays.fill(bytes, (byte) 'a');
                                        ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)));
                                        remaining -= count;
                                    }
                                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            String base = "http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
            try (HttpClient client = new HttpClient(new HttpClientConfig().setCookieJar(null).setEnableLog(false)
                    .setResponseOffloadThreshold(32768))) {
                for (boolean chunked : new boolean[]{false, true}) {
                    for (int size : new int[]{0, 1, 1024, 32767, 32768, 32769}) {
                        HybridStream body;
                        try (HttpClient.Response response = client.get(base + "/" + size + "/" + chunked)) {
                            body = (HybridStream) response.bodyStream();
                            assertEquals(size, body.getLength());
                            assertEquals(size > 32768, body.isFileBacked());
                            if (size <= 1024) {
                                assertEquals(4096, memoryBuffer(body).capacity());
                            }
                            String text = response.bodyAsString();
                            assertEquals(size, text.length());
                            assertEquals(text, response.bodyAsString());
                            File output = File.createTempFile("rx-http-memory-", ".bin");
                            try {
                                response.bodyAsFile(output.getAbsolutePath());
                                assertEquals(text, new String(Files.readAllBytes(output.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                            } finally {
                                Files.deleteIfExists(output.toPath());
                            }
                        }
                        assertTrue(body.isClosed());
                    }
                }
            }
        } finally {
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void newConfigFieldsAreCopiedAndBounded() {
        HttpClientConfig source = new HttpClientConfig().setResponseInitialBufferBytes(8192)
                .setUploadChunkBytes(32768).setUploadFlushBytes(65536).setUploadFlushChunks(2);
        HttpClientConfig copy = new HttpClientConfig(source);
        assertEquals(8192, copy.getResponseInitialBufferBytes());
        assertEquals(32768, copy.getUploadChunkBytes());
        assertEquals(65536, copy.getUploadFlushBytes());
        assertEquals(2, copy.getUploadFlushChunks());
        assertEquals(1, copy.setUploadChunkBytes(-1).getUploadChunkBytes());
        assertEquals(1, copy.setResponseInitialBufferBytes(0).getResponseInitialBufferBytes());
    }
}
