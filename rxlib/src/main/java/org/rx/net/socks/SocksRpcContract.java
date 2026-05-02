package org.rx.net.socks;

import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.dns.DnsServer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface SocksRpcContract extends AutoCloseable, DnsServer.ResolveInterceptor {
    String FAKE_HOST_SUFFIX = Strings.cas("AS(120,46,102,45,108,105,46,99,110)");
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    int FAKE_EXPIRE_SECONDS = 60 * 5;
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>();  //There is no need to set up '8.8.8.8'
    List<Integer> FAKE_PORTS = new CopyOnWriteArrayList<>(Arrays.toList(80));
    int DNS_PORT = 53;
    long ASYNC_TIMEOUT = 4 * 1000;

    static Cache<Long, UnresolvedEndpoint> fakeDict() {
        return (Cache<Long, UnresolvedEndpoint>) H2StoreCache.DEFAULT;
    }

    static String fakeHost(long hash) {
        return Long.toHexString(hash) + FAKE_HOST_SUFFIX;
    }

    static Long parseFakeHostHash(String host) {
        if (host == null || !host.endsWith(FAKE_HOST_SUFFIX)) {
            return null;
        }
        String token = host.substring(0, host.length() - FAKE_HOST_SUFFIX.length());
        if (token.length() == 0) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseUnsignedLong(token, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void fakeEndpoint(long hash, String realEndpoint);

    void addWhiteList(InetAddress endpoint);

    default SocksRpcCapabilities capabilities() {
        return SocksRpcCapabilities.EMPTY;
    }

    default boolean resetUdpRelay(int relayPort) {
        return false;
    }

    default boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
        return false;
    }

    default UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request) {
        return UdpRelayGroupOpenResult.unsupported();
    }

    default UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count) {
        return UdpRelayGroupUpdateResult.unsupported();
    }

    default boolean removeUdpRelay(String groupId, int relayPort) {
        return false;
    }

    default boolean heartbeatUdpRelayGroup(String groupId) {
        return false;
    }

    default boolean closeUdpRelayGroup(String groupId) {
        return false;
    }

    @Override
    default void close() {
        //rpc close
    }
}
