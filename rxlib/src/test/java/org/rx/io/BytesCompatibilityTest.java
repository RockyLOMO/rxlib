package org.rx.io;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class BytesCompatibilityTest {
    @Test
    void releaseDirectBufferShouldNotThrow() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        assertDoesNotThrow(() -> Bytes.release(buffer));
    }
}
