package org.rx.net.support;

import com.alibaba.fastjson.JSONObject;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
class ComboIPSearcher implements IPSearcher {
    final static int TIMEOUT_SECONDS = 10000;
    final static String LOOPBACK_ADDR = Sockets.loopbackAddress().getHostAddress();
    final RandomList<BiFunc<String, IPAddress>> apis = new RandomList<>(),
            dApis = new RandomList<>();
    final KeyValueStore<String, IPAddress> store = new KeyValueStore<>(KeyValueStoreConfig.defaultConfig("./data/host"));

    public ComboIPSearcher() {
//        apis.add(this::ip_Api, 240);
        apis.add(this::ip_Api, 90);
        apis.add(this::ipGeo, 40);
        apis.add(this::ipData, 40);
        apis.add(this::ipInfo, 100);
        apis.add(this::ipWho, 20);
        apis.add(this::ipApi, 2);

//        dApis.add(this::ip_Api, 120);
        dApis.add(this::ip_Api, 60);
        dApis.add(this::ipApi, 1);
    }

    @Override
    public String currentIp() {
        return Tasks.randomRetry(() -> new HttpClient(TIMEOUT_SECONDS).get("https://api.ipify.org").toString(),
                () -> searchCurrent().getIp());
    }

    @Override
    public IPAddress searchCurrent() {
        return search(LOOPBACK_ADDR);
    }

    @SneakyThrows
    @Override
    public IPAddress search(@NonNull String host, boolean resolveHostRemotely) {
        if (Strings.hashEquals(LOOPBACK_ADDR, host)) {
            return rndRetry(host, resolveHostRemotely);
        }

        return store.computeIfAbsent(String.format("%s:%s", host, resolveHostRemotely ? 1 : 0), k -> {
            if (resolveHostRemotely) {
                log.info("resolveHostRemotely: {}", k);
            }
            return rndRetry(host, resolveHostRemotely);
        });
    }

    @SneakyThrows
    IPAddress rndRetry(String host, boolean resolveHostRemotely) {
        if (Sockets.isValidIp(host)) {
            resolveHostRemotely = false;
        }
        if (!resolveHostRemotely) {
            host = InetAddress.getByName(host).getHostAddress();
        }
//        return Tasks.randomRetry(() -> ip_Api(host), () -> ipGeo(host),
//                () -> ipData(host), () -> ipWho(host));
        RandomList<BiFunc<String, IPAddress>> fns = resolveHostRemotely ? dApis : apis;
        String finalHost = host;
        return sneakyInvoke(() -> fns.next().invoke(finalHost), fns.size());
    }

    //6k/d
    IPAddress ip_Api(String host) {
        if (Strings.hashEquals(LOOPBACK_ADDR, host)) {
            host = Strings.EMPTY;
        }
        String url = String.format("http://ip-api.com/json/%s", host);
        JSONObject json = getJson(url, p -> eq(p.getString("status"), "success"));

        return new IPAddress(json.getString("query"), json.getString("country"), json.getString("countryCode"), json.getString("city"),
                json.getString("isp"),
                String.format("%s %s", json.getString("as"), json.getString("org")));
    }

    //1k/m
    IPAddress ipApi(String host) {
        if (Strings.hashEquals(LOOPBACK_ADDR, host)) {
            host = "check";
        }
        String url = String.format("http://api.ipapi.com/%s?access_key=8da5fe816dba52150d4c40ba72705954", host);
        JSONObject json = getJson(url, p -> p.getString("country_name") != null);

        return new IPAddress(json.getString("ip"), json.getString("country_name"), json.getString("country_code"), json.getString("city"),
                null, null);
    }

    //1k/d
    @SneakyThrows
    IPAddress ipGeo(String ip) {
        if (Strings.hashEquals(LOOPBACK_ADDR, ip)) {
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
        if (Strings.hashEquals(LOOPBACK_ADDR, ip)) {
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
        if (Strings.hashEquals(LOOPBACK_ADDR, ip)) {
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
        if (Strings.hashEquals(LOOPBACK_ADDR, ip)) {
            ip = Strings.EMPTY;
        } else if (!Sockets.isValidIp(ip)) {
            ip = InetAddress.getByName(ip).getHostAddress();
        }
        String url = String.format("https://ipinfo.io/%s?token=de26227b84b811", ip);
        JSONObject json = getJson(url, p -> p.getString("country") != null);

        return new IPAddress(json.getString("ip"), null, json.getString("country"), json.getString("city"),
                json.getString("org"), null);
    }

    private JSONObject getJson(String url, Predicate<JSONObject> check) {
        HttpClient client = new HttpClient(TIMEOUT_SECONDS);
        client.setEnableLog(true);
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
