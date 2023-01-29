package org.rx.net.support;

import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.net.dns.DnsServer;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksSupport extends AutoCloseable, DnsServer.ResolveInterceptor {
    String FAKE_HOST_SUFFIX = "x.f-li.cn";
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>();  //There is no need to set up '8.8.8.8'
    List<Integer> FAKE_PORTS = new CopyOnWriteArrayList<>(Arrays.toList(80));
    int DNS_PORT = 53;
    long ASYNC_TIMEOUT = 5 * 1000;
    EndpointTracer ENDPOINT_TRACER = new EndpointTracer();

    static Cache<BigInteger, UnresolvedEndpoint> fakeDict() {
        return Cache.getInstance(Cache.DISK_CACHE);
    }

    void fakeEndpoint(BigInteger hash, String realEndpoint);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);

    @Override
    default void close() {
        //rpc close
    }
}
