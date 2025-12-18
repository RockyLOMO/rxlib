package org.rx.net.support;

import javax.validation.constraints.NotNull;

public interface IPSearcher {
    IPSearcher DEFAULT = GeoLite2.INSTANCE;

    String getPublicIp();

//    default IpGeolocation resolvePublicIp() {
//        return resolve(getPublicIp());
//    }

    @NotNull
    IpGeolocation resolve(String host);
}
