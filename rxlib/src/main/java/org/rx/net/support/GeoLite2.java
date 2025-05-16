package org.rx.net.support;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.http.HttpClient;

import java.io.File;
import java.net.InetAddress;
import java.util.Collections;

@Slf4j
public class GeoLite2 implements IPSearcher {
    DatabaseReader reader;
    @Setter
    String resolveServer = "https://f-li.cn:8082";

    @Override
    public String getPublicIp() {
        String[] services = resolveServer != null
                ? new String[]{resolveServer + "/getPublicIp", "https://checkip.amazonaws.com", "https://api.seeip.org"}
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
        if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()
                || Sockets.isPrivateIp(ip)) {
            return new IPAddress(ip.getHostAddress(), "Private", null, null);
        }
        if (reader == null) {
            Tasks.run(this::init);
            return new IPAddress(ip.getHostAddress(), "Init..", null, null);
        }

        CityResponse result = reader.city(ip);
        Country country = result.getCountry();
        City city = result.getCity();
        return new IPAddress(ip.getHostAddress(), country.getName(), country.getIsoCode(), city.getName());
    }

    @SneakyThrows
    synchronized void init() {
        File f = new File("GeoLite2-City.mmdb");
        if (!f.exists()) {
            new HttpClient().withTimeoutMillis(10 * 60 * 1000)
                    .get(Constants.RXCLOUD + "/GeoLite2-City.mmdb").toFile(f.getName());
        }
        reader = new DatabaseReader.Builder(f).build();
    }
}
