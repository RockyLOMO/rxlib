package org.rx.fl.util;

import lombok.SneakyThrows;
import okhttp3.*;
import org.rx.Disposable;
import org.rx.bean.Const;
import org.rx.socks.HttpClient;

import java.net.URLDecoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.rx.Contract.require;
import static org.rx.Contract.toJsonString;

public class HttpCaller extends Disposable {
    public static class CookieContainer implements CookieJar {
        private ConcurrentMap<HttpUrl, List<Cookie>> cookies = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl httpUrl, List<Cookie> list) {
            cookies.put(httpUrl, list);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl httpUrl) {
            return cookies.getOrDefault(httpUrl, Collections.emptyList());
        }
    }

    private static final MediaType FormType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"),
            JsonType = MediaType.parse("application/json; charset=utf-8");
    public static final HttpCaller Instance = new HttpCaller();

    static {
        System.setProperty("jsse.enableSNIExtension", "false");
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
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), Const.Utf8) : pair;
            String value = idx > 0 && pair.length() > idx + 1
                    ? URLDecoder.decode(pair.substring(idx + 1), Const.Utf8).trim()
                    : "";
            map.put(key, value);
        }
        return map;
    }

    private final ConnectionPool pool;
    private Headers headers;
    private CookieContainer cookieContainer;
    private OkHttpClient client;

    public synchronized void setHeaders(Map<String, String> headers) {
        require(headers);

        this.headers = this.headers.newBuilder().addAll(Headers.of(headers)).build();
    }

    public HttpCaller() {
        pool = new ConnectionPool();
        headers = new Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 5 Build/LMY48B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/43.0.2357.65 Mobile Safari/537.36").build();
        cookieContainer = new CookieContainer();
        client = createClient();
    }

    @Override
    protected void freeUnmanaged() {

    }

    private OkHttpClient createClient() {
        return new OkHttpClient.Builder().connectionPool(pool).cookieJar(cookieContainer).retryOnConnectionFailure(false).build();
    }

    private Request.Builder createRequest(String url) {
        return new Request.Builder().url(url).headers(headers);
    }

    public String httpGet(String url) {
        return httpGet(url, null);
    }

    @SneakyThrows
    public String httpGet(String url, Consumer<Response> responseConsumer) {
        require(url);

        Response response = client.newCall(createRequest(url).build()).execute();
        if (responseConsumer != null) {
            responseConsumer.accept(response);
        }
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    @SneakyThrows
    public String httpPost(String url, Map<String, String> formData) {
        require(url, formData);

        Response response = client.newCall(createRequest(url)
                .post(RequestBody.create(FormType, HttpClient.buildQueryString("", formData).substring(1))).build()).execute();
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    @SneakyThrows
    public String httpPost(String url, Object json) {
        require(url, json);

        Response response = client.newCall(createRequest(url)
                .post(RequestBody.create(JsonType, toJsonString(json))).build()).execute();
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }
}
