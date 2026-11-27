package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.Sys;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.protocol.PingPacket;
import org.rx.test.PersonBean;
import org.rx.test.UserEventArgs;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuryRemotingCodecTest {
    @Test
    void roundTripMethodMessageAndPingPacket() throws Exception {
        ResourceLeakDetector.Level oldLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
            RemotingCodec codec = factory.newCodec();

            MethodMessage method = new MethodMessage(7, "create",
                    new Object[]{PersonBean.LeZhi, new UserEventArgs(PersonBean.LeZhi)}, "trace-7");
            Object decodedMethod = roundTrip(codec, method);
            MethodMessage methodPack = assertInstanceOf(MethodMessage.class, decodedMethod);
            assertEquals(method.id, methodPack.id);
            assertEquals(method.methodName, methodPack.methodName);
            assertEquals(PersonBean.LeZhi.getName(), ((PersonBean) methodPack.parameters[0]).getName());
            assertEquals(PersonBean.LeZhi.getName(), ((UserEventArgs) methodPack.parameters[1]).getUser().getName());

            Object decodedPing = roundTrip(codec, new PingPacket());
            assertInstanceOf(PingPacket.class, decodedPing);
        } finally {
            ResourceLeakDetector.setLevel(oldLevel);
        }
    }

    @Test
    void codecSurvivesDeepClone() throws Exception {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        RemotingCodec clone = Sys.deepClone(factory.newCodec());
        assertNotNull(clone);

        Object decoded = roundTrip(clone, new MethodMessage(11, "ping", new Object[]{"ok"}, "trace-11"));
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        assertEquals("ok", pack.parameters[0]);
    }

    @Test
    void equivalentRemotingCodecsReuseSharedFuryLocal() {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        FuryRemotingCodecFactory.FuryRemotingCodec first =
                (FuryRemotingCodecFactory.FuryRemotingCodec) factory.newCodec();
        FuryRemotingCodecFactory.FuryRemotingCodec second =
                (FuryRemotingCodecFactory.FuryRemotingCodec) factory.newCodec();

        first.fury();
        second.fury();

        assertSame(first.furyLocal, second.furyLocal);
    }

    @Test
    void roundTripDateTimeParameterWithCustomSerializer() throws Exception {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        RemotingCodec codec = factory.newCodec();

        DateTime dateTime = new DateTime(1711960245123L, TimeZone.getTimeZone("UTC"));
        MethodMessage method = new MethodMessage(15, "withDateTime", new Object[]{dateTime}, "trace-15");
        Object decoded = roundTrip(codec, method);
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        DateTime decodedDateTime = assertInstanceOf(DateTime.class, pack.parameters[0]);
        assertEquals(dateTime.getTime(), decodedDateTime.getTime());
        assertEquals(dateTime.getTimeZone().getID(), decodedDateTime.getTimeZone().getID());
    }

    @Test
    void roundTripInetAddressReturnValueWithCustomSerializer() throws Exception {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        RemotingCodec codec = factory.newCodec();

        InetAddress ipv4 = InetAddress.getByName("198.51.100.7");
        InetAddress ipv6 = InetAddress.getByName("2001:db8::7");
        MethodMessage method = new MethodMessage(16, "resolveHost", new Object[]{null, null}, "trace-16");
        method.returnValue = new ArrayList<InetAddress>(Arrays.asList(ipv4, ipv6));

        Object decoded = roundTrip(codec, method);
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        List<?> addresses = assertInstanceOf(List.class, pack.returnValue);
        assertEquals(2, addresses.size());
        assertEquals(ipv4, addresses.get(0));
        assertEquals(ipv6, addresses.get(1));

        method.returnValue = Collections.singletonList(ipv4);
        decoded = roundTrip(codec, method);
        pack = assertInstanceOf(MethodMessage.class, decoded);
        addresses = assertInstanceOf(List.class, pack.returnValue);
        assertEquals(Collections.singletonList(ipv4), addresses);

        method.returnValue = Collections.emptyList();
        decoded = roundTrip(codec, method);
        pack = assertInstanceOf(MethodMessage.class, decoded);
        addresses = assertInstanceOf(List.class, pack.returnValue);
        assertTrue(addresses.isEmpty());
    }

    @Test
    void roundTripUnresolvedInetSocketAddressWithoutLocalDnsResolve() throws Exception {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        RemotingCodec codec = factory.newCodec();

        InetSocketAddress endpoint = InetSocketAddress.createUnresolved("example.com", 443);
        MethodMessage method = new MethodMessage(17, "connect", new Object[]{endpoint}, "trace-17");

        Object decoded = roundTrip(codec, method);
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        InetSocketAddress decodedEndpoint = assertInstanceOf(InetSocketAddress.class, pack.parameters[0]);
        assertEquals("example.com", decodedEndpoint.getHostString());
        assertEquals(443, decodedEndpoint.getPort());
        assertNull(decodedEndpoint.getAddress());

        InetSocketAddress ipLiteralEndpoint = InetSocketAddress.createUnresolved("198.51.100.8", 5300);
        method = new MethodMessage(18, "connectIp", new Object[]{ipLiteralEndpoint}, "trace-18");
        decoded = roundTrip(codec, method);
        pack = assertInstanceOf(MethodMessage.class, decoded);
        decodedEndpoint = assertInstanceOf(InetSocketAddress.class, pack.parameters[0]);
        assertEquals("198.51.100.8", decodedEndpoint.getHostString());
        assertEquals(5300, decodedEndpoint.getPort());
        assertNotNull(decodedEndpoint.getAddress());
        assertEquals("198.51.100.8", decodedEndpoint.getAddress().getHostAddress());

        InetSocketAddress resolvedEndpoint = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 9}), 5301);
        method = new MethodMessage(19, "connectResolvedIp", new Object[]{resolvedEndpoint}, "trace-19");
        decoded = roundTrip(codec, method);
        pack = assertInstanceOf(MethodMessage.class, decoded);
        decodedEndpoint = assertInstanceOf(InetSocketAddress.class, pack.parameters[0]);
        assertEquals(5301, decodedEndpoint.getPort());
        assertNotNull(decodedEndpoint.getAddress());
        assertEquals("198.51.100.9", decodedEndpoint.getAddress().getHostAddress());
    }

    private static Object roundTrip(RemotingCodec codec, Object message) throws Exception {
        ByteBuf encoded = codec.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            assertTrue(encoded.readableBytes() >= 8);
            Object decoded = codec.decode(encoded);
            assertNotNull(decoded);
            assertEquals(0, encoded.readableBytes());
            return decoded;
        } finally {
            encoded.release();
        }
    }
}
