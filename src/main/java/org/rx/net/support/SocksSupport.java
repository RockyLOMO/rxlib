package org.rx.net.support;

import org.rx.bean.SUID;
import org.rx.core.Arrays;
import org.rx.io.KeyValueStore;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksSupport {
    String FAKE_HOST_SUFFIX = "x.f-li.cn";
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>(Arrays.toList("8.8.8.8", "8.8.4.4"));

    static Map<SUID, UnresolvedEndpoint> fakeDict() {
        return KeyValueStore.getInstance();
    }

    void fakeEndpoint(SUID hash, String realEndpoint);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);
}
