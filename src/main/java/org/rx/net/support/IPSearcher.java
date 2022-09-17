package org.rx.net.support;

import org.rx.net.http.HttpClient;

public interface IPSearcher {
    IPSearcher DEFAULT = new ComboIPSearcher();

    static String godaddyDns(String ssoKey, String domain, String name) {
        return godaddyDns(ssoKey, domain, name, DEFAULT.currentIp());
    }

    static String godaddyDns(String ssoKey, String domain, String name, String ip) {
        String url = String.format("https://api.godaddy.com/v1/domains/%s/records/A/%s", domain, name);
        HttpClient client = new HttpClient();
        client.getRequestHeaders().add("Authorization", "sso-key " + ssoKey);
        return client.putJson(url, String.format("[\n" +
                "  {\n" +
                "    \"data\": \"%s\",\n" +
                "    \"ttl\": 600\n" +
                "  }\n" +
                "]", ip)).toString();
    }

    String currentIp();

    IPAddress searchCurrent();

    IPAddress search(String ip);
}
