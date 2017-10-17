package org.rx.test;

import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.rx.test.bean.RestApi;
import org.rx.test.bean.RestParam;
import org.rx.test.bean.SourceBean;
import org.rx.test.bean.TargetBean;
import org.rx.util.BeanMapper;
import org.rx.util.BinaryStream;
import org.rx.util.MemoryStream;
import org.rx.feign.RestClient;

public class UtilTester {
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

        System.out.println(JSON.toJSONString(bean));
        System.out.println(JSON.toJSONString(newBean));
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
    public void testMapper() {
        BeanMapper mapper = new BeanMapper();
        mapper.setConfig(SourceBean.class, TargetBean.class, p -> {
            switch (p) {
                case "info":
                    return "name";
            }
            return null;
        }, "luckyNum");

        SourceBean f = new SourceBean();
        f.setName("HW ");
        f.setAge(100);
        f.setMoney(200L);
        TargetBean t = new TargetBean();
        t.setKids(10L);
        mapper.map(f, t, BeanMapper.Flags.TrimString | BeanMapper.Flags.SkipNull);
        System.out.println(t);
        assert t.getName().equals(f.getName().trim());
        assert t.getInfo().equals(f.getName().trim());
        assert t.getLuckyNum() == 0;
    }

    @Test
    public void testRest() {
        String proxy = null;
        proxy = "127.0.0.1:8888";
        RestApi client = RestClient.create(RestApi.class, "http://localhost:8081", proxy, true);
        System.out.println(client.getClass());
        client.test();
        client.add(1, 1);
        client.login("Rocky", "abc123");
        RestParam p = new RestParam();
        p.setA(12);
        p.setB(12);
        client.add2(p);
    }
}
