package org.rx;

import lombok.Data;
import org.rx.util.BeanMapper;
import org.rx.util.RestClient;

import java.math.BigDecimal;
import java.util.Date;

public class Tester {
    public static void main(String[] args) {
        System.out.println("--rx--");
        //        SourceBean sb = new SourceBean();
        //        App.changeType(sb, TargetBean.class);

        //        String sd = "2017";
        //        App.changeType(sd, Date.class);
    }

    private static void testRest() {
        String proxy = null;
        proxy = "127.0.0.1:8888";
        RestApi client = RestClient.create(RestApi.class, "http://localhost:8081", proxy, true);
        System.out.println(client.getClass());
        client.test();
        client.add(1, 1);
        client.login("Rocky", "abc123");
        ObjectParam p = new ObjectParam();
        p.setA(12);
        p.setB(12);
        client.add2(p);
    }

    private static void testMap() {
        BeanMapper mapper = new BeanMapper();
        mapper.setConfig(SourceBean.class, TargetBean.class, p -> {
            switch (p) {
                case "setInfo":
                    return "getName";
            }
            return null;
        }, "setLuckyNum");

        SourceBean f = new SourceBean();
        f.setName("HW ");
        f.setAge(100);
        f.setMoney(200L);
        TargetBean t = new TargetBean();
        t.setKids(10L);
        mapper.map(f, t, BeanMapper.Flags.TrimString | BeanMapper.Flags.SkipNull);
        System.out.println(t);
    }

    @Data
    public static class SourceBean {
        private String  name;
        private int     age;
        private boolean sex;
        private Long    money;
        private Long    kids;
    }

    @Data
    public static class TargetBean {
        private String     name;
        private String     age;
        private int        luckyNum;
        private BigDecimal money;
        private String     info;
        private Long       kids;
    }
}
