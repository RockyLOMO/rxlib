package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerLifecycleTest {
    private static final class TestChannel extends EmbeddedChannel {
        TestChannel(ChannelHandler... handlers) {
            super(handlers);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
    }

    private static DefaultHttpRequest request(String contentType) {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test");
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setTransferEncodingChunked(request, true);
        return request;
    }

    private static DefaultHttpContent content(String value) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(value, CharsetUtil.UTF_8));
    }

    private static final String PART = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\nhello";

    @Test
    void ordinaryBodyDisconnectReleasesAccumulatedBuffer() {
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> fail("Incomplete request must not run"));
            HttpServer.ServerHandler handler = server.new ServerHandler();
            TestChannel channel = new TestChannel(handler);
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(content("{\"partial\":"));
                ByteBuf body = handler.state.req.getContent();
                assertTrue(body.refCnt() > 0);
                channel.close();
                channel.runPendingTasks();
                assertEquals(0, body.refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
                assertEquals(0, server.getMetrics().activeConnections());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void partialMultipartDisconnectDestroysPartialUpload() {
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> fail("Incomplete multipart must not run"));
            HttpServer.ServerHandler handler = server.new ServerHandler();
            TestChannel channel = new TestChannel(handler);
            try {
                channel.writeInbound(request("multipart/form-data; boundary=boundary"));
                channel.writeInbound(content(PART));
                InterfaceHttpData partial = handler.state.decoder.currentPartialHttpData();
                assertNotNull(partial);
                assertTrue(partial.refCnt() > 0);
                channel.close();
                channel.runPendingTasks();
                assertEquals(0, partial.refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void thousandInterruptedDiskUploadsLeaveNoFilesOrActiveRequests() throws Exception {
        char[] chars = new char[4096];
        java.util.Arrays.fill(chars, 'x');
        String chunk = new String(chars);
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> fail("Interrupted uploads must not invoke the handler"));
            for (int iteration = 0; iteration < 1000; iteration++) {
                HttpServer.ServerHandler handler = server.new ServerHandler();
                TestChannel channel = new TestChannel(handler);
                try {
                    channel.writeInbound(request("multipart/form-data; boundary=boundary"));
                    channel.writeInbound(content(PART));
                    // Five separate chunks force the mixed upload beyond its 16 KiB disk threshold.
                    for (int part = 0; part < 5; part++) {
                        channel.writeInbound(content(chunk));
                    }
                    InterfaceHttpData partial = handler.state.decoder.currentPartialHttpData();
                    assertTrue(partial instanceof FileUpload, "Iteration " + iteration);
                    FileUpload upload = (FileUpload) partial;
                    assertFalse(upload.isInMemory(), "Upload must actually exercise disk cleanup");
                    File file = upload.getFile();
                    assertNotNull(file);
                    assertTrue(file.isFile(), "Partial upload should have an existing temporary file");
                    assertTrue(upload.refCnt() > 0);
                    channel.close();
                    channel.runPendingTasks();
                    assertEquals(0, upload.refCnt(), "Iteration " + iteration);
                    assertFalse(file.exists(), "Temporary file survived iteration " + iteration);
                    assertEquals(0, server.getMetrics().activeRequests(), "Iteration " + iteration);
                    assertEquals(0, server.getMetrics().activeConnections(), "Iteration " + iteration);
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void completedMultipartIsBorrowedDuringHandlerAndReleasedAfterResponse() {
        AtomicReference<FileUpload> upload = new AtomicReference<>();
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> {
                FileUpload file = req.getFiles().getFirst("file");
                assertNotNull(file);
                assertTrue(file.refCnt() > 0);
                assertEquals("hello", file.getByteBuf().toString(CharsetUtil.UTF_8));
                upload.set(file);
                res.htmlBody("ok");
            });
            TestChannel channel = new TestChannel(server.new ServerHandler());
            try {
                channel.writeInbound(request("multipart/form-data; boundary=boundary"));
                channel.writeInbound(content(PART + "\r\n--boundary--\r\n"));
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                FullHttpResponse response = channel.readOutbound();
                assertNotNull(response);
                try {
                    assertEquals(HttpResponseStatus.OK, response.status());
                } finally {
                    response.release();
                }
                assertNotNull(upload.get());
                assertEquals(0, upload.get().refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void disconnectDuringAsyncHandlerDefersBodyReleaseUntilHandlerReturns() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<ByteBuf> body = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServerConfig config = new HttpServerConfig();
        config.setBusinessExecutor(executor);
        try (HttpServer server = new HttpServer(0, false, config)) {
            server.requestAsync("/test", (req, res) -> {
                body.set(req.getContent());
                entered.countDown();
                try {
                    assertTrue(leave.await(5, TimeUnit.SECONDS));
                    assertEquals("payload", req.jsonBody());
                    res.htmlBody("ok");
                } catch (Throwable e) {
                    failure.set(e);
                    throw e;
                } finally {
                    finished.countDown();
                }
            });
            TestChannel channel = new TestChannel(server.new ServerHandler());
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(content("payload"));
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                channel.close();
                channel.runPendingTasks();
                assertTrue(body.get().refCnt() > 0, "Worker still owns the request body");
                leave.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                await(channel, () -> body.get().refCnt() == 0 && server.getMetrics().activeRequests() == 0);
                assertNull(failure.get());
            } finally {
                leave.countDown();
                channel.finishAndReleaseAll();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedBusinessExecutorReturns503AndReleasesBody() {
        HttpServerConfig config = new HttpServerConfig();
        config.setBusinessExecutor(task -> { throw new RejectedExecutionException("test rejection"); });
        try (HttpServer server = new HttpServer(0, false, config)) {
            server.requestAsync("/test", (req, res) -> fail("Rejected task must not run"));
            HttpServer.ServerHandler handler = server.new ServerHandler();
            TestChannel channel = new TestChannel(handler);
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(content("payload"));
                ByteBuf body = handler.state.req.getContent();
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                channel.runPendingTasks();
                FullHttpResponse response = channel.readOutbound();
                assertNotNull(response);
                try {
                    assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
                } finally {
                    response.release();
                }
                assertEquals(0, body.refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void failedWriteReleasesResponseAndClosesConnection() {
        AtomicReference<ByteBuf> responseBody = new AtomicReference<>();
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> {
                res.htmlBody("response");
                responseBody.set(res.getContent());
            });
            TestChannel channel = new TestChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(new IOException("Injected write failure"));
                }
            }, server.new ServerHandler());
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                channel.runPendingTasks();
                assertFalse(channel.isActive());
                assertNotNull(responseBody.get());
                assertEquals(0, responseBody.get().refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
                assertEquals(1, server.getMetrics().writeFailures());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void alreadyDecodedPipelinedRequestCannotOverwriteAsyncRequest() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger secondCalls = new AtomicInteger();
        HttpServerConfig config = new HttpServerConfig();
        config.setBusinessExecutor(task -> assertTrue(queued.compareAndSet(null, task)));
        try (HttpServer server = new HttpServer(0, false, config)) {
            server.requestAsync("/test", (req, res) -> res.htmlBody("first"));
            server.requestMapping("/second", (req, res) -> secondCalls.incrementAndGet());
            HttpServer.ServerHandler handler = server.new ServerHandler();
            TestChannel channel = new TestChannel(handler);
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(content("first request"));
                ByteBuf body = handler.state.req.getContent();
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                assertNotNull(queued.get());
                // autoRead=false cannot retract objects already emitted by HttpServerCodec.
                channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second"));
                assertFalse(channel.isActive());
                assertEquals(0, secondCalls.get());
                assertEquals(0, body.refCnt(), "Cancellation must clean queued tasks even if the executor never runs them");
                assertEquals(0, server.getMetrics().activeRequests());
                queued.get().run();
                channel.runPendingTasks();
                assertEquals(0, body.refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void retainedUploadSurvivesDecoderCleanupWithOneApplicationReference() throws Exception {
        AtomicReference<FileUpload> retained = new AtomicReference<>();
        try (HttpServer server = new HttpServer(0, false)) {
            server.requestMapping("/test", (req, res) -> {
                FileUpload file = req.getFiles().getFirst("file");
                retained.set(file.retain());
                res.htmlBody("ok");
            });
            TestChannel channel = new TestChannel(server.new ServerHandler());
            try {
                channel.writeInbound(request("multipart/form-data; boundary=boundary"));
                channel.writeInbound(content(PART + "\r\n--boundary--\r\n"));
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                assertNotNull(retained.get());
                assertEquals(1, retained.get().refCnt(), "Only the application's retained reference must remain");
                assertEquals("hello", retained.get().getByteBuf().toString(CharsetUtil.UTF_8));
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
                if (retained.get() != null && retained.get().refCnt() > 0) {
                    retained.get().release();
                }
            }
            assertEquals(0, retained.get().refCnt());
        }
    }

    @Test
    void stoppingServerClosesAcceptedChildAndRejectsLateAcceptedChild() {
        HttpServer server = new HttpServer(0, false);
        server.requestMapping("/test", (req, res) -> fail("Partial request must not run"));
        HttpServer.ServerHandler handler = server.new ServerHandler();
        TestChannel accepted = new TestChannel(handler);
        TestChannel late = null;
        try {
            accepted.writeInbound(request("application/json"));
            accepted.writeInbound(content("partial"));
            ByteBuf body = handler.state.req.getContent();
            assertEquals(1, server.getMetrics().activeRequests());
            server.close();
            accepted.runPendingTasks();
            assertFalse(accepted.isActive());
            assertEquals(0, body.refCnt());
            assertEquals(0, server.getMetrics().activeRequests());
            assertEquals(0, server.getMetrics().activeConnections());
            // Simulate an accept that was already queued when the listening socket closed.
            late = new TestChannel(server.new ServerHandler());
            late.runPendingTasks();
            assertFalse(late.isActive());
            assertEquals(0, server.getMetrics().activeConnections());
        } finally {
            accepted.finishAndReleaseAll();
            if (late != null) {
                late.finishAndReleaseAll();
            }
            server.close();
        }
    }

    @Test
    void rejectedEventLoopHandoffReleasesResponseAndRequest() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<ByteBuf> responseBody = new AtomicReference<>();
        HttpServerConfig config = new HttpServerConfig();
        config.setBusinessExecutor(queued::set);
        try (HttpServer server = new HttpServer(0, false, config)) {
            server.requestAsync("/test", (req, res) -> {
                res.htmlBody("response");
                responseBody.set(res.getContent());
            });
            HttpServer.ServerHandler handler = server.new ServerHandler();
            TestChannel channel = new TestChannel(handler);
            try {
                channel.writeInbound(request("application/json"));
                channel.writeInbound(content("payload"));
                ByteBuf body = handler.state.req.getContent();
                ChannelHandlerContext context = channel.pipeline().context(handler);
                io.netty.util.concurrent.EventExecutor rejecting = (io.netty.util.concurrent.EventExecutor) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{io.netty.util.concurrent.EventExecutor.class},
                        (proxy, method, args) -> {
                            if ("execute".equals(method.getName())) {
                                throw new RejectedExecutionException("Injected EventLoop rejection");
                            }
                            return method.invoke(context.executor(), args);
                        });
                ChannelHandlerContext rejectingContext = (ChannelHandlerContext) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{ChannelHandlerContext.class},
                        (proxy, method, args) -> "executor".equals(method.getName()) ? rejecting : method.invoke(context, args));
                handler.state.receiving = false;
                handler.dispatch(rejectingContext, handler.state);
                assertNotNull(queued.get());
                queued.get().run();
                channel.runPendingTasks();
                assertFalse(channel.isActive());
                assertNotNull(responseBody.get());
                assertEquals(0, responseBody.get().refCnt());
                assertEquals(0, body.refCnt());
                assertEquals(0, server.getMetrics().activeRequests());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    private static void await(TestChannel channel, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            Thread.sleep(1);
        }
        channel.runPendingTasks();
        assertTrue(condition.getAsBoolean(), "Asynchronous cleanup did not finish within five seconds");
    }
}
