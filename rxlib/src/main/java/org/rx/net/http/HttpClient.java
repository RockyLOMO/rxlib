package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import kotlin.Pair;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSink;
import org.apache.commons.collections4.MapUtils;
import org.rx.bean.ProceedEventArgs;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.util.Lazy;
import org.rx.util.function.BiAction;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
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
    interface RequestContent {
        RequestContent NONE = new RequestContent() {
            @Override
            public RequestBody toBody() {
                return RequestBody.create(FORM_TYPE, Strings.EMPTY);
            }

            @Override
            public String toString() {
                return Strings.EMPTY;
            }
        };

        RequestBody toBody();
    }

    @RequiredArgsConstructor
    static class JsonContent implements RequestContent {
        @Getter(value = AccessLevel.PRIVATE)
        final Object json;
        final Lazy<String> body = new Lazy<>(() -> toJsonString(getJson()));

        @Override
        public RequestBody toBody() {
            return RequestBody.create(JSON_TYPE, body.getValue());
        }

        @Override
        public String toString() {
            return body.getValue();
        }
    }

    @RequiredArgsConstructor
    static class FormContent implements RequestContent {
        final Map<String, Object> forms;
        final Map<String, IOStream> files;

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
            return "FormContent{" +
                    "forms=" + forms +
                    ", files=" + files +
                    '}';
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PROTECTED)
    public static class ResponseContent {
        @Getter
        @JSONField(serialize = false)
        final Response response;
        HybridStream stream;
        File file;
        String string;

        public String getResponseUrl() {
            return response.request().url().toString();
        }

        public String getResponseText() {
            return toString();
        }

        @JSONField(serialize = false)
        public Headers getHeaders() {
            return response.headers();
        }

        @JSONField(serialize = false)
        public Charset getCharset() {
            return Reflects.invokeMethod(response.body(), "charset");
        }

        @SneakyThrows
        public synchronized void handle(BiAction<InputStream> action) {
            if (stream != null) {
                action.invoke(toStream().getReader());
                return;
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new InvalidException("Empty response from url {}", getResponseUrl());
            }
            try {
                action.invoke(body.byteStream());
            } finally {
                body.close();
            }
        }

        public synchronized HybridStream toStream() {
            if (stream == null) {
                //tmp 4 handle()
                HybridStream tmp = new HybridStream();
                handle(tmp::write);
                stream = tmp;
            }
            stream.setPosition(0);
            return stream;
        }

        public synchronized File toFile(String filePath) {
            if (file == null) {
                handle(in -> Files.saveFile(filePath, in));
                file = new File(filePath);
            }
            return file;
        }

        public synchronized String toString() {
            toStream();
            if (string == null) {
                handle(in -> string = IOStream.readString(in, getCharset()));
            }
            return string;
        }

        public <T extends Serializable> T toJson() {
            return (T) JSON.parse(toString());
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
            sb.append(sb.length() == url.length() ? "?" : "&")
                    .append(encodeUrl(entry.getKey())).append("=").append(encodeUrl(val.toString()));
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
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                    : null;
            params.put(key, value);
        }
        return params;
    }

    @SneakyThrows
    public static Map<String, String> decodeHeader(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }

        String[] pairs = raw.split(Pattern.quote("\n"));
        for (String pair : pairs) {
            int idx = pair.indexOf(Pattern.quote(":"));
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()).trim()
                    : Strings.EMPTY;
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
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(POOL)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(sslContext.getSocketFactory(), TRUST_MANAGER).hostnameVerifier((s, sslSession) -> true)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .proxy(proxy)
                .proxyAuthenticator(authenticator);
        if (enableCookie) {
            builder = builder.cookieJar(COOKIES);
        }
        return builder.build();
    }
    //endregion

    @Getter
    final HttpHeaders requestHeaders = new DefaultHttpHeaders();
    @Setter
    boolean enableLog = RxConfig.INSTANCE.getNet().isEnableLog();
    long timeoutMillis;
    boolean enableCookie;
    AuthenticProxy proxy;
    //Not thread safe
    OkHttpClient client;
    ResponseContent responseContent;

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

    OkHttpClient getClient() {
        if (client == null) {
            client = createClient(timeoutMillis, enableCookie, proxy);
        }
        return client;
    }

    public HttpClient() {
        this(RxConfig.INSTANCE.getNet().getConnectTimeoutMillis());
    }

    public HttpClient(long timeoutMillis) {
        this(timeoutMillis, false, null);
    }

    public HttpClient(long timeoutMillis, String rawCookie) {
        this(timeoutMillis, false, rawCookie);
    }

    public HttpClient(long timeoutMillis, boolean enableCookie, String rawCookie) {
        this.timeoutMillis = timeoutMillis;
        this.enableCookie = enableCookie;
        requestHeaders.set(HttpHeaderNames.USER_AGENT, RxConfig.INSTANCE.getNet().getUserAgent());
        if (rawCookie != null) {
            requestHeaders.set(HttpHeaderNames.COOKIE, rawCookie);
        }
    }

    private Request.Builder createRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : requestHeaders) {
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
            if (responseContent != null) {
                responseContent.response.close();
            }
            return responseContent = args.proceed(() -> new ResponseContent(getClient().newCall(request.build()).execute()));
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
        return invoke(url, HttpMethod.POST, new FormContent(forms, files));
    }

    public ResponseContent postJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.POST, new JsonContent(json));
    }

    public ResponseContent put(String url, Map<String, Object> forms) {
        return put(url, forms, Collections.emptyMap());
    }

    public ResponseContent put(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.PUT, new FormContent(forms, files));
    }

    public ResponseContent putJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PUT, new JsonContent(json));
    }

    public ResponseContent patch(String url, Map<String, Object> forms) {
        return patch(url, forms, Collections.emptyMap());
    }

    public ResponseContent patch(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.PATCH, new FormContent(forms, files));
    }

    public ResponseContent patchJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PATCH, new JsonContent(json));
    }

    public ResponseContent delete(String url, Map<String, Object> forms) {
        return delete(url, forms, Collections.emptyMap());
    }

    public ResponseContent delete(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        return invoke(url, HttpMethod.DELETE, new FormContent(forms, files));
    }

    public ResponseContent deleteJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.DELETE, new JsonContent(json));
    }

    @SneakyThrows
    public synchronized void forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        for (String n : Collections.list(servletRequest.getHeaderNames())) {
            if ("host".equals(n)) {
                continue;
            }
            getRequestHeaders().set(n, servletRequest.getHeader(n));
        }

        String query = servletRequest.getQueryString();
        if (!Strings.isEmpty(query)) {
            forwardUrl += (forwardUrl.lastIndexOf("?") == -1 ? "?" : "&") + query;
        }
        log.info("Forward request: {}\nheaders {}", forwardUrl, toJsonString(requestHeaders));
        Request.Builder request = createRequest(forwardUrl);
        RequestBody requestBody = null;
//        if (!servletRequest.getMethod().equalsIgnoreCase(HttpMethod.GET.name())) {
            ServletInputStream inStream = servletRequest.getInputStream();
            if (inStream != null) {
                if (servletRequest.getContentType() != null) {
                    requestBody = RequestBody.create(IOStream.wrap(Strings.EMPTY, inStream).toArray(), MediaType.parse(servletRequest.getContentType()));
                } else {
                    requestBody = RequestBody.create(IOStream.wrap(Strings.EMPTY, inStream).toArray());
                }
            }
//        }
        Response response = getClient().newCall(request.method(servletRequest.getMethod(), requestBody).build()).execute();
        servletResponse.setStatus(response.code());
        for (Pair<? extends String, ? extends String> header : response.headers()) {
            servletResponse.setHeader(header.getFirst(), header.getSecond());
        }

        ResponseBody responseBody = response.body();
        log.info("Forward response: hasBody={}", requestBody != null);
        if (responseBody != null) {
            if (responseBody.contentType() != null) {
                servletResponse.setContentType(responseBody.contentType().toString());
            }
            servletResponse.setContentLength((int) responseBody.contentLength());
            InputStream in = responseBody.byteStream();
            ServletOutputStream out = servletResponse.getOutputStream();
            IOStream.copy(in, IOStream.NON_READ_FULLY, out);
        }
    }
}
