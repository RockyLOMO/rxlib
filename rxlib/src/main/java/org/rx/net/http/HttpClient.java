package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import io.netty.handler.codec.http.*;
import kotlin.Pair;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.*;
import okio.BufferedSink;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.rx.bean.ProceedEventArgs;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.util.function.BiFunc;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.StringBuilder;
import java.net.Proxy;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.rx.core.Sys.logHttp;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class HttpClient {
    public interface RequestContent {
        RequestContent NONE = new EmptyContent(null);

        HttpHeaders getHeaders();

        RequestBody toBody();
    }

    @RequiredArgsConstructor
    public static class JsonContent implements RequestContent {
        final Object json;
        @Getter
        final HttpHeaders headers;
        String str;

        @Override
        public RequestBody toBody() {
            return RequestBody.create(JSON_TYPE, toString());
        }

        @Override
        public String toString() {
            if (str == null) {
                str = toJsonString(json);
            }
            return str;
        }
    }

    @RequiredArgsConstructor
    public static class FormContent implements RequestContent {
        final Map<String, Object> forms;
        final Map<String, IOStream> files;
        @Getter
        final HttpHeaders headers;

        @Override
        public RequestBody toBody() {
            if (MapUtils.isEmpty(files)) {
                String formString = buildUrl(null, forms);
                if (!Strings.isEmpty(formString)) {
                    formString = formString.substring(1);
                }
                return RequestBody.create(FORM_TYPE, formString);
            }
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            if (!MapUtils.isEmpty(forms)) {
                for (Map.Entry<String, Object> entry : forms.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    builder.addFormDataPart(entry.getKey(), entry.getValue().toString());
                }
            }
            if (!MapUtils.isEmpty(files)) {
                for (Map.Entry<String, IOStream> entry : files.entrySet()) {
                    IOStream stream = entry.getValue();
                    if (stream == null) {
                        continue;
                    }
                    builder.addFormDataPart(entry.getKey(), stream.getName(), new RequestBody() {
                        @Override
                        public MediaType contentType() {
                            return MediaType.parse(Files.getMediaTypeFromName(stream.getName()));
                        }

                        @Override
                        public void writeTo(BufferedSink bufferedSink) {
                            stream.read(bufferedSink.outputStream());
                        }
                    });
                }
            }
            return builder.build();
        }

        @Override
        public String toString() {
            return "FormContent{" + "forms=" + forms + ", files=" + files + '}';
        }
    }

    @RequiredArgsConstructor
    public static class EmptyContent implements RequestContent {
        final MediaType contentType;

        @Override
        public HttpHeaders getHeaders() {
            return EmptyHttpHeaders.INSTANCE;
        }

        @Override
        public RequestBody toBody() {
            return RequestBody.create(contentType, toString());
        }

        @Override
        public String toString() {
            return Strings.EMPTY;
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PROTECTED)
    public static class ResponseContent {
        @Getter
        @JSONField(serialize = false)
        final Response response;
        boolean cachingStream = true;
        HybridStream stream;
        File file;
        String str;

        public String getResponseUrl() {
            return response.request().url().toString();
        }

        public String getResponseText() {
            return toString();
        }

        @JSONField(serialize = false)
        public Charset getCharset() {
            return response.body() != null ? Reflects.invokeMethod(response.body(), "charset") : StandardCharsets.UTF_8;
        }

        public Headers responseHeaders() {
            return response.headers();
        }

        public InputStream responseStream() {
            return response.body() != null ? response.body().byteStream() : null;
        }

        @SneakyThrows
        public synchronized <T> T handle(BiFunc<InputStream, T> fn) {
            if (!cachingStream) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new InvalidException("Empty response from url {}", getResponseUrl());
                }
                try {
                    return fn.invoke(body.byteStream());
                } finally {
                    body.close();
                }
            }

            if (stream == null) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new InvalidException("Empty response from url {}", getResponseUrl());
                }
                try {
                    Long len = Reflects.changeType(response.header(HttpHeaderNames.CONTENT_LENGTH.toString()), Long.class);
                    stream = new HybridStream(len != null && len > Constants.MAX_HEAP_BUF_SIZE ? HybridStream.NON_MEMORY_SIZE : Constants.MAX_HEAP_BUF_SIZE, false);
                    stream.write(body.byteStream());
                } finally {
                    body.close();
                }
            }
            return fn.invoke(stream.rewind().getReader());
        }

        public synchronized HybridStream toStream() {
            cachingStream = true;
            return handle(in -> stream);
        }

        public File toFile(String filePath) {
            if (file == null) {
                file = handle(in -> {
                    Files.saveFile(filePath, in);
                    return new File(filePath);
                });
            }
            return file;
        }

        public <T extends Serializable> T toJson() {
            return (T) JSON.parse(toString());
        }

        @Override
        public String toString() {
            if (str == null) {
                str = handle(in -> IOStream.readString(in, getCharset()));
            }
            return str;
        }
    }

    public static final CookieContainer COOKIES = new CookieContainer();
    static final ConnectionPool POOL = new ConnectionPool(RxConfig.INSTANCE.getNet().getPoolMaxSize(), RxConfig.INSTANCE.getNet().getPoolKeepAliveSeconds(), TimeUnit.SECONDS);
    static final MediaType FORM_TYPE = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"), JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    static final X509TrustManager TRUST_MANAGER = new X509TrustManager() {
        final X509Certificate[] empty = new X509Certificate[0];

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return empty;
        }
    };

    //region StaticMembers
    public static String encodeCookie(List<Cookie> cookies) {
        if (cookies == null) {
            return Strings.EMPTY;
        }

        return String.join("; ", Linq.from(cookies).select(p -> p.name() + "=" + p.value()));
    }

    public static List<Cookie> decodeCookie(@NonNull HttpUrl httpUrl, @NonNull String raw) {
        List<Cookie> cookies = new ArrayList<>();
        String domain = httpUrl.topPrivateDomain();
        for (String pair : raw.split(Pattern.quote("; "))) {
            int i = pair.indexOf("=");
            if (i == -1) {
                continue;
            }
            Cookie.Builder builder = new Cookie.Builder();
            if (domain != null) {
                builder = builder.domain(domain);
            }
            cookies.add(builder.path("/").name(pair.substring(0, i)).value(pair.substring(i + 1)).build());
        }
        return cookies;
    }

    public static String buildUrl(String url, Map<String, Object> queryString) {
        if (url == null) {
            url = Strings.EMPTY;
        }
        if (queryString == null) {
            return url;
        }

        Map<String, Object> query = (Map) decodeQueryString(url);
        query.putAll(queryString);
        int i = url.indexOf("?");
        if (i != -1) {
            url = url.substring(0, i);
        }
        StringBuilder sb = new StringBuilder(url);
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            sb.append(sb.length() == url.length() ? "?" : "&").append(encodeUrl(entry.getKey())).append("=").append(encodeUrl(val.toString()));
        }
        return sb.toString();
    }

    @SneakyThrows
    public static Map<String, String> decodeQueryString(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        if (Strings.isEmpty(url)) {
            return params;
        }

        int i = url.indexOf("?");
        if (i != -1) {
            url = url.substring(i + 1);
        }
        String[] pairs = url.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
            String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) : null;
            params.put(key, value);
        }
        return params;
    }

    public static Map<String, String> decodeHeader(String raw) {
        return decodeHeader(Arrays.toList(raw.split(Pattern.quote("\n"))));
    }

    @SneakyThrows
    public static Map<String, String> decodeHeader(List<String> pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(pairs)) {
            return map;
        }

        for (String pair : pairs) {
            int idx = pair.indexOf(Pattern.quote(":"));
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
            String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()).trim() : Strings.EMPTY;
            map.put(key, value);
        }
        return map;
    }

    @SneakyThrows
    public static String encodeUrl(String str) {
        if (Strings.isEmpty(str)) {
            return Strings.EMPTY;
        }

        return URLEncoder.encode(str, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    @SneakyThrows
    public static String decodeUrl(String str) {
        if (Strings.isEmpty(str)) {
            return Strings.EMPTY;
        }

        return URLDecoder.decode(str, StandardCharsets.UTF_8.name()).replace("%20", "+");
    }

    public static void saveRawCookie(@NonNull String url, @NonNull String cookie) {
        HttpUrl httpUrl = HttpUrl.get(url);
        COOKIES.saveFromResponse(httpUrl, decodeCookie(httpUrl, cookie));
    }

    @SneakyThrows
    static OkHttpClient createClient(long timeoutMillis, boolean enableCookie, Proxy proxy) {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{TRUST_MANAGER}, new SecureRandom());
        Authenticator authenticator = proxy instanceof AuthenticProxy ? ((AuthenticProxy) proxy).getAuthenticator() : Authenticator.NONE;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), TRUST_MANAGER).hostnameVerifier((s, sslSession) -> true)
                .connectionPool(POOL).retryOnConnectionFailure(true) //unexpected end of stream
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .proxy(proxy).proxyAuthenticator(authenticator);
        if (enableCookie) {
            builder.cookieJar(COOKIES);
        }
        return builder.build();
    }
    //endregion

    @Setter
    boolean enableLog = RxConfig.INSTANCE.getNet().isEnableLog();
    @Setter
    boolean cachingStream = true;
    long timeoutMillis;
    boolean enableCookie;
    AuthenticProxy proxy;
    //Not thread safe
    OkHttpClient client;
    HttpHeaders reqHeaders;
    ResponseContent resContent;

    public synchronized void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        client = null;
    }

    public synchronized void setEnableCookie(boolean enableCookie) {
        this.enableCookie = enableCookie;
        client = null;
    }

    public synchronized void setProxy(AuthenticProxy proxy) {
        this.proxy = proxy;
        client = null;
    }

    public HttpClient withUserAgent() {
        requestHeaders().set(HttpHeaderNames.USER_AGENT, RxConfig.INSTANCE.getNet().getUserAgent());
        return this;
    }

    public HttpClient withRequestCookie(String rawCookie) {
        requestHeaders().set(HttpHeaderNames.COOKIE, rawCookie);
        return this;
    }

    public HttpHeaders requestHeaders() {
        return requestHeaders(false);
    }

    public HttpHeaders requestHeaders(boolean readOnly) {
        if (reqHeaders == null) {
            if (readOnly) {
                return EmptyHttpHeaders.INSTANCE;
            }
            reqHeaders = new DefaultHttpHeaders();
        }
        return reqHeaders;
    }

    public HttpClient() {
        this(RxConfig.INSTANCE.getNet().getConnectTimeoutMillis());
    }

    public HttpClient(long timeoutMillis) {
        this(timeoutMillis, false);
    }

    public HttpClient(long timeoutMillis, boolean enableCookie) {
        this.timeoutMillis = timeoutMillis;
        this.enableCookie = enableCookie;
    }

    OkHttpClient getClient() {
        if (client == null) {
            client = createClient(timeoutMillis, enableCookie, proxy);
        }
        return client;
    }

    Request.Builder createRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : requestHeaders()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    @SneakyThrows
    private synchronized ResponseContent invoke(String url, HttpMethod method, RequestContent content) {
        ProceedEventArgs args = new ProceedEventArgs(this.getClass(), new Object[]{method.toString(), content.toString()}, false);
        try {
            Request.Builder request = createRequest(url);
            RequestBody requestBody = content.toBody();
            if (HttpMethod.GET.equals(method)) {
                request.get();
            } else if (HttpMethod.POST.equals(method)) {
                request.post(requestBody);
            } else if (HttpMethod.HEAD.equals(method)) {
                request.head();
            } else if (HttpMethod.PUT.equals(method)) {
                request.put(requestBody);
            } else if (HttpMethod.PATCH.equals(method)) {
                request.patch(requestBody);
            } else if (HttpMethod.DELETE.equals(method)) {
                request.delete(requestBody);
            } else {
                throw new UnsupportedOperationException();
            }
            if (resContent != null) {
                resContent.response.close();
            }
            return resContent = args.proceed(() -> {
                ResponseContent rc = new ResponseContent(getClient().newCall(request.build()).execute());
                rc.cachingStream = cachingStream;
                return rc;
            });
        } catch (Throwable e) {
            args.setError(e);
            throw e;
        } finally {
            if (enableLog) {
                logHttp(args, url);
            }
        }
    }

    public ResponseContent head(@NonNull String url) {
        return invoke(url, HttpMethod.HEAD, RequestContent.NONE);
    }

    public ResponseContent get(@NonNull String url) {
        return invoke(url, HttpMethod.GET, RequestContent.NONE);
    }

    public ResponseContent post(String url, Map<String, Object> forms) {
        return post(url, forms, Collections.emptyMap());
    }

    public ResponseContent post(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.POST, new FormContent(forms, files, requestHeaders(true)));
    }

    public ResponseContent postJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.POST, new JsonContent(json, requestHeaders(true)));
    }

    public ResponseContent put(String url, Map<String, Object> forms) {
        return put(url, forms, Collections.emptyMap());
    }

    public ResponseContent put(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.PUT, new FormContent(forms, files, requestHeaders(true)));
    }

    public ResponseContent putJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PUT, new JsonContent(json, requestHeaders(true)));
    }

    public ResponseContent patch(String url, Map<String, Object> forms) {
        return patch(url, forms, Collections.emptyMap());
    }

    public ResponseContent patch(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.PATCH, new FormContent(forms, files, requestHeaders(true)));
    }

    public ResponseContent patchJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PATCH, new JsonContent(json, requestHeaders(true)));
    }

    public ResponseContent delete(String url, Map<String, Object> forms) {
        return delete(url, forms, Collections.emptyMap());
    }

    public ResponseContent delete(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.DELETE, new FormContent(forms, files, requestHeaders(true)));
    }

    public ResponseContent deleteJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.DELETE, new JsonContent(json, requestHeaders(true)));
    }

    public Tuple<RequestContent, ResponseContent> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        return forward(servletRequest, servletResponse, forwardUrl, null);
    }

    @SneakyThrows
    public synchronized Tuple<RequestContent, ResponseContent> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl, BiFunc<RequestContent, RequestContent> requestInterceptor) {
        HttpHeaders reqHeaders = requestHeaders();
        for (String n : Collections.list(servletRequest.getHeaderNames())) {
            if (Strings.equalsIgnoreCase(n, HttpHeaderNames.HOST)) {
                continue;
            }
            reqHeaders.set(n, servletRequest.getHeader(n));
        }

        String query = servletRequest.getQueryString();
        if (!Strings.isEmpty(query)) {
            forwardUrl += (forwardUrl.lastIndexOf("?") == -1 ? "?" : "&") + query;
        }
        log.info("Forward request: {}\nheaders: {}", forwardUrl, toJsonString(reqHeaders));
        RequestContent reqContent;
        String requestContentType = servletRequest.getContentType();
        ServletInputStream inStream = servletRequest.getInputStream();
        byte[] inBytes;
        if (inStream != null && (inBytes = IOStream.wrap(Strings.EMPTY, inStream).toArray()).length > 0) {
            reqContent = new RequestContent() {
                @Override
                public HttpHeaders getHeaders() {
                    return reqHeaders;
                }

                @Override
                public RequestBody toBody() {
                    return RequestBody.create(requestContentType != null ? MediaType.parse(requestContentType) : null, inBytes);
                }
            };
        } else {
            if (requestContentType != null) {
                if (Strings.startsWithIgnoreCase(requestContentType, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)) {
                    Map<String, Object> forms = Linq.from(Collections.list(servletRequest.getParameterNames())).toMap(p -> p, servletRequest::getParameter);
                    reqContent = new FormContent(forms, Collections.emptyMap(), reqHeaders);
                } else if (Strings.startsWithIgnoreCase(requestContentType, "multipart/")) {
                    Map<String, IOStream> files = Linq.from(servletRequest.getParts()).where(p -> p.getContentType() != null).toMap(Part::getName, p -> IOStream.wrap(p.getSubmittedFileName(), p.getInputStream()));
                    Map<String, Object> forms = Linq.from(Collections.list(servletRequest.getParameterNames())).toMap(p -> p, servletRequest::getParameter);
                    reqContent = new FormContent(forms, files, reqHeaders);
                } else {
//                    throw new InvalidException("Not support {}", contentType);
                    log.warn("Not support {} {}", servletRequest, requestContentType);
                    reqContent = new EmptyContent(MediaType.parse(requestContentType));
                }
            } else {
                reqContent = RequestContent.NONE;
            }
        }
        if (requestInterceptor != null) {
            reqContent = requestInterceptor.invoke(reqContent);
        }

        //todo get request body
//        boolean isGet = Strings.equalsIgnoreCase(servletRequest.getMethod(), HttpMethod.GET.name());
//        ResponseContent resContent = new ResponseContent(getClient().newCall(createRequest(forwardUrl).method(servletRequest.getMethod(), isGet ? null : reqContent.toBody()).build()).execute());
        ResponseContent resContent = new ResponseContent(getClient().newCall(createRequest(forwardUrl).method(servletRequest.getMethod(), reqContent.toBody()).build()).execute());
        resContent.cachingStream = cachingStream;
        servletResponse.setStatus(resContent.response.code());
        for (Pair<? extends String, ? extends String> header : resContent.responseHeaders()) {
            servletResponse.setHeader(header.getFirst(), header.getSecond());
        }

        ResponseBody responseBody = resContent.response.body();
        boolean hasResBody = responseBody != null;
        log.info("Forward response: {}\nheaders: {} hasBody: {}", resContent.getResponseUrl(), toJsonString(resContent.responseHeaders()), hasResBody);
        if (hasResBody) {
            MediaType responseContentType = responseBody.contentType();
            if (responseContentType != null) {
                servletResponse.setContentType(responseContentType.toString());
            }
            servletResponse.setContentLength((int) responseBody.contentLength());
            ServletOutputStream out = servletResponse.getOutputStream();
            resContent.handle(in -> {
                IOStream.copy(in, IOStream.NON_READ_FULLY, out);
                return null;
            });
        }
        return Tuple.of(reqContent, resContent);
    }
}
