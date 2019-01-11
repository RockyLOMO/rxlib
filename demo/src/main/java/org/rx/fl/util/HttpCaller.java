package org.rx.fl.util;

import com.google.common.base.Strings;
import lombok.SneakyThrows;
import okhttp3.*;
import org.rx.common.Contract;
import org.rx.common.NQuery;
import org.rx.io.IOStream;
import org.rx.socks.Sockets;
import org.rx.socks.http.HttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Proxy;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.toJsonString;

public final class HttpCaller {
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
    public static Map<String, String> parseOriginalHeader(String queryString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (queryString == null) {
            return map;
        }

        String[] pairs = queryString.split(Pattern.quote("\n"));
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

    private Headers headers;
    private OkHttpClient client;

    public void setHeaders(Map<String, String> headers) {
        require(headers);

        this.headers = this.headers.newBuilder().addAll(Headers.of(headers)).build();
    }

    public HttpCaller() {
        headers = new Headers.Builder()
                .set("User-Agent", IE_UserAgent).build();
        client = createClient();
    }

    private OkHttpClient createClient() {
//        Proxy proxy = new Proxy(Proxy.Type.HTTP, Sockets.parseAddress("127.0.0.1:8888"));
        return new OkHttpClient.Builder().connectionPool(pool).cookieJar(CookieContainer).retryOnConnectionFailure(false)
//                .proxy(proxy)
                .build();
    }

    private Request.Builder createRequest(String url) {
        return new Request.Builder().url(url).headers(headers);
    }

    public String get(String url) {
        require(url);

        return handleString(invoke(url, HttpClient.GetMethod, null, null));
    }

    public File getDownload(String url, String filePath) {
        require(url, filePath);

        return handleFile(invoke(url, HttpClient.GetMethod, null, null), filePath);
    }

    public String post(String url, Map<String, String> formData) {
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

    public File postDownloadFile(String url, Map<String, String> formData, String filePath) {
        require(url, formData, filePath);

        String dataString = HttpClient.buildQueryString("", formData);
        if (!Strings.isNullOrEmpty(dataString)) {
            dataString = dataString.substring(1);
        }
        return handleFile(invoke(url, HttpClient.PostMethod, FormType, dataString), filePath);
    }

    public File postDownloadFile(String url, Object json, String filePath) {
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
        Response response = client.newCall(request.build()).execute();
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

    @SneakyThrows
    private String handleString(ResponseBody body) {
        return body == null ? "" : body.string();
    }
}
