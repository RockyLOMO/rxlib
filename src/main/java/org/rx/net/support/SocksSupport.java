package org.rx.net.support;

import org.apache.commons.collections4.map.LRUMap;
import org.rx.bean.SUID;
import org.rx.core.Arrays;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksSupport {
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>(Arrays.toList("8.8.8.8", "8.8.4.4"));

    String FAKE_HOST_SUFFIX = "x.f-li.cn";
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    Map<SUID, UnresolvedEndpoint> HOST_DICT = Collections.synchronizedMap(new LRUMap<>(4000));

    void fakeEndpoint(SUID hash, String realEndpoint);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);
}
