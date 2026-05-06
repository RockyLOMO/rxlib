package org.rx.net.socks;

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
}
