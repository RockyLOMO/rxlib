package org.rx.net.http;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import kotlin.Pair;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.Authenticator;
import okio.BufferedSink;
import org.apache.commons.collections4.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rx.core.App;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.util.function.BiAction;
import org.springframework.http.HttpMethod;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.rx.core.App.*;

@Slf4j
public class HttpClient {
    interface RequestContent {
        RequestContent NONE = new RequestContent() {
            @Override
            public RequestBody toBody() {
                return RequestBody.create(FORM_TYPE, "");
            }
        };

        RequestBody toBody();
    }

    @RequiredArgsConstructor
    static class JsonContent implements RequestContent {
        final Object json;

        @Override
        public RequestBody toBody() {
            return RequestBody.create(JSON_TYPE, toJsonString(json));
        }
    }

    @RequiredArgsConstructor
    static class FormContent implements RequestContent {
        final Map<String, Object> forms;
        final Map<String, IOStream<?, ?>> files;

        @Override
        public RequestBody toBody() {
            if (MapUtils.isEmpty(files)) {
                String formString = buildQueryString(null, forms);
                if (!Strings.isNullOrEmpty(formString)) {
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
                for (Map.Entry<String, IOStream<?, ?>> entry : files.entrySet()) {
                    IOStream<?, ?> stream = entry.getValue();
                    if (stream == null) {
                        continue;
                    }
                    builder.addFormDataPart(entry.getKey(), stream.getName(), new RequestBody() {
                        @Nullable
                        @Override
                        public MediaType contentType() {
                            return MediaType.parse(Files.getMediaTypeFromName(stream.getName()));
                        }

                        @Override
                        public void writeTo(@NotNull BufferedSink bufferedSink) throws IOException {
                            stream.read(bufferedSink.outputStream());
                        }
                    });
                }
            }
            return builder.build();
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PROTECTED)
    public static class ResponseContent {
        @Getter
        private final Response response;
        private String string;
        private HybridStream stream;
        private File file;

        public String getResponseUrl() {
            return response.request().url().toString();
        }

        public Headers getHeaders() {
            return response.headers();
        }

        @SneakyThrows
        public synchronized void handle(BiAction<InputStream> action) {
            ResponseBody body = response.body();
            if (body == null) {
                return;
            }
            try {
                action.invoke(body.byteStream());
            } finally {
                body.close();
            }
        }

        public synchronized File asFile(String filePath) {
            if (file == null) {
                ResponseBody body = response.body();
                if (body == null) {
                    return new File(filePath);
                }
                try {
                    Files.saveFile(filePath, body.byteStream());
                    file = new File(filePath);
                } finally {
                    body.close();
                }
            }
            return file;
        }

        public synchronized HybridStream asStream() {
            if (stream == null) {
                stream = new HybridStream();
                ResponseBody body = response.body();
                if (body == null) {
                    return stream;
                }
                try {
                    stream.write(body.byteStream());
                    stream.setPosition(0);
                } finally {
                    body.close();
                }
            }
            return stream;
        }

        @SneakyThrows
        public synchronized String asString() {
            if (string == null) {
                ResponseBody body = response.body();
                if (body == null) {
                    return string = "";
                }
                try {
                    string = body.string();
                } finally {
                    body.close();
                }
            }
            return string;
        }
    }

    public static final CookieContainer COOKIE_CONTAINER = new CookieContainer();
    private static final ConnectionPool POOL = new ConnectionPool(App.getConfig().getNetMaxPoolSize(), App.getConfig().getNetKeepaliveSeconds(), TimeUnit.SECONDS);
    private static final MediaType FORM_TYPE = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"), JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    static {
//        System.setProperty("https.protocols", "TLSv1.2,TLSv1.1,TLSv1,SSLv3,SSLv2Hello");
//        System.setProperty("jsse.enableSNIExtension", "false");
    }

    //region StaticMembers
    public static String godaddyDns(String ssoKey, String domain, String name) {
        return godaddyDns(ssoKey, domain, name, getWanIp());
    }

    public static String godaddyDns(String ssoKey, String domain, String name, String ip) {
        String url = String.format("https://api.godaddy.com/v1/domains/%s/records/A/%s", domain, name);
        HttpClient client = new HttpClient();
        client.getHeaders().add("Authorization", "sso-key " + ssoKey);
        return client.putJson(url, String.format("[\n" +
                "  {\n" +
                "    \"data\": \"%s\",\n" +
                "    \"ttl\": 600\n" +
                "  }\n" +
                "]", ip)).asString();
    }

    public static String getWanIp() {
        HttpClient client = new HttpClient();
        return client.get("https://api.ipify.org").asString();
    }

    public static void saveRawCookies(@NonNull String url, @NonNull String raw) {
        HttpUrl httpUrl = HttpUrl.get(url);
        COOKIE_CONTAINER.saveFromResponse(httpUrl, parseRawCookie(httpUrl, raw));
    }

    public static String toRawCookie(List<Cookie> cookies) {
        if (cookies == null) {
            return "";
        }

        return String.join("; ", NQuery.of(cookies).select(p -> p.name() + "=" + p.value()));
    }

    @SneakyThrows
    public static List<Cookie> parseRawCookie(@NonNull HttpUrl httpUrl, @NonNull String raw) {
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

    @SneakyThrows
    public static Map<String, String> parseOriginalHeader(String raw) {
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
                    : "";
            map.put(key, value);
        }
        return map;
    }

    @SneakyThrows
    public static String encodeUrl(String str) {
        if (Strings.isNullOrEmpty(str)) {
            return "";
        }

        return URLEncoder.encode(str, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    @SneakyThrows
    public static String decodeUrl(String str) {
        if (Strings.isNullOrEmpty(str)) {
            return "";
        }

        return URLDecoder.decode(str, StandardCharsets.UTF_8.name()).replace("%20", "+");
    }

    @SneakyThrows
    public static Map<String, String> parseQueryString(String url) {
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

    public static String buildQueryString(String url, Map<String, Object> params) {
        if (url == null) {
            url = "";
        }
        if (params == null) {
            return url;
        }

        Map<String, Object> query = (Map) parseQueryString(url);
        query.putAll(params);
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
    private static OkHttpClient createClient(int timeoutMillis, boolean cookieJar, Proxy proxy) {
        X509TrustManager trustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        Authenticator authenticator = proxy instanceof AuthenticProxy ? ((AuthenticProxy) proxy).getAuthenticator() : Authenticator.NONE;
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(POOL)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager).hostnameVerifier((s, sslSession) -> true)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
//                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .proxy(proxy)
                .proxyAuthenticator(authenticator);
        if (cookieJar) {
            builder = builder.cookieJar(COOKIE_CONTAINER);
        }
        return builder.build();
    }
    //endregion

    //不是线程安全
    private final OkHttpClient client;
    @Getter
    private final HttpHeaders headers = new DefaultHttpHeaders();
    private ResponseContent responseContent;

    public HttpClient() {
        this(App.getConfig().getNetTimeoutMillis(), null, null);
    }

    public HttpClient(int timeoutMillis, String rawCookie, Proxy proxy) {
        headers.set(HttpHeaderNames.USER_AGENT, App.getConfig().getNetUserAgent());
        boolean cookieJar = Strings.isEmpty(rawCookie);
        if (!cookieJar) {
            headers.set(HttpHeaderNames.COOKIE, rawCookie);
        }
        client = createClient(timeoutMillis, cookieJar, proxy);
    }

    private Request.Builder createRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    @SneakyThrows
    private ResponseContent invoke(String url, HttpMethod method, RequestContent content) {
        Request.Builder request = createRequest(url);
        RequestBody requestBody = content.toBody();
        switch (method) {
            case POST:
                request = request.post(requestBody);
                break;
            case HEAD:
                request = request.head();
                break;
            case PUT:
                request = request.put(requestBody);
                break;
            case PATCH:
                request = request.patch(requestBody);
                break;
            case DELETE:
                request = request.delete(requestBody);
                break;
            default:
                request = request.get();
                break;
        }
        if (responseContent != null) {
            responseContent.response.close();
        }
        return responseContent = new ResponseContent(client.newCall(request.build()).execute());
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

    public ResponseContent post(@NonNull String url, Map<String, Object> forms, Map<String, IOStream<?, ?>> files) {
        return invoke(url, HttpMethod.POST, new FormContent(forms, files));
    }

    public ResponseContent postJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.POST, new JsonContent(json));
    }

    public ResponseContent put(String url, Map<String, Object> forms) {
        return put(url, forms, Collections.emptyMap());
    }

    public ResponseContent put(@NonNull String url, Map<String, Object> forms, Map<String, IOStream<?, ?>> files) {
        return invoke(url, HttpMethod.PUT, new FormContent(forms, files));
    }

    public ResponseContent putJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PUT, new JsonContent(json));
    }

    public ResponseContent patch(String url, Map<String, Object> forms) {
        return patch(url, forms, Collections.emptyMap());
    }

    public ResponseContent patch(@NonNull String url, Map<String, Object> forms, Map<String, IOStream<?, ?>> files) {
        return invoke(url, HttpMethod.PATCH, new FormContent(forms, files));
    }

    public ResponseContent patchJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PATCH, new JsonContent(json));
    }

    public ResponseContent delete(String url, Map<String, Object> forms) {
        return delete(url, forms, Collections.emptyMap());
    }

    public ResponseContent delete(@NonNull String url, Map<String, Object> forms, Map<String, IOStream<?, ?>> files) {
        return invoke(url, HttpMethod.DELETE, new FormContent(forms, files));
    }

    public ResponseContent deleteJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.DELETE, new JsonContent(json));
    }

    @SneakyThrows
    public void forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        for (String n : Collections.list(servletRequest.getHeaderNames())) {
            if ("host".equals(n)) {
                continue;
            }
            getHeaders().set(n, servletRequest.getHeader(n));
        }

        String query = servletRequest.getQueryString();
        if (!Strings.isEmpty(query)) {
            forwardUrl += (forwardUrl.lastIndexOf("?") == -1 ? "?" : "&") + query;
        }
        log.info("Forward request: {}\nheaders {}", forwardUrl, toJsonString(headers));
        Request.Builder request = createRequest(forwardUrl);
        RequestBody requestBody = null;
        if (!servletRequest.getMethod().equalsIgnoreCase(HttpMethod.GET.name())) {
            ServletInputStream inStream = servletRequest.getInputStream();
            if (inStream != null) {
                if (servletRequest.getContentType() != null) {
                    requestBody = RequestBody.create(IOStream.wrap("", inStream).toArray(), MediaType.parse(servletRequest.getContentType()));
                } else {
                    requestBody = RequestBody.create(IOStream.wrap("", inStream).toArray());
                }
            }
        }
        Response response = client.newCall(request.method(servletRequest.getMethod(), requestBody).build()).execute();
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
