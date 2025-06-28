package org.rx.net.support;

import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.core.cache.DiskCache;
import org.rx.net.dns.DnsServer;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksSupport extends AutoCloseable, DnsServer.ResolveInterceptor {
    String FAKE_HOST_SUFFIX = Strings.cas("AS(120,46,102,45,108,105,46,99,110)");
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    int FAKE_EXPIRE_SECONDS = 60 * 5;
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>();  //There is no need to set up '8.8.8.8'
    List<Integer> FAKE_PORTS = new CopyOnWriteArrayList<>(Arrays.toList(80));
    int DNS_PORT = 53;
    long ASYNC_TIMEOUT = 4 * 1000;
    EndpointTracer ENDPOINT_TRACER = new EndpointTracer();

    static Cache<BigInteger, UnresolvedEndpoint> fakeDict() {
        return (Cache<BigInteger, UnresolvedEndpoint>) DiskCache.DEFAULT;
    }

    void fakeEndpoint(BigInteger hash, String realEndpoint);

    List<InetAddress> resolveHost(String host);

    void addWhiteList(InetAddress endpoint);

    @Override
    default void close() {
        //rpc close
    }
}
