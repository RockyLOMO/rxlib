package org.rx.net.support;

import lombok.Data;
import org.rx.core.Strings;

import java.io.Serializable;

@Data
public class IPAddress implements Serializable {
    private static final long serialVersionUID = 1529992648624772634L;
    private final String ip;
    private final String country;
    private final String countryCode;
    private final String city;

    public boolean isChina() {
        return Strings.equalsIgnoreCase(countryCode, "CN");
    }
}
