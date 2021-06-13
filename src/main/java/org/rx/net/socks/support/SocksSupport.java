package org.rx.net.socks.support;

import org.apache.commons.collections4.map.LRUMap;
import org.rx.bean.SUID;

import java.util.Collections;
import java.util.Map;

public interface SocksSupport {
    String FAKE_SUFFIX = "x.f-li.cn";

    Map<SUID, String> HOST_DICT = Collections.synchronizedMap(new LRUMap<>(2000));

    void fakeHost(SUID hash, String realHost);
}
