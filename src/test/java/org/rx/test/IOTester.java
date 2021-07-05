package org.rx.test;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.io.*;
import org.rx.test.bean.GirlBean;
import org.rx.test.bean.PersonBean;
import org.rx.test.common.TestUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.App.sleep;
import static org.rx.core.App.toJsonString;

@Slf4j
public class IOTester {
    @Test
    public void releaseBuffer() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64);
        IOStream.release(buffer);
    }

    final String baseDir = "C:\\download";
    final String filePathFormat = baseDir + "\\%s.txt";
    final String KvPath = baseDir + "\\RxKv";
    final boolean doWrite = true;
    final byte[] content = "Hello world, 王湵范 & wanglezhi!".getBytes();

    @Test
    public void kvDb2() {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(KvPath);
        conf.setLogGrowSize(1024 * 1024 * 4);
        conf.setIndexBufferSize(1024 * 1024 * 4);
        KeyValueStore<Integer, String> kv = new KeyValueStore<>(conf, Serializer.DEFAULT);
        int loopCount = 10;
        TestUtil.invoke("kvdb", i -> {
//                int k = ThreadLocalRandom.current().nextInt(0, loopCount);
            int k = i;
            String val = kv.get(k);
            if (val == null) {
                kv.put(k, val = String.valueOf(k));
            }
            String newGet = kv.get(k);
            if (!val.equals(newGet)) {
                log.error("check: {} == {}", val, newGet);
            }
            assert val.equals(newGet);
        }, loopCount);
        kv.close();
    }

    @Test
    public void kvDb() {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(KvPath);
        conf.setLogGrowSize(1024 * 1024 * 4);
        conf.setIndexBufferSize(1024 * 1024);
        KeyValueStore<Integer, String> kv = new KeyValueStore<>(conf, Serializer.DEFAULT);
//        kv.clear();
        int loopCount = 100, removeK = 99;
        TestUtil.invoke("put", i -> {
            String val = kv.get(i);
            if (val == null) {
                val = DateTime.now().toString();
                if (i == removeK) {
                    System.out.println(1);
                }
                kv.put(i, val);
                String newGet = kv.get(i);
                while (newGet == null) {
                    newGet = kv.get(i);
                    sleep(1000);
                }
                log.info("put new {} {} -> {}", i, val, newGet);
                assert val.equals(newGet);
                if (i != removeK) {
                    assert kv.size() == i + 1;
                } else {
                    assert kv.size() == 100;
                    System.out.println("x:" + kv.size());
                }
            }

            val += "|";
            kv.put(i, val);
            String newGet = kv.get(i);
            while (newGet == null) {
                newGet = kv.get(i);
                sleep(1000);
                System.out.println("x:" + newGet);
            }
            log.info("put {} {} -> {}", i, val, newGet);
            assert val.equals(newGet);
        }, loopCount);
        log.info("remove {} {}", removeK, kv.remove(removeK));
        assert kv.size() == removeK;

        int mk = 1001, mk2 = 1002;
        kv.put(mk2, "a");
        log.info("remove {} {}", mk2, kv.remove(mk2, "a"));

//        kv.put(mk, null);
//        String s = kv.get(mk);
//        assert s == null;

        kv.close();
    }

    @Test
    public void hybridStream() {
        int[] maxSizes = new int[]{35, 70};
        for (int max : maxSizes) {
            HybridStream stream = new HybridStream(max, null);
            testSeekStream(stream);

            long position = stream.getPosition();
            System.out.println(position);
            stream.write(content);
            assert stream.getPosition() == position + content.length && stream.getLength() == stream.getPosition();
            byte[] data = new byte[(int) stream.getLength()];
            stream.setPosition(0L);
            stream.read(data);
            System.out.println(new String(data));
        }
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
        long write = stream.write0(buf);
        assert buf.readerIndex() == stream.getPosition();
        assert stream.getPosition() == write && write == buf.writerIndex();

        stream.setPosition(0L);
        buf = Bytes.directBuffer(buf.writerIndex(), false);
        long read = stream.read(buf);
        assert stream.getPosition() == read;
        System.out.println(buf.toString(StandardCharsets.UTF_8));

        FileStream fs = new FileStream(String.format(filePathFormat, "mmap"));
        CompositeMmap mmap = fs.mmap(FileChannel.MapMode.READ_WRITE, 0, Integer.MAX_VALUE * 2L + 1);
        testMmapStream(mmap);

        testMmap(mmap, 1);
        testMmap(mmap, Integer.MAX_VALUE + 1L);
        testMmap(mmap, mmap.getBlock().position + mmap.getBlock().size - 12);

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

    private void testMmapStream(IOStream<?, ?> stream) {
        stream.write(content);
        assert stream.getPosition() == content.length;
        stream.setPosition(0L);
//        assert stream.available() == 0;
        byte[] data = new byte[content.length];
        int count = stream.read(data);
        assert stream.getPosition() == count;
        assert Arrays.equals(content, data);

//        long pos = stream.getPosition();
//        IOStream<?, ?> newStream = App.deepClone(stream);
//        assert pos == newStream.getPosition();
    }

    @SneakyThrows
    @Test
    public void fileBuf64K() {
        BufferedRandomAccessFile fd = new BufferedRandomAccessFile(String.format(filePathFormat, 64), FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        TestUtil.invoke("fileBuf64K", i -> {
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
        BufferedRandomAccessFile fd = new BufferedRandomAccessFile(String.format(filePathFormat, 4), FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        TestUtil.invoke("fileBuf4K", i -> {
            if (doWrite) {
                fd.write(UUID.randomUUID().toString().getBytes());
                return;
            }
            fd.read(UUID.randomUUID().toString().getBytes());
        });
    }

    @Test
    public void binaryStream() {
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

    @Test
    public void memoryStream() {
        MemoryStream stream = new MemoryStream(32, false, true);
        testSeekStream(stream);

        stream.setPosition(0L);
        for (int i = 0; i < 40; i++) {
            stream.write(i);
        }
        System.out.printf("Position=%s, Length=%s, Capacity=%s%n", stream.getPosition(),
                stream.getLength(), stream.getBuffer().writerIndex());

        stream.setPosition(0L);
        System.out.println(stream.read());

        IOStream<?, ?> serializeStream = Serializer.DEFAULT.serialize(stream);
        serializeStream.setPosition(0);
        MemoryStream newStream = Serializer.DEFAULT.deserialize(serializeStream);
        newStream.setPosition(0L);
        byte[] bytes = newStream.toArray();
        for (int i = 0; i < 40; i++) {
            assert bytes[i] == i;
        }
    }

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
        for (File p : Files.listFiles(baseDir, false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listFiles(baseDir, true)) {
            System.out.println(p);
        }
    }

    @Test
    public void listDirectories() {
        Path path = Files.path(baseDir);
        System.out.println(path.getRoot());
        System.out.println(path.getFileName());
        System.out.println("---");
        for (File p : Files.listDirectories(baseDir, false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listDirectories(baseDir, true)) {
            System.out.println(p);
        }
    }

    @Test
    public void serialize() {
        //json
        GirlBean girlBean = new GirlBean();
        girlBean.setAge(8);
        IOStream<?, ?> serialize = Serializer.DEFAULT.serialize(girlBean);
        serialize.setPosition(0);
        GirlBean deGirl = Serializer.DEFAULT.deserialize(serialize);
        assert girlBean.equals(deGirl);
    }
}
