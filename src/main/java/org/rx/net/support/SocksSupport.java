package org.rx.net.support;

import org.rx.bean.SUID;
import org.rx.core.Arrays;
import org.rx.core.Cache;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksSupport {
    String FAKE_HOST_SUFFIX = "x.f-li.cn";
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>();  //8.8.8.8 不需要设置了
    List<Integer> FAKE_PORTS = new CopyOnWriteArrayList<>(Arrays.toList(80));
    int DNS_PORT = 53;
    long ASYNC_TIMEOUT = 5 * 1000;

    static Cache<SUID, UnresolvedEndpoint> fakeDict() {
        return Cache.getInstance(Cache.DISTRIBUTED_CACHE);
    }

    void fakeEndpoint(SUID hash, String realEndpoint);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);
}
