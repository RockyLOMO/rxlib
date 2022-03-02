package org.rx.test;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.Arrays;
import org.rx.core.Tasks;
import org.rx.io.*;
import org.rx.net.socks.SocksUser;
import org.rx.test.bean.GirlBean;
import org.rx.test.bean.PersonBean;
import org.rx.test.common.TestUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.rx.core.App.*;
import static org.rx.core.Extends.sleep;

@Slf4j
public class IOTester {
    static final String h2Db = "~/h2/test";

    @SneakyThrows
    @Test
    public synchronized void h2Reduce() {
        EntityDatabase db = new EntityDatabase(h2Db, "yyyyMMddHH");
        db.setRollingHours(0);
        db.createMapping(PersonBean.class);
        for (int i = 0; i < 1000; i++) {
            db.save(new PersonBean());
        }
        db.compact();
//        db.clearTimeRollingFiles();
//        Tasks.setTimeout(() -> {
//            db.save(new PersonBean());
//            return true;
//        }, 2000);
//        db.dropMapping(PersonBean.class);

        DiskMonitor.INSTANCE.register(1, () -> System.out.println(1));
        DiskMonitor.INSTANCE.register(1, () -> System.out.println(11));
        DiskMonitor.INSTANCE.register(20, () -> System.out.println(20));
        DiskMonitor.INSTANCE.register(99, () -> System.out.println(99));

        wait();
    }

    @Test
    public void h2() {
        EntityDatabase db = new EntityDatabase(h2Db);
        db.setAutoUnderscoreColumnName(true);
        db.createMapping(PersonBean.class);
        db.begin();

        PersonBean entity = PersonBean.LeZhi;
        db.save(entity);

        EntityQueryLambda<PersonBean> queryLambda = new EntityQueryLambda<>(PersonBean.class).eq(PersonBean::getName, "乐之")
                .limit(1, 10);
        assert db.exists(queryLambda);
        db.commit();

        System.out.println(db.count(queryLambda));
        List<PersonBean> list = db.findBy(queryLambda);
        System.out.println(toJsonString(list));
        assert !list.isEmpty() && list.get(0).getName().equals("乐之");
        UUID pk = list.get(0).getId();
        assert db.existsById(PersonBean.class, pk);
        PersonBean byId = db.findById(PersonBean.class, pk);
        System.out.println(byId);
        assert byId != null;

        db.delete(new EntityQueryLambda<>(PersonBean.class).lt(PersonBean::getId, null));

        EntityQueryLambda<PersonBean> q = new EntityQueryLambda<>(PersonBean.class)
                .eq(PersonBean::getName, "张三")
                .in(PersonBean::getIndex, 1, 2, 3)
                .between(PersonBean::getAge, 6, 14)
                .notLike(PersonBean::getName, "王%");
        q.and(q.newClause()
                        .ne(PersonBean::getAge, 10)
                        .ne(PersonBean::getAge, 11))
                .or(q.newClause()
                        .ne(PersonBean::getAge, 12)
                        .ne(PersonBean::getAge, 13).orderByDescending(PersonBean::getMoney)).orderBy(PersonBean::getAge)
                .limit(100);
        System.out.println(q.toString());
        List<Object> params = new ArrayList<>();
        System.out.println(q.toString(params));
        System.out.println(toJsonString(params));
    }

    @Test
    public void releaseBuffer() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64);
        IOStream.release(buffer);
    }

    final byte[] content = "Hello world, 王湵范 & wanglezhi!".getBytes();

    @SneakyThrows
    @Test
    public void kvApi() {
        KeyValueStoreConfig conf = tstConf();
        conf.setApiPort(8070);
        conf.setApiPassword("wyf");
        conf.setApiReturnJson(true);
        KeyValueStore<String, SocksUser> kv = new KeyValueStore<>(conf);
        SocksUser r = new SocksUser("rocky");
        r.setPassword("202002");
        r.setMaxIpCount(-1);
        kv.put(r.getUsername(), r);

        System.in.read();
    }

    @Test
    public void kvIterator() {
        KeyValueStoreConfig conf = tstConf();
        conf.setIteratorPrefetchCount(4);
        KeyValueStore<Integer, String> kv = new KeyValueStore<>(conf);

        for (int i = 0; i < 100; i++) {
            kv.put(i, i + " " + DateTime.now().toString());
        }

        assert kv.size() == 100;
        int j = 99;
        for (Map.Entry<Integer, String> entry : kv) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
            assert j == entry.getKey();
            assert entry.getValue().startsWith(j + " ");
            j--;
        }
    }

    @Test
    public void kvZip() {
        KeyValueStoreConfig conf = tstConf();
        KeyValueStore<Integer, PersonBean> kv = new KeyValueStore<>(conf);
        kv.put(0, PersonBean.YouFan);
        kv.put(1, PersonBean.LeZhi);

        assert kv.get(0).equals(PersonBean.YouFan);
        assert kv.get(1).equals(PersonBean.LeZhi);
    }

    @Test
    public void kvDbAsync() {
        KeyValueStoreConfig conf = tstConf();
        KeyValueStore<Integer, String> kv = new KeyValueStore<>(conf);
        int loopCount = 10000;
        TestUtil.invokeAsync("kvdb", i -> {
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
        KeyValueStoreConfig conf = tstConf();
        KeyValueStore<Integer, String> kv = new KeyValueStore<>(conf);
        kv.clear();
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

        int mk = 1001, mk2 = 1002, mk3 = 1003;

        kv.put(mk, null);
        assert kv.get(mk) == null;

        kv.put(mk2, "a");
        log.info("remove {} {}", mk2, kv.remove(mk2, "a"));

        assert kv.get(mk3) == null;
        kv.putBehind(mk3, "1");
        sleep(2000);
        assert kv.get(mk3).equals("1");
        kv.putBehind(mk3, "2");
        assert kv.get(mk3).equals("2");
        kv.putBehind(mk3, "3");
        sleep(2000);

        kv.close();
    }

    private KeyValueStoreConfig tstConf() {
        KeyValueStoreConfig conf = KeyValueStoreConfig.defaultConfig();
        conf.setLogGrowSize(1024 * 1024);
        conf.setIndexGrowSize(1024 * 32);
        return conf;
    }

    @SneakyThrows
    @Test
    public void zipStream() {
        int loopCount = 4;

        MemoryStream stream = new MemoryStream();
        GZIPStream out = new GZIPStream(stream);
        for (int i = 0; i < loopCount; i++) {
            out.write(content);
        }
        out.finish();

        System.out.println(content.length * loopCount);
        System.out.println(stream.toArray().length);

        byte[] outBytes = out.toArray();
        System.out.println(new String(outBytes));
        System.out.println(new String(outBytes).length());
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
        buf = Bytes.directBuffer(buf.writerIndex());
        long read = stream.read(buf);
        assert stream.getPosition() == read;
        System.out.println(buf.toString(StandardCharsets.UTF_8));

        FileStream fs = new FileStream(TConfig.path("mmap.txt"));
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
        MemoryStream stream = new MemoryStream(32, false);
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
        IOStream<?, ?> newStream = stream.deepClone();
        assert pos == newStream.getPosition() && len == newStream.getLength();
    }

    @Test
    public void listFiles() {
        for (File p : Files.listFiles(TConfig.BASE_DIR, false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listFiles(TConfig.BASE_DIR, true)) {
            System.out.println(p);
        }
    }

    @Test
    public void listDirectories() {
        Path path = Paths.get(TConfig.BASE_DIR);
        System.out.println(path.getRoot());
        System.out.println(path.getFileName());
        System.out.println("---");
        for (File p : Files.listDirectories(TConfig.BASE_DIR, false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (File p : Files.listDirectories(TConfig.BASE_DIR, true)) {
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
