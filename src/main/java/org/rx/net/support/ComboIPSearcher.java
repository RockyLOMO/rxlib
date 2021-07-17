package org.rx.net.support;

import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import org.rx.bean.Tuple;
import org.rx.core.App;
import org.rx.core.Cache;
import org.rx.core.Tasks;
import org.rx.io.KeyValueStore;
import org.rx.net.http.HttpClient;

import java.util.concurrent.CompletableFuture;

class ComboIPSearcher implements IPSearcher {
    final KeyValueStore<String, IPAddress> store = KeyValueStore.getInstance();

    @SneakyThrows
    @Override
    public IPAddress search(String ip) {
        Tuple<CompletableFuture<IPAddress>, CompletableFuture<IPAddress>[]> tuple = Tasks.anyOf(() -> ipApi(ip));
        return tuple.left.get();
    }

    IPAddress ipApi(String ip) {
        HttpClient client = new HttpClient();
        String j = client.get(String.format("http://ip-api.com/json/%s", ip)).asString();
        JSONObject json = App.toJsonObject(j);

        return new IPAddress(ip, json.getString("country"), json.getString("countryCode"), json.getString("city"),
                String.format("%s %s", json.getString("as"), json.getString("org")));
    }
}
