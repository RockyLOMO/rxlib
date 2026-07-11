package org.rx.net.socks;

import io.netty.util.internal.SystemPropertyUtil;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.EventPublisher;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.dns.DnsResolveInterceptor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public interface SocksRpcContract extends AutoCloseable, DnsResolveInterceptor, EventPublisher<SocksRpcContract> {
    String FAKE_HOST_SUFFIX = Strings.cas("AS(120,46,102,45,108,105,46,99,110)");
    int[] FAKE_PORT_OBFS = new int[]{443, 3306};
    int FAKE_EXPIRE_SECONDS = 60 * 5;
    String FAKE_REGISTER_WAIT_MILLIS_PROPERTY = "app.net.socks.fakeEndpointRegisterWaitMillis";
    int FAKE_REGISTER_WAIT_MILLIS = 4 * 1000;
    String FAKE_RECOVER_WAIT_MILLIS_PROPERTY = "app.net.socks.fakeEndpointRecoverWaitMillis";
    int FAKE_RECOVER_WAIT_MILLIS = 1200;
    int FAKE_RECOVER_RPC_TIMEOUT_MILLIS = 5000;
    int RPC_EVENT_VERSION = 1;
    String EVENT_FAKE_ENDPOINT_RECOVERY = "fakeEndpointRecovery";
    int FAKE_TOKEN_LENGTH = 16;
    long FAKE_TOKEN_PART_MASK = (1L << 40) - 1L;
    List<String> FAKE_IPS = new CopyOnWriteArrayList<>();  //There is no need to set up '8.8.8.8'
    List<Integer> FAKE_PORTS = new CopyOnWriteArrayList<>(Arrays.toList(80));
    int DNS_PORT = 53;
    static Cache<String, InetSocketAddress> fakeDict() {
        return (Cache<String, InetSocketAddress>) H2StoreCache.DEFAULT;
    }

    static String newFakeHost() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] token = new char[FAKE_TOKEN_LENGTH + FAKE_HOST_SUFFIX.length()];
        writeBase36Part(random.nextLong() & FAKE_TOKEN_PART_MASK, token, 0);
        writeBase36Part(random.nextLong() & FAKE_TOKEN_PART_MASK, token, 8);
        FAKE_HOST_SUFFIX.getChars(0, FAKE_HOST_SUFFIX.length(), token, FAKE_TOKEN_LENGTH);
        return new String(token);
    }

    static boolean isFakeHost(String host) {
        if (host == null || host.length() != FAKE_TOKEN_LENGTH + FAKE_HOST_SUFFIX.length()
                || !host.endsWith(FAKE_HOST_SUFFIX)) {
            return false;
        }
        for (int i = 0; i < FAKE_TOKEN_LENGTH; i++) {
            char c = host.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    static void writeBase36Part(long value, char[] output, int offset) {
        for (int i = offset + 7; i >= offset; i--) {
            int digit = (int) (value % 36L);
            output[i] = (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            value /= 36L;
        }
    }

    static String rpcToken() {
        return RxConfig.INSTANCE.getRtoken();
    }

    static boolean isValidRpcToken(String token) {
        String expected = RxConfig.INSTANCE.getRtoken();
        return !Strings.isEmpty(expected) && expected.equals(token);
    }

    static long fakeRecoverWaitMillis() {
        long configured = SystemPropertyUtil.getLong(FAKE_RECOVER_WAIT_MILLIS_PROPERTY, FAKE_RECOVER_WAIT_MILLIS);
        return configured > 0L ? configured : FAKE_RECOVER_WAIT_MILLIS;
    }

    static long fakeRegisterWaitMillis() {
        long configured = SystemPropertyUtil.getLong(FAKE_REGISTER_WAIT_MILLIS_PROPERTY, FAKE_REGISTER_WAIT_MILLIS);
        return configured > 0L ? configured : FAKE_REGISTER_WAIT_MILLIS;
    }

    static void requireValidRpcToken(String token) {
        if (!isValidRpcToken(token)) {
            throw new SecurityException("invalid rpc token");
        }
    }

    /**
     * @return true when the token is registered for this endpoint; false on a token collision.
     */
    boolean fakeEndpoint(String fakeHost, String realEndpoint, String token);

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
