package org.rx.net.support;

import com.alibaba.fastjson.JSONObject;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.RandomList;
import org.rx.core.App;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;
import org.rx.util.function.BiFunc;

import java.net.InetAddress;
import java.util.function.Predicate;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.sneakyInvoke;

class ComboIPSearcher implements IPSearcher {
    final RandomList<BiFunc<String, IPAddress>> apis = new RandomList<>();
    final int retryCount;
    final KeyValueStore<String, IPAddress> store = new KeyValueStore<>(KeyValueStoreConfig.defaultConfig("./data/ip"));

    public ComboIPSearcher() {
        apis.add(this::ip_Api, 240);
        apis.add(this::ipGeo, 40);
        apis.add(this::ipData, 40);
        apis.add(this::ipInfo, 100);
        apis.add(this::ipWho, 20);
        apis.add(this::ipApi, 2);
        retryCount = Math.max(apis.size() / 2, 2);
    }

    @Override
    public String currentIp() {
        return Tasks.randomRetry(() -> new HttpClient().get("https://api.ipify.org").toString(),
                () -> searchCurrent().getIp());
    }

    @Override
    public IPAddress searchCurrent() {
        return search(Sockets.loopbackAddress().getHostAddress());
    }

    @SneakyThrows
    @Override
    public IPAddress search(@NonNull String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            return rndRetry(ip);
        }

        return store.computeIfAbsent(ip, k -> rndRetry(ip));
    }

    IPAddress rndRetry(String ip) {
//        return Tasks.randomRetry(() -> ip_Api(ip), () -> ipGeo(ip),
//                () -> ipData(ip), () -> ipWho(ip));
        return sneakyInvoke(() -> apis.next().invoke(ip), retryCount);
    }

    //6k/d
    IPAddress ip_Api(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = Strings.EMPTY;
        }
        String url = String.format("http://ip-api.com/json/%s", ip);
        JSONObject json = getJson(url, p -> eq(p.getString("status"), "success"));

        return new IPAddress(json.getString("query"), json.getString("country"), json.getString("countryCode"), json.getString("city"),
                json.getString("isp"),
                String.format("%s %s", json.getString("as"), json.getString("org")));
    }

    //1k/d
    @SneakyThrows
    IPAddress ipGeo(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = Strings.EMPTY;
        } else if (!Sockets.isValidIp(ip)) {
            ip = InetAddress.getByName(ip).getHostAddress();
        }
        String url = String.format("https://api.ipgeolocation.io/ipgeo?apiKey=e96493e9280e4a4fae1b8744ad688272&ip=%s", ip);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);

        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code2"), json.getString("city"),
                json.getString("isp"), json.getString("organization"));
    }

    //1.5k/d
    @SneakyThrows
    IPAddress ipData(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = Strings.EMPTY;
        } else if (!Sockets.isValidIp(ip)) {
            ip = InetAddress.getByName(ip).getHostAddress();
        }
        String url = String.format("https://api.ipdata.co/%s?api-key=cbf82d3beb9d0591285d73210d518d99920fb7d50dc2e5a24e9c599a", ip);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);

        String isp = null;
        JSONObject connection = json.getJSONObject("asn");
        if (connection != null) {
            isp = String.format("%s %s", connection.getString("asn"), connection.getString("name"));
        }
        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code"), json.getString("city"),
                isp, null);
    }

    //10k/m
    @SneakyThrows
    IPAddress ipWho(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = Strings.EMPTY;
        } else if (!Sockets.isValidIp(ip)) {
            ip = InetAddress.getByName(ip).getHostAddress();
        }
        String url = String.format("https://ipwho.is/%s", ip);
        JSONObject json = getJson(url, p -> p.getBooleanValue("success"));

        String isp = null, extra = null;
        JSONObject connection = json.getJSONObject("connection");
        if (connection != null) {
            isp = connection.getString("isp");
            extra = connection.getString("org");
        }
        return new IPAddress(json.getString("ip"), json.getString("country"), json.getString("country_code"), json.getString("city"),
                isp, extra);
    }

    //50k/m
    @SneakyThrows
    IPAddress ipInfo(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = Strings.EMPTY;
        } else if (!Sockets.isValidIp(ip)) {
            ip = InetAddress.getByName(ip).getHostAddress();
        }
        String url = String.format("https://ipinfo.io/%s?token=de26227b84b811", ip);
        JSONObject json = getJson(url, p -> p.getString("country") != null);

        return new IPAddress(json.getString("ip"), null, json.getString("country"), json.getString("city"),
                json.getString("org"), null);
    }

    //1k/m
    IPAddress ipApi(String ip) {
        if (ip.equals(Sockets.loopbackAddress().getHostAddress())) {
            ip = "check";
        }
        String url = String.format("http://api.ipapi.com/%s?access_key=8da5fe816dba52150d4c40ba72705954", ip);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);

        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code"), json.getString("city"),
                null, null);
    }

    private JSONObject getJson(String url, Predicate<JSONObject> check) {
        HttpClient client = new HttpClient(15 * 1000);
        String text = client.get(url).toString();
        if (Strings.isEmpty(text)) {
            throw new InvalidException("Empty response from {}", url);
        }
        JSONObject json = App.toJsonObject(text);
        if (!check.test(json)) {
            throw new InvalidException("Request:\t{}\n" + "Response:\t{}", url, text);
        }
        return json;
    }
}
