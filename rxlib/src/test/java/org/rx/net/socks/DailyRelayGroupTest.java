package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;
import org.rx.net.udp.DailyUdpWire;
import org.rx.net.udp.UdpRedundantEncoder;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DailyRelayGroupTest {
    @Test
    void negotiationIsPerGroupAndClosingEitherPortClosesItsPartner() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(7);
        assertThrows(IllegalArgumentException.class, () -> config.setDailyPortRange("66000-66001"));
        assertThrows(IllegalArgumentException.class, () -> config.setDailyPortRange("15680-15680"));
        config.setDailyPortRange("15678-15681");
        config.setUdpRedundantMultiplier(2);
        try (SocksProxyServer server = new SocksProxyServer(config)) {
            UdpRelayGroupOpenRequest request = new UdpRelayGroupOpenRequest();
            String rpcToken = SocksRpcContract.rpcToken();
            assertTrue(server.socksRpcCapabilities(rpcToken).has(SocksRpcCapabilities.SUPPORT_DAILY_UDP));
            UdpRelayGroupOpenResult legacy = server.openUdpRelayGroup(request, rpcToken);
            assertTrue(legacy.isSuccess());
            assertEquals(rpcToken, legacy.getToken());
            assertEquals(1, legacy.getRelays().size());

            request.setMaxRelayCount(2);
            request.setDailyFeatures(DailyUdpWire.FEC);
            UdpRelayGroupOpenResult daily = server.openUdpRelayGroup(request, rpcToken);
            assertTrue(daily.isSuccess(), daily.getErrorCode());
            assertNotEquals(rpcToken, daily.getToken());
            assertNotNull(UUID.fromString(daily.getToken()));
            assertEquals(2, daily.getRelays().size());
            UdpRelayEndpoint hop = daily.getRelays().get(1);
            assertEquals(SocksRpcCapabilities.DAILY_HOP_ID, hop.getRelayId());
            Channel primary = server.udpRelayRegistry.get(daily.getRelays().get(0).getRelayAddress().getPort());
            Channel alias = server.udpRelayRegistry.get(hop.getRelayAddress().getPort());
            assertEquals(15680 + 15681, daily.getRelays().get(0).getRelayAddress().getPort()
                    + hop.getRelayAddress().getPort());
            assertFalse(server.openUdpRelayGroup(request, rpcToken).isSuccess());
            assertNotSame(primary, alias);
            assertSame(primary.eventLoop(), alias.eventLoop());
            assertNotNull(primary.pipeline().get(DailyUdpWire.class));
            assertNull(primary.pipeline().get(UdpRedundantEncoder.class));
            assertNull(alias.pipeline().get(SocksUdpRelayHandler.class));
            alias.close().sync();
            assertTrue(primary.closeFuture().await(5, TimeUnit.SECONDS));
            assertFalse(server.heartbeatUdpRelayGroup(daily.getGroupId(), rpcToken));
            assertTrue(server.heartbeatUdpRelayGroup(legacy.getGroupId(), rpcToken));
        }
    }
}
