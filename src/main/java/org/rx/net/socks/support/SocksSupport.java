package org.rx.net.socks.support;

import org.rx.bean.SUID;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface SocksSupport {
    String FAKE_PREFIX = "fx.";

    Map<SUID, String> FAKE_DICT = Collections.synchronizedMap(new LinkedHashMap<>());

    void fakeHost(SUID hash, String realHost);
}
