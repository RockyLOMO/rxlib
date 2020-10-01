package org.rx.net.http;

import com.google.common.net.HttpHeaders;
import kotlin.Pair;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.Authenticator;
import org.apache.commons.io.IOUtils;
import org.rx.core.Contract;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.core.exception.InvalidException;
import org.rx.io.MemoryStream;
import org.rx.io.IOStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.rx.core.Contract.*;

@Slf4j
public class HttpClient {
    public static final String GET_METHOD = "GET", POST_METHOD = "POST", HEAD_METHOD = "HEAD";
    public static final CookieContainer COOKIE_CONTAINER = new CookieContainer();
    private static final ConnectionPool POOL = new ConnectionPool(CONFIG.getNetMaxPoolSize(), CONFIG.getCacheExpireMinutes(), TimeUnit.MINUTES);
    private static final MediaType FORM_TYPE = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"), JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    static {
//        System.setProperty("https.protocols", "TLSv1.2,TLSv1.1,TLSv1,SSLv3,SSLv2");//SSLv2Hello
//        System.setProperty("jsse.enableSNIExtension", "false");
    }

    //region StaticMembers
    public static void saveRawCookies(String url, String raw) {
        require(url, raw);

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
    public static List<Cookie> parseRawCookie(HttpUrl httpUrl, String raw) {
        require(httpUrl, raw);

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
//            if (httpUrl.isHttps()) {
//                builder = builder.secure();
//            }
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
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), Contract.UTF_8) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), Contract.UTF_8).trim()
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

        return URLEncoder.encode(str, Contract.UTF_8).replace("+", "%20");
    }

    @SneakyThrows
    public static String decodeUrl(String str) {
        if (Strings.isNullOrEmpty(str)) {
            return "";
        }

        return URLDecoder.decode(str, Contract.UTF_8).replace("%20", "+");
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
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), Contract.UTF_8) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), Contract.UTF_8)
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
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        Authenticator authenticator = proxy instanceof AuthenticProxy ? ((AuthenticProxy) proxy).getAuthenticator() : Authenticator.NONE;
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(POOL)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager).hostnameVerifier((s, sslSession) -> true)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .proxy(proxy)
                .proxyAuthenticator(authenticator);
        if (cookieJar) {
            builder = builder.cookieJar(COOKIE_CONTAINER);
        }
        return builder.build();
    }
    //endregion

    private final OkHttpClient client;
    private Headers headers;
    private Response response;

    public String cookie(String rawCookie) {
        String ua = headers.get(HttpHeaders.COOKIE);
        setRequestHeader(HttpHeaders.COOKIE, rawCookie);
        return ua;
    }

    public String userAgent(String userAgent) {
        String ua = headers.get(HttpHeaders.USER_AGENT);
        setRequestHeader(HttpHeaders.USER_AGENT, userAgent);
        return ua;
    }

    public void setRequestHeader(String name, String value) {
        setRequestHeaders(Collections.singletonMap(name, value));
    }

    public void setRequestHeaders(Map<String, String> headers) {
        require(headers);

        Headers.Builder builder = this.headers.newBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.set(entry.getKey(), entry.getValue());
        }
        this.headers = builder.build();
    }

    private Response getResponse() {
        if (response == null) {
            throw new InvalidException("No response");
        }
        return response;
    }

    public String responseUrl() {
        return getResponse().request().url().toString();
    }

    public Map<String, List<String>> responseHeaders() {
        return getResponse().headers().toMultimap();
    }

    public HttpClient() {
        this(CONFIG.getNetTimeoutMillis(), null, null);
    }

    public HttpClient(int timeoutMillis, String rawCookie, Proxy proxy) {
        Headers.Builder builder = new Headers.Builder().set(HttpHeaders.USER_AGENT, CONFIG.getNetUserAgent());
        boolean cookieJar = Strings.isEmpty(rawCookie);
        if (!cookieJar) {
            builder = builder.set(HttpHeaders.COOKIE, rawCookie);
        }
        headers = builder.build();
        client = org.rx.core.Cache.getOrSet(cacheKey("HC", timeoutMillis, rawCookie, proxy), k -> createClient(timeoutMillis, cookieJar, proxy));
    }

    private Request.Builder createRequest(String url) {
        return new Request.Builder().url(url).headers(headers);
    }

    public Map<String, List<String>> head(String url) {
        handleString(invoke(url, HttpClient.HEAD_METHOD, null, null));

        return responseHeaders();
    }

    public String get(String url) {
        require(url);

        return handleString(invoke(url, HttpClient.GET_METHOD, null, null));
    }

    public MemoryStream getStream(String url) {
        require(url);

        return handleStream(invoke(url, HttpClient.GET_METHOD, null, null));
    }

    public File getFile(String url, String filePath) {
        require(url, filePath);

        return handleFile(invoke(url, HttpClient.GET_METHOD, null, null), filePath);
    }

    public String post(String url, Map<String, Object> formData) {
        require(url, formData);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleString(invoke(url, HttpClient.POST_METHOD, FORM_TYPE, dataString));
    }

    public String post(String url, Object json) {
        require(url, json);

        return handleString(invoke(url, HttpClient.POST_METHOD, JSON_TYPE, toJsonString(json)));
    }

    public MemoryStream postStream(String url, Map<String, Object> formData) {
        require(url, formData);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleStream(invoke(url, HttpClient.POST_METHOD, FORM_TYPE, dataString));
    }

    public File postFile(String url, Map<String, Object> formData, String filePath) {
        require(url, formData, filePath);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleFile(invoke(url, HttpClient.POST_METHOD, FORM_TYPE, dataString), filePath);
    }

    public File postFile(String url, Object json, String filePath) {
        require(url, json, filePath);

        return handleFile(invoke(url, HttpClient.POST_METHOD, JSON_TYPE, toJsonString(json)), filePath);
    }

    @SneakyThrows
    private ResponseBody invoke(String url, String method, MediaType contentType, String content) {
        Request.Builder request = createRequest(url);
        RequestBody requestBody;
        switch (method) {
            case HttpClient.POST_METHOD:
                requestBody = RequestBody.create(content, contentType);
                request = request.post(requestBody);
                break;
            default:
                request = request.get();
                break;
        }
        if (response != null) {
            response.close();
        }
        response = client.newCall(request.build()).execute();
        return response.body();
    }

    @SneakyThrows
    private File handleFile(ResponseBody body, String filePath) {
        File file = new File(filePath);
        if (body != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                IOStream.copyTo(body.byteStream(), out);
            }
        }
        return file;
    }

    private MemoryStream handleStream(ResponseBody body) {
        MemoryStream stream = new MemoryStream();
        if (body == null) {
            return stream;
        }
        IOStream.copyTo(body.byteStream(), stream.getWriter());
        stream.setPosition(0);
        return stream;
    }

    @SneakyThrows
    private String handleString(ResponseBody body) {
        return body == null ? "" : body.string();
    }

    @SneakyThrows
    public void forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        Map<String, String> headers = NQuery.of(Collections.list(servletRequest.getHeaderNames())).toMap(p -> p, servletRequest::getHeader);
        headers.remove("host");
        setRequestHeaders(headers);

        String query = servletRequest.getQueryString();
        if (!Strings.isEmpty(query)) {
            forwardUrl += (forwardUrl.lastIndexOf("?") == -1 ? "?" : "&") + query;
        }
        log.info("Forward request: {}\nheaders {}", forwardUrl, toJsonString(headers));
        Request.Builder request = createRequest(forwardUrl);
        RequestBody requestBody = null;
        if (!servletRequest.getMethod().equalsIgnoreCase(GET_METHOD)) {
            ServletInputStream inStream = servletRequest.getInputStream();
            if (inStream != null) {
                if (servletRequest.getContentType() != null) {
                    requestBody = RequestBody.create(IOUtils.toByteArray(inStream), MediaType.parse(servletRequest.getContentType()));
                } else {
                    requestBody = RequestBody.create(IOUtils.toByteArray(inStream));
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
            IOUtils.copy(in, out);
        }
    }
}
