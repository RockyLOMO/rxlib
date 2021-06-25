package org.rx.test;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.io.*;
import org.rx.test.bean.PersonBean;
import org.rx.test.common.TestUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.rx.core.App.toJsonString;

public class IOTester {
    @Test
    public void releaseBuffer() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64);
        IOStream.release(buffer);
    }

    @Test
    public void hybridStream() {
        HybridStream stream = new HybridStream(16);
        testSeekStream(stream); // 12 len

        byte[] bytes = "wanglezhi".getBytes();
        stream.write(bytes);
        assert stream.getPosition() == 12 + bytes.length && stream.getLength() == stream.getPosition();
        byte[] data = new byte[(int) stream.getLength()];
        stream.setPosition(0L);
        stream.read(data);
        System.out.println(new String(data));
    }

    @SneakyThrows
    @Test
    public void fileStream() {
        FileStream stream = new FileStream();
        testSeekStream(stream);

        InputStream reader = stream.getReader();
        OutputStream writer = stream.getWriter();

        long len = stream.getLength();
        writer.write(content);
        writer.flush();
        stream.setPosition(0L);
        System.out.println(IOStream.readString(reader, StandardCharsets.UTF_8));
        assert stream.getLength() > len;

        stream.setPosition(1L);
        assert reader.available() == stream.getLength() - 1L;

        stream.setPosition(0L);
        ByteBuf buf = Bytes.directBuffer();
        buf.writeBytes(content);
        long write = stream.write(buf);
        assert buf.readerIndex() == stream.getPosition();
        assert stream.getPosition() == write && write == buf.writerIndex();

        stream.setPosition(0L);
        buf = Bytes.directBuffer(buf.writerIndex(), false);
        long read = stream.read(buf);
        assert stream.getPosition() == read;
        System.out.println(buf.toString(StandardCharsets.UTF_8));

        FileStream fs = new FileStream(String.format(nameFormat, "mmap"));
        CompositeMmap mmap = fs.mmap(FileChannel.MapMode.READ_WRITE, 0, Integer.MAX_VALUE * 2L + 1);

        testMmap(mmap, 1);
        testMmap(mmap, Integer.MAX_VALUE + 1L);
        testMmap(mmap, mmap.length() - 12);

//        fs.setLength(Integer.MAX_VALUE);

        mmap.close();
    }

    private void testMmap(CompositeMmap mmap, long position) {
        ByteBuf buf = Bytes.directBuffer();
        buf.writeLong(1024);
        buf.writeInt(512);
        int write = mmap.write(position, buf);
        assert buf.readerIndex() == buf.writerIndex();
        assert write == buf.writerIndex();

        buf = Bytes.directBuffer();
        buf.capacity(12);
        int read = mmap.read(position, buf);
        assert buf.capacity() == read;
        assert buf.readLong() == 1024;
        assert buf.readInt() == 512;
    }

    final boolean doWrite = true;
    final String nameFormat = "C:\\download\\%s.txt";

    @SneakyThrows
    @Test
    public void fileBuf64K() {
        BufferedRandomAccessFile fd = new BufferedRandomAccessFile(String.format(nameFormat, 64), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        TestUtil.invoke("fileBuf64K", () -> {
            if (doWrite) {
                fd.write(UUID.randomUUID().toString().getBytes());
                return;
            }
            fd.read(UUID.randomUUID().toString().getBytes());
        });
    }

    @SneakyThrows
    @Test
    public void fileBuf4K() {
        BufferedRandomAccessFile fd = new BufferedRandomAccessFile(String.format(nameFormat, 4), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        TestUtil.invoke("fileBuf4K", () -> {
            if (doWrite) {
                fd.write(UUID.randomUUID().toString().getBytes());
                return;
            }
            fd.read(UUID.randomUUID().toString().getBytes());
        });
    }

    @Test
    public void memoryStream() {
//        MemoryStream stream = new MemoryStream(32, true);
        DirectMemoryStream stream = new DirectMemoryStream();
        testSeekStream(stream);

        stream.setPosition(0L);
        for (int i = 0; i < 40; i++) {
            stream.write(i);
        }
//        System.out.printf("Position=%s, Length=%s, Capacity=%s%n", stream.getPosition(),
//                stream.getLength(), stream.getBuffer().length);

        stream.setPosition(0L);
        System.out.println(stream.read());

        IOStream<?, ?> serializeStream = IOStream.serialize(stream);
//        MemoryStream newStream = IOStream.deserialize(serializeStream);
//        newStream.setPosition(0L);
//        byte[] bytes = newStream.toArray();
//        for (int i = 0; i < 40; i++) {
//            assert bytes[i] == i;
//        }
    }

    @Test
    public void testBinaryStream() {
        BinaryStream stream = new BinaryStream(new MemoryStream());
        stream.writeString("test hello");

        stream.writeInt(100);
        stream.writeLine("di yi hang");
        stream.writeLine("di er hang");

        stream.setPosition(0);
        System.out.println(stream.readString());
        System.out.println(stream.readInt());

        String line;
        while ((line = stream.readLine()) != null) {
            System.out.println(line);
        }

        PersonBean bean = new PersonBean();
        bean.setName("hello");
        bean.setAge(12);
        bean.setMoneyCent(250L);
        stream.setPosition(0);
        stream.writeObject(bean);

        stream.setPosition(0);
        PersonBean newBean = stream.readObject();

        System.out.println(toJsonString(bean));
        System.out.println(toJsonString(newBean));
    }

    final byte[] content = "Hello world, 王湵范!".getBytes();

    private void testSeekStream(IOStream<?, ?> stream) {
        stream.write(content);
        assert stream.getPosition() == content.length && stream.getLength() == content.length;
        stream.setPosition(0L);
        assert stream.available() == stream.getLength();
        byte[] data = new byte[content.length];
        int count = stream.read(data);
        assert stream.getPosition() == count && stream.getLength() == data.length;
        assert Arrays.equals(content, data);

        long pos = stream.getPosition();
        long len = stream.getLength();
        IOStream<?, ?> newStream = App.deepClone(stream);
        assert pos == newStream.getPosition() && len == newStream.getLength();
    }

    @Test
    public void listFiles() {
        for (File p : Files.listFiles("/", false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listFiles("/", true)) {
            System.out.println(p);
        }
    }

    @Test
    public void listDirectories() {
        String dirPath = "E:\\rdbc\\mysql-backup";
        Path path = Files.path(dirPath);
        System.out.println(path.getRoot());
        System.out.println(path.getFileName());
        System.out.println("---");
        for (File p : Files.listDirectories(dirPath, false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listDirectories(dirPath, true)) {
            System.out.println(p);
        }
    }
}
