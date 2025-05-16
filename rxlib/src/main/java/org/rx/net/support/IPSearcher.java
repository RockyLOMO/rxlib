package org.rx.net.support;

import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;

public interface IPSearcher {
    IPSearcher DEFAULT = new GeoLite2("GeoLite2-City.mmdb");

    static String godaddyDns(String ssoKey, String domain, String name) {
        return godaddyDns(ssoKey, domain, name, DEFAULT.getPublicIp(), null);
    }

    static String godaddyDns(String ssoKey, String domain, String name, String ip, AuthenticProxy proxy) {
        String u = String.format("https://api.godaddy.com/v1/domains/%s/records/A/%s", domain, name);
        HttpClient c = new HttpClient().withFeatures(false, true).withProxy(proxy);
        c.requestHeaders().add("Authorization", "sso-key " + ssoKey);
        return c.putJson(u, String.format("[\n" +
                "  {\n" +
                "    \"data\": \"%s\",\n" +
                "    \"ttl\": 600\n" +
                "  }\n" +
                "]", ip)).toString();
    }

    String getPublicIp();

    default IPAddress resolvePublicIp() {
        return resolve(getPublicIp());
    }

    IPAddress resolve(String host);
}
