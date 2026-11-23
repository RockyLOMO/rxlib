package org.rx.net.udp;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UdpSequenceWindowTest {
    @Test
    void matchesBoundedHistoryAcrossWordBoundariesJumpsAndSerialWrap() {
        for (int size : new int[]{64, UdpSequenceWindow.FEC_SIZE}) {
            UdpSequenceWindow window = new UdpSequenceWindow(size);
            Set<Long> seen = new HashSet<>();
            long newest = 0xfffffff0L;
            assertTrue(window.acceptSerial((int) newest));
            seen.add(newest);
            Random random = new Random(971);
            for (int i = 0; i < 12000; i++) {
                long seq;
                switch (i % 6) {
                    case 0: seq = newest + 1; break;
                    case 1: seq = newest + random.nextInt(size * 2); break;
                    case 2: seq = newest - size; break;
                    case 3: seq = newest - size + 1; break;
                    default: seq = newest - random.nextInt(size); break;
                }
                boolean expected = newest - seq < size && seen.add(seq);
                assertEquals(expected, window.acceptSerial((int) seq));
                newest = Math.max(newest, seq);
            }
        }
    }
}
