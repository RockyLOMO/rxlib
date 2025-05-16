package org.rx.net.support;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.http.HttpClient;
import org.rx.util.Lazy;

import java.io.File;
import java.net.InetAddress;
import java.util.Collections;

@Slf4j
public class GeoLite2 implements IPSearcher {
    final Lazy<DatabaseReader> reader;
    @Setter
    String resolveServer;

    public GeoLite2() {
        reader = new Lazy<>(() -> {
            File f = new File("GeoLite2-City.mmdb");
            if (!f.exists()) {
                new HttpClient().withTimeoutMillis(10 * 60 * 1000)
                        .get("https://cloud.f-li.cn:6400/GeoLite2-City.mmdb").toFile(f.getName());
            }
            return new DatabaseReader.Builder(f).build();
        });
    }

    @Override
    public String getPublicIp() {
        String[] services = resolveServer != null
                ? new String[]{"https://checkip.amazonaws.com", "https://api.seeip.org", resolveServer + "/getPublicIp"}
                : new String[]{"https://checkip.amazonaws.com", "https://api.seeip.org"};
        HttpClient client = new HttpClient();
        for (String service : services) {
            try {
                String ip = client.get(service).toString();
                if (Sockets.isValidIp(ip)) {
                    return ip;
                }
            } catch (Exception e) {
                log.warn("getPublicIp retry", e);
            }
        }
        throw new InvalidException("getPublicIp fail");
    }

    @SneakyThrows
    @Override
    public IPAddress resolve(String host) {
        if (Sockets.isValidIp(host)) {
            InetAddress ipAddr = InetAddress.getByName(host);
            return search(ipAddr);
        }

        if (resolveServer == null) {
            InetAddress ipAddr = DnsClient.outlandClient().resolve(host);
            return search(ipAddr);
        }

        return Sys.fromJson(new HttpClient()
                .get(HttpClient.buildUrl(resolveServer + "/geo", Collections.singletonMap("host", host))).toString(), IPAddress.class);
    }

    @SneakyThrows
    IPAddress search(InetAddress ip) {
        if (Sockets.isPrivateIp(ip)) {
            return new IPAddress(ip.getHostAddress(), "Private", null, null);
        }

        CityResponse result = reader.getValue().city(ip);
        Country country = result.getCountry();
        City city = result.getCity();
        return new IPAddress(ip.getHostAddress(), country.getName(), country.getIsoCode(), city.getName());
    }
}
