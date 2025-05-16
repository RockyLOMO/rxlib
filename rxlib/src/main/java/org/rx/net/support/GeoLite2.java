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

import java.io.File;
import java.net.InetAddress;
import java.util.Collections;

@Slf4j
public class GeoLite2 implements IPSearcher {
    final DatabaseReader reader;
    @Setter
    String resolveServer;

    @SneakyThrows
    public GeoLite2(String filePath) {
        reader = new DatabaseReader.Builder(new File(filePath)).build();
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

        CityResponse result = reader.city(ip);
        Country country = result.getCountry();
        City city = result.getCity();
        return new IPAddress(ip.getHostAddress(), country.getName(), country.getIsoCode(), city.getName());
    }
}
