package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.Contract;
import org.rx.io.*;
import org.rx.test.bean.PersonBean;

import java.io.*;
import java.nio.file.Path;

public class IOTester {
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
        bean.setMoney(250L);
        stream.setPosition(0);
        stream.writeObject(bean);

        stream.setPosition(0);
        PersonBean newBean = stream.readObject();

        System.out.println(Contract.toJsonString(bean));
        System.out.println(Contract.toJsonString(newBean));
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
        writer.write("more..".getBytes());
        writer.flush();
        stream.setPosition(0L);
        System.out.println(IOStream.readString(reader));
        assert stream.getLength() > len;

        stream.setPosition(1L);
        assert reader.available() == stream.getLength() - 1L;
    }

    @Test
    public void memoryStream() {
        MemoryStream stream = new MemoryStream(32, true);
        testSeekStream(stream);

        stream.setPosition(0L);
        for (int i = 0; i < 40; i++) {
            stream.write(i);
        }
        System.out.println(String.format("Position=%s, Length=%s, Capacity=%s", stream.getPosition(),
                stream.getLength(), stream.getBuffer().length));

        stream.setPosition(0L);
        System.out.println(stream.read());

        IOStream<?, ?> serializeStream = IOStream.serialize(stream);
        MemoryStream newStream = IOStream.deserialize(serializeStream);
        newStream.setPosition(0L);
        byte[] bytes = newStream.toArray();
        for (int i = 0; i < 40; i++) {
            assert bytes[i] == i;
        }
    }

    private void testSeekStream(IOStream stream) {
        byte[] content = "hello world!".getBytes();
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
        IOStream newStream = App.deepClone(stream);
        assert pos == newStream.getPosition() && len == newStream.getLength();
    }

    @Test
    public void listFiles() {
        for (Path p : Files.listFiles(Files.path("/"), false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (Path p : Files.listFiles(Files.path("/"), true)) {
            System.out.println(p);
        }
    }

    @Test
    public void listDirectories() {
        Path path = Files.path("/a/1.txt");
        System.out.println(path.getRoot());
        System.out.println(path.getFileName());
        System.out.println("---");
        for (Path p : Files.listDirectories(Files.path("/"), false)) {
            System.out.println(p);
        }
        System.out.println("---");
        for (Path p : Files.listDirectories(Files.path("/"), true)) {
            System.out.println(p);
        }
    }
}
