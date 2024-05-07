package org.rx.net.support;

import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;

public interface IPSearcher {
    IPSearcher DEFAULT = new ComboIPSearcher();

    static String godaddyDns(String ssoKey, String domain, String name) {
        return godaddyDns(ssoKey, domain, name, DEFAULT.currentIp(), null);
    }

    static String godaddyDns(String ssoKey, String domain, String name, String ip, AuthenticProxy proxy) {
        String u = String.format("https://api.godaddy.com/v1/domains/%s/records/A/%s", domain, name);
        HttpClient c = new HttpClient();
        c.setEnableLog(true);
        c.setProxy(proxy);
        c.requestHeaders().add("Authorization", "sso-key " + ssoKey);
        return c.putJson(u, String.format("[\n" +
                "  {\n" +
                "    \"data\": \"%s\",\n" +
                "    \"ttl\": 600\n" +
                "  }\n" +
                "]", ip)).toString();
    }

    String currentIp();

    IPAddress searchCurrent();

    default IPAddress search(String host) {
        return search(host, false);
    }

    IPAddress search(String host, boolean resolveHostRemotely);
}
