package org.rx.net.support;

import org.apache.commons.collections4.map.LRUMap;
import org.rx.bean.SUID;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface SocksSupport {
    String FAKE_SUFFIX = "x.f-li.cn";

    Map<SUID, String> HOST_DICT = Collections.synchronizedMap(new LRUMap<>(2000));

    void fakeHost(SUID hash, String realHost);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);
}
