package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.annotation.ErrorCode;
import org.rx.beans.$;
import org.rx.core.App;
import org.rx.core.Contract;
import org.rx.core.SystemException;
import org.rx.io.BinaryStream;
import org.rx.io.MemoryStream;
import org.rx.test.bean.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.eq;
import static org.rx.core.Contract.values;

public class RxTester {
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

    @Test
    public void shorterUUID() {
        UUID id = UUID.randomUUID();
        String sid = App.toShorterUUID(id);
        UUID id2 = App.fromShorterUUID(sid);
        System.out.printf(sid);
        assert id.equals(id2);
    }

    @Test
    @ErrorCode(messageKeys = {"$x"})
    @ErrorCode(cause = IllegalArgumentException.class, messageKeys = {"$x"})
    public void testCode() {
        System.out.println(App.getBootstrapPath());

        String val = "rx";
        SystemException ex = new SystemException(values(val));
        assert eq(ex.getFriendlyMessage(), "Method Error Code value=\"" + val + "\"");

        ex = new SystemException(values(val), new IllegalArgumentException());
        assert eq(ex.getFriendlyMessage(), "This is IllegalArgumentException! \"" + val + "\"");
        $<IllegalArgumentException> out = $();
        assert ex.tryGet(out, IllegalArgumentException.class);

        String uid = "userId";
        ex.setErrorCode(UserManager.xCode.argument, uid);
        assert eq(ex.getFriendlyMessage(), "Enum Error Code value=\"" + uid + "\"");

        try {
            String date = "2017-08-24 02:02:02";
            App.changeType(date, Date.class);

            date = "x";
            App.changeType(date, Date.class);
        } catch (SystemException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testReadSetting() {
        Map<String, Object> map = App.loadYaml("application.yml");
        System.out.println(map);

        Object o = App.readSetting("app.static.version");
        System.out.println(o);

        Object v = App.readSetting("not");
        assert v == null;

        v = App.readSetting("org.rx.test.Tester", null, SystemException.CodeFile);
        assert v instanceof Map;

        v = App.readSetting("org.rx.test.Tester.testCode<IllegalArgumentException>", null, SystemException.CodeFile);
        assert eq(v, "This is IllegalArgumentException! $x");
    }

    @Test
    @SneakyThrows
    public void testJson() {
        URL e = App.getClassLoader().getResource("jScript/");
        System.out.println(e);
        for (Path path : App.fileStream(Paths.get(e.toURI()))) {
            System.out.println(path);
            Map<String, Object> map = App.loadYaml(path.toString());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }
        }

        //        Object p = new TestServletRequest();
        //        System.out.println(Contract.toJsonString(p));
        //
        //        ErrorBean eb = new ErrorBean();
        //        System.out.println(Contract.toJsonString(eb));
        //
        //        System.out.println(Contract.toJsonString(eb));
    }
}
