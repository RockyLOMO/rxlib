package org.rx.socket;

import com.alibaba.fastjson.JSON;
import org.rx.App;
import org.rx.SystemException;

import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.rx.Contract.isNull;

/**
 * http://www.jianshu.com/p/aa3f066263ed
 */
public class HttpClient {
    //region StaticMembers
    public static String urlEncode(String val) {
        try {
            return URLEncoder.encode(val, App.UTF8);
        } catch (UnsupportedEncodingException ex) {
            throw new SystemException(ex);
        }
    }

    public static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (queryString == null) {
            return map;
        }

        String[] pairs = queryString.split("&");
        try {
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), App.UTF8) : pair;
                String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), App.UTF8)
                        : null;
                map.put(key, value);
            }
        } catch (UnsupportedEncodingException ex) {
            throw new SystemException(ex);
        }
        return map;
    }

    public static String buildQueryString(String baseUrl, Map<String, String> params) {
        if (params == null) {
            return baseUrl;
        }
        if (baseUrl == null) {
            baseUrl = "";
        }

        String c = baseUrl.indexOf("?") == -1 ? "?" : "&";
        StringBuilder url = new StringBuilder(baseUrl);
        for (String key : params.keySet()) {
            String val = params.get(key);
            url.append(url.length() == baseUrl.length() ? c : "&").append(urlEncode(key)).append("=")
                    .append(val == null ? "" : urlEncode(val));
        }
        return url.toString();
    }
    //endregion

    public static final String  GetMethod    = "GET", PostMethod = "POST";
    private static final String FormMimeType = "application/x-www-form-urlencoded", JsonMimeType = "application/json";
    private static final String UserAgent    = "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 5 Build/LMY48B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/43.0.2357.65 Mobile Safari/537.36";
    private String              contentType;
    private int                 timeout;
    private String              proxyHost;

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public HttpClient() {
        timeout = App.TimeoutInfinite;
    }

    public String httpGet(String url) {
        return httpGet(url, null);
    }

    public String httpGet(String url, Map<String, String> params) {
        if (params != null && params.size() > 0) {
            url = buildQueryString(url, params);
        }
        return exec(url, GetMethod, null, contentType, timeout);
    }

    public String httpPost(String url, Map<String, String> params) {
        contentType = FormMimeType;
        return exec(url, PostMethod, buildQueryString("", params).substring(1), contentType, timeout);
    }

    public String httpPost(String url, Object jsonEntity) {
        contentType = JsonMimeType;
        return exec(url, PostMethod, JSON.toJSONString(jsonEntity), contentType, timeout);
    }

    private String exec(String url, String method, String content, String contentType, int timeout) {
        String charset = App.UTF8;
        try {
            URL uri = new URL(url);
            HttpURLConnection client = (HttpURLConnection) (proxyHost != null
                    ? uri.openConnection(new Proxy(Proxy.Type.HTTP, Sockets.parseAddress(proxyHost)))
                    : uri.openConnection());
            client.setDoOutput(true);
            client.setDoInput(true);
            client.setUseCaches(false);
            client.setRequestProperty("User-Agent", UserAgent);
            client.setRequestProperty("Accept-Charset", charset);
            client.setRequestMethod(method);
            if (!App.isNullOrEmpty(contentType)) {
                client.setRequestProperty("Content-Type", contentType + ";charset=" + charset);
            }
            if (timeout > App.TimeoutInfinite) {
                client.setConnectTimeout(timeout);
                client.setReadTimeout(timeout);
            }
            client.connect();
            if (App.equals(method, PostMethod) && !App.isNullOrEmpty(content)) {
                App.writeString(client.getOutputStream(), content, charset);
            }

            int resCode = client.getResponseCode();
            if (resCode != HttpURLConnection.HTTP_OK) {

            }
            return App.readString(client.getInputStream(), isNull(client.getContentEncoding(), charset));
        } catch (Exception ex) {
            throw new SystemException(ex);
        }
    }
}
