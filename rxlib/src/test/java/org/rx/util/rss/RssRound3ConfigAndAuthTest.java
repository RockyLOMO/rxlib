package org.rx.util.rss;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.HandshakeMode;
import org.rx.net.SecurityMode;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksUser;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 3 verification for §8.2.1 (Egress partial inheritance global -> user -> upstream, explicit none override,
 * client allowedCodecs rejection, CLI param consistency), §8.2.3 (auth fail-closed & reload revocation),
 * and §9/§11/§12 config defaults (handshakeMode=lightweight, securityMode=fast, rpcMinSize=1).
 */
class RssRound3ConfigAndAuthTest {

    @Test
    void egressPartialInheritanceGlobalUserUpstreamAndExplicitNoneOverride() {
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "pwd123";
        conf.egress = new RssClientConf.Egress();
        conf.egress.tcpCodec = "http";
        conf.egress.maxBodyBytes = 1048576;
        conf.egress.httpHost = "global.example";

        // User only overrides httpBodyEncoding -> MUST inherit global tcpCodec="http", maxBodyBytes=1048576, httpHost
        ShadowUser userPartial = new ShadowUser();
        userPartial.setUsername("u-partial");
        userPartial.setSocksUser("s-partial");
        userPartial.setSsPort(18101);
        userPartial.setSsPwd("p1");
        userPartial.transport = new RssTransport();
        userPartial.transport.egress = new RssClientConf.Egress();
        userPartial.transport.egress.httpBodyEncoding = "zlib-stored";
        assertNull(userPartial.transport.egress.tcpCodec, "Unset Egress.tcpCodec must remain null, not 'none'");

        // User explicitly sets tcpCodec="none" -> MUST override global HTTP back to NONE
        ShadowUser userExplicitNone = new ShadowUser();
        userExplicitNone.setUsername("u-none");
        userExplicitNone.setSocksUser("s-none");
        userExplicitNone.setSsPort(18102);
        userExplicitNone.setSsPwd("p2");
        userExplicitNone.transport = new RssTransport();
        userExplicitNone.transport.egress = new RssClientConf.Egress();
        userExplicitNone.transport.egress.tcpCodec = "none";

        conf.shadowUsers = Arrays.asList(userPartial, userExplicitNone);

        // Upstream A overrides ONLY maxBodyBytes -> inherits HTTP + user's zlib-stored + upstream's 65536
        RssClientConf.SocksServer upA = new RssClientConf.SocksServer("up-a", 1,
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "p"));
        upA.egress = new RssClientConf.Egress();
        upA.egress.maxBodyBytes = 65536;

        // Upstream B explicitly overrides tcpCodec="none" -> overrides global HTTP back to NONE
        RssClientConf.SocksServer upB = new RssClientConf.SocksServer("up-b", 1,
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1081), "u", "p"));
        upB.egress = new RssClientConf.Egress();
        upB.egress.tcpCodec = "none";

        conf.socksServers = Arrays.asList(upA, upB);
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));

        SocksConfig inbound = new SocksConfig(0);
        RssClient.OutboundProfiles profiles = new RssClient.OutboundProfiles(conf, inbound);

        // Verify global -> user merge
        EgressConfig uPartialMerged = profiles.tcp(userPartial, 443).effectiveClientEgressConfig();
        assertEquals(EgressCodec.HTTP, uPartialMerged.getTcpCodec());
        assertEquals(EgressCodec.HttpBodyEncoding.ZLIB_STORED, uPartialMerged.getHttpBodyEncoding());
        assertEquals(1048576, uPartialMerged.getMaxBodyBytes());
        assertEquals("global.example", uPartialMerged.getHttpHost());

        EgressConfig uNoneMerged = profiles.tcp(userExplicitNone, 443).effectiveClientEgressConfig();
        assertEquals(EgressCodec.NONE, uNoneMerged.getTcpCodec());

        // Verify global -> user -> upstream merge via RssClientConf.Egress.toClientEgressConfig(parent)
        EgressConfig upAMerged = upA.egress.toClientEgressConfig(uPartialMerged);
        assertEquals(EgressCodec.HTTP, upAMerged.getTcpCodec());
        assertEquals(EgressCodec.HttpBodyEncoding.ZLIB_STORED, upAMerged.getHttpBodyEncoding());
        assertEquals(65536, upAMerged.getMaxBodyBytes());
        assertEquals("global.example", upAMerged.getHttpHost());

        EgressConfig upBMerged = upB.egress.toClientEgressConfig(uPartialMerged);
        assertEquals(EgressCodec.NONE, upBMerged.getTcpCodec());

        // Verify JSON serialization round-trip distinguishes absent/null vs explicit "none"
        String jsonPartial = JSON.toJSONString(upA.egress);
        RssClientConf.Egress parsedPartial = JSON.parseObject(jsonPartial, RssClientConf.Egress.class);
        assertNull(parsedPartial.tcpCodec);
        assertEquals(Integer.valueOf(65536), parsedPartial.maxBodyBytes);

        String jsonNone = JSON.toJSONString(upB.egress);
        RssClientConf.Egress parsedNone = JSON.parseObject(jsonNone, RssClientConf.Egress.class);
        assertEquals("none", parsedNone.tcpCodec);
    }

    @Test
    void clientAllowedCodecsRejectedAndServerCliParamNamesMatch() {
        // Client-side allowedCodecs must be rejected
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "pwd123";
        conf.egress = new RssClientConf.Egress();
        conf.egress.tcpCodec = "http";
        conf.egress.allowedCodecs = Collections.singletonList("http");
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf),
                "Client config specifying allowedCodecs must be rejected");

        // EgressConfig.validateClient also rejects non-empty allowedCodecs directly
        EgressConfig direct = EgressConfig.client(EgressCodec.HTTP);
        direct.setAllowedCodecs(Collections.singleton(EgressCodec.HTTP));
        assertThrows(IllegalArgumentException.class, direct::validateClient);

        // Server CLI flags match actual RssServer.parseServerEgressConfig names
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("tcpEgressCodec", "auto");
        opts.put("tcpEgressAllowedCodecs", "none,zlib-stored,http");
        opts.put("tcpEgressHttpBodyEncoding", "zlib-stored");
        opts.put("tcpEgressHttpRandomHeaders", "false");
        opts.put("tcpEgressHttpAllowGetMethod", "false");
        opts.put("tcpEgressHttpHost", "edge.example");
        opts.put("tcpEgressHttpPath", "/stream");
        opts.put("tcpEgressMaxHeaderBytes", "8192");
        opts.put("tcpEgressMaxBodyBytes", "262144");
        opts.put("tcpEgressMaxInflateBytes", "1048576");
        opts.put("tcpEgressDetectTimeoutMillis", "7500");

        EgressConfig parsed = RssServer.parseServerEgressConfig(opts);
        assertEquals(EgressCodec.AUTO, parsed.getTcpCodec());
        assertEquals(3, parsed.getAllowedCodecs().size());
        assertEquals(EgressCodec.HttpBodyEncoding.ZLIB_STORED, parsed.getHttpBodyEncoding());
        assertFalse(parsed.isHttpRandomHeaders());
        assertFalse(parsed.isHttpAllowGetMethod());
        assertEquals("edge.example", parsed.getHttpHost());
        assertEquals("/stream", parsed.getHttpPath());
        assertEquals(8192, parsed.getMaxHeaderBytes());
        assertEquals(262144, parsed.getMaxBodyBytes());
        assertEquals(1048576, parsed.getMaxInflateBytes());
        assertEquals(7500, parsed.getDetectTimeoutMillis());
    }

    @Test
    void rssClientConfDefaultsAndSecurityModeValidation() {
        RssClientConf defaults = new RssClientConf();
        assertEquals(1, defaults.rpcMinSize, "Default rpcMinSize should be lowered from 2 to 1");
        assertEquals(HandshakeMode.LIGHTWEIGHT, HandshakeMode.parse(defaults.handshakeMode));
        assertEquals(SecurityMode.FAST, SecurityMode.parse(defaults.securityMode));

        // rpcMinSize = 0 is now valid for full idle shrink
        defaults.socksPwd = "pwd123";
        defaults.rpcMinSize = 0;
        ShadowUser u = new ShadowUser();
        u.setUsername("u1");
        u.setSocksUser("su1");
        u.setSsPort(18150);
        u.setSsPwd("p1");
        defaults.shadowUsers = Collections.singletonList(u);
        defaults.socksServers = Collections.singletonList(new RssClientConf.SocksServer("s1", 1,
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "p")));
        assertTrue(RssClient.normalizeAndValidateRssConfig(defaults));

        // securityMode=none rejected when tcpCipherPorts are configured
        RssClientConf badSec = new RssClientConf();
        badSec.socksPwd = "pwd123";
        badSec.securityMode = "none";
        badSec.tcpCipherPorts = new int[]{22, 80};
        assertFalse(RssClient.normalizeAndValidateRssConfig(badSec),
                "securityMode=none must be rejected when protected tcpCipherPorts are configured");

        // Unknown handshakeMode/securityMode rejected
        assertThrows(IllegalArgumentException.class, () -> HandshakeMode.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> SecurityMode.parse("normal"));
        assertEquals(HandshakeMode.LIGHTWEIGHT, HandshakeMode.parse(null));
        assertEquals(SecurityMode.FAST, SecurityMode.parse(null));
    }

    @Test
    void rssAuthenticatorFailClosedAndReloadRevocation() {
        ShadowUser u1 = new ShadowUser();
        u1.setUsername("alice");
        u1.setSocksUser("s-alice");
        u1.setSsPort(18201);
        u1.setSsPwd("p1");
        ShadowUser u2 = new ShadowUser();
        u2.setUsername("bob");
        u2.setSocksUser("s-bob");
        u2.setSsPort(18202);
        u2.setSsPwd("p2");

        RssAuthenticator auth = new RssAuthenticator(Arrays.asList(u1, u2), "shared-socks-pwd", 24);
        SocksUser ok = auth.login("alice", "shared-socks-pwd");
        assertNotNull(ok);
        assertFalse(ok.isAnonymous());
        assertEquals("s-alice", ok.getUsername());
        assertNull(auth.login("alice", "wrong-pwd"), "Wrong password must return null, NEVER ANONYMOUS");
        assertNull(auth.login("unknown-user", "shared-socks-pwd"), "Unknown user must return null, NEVER ANONYMOUS");

        // Reload removing bob revokes bob immediately while keeping alice active
        auth.reload(Collections.singletonList(u1), "shared-socks-pwd", 24);
        assertNotNull(auth.login("alice", "shared-socks-pwd"));
        assertNull(auth.login("bob", "shared-socks-pwd"), "Removed user must be rejected immediately after reload");
    }
}
