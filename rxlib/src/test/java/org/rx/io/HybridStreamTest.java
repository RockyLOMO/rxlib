package org.rx.io;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HybridStreamTest {
    @Test
    public void largeSingleWriteKeepsMemoryPrefixAndSpillsSuffix() {
        byte[] data = range(24);
        try (HybridStream stream = new HybridStream(8, false)) {
            stream.write(data);

            assertEquals(data.length, stream.getLength());
            assertEquals(data.length, stream.getPosition());
            assertTrue(stream.hasFileStream());
            assertEquals(8, stream.getMemoryLength());
            assertEquals(data.length - 8, stream.getFileLength());

            stream.setPosition(0);
            byte[] actual = new byte[data.length];
            assertEquals(data.length, stream.read(actual));
            assertArrayEquals(data, actual);
        }
    }

    @Test
    public void cachedWriterStillSpillsWhenThresholdIsExceeded() throws Exception {
        byte[] data = range(24);
        try (HybridStream stream = new HybridStream(8, false)) {
            OutputStream writer = stream.asOutputStream();
            writer.write(data, 0, 6);
            writer.write(data, 6, data.length - 6);
            writer.flush();

            assertTrue(stream.hasFileStream());
            assertEquals(data.length, stream.getLength());
            stream.setPosition(0);
            byte[] actual = new byte[data.length];
            assertEquals(data.length, stream.read(actual));
            assertArrayEquals(data, actual);
        }
    }

    @Test
    public void byteBufWriteAndReadSplitAcrossMemoryAndFile() {
        byte[] data = range(24);
        ByteBuf src = Bytes.directBuffer(data.length);
        ByteBuf dst = Bytes.directBuffer(data.length);
        try (HybridStream stream = new HybridStream(8, false)) {
            src.writeBytes(data);
            stream.write(src, 20);

            assertEquals(20, src.readerIndex());
            assertTrue(stream.hasFileStream());
            assertEquals(8, stream.getMemoryLength());
            assertEquals(12, stream.getFileLength());

            stream.setPosition(0);
            assertEquals(20, stream.read(dst, 20));
            byte[] actual = new byte[20];
            dst.readBytes(actual);
            assertArrayEquals(Arrays.copyOf(data, 20), actual);
        } finally {
            Bytes.release(src);
            Bytes.release(dst);
        }
    }

    @Test
    public void boundedInputStreamWriteDoesNotOverRead() {
        byte[] data = range(24);
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try (HybridStream stream = new HybridStream(8, false)) {
            assertEquals(10, stream.write(in, 10));
            assertEquals(data.length - 10, in.available());
            assertEquals(10, stream.getLength());
        }
    }

    @Test
    public void rewriteAfterRewindTruncatesOldContent() {
        byte[] oldData = range(16);
        byte[] newData = range(4);
        try (HybridStream stream = new HybridStream(8, false)) {
            stream.write(oldData);
            stream.setPosition(0);
            stream.write(newData);

            assertEquals(newData.length, stream.getLength());
            stream.setPosition(0);
            byte[] actual = new byte[newData.length];
            assertEquals(newData.length, stream.read(actual));
            assertArrayEquals(newData, actual);
        }
    }

    @Test
    public void ownedTempFileIsDeletedOnClose() {
        File file;
        try (HybridStream stream = new HybridStream(4, false)) {
            stream.write(range(16));
            String path = stream.getFilePath();

            assertNotNull(path);
            file = new File(path);
            assertTrue(file.exists());
        }

        assertFalse(file.exists(), file.getAbsolutePath());
    }

    @Test
    public void getReaderReturnsSelf() {
        try (HybridStream stream = new HybridStream(8, false)) {
            assertSame(stream, stream.getReader());
        }
    }

    @Test
    public void borrowedAdaptersDoNotCloseBaseStream() throws Exception {
        try (HybridStream stream = new HybridStream(8, false)) {
            OutputStream out = stream.asOutputStream();
            out.write(range(4));
            out.close();

            assertFalse(stream.isClosed());
            stream.setPosition(0);

            InputStream in = stream.asInputStream();
            assertEquals(0, in.read());
            in.close();

            assertFalse(stream.isClosed());
            assertEquals(1, stream.getPosition());
        }
    }

    private static byte[] range(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }
}
