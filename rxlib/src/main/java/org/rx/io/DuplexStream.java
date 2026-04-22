package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.annotation.ErrorCode;
import org.rx.core.Constants;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.exception.ApplicationException;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import static org.rx.core.Extends.*;

public abstract class DuplexStream extends InputStream implements Flushable, Extends {
    public static final int NON_READ_FULLY = -1;

    private volatile boolean closed;
    private transient InputStream inputStream;
    private transient OutputStream outputStream;

    public static DuplexStream wrap(String filePath) {
        return wrap(new File(filePath));
    }

    public static DuplexStream wrap(@NonNull File file) {
        return new FileStream(file);
    }

    public static DuplexStream wrap(String name, byte[] data) {
        HybridStream stream = new HybridStream();
        stream.setName(ifNull(name, Strings.EMPTY));
        stream.write(data);
        return stream.rewind();
    }

    public static DuplexStream wrap(String name, InputStream in) {
        HybridStream stream = new HybridStream();
        stream.setName(ifNull(name, Strings.EMPTY));
        stream.write(in);
        return stream.rewind();
    }

    @SneakyThrows
    public static long copy(@NonNull InputStream in, long length, @NonNull OutputStream out) {
        byte[] buffer = new byte[Constants.MEDIUM_BUF];
        boolean readFully = length != NON_READ_FULLY;
        long copyLen = 0;
        int read;
        while ((!readFully || copyLen < length)
                && (read = in.read(buffer, 0, readFully ? Math.min(buffer.length, safeRemaining(length - copyLen)) : buffer.length)) != Constants.IO_EOF) {
            out.write(buffer, 0, read);
            copyLen += read;
        }
        out.flush();
        return copyLen;
    }

    public static long checksum(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    public static long checksum(InputStream stream) {
        return checksum(stream, 1024);
    }

    @SneakyThrows
    public static long checksum(InputStream stream, int bufferSize) {
        CheckedInputStream checkedInputStream = new CheckedInputStream(stream, new CRC32());
        byte[] buffer = new byte[bufferSize];
        while (checkedInputStream.read(buffer, 0, buffer.length) >= 0) {
        }
        return checkedInputStream.getChecksum().getValue();
    }

    @SneakyThrows
    public static String readString(@NonNull InputStream in, Charset charset) {
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }

        ByteBuf buf = Bytes.heapBuffer();
        try {
            int chunkSize = Constants.KB;
            while (buf.writeBytes(in, chunkSize) != Constants.IO_EOF) {
            }
            return buf.toString(charset);
        } finally {
            buf.release();
        }
    }

    @SneakyThrows
    public static void writeString(@NonNull OutputStream out, @NonNull String value, Charset charset) {
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }

        out.write(value.getBytes(charset));
    }

    static int safeRemaining(long remaining) {
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    public final InputStream getReader() {
        return this;
    }

    public final synchronized InputStream asInputStream() {
        if (inputStream == null) {
            inputStream = new InputStream() {
                @Override
                public int available() {
                    return DuplexStream.this.available();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return DuplexStream.this.read(b, off, len);
                }

                @Override
                public int read() {
                    return DuplexStream.this.read();
                }

                @Override
                public boolean markSupported() {
                    return DuplexStream.this.markSupported();
                }

                @Override
                public synchronized void mark(int readlimit) {
                    DuplexStream.this.mark(readlimit);
                }

                @Override
                public synchronized void reset() throws IOException {
                    DuplexStream.this.reset();
                }

                @Override
                public void close() {
                    // Borrowed view: callers may close it without owning the stream.
                }
            };
        }
        return inputStream;
    }

    public final synchronized OutputStream asOutputStream() {
        if (outputStream == null) {
            outputStream = new OutputStream() {
                @Override
                public void write(byte[] b, int off, int len) {
                    DuplexStream.this.write(b, off, len);
                }

                @Override
                public void write(int b) {
                    DuplexStream.this.write(b);
                }

                @Override
                public void flush() {
                    DuplexStream.this.flush();
                }

                @Override
                public void close() {
                    if (!DuplexStream.this.isClosed()) {
                        DuplexStream.this.flush();
                    }
                }
            };
        }
        return outputStream;
    }

    public abstract String getName();

    @Override
    public abstract int read();

    public final boolean isClosed() {
        return closed;
    }

    protected void dispose() throws Throwable {
        flush();
    }

    @ErrorCode
    protected final void checkNotClosed() {
        if (closed) {
            throw new ApplicationException(values(this.getClass().getSimpleName()));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            dispose();
        } catch (Throwable e) {
            // keep the historical stream close() behavior: close quietly.
        } finally {
            closed = true;
        }
    }

    public boolean canSeek() {
        return false;
    }

    @ErrorCode
    public long getPosition() {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public void setPosition(long position) {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public long getLength() {
        throw new ApplicationException(values(this.getClass().getSimpleName()));
    }

    public long availableBytes() {
        if (isClosed()) {
            return 0;
        }
        if (!canSeek()) {
            return 0;
        }
        return Math.max(0, getLength() - getPosition());
    }

    @Override
    public int available() {
        return safeRemaining(availableBytes());
    }

    @Override
    public int read(@NonNull byte[] buffer) {
        checkNotClosed();

        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) {
        checkNotClosed();
        checkArrayRange(buffer, offset, length);
        if (length == 0) {
            return 0;
        }

        int value = read();
        if (value == Constants.IO_EOF) {
            return Constants.IO_EOF;
        }
        buffer[offset] = (byte) value;
        int read = 1;
        for (; read < length; read++) {
            value = read();
            if (value == Constants.IO_EOF) {
                break;
            }
            buffer[offset + read] = (byte) value;
        }
        return read;
    }

    public long read(DuplexStream stream) {
        return read(stream, NON_READ_FULLY);
    }

    public long read(@NonNull DuplexStream stream, long length) {
        return read(stream.asOutputStream(), length);
    }

    public long read(OutputStream out) {
        return read(out, NON_READ_FULLY);
    }

    public long read(OutputStream out, long length) {
        checkNotClosed();

        return copy(this, length, out);
    }

    public int read(ByteBuf dst) {
        int total = 0, read;
        while ((read = read(dst, Constants.HEAP_BUF_SIZE)) > 0) {
            total += read;
        }
        return total;
    }

    @SneakyThrows
    public int read(ByteBuf dst, int length) {
        checkNotClosed();
        checkLength(length);
        if (length == 0) {
            return 0;
        }
        return dst.writeBytes(this, length);
    }

    @SneakyThrows
    public short readShort() {
        int ch1 = read();
        int ch2 = read();
        if ((ch1 | ch2) < 0) {
            throw new EOFException();
        }
        return (short) ((ch1 << 8) + (ch2 << 0));
    }

    @SneakyThrows
    public int readInt() {
        int ch1 = read();
        int ch2 = read();
        int ch3 = read();
        int ch4 = read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
    }

    public String readString(int length) {
        return readString(length, StandardCharsets.UTF_8);
    }

    public String readString(int length, Charset charset) {
        ByteBuf buf = Bytes.heapBuffer();
        try {
            read(buf, length);
            return buf.toString(charset);
        } finally {
            buf.release();
        }
    }

    @ErrorCode
    public void write(int b) {
        throw new ApplicationException(values(this.getClass().getSimpleName()));
    }

    public void write(@NonNull byte[] buffer) {
        checkNotClosed();

        write(buffer, 0, buffer.length);
    }

    public void write(@NonNull byte[] buffer, int offset, int length) {
        checkNotClosed();
        checkArrayRange(buffer, offset, length);
        for (int i = 0; i < length; i++) {
            write(buffer[offset + i] & 0xff);
        }
    }

    public long write(DuplexStream stream) {
        return write(stream, NON_READ_FULLY);
    }

    public long write(@NonNull DuplexStream stream, long length) {
        return write((InputStream) stream, length);
    }

    public long write(InputStream in) {
        return write(in, NON_READ_FULLY);
    }

    public long write(InputStream in, long length) {
        checkNotClosed();

        return copy(in, length, asOutputStream());
    }

    public void write(ByteBuf src) {
        write(src, src.readableBytes());
    }

    @SneakyThrows
    public void write(ByteBuf src, int length) {
        checkNotClosed();
        checkLength(length);
        src.readBytes(asOutputStream(), length);
    }

    public void writeShort(short n) {
        write((n >>> 8) & 0xFF);
        write((n >>> 0) & 0xFF);
    }

    public void writeInt(int n) {
        write((n >>> 24) & 0xFF);
        write((n >>> 16) & 0xFF);
        write((n >>> 8) & 0xFF);
        write((n >>> 0) & 0xFF);
    }

    public void writeString(String str) {
        writeString(str, StandardCharsets.UTF_8);
    }

    public void writeString(String str, Charset charset) {
        write(str.getBytes(charset));
    }

    @Override
    public void flush() {
        checkNotClosed();
    }

    public byte[] toArray() {
        checkNotClosed();

        long pos = getPosition();
        setPosition(0);
        byte[] data = new byte[(int) getLength()];
        read(data);
        setPosition(pos);
        return data;
    }

    public final <T extends DuplexStream> T rewind() {
        setPosition(0);
        return (T) this;
    }

    protected static void checkArrayRange(byte[] buffer, int offset, int length) {
        if ((offset | length) < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException();
        }
    }

    protected static void checkLength(int length) {
        if (length < 0) {
            throw new IndexOutOfBoundsException();
        }
    }
}
