package org.rx.net.socks;

import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.dns.DnsServer;
import java.net.InetSocketAddress;

import java.net.InetAddress;
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

    static Cache<Long, InetSocketAddress> fakeDict() {
        return (Cache<Long, InetSocketAddress>) H2StoreCache.DEFAULT;
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

    static String rpcToken() {
        return RxConfig.INSTANCE.getRtoken();
    }

    static boolean isValidRpcToken(String token) {
        String expected = RxConfig.INSTANCE.getRtoken();
        return !Strings.isEmpty(expected) && expected.equals(token);
    }

    static void requireValidRpcToken(String token) {
        if (!isValidRpcToken(token)) {
            throw new SecurityException("invalid rpc token");
        }
    }

    void fakeEndpoint(long hash, String realEndpoint, String token);

    void addWhiteList(InetAddress endpoint, String token);

    default SocksRpcCapabilities capabilities(String token) {
        return SocksRpcCapabilities.EMPTY;
    }

    default boolean resetUdpRelay(int relayPort, String token) {
        return false;
    }

    default boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token) {
        return false;
    }

    default UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request, String token) {
        return UdpRelayGroupOpenResult.unsupported();
    }

    default UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count, String token) {
        return UdpRelayGroupUpdateResult.unsupported();
    }

    default boolean removeUdpRelay(String groupId, int relayPort, String token) {
        return false;
    }

    default boolean heartbeatUdpRelayGroup(String groupId, String token) {
        return false;
    }

    default boolean closeUdpRelayGroup(String groupId, String token) {
        return false;
    }

    default Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request, String token) {
        return Udp2rawOpenResult.unsupported();
    }

    default boolean heartbeatUdp2rawTunnel(String tunnelId, String token) {
        return false;
    }

    default boolean closeUdp2rawTunnel(String tunnelId, String token) {
        return false;
    }

    @Override
    default void close() {
        //rpc close
    }
}
