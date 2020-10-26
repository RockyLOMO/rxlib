package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Contract;
import org.rx.io.BinaryStream;
import org.rx.io.Files;
import org.rx.io.MemoryStream;
import org.rx.test.bean.PersonBean;
import org.rx.util.Helper;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class IOTester {
    @SneakyThrows
    @Test
    public void excel() {
        String path = "D:/data3.xlsx";
        Map<String, List<Object[]>> map = Helper.readExcel(new FileInputStream(path), false);
        for (Map.Entry<String, List<Object[]>> entry : map.entrySet()) {
            System.out.println(entry.getKey());
//            System.out.println(toJsonString(entry.getValue()));
            System.out.println();
        }
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
        bean.setMoney(250L);
        stream.setPosition(0);
        stream.writeObject(bean);

        stream.setPosition(0);
        PersonBean newBean = stream.readObject();

        System.out.println(Contract.toJsonString(bean));
        System.out.println(Contract.toJsonString(newBean));
    }

    @Test
    public void memoryStream() {
        MemoryStream stream = new MemoryStream(32, true);
        for (int i = 0; i < 5; i++) {
            stream.write(i);
        }
        System.out.println(String.format("Position=%s, Length=%s, Capacity=%s", stream.getPosition(),
                stream.getLength(), stream.getBuffer().length));

        stream.write(new byte[30]);
        System.out.println(String.format("Position=%s, Length=%s, Capacity=%s", stream.getPosition(),
                stream.getLength(), stream.getBuffer().length));

        stream.setPosition(0);
        System.out.println(stream.read());

        byte[] serialize = App.serialize(stream);
        MemoryStream newStream = App.deserialize(serialize);
        byte[] bytes = newStream.toArray();
        for (int i = 0; i < 5; i++) {
            assert bytes[i] == i;
        }
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
