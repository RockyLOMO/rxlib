package org.rx.net.http;

import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Per-server resource limits; a server takes a defensive copy at construction. */
@Getter
public class HttpServerConfig {
    private long maxRequestBodyBytes = 8L * 1024 * 1024;
    private long maxMultipartBytes = 64L * 1024 * 1024;
    private long maxFileBytes = 32L * 1024 * 1024;
    private int maxFormFields = 128;
    private int maxFormFieldBytes = 65536;
    private int maxUriLength = 4096;
    private int maxHeaderBytes = 8192;
    private int headerTimeoutMillis = 10000;
    private int bodyTimeoutMillis = 30000;
    private int keepAliveIdleMillis = 60000;
    private int maxConnections = 1024;
    private int maxConcurrentUploads = 32;
    private int maxAsyncRequests = 64;
    // Null selects the server-owned bounded executor. Custom executors remain caller-owned.
    private Executor businessExecutor;
    private InetSocketAddress bindAddress;

    public HttpServerConfig() {
    }

    public HttpServerConfig(HttpServerConfig source) {
        Objects.requireNonNull(source, "source");
        maxRequestBodyBytes = source.maxRequestBodyBytes;
        maxMultipartBytes = source.maxMultipartBytes;
        maxFileBytes = source.maxFileBytes;
        maxFormFields = source.maxFormFields;
        maxFormFieldBytes = source.maxFormFieldBytes;
        maxUriLength = source.maxUriLength;
        maxHeaderBytes = source.maxHeaderBytes;
        headerTimeoutMillis = source.headerTimeoutMillis;
        bodyTimeoutMillis = source.bodyTimeoutMillis;
        keepAliveIdleMillis = source.keepAliveIdleMillis;
        maxConnections = source.maxConnections;
        maxConcurrentUploads = source.maxConcurrentUploads;
        maxAsyncRequests = source.maxAsyncRequests;
        businessExecutor = source.businessExecutor;
        bindAddress = source.bindAddress;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public HttpServerConfig setMaxRequestBodyBytes(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxRequestBodyBytes must not exceed Integer.MAX_VALUE");
        }
        maxRequestBodyBytes = positive(value, "maxRequestBodyBytes");
        return this;
    }

    public HttpServerConfig setMaxMultipartBytes(long value) {
        maxMultipartBytes = positive(value, "maxMultipartBytes");
        return this;
    }

    public HttpServerConfig setMaxFileBytes(long value) {
        maxFileBytes = positive(value, "maxFileBytes");
        return this;
    }

    public HttpServerConfig setMaxFormFields(int value) {
        maxFormFields = (int) positive(value, "maxFormFields");
        return this;
    }

    public HttpServerConfig setMaxFormFieldBytes(int value) {
        maxFormFieldBytes = (int) positive(value, "maxFormFieldBytes");
        return this;
    }

    public HttpServerConfig setMaxUriLength(int value) {
        maxUriLength = (int) positive(value, "maxUriLength");
        return this;
    }

    public HttpServerConfig setMaxHeaderBytes(int value) {
        maxHeaderBytes = (int) positive(value, "maxHeaderBytes");
        return this;
    }

    public HttpServerConfig setHeaderTimeoutMillis(int value) {
        headerTimeoutMillis = (int) positive(value, "headerTimeoutMillis");
        return this;
    }

    public HttpServerConfig setBodyTimeoutMillis(int value) {
        bodyTimeoutMillis = (int) positive(value, "bodyTimeoutMillis");
        return this;
    }

    public HttpServerConfig setKeepAliveIdleMillis(int value) {
        keepAliveIdleMillis = (int) positive(value, "keepAliveIdleMillis");
        return this;
    }

    public HttpServerConfig setMaxConnections(int value) {
        maxConnections = (int) positive(value, "maxConnections");
        return this;
    }

    public HttpServerConfig setMaxConcurrentUploads(int value) {
        maxConcurrentUploads = (int) positive(value, "maxConcurrentUploads");
        return this;
    }

    public HttpServerConfig setMaxAsyncRequests(int value) {
        maxAsyncRequests = (int) positive(value, "maxAsyncRequests");
        return this;
    }

    public HttpServerConfig setBusinessExecutor(Executor value) {
        businessExecutor = Objects.requireNonNull(value, "businessExecutor");
        return this;
    }

    public HttpServerConfig setBindAddress(InetSocketAddress value) {
        bindAddress = value;
        return this;
    }
}

