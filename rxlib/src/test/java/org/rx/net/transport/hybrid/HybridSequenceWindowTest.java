package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSequenceWindowTest {
    @Test
    void duplicateSeqIsRejected() {
        HybridSequenceWindow window = new HybridSequenceWindow(1000);

        assertTrue(window.mark(7));
        assertFalse(window.mark(7));
        assertTrue(window.mark(8));
    }
}
