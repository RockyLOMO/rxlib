package org.rx.net.support;

import com.alibaba.fastjson.JSONObject;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.App;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;
import org.rx.net.http.RestClientException;

import java.util.function.Predicate;

import static org.rx.core.App.eq;

class ComboIPSearcher implements IPSearcher {
    final KeyValueStore<String, IPAddress> store = new KeyValueStore<>(KeyValueStoreConfig.miniConfig("./data/ip"));

    @Override
    public IPAddress current() {
        return search(Sockets.LOOPBACK_ADDRESS.getHostAddress());
    }

    @SneakyThrows
    @Override
    public IPAddress search(@NonNull String ip) {
        return store.computeIfAbsent(ip, k -> Tasks.sequentialRetry(() -> ip_Api(ip), () -> ipGeo(ip),
                () -> ipApi(ip)));
    }

    private JSONObject getJson(String url, Predicate<JSONObject> check) {
        HttpClient client = new HttpClient();
        String text = client.get(url).asString();
        if (Strings.isEmpty(text)) {
            throw new RestClientException("No response " + url);
        }
        JSONObject json = App.toJsonObject(text);
        if (!check.test(json)) {
            throw new RestClientException("Not success " + url);
        }
        return json;
    }

    IPAddress ip_Api(String ip) {
        if (ip.equals(Sockets.LOOPBACK_ADDRESS.getHostAddress())) {
            ip = Strings.EMPTY;
        }
        String url = String.format("http://ip-api.com/json/%s", ip);
        JSONObject json = getJson(url, p -> eq(p.getString("status"), "success"));

        return new IPAddress(json.getString("query"), json.getString("country"), json.getString("countryCode"), json.getString("city"),
                json.getString("isp"),
                String.format("%s %s", json.getString("as"), json.getString("org")));
    }

    //1k/d
    IPAddress ipGeo(String ip) {
        if (ip.equals(Sockets.LOOPBACK_ADDRESS.getHostAddress())) {
            ip = Strings.EMPTY;
        }
        String url = String.format("https://api.ipgeolocation.io/ipgeo?apiKey=e96493e9280e4a4fae1b8744ad688272&ip=%s", ip);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);
        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code2"), json.getString("city"),
                json.getString("isp"), json.getString("organization"));
    }

    //1k/m
    IPAddress ipApi(String ip) {
        if (ip.equals(Sockets.LOOPBACK_ADDRESS.getHostAddress())) {
            ip = "check";
        }
        String url = String.format("http://api.ipapi.com/%s?access_key=8da5fe816dba52150d4c40ba72705954", ip);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);

        String isp = null;
        JSONObject connection = json.getJSONObject("connection");
        if (connection != null) {
            isp = String.format("%s %s", connection.getString("asn"), connection.getString("isp"));
        }
        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code"), json.getString("city"),
                isp, null);
    }
}
