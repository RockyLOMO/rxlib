package org.rx.net.support;

public interface IPSearcher {
    IPSearcher DEFAULT = new GeoLite2();

    String getPublicIp();

    default IPAddress resolvePublicIp() {
        return resolve(getPublicIp());
    }

    IPAddress resolve(String host);
}
