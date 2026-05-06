package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Udp2rawMtuStateTest {
    @Test
    void ackRaisesMtuAndRepeatedMissVerifiesCurrent() {
        Udp2rawMtuState state = new Udp2rawMtuState(1300, 1200, 1400);
        long now = System.currentTimeMillis() + 1000L;

        Udp2rawMtuState.Probe verify = state.nextProbe(now);
        assertNotNull(verify);
        assertEquals(1300, verify.mtu);
        assertTrue(state.ack(verify.seq, now + 10L));
        assertEquals(1300, state.currentMtu());

        Udp2rawMtuState.Probe up = state.nextProbe(now + 20_000L);
        assertNotNull(up);
        assertEquals(1320, up.mtu);
        state.nextProbe(now + 23_000L);
        assertEquals(1300, state.currentMtu(), "上探失败不应立即降低已验证 MTU");

        Udp2rawMtuState.Probe upAgain = state.nextProbe(now + 30_000L);
        assertNotNull(upAgain);
        assertEquals(1320, upAgain.mtu);
        state.nextProbe(now + 33_000L);
        assertEquals(1300, state.currentMtu());

        Udp2rawMtuState.Probe current = state.nextProbe(now + 40_000L);
        assertNotNull(current);
        assertEquals(1300, current.mtu);
        state.nextProbe(now + 43_000L);
        assertEquals(1220, state.currentMtu(), "当前 MTU 验证失败才快速下调");
    }

    @Test
    void acceptedSizeClampsAckedProbeMtu() {
        Udp2rawMtuState state = new Udp2rawMtuState(1300, 1200, 1400);
        long now = System.currentTimeMillis() + 1000L;

        Udp2rawMtuState.Probe verify = state.nextProbe(now);
        assertTrue(state.ack(verify.seq, 1300, now + 10L));
        Udp2rawMtuState.Probe up = state.nextProbe(now + 20_000L);
        assertEquals(1320, up.mtu);

        assertTrue(state.ack(up.seq, 1310, now + 20_010L));

        assertEquals(1310, state.currentMtu());
    }

    @Test
    void writeMtuDropOnlyLowersWhenDatagramWasWithinCurrentMtu() {
        Udp2rawMtuState state = new Udp2rawMtuState(1300, 1200, 1400);
        long now = System.currentTimeMillis() + 1000L;

        state.onWriteMtuDrop(1400, now);
        assertEquals(1300, state.currentMtu(), "业务包超过当前 MTU 时只记录，不应越降越小");

        state.onWriteMtuDrop(1300, now + 1L);

        assertEquals(1220, state.currentMtu());
    }

    @Test
    void defaultStateDoesNotProbeAboveHardInitialMtu() {
        Udp2rawMtuState state = new Udp2rawMtuState(1300);
        long now = System.currentTimeMillis() + 1000L;

        Udp2rawMtuState.Probe verify = state.nextProbe(now);
        assertEquals(1300, verify.mtu);
        assertTrue(state.ack(verify.seq, 1300, now + 10L));
        Udp2rawMtuState.Probe next = state.nextProbe(now + 20_000L);

        assertEquals(1300, next.mtu);
    }

    @Test
    void initialBelowMinRespectsHardCap() {
        Udp2rawMtuState state = new Udp2rawMtuState(1000, "client");
        long now = System.currentTimeMillis() + 1000L;

        assertEquals(1000, state.currentMtu());
        Udp2rawMtuState.Probe probe = state.nextProbe(now);

        assertEquals(1000, probe.mtu);
    }

    @Test
    void ackAcceptedMtuRequiresExactlyFourBytes() {
        assertEquals(-1, Udp2rawMtuProbeSupport.readAckAcceptedMtu(Unpooled.EMPTY_BUFFER));
        ByteBuf three = Unpooled.buffer(3).writeMedium(1300);
        ByteBuf five = Unpooled.buffer(5).writeInt(1300).writeByte(0);
        ByteBuf zero = Unpooled.buffer(4).writeInt(0);
        try {
            assertEquals(-1, Udp2rawMtuProbeSupport.readAckAcceptedMtu(three));
            assertEquals(-1, Udp2rawMtuProbeSupport.readAckAcceptedMtu(five));
            assertEquals(-1, Udp2rawMtuProbeSupport.readAckAcceptedMtu(zero));
        } finally {
            three.release();
            five.release();
            zero.release();
        }
    }
}
