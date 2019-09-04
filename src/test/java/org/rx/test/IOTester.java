package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.core.Arrays;
import org.rx.core.Contract;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.io.BinaryStream;
import org.rx.io.MemoryStream;
import org.rx.test.bean.SourceBean;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.toJsonString;

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

        SourceBean bean = new SourceBean();
        bean.setName("hello");
        bean.setAge(12);
        bean.setMoney(250L);
        stream.setPosition(0);
        stream.writeObject(bean);

        stream.setPosition(0);
        SourceBean newBean = stream.readObject();

        System.out.println(Contract.toJsonString(bean));
        System.out.println(Contract.toJsonString(newBean));
    }

    @Test
    public void testStream() {
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
    }
}
