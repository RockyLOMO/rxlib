package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.Sys;
import org.rx.net.transport.TcpChannelCodec;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.protocol.PingPacket;
import org.rx.test.PersonBean;
import org.rx.test.UserEventArgs;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuryRemotingCodecTest {
    @Test
    void roundTripMethodMessageAndPingPacket() {
        ResourceLeakDetector.Level oldLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            RpcClientConfig<Object> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", 9527), 1);
            FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
            TcpChannelCodec codec = factory.newClientCodec(config);
            List<String> allowedPrefixes = new ArrayList<>(factory.allowedClassPrefixes);

            MethodMessage method = new MethodMessage(7, "create",
                    new Object[]{PersonBean.LeZhi, new UserEventArgs(PersonBean.LeZhi)}, "trace-7");
            Object decodedMethod = roundTrip(codec, allowedPrefixes, method);
            MethodMessage methodPack = assertInstanceOf(MethodMessage.class, decodedMethod);
            assertEquals(method.id, methodPack.id);
            assertEquals(method.methodName, methodPack.methodName);
            assertEquals(PersonBean.LeZhi.getName(), ((PersonBean) methodPack.parameters[0]).getName());
            assertEquals(PersonBean.LeZhi.getName(), ((UserEventArgs) methodPack.parameters[1]).getUser().getName());

            Object decodedPing = roundTrip(codec, allowedPrefixes, new PingPacket());
            assertInstanceOf(PingPacket.class, decodedPing);
        } finally {
            ResourceLeakDetector.setLevel(oldLevel);
        }
    }

    @Test
    void codecSurvivesTcpConfigDeepClone() {
        RpcClientConfig<Object> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", 9528), 1);
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        config.getTcpConfig().setCodec(factory.newClientCodec(config));

        TcpClientConfig clone = Sys.deepClone(config.getTcpConfig());
        assertNotNull(clone.getCodec());
        List<String> allowedPrefixes = new ArrayList<>(factory.allowedClassPrefixes);

        Object decoded = roundTrip(clone.getCodec(), allowedPrefixes, new MethodMessage(11, "ping", new Object[]{"ok"}, "trace-11"));
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        assertEquals("ok", pack.parameters[0]);
    }

    @Test
    void equivalentRemotingCodecsReuseSharedFuryLocal() {
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        FuryRemotingCodecFactory.FuryRemotingUdpCodec first =
                (FuryRemotingCodecFactory.FuryRemotingUdpCodec) factory.newCodec();
        FuryRemotingCodecFactory.FuryRemotingUdpCodec second =
                (FuryRemotingCodecFactory.FuryRemotingUdpCodec) factory.newCodec();

        first.fury();
        second.fury();

        assertSame(first.furyLocal, second.furyLocal);

        List<String> allowedPrefixes = new ArrayList<>(factory.allowedClassPrefixes);
        FuryRemotingCodecFactory.FuryMessageDecoder firstDecoder =
                new FuryRemotingCodecFactory.FuryMessageDecoder(
                        new FuryRemotingCodecFactory.FuryRemotingSupport(new ArrayList<>(allowedPrefixes)));
        FuryRemotingCodecFactory.FuryMessageDecoder secondDecoder =
                new FuryRemotingCodecFactory.FuryMessageDecoder(
                        new FuryRemotingCodecFactory.FuryRemotingSupport(new ArrayList<>(allowedPrefixes)));

        assertSame(firstDecoder.handlerSupport.furyLocal, secondDecoder.handlerSupport.furyLocal);
    }

    @Test
    void roundTripDateTimeParameterWithCustomSerializer() {
        RpcClientConfig<Object> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", 9529), 1);
        FuryRemotingCodecFactory factory = FuryRemotingCodecFactory.createDefault();
        TcpChannelCodec codec = factory.newClientCodec(config);
        List<String> allowedPrefixes = new ArrayList<>(factory.allowedClassPrefixes);

        DateTime dateTime = new DateTime(1711960245123L, TimeZone.getTimeZone("UTC"));
        MethodMessage method = new MethodMessage(15, "withDateTime", new Object[]{dateTime}, "trace-15");
        Object decoded = roundTrip(codec, allowedPrefixes, method);
        MethodMessage pack = assertInstanceOf(MethodMessage.class, decoded);
        DateTime decodedDateTime = assertInstanceOf(DateTime.class, pack.parameters[0]);
        assertEquals(dateTime.getTime(), decodedDateTime.getTime());
        assertEquals(dateTime.getTimeZone().getID(), decodedDateTime.getTimeZone().getID());
    }

    private static Object roundTrip(TcpChannelCodec codec, List<String> allowedPrefixes, Object message) {
        EmbeddedChannel outbound = new EmbeddedChannel();
        EmbeddedChannel inbound = new EmbeddedChannel(
                new FuryRemotingCodecFactory.FuryMessageDecoder(
                        new FuryRemotingCodecFactory.FuryRemotingSupport(new ArrayList<>(allowedPrefixes))));
        try {
            codec.install(outbound.pipeline());
            assertTrue(outbound.writeOutbound(message));
            ByteBuf encoded = Unpooled.buffer();
            for (ByteBuf part; (part = outbound.readOutbound()) != null; ) {
                try {
                    encoded.writeBytes(part);
                } finally {
                    ReferenceCountUtil.release(part);
                }
            }
            assertTrue(encoded.readableBytes() > Integer.BYTES);
            ByteBuf frame = null;
            try {
                int frameLength = encoded.readInt();
                assertEquals(frameLength, encoded.readableBytes());
                frame = encoded.readRetainedSlice(frameLength);
                assertTrue(inbound.writeInbound(frame));
            } finally {
                if (frame != null && frame.refCnt() > 0) {
                    ReferenceCountUtil.release(frame);
                }
                if (encoded.refCnt() > 0) {
                    ReferenceCountUtil.release(encoded);
                }
            }
            inbound.checkException();
            Object decoded = inbound.readInbound();
            assertNotNull(decoded);
            return decoded;
        } finally {
            outbound.finishAndReleaseAll();
            inbound.finishAndReleaseAll();
        }
    }
}
