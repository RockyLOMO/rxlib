package org.rx.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetworkFlowDiagnosticsTest {
    @Test
    public void testFlowDebugFlags() {
        assertFalse(NetworkFlowDiagnostics.hasFlag(NetworkFlowDiagnostics.FLAGS_OFF,
                NetworkFlowDiagnostics.FLAG_REPORT));

        int scene4Flags = NetworkFlowDiagnostics.FLAG_REPORT | NetworkFlowDiagnostics.FLAG_TOP_CHANNELS;
        assertTrue(NetworkFlowDiagnostics.hasFlag(scene4Flags, NetworkFlowDiagnostics.FLAG_REPORT));
        assertTrue(NetworkFlowDiagnostics.hasFlag(scene4Flags, NetworkFlowDiagnostics.FLAG_TOP_CHANNELS));
        assertFalse(NetworkFlowDiagnostics.hasFlag(scene4Flags, NetworkFlowDiagnostics.FLAG_UDP_DROPS));

        int fullFlags = scene4Flags | NetworkFlowDiagnostics.FLAG_UDP_DROPS;
        assertTrue(NetworkFlowDiagnostics.hasFlag(fullFlags, NetworkFlowDiagnostics.FLAG_UDP_DROPS));
    }
}
