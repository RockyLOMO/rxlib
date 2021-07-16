package org.rx.net.support;

import com.alibaba.fastjson.JSONObject;
import org.rx.core.App;
import org.rx.net.http.HttpClient;

class XXX implements IPSearcher {
    final HttpClient client = new HttpClient();

    @Override
    public IPAddress search(String ip) {
        return null;
    }

    IPAddress ipApi(String ip) {
        String j = client.get(String.format("http://ip-api.com/json/%s", ip)).asString();
        JSONObject json = App.toJsonObject(j);

        return new IPAddress(ip, json.getString("country"), json.getString("countryCode"), json.getString("city"),
                String.format("%s %s", json.getString("as"), json.getString("org")));
    }
}
