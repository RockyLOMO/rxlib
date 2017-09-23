package org.rx.test.util;

import org.junit.Test;
import org.rx.test.bean.RestApi;
import org.rx.test.bean.RestParam;
import org.rx.test.bean.SourceBean;
import org.rx.test.bean.TargetBean;
import org.rx.util.BeanMapper;
import org.rx.util.RestClient;

public class UtilTester {
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
