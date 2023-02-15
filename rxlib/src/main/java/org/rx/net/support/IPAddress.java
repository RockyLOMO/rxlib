package org.rx.net.support;

import lombok.*;
import org.rx.core.Strings;

import java.io.Serializable;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class IPAddress implements Serializable {
    private static final long serialVersionUID = 1529992648624772634L;
    private final String ip;
    private final String country;
    private final String countryCode;
    private final String city;
    private final String ISP;
    private final String extra;

    public boolean isChina() {
        return Strings.equalsIgnoreCase(countryCode, "CN");
    }
}
