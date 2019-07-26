package org.rx.socks.http;

import com.google.common.base.Strings;
import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.SneakyThrows;
import okhttp3.*;
import okhttp3.Authenticator;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rx.common.Contract;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.io.MemoryStream;
import org.rx.io.IOStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.rx.common.Contract.*;
import static org.rx.common.Contract.require;

public class HttpClient {
    public static final String GetMethod = "GET", PostMethod = "POST", HeadMethod = "HEAD";
    public static final String IE_UserAgent = "Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko";
    public static final String Chrome_UserAgent = "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 5 Build/LMY48B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/43.0.2357.65 Mobile Safari/537.36";
    public static final CookieContainer CookieContainer;
    private static final ConnectionPool pool;
    private static final MediaType FormType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"),
            JsonType = MediaType.parse("application/json; charset=utf-8");

    static {
        System.setProperty("jsse.enableSNIExtension", "false");
        CookieContainer = new CookieContainer();
        pool = new ConnectionPool();
    }

    //region StaticMembers
    public static void saveRawCookies(String url, String raw) {
        require(url, raw);

        HttpUrl httpUrl = HttpUrl.get(url);
        CookieContainer.saveFromResponse(httpUrl, parseRawCookie(httpUrl, raw));
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
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), Contract.Utf8) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), Contract.Utf8).trim()
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

        return URLEncoder.encode(str, Contract.Utf8).replace("+", "%20");
    }

    @SneakyThrows
    public static String decodeUrl(String str) {
        if (Strings.isNullOrEmpty(str)) {
            return "";
        }

        return URLDecoder.decode(str, Contract.Utf8).replace("%20", "+");
    }

    @SneakyThrows
    public static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (queryString == null) {
            return params;
        }
        if (queryString.startsWith("?")) {
            queryString = queryString.substring(1);
        }

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), Contract.Utf8) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), Contract.Utf8)
                    : null;
            params.put(key, value);
        }
        return params;
    }

    public static String buildQueryString(String baseUrl, Map<String, Object> params) {
        if (params == null) {
            return baseUrl;
        }
        if (baseUrl == null) {
            baseUrl = "";
        }

        String c = baseUrl.lastIndexOf("?") == -1 ? "?" : "&";
        StringBuilder url = new StringBuilder(baseUrl);
        for (String key : params.keySet()) {
            Object val = params.get(key);
            if (val == null) {
                continue;
            }
            url.append(url.length() == baseUrl.length() ? c : "&")
                    .append(encodeUrl(key)).append("=").append(encodeUrl(val.toString()));
        }
        return url.toString();
    }
    //endregion

    private Headers headers;
    private OkHttpClient client;
    private Response response;

    public void setHeaders(Map<String, String> headers) {
        require(headers);

        this.headers = this.headers.newBuilder().addAll(Headers.of(headers)).build();
    }

    private Response getResponse() {
        if (response == null) {
            throw new InvalidOperationException("No response");
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
        this(30 * 1000, null, null);
    }

    /**
     * @param millis
     * @param proxy  new Proxy(Proxy.Type.HTTP, Sockets.parseAddress("127.0.0.1:8888"))
     */
    public HttpClient(int millis, String rawCookie, Proxy proxy) {
        Headers.Builder builder = new Headers.Builder().set("user-agent", IE_UserAgent);
        boolean cookieJar = StringUtils.isEmpty(rawCookie);
        if (!cookieJar) {
            builder = builder.set("cookie", rawCookie);
        }
        headers = builder.build();
        client = createClient(millis, cookieJar, proxy);
    }

    @SneakyThrows
    private OkHttpClient createClient(int millis, boolean cookieJar, Proxy proxy) {
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
        Authenticator authenticator = proxy instanceof ProxyWithAuth ? ((ProxyWithAuth) proxy).getAuthenticator() : Authenticator.NONE;
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(pool)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager).hostnameVerifier((s, sslSession) -> true)
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .proxy(proxy)
                .proxyAuthenticator(authenticator);
        if (cookieJar) {
            builder = builder.cookieJar(CookieContainer);
        }
        return builder.build();
    }

    private Request.Builder createRequest(String url) {
        return new Request.Builder().url(url).headers(headers);
    }

    public Map<String, List<String>> head(String url) {
        handleString(invoke(url, HttpClient.HeadMethod, null, null));

        return responseHeaders();
    }

    public String get(String url) {
        require(url);

        return handleString(invoke(url, HttpClient.GetMethod, null, null));
    }

    public MemoryStream getStream(String url) {
        require(url);

        return handleStream(invoke(url, HttpClient.GetMethod, null, null));
    }

    public File getFile(String url, String filePath) {
        require(url, filePath);

        return handleFile(invoke(url, HttpClient.GetMethod, null, null), filePath);
    }

    public String post(String url, Map<String, Object> formData) {
        require(url, formData);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleString(invoke(url, HttpClient.PostMethod, FormType, dataString));
    }

    public String post(String url, Object json) {
        require(url, json);

        return handleString(invoke(url, HttpClient.PostMethod, JsonType, toJsonString(json)));
    }

    public MemoryStream postStream(String url, Map<String, Object> formData) {
        require(url, formData);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleStream(invoke(url, HttpClient.PostMethod, FormType, dataString));
    }

    public File postFile(String url, Map<String, Object> formData, String filePath) {
        require(url, formData, filePath);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleFile(invoke(url, HttpClient.PostMethod, FormType, dataString), filePath);
    }

    public File postFile(String url, Object json, String filePath) {
        require(url, json, filePath);

        return handleFile(invoke(url, HttpClient.PostMethod, JsonType, toJsonString(json)), filePath);
    }

    @SneakyThrows
    private ResponseBody invoke(String url, String method, MediaType contentType, String content) {
        Request.Builder request = createRequest(url);
        RequestBody requestBody;
        switch (method) {
            case HttpClient.PostMethod:
                requestBody = RequestBody.create(contentType, content);
                request = request.post(requestBody);
                break;
            default:
                request = request.get();
                break;
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
}
